package io.forgedb.index;

import io.forgedb.catalog.CatalogManager;
import io.forgedb.catalog.Column;
import io.forgedb.catalog.DataType;
import io.forgedb.catalog.IndexDefinition;
import io.forgedb.catalog.TableSchema;
import io.forgedb.exception.ForgeDbException;
import io.forgedb.storage.DbKey;
import io.forgedb.storage.RecordLocation;
import io.forgedb.storage.RecordManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IndexManager {
    private static final int INDEX_FILE_VERSION = 1;

    private final CatalogManager catalog;
    private final RecordManager records;
    private final Map<String, BPlusTree<DbKey, RecordLocation>> cache = new HashMap<>();

    public IndexManager(CatalogManager catalog, RecordManager records) {
        this.catalog = catalog;
        this.records = records;
    }

    public void createIndex(String databaseName, TableSchema table,
                            String indexName, String columnName) {
        if (table.getIndex() != null) {
            throw new ForgeDbException("Each table can have only one index");
        }

        Column column = table.getColumn(columnName);
        if (!column.isPrimaryKey()) {
            throw new ForgeDbException("Index must be created on the primary key");
        }

        table.setIndex(new IndexDefinition(indexName, column.getName()));
        rebuild(databaseName, table);
        catalog.save();
    }

    public void dropIndex(String databaseName, TableSchema table, String indexName) {
        IndexDefinition definition = table.getIndex();
        if (definition == null || !definition.getName().equalsIgnoreCase(indexName)) {
            throw new ForgeDbException("Index does not exist: " + indexName);
        }

        cache.remove(cacheKey(databaseName, table));
        try {
            Files.deleteIfExists(indexFile(databaseName, definition));
        } catch (IOException e) {
            throw new ForgeDbException("Could not delete index file", e);
        }
        table.setIndex(null);
        catalog.save();
    }

    public RecordLocation lookup(String databaseName, TableSchema table,
                                 String columnName, String rawValue) {
        IndexDefinition definition = table.getIndex();
        if (definition == null || !definition.getColumnName().equalsIgnoreCase(columnName)) {
            return null;
        }

        BPlusTree<DbKey, RecordLocation> tree = ensureLoaded(databaseName, table);
        Column column = table.getColumn(columnName);
        DbKey key = records.getCodec().toKey(column, rawValue);
        return tree.search(key);
    }

    public void addEntry(String databaseName, TableSchema table, Object keyValue,
                         RecordLocation location) {
        if (table.getIndex() == null) {
            return;
        }
        Column column = table.getColumn(table.getIndex().getColumnName());
        BPlusTree<DbKey, RecordLocation> tree = ensureLoaded(databaseName, table);
        tree.insert(records.getCodec().toKey(column, keyValue), location);
        persist(databaseName, table, tree);
    }

    /**
     * Rebuilding after deletes/PK-changing updates keeps the implementation
     * small and correct while the B+ tree insertion/search logic stays real.
     */
    public void rebuild(String databaseName, TableSchema table) {
        if (table.getIndex() == null) {
            return;
        }

        Column column = table.getColumn(table.getIndex().getColumnName());
        int columnIndex = table.getColumnIndex(column.getName());
        BPlusTree<DbKey, RecordLocation> tree = new BPlusTree<>(calculateOrder(column));

        List<RecordManager.LocatedRow> rows = records.select(databaseName, table, List.of());
        for (RecordManager.LocatedRow row : rows) {
            DbKey key = records.getCodec().toKey(column, row.values().get(columnIndex));
            tree.insert(key, row.location());
        }

        cache.put(cacheKey(databaseName, table), tree);
        persist(databaseName, table, tree);
    }

    public void forgetTable(String databaseName, TableSchema table) {
        cache.remove(cacheKey(databaseName, table));
    }

    private BPlusTree<DbKey, RecordLocation> ensureLoaded(String databaseName, TableSchema table) {
        String key = cacheKey(databaseName, table);
        BPlusTree<DbKey, RecordLocation> tree = cache.get(key);
        if (tree != null) {
            return tree;
        }

        Path file = indexFile(databaseName, table.getIndex());
        if (!Files.exists(file)) {
            rebuild(databaseName, table);
            return cache.get(key);
        }

        Column column = table.getColumn(table.getIndex().getColumnName());
        tree = new BPlusTree<>(calculateOrder(column));
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int version = in.readInt();
            if (version != INDEX_FILE_VERSION) {
                throw new ForgeDbException("Unsupported ForgeDB index file version");
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                Object value = readKey(in, column.getType());
                int page = in.readInt();
                int slot = in.readInt();
                tree.insert(new DbKey(column.getType(), value), new RecordLocation(page, slot));
            }
        } catch (EOFException e) {
            throw new ForgeDbException("Index file is incomplete; recreate the index", e);
        } catch (IOException e) {
            throw new ForgeDbException("Could not read index file", e);
        }

        cache.put(key, tree);
        return tree;
    }

    private void persist(String databaseName, TableSchema table,
                         BPlusTree<DbKey, RecordLocation> tree) {
        Path file = indexFile(databaseName, table.getIndex());
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
                out.writeInt(INDEX_FILE_VERSION);
                out.writeInt(tree.size());
                for (Map.Entry<DbKey, RecordLocation> entry : tree.entries()) {
                    writeKey(out, entry.getKey());
                    out.writeInt(entry.getValue().pageNumber());
                    out.writeInt(entry.getValue().slot());
                }
            }
        } catch (IOException e) {
            throw new ForgeDbException("Could not write index file", e);
        }
    }

    private void writeKey(DataOutputStream out, DbKey key) throws IOException {
        switch (key.getType()) {
            case INT -> out.writeInt((Integer) key.getValue());
            case FLOAT -> out.writeFloat((Float) key.getValue());
            case CHAR -> out.writeUTF((String) key.getValue());
        }
    }

    private Object readKey(DataInputStream in, DataType type) throws IOException {
        return switch (type) {
            case INT -> in.readInt();
            case FLOAT -> in.readFloat();
            case CHAR -> in.readUTF();
        };
    }

    private int calculateOrder(Column column) {
        int approximateEntrySize = column.getLength() + Integer.BYTES * 2;
        int pageSizedOrder = (4096 - 12) / Math.max(approximateEntrySize, 8);
        return Math.max(4, Math.min(pageSizedOrder, 128));
    }

    private Path indexFile(String databaseName, IndexDefinition index) {
        return catalog.getRootDirectory().resolve(databaseName).resolve(index.getName() + ".index");
    }

    private String cacheKey(String databaseName, TableSchema table) {
        return databaseName.toLowerCase() + "/" + table.getName().toLowerCase();
    }
}
