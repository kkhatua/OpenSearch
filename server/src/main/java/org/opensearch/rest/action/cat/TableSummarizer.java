/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.rest.action.cat;

import org.opensearch.common.Table;
import org.opensearch.common.unit.SizeValue;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.unit.ByteSizeValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to summarize table data for _cat APIs based on grouping and aggregation functions.
 */
public class TableSummarizer {

    private static final Pattern AGGREGATION_PATTERN = Pattern.compile("^(sum|count|avg|min|max)\\((.*?)\\)$");

    public static boolean hasAggregation(String headersParam) {
        if (headersParam == null || headersParam.isEmpty()) {
            return false;
        }
        for (String header : headersParam.split(",")) {
            if (AGGREGATION_PATTERN.matcher(header.trim()).matches()) {
                return true;
            }
        }
        return false;
    }

    public static Table summarize(Table table, String headersParam) {
        if (headersParam == null || headersParam.isEmpty()) {
            return table;
        }

        String[] headers = headersParam.split(",");
        List<String> groupByColumns = new ArrayList<>();
        List<Aggregation> aggregations = new ArrayList<>();

        boolean hasAggregation = false;
        for (String header : headers) {
            Matcher matcher = AGGREGATION_PATTERN.matcher(header.trim());
            if (matcher.matches()) {
                hasAggregation = true;
                String func = matcher.group(1).toLowerCase();
                String column = matcher.group(2).trim();
                aggregations.add(new Aggregation(func, column, header.trim()));
            } else {
                groupByColumns.add(header.trim());
            }
        }

        if (!hasAggregation) {
            return table;
        }

        Map<GroupKey, GroupData> groups = new LinkedHashMap<>();

        // Helper to find the resolved column name considering aliases
        Map<String, String> aliasMap = table.getAliasMap();
        Map<String, String> resolvedGroupByCols = new LinkedHashMap<>();
        for (String col : groupByColumns) {
            if (aliasMap.containsKey(col)) {
                resolvedGroupByCols.put(col, aliasMap.get(col));
            } else {
                resolvedGroupByCols.put(col, col); // Might be an error later or just null
            }
        }

        List<AggregationInfo> resolvedAggs = new ArrayList<>();
        for (Aggregation agg : aggregations) {
            String resolvedCol = agg.column;
            if (!agg.column.isEmpty() && aliasMap.containsKey(agg.column)) {
                resolvedCol = aliasMap.get(agg.column);
            }
            resolvedAggs.add(new AggregationInfo(agg, resolvedCol));
        }

        Map<String, List<Table.Cell>> tableMap = table.getAsMap();
        int rowCount = table.getRows().size();

        for (int r = 0; r < rowCount; r++) {
            List<Object> groupValues = new ArrayList<>();
            for (String col : resolvedGroupByCols.values()) {
                List<Table.Cell> columnCells = tableMap.get(col);
                if (columnCells != null && r < columnCells.size()) {
                    groupValues.add(columnCells.get(r).value);
                } else {
                    groupValues.add(null);
                }
            }
            GroupKey key = new GroupKey(groupValues);
            GroupData data = groups.computeIfAbsent(key, k -> new GroupData(resolvedAggs.size()));

            for (int i = 0; i < resolvedAggs.size(); i++) {
                AggregationInfo aggInfo = resolvedAggs.get(i);
                Object val = null;
                if (!aggInfo.resolvedColumn.isEmpty()) {
                    List<Table.Cell> columnCells = tableMap.get(aggInfo.resolvedColumn);
                    if (columnCells != null && r < columnCells.size()) {
                        val = columnCells.get(r).value;
                    }
                }
                data.addValue(i, aggInfo.agg.function, val);
            }
        }

        Table summarizedTable = new Table(table.getPageToken());
        summarizedTable.startHeaders();
        for (String col : groupByColumns) {
            Table.Cell origHeader = table.findHeaderByName(resolvedGroupByCols.get(col));
            if (origHeader != null) {
                summarizedTable.addCell(col, getAttrString(origHeader.attr));
            } else {
                summarizedTable.addCell(col);
            }
        }
        for (AggregationInfo agg : resolvedAggs) {
            Table.Cell origHeader = agg.resolvedColumn.isEmpty() ? null : table.findHeaderByName(agg.resolvedColumn);
            if (origHeader != null) {
                summarizedTable.addCell(agg.agg.displayHeader, getAttrString(origHeader.attr));
            } else {
                summarizedTable.addCell(agg.agg.displayHeader);
            }
        }
        summarizedTable.endHeaders();

        for (Map.Entry<GroupKey, GroupData> entry : groups.entrySet()) {
            summarizedTable.startRow();
            for (Object val : entry.getKey().values) {
                summarizedTable.addCell(val);
            }
            for (int i = 0; i < resolvedAggs.size(); i++) {
                AggregationInfo aggInfo = resolvedAggs.get(i);
                Object aggValue = entry.getValue().getAggregatedValue(i, aggInfo.agg.function);
                // Try to format it back to original type if possible, or keep it simple
                summarizedTable.addCell(aggValue);
            }
            summarizedTable.endRow();
        }

        return summarizedTable;
    }

    private static String getAttrString(Map<String, String> attr) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : attr.entrySet()) {
            if (sb.length() > 0) sb.append(";");
            sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
        return sb.toString();
    }

    private static class Aggregation {
        String function;
        String column;
        String displayHeader;

        Aggregation(String function, String column, String displayHeader) {
            this.function = function;
            this.column = column;
            this.displayHeader = displayHeader;
        }
    }

    private static class AggregationInfo {
        Aggregation agg;
        String resolvedColumn;

        AggregationInfo(Aggregation agg, String resolvedColumn) {
            this.agg = agg;
            this.resolvedColumn = resolvedColumn;
        }
    }

    private static class GroupKey {
        List<Object> values;

        GroupKey(List<Object> values) {
            this.values = values;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GroupKey groupKey = (GroupKey) o;
            return values.equals(groupKey.values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(values);
        }
    }

    private static class GroupData {
        Aggregator[] aggregators;

        GroupData(int aggCount) {
            aggregators = new Aggregator[aggCount];
            for (int i = 0; i < aggCount; i++) {
                aggregators[i] = new Aggregator();
            }
        }

        void addValue(int index, String function, Object value) {
            aggregators[index].add(function, value);
        }

        Object getAggregatedValue(int index, String function) {
            return aggregators[index].getValue(function);
        }
    }

    private static class Aggregator {
        long count = 0;
        Double sum = null;
        Double min = null;
        Double max = null;
        Object sampleValue = null; // To remember type if possible

        void add(String function, Object value) {
            count++;
            if (value != null && function.matches("sum|avg|min|max")) {
                Double num = parseAsDouble(value);
                if (num != null) {
                    if (sum == null) sum = 0.0;
                    sum += num;
                    if (min == null || num < min) min = num;
                    if (max == null || num > max) max = num;
                }
                if (sampleValue == null) {
                    sampleValue = value;
                }
            }
        }

        Object getValue(String function) {
            switch (function) {
                case "count":
                    return count;
                case "sum":
                    return formatValue(sum, sampleValue);
                case "min":
                    return formatValue(min, sampleValue);
                case "max":
                    return formatValue(max, sampleValue);
                case "avg":
                    return count > 0 && sum != null ? formatValue(sum / count, sampleValue) : null;
                default:
                    return null;
            }
        }

        Double parseAsDouble(Object val) {
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            } else if (val instanceof ByteSizeValue) {
                return (double) ((ByteSizeValue) val).getBytes();
            } else if (val instanceof SizeValue) {
                return (double) ((SizeValue) val).singles();
            } else if (val instanceof TimeValue) {
                return (double) ((TimeValue) val).millis();
            } else if (val instanceof String) {
                try {
                    return Double.parseDouble((String) val);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }

        Object formatValue(Double num, Object sampleValue) {
            if (num == null) return null;
            if (sampleValue instanceof ByteSizeValue) {
                return new ByteSizeValue(num.longValue());
            } else if (sampleValue instanceof SizeValue) {
                return new SizeValue(num.longValue());
            } else if (sampleValue instanceof TimeValue) {
                return new TimeValue(num.longValue());
            } else if (sampleValue instanceof Long
                || sampleValue instanceof Integer
                || sampleValue instanceof Short
                || sampleValue instanceof Byte) {
                    return num.longValue();
                } else if (sampleValue instanceof String) {
                    // If it was parsed from a string, maybe it's just a number
                    if (num == num.longValue()) {
                        return String.valueOf(num.longValue());
                    } else {
                        return String.format("%.2f", num); // Simple float formatting
                    }
                }
            return num;
        }
    }
}
