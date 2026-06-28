/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.cluster.shards;

import org.opensearch.action.admin.cluster.state.ClusterStateRequest;
import org.opensearch.action.admin.cluster.state.ClusterStateResponse;
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.pagination.PageParams;
import org.opensearch.action.pagination.ShardPaginationStrategy;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.action.support.TimeoutTaskCancellationUtility;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.common.breaker.ResponseLimitBreachedException;
import org.opensearch.common.breaker.ResponseLimitSettings;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.NotifyOnceListener;
import org.opensearch.tasks.CancellableTask;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.node.NodeClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import static org.opensearch.common.breaker.ResponseLimitSettings.LimitEntity.SHARDS;

/**
 * Perform cat shards action
 *
 * @opensearch.internal
 */
public class TransportCatShardsAction extends HandledTransportAction<CatShardsRequest, CatShardsResponse> {

    private final NodeClient client;
    private final ResponseLimitSettings responseLimitSettings;

    @Inject
    public TransportCatShardsAction(
        NodeClient client,
        TransportService transportService,
        ActionFilters actionFilters,
        ResponseLimitSettings responseLimitSettings
    ) {
        super(CatShardsAction.NAME, transportService, actionFilters, CatShardsRequest::new);
        this.client = client;
        this.responseLimitSettings = responseLimitSettings;
    }

    @Override
    public void doExecute(Task parentTask, CatShardsRequest shardsRequest, ActionListener<CatShardsResponse> listener) {
        final ClusterStateRequest clusterStateRequest = new ClusterStateRequest();
        clusterStateRequest.setShouldCancelOnTimeout(true);
        clusterStateRequest.local(shardsRequest.local());
        clusterStateRequest.clusterManagerNodeTimeout(shardsRequest.clusterManagerNodeTimeout());
        if (Objects.isNull(shardsRequest.getPageParams())) {
            clusterStateRequest.clear().nodes(true).routingTable(true).indices(shardsRequest.getIndices());
        } else {
            clusterStateRequest.clear().nodes(true).routingTable(true).indices(shardsRequest.getIndices()).metadata(true);
        }
        assert parentTask instanceof CancellableTask;
        clusterStateRequest.setParentTask(client.getLocalNodeId(), parentTask.getId());

        ActionListener<CatShardsResponse> originalListener = new NotifyOnceListener<CatShardsResponse>() {
            @Override
            protected void innerOnResponse(CatShardsResponse catShardsResponse) {
                listener.onResponse(catShardsResponse);
            }

            @Override
            protected void innerOnFailure(Exception e) {
                listener.onFailure(e);
            }
        };
        ActionListener<CatShardsResponse> cancellableListener = TimeoutTaskCancellationUtility.wrapWithCancellationListener(
            client,
            (CancellableTask) parentTask,
            ((CancellableTask) parentTask).getCancellationTimeout(),
            originalListener,
            e -> {
                originalListener.onFailure(e);
            }
        );
        CatShardsResponse catShardsResponse = new CatShardsResponse();
        try {
            client.admin().cluster().state(clusterStateRequest, new ActionListener<ClusterStateResponse>() {
                @Override
                public void onResponse(ClusterStateResponse clusterStateResponse) {
                    validateRequestLimit(shardsRequest, clusterStateResponse, cancellableListener);
                    try {
                        ShardPaginationStrategy paginationStrategy = getPaginationStrategy(
                            shardsRequest.getPageParams(),
                            clusterStateResponse
                        );
                        catShardsResponse.setNodes(clusterStateResponse.getState().getNodes());
                        // Phase 3: when the REST layer set a routing-derivable single-column sort
                        // with a positive limit, and pagination is NOT active, we can select the
                        // top-K shards directly from cluster state. This avoids issuing
                        // IndicesStats for all shards on all nodes when most rows would be
                        // discarded by the limit anyway.
                        List<ShardRouting> selectedShards;
                        if (Objects.isNull(paginationStrategy)
                            && shardsRequest.getRoutingSortColumn() != null
                            && shardsRequest.getResponseLimit() > 0) {
                            selectedShards = selectTopKShardsByRouting(
                                clusterStateResponse.getState().routingTable().allShards(),
                                clusterStateResponse.getState().getNodes(),
                                shardsRequest.getRoutingSortColumn(),
                                shardsRequest.isRoutingSortDescending(),
                                shardsRequest.getResponseLimit()
                            );
                        } else {
                            selectedShards = Objects.isNull(paginationStrategy)
                                ? clusterStateResponse.getState().routingTable().allShards()
                                : paginationStrategy.getRequestedEntities();
                        }
                        catShardsResponse.setResponseShards(selectedShards);
                        catShardsResponse.setPageToken(Objects.isNull(paginationStrategy) ? null : paginationStrategy.getResponseToken());

                        String[] indices;
                        if (Objects.isNull(paginationStrategy)) {
                            // If top-K narrowed the shard set, narrow the IndicesStats fan-out to
                            // only the (distinct) indices that those shards belong to. For routing
                            // pushdown on, say, 5 shards across 2 indices, IndicesStats now hits
                            // 2 indices on the data nodes instead of every index in the cluster.
                            if (shardsRequest.getRoutingSortColumn() != null && shardsRequest.getResponseLimit() > 0) {
                                indices = distinctIndicesOf(selectedShards);
                            } else {
                                indices = shardsRequest.getIndices();
                            }
                        } else {
                            indices = filterClosedIndices(clusterStateResponse.getState(), paginationStrategy.getRequestedIndices());
                        }
                        // For paginated queries, if strategy outputs no shards to be returned, avoid fetching IndicesStats.
                        if (shouldSkipIndicesStatsRequest(paginationStrategy, indices)) {
                            catShardsResponse.setIndicesStatsResponse(IndicesStatsResponse.getEmptyResponse());
                            cancellableListener.onResponse(catShardsResponse);
                            return;
                        }
                        // Fast path: if the REST layer determined that no requested column (h=/s=)
                        // requires per-shard stats, skip the IndicesStats broadcast entirely. The
                        // resulting Table has null cells for stats columns, which are not displayed
                        // because they were not in h=.
                        if (shardsRequest.isIndicesStatsRequired() == false) {
                            catShardsResponse.setIndicesStatsResponse(IndicesStatsResponse.getEmptyResponse());
                            cancellableListener.onResponse(catShardsResponse);
                            return;
                        }
                        IndicesStatsRequest indicesStatsRequest = new IndicesStatsRequest();
                        indicesStatsRequest.setShouldCancelOnTimeout(true);
                        indicesStatsRequest.all();
                        indicesStatsRequest.indices(indices);
                        indicesStatsRequest.setParentTask(client.getLocalNodeId(), parentTask.getId());
                        client.admin().indices().stats(indicesStatsRequest, new ActionListener<IndicesStatsResponse>() {
                            @Override
                            public void onResponse(IndicesStatsResponse indicesStatsResponse) {
                                catShardsResponse.setIndicesStatsResponse(indicesStatsResponse);
                                cancellableListener.onResponse(catShardsResponse);
                            }

                            @Override
                            public void onFailure(Exception e) {
                                cancellableListener.onFailure(e);
                            }
                        });
                    } catch (Exception e) {
                        cancellableListener.onFailure(e);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    cancellableListener.onFailure(e);
                }
            });
        } catch (Exception e) {
            cancellableListener.onFailure(e);
        }

    }

    private ShardPaginationStrategy getPaginationStrategy(PageParams pageParams, ClusterStateResponse clusterStateResponse) {
        return Objects.isNull(pageParams) ? null : new ShardPaginationStrategy(pageParams, clusterStateResponse.getState());
    }

    private void validateRequestLimit(
        final CatShardsRequest shardsRequest,
        final ClusterStateResponse clusterStateResponse,
        final ActionListener<CatShardsResponse> listener
    ) {
        if (shardsRequest.isRequestLimitCheckSupported()
            && Objects.nonNull(clusterStateResponse)
            && Objects.nonNull(clusterStateResponse.getState())) {
            int limit = responseLimitSettings.getCatShardsResponseLimit();
            if (ResponseLimitSettings.isResponseLimitBreached(clusterStateResponse.getState().getRoutingTable(), SHARDS, limit)) {
                listener.onFailure(new ResponseLimitBreachedException("Too many shards requested.", limit, SHARDS));
            }
        }
    }

    private boolean shouldSkipIndicesStatsRequest(ShardPaginationStrategy paginationStrategy, String[] indices) {
        return Objects.nonNull(paginationStrategy) && (indices == null || indices.length == 0);
    }

    /**
     * Will be used by paginated query (_list/shards) to filter out closed indices (only consider OPEN) before fetching
     * IndicesStats. Since pagination strategy always passes concrete indices to TransportIndicesStatsAction,
     * the default behaviour of StrictExpandOpenAndForbidClosed leads to errors if closed indices are encountered.
     */
    private String[] filterClosedIndices(ClusterState clusterState, List<String> strategyIndices) {
        return strategyIndices.stream().filter(index -> {
            IndexMetadata metadata = clusterState.metadata().indices().get(index);
            return metadata != null && metadata.getState().equals(IndexMetadata.State.CLOSE) == false;
        }).toArray(String[]::new);
    }

    /**
     * Returns the distinct index names across the given shards, preserving insertion order.
     * Used to narrow the IndicesStats broadcast to only the indices the selected shards belong to.
     * Package-private for testing.
     */
    static String[] distinctIndicesOf(List<ShardRouting> shards) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (ShardRouting shard : shards) {
            if (seen.add(shard.getIndexName())) {
                out.add(shard.getIndexName());
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * Selects the top-K shards from {@code allShards} by the given routing-derivable column using a
     * bounded priority queue (O(N log K)). Tie-breaks by the original iteration index so the
     * resulting order is deterministic and matches the behavior of {@code Collections.sort} over
     * the same comparator + truncate.
     *
     * Package-private for testing.
     */
    static List<ShardRouting> selectTopKShardsByRouting(
        List<ShardRouting> allShards,
        DiscoveryNodes nodes,
        String sortColumn,
        boolean descending,
        int limit
    ) {
        if (limit >= allShards.size()) {
            // Limit not narrowing — sort the full set with the routing comparator so downstream
            // ordering is consistent with the explicit s=... parameter.
            Comparator<ShardRouting> cmp = routingComparator(sortColumn, descending, nodes);
            List<ShardRouting> sorted = new ArrayList<>(allShards);
            sorted.sort(cmp);
            return sorted;
        }
        Comparator<ShardRouting> cmp = routingComparator(sortColumn, descending, nodes);
        // Capture iteration order for deterministic tie-breaking (matches Collections.sort stability).
        ShardRouting[] arr = allShards.toArray(new ShardRouting[0]);
        // Pair each ShardRouting with its index in the original list.
        Comparator<Integer> idxCmp = (i, j) -> {
            int c = cmp.compare(arr[i], arr[j]);
            if (c != 0) return c;
            return Integer.compare(i, j);
        };
        PriorityQueue<Integer> heap = new PriorityQueue<>(limit, idxCmp.reversed());
        for (int i = 0; i < arr.length; i++) {
            if (heap.size() < limit) {
                heap.offer(i);
            } else if (idxCmp.compare(i, heap.peek()) < 0) {
                heap.poll();
                heap.offer(i);
            }
        }
        List<Integer> indices = new ArrayList<>(heap);
        indices.sort(idxCmp);
        List<ShardRouting> result = new ArrayList<>(indices.size());
        for (Integer i : indices) {
            result.add(arr[i]);
        }
        return result;
    }

    /**
     * Builds a comparator over {@link ShardRouting} for the routing-only columns supported by the
     * pushdown. Mirrors the null-ordering behavior of {@code RestTable.TableIndexComparator}
     * (nulls sort first in ascending, last in descending) so that the top-K result is observably
     * equivalent to "build full table, sort, truncate".
     */
    private static Comparator<ShardRouting> routingComparator(String sortColumn, boolean descending, DiscoveryNodes nodes) {
        Comparator<ShardRouting> base;
        switch (sortColumn) {
            case "index":
                base = Comparator.comparing(ShardRouting::getIndexName, nullsFirst(String::compareTo));
                break;
            case "shard":
                base = Comparator.comparingInt(ShardRouting::id);
                break;
            case "prirep":
                base = Comparator.comparing(TransportCatShardsAction::prirepValue, nullsFirst(String::compareTo));
                break;
            case "state":
                // Compare by enum name() for stable string ordering, matching the cell renderer.
                base = Comparator.comparing(s -> s.state() == null ? null : s.state().name(), nullsFirst(String::compareTo));
                break;
            case "node":
                base = Comparator.comparing(s -> nodeAttr(s, nodes, DiscoveryNode::getName), nullsFirst(String::compareTo));
                break;
            case "ip":
                base = Comparator.comparing(s -> nodeAttr(s, nodes, DiscoveryNode::getHostAddress), nullsFirst(String::compareTo));
                break;
            case "id":
                base = Comparator.comparing((ShardRouting s) -> s.currentNodeId(), nullsFirst(String::compareTo));
                break;
            default:
                throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "Routing-only sort column not supported by pushdown: %s", sortColumn)
                );
        }
        return descending ? base.reversed() : base;
    }

    private static String prirepValue(ShardRouting shard) {
        if (shard.primary()) return "p";
        return shard.isSearchOnly() ? "s" : "r";
    }

    private static <T> T nodeAttr(ShardRouting shard, DiscoveryNodes nodes, java.util.function.Function<DiscoveryNode, T> extractor) {
        if (shard.assignedToNode() == false) return null;
        DiscoveryNode node = nodes.get(shard.currentNodeId());
        return node == null ? null : extractor.apply(node);
    }

    private static <T> Comparator<T> nullsFirst(Comparator<T> cmp) {
        return Comparator.nullsFirst(cmp);
    }
}
