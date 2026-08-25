package io.forgedb.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.forgedb.catalog.Column;
import io.forgedb.catalog.TableSchema;
import io.forgedb.exception.ForgeDbException;

public class RowCodec {
    public List<Object> parseValues(TableSchema table, List<String> rawValues) {
        if (rawValues.size() != table.getColumns().size()) {
            throw new ForgeDbException("Expected " + table.getColumns().size()
                    + " values but got " + rawValues.size());
        }
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < rawValues.size(); i++) {
            values.add(parseValue(table.getColumns().get(i), rawValues.get(i)));
        }
        return values;
    }

    public Object parseValue(Column column, String rawValue) {
        try {
            return switch (column.getType()) {
                case INT -> Integer.parseInt(rawValue.trim());
                case FLOAT -> Float.parseFloat(rawValue.trim());
                case CHAR -> parseChar(column, rawValue);
            };
        } catch (NumberFormatException e) {
            throw new ForgeDbException("Invalid " + column.getType() + " value for "
                    + column.getName() + ": " + rawValue);
        }
    }

    private String parseChar(Column column, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > column.getLength()) {
            throw new ForgeDbException("Value for " + column.getName() + " is longer than CHAR("
                    + column.getLength() + ")");
        }
        return value;
    }

    public byte[] encode(TableSchema table, List<Object> values) {
        ByteBuffer buffer = ByteBuffer.allocate(table.getRecordLength());
        for (int i = 0; i < table.getColumns().size(); i++) {
            Column column = table.getColumns().get(i);
            Object value = values.get(i);
            switch (column.getType()) {
                case INT -> buffer.putInt((Integer) value);
                case FLOAT -> buffer.putFloat((Float) value);
                case CHAR -> {
                    byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    buffer.put(bytes);
                    for (int pad = bytes.length; pad < column.getLength(); pad++) {
                        buffer.put((byte) 0);
                    }
                }
            }
        }
        return buffer.array();
    }

    public List<Object> decode(TableSchema table, byte[] row) {
        ByteBuffer buffer = ByteBuffer.wrap(row);
        List<Object> values = new ArrayList<>();
        for (Column column : table.getColumns()) {
            switch (column.getType()) {
                case INT -> values.add(buffer.getInt());
                case FLOAT -> values.add(buffer.getFloat());
                case CHAR -> {
                    byte[] bytes = new byte[column.getLength()];
                    buffer.get(bytes);
                    int end = 0;
                    while (end < bytes.length && bytes[end] != 0) {
                        end++;
                    }
                    values.add(new String(bytes, 0, end, StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    public DbKey toKey(Column column, String rawValue) {
        return new DbKey(column.getType(), parseValue(column, rawValue));
    }

    public DbKey toKey(Column column, Object value) {
        return new DbKey(column.getType(), value);
    }
}
