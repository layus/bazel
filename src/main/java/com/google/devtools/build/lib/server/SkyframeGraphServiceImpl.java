// Copyright 2025 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.server;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.GetGraphInfoRequest;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.GetGraphInfoResponse;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.GetNodeRequest;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.GetNodeResponse;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.GetNodesRequest;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.GetNodesResponse;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.GetRootsRequest;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.ListNodesRequest;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.NodeFieldFilter;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.NodeInfo;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.RefreshIndexRequest;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphProtos.RefreshIndexResponse;
import com.google.devtools.build.lib.skyframe.graph.SkyframeGraphServiceGrpc;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.SkyKey;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * gRPC service for querying the Skyframe in-memory graph.
 *
 * <p>All data is pre-computed during {@link #refreshIndex} (or the {@code RefreshIndex} RPC). No
 * expensive computation happens when serving individual requests.
 */
public class SkyframeGraphServiceImpl
    extends SkyframeGraphServiceGrpc.SkyframeGraphServiceImplBase {

  private final Supplier<InMemoryGraph> graphSupplier;

  @Nullable private volatile GraphIndex index;

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  public SkyframeGraphServiceImpl(Supplier<InMemoryGraph> graphSupplier) {
    this.graphSupplier = graphSupplier;
    logger.atInfo().log("SkyframeGraphServiceImpl created");
  }

  // -- Pre-computed snapshot ---------------------------------------------------

  /** Immutable snapshot of the graph built at index time. */
  private static final class GraphIndex {
    /** All done node entries, indexed by sequential integer ID. */
    final ArrayList<InMemoryNodeEntry> entries;
    /** Mapping from SkyKey to integer ID. */
    final HashMap<SkyKey, Integer> keyToId;
    /** Pre-resolved direct dep IDs per node. null elements for nodes that don't keep edges. */
    final int[][] depIds;
    /** IDs of root nodes (zero reverse deps at index time). */
    final int[] rootIds;
    /** Cached canonical names per node. */
    final String[] canonicalNames;
    /** Cached function type names per node. */
    final String[] functionTypes;
    /** Whether the graph keeps edges. */
    final boolean keepsEdges;

    GraphIndex(
        ArrayList<InMemoryNodeEntry> entries,
        HashMap<SkyKey, Integer> keyToId,
        int[][] depIds,
        int[] rootIds,
        String[] canonicalNames,
        String[] functionTypes,
        boolean keepsEdges) {
      this.entries = entries;
      this.keyToId = keyToId;
      this.depIds = depIds;
      this.rootIds = rootIds;
      this.canonicalNames = canonicalNames;
      this.functionTypes = functionTypes;
      this.keepsEdges = keepsEdges;
    }
  }

  /**
   * Builds the index from the live graph. Called by the {@code RefreshIndex} RPC or programmatically
   * after a build completes.
   */
  public RefreshIndexResponse refreshIndex() {
    InMemoryGraph graph = graphSupplier.get();
    if (graph == null) {
      throw Status.UNAVAILABLE.withDescription("Skyframe graph not available").asRuntimeException();
    }

    // Pass 1: collect all done entries, assign IDs, cache names.
    ArrayList<InMemoryNodeEntry> entries = new ArrayList<>();
    HashMap<SkyKey, Integer> keyToId = new HashMap<>();
    boolean keepsEdges = true;

    for (InMemoryNodeEntry entry : graph.getAllNodeEntries()) {
      if (!entry.isDone()) {
        continue;
      }
      int id = entries.size();
      keyToId.put(entry.getKey(), id);
      entries.add(entry);
      if (!entry.keepsEdges()) {
        keepsEdges = false;
      }
    }

    int nodeCount = entries.size();
    String[] canonicalNames = new String[nodeCount];
    String[] functionTypes = new String[nodeCount];
    int[][] depIds = new int[nodeCount][];

    for (int i = 0; i < nodeCount; i++) {
      InMemoryNodeEntry entry = entries.get(i);
      SkyKey key = entry.getKey();
      canonicalNames[i] = key.getCanonicalName();
      functionTypes[i] = key.functionName().getName();

      if (entry.keepsEdges()) {
        Iterable<SkyKey> directDeps = entry.getDirectDeps();
        ArrayList<Integer> resolvedDeps = new ArrayList<>();
        for (SkyKey dep : directDeps) {
          Integer depId = keyToId.get(dep);
          if (depId != null) {
            resolvedDeps.add(depId);
          }
        }
        int[] arr = new int[resolvedDeps.size()];
        for (int j = 0; j < resolvedDeps.size(); j++) {
          arr[j] = resolvedDeps.get(j);
        }
        depIds[i] = arr;
      }
    }

    // Pass 2: identify roots (nodes with zero reverse deps).
    ArrayList<Integer> roots = new ArrayList<>();
    for (int i = 0; i < nodeCount; i++) {
      InMemoryNodeEntry entry = entries.get(i);
      if (entry.keepsEdges()) {
        Collection<SkyKey> rdeps = entry.getReverseDepsForDoneEntry();
        if (rdeps.isEmpty()) {
          roots.add(i);
        }
      }
    }
    int[] rootIds = new int[roots.size()];
    for (int i = 0; i < roots.size(); i++) {
      rootIds[i] = roots.get(i);
    }

    GraphIndex newIndex =
        new GraphIndex(entries, keyToId, depIds, rootIds, canonicalNames, functionTypes, keepsEdges);
    this.index = newIndex;

    return RefreshIndexResponse.newBuilder()
        .setTotalNodeCount(nodeCount)
        .setRootCount(rootIds.length)
        .build();
  }

  // -- RPC implementations ----------------------------------------------------

  @Override
  public void refreshIndex(
      RefreshIndexRequest request, StreamObserver<RefreshIndexResponse> responseObserver) {
    try {
      RefreshIndexResponse response = refreshIndex();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(
          Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
    }
  }

  @Override
  public void getNode(GetNodeRequest request, StreamObserver<GetNodeResponse> responseObserver) {
    GraphIndex idx = requireIndex(responseObserver);
    if (idx == null) {
      return;
    }
    int id = (int) request.getId();
    if (id < 0 || id >= idx.entries.size()) {
      responseObserver.onError(
          Status.NOT_FOUND
              .withDescription("Node ID " + id + " out of range [0, " + idx.entries.size() + ")")
              .asRuntimeException());
      return;
    }
    NodeInfo info = buildNodeInfo(idx, id, request.getFilter());
    responseObserver.onNext(GetNodeResponse.newBuilder().setNode(info).build());
    responseObserver.onCompleted();
  }

  @Override
  public void getNodes(
      GetNodesRequest request, StreamObserver<GetNodesResponse> responseObserver) {
    GraphIndex idx = requireIndex(responseObserver);
    if (idx == null) {
      return;
    }
    GetNodesResponse.Builder resp = GetNodesResponse.newBuilder();
    NodeFieldFilter filter = request.getFilter();
    for (long idLong : request.getIdsList()) {
      int id = (int) idLong;
      if (id < 0 || id >= idx.entries.size()) {
        responseObserver.onError(
            Status.NOT_FOUND
                .withDescription(
                    "Node ID " + id + " out of range [0, " + idx.entries.size() + ")")
                .asRuntimeException());
        return;
      }
      resp.addNodes(buildNodeInfo(idx, id, filter));
    }
    responseObserver.onNext(resp.build());
    responseObserver.onCompleted();
  }

  @Override
  public void getRoots(GetRootsRequest request, StreamObserver<NodeInfo> responseObserver) {
    GraphIndex idx = requireIndex(responseObserver);
    if (idx == null) {
      return;
    }
    NodeFieldFilter filter = request.getFilter();
    for (int rootId : idx.rootIds) {
      responseObserver.onNext(buildNodeInfo(idx, rootId, filter));
    }
    responseObserver.onCompleted();
  }

  @Override
  public void getGraphInfo(
      GetGraphInfoRequest request, StreamObserver<GetGraphInfoResponse> responseObserver) {
    GraphIndex idx = requireIndex(responseObserver);
    if (idx == null) {
      return;
    }
    responseObserver.onNext(
        GetGraphInfoResponse.newBuilder()
            .setTotalNodeCount(idx.entries.size())
            .setRootCount(idx.rootIds.length)
            .setKeepsEdges(idx.keepsEdges)
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void listNodes(ListNodesRequest request, StreamObserver<NodeInfo> responseObserver) {
    GraphIndex idx = requireIndex(responseObserver);
    if (idx == null) {
      return;
    }
    String funcFilter = request.getFunctionTypeFilter();
    boolean hasFilter = !funcFilter.isEmpty();
    NodeFieldFilter filter = request.getFilter();
    for (int i = 0; i < idx.entries.size(); i++) {
      if (hasFilter && !idx.functionTypes[i].equals(funcFilter)) {
        continue;
      }
      responseObserver.onNext(buildNodeInfo(idx, i, filter));
    }
    responseObserver.onCompleted();
  }

  // -- Helpers ----------------------------------------------------------------

  @Nullable
  private GraphIndex requireIndex(StreamObserver<?> responseObserver) {
    GraphIndex idx = this.index;
    if (idx == null) {
      responseObserver.onError(
          Status.FAILED_PRECONDITION
              .withDescription("Index not built. Call RefreshIndex first.")
              .asRuntimeException());
      return null;
    }
    return idx;
  }

  /** Returns true if the filter has no fields set (i.e. is the default instance). */
  private static boolean isDefaultFilter(NodeFieldFilter filter) {
    return filter.equals(NodeFieldFilter.getDefaultInstance());
  }

  private static NodeInfo buildNodeInfo(GraphIndex idx, int id, NodeFieldFilter filter) {
    // When no filter is provided (default instance with all booleans false), include everything.
    boolean includeAll = isDefaultFilter(filter);
    InMemoryNodeEntry entry = idx.entries.get(id);
    NodeInfo.Builder builder = NodeInfo.newBuilder();
    builder.setId(id);
    builder.setIsDone(entry.isDone());
    builder.setKeepsEdges(entry.keepsEdges());

    if (includeAll || filter.getIncludeCanonicalName()) {
      builder.setCanonicalName(idx.canonicalNames[id]);
    }
    if (includeAll || filter.getIncludeFunctionType()) {
      builder.setFunctionType(idx.functionTypes[id]);
    }
    if ((includeAll || filter.getIncludeDirectDeps()) && idx.depIds[id] != null) {
      for (int depId : idx.depIds[id]) {
        builder.addDirectDepIds(depId);
      }
    }
    if (includeAll || filter.getIncludeLifecycleState()) {
      NodeEntry.LifecycleState state = entry.getLifecycleState();
      builder.setLifecycleState(state.name());
    }
    return builder.build();
  }
}
