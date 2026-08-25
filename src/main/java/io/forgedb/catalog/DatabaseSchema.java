package io.forgedb.catalog;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;

public class DatabaseSchema implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final LinkedHashMap<String, TableSchema> tables = new LinkedHashMap<>();

    public DatabaseSchema(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Collection<TableSchema> getTables() {
        return tables.values();
    }

    public TableSchema getTable(String tableName) {
        for (TableSchema table : tables.values()) {
            if (table.getName().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }

    public void addTable(TableSchema table) {
        tables.put(table.getName(), table);
    }

    public void removeTable(String tableName) {
        String key = tables.keySet().stream()
                .filter(name -> name.equalsIgnoreCase(tableName))
                .findFirst()
                .orElse(null);
        if (key != null) {
            tables.remove(key);
        }
    }
}
