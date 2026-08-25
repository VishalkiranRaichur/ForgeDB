package io.forgedb.catalog;

import java.io.Serializable;

public class IndexDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String columnName;

    public IndexDefinition(String name, String columnName) {
        this.name = name;
        this.columnName = columnName;
    }

    public String getName() {
        return name;
    }

    public String getColumnName() {
        return columnName;
    }
}
