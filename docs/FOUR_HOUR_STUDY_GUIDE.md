# ForgeDB 3–4 Hour Study Guide

This guide is for understanding the project well enough to run it, explain the architecture, and make a small change yourself. Do not try to memorize every line.

## Block 1 — 0:00 to 0:35: Run it first

1. Run `./scripts/test.sh`.
2. Run `./scripts/run.sh`.
3. Manually execute:
   - `CREATE DATABASE`
   - `USE`
   - `CREATE TABLE`
   - three `INSERT`s
   - `SELECT`
   - `CREATE INDEX`
   - indexed `SELECT`
   - `UPDATE`
   - `DELETE`
4. Open `~/ForgeDBData` and notice `catalog.ser`, `.records`, and `.index` files.

Goal: know what ForgeDB does before reading implementation details.

## Block 2 — 0:35 to 1:10: Follow one SQL statement

Read these in order:

1. `ForgeDB.java`
2. `ForgeDbShell.java`
3. `SqlParser.java`
4. `Statements.java`
5. `ForgeDbEngine.java`

Trace this statement:

```sql
SELECT * FROM students WHERE id = 2;
```

Be able to explain:

```text
text SQL -> Statement object -> engine -> table schema -> index or scan -> formatted rows
```

## Block 3 — 1:10 to 1:55: Understand disk storage

Read:

1. `Page.java`
2. `RowCodec.java`
3. `BufferPool.java`
4. the insertion part of `RecordManager.java`

Draw this on paper:

```text
4096-byte page
+--------------------+
| previous page 4 B  |
| next page     4 B  |
| row count     4 B  |
+--------------------+
| row 0              |
| row 1              |
| ...                |
+--------------------+
```

Know why fixed-size rows make `12 + slot * recordLength` possible.

## Block 4 — 1:55 to 2:35: Understand the B+ tree

Read `BPlusTree.java` in this order:

1. `search`
2. `findLeaf`
3. `insert`
4. `splitLeaf`
5. `insertIntoParent`
6. `splitInternal`

Then read `IndexManager.java`.

Be able to explain why an indexed `WHERE id = 777` avoids scanning all rows.

Do not memorize split code. Understand the invariant:

- keys stay sorted
- values live in leaves
- internal keys direct searches
- full nodes split
- a split may propagate to the root

## Block 5 — 2:35 to 3:05: Catalog + update/delete correctness

Read:

1. `CatalogManager.java`
2. `TableSchema.java`
3. `RecordManager.delete`
4. `RecordManager.update`

Know:

- what metadata lives in the catalog
- how an empty page is recycled
- why delete compacts a page
- why ForgeDB rebuilds the index after delete/PK update
- how update validates final primary-key uniqueness before writes

## Block 6 — 3:05 to 3:35: Tests

Read:

1. `ForgeDBSmokeTest.java`
2. `ForgeDBStressTest.java`
3. `BPlusTreeTest.java`

Run them again.

Tests are useful interview evidence because they show you thought about correctness rather than only implementing a demo path.

## Block 7 — 3:35 to 4:00: Make one change yourself

Choose one small extension:

- add `DESCRIBE table;`
- add `SHOW INDEXES;`
- improve table output
- add integer-only `COUNT(*)`
- add a new smoke-test case

A small change you make yourself is the fastest way to prove you understand the code.

## Six questions you should be able to answer

1. Why does ForgeDB use fixed-size records?
2. What exactly is stored in the 12-byte page header?
3. What happens when the buffer pool is full?
4. What is the difference between a full table scan and an indexed equality lookup?
5. What happens when a B+ tree leaf is full?
6. Why is the index rebuilt after deleting rows?

If you can answer those clearly and trace one insert/select through the code, you understand the core of the project.
