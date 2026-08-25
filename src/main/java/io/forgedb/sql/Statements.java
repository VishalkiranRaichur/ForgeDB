package io.forgedb.sql;

import io.forgedb.catalog.Column;

import java.util.List;
import java.util.Map;

public final class Statements {
    private Statements() {
    }

    public record Help() implements Statement {}
    public record Quit() implements Statement {}
    public record ShowDatabases() implements Statement {}
    public record ShowTables() implements Statement {}
    public record CreateDatabase(String name) implements Statement {}
    public record DropDatabase(String name) implements Statement {}
    public record UseDatabase(String name) implements Statement {}
    public record CreateTable(String tableName, List<Column> columns) implements Statement {}
    public record DropTable(String tableName) implements Statement {}
    public record CreateIndex(String indexName, String tableName, String columnName) implements Statement {}
    public record DropIndex(String indexName) implements Statement {}
    public record Insert(String tableName, List<String> values) implements Statement {}
    public record Select(String tableName, List<Condition> conditions) implements Statement {}
    public record Delete(String tableName, List<Condition> conditions) implements Statement {}
    public record Update(String tableName, Map<String, String> assignments, List<Condition> conditions) implements Statement {}
    public record Exec(String fileName) implements Statement {}
}
