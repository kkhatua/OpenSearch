/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.cluster.shards;

import org.opensearch.Version;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Unit tests for the Phase 3 top-K pushdown helpers on {@link TransportCatShardsAction}.
 */
public class TransportCatShardsActionTests extends OpenSearchTestCase {

    private ShardRouting startedShard(String index, int id, String nodeId, boolean primary) {
        return TestShardRouting.newShardRouting(index, id, nodeId, primary, ShardRoutingState.STARTED);
    }

    private DiscoveryNodes simpleNodes() {
        DiscoveryNode nodeA = new DiscoveryNode("nodeA", buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode nodeB = new DiscoveryNode("nodeB", buildNewFakeTransportAddress(), Version.CURRENT);
        return DiscoveryNodes.builder().add(nodeA).add(nodeB).build();
    }

    public void testDistinctIndicesOfPreservesOrderAndDedupes() {
        DiscoveryNodes nodes = simpleNodes();
        List<ShardRouting> shards = Arrays.asList(
            startedShard("b", 0, "nodeA", true),
            startedShard("a", 0, "nodeA", true),
            startedShard("b", 1, "nodeB", false),
            startedShard("c", 0, "nodeA", true),
            startedShard("a", 1, "nodeB", false)
        );
        String[] indices = TransportCatShardsAction.distinctIndicesOf(shards);
        // Order = first occurrence: b, a, c
        assertThat(Arrays.asList(indices), contains("b", "a", "c"));
    }

    public void testTopKByIndexAscending() {
        DiscoveryNodes nodes = simpleNodes();
        // 10 shards across 4 indices.
        List<ShardRouting> shards = new ArrayList<>();
        for (String idx : Arrays.asList("delta", "alpha", "charlie", "bravo")) {
            shards.add(startedShard(idx, 0, "nodeA", true));
            shards.add(startedShard(idx, 1, "nodeB", false));
        }
        shards.add(startedShard("alpha", 2, "nodeA", true));
        shards.add(startedShard("bravo", 2, "nodeB", false));

        List<ShardRouting> topK = TransportCatShardsAction.selectTopKShardsByRouting(shards, nodes, "index", false, 3);
        assertThat(topK.size(), equalTo(3));
        // Top-3 by index name ascending = first 3 shards whose index sorts smallest. "alpha" has 3
        // shards, all should be selected.
        for (ShardRouting s : topK) {
            assertThat(s.getIndexName(), equalTo("alpha"));
        }
    }

    public void testTopKByIndexDescending() {
        DiscoveryNodes nodes = simpleNodes();
        List<ShardRouting> shards = new ArrayList<>();
        for (String idx : Arrays.asList("delta", "alpha", "charlie", "bravo")) {
            shards.add(startedShard(idx, 0, "nodeA", true));
        }
        List<ShardRouting> topK = TransportCatShardsAction.selectTopKShardsByRouting(shards, nodes, "index", true, 2);
        assertThat(topK.size(), equalTo(2));
        assertEquals("delta", topK.get(0).getIndexName());
        assertEquals("charlie", topK.get(1).getIndexName());
    }

    public void testTopKByShardId() {
        DiscoveryNodes nodes = simpleNodes();
        List<ShardRouting> shards = new ArrayList<>();
        for (int id : new int[] { 7, 3, 9, 1, 5 }) {
            shards.add(startedShard("foo", id, "nodeA", true));
        }
        List<ShardRouting> topK = TransportCatShardsAction.selectTopKShardsByRouting(shards, nodes, "shard", false, 2);
        // Smallest shard ids ascending: 1, 3
        assertThat(topK.size(), equalTo(2));
        assertEquals(1, topK.get(0).id());
        assertEquals(3, topK.get(1).id());
    }

    public void testTopKLimitGreaterThanRows() {
        DiscoveryNodes nodes = simpleNodes();
        List<ShardRouting> shards = Arrays.asList(
            startedShard("c", 0, "nodeA", true),
            startedShard("a", 0, "nodeA", true),
            startedShard("b", 0, "nodeA", true)
        );
        List<ShardRouting> topK = TransportCatShardsAction.selectTopKShardsByRouting(shards, nodes, "index", false, 10);
        // limit >= size — fall back to full sort, all 3 returned, sorted ascending.
        assertThat(topK.size(), equalTo(3));
        assertEquals("a", topK.get(0).getIndexName());
        assertEquals("b", topK.get(1).getIndexName());
        assertEquals("c", topK.get(2).getIndexName());
    }

    public void testTopKTiebreakIsStable() {
        DiscoveryNodes nodes = simpleNodes();
        // All shards have the same index name → ties on the sort key. Tie-breaker should preserve
        // the original iteration order (matches Collections.sort stability).
        List<ShardRouting> shards = new ArrayList<>();
        for (int id : new int[] { 4, 7, 2, 9, 1 }) {
            shards.add(startedShard("foo", id, "nodeA", true));
        }
        List<ShardRouting> topK = TransportCatShardsAction.selectTopKShardsByRouting(shards, nodes, "index", false, 3);
        // All tie on "index=foo" → first three from input order: shard ids 4, 7, 2
        assertThat(topK.size(), equalTo(3));
        assertEquals(4, topK.get(0).id());
        assertEquals(7, topK.get(1).id());
        assertEquals(2, topK.get(2).id());
    }

    public void testTopKMatchesFullSortPrefix() {
        DiscoveryNodes nodes = simpleNodes();
        List<ShardRouting> shards = new ArrayList<>();
        // Varied input across multiple indices.
        for (String idx : Arrays.asList("c", "a", "b", "a", "c", "d", "b", "a")) {
            shards.add(startedShard(idx, 0, "nodeA", true));
        }
        // Full sort ascending = "a a a b b c c d" with stable tie-break → expected index in that order.
        List<ShardRouting> full = TransportCatShardsAction.selectTopKShardsByRouting(shards, nodes, "index", false, shards.size());
        for (int k = 1; k <= shards.size(); k++) {
            List<ShardRouting> topK = TransportCatShardsAction.selectTopKShardsByRouting(shards, nodes, "index", false, k);
            assertThat("limit=" + k + " size", topK.size(), equalTo(k));
            for (int i = 0; i < k; i++) {
                assertEquals(
                    "limit=" + k + " row " + i + " should match full-sort prefix",
                    full.get(i).getIndexName(),
                    topK.get(i).getIndexName()
                );
            }
        }
    }
}
