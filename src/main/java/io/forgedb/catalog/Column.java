package io.forgedb.catalog;

import java.io.Serializable;

public class Column implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final DataType type;
    private final int length;
    private final boolean primaryKey;

    public Column(String name, DataType type, int length, boolean primaryKey) {
        this.name = name;
        this.type = type;
        this.length = length;
        this.primaryKey = primaryKey;
    }

    public String getName() {
        return name;
    }

    public DataType getType() {
        return type;
    }

    public int getLength() {
        return length;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    @Override
    public String toString() {
        String typeText = type == DataType.CHAR ? "CHAR(" + length + ")" : type.name();
        return name + " " + typeText + (primaryKey ? " PRIMARY KEY" : "");
    }
}
