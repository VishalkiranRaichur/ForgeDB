package io.forgedb.catalog;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import io.forgedb.exception.ForgeDbException;

public class TableSchema implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final ArrayList<Column> columns;
    private final int recordLength;

    private int firstPage = -1;
    private int lastPage = -1;
    private int pageCount = 0;
    private final ArrayDeque<Integer> freePages = new ArrayDeque<>();
    private IndexDefinition index;

    public TableSchema(String name, List<Column> columns) {
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.recordLength = columns.stream().mapToInt(Column::getLength).sum();
    }

    public String getName() {
        return name;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public int getRecordLength() {
        return recordLength;
    }

    public int getFirstPage() {
        return firstPage;
    }

    public void setFirstPage(int firstPage) {
        this.firstPage = firstPage;
    }

    public int getLastPage() {
        return lastPage;
    }

    public void setLastPage(int lastPage) {
        this.lastPage = lastPage;
    }

    public int getPageCount() {
        return pageCount;
    }

    public int allocatePageNumber() {
        if (!freePages.isEmpty()) {
            return freePages.removeFirst();
        }
        return pageCount++;
    }

    public void recyclePageNumber(int pageNumber) {
        if (!freePages.contains(pageNumber)) {
            freePages.addLast(pageNumber);
        }
    }

    public Column getColumn(String columnName) {
        return columns.stream()
                .filter(c -> c.getName().equalsIgnoreCase(columnName))
                .findFirst()
                .orElseThrow(() -> new ForgeDbException("Unknown column: " + columnName));
    }

    public int getColumnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        throw new ForgeDbException("Unknown column: " + columnName);
    }

    public int getPrimaryKeyIndex() {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).isPrimaryKey()) {
                return i;
            }
        }
        return -1;
    }

    public IndexDefinition getIndex() {
        return index;
    }

    public void setIndex(IndexDefinition index) {
        this.index = index;
    }
}
