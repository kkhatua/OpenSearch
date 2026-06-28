/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.rest.action.cat;

import org.opensearch.Version;
import org.opensearch.action.admin.cluster.state.ClusterStateResponse;
import org.opensearch.action.admin.indices.stats.CommonStats;
import org.opensearch.action.admin.indices.stats.IndexStats;
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse;
import org.opensearch.action.admin.indices.stats.ShardStats;
import org.opensearch.action.pagination.PageToken;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.common.Table;
import org.opensearch.index.shard.DocsStats;
import org.opensearch.index.shard.ShardPath;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.rest.FakeRestRequest;
import org.junit.Before;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RestShardsActionTests extends OpenSearchTestCase {

    private final DiscoveryNode localNode = new DiscoveryNode("local", buildNewFakeTransportAddress(), Version.CURRENT);
    private List<ShardRouting> shardRoutings = new ArrayList<>();
    private Map<ShardRouting, ShardStats> shardStatsMap = new HashMap<>();
    private ClusterStateResponse state;
    private IndicesStatsResponse stats;

    @Before
    public void setup() {
        final int numShards = randomIntBetween(1, 5);
        long numDocs = randomLongBetween(0, 10000);
        long numDeletedDocs = randomLongBetween(0, 100);

        String index = "index";
        for (int i = 0; i < numShards; i++) {
            ShardRoutingState shardRoutingState = ShardRoutingState.fromValue((byte) randomIntBetween(2, 3));
            ShardRouting shardRouting = TestShardRouting.newShardRouting(index, i, localNode.getId(), randomBoolean(), shardRoutingState);
            Path path = createTempDir().resolve("indices")
                .resolve(shardRouting.shardId().getIndex().getUUID())
                .resolve(String.valueOf(shardRouting.shardId().id()));
            CommonStats commonStats = new CommonStats();
            commonStats.docs = new DocsStats.Builder().count(numDocs).deleted(numDeletedDocs).totalSizeInBytes(0).build();
            ShardStats shardStats = new ShardStats.Builder().shardRouting(shardRouting)
                .shardPath(new ShardPath(false, path, path, shardRouting.shardId()))
                .commonStats(commonStats)
                .commitStats(null)
                .seqNoStats(null)
                .retentionLeaseStats(null)
                .pollingIngestStats(null)
                .build();
            shardStatsMap.put(shardRouting, shardStats);
            shardRoutings.add(shardRouting);
        }

        IndexStats indexStats = mock(IndexStats.class);
        when(indexStats.getPrimaries()).thenReturn(new CommonStats());
        when(indexStats.getTotal()).thenReturn(new CommonStats());

        stats = mock(IndicesStatsResponse.class);
        when(stats.asMap()).thenReturn(shardStatsMap);

        DiscoveryNodes discoveryNodes = mock(DiscoveryNodes.class);
        when(discoveryNodes.get(localNode.getId())).thenReturn(localNode);

        state = mock(ClusterStateResponse.class);
        RoutingTable routingTable = mock(RoutingTable.class);
        when(routingTable.allShards()).thenReturn(shardRoutings);
        ClusterState clusterState = mock(ClusterState.class);
        when(clusterState.routingTable()).thenReturn(routingTable);
        when(clusterState.nodes()).thenReturn(discoveryNodes);
        when(state.getState()).thenReturn(clusterState);
    }

    public void testBuildTable() {
        final RestShardsAction action = new RestShardsAction();
        final Table table = action.buildTable(
            new FakeRestRequest(),
            state.getState().nodes(),
            stats,
            state.getState().routingTable().allShards(),
            null
        );
        assertTable(table);
    }

    public void testBuildTableWithPageToken() {
        final RestShardsAction action = new RestShardsAction();
        final Table table = action.buildTable(
            new FakeRestRequest(),
            state.getState().nodes(),
            stats,
            state.getState().routingTable().allShards(),
            new PageToken("foo", "test")
        );
        assertTable(table);
        assertNotNull(table.getPageToken());
        assertEquals("foo", table.getPageToken().getNextToken());
        assertEquals("test", table.getPageToken().getPaginatedEntity());
    }

    // --- Phase 2: routing-only fast path tests ---

    public void testIndicesStatsRequiredWhenNoHParam() {
        // No h= → default headers include 'docs' and 'store' which need stats.
        assertTrue(RestShardsAction.requestNeedsIndicesStats(new FakeRestRequest()));
    }

    public void testIndicesStatsRequiredWhenHIsRoutingOnly() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("h", "index,shard,prirep,state,node,ip");
        assertFalse(RestShardsAction.requestNeedsIndicesStats(req));
    }

    public void testIndicesStatsRequiredWhenHUsesAliases() {
        FakeRestRequest req = new FakeRestRequest();
        // i=index, sh=shard, p=prirep, st=state, n=node, ip=ip, ur=unassigned.reason
        req.params().put("h", "i,sh,p,st,n,ip,ur");
        assertFalse(RestShardsAction.requestNeedsIndicesStats(req));
    }

    public void testIndicesStatsRequiredWhenHContainsStatsColumn() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("h", "index,shard,docs"); // docs requires stats
        assertTrue(RestShardsAction.requestNeedsIndicesStats(req));
    }

    public void testIndicesStatsRequiredWhenHContainsWildcard() {
        FakeRestRequest req = new FakeRestRequest();
        // Wildcards conservatively force the slow path.
        req.params().put("h", "index,shard*");
        assertTrue(RestShardsAction.requestNeedsIndicesStats(req));
    }

    public void testIndicesStatsRequiredWhenSortReferencesStatsColumn() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("h", "index,shard,node");
        req.params().put("s", "docs:desc"); // sort on stats column → stats still needed
        assertTrue(RestShardsAction.requestNeedsIndicesStats(req));
    }

    public void testIndicesStatsNotRequiredWhenSortIsRoutingOnly() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("h", "index,shard,node");
        req.params().put("s", "index:asc,shard:desc"); // routing columns only
        assertFalse(RestShardsAction.requestNeedsIndicesStats(req));
    }

    public void testIndicesStatsRequiredOnUnknownColumn() {
        FakeRestRequest req = new FakeRestRequest();
        // Unknown column conservatively forces slow path (don't optimize unrecognized tokens).
        req.params().put("h", "index,shard,not_a_column");
        assertTrue(RestShardsAction.requestNeedsIndicesStats(req));
    }

    // --- Phase 3: routing-only top-K pushdown tests ---

    public void testRoutingPushdownAppliedForSingleColumnSort() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("s", "index:desc");
        req.params().put("limit", "5");
        org.opensearch.action.admin.cluster.shards.CatShardsRequest sr = new org.opensearch.action.admin.cluster.shards.CatShardsRequest();
        RestShardsAction.applyRoutingTopKPushdown(req, sr);
        assertEquals("index", sr.getRoutingSortColumn());
        assertTrue(sr.isRoutingSortDescending());
        assertEquals(5, sr.getResponseLimit());
    }

    public void testRoutingPushdownAliasResolvedToCanonical() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("s", "n"); // alias for node
        req.params().put("limit", "3");
        org.opensearch.action.admin.cluster.shards.CatShardsRequest sr = new org.opensearch.action.admin.cluster.shards.CatShardsRequest();
        RestShardsAction.applyRoutingTopKPushdown(req, sr);
        assertEquals("node", sr.getRoutingSortColumn());
        assertFalse(sr.isRoutingSortDescending());
    }

    public void testRoutingPushdownSkippedForMultiColumnSort() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("s", "index,shard");
        req.params().put("limit", "5");
        org.opensearch.action.admin.cluster.shards.CatShardsRequest sr = new org.opensearch.action.admin.cluster.shards.CatShardsRequest();
        RestShardsAction.applyRoutingTopKPushdown(req, sr);
        assertNull(sr.getRoutingSortColumn());
        assertEquals(-1, sr.getResponseLimit());
    }

    public void testRoutingPushdownSkippedForStatsColumn() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("s", "docs:desc");
        req.params().put("limit", "5");
        org.opensearch.action.admin.cluster.shards.CatShardsRequest sr = new org.opensearch.action.admin.cluster.shards.CatShardsRequest();
        RestShardsAction.applyRoutingTopKPushdown(req, sr);
        assertNull(sr.getRoutingSortColumn());
    }

    public void testRoutingPushdownSkippedWithoutLimit() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("s", "index");
        // no limit param
        org.opensearch.action.admin.cluster.shards.CatShardsRequest sr = new org.opensearch.action.admin.cluster.shards.CatShardsRequest();
        RestShardsAction.applyRoutingTopKPushdown(req, sr);
        assertNull(sr.getRoutingSortColumn());
    }

    public void testRoutingPushdownSkippedWithoutSort() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("limit", "5");
        // no s= param
        org.opensearch.action.admin.cluster.shards.CatShardsRequest sr = new org.opensearch.action.admin.cluster.shards.CatShardsRequest();
        RestShardsAction.applyRoutingTopKPushdown(req, sr);
        assertNull(sr.getRoutingSortColumn());
    }

    public void testRoutingPushdownSkippedForWildcardSort() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("s", "index*");
        req.params().put("limit", "5");
        org.opensearch.action.admin.cluster.shards.CatShardsRequest sr = new org.opensearch.action.admin.cluster.shards.CatShardsRequest();
        RestShardsAction.applyRoutingTopKPushdown(req, sr);
        assertNull(sr.getRoutingSortColumn());
    }

    // --- Phase B: per-endpoint early-exit tests ---

    public void testBuildTableEarlyExitOnLimit() {
        // Force a known shard count by passing 0 — actually, randomIntBetween(1, 5) gives 1..5, so
        // we just assert size <= limit when limit < count. Use limit=1 (always valid).
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("limit", "1");
        final RestShardsAction action = new RestShardsAction();
        final Table table = action.buildTable(req, state.getState().nodes(), stats, shardRoutings, null);
        assertThat(table.getRows().size(), equalTo(Math.min(1, shardRoutings.size())));
    }

    public void testBuildTableLimitZeroProducesNoRows() {
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("limit", "0");
        final RestShardsAction action = new RestShardsAction();
        final Table table = action.buildTable(req, state.getState().nodes(), stats, shardRoutings, null);
        assertThat(table.getRows().size(), equalTo(0));
    }

    public void testBuildTableLimitNoOptimizationWithSort() {
        // With s= set, the early-exit must NOT fire — sort needs every row to produce correct top-N.
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("limit", "1");
        req.params().put("s", "shard");
        final RestShardsAction action = new RestShardsAction();
        final Table table = action.buildTable(req, state.getState().nodes(), stats, shardRoutings, null);
        // All shards present in the table; RestTable.getRowOrder applies the final limit downstream.
        assertThat(table.getRows().size(), equalTo(shardRoutings.size()));
    }

    public void testBuildTableLimitNoOptimizationWithAggregation() {
        // With an aggregation func in h=, the early-exit must NOT fire — summarize needs every row.
        FakeRestRequest req = new FakeRestRequest();
        req.params().put("limit", "1");
        req.params().put("h", "index,sum(docs)");
        final RestShardsAction action = new RestShardsAction();
        final Table table = action.buildTable(req, state.getState().nodes(), stats, shardRoutings, null);
        assertThat(table.getRows().size(), equalTo(shardRoutings.size()));
    }

    private void assertTable(Table table) {
        // now, verify the table is correct
        List<Table.Cell> headers = table.getHeaders();
        assertThat(headers.get(0).value, equalTo("index"));
        assertThat(headers.get(1).value, equalTo("shard"));
        assertThat(headers.get(2).value, equalTo("prirep"));
        assertThat(headers.get(3).value, equalTo("state"));
        assertThat(headers.get(4).value, equalTo("docs"));
        assertThat(headers.get(5).value, equalTo("store"));
        assertThat(headers.get(6).value, equalTo("ip"));
        assertThat(headers.get(7).value, equalTo("id"));
        assertThat(headers.get(8).value, equalTo("node"));
        assertThat(headers.get(92).value, equalTo("docs.deleted"));

        final List<List<Table.Cell>> rows = table.getRows();
        assertThat(rows.size(), equalTo(shardRoutings.size()));

        Iterator<ShardRouting> shardRoutingsIt = shardRoutings.iterator();
        for (final List<Table.Cell> row : rows) {
            ShardRouting shardRouting = shardRoutingsIt.next();
            ShardStats shardStats = shardStatsMap.get(shardRouting);
            assertThat(row.get(0).value, equalTo(shardRouting.getIndexName()));
            assertThat(row.get(1).value, equalTo(shardRouting.getId()));
            assertThat(row.get(2).value, equalTo(shardRouting.primary() ? "p" : "r"));
            assertThat(row.get(3).value, equalTo(shardRouting.state()));
            assertThat(row.get(4).value, equalTo(shardStats.getStats().getDocs().getCount()));
            assertThat(row.get(6).value, equalTo(localNode.getHostAddress()));
            assertThat(row.get(7).value, equalTo(localNode.getId()));
            assertThat(row.get(90).value, equalTo(shardStats.getDataPath()));
            assertThat(row.get(91).value, equalTo(shardStats.getStatePath()));
            assertThat(row.get(92).value, equalTo(shardStats.getStats().getDocs().getDeleted()));
        }
    }
}
