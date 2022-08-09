/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.consensus.statemachine;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.StepTracker;
import org.apache.iotdb.commons.consensus.DataRegionId;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.consensus.common.DataSet;
import org.apache.iotdb.consensus.common.request.BatchIndexedConsensusRequest;
import org.apache.iotdb.consensus.common.request.IConsensusRequest;
import org.apache.iotdb.consensus.common.request.IndexedConsensusRequest;
import org.apache.iotdb.consensus.multileader.wal.GetConsensusReqReaderPlan;
import org.apache.iotdb.db.consensus.statemachine.visitor.DataExecutionVisitor;
import org.apache.iotdb.db.engine.StorageEngineV2;
import org.apache.iotdb.db.engine.snapshot.SnapshotLoader;
import org.apache.iotdb.db.engine.snapshot.SnapshotTaker;
import org.apache.iotdb.db.engine.storagegroup.DataRegion;
import org.apache.iotdb.db.mpp.execution.fragment.FragmentInstanceManager;
import org.apache.iotdb.db.mpp.plan.planner.plan.FragmentInstance;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertMultiTabletsNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertRowNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertRowsNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertRowsOfOneDeviceNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.write.InsertTabletNode;
import org.apache.iotdb.rpc.TSStatusCode;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DataRegionStateMachine extends BaseStateMachine {

  private static final Logger logger = LoggerFactory.getLogger(DataRegionStateMachine.class);

  private static final FragmentInstanceManager QUERY_INSTANCE_MANAGER =
      FragmentInstanceManager.getInstance();

  private DataRegion region;

  private static final int MAX_REQUEST_CACHE_SIZE = 5;
  private static final long CACHE_WINDOW_TIME_IN_MS = 10_000;

  private final Lock queueLock = new ReentrantLock();
  private final Condition queueSortCondition = queueLock.newCondition();
  private final PriorityQueue<InsertNodeWrapper> requestCache;
  private long nextSyncIndex = -1;

  public DataRegionStateMachine(DataRegion region) {
    this.region = region;
    this.requestCache = new PriorityQueue<>();
  }

  @Override
  public void start() {}

  @Override
  public void stop() {}

  @Override
  public boolean takeSnapshot(File snapshotDir) {
    try {
      return new SnapshotTaker(region).takeFullSnapshot(snapshotDir.getAbsolutePath(), true);
    } catch (Exception e) {
      logger.error(
          "Exception occurs when taking snapshot for {}-{} in {}",
          region.getLogicalStorageGroupName(),
          region.getDataRegionId(),
          snapshotDir,
          e);
      return false;
    }
  }

  @Override
  public void loadSnapshot(File latestSnapshotRootDir) {
    DataRegion newRegion =
        new SnapshotLoader(
                latestSnapshotRootDir.getAbsolutePath(),
                region.getLogicalStorageGroupName(),
                region.getDataRegionId())
            .loadSnapshotForStateMachine();
    if (newRegion == null) {
      logger.error("Fail to load snapshot from {}", latestSnapshotRootDir);
      return;
    }
    this.region = newRegion;
    try {
      StorageEngineV2.getInstance()
          .setDataRegion(new DataRegionId(Integer.parseInt(region.getDataRegionId())), region);
    } catch (Exception e) {
      logger.error("Exception occurs when replacing data region in storage engine.", e);
    }
  }

  private TSStatus cacheAndInsertLatestNode(InsertNodeWrapper insertNodeWrapper) {
    long cacheRequestStartTime = System.nanoTime();
    logger.info(
        "region = {}, queue size = {}, syncIndex = {}, nextSyncIndex = {}",
        region.getDataRegionId(),
        requestCache.size(),
        insertNodeWrapper.startSyncIndex,
        nextSyncIndex);
    queueLock.lock();
    try {
      requestCache.add(insertNodeWrapper);
      // If the peek is not hold by current thread, it should notify the corresponding thread to
      // process the peek when the queue is full
      if (requestCache.size() == MAX_REQUEST_CACHE_SIZE
          && requestCache.peek().getStartSyncIndex() != insertNodeWrapper.getStartSyncIndex()) {
        queueSortCondition.signalAll();
      }
      while (true) {
        if (insertNodeWrapper.getStartSyncIndex() == nextSyncIndex) {
          requestCache.remove(insertNodeWrapper);
          nextSyncIndex = insertNodeWrapper.getEndSyncIndex() + 1;
          break;
        }
        if (requestCache.size() == MAX_REQUEST_CACHE_SIZE
            && requestCache.peek().getStartSyncIndex() == insertNodeWrapper.getStartSyncIndex()) {
          requestCache.remove();
          nextSyncIndex = insertNodeWrapper.getEndSyncIndex() + 1;
          break;
        }
        try {
          boolean timeout =
              !queueSortCondition.await(CACHE_WINDOW_TIME_IN_MS, TimeUnit.MILLISECONDS);
          if (timeout) {
            logger.info(
                "waiting target request timeout. current index: {}, target index: {}",
                insertNodeWrapper.getStartSyncIndex(),
                nextSyncIndex);
            break;
          }
        } catch (InterruptedException e) {
          logger.warn(
              "current waiting is interrupted. SyncIndex: {}. Exception: {}",
              insertNodeWrapper.getStartSyncIndex(),
              e);
          Thread.currentThread().interrupt();
        }
      }
      StepTracker.trace("cacheAndQueueRequest", cacheRequestStartTime, System.nanoTime());
      logger.info(
          "queue size {}, startSyncIndex = {}, endSyncIndex = {}",
          requestCache.size(),
          insertNodeWrapper.getStartSyncIndex(),
          insertNodeWrapper.getEndSyncIndex());
      List<TSStatus> subStatus = new LinkedList<>();
      for (InsertNode insertNode : insertNodeWrapper.getInsertNodes()) {
        subStatus.add(write(insertNode));
      }
      queueSortCondition.signalAll();
      return new TSStatus().setSubStatus(subStatus);
    } finally {
      queueLock.unlock();
    }
  }

  private static class InsertNodeWrapper implements Comparable<InsertNodeWrapper> {
    private final long startSyncIndex;
    private final long endSyncIndex;
    private final List<InsertNode> insertNodes;

    public InsertNodeWrapper(long startSyncIndex, long endSyncIndex) {
      this.startSyncIndex = startSyncIndex;
      this.endSyncIndex = endSyncIndex;
      this.insertNodes = new LinkedList<>();
    }

    @Override
    public int compareTo(@NotNull InsertNodeWrapper o) {
      return Long.compare(startSyncIndex, o.startSyncIndex);
    }

    public void add(InsertNode insertNode) {
      this.insertNodes.add(insertNode);
    }

    public long getStartSyncIndex() {
      return startSyncIndex;
    }

    public long getEndSyncIndex() {
      return endSyncIndex;
    }

    public List<InsertNode> getInsertNodes() {
      return insertNodes;
    }
  }

  private InsertNodeWrapper deserializeAndWrap(BatchIndexedConsensusRequest batchRequest) {
    InsertNodeWrapper insertNodeWrapper =
        new InsertNodeWrapper(batchRequest.getStartSyncIndex(), batchRequest.getEndSyncIndex());
    for (IndexedConsensusRequest indexedRequest : batchRequest.getRequests()) {
      insertNodeWrapper.add(grabInsertNode(indexedRequest));
    }
    return insertNodeWrapper;
  }

  private InsertNode grabInsertNode(IndexedConsensusRequest indexedRequest) {
    List<InsertNode> insertNodes = new ArrayList<>(indexedRequest.getRequests().size());
    for (IConsensusRequest req : indexedRequest.getRequests()) {
      // PlanNode in IndexedConsensusRequest should always be InsertNode
      InsertNode innerNode = (InsertNode) getPlanNode(req);
      innerNode.setSearchIndex(indexedRequest.getSearchIndex());
      insertNodes.add(innerNode);
    }
    return mergeInsertNodes(insertNodes);
  }

  @Override
  public TSStatus write(IConsensusRequest request) {
    PlanNode planNode;
    try {
      if (request instanceof IndexedConsensusRequest) {
        IndexedConsensusRequest indexedRequest = (IndexedConsensusRequest) request;
        planNode = grabInsertNode(indexedRequest);
      } else if (request instanceof BatchIndexedConsensusRequest) {
        InsertNodeWrapper insertNodeWrapper =
            deserializeAndWrap((BatchIndexedConsensusRequest) request);
        return cacheAndInsertLatestNode(insertNodeWrapper);
      } else {
        planNode = getPlanNode(request);
      }
      return write(planNode);
    } catch (IllegalArgumentException e) {
      logger.error(e.getMessage(), e);
      return new TSStatus(TSStatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
    }
  }

  /**
   * Merge insert nodes sharing same search index ( e.g. tablet-100, tablet-100, tablet-100 will be
   * merged to one multi-tablet). <br>
   * Notice: the continuity of insert nodes sharing same search index should be protected by the
   * upper layer.
   */
  private InsertNode mergeInsertNodes(List<InsertNode> insertNodes) {
    int size = insertNodes.size();
    if (size == 0) {
      throw new RuntimeException();
    }
    if (size == 1) {
      return insertNodes.get(0);
    }

    InsertNode result;
    if (insertNodes.get(0) instanceof InsertTabletNode) { // merge to InsertMultiTabletsNode
      List<Integer> index = new ArrayList<>(size);
      List<InsertTabletNode> insertTabletNodes = new ArrayList<>(size);
      int i = 0;
      for (InsertNode insertNode : insertNodes) {
        insertTabletNodes.add((InsertTabletNode) insertNode);
        index.add(i);
        i++;
      }
      result =
          new InsertMultiTabletsNode(insertNodes.get(0).getPlanNodeId(), index, insertTabletNodes);
    } else { // merge to InsertRowsNode or InsertRowsOfOneDeviceNode
      boolean sameDevice = true;
      PartialPath device = insertNodes.get(0).getDevicePath();
      List<Integer> index = new ArrayList<>(size);
      List<InsertRowNode> insertRowNodes = new ArrayList<>(size);
      int i = 0;
      for (InsertNode insertNode : insertNodes) {
        if (sameDevice && !insertNode.getDevicePath().equals(device)) {
          sameDevice = false;
        }
        insertRowNodes.add((InsertRowNode) insertNode);
        index.add(i);
        i++;
      }
      result =
          sameDevice
              ? new InsertRowsOfOneDeviceNode(
                  insertNodes.get(0).getPlanNodeId(), index, insertRowNodes)
              : new InsertRowsNode(insertNodes.get(0).getPlanNodeId(), index, insertRowNodes);
    }
    result.setSearchIndex(insertNodes.get(0).getSearchIndex());
    result.setDevicePath(insertNodes.get(0).getDevicePath());
    return result;
  }

  protected TSStatus write(PlanNode planNode) {
    long startTime = System.nanoTime();
    try {
      return planNode.accept(new DataExecutionVisitor(), region);
    } finally {
      StepTracker.trace("StateMachineWrite", startTime, System.nanoTime());
    }
  }

  @Override
  public DataSet read(IConsensusRequest request) {
    if (request instanceof GetConsensusReqReaderPlan) {
      return region.getWALNode();
    } else {
      FragmentInstance fragmentInstance;
      try {
        fragmentInstance = getFragmentInstance(request);
      } catch (IllegalArgumentException e) {
        logger.error(e.getMessage());
        return null;
      }
      return QUERY_INSTANCE_MANAGER.execDataQueryFragmentInstance(fragmentInstance, region);
    }
  }
}
