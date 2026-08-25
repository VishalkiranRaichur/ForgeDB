# ForgeDB

**ForgeDB is a small relational database engine built from scratch in Java for learning database internals.**

It supports a practical subset of SQL, stores fixed-length records in 4 KB disk pages, keeps a small LRU buffer pool, persists schema metadata, and implements a B+ tree primary-key index.

## Why this project exists

Most student database projects call an existing database through JDBC. ForgeDB goes one layer lower: it implements the pieces a database needs to store, find, update, and delete its own rows.

The project was designed after studying the architecture and behavior of Yan Chen's educational C++ `nrthyrk/minidb` project. The Java implementation is reorganized and rewritten for readability instead of being a line-by-line translation. See `NOTICE.md` and `docs/ORIGINAL_ARCHITECTURE.md`.

## Features

- Create, drop, list, and select databases
- Create, drop, and list tables
- `INT`, `FLOAT`, and fixed-width `CHAR(N)` columns
- One optional primary key per table
- Primary-key uniqueness checks
- `INSERT`
- `SELECT *` with optional `WHERE`
- `DELETE` with optional `WHERE`
- `UPDATE ... SET ... WHERE ...`
- Comparison operators: `=`, `<>`, `<`, `>`, `<=`, `>=`
- Multiple `WHERE` conditions joined with `AND`
- One B+ tree index per table, created on the primary key
- Indexed equality lookups
- 4 KB binary record pages
- 12-byte page header: previous page, next page, record count
- Reuse of empty pages after deletes
- LRU buffer pool with dirty-page flushing
- Persistent catalog metadata
- Persistent `.records` and `.index` files
- `EXEC file.sql`
- Interactive command-line shell
- Smoke, storage/index stress, restart-persistence, and deep B+ tree split tests

## Requirements

- JDK 17 or newer
- No third-party Java libraries are required

## Build

The easiest build does not require Maven:

```bash
./scripts/build.sh
```

That creates:

```text
build/forgedb.jar
```

Run it with:

```bash
./scripts/run.sh
```

or:

```bash
java -jar build/forgedb.jar
```

A `pom.xml` is also included, so on a machine with Maven you can use:

```bash
mvn package
java -jar target/forgedb-1.0.0.jar
```

## Run the tests

```bash
./scripts/test.sh
```

The test script runs:

1. A deep B+ tree test with 20,000 keys and a very small tree order to force internal splits.
2. An end-to-end SQL smoke test.
3. A 1,000-row storage/index stress test that crosses multiple disk pages, deletes rows, reuses pages, updates a primary key, rebuilds the index, and verifies persistence after restart.

## Quick demo

Start ForgeDB:

```text
$ ./scripts/run.sh
ForgeDB 1.0.0 — type HELP; for commands.
forgedb>
```

Then try:

```sql
CREATE DATABASE college;
USE college;

CREATE TABLE students (
    id INT,
    gpa FLOAT,
    name CHAR(32),
    PRIMARY KEY (id)
);

INSERT INTO students VALUES (1, 3.70, 'Alice');
INSERT INTO students VALUES (2, 3.90, 'Bob');
INSERT INTO students VALUES (3, 3.20, 'Carol');

CREATE INDEX students_pk ON students (id);

SELECT * FROM students WHERE id = 2;
UPDATE students SET gpa = 3.95 WHERE id = 3;
DELETE FROM students WHERE id = 1;
SELECT * FROM students WHERE gpa >= 3.5;
```

You can also run the included script from the ForgeDB shell:

```sql
EXEC examples/demo.sql;
```

## Architecture

```text
                         +-------------------+
                         |   ForgeDB Shell   |
                         +---------+---------+
                                   |
                                   v
                         +-------------------+
                         |    SQL Parser     |
                         +---------+---------+
                                   |
                                   v
                         +-------------------+
                         |  ForgeDbEngine    |
                         +----+---------+----+
                              |         |
                +-------------+         +-------------+
                v                                     v
       +----------------+                     +----------------+
       | Record Manager |                     | Catalog Manager|
       +-------+--------+                     +----------------+
               |
        +------+-------+
        |              |
        v              v
+---------------+  +----------------+
| Index Manager |  |  Buffer Pool   |
+-------+-------+  +--------+-------+
        |                   |
        v                   v
+---------------+    +--------------+
|    B+ Tree    |    | 4 KB Pages   |
+---------------+    +------+-------+
                            |
                            v
                    +---------------+
                    | .records files|
                    +---------------+
```

### SQL parser

`SqlParser` recognizes ForgeDB's SQL subset and returns small immutable statement objects. It is case-insensitive for SQL keywords and keeps quoted values intact.

### Catalog manager

The catalog stores databases, tables, columns, primary-key information, page-chain metadata, and index definitions. It is persisted to `catalog.ser`.

### Record manager

Rows are encoded according to a table's schema and stored as fixed-length binary records. Each table owns a `.records` file made of 4096-byte pages.

### Buffer pool

`BufferPool` keeps recently used pages in memory. A Java `LinkedHashMap` in access-order mode supplies the LRU policy. Dirty pages are flushed when evicted or when the engine flushes/closes.

### B+ tree index

`BPlusTree` implements leaf and internal nodes, sorted leaf values, linked leaves, leaf splitting, internal splitting, and root growth. Equality predicates on an indexed primary-key column use the B+ tree instead of scanning every row.

ForgeDB intentionally rebuilds an index after deletes or primary-key-changing updates. This is simpler and easier to reason about for an educational database while keeping the important B+ tree insertion/search algorithms real.

## On-disk layout

By default data is stored under:

```text
~/ForgeDBData/
```

Example:

```text
ForgeDBData/
├── catalog.ser
└── college/
    ├── students.records
    └── students_pk.index
```

A record page is exactly 4096 bytes:

```text
byte 0  - 3    previous page number
byte 4  - 7    next page number
byte 8  - 11   number of records in this page
byte 12 - ...  fixed-length row data
```

`-1` means no previous/next page.

## Current scope

ForgeDB is intentionally small. It does **not** implement:

- joins
- transactions / rollback
- concurrent clients or locking
- foreign keys
- authentication/users
- views
- `OR` predicates
- arbitrary column projection (`SELECT name, gpa ...`)
- query planning/cost optimization
- variable-length records
- crash recovery / WAL

Those are good future extensions, but they are not required to understand the core storage/index path.

## Project structure

```text
src/main/java/io/forgedb/
├── ForgeDB.java
├── catalog/
│   ├── CatalogManager.java
│   ├── Column.java
│   ├── DatabaseSchema.java
│   ├── DataType.java
│   ├── IndexDefinition.java
│   └── TableSchema.java
├── cli/
│   └── ForgeDbShell.java
├── engine/
│   └── ForgeDbEngine.java
├── exception/
│   └── ForgeDbException.java
├── index/
│   ├── BPlusTree.java
│   └── IndexManager.java
├── sql/
│   ├── Condition.java
│   ├── Operator.java
│   ├── SqlParser.java
│   ├── Statement.java
│   └── Statements.java
└── storage/
    ├── BufferPool.java
    ├── DbKey.java
    ├── Page.java
    ├── RecordLocation.java
    ├── RecordManager.java
    └── RowCodec.java
```

## License and attribution

The reference C++ project is GPL-3.0 licensed. Because ForgeDB was produced while studying that project, this repository includes the GPL v3 license and explicit attribution in `NOTICE.md`.
