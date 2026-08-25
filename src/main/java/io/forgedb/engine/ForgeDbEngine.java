package io.forgedb.engine;

import io.forgedb.catalog.CatalogManager;
import io.forgedb.catalog.Column;
import io.forgedb.catalog.DatabaseSchema;
import io.forgedb.catalog.IndexDefinition;
import io.forgedb.catalog.TableSchema;
import io.forgedb.exception.ForgeDbException;
import io.forgedb.index.IndexManager;
import io.forgedb.sql.Condition;
import io.forgedb.sql.Operator;
import io.forgedb.sql.SqlParser;
import io.forgedb.sql.Statement;
import io.forgedb.sql.Statements;
import io.forgedb.storage.BufferPool;
import io.forgedb.storage.RecordLocation;
import io.forgedb.storage.RecordManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ForgeDbEngine implements AutoCloseable {
    public static final String VERSION = "1.0.0";

    private final CatalogManager catalog;
    private final BufferPool bufferPool;
    private final RecordManager records;
    private final IndexManager indexes;
    private final SqlParser parser = new SqlParser();
    private String currentDatabase;

    public ForgeDbEngine() {
        this(Path.of(System.getProperty("user.home"), "ForgeDBData"));
    }

    public ForgeDbEngine(Path dataDirectory) {
        this.catalog = new CatalogManager(dataDirectory);
        this.bufferPool = new BufferPool(32);
        this.records = new RecordManager(catalog, bufferPool);
        this.indexes = new IndexManager(catalog, records);
    }

    public String execute(String sql) {
        return execute(parser.parse(sql));
    }

    public String execute(Statement statement) {
        if (statement instanceof Statements.Help) {
            return help();
        }
        if (statement instanceof Statements.Quit) {
            close();
            return "Bye from ForgeDB.";
        }
        if (statement instanceof Statements.ShowDatabases) {
            return showDatabases();
        }
        if (statement instanceof Statements.ShowTables) {
            return showTables();
        }
        if (statement instanceof Statements.CreateDatabase s) {
            catalog.createDatabase(s.name());
            return "Database created: " + s.name();
        }
        if (statement instanceof Statements.DropDatabase s) {
            return dropDatabase(s.name());
        }
        if (statement instanceof Statements.UseDatabase s) {
            return useDatabase(s.name());
        }
        if (statement instanceof Statements.CreateTable s) {
            return createTable(s.tableName(), s.columns());
        }
        if (statement instanceof Statements.DropTable s) {
            return dropTable(s.tableName());
        }
        if (statement instanceof Statements.CreateIndex s) {
            return createIndex(s.indexName(), s.tableName(), s.columnName());
        }
        if (statement instanceof Statements.DropIndex s) {
            return dropIndex(s.indexName());
        }
        if (statement instanceof Statements.Insert s) {
            return insert(s.tableName(), s.values());
        }
        if (statement instanceof Statements.Select s) {
            return select(s.tableName(), s.conditions());
        }
        if (statement instanceof Statements.Delete s) {
            return delete(s.tableName(), s.conditions());
        }
        if (statement instanceof Statements.Update s) {
            return update(s.tableName(), s.assignments(), s.conditions());
        }
        if (statement instanceof Statements.Exec s) {
            return executeFile(s.fileName());
        }
        throw new ForgeDbException("Unknown statement type");
    }

    public String getCurrentDatabase() {
        return currentDatabase;
    }

    public Path getDataDirectory() {
        return catalog.getRootDirectory();
    }

    private String help() {
        return """
                ForgeDB 1.0.0
                Supported commands:
                  CREATE DATABASE name;
                  DROP DATABASE name;
                  SHOW DATABASES;
                  USE name;
                  CREATE TABLE name (..., PRIMARY KEY (column));
                  DROP TABLE name;
                  SHOW TABLES;
                  CREATE INDEX name ON table (primary_key_column);
                  DROP INDEX name;
                  INSERT INTO table VALUES (...);
                  SELECT * FROM table [WHERE condition AND ...];
                  DELETE FROM table [WHERE condition AND ...];
                  UPDATE table SET column=value [, ...] WHERE condition [AND ...];
                  EXEC file.sql;
                  HELP;
                  QUIT; / EXIT;
                """.strip();
    }

    private String showDatabases() {
        List<String> names = catalog.getDatabases().stream()
                .map(DatabaseSchema::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (names.isEmpty()) {
            return "No databases.";
        }
        return "Databases:\n  " + String.join("\n  ", names);
    }

    private String showTables() {
        DatabaseSchema database = requireCurrentDatabase();
        List<String> names = database.getTables().stream()
                .map(TableSchema::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (names.isEmpty()) {
            return "No tables in " + database.getName() + ".";
        }
        return "Tables in " + database.getName() + ":\n  " + String.join("\n  ", names);
    }

    private String useDatabase(String name) {
        DatabaseSchema database = catalog.getDatabase(name);
        if (database == null) {
            throw new ForgeDbException("Database does not exist: " + name);
        }
        currentDatabase = database.getName();
        return "Using database: " + currentDatabase;
    }

    private String dropDatabase(String name) {
        DatabaseSchema database = catalog.getDatabase(name);
        if (database == null) {
            throw new ForgeDbException("Database does not exist: " + name);
        }

        bufferPool.flushAll();
        Path directory = catalog.getRootDirectory().resolve(database.getName());
        for (TableSchema table : database.getTables()) {
            bufferPool.discardFile(records.tableFile(database.getName(), table));
            indexes.forgetTable(database.getName(), table);
        }
        deleteRecursively(directory);
        catalog.removeDatabase(database.getName());

        if (database.getName().equalsIgnoreCase(currentDatabase)) {
            currentDatabase = null;
        }
        return "Database dropped: " + database.getName();
    }

    private String createTable(String tableName, List<Column> columns) {
        DatabaseSchema database = requireCurrentDatabase();
        if (database.getTable(tableName) != null) {
            throw new ForgeDbException("Table already exists: " + tableName);
        }

        TableSchema table = new TableSchema(tableName, columns);
        if (table.getRecordLength() > 4096 - 12) {
            throw new ForgeDbException("Table row is too large for a 4 KB page");
        }
        database.addTable(table);
        try {
            Path file = records.tableFile(database.getName(), table);
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) Files.createFile(file);
        } catch (IOException e) {
            database.removeTable(tableName);
            throw new ForgeDbException("Could not create table file", e);
        }
        catalog.save();
        return "Table created: " + tableName;
    }

    private String dropTable(String tableName) {
        DatabaseSchema database = requireCurrentDatabase();
        TableSchema table = requireTable(database, tableName);
        Path recordFile = records.tableFile(database.getName(), table);
        bufferPool.discardFile(recordFile);
        indexes.forgetTable(database.getName(), table);

        try {
            Files.deleteIfExists(recordFile);
            if (table.getIndex() != null) {
                Files.deleteIfExists(catalog.getRootDirectory().resolve(database.getName())
                        .resolve(table.getIndex().getName() + ".index"));
            }
        } catch (IOException e) {
            throw new ForgeDbException("Could not delete table files", e);
        }

        database.removeTable(tableName);
        catalog.save();
        return "Table dropped: " + table.getName();
    }

    private String createIndex(String indexName, String tableName, String columnName) {
        DatabaseSchema database = requireCurrentDatabase();
        for (TableSchema table : database.getTables()) {
            IndexDefinition existing = table.getIndex();
            if (existing != null && existing.getName().equalsIgnoreCase(indexName)) {
                throw new ForgeDbException("Index already exists: " + indexName);
            }
        }

        TableSchema table = requireTable(database, tableName);
        indexes.createIndex(database.getName(), table, indexName, columnName);
        return "Index created: " + indexName + " on " + table.getName() + "(" + columnName + ")";
    }

    private String dropIndex(String indexName) {
        DatabaseSchema database = requireCurrentDatabase();
        for (TableSchema table : database.getTables()) {
            IndexDefinition definition = table.getIndex();
            if (definition != null && definition.getName().equalsIgnoreCase(indexName)) {
                indexes.dropIndex(database.getName(), table, indexName);
                return "Index dropped: " + indexName;
            }
        }
        throw new ForgeDbException("Index does not exist: " + indexName);
    }

    private String insert(String tableName, List<String> values) {
        DatabaseSchema database = requireCurrentDatabase();
        TableSchema table = requireTable(database, tableName);
        RecordLocation location = records.insert(database.getName(), table, values);

        if (table.getIndex() != null) {
            int keyIndex = table.getColumnIndex(table.getIndex().getColumnName());
            Object keyValue = records.getAt(database.getName(), table, location).values().get(keyIndex);
            indexes.addEntry(database.getName(), table, keyValue, location);
        }
        return "1 row inserted.";
    }

    private String select(String tableName, List<Condition> conditions) {
        DatabaseSchema database = requireCurrentDatabase();
        TableSchema table = requireTable(database, tableName);
        List<RecordManager.LocatedRow> rows;

        Condition indexedEquality = findIndexedEquality(table, conditions);
        if (indexedEquality != null) {
            RecordLocation location = indexes.lookup(database.getName(), table,
                    indexedEquality.columnName(), indexedEquality.rawValue());
            RecordManager.LocatedRow row = records.getAt(database.getName(), table, location);
            if (row != null && records.matches(table, row.values(), conditions)) {
                rows = List.of(row);
            } else {
                rows = List.of();
            }
        } else {
            rows = records.select(database.getName(), table, conditions);
        }

        return formatRows(table, rows);
    }

    private String delete(String tableName, List<Condition> conditions) {
        DatabaseSchema database = requireCurrentDatabase();
        TableSchema table = requireTable(database, tableName);
        int count = records.delete(database.getName(), table, conditions);
        if (table.getIndex() != null) {
            indexes.rebuild(database.getName(), table);
        }
        return count + (count == 1 ? " row deleted." : " rows deleted.");
    }

    private String update(String tableName, Map<String, String> assignments, List<Condition> conditions) {
        DatabaseSchema database = requireCurrentDatabase();
        TableSchema table = requireTable(database, tableName);
        for (String column : assignments.keySet()) {
            table.getColumn(column);
        }
        int count = records.update(database.getName(), table, assignments, conditions);
        if (table.getIndex() != null) {
            indexes.rebuild(database.getName(), table);
        }
        return count + (count == 1 ? " row updated." : " rows updated.");
    }

    private String executeFile(String fileName) {
        Path path = Path.of(fileName);
        try {
            String script = Files.readString(path);
            StringBuilder output = new StringBuilder();
            for (String sql : parser.splitStatements(script)) {
                output.append("> ").append(sql.replaceAll("\\s+", " ").trim()).append(";\n");
                try {
                    Statement statement = parser.parse(sql);
                    if (statement instanceof Statements.Quit) {
                        output.append("QUIT ignored inside EXEC file.\n");
                    } else {
                        output.append(execute(statement)).append('\n');
                    }
                } catch (ForgeDbException e) {
                    output.append("ERROR: ").append(e.getMessage()).append('\n');
                }
            }
            return output.toString().stripTrailing();
        } catch (IOException e) {
            throw new ForgeDbException("Could not read SQL file: " + fileName, e);
        }
    }

    private Condition findIndexedEquality(TableSchema table, List<Condition> conditions) {
        if (table.getIndex() == null) return null;
        return conditions.stream()
                .filter(c -> c.operator() == Operator.EQ)
                .filter(c -> c.columnName().equalsIgnoreCase(table.getIndex().getColumnName()))
                .findFirst()
                .orElse(null);
    }

    private String formatRows(TableSchema table, List<RecordManager.LocatedRow> rows) {
        List<String> headers = table.getColumns().stream().map(Column::getName).toList();
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) widths[i] = headers.get(i).length();

        List<List<String>> printableRows = new ArrayList<>();
        for (RecordManager.LocatedRow row : rows) {
            List<String> printable = row.values().stream().map(String::valueOf).toList();
            printableRows.add(printable);
            for (int i = 0; i < printable.size(); i++) {
                widths[i] = Math.max(widths[i], printable.get(i).length());
            }
        }

        StringBuilder out = new StringBuilder();
        appendRow(out, headers, widths);
        for (int width : widths) out.append("-").append("-".repeat(width)).append("-").append('+');
        if (widths.length > 0) out.setLength(out.length() - 1);
        out.append('\n');
        for (List<String> row : printableRows) appendRow(out, row, widths);
        out.append(rows.size()).append(rows.size() == 1 ? " row." : " rows.");
        return out.toString();
    }

    private void appendRow(StringBuilder out, List<String> values, int[] widths) {
        for (int i = 0; i < values.size(); i++) {
            out.append(" ").append(String.format("%-" + widths[i] + "s", values.get(i))).append(" ");
            if (i < values.size() - 1) out.append('|');
        }
        out.append('\n');
    }

    private DatabaseSchema requireCurrentDatabase() {
        if (currentDatabase == null) {
            throw new ForgeDbException("No database selected");
        }
        DatabaseSchema database = catalog.getDatabase(currentDatabase);
        if (database == null) {
            currentDatabase = null;
            throw new ForgeDbException("Selected database no longer exists");
        }
        return database;
    }

    private TableSchema requireTable(DatabaseSchema database, String tableName) {
        TableSchema table = database.getTable(tableName);
        if (table == null) {
            throw new ForgeDbException("Table does not exist: " + tableName);
        }
        return table;
    }

    private void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) return;
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new ForgeDbException("Could not delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new ForgeDbException("Could not remove database directory", e);
        }
    }

    @Override
    public void close() {
        bufferPool.close();
        catalog.save();
    }
}
