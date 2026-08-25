package io.forgedb;

import io.forgedb.engine.ForgeDbEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class ForgeDBStressTest {
    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("forgedb-stress-");
        try {
            try (ForgeDbEngine db = new ForgeDbEngine(data)) {
                db.execute("CREATE DATABASE stress;");
                db.execute("USE stress;");
                db.execute("CREATE TABLE items (id INT, score FLOAT, label CHAR(40), PRIMARY KEY (id));");
                for (int i = 0; i < 1000; i++) {
                    db.execute("INSERT INTO items VALUES (" + i + ", " + (i / 10.0f) + ", 'item " + i + "');");
                }
                db.execute("CREATE INDEX items_pk ON items (id);");
                require(db.execute("SELECT * FROM items WHERE id = 777;").contains("item 777"), "index split/search failed");
                require(db.execute("SELECT * FROM items WHERE label = 'item 10';").contains("item 10"), "scan failed");

                db.execute("DELETE FROM items WHERE id < 600;");
                require(db.execute("SELECT * FROM items WHERE id = 777;").contains("item 777"), "index rebuild after delete failed");
                require(db.execute("SELECT * FROM items WHERE id = 100;").endsWith("0 rows."), "deleted row still visible");

                db.execute("UPDATE items SET id = 2000, label = 'rock and roll' WHERE id = 999;");
                require(db.execute("SELECT * FROM items WHERE id = 2000 AND label = 'rock and roll';").contains("rock and roll"),
                        "PK update/index rebuild failed");

                for (int i = 3000; i < 3300; i++) {
                    db.execute("INSERT INTO items VALUES (" + i + ", 1.0, 'reuse');");
                }
                require(db.execute("SELECT * FROM items WHERE id = 3299;").contains("reuse"), "post-delete insert failed");
            }

            try (ForgeDbEngine db = new ForgeDbEngine(data)) {
                db.execute("USE stress;");
                require(db.execute("SELECT * FROM items WHERE id = 2000;").contains("rock and roll"), "restart after stress failed");
            }
            System.out.println("ForgeDB stress test PASSED");
        } finally {
            deleteRecursively(data);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
