package io.forgedb.catalog;

import io.forgedb.exception.ForgeDbException;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class CatalogManager {
    private final Path rootDirectory;
    private final Path catalogFile;
    private Map<String, DatabaseSchema> databases;

    public CatalogManager(Path rootDirectory) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.catalogFile = this.rootDirectory.resolve("catalog.ser");
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException e) {
            throw new ForgeDbException("Could not create ForgeDB data directory", e);
        }
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(catalogFile)) {
            databases = new LinkedHashMap<>();
            return;
        }

        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(catalogFile))) {
            databases = (Map<String, DatabaseSchema>) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new ForgeDbException("Could not read ForgeDB catalog", e);
        }
    }

    public synchronized void save() {
        Path temp = catalogFile.resolveSibling("catalog.ser.tmp");
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(temp))) {
            out.writeObject(databases);
            out.flush();
            Files.move(temp, catalogFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(temp, catalogFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                throw new ForgeDbException("Could not write ForgeDB catalog", fallbackFailure);
            }
        }
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public Collection<DatabaseSchema> getDatabases() {
        return databases.values();
    }

    public DatabaseSchema getDatabase(String name) {
        return databases.values().stream()
                .filter(db -> db.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public void createDatabase(String name) {
        if (getDatabase(name) != null) {
            throw new ForgeDbException("Database already exists: " + name);
        }
        databases.put(name, new DatabaseSchema(name));
        try {
            Files.createDirectories(rootDirectory.resolve(name));
        } catch (IOException e) {
            throw new ForgeDbException("Could not create database directory: " + name, e);
        }
        save();
    }

    public void removeDatabase(String name) {
        String key = databases.keySet().stream()
                .filter(dbName -> dbName.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        if (key == null) {
            throw new ForgeDbException("Database does not exist: " + name);
        }
        databases.remove(key);
        save();
    }
}
