package io.forgedb.sql;

public record Condition(String columnName, Operator operator, String rawValue) {
}
