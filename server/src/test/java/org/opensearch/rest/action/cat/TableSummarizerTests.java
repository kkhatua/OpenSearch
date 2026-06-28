/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.rest.action.cat;

import org.opensearch.common.Table;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class TableSummarizerTests extends OpenSearchTestCase {

    public void testNoAggregation() {
        Table table = new Table();
        table.startHeaders();
        table.addCell("status");
        table.addCell("index");
        table.endHeaders();

        table.startRow();
        table.addCell("green");
        table.addCell("test1");
        table.endRow();

        Table result = TableSummarizer.summarize(table, "status,index");
        assertSame(table, result); // Should return the same table if no aggregation
    }

    public void testCountAggregation() {
        Table table = new Table();
        table.startHeaders();
        table.addCell("status", "alias:s;desc:index status");
        table.addCell("index", "alias:i;desc:index name");
        table.endHeaders();

        table.startRow();
        table.addCell("green");
        table.addCell("test1");
        table.endRow();

        table.startRow();
        table.addCell("green");
        table.addCell("test2");
        table.endRow();

        table.startRow();
        table.addCell("yellow");
        table.addCell("test3");
        table.endRow();

        Table result = TableSummarizer.summarize(table, "status,count(index)");
        assertNotSame(table, result);

        List<Table.Cell> headers = result.getHeaders();
        assertEquals(2, headers.size());
        assertEquals("status", headers.get(0).value);
        assertEquals("alias:s;desc:index status", getAttrString(headers.get(0)));
        assertEquals("count(index)", headers.get(1).value);

        List<List<Table.Cell>> rows = result.getRows();
        assertEquals(2, rows.size());

        assertEquals("green", rows.get(0).get(0).value);
        assertEquals(2L, rows.get(0).get(1).value);

        assertEquals("yellow", rows.get(1).get(0).value);
        assertEquals(1L, rows.get(1).get(1).value);
    }

    public void testSumAndAvgAggregationWithByteSize() {
        Table table = new Table();
        table.startHeaders();
        table.addCell("status");
        table.addCell("size");
        table.endHeaders();

        table.startRow();
        table.addCell("green");
        table.addCell(new ByteSizeValue(100));
        table.endRow();

        table.startRow();
        table.addCell("green");
        table.addCell(new ByteSizeValue(200));
        table.endRow();

        Table result = TableSummarizer.summarize(table, "status,sum(size),avg(size)");

        List<List<Table.Cell>> rows = result.getRows();
        assertEquals(1, rows.size());
        assertEquals("green", rows.get(0).get(0).value);
        assertEquals(new ByteSizeValue(300), rows.get(0).get(1).value);
        assertEquals(new ByteSizeValue(150), rows.get(0).get(2).value);
    }

    public void testMinMaxAggregation() {
        Table table = new Table();
        table.startHeaders();
        table.addCell("status");
        table.addCell("docs");
        table.endHeaders();

        table.startRow();
        table.addCell("green");
        table.addCell("10"); // String number
        table.endRow();

        table.startRow();
        table.addCell("green");
        table.addCell("20");
        table.endRow();

        Table result = TableSummarizer.summarize(table, "status,min(docs),max(docs)");

        List<List<Table.Cell>> rows = result.getRows();
        assertEquals(1, rows.size());
        assertEquals("green", rows.get(0).get(0).value);
        assertEquals("10", rows.get(0).get(1).value);
        assertEquals("20", rows.get(0).get(2).value);
    }

    public void testEmptyAggregation() {
        Table table = new Table();
        table.startHeaders();
        table.addCell("status");
        table.addCell("index");
        table.endHeaders();

        table.startRow();
        table.addCell("green");
        table.addCell("test1");
        table.endRow();

        table.startRow();
        table.addCell("green");
        table.addCell("test2");
        table.endRow();

        Table result = TableSummarizer.summarize(table, "status,count()");

        List<List<Table.Cell>> rows = result.getRows();
        assertEquals(1, rows.size());
        assertEquals("green", rows.get(0).get(0).value);
        assertEquals(2L, rows.get(0).get(1).value);
    }

    private String getAttrString(Table.Cell cell) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : cell.attr.entrySet()) {
            if (sb.length() > 0) sb.append(";");
            sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
        return sb.toString();
    }
}
