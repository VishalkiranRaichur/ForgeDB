package io.forgedb.storage;

import io.forgedb.catalog.DataType;

public final class DbKey implements Comparable<DbKey> {
    private final DataType type;
    private final Object value;

    public DbKey(DataType type, Object value) {
        this.type = type;
        this.value = value;
    }

    public DataType getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public int compareTo(DbKey other) {
        if (type != other.type) {
            throw new IllegalArgumentException("Cannot compare different ForgeDB key types");
        }
        return switch (type) {
            case INT -> Integer.compare((Integer) value, (Integer) other.value);
            case FLOAT -> Float.compare((Float) value, (Float) other.value);
            case CHAR -> ((String) value).compareTo((String) other.value);
        };
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DbKey other && type == other.type && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
