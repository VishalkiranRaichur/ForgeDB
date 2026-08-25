package io.forgedb;

import io.forgedb.engine.ForgeDbEngine;
import io.forgedb.exception.ForgeDbException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class ForgeDBSmokeTest {
    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("forgedb-smoke-");
        try {
            runDatabaseFlow(data);
            verifyRestart(data);
            System.out.println("ForgeDB smoke test PASSED");
        } finally {
            deleteRecursively(data);
        }
    }

    private static void runDatabaseFlow(Path data) {
        try (ForgeDbEngine db = new ForgeDbEngine(data)) {
            db.execute("CREATE DATABASE school;");
            db.execute("USE school;");
            db.execute("""
                    CREATE TABLE students (
                        id INT,
                        gpa FLOAT,
                        name CHAR(24),
                        PRIMARY KEY (id)
                    );
                    """);

            db.execute("INSERT INTO students VALUES (1, 3.5, 'Alice');");
            db.execute("INSERT INTO students VALUES (2, 3.8, 'Bob');");
            db.execute("INSERT INTO students VALUES (3, 3.2, 'Carol');");
            db.execute("CREATE INDEX students_pk ON students (id);");

            String indexed = db.execute("SELECT * FROM students WHERE id = 2;");
            require(indexed.contains("Bob"), "indexed SELECT did not return Bob");

            String filtered = db.execute("SELECT * FROM students WHERE gpa >= 3.5 AND name <> 'Alice';");
            require(filtered.contains("Bob") && !filtered.contains("Carol"), "WHERE filtering failed");

            db.execute("UPDATE students SET gpa = 3.95, name = 'Carol Smith' WHERE id = 3;");
            String updated = db.execute("SELECT * FROM students WHERE id = 3;");
            require(updated.contains("Carol Smith") && updated.contains("3.95"), "UPDATE failed");

            db.execute("DELETE FROM students WHERE id = 1;");
            String all = db.execute("SELECT * FROM students;");
            require(!all.contains("Alice") && all.contains("Bob") && all.contains("Carol Smith"), "DELETE failed");

            boolean duplicateRejected = false;
            try {
                db.execute("INSERT INTO students VALUES (2, 2.0, 'Duplicate');");
            } catch (ForgeDbException e) {
                duplicateRejected = e.getMessage().toLowerCase().contains("primary key");
            }
            require(duplicateRejected, "duplicate primary key was not rejected");
        }
    }

    private static void verifyRestart(Path data) {
        try (ForgeDbEngine db = new ForgeDbEngine(data)) {
            db.execute("USE school;");
            String result = db.execute("SELECT * FROM students WHERE id = 3;");
            require(result.contains("Carol Smith"), "row did not survive restart");
            require(Files.exists(data.resolve("school").resolve("students_pk.index")),
                    "index file was not persisted");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
