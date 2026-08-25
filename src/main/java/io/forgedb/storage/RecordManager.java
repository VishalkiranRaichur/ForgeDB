package io.forgedb.storage;

import io.forgedb.catalog.CatalogManager;
import io.forgedb.catalog.Column;
import io.forgedb.catalog.TableSchema;
import io.forgedb.exception.ForgeDbException;
import io.forgedb.sql.Condition;
import io.forgedb.sql.Operator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles fixed-length rows stored inside 4 KB pages.
 */
public class RecordManager {
    private final CatalogManager catalog;
    private final BufferPool bufferPool;
    private final RowCodec codec = new RowCodec();

    public RecordManager(CatalogManager catalog, BufferPool bufferPool) {
        this.catalog = catalog;
        this.bufferPool = bufferPool;
    }

    public RecordLocation insert(String databaseName, TableSchema table, List<String> rawValues) {
        List<Object> values = codec.parseValues(table, rawValues);
        validatePrimaryKeyUnique(databaseName, table, values, null);

        int capacity = recordsPerPage(table);
        Path file = tableFile(databaseName, table);

        int pageNumber = table.getFirstPage();
        while (pageNumber != -1) {
            Page page = bufferPool.getPage(file, pageNumber);
            if (page.getRecordCount() < capacity) {
                int slot = page.getRecordCount();
                page.writeRecord(slot, table.getRecordLength(), codec.encode(table, values));
                page.setRecordCount(slot + 1);
                bufferPool.flushAll();
                catalog.save();
                return new RecordLocation(pageNumber, slot);
            }
            pageNumber = page.getNextPage();
        }

        int newPageNumber = table.allocatePageNumber();
        Page page = bufferPool.resetPage(file, newPageNumber);

        if (table.getFirstPage() == -1) {
            table.setFirstPage(newPageNumber);
            table.setLastPage(newPageNumber);
        } else {
            Page last = bufferPool.getPage(file, table.getLastPage());
            last.setNextPage(newPageNumber);
            page.setPreviousPage(last.getPageNumber());
            table.setLastPage(newPageNumber);
        }

        page.writeRecord(0, table.getRecordLength(), codec.encode(table, values));
        page.setRecordCount(1);
        bufferPool.flushAll();
        catalog.save();
        return new RecordLocation(newPageNumber, 0);
    }

    public List<LocatedRow> select(String databaseName, TableSchema table, List<Condition> conditions) {
        List<LocatedRow> rows = new ArrayList<>();
        Path file = tableFile(databaseName, table);
        int pageNumber = table.getFirstPage();

        while (pageNumber != -1) {
            Page page = bufferPool.getPage(file, pageNumber);
            for (int slot = 0; slot < page.getRecordCount(); slot++) {
                List<Object> values = codec.decode(table, page.readRecord(slot, table.getRecordLength()));
                if (matches(table, values, conditions)) {
                    rows.add(new LocatedRow(new RecordLocation(pageNumber, slot), values));
                }
            }
            pageNumber = page.getNextPage();
        }
        return rows;
    }

    public LocatedRow getAt(String databaseName, TableSchema table, RecordLocation location) {
        if (location == null) {
            return null;
        }
        Page page = bufferPool.getPage(tableFile(databaseName, table), location.pageNumber());
        if (location.slot() < 0 || location.slot() >= page.getRecordCount()) {
            return null;
        }
        List<Object> values = codec.decode(table,
                page.readRecord(location.slot(), table.getRecordLength()));
        return new LocatedRow(location, values);
    }

    public int delete(String databaseName, TableSchema table, List<Condition> conditions) {
        Path file = tableFile(databaseName, table);
        int deleted = 0;
        int pageNumber = table.getFirstPage();

        while (pageNumber != -1) {
            Page page = bufferPool.getPage(file, pageNumber);
            int nextPage = page.getNextPage();
            int slot = 0;

            while (slot < page.getRecordCount()) {
                List<Object> values = codec.decode(table, page.readRecord(slot, table.getRecordLength()));
                if (!matches(table, values, conditions)) {
                    slot++;
                    continue;
                }

                int lastSlot = page.getRecordCount() - 1;
                if (slot != lastSlot) {
                    byte[] lastRow = page.readRecord(lastSlot, table.getRecordLength());
                    page.writeRecord(slot, table.getRecordLength(), lastRow);
                }
                page.clearRecord(lastSlot, table.getRecordLength());
                page.setRecordCount(lastSlot);
                deleted++;
            }

            if (page.getRecordCount() == 0) {
                unlinkEmptyPage(file, table, page);
            }
            pageNumber = nextPage;
        }

        bufferPool.flushAll();
        catalog.save();
        return deleted;
    }

    public int update(String databaseName, TableSchema table, Map<String, String> assignments,
                      List<Condition> conditions) {
        if (assignments.isEmpty()) {
            return 0;
        }

        List<LocatedRow> allRows = select(databaseName, table, List.of());
        List<PendingUpdate> changes = new ArrayList<>();

        for (LocatedRow row : allRows) {
            if (!matches(table, row.values(), conditions)) {
                continue;
            }
            List<Object> newValues = new ArrayList<>(row.values());
            for (Map.Entry<String, String> assignment : assignments.entrySet()) {
                int columnIndex = table.getColumnIndex(assignment.getKey());
                Column column = table.getColumns().get(columnIndex);
                newValues.set(columnIndex, codec.parseValue(column, assignment.getValue()));
            }
            changes.add(new PendingUpdate(row.location(), newValues));
        }

        validatePrimaryKeysAfterUpdate(table, allRows, changes);

        Path file = tableFile(databaseName, table);
        for (PendingUpdate change : changes) {
            Page page = bufferPool.getPage(file, change.location().pageNumber());
            page.writeRecord(change.location().slot(), table.getRecordLength(),
                    codec.encode(table, change.values()));
        }

        bufferPool.flushAll();
        return changes.size();
    }

    public boolean matches(TableSchema table, List<Object> values, List<Condition> conditions) {
        for (Condition condition : conditions) {
            int index = table.getColumnIndex(condition.columnName());
            Column column = table.getColumns().get(index);
            Object right = codec.parseValue(column, condition.rawValue());
            if (!compare(column, values.get(index), right, condition.operator())) {
                return false;
            }
        }
        return true;
    }

    public RowCodec getCodec() {
        return codec;
    }

    public Path tableFile(String databaseName, TableSchema table) {
        return catalog.getRootDirectory().resolve(databaseName).resolve(table.getName() + ".records");
    }

    private void validatePrimaryKeyUnique(String databaseName, TableSchema table, List<Object> values,
                                          RecordLocation ignoredLocation) {
        int pkIndex = table.getPrimaryKeyIndex();
        if (pkIndex == -1) {
            return;
        }
        Object newKey = values.get(pkIndex);
        for (LocatedRow row : select(databaseName, table, List.of())) {
            if (ignoredLocation != null && ignoredLocation.equals(row.location())) {
                continue;
            }
            if (row.values().get(pkIndex).equals(newKey)) {
                throw new ForgeDbException("Primary key conflict: " + newKey);
            }
        }
    }

    private void validatePrimaryKeysAfterUpdate(TableSchema table, List<LocatedRow> original,
                                                 List<PendingUpdate> changes) {
        int pkIndex = table.getPrimaryKeyIndex();
        if (pkIndex == -1 || changes.isEmpty()) {
            return;
        }

        Map<RecordLocation, List<Object>> replacements = changes.stream()
                .collect(java.util.stream.Collectors.toMap(PendingUpdate::location, PendingUpdate::values));
        java.util.HashSet<Object> seen = new java.util.HashSet<>();
        for (LocatedRow row : original) {
            List<Object> values = replacements.getOrDefault(row.location(), row.values());
            Object key = values.get(pkIndex);
            if (!seen.add(key)) {
                throw new ForgeDbException("Primary key conflict: " + key);
            }
        }
    }

    private boolean compare(Column column, Object left, Object right, Operator operator) {
        int cmp = switch (column.getType()) {
            case INT -> Integer.compare((Integer) left, (Integer) right);
            case FLOAT -> Float.compare((Float) left, (Float) right);
            case CHAR -> ((String) left).compareTo((String) right);
        };
        return switch (operator) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case LT -> cmp < 0;
            case GT -> cmp > 0;
            case LE -> cmp <= 0;
            case GE -> cmp >= 0;
        };
    }

    private int recordsPerPage(TableSchema table) {
        if (table.getRecordLength() <= 0 || table.getRecordLength() > Page.PAGE_SIZE - Page.HEADER_SIZE) {
            throw new ForgeDbException("Record is too large for a 4 KB page");
        }
        return (Page.PAGE_SIZE - Page.HEADER_SIZE) / table.getRecordLength();
    }

    private void unlinkEmptyPage(Path file, TableSchema table, Page page) {
        int previous = page.getPreviousPage();
        int next = page.getNextPage();

        if (previous != -1) {
            bufferPool.getPage(file, previous).setNextPage(next);
        } else {
            table.setFirstPage(next);
        }

        if (next != -1) {
            bufferPool.getPage(file, next).setPreviousPage(previous);
        } else {
            table.setLastPage(previous);
        }

        int pageNumber = page.getPageNumber();
        page.reset();
        table.recyclePageNumber(pageNumber);
    }

    public record LocatedRow(RecordLocation location, List<Object> values) {
    }

    private record PendingUpdate(RecordLocation location, List<Object> values) {
    }
}
