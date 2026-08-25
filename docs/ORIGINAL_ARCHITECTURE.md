# MiniDB C++ Reference → ForgeDB Java Design Notes

This document explains what was learned from the C++ reference project and how those ideas were reconstructed in ForgeDB.

## 1. Reference project scope

The C++ MiniDB project is a deliberately simplified relational database engine. Its public feature set includes:

- database creation/removal/listing/selection
- table creation/removal/listing
- `INT`, `FLOAT`, and `CHAR(N)` columns
- one primary key
- one B+ tree index per table, only on the primary key
- insert/select/delete/update
- equality and comparison predicates combined using `AND`
- interactive SQL execution and SQL files

It explicitly does not implement joins, transactions, users/authentication, foreign keys, views, or complex selects.

ForgeDB keeps essentially the same educational boundary.

## 2. Six-module C++ architecture

The original documentation separates the engine into six main modules:

1. **Interpreter** — formats and parses SQL and chooses the statement type.
2. **API** — coordinates the managers and maintains the selected database.
3. **Record Manager** — inserts, scans, selects, deletes, and updates rows.
4. **Index Manager** — creates and maintains a B+ tree.
5. **Catalog Manager** — stores database/table/attribute/index metadata.
6. **Buffer Manager** — caches 4 KB blocks and writes dirty data back to disk.

ForgeDB preserves the same responsibilities but maps them into Java classes instead of reproducing the C++ class layout exactly.

| C++ concept | ForgeDB Java equivalent |
|---|---|
| `Interpreter` | `SqlParser` + `ForgeDbShell` |
| `MiniDBAPI` | `ForgeDbEngine` |
| `RecordManager` | `RecordManager` |
| `IndexManager` / `BPlusTree` | `IndexManager` / `BPlusTree` |
| `CatalogManager`, `Database`, `Table`, `Attribute`, `Index` | `CatalogManager`, `DatabaseSchema`, `TableSchema`, `Column`, `IndexDefinition` |
| `BufferManager`, `FileHandle`, `BlockHandle`, `BlockInfo` | `BufferPool` + `Page` |
| `TKey` | normal Java values + `DbKey` |
| CMake | Maven + small JDK-only build scripts |
| Boost filesystem | `java.nio.file` |
| Boost regex | `java.util.regex` |
| Boost serialization | Java catalog serialization |
| GNU Readline | `BufferedReader` shell |

## 3. Types and operators

The reference code assigns numeric constants to three data types:

- integer
- float
- fixed-size char

and six comparison operators:

- `=`
- `<>`
- `<`
- `>`
- `<=`
- `>=`

ForgeDB replaces numeric constants with the `DataType` and `Operator` enums. This removes magic numbers while keeping exactly the same ideas.

## 4. Catalog information

The original `Table` metadata stores:

- table name
- fixed record length
- first used block number
- first recycled/rubbish block number
- block count
- attributes
- indexes

Each attribute stores:

- column name
- data type
- byte length
- whether it is the primary key

The index metadata stores its name, indexed attribute, key type/length, B+ tree rank, root/leaf information, and counts.

ForgeDB stores the same important logical metadata but does not persist B+ tree node numbers inside the catalog because the Java index file can be loaded into a fresh B+ tree directly.

`TableSchema` therefore keeps:

- table name
- columns
- fixed record length
- first/last active page
- total allocated page count
- recyclable page numbers
- optional index definition

## 5. Record page format

A particularly useful detail in the reference implementation is the block format. A block is exactly 4 KB and the first 12 bytes contain three 32-bit integers:

```text
0..3    previous block
4..7    next block
8..11   record count
12..    record bytes
```

ForgeDB preserves that layout exactly in `Page`.

For a table whose row size is `R`, the number of rows in one page is:

```text
floor((4096 - 12) / R)
```

Rows are fixed-length, making row position simple:

```text
12 + slot * recordLength
```

## 6. Fixed-length row encoding

The C++ engine stores:

- `INT` as 4 bytes
- `FLOAT` as 4 bytes
- `CHAR(N)` as exactly N bytes

ForgeDB uses the same byte sizes. `RowCodec` converts between Java values and binary rows.

This design has an important educational advantage: locating a row does not require a slotted-page directory or variable-length offset table.

## 7. Buffer replacement

The original buffer manager tracks an age for blocks. When it needs to recycle a block and no free buffer exists, it chooses the oldest block, writes it if dirty, then reuses the memory. That is an LRU-style replacement strategy.

ForgeDB implements the same policy more directly with an access-ordered `LinkedHashMap`. The least recently accessed page is the first eviction candidate. Dirty pages are written back before eviction.

## 8. Record insertion

The reference insertion flow is roughly:

1. Resolve the table from the selected database.
2. Convert SQL values into typed key/value bytes.
3. Enforce primary-key uniqueness.
4. Find a non-full active block.
5. Otherwise reuse a recycled block or allocate a new block.
6. Copy row bytes into the block.
7. Increase the record count.
8. Add the indexed key to the B+ tree if an index exists.
9. Flush changed metadata/pages.

ForgeDB follows the same sequence at a higher level.

## 9. Select path

The C++ record manager checks whether a `WHERE` condition is an equality predicate on an indexed attribute.

If not, it scans active blocks and tests every row.

If yes, it asks the B+ tree for a packed record location, loads that row, then checks any remaining `WHERE` conditions.

ForgeDB preserves exactly that optimization:

```text
indexed PK equality? ---- yes ---> B+ tree lookup ---> one row
         |
         no
         v
     page scan
```

## 10. B+ tree behavior

The reference project implements real B+ tree nodes and supports:

- search
- insertion
- splitting
- root creation/growth
- leaf links
- deletion/rebalancing

ForgeDB implements the key educational algorithms directly:

- ordered leaf insertion
- leaf splitting
- promoted separator keys
- internal-node splitting
- root growth
- linked leaves
- equality lookup

For delete and primary-key-changing update operations, ForgeDB deliberately chooses a simpler strategy: modify the record file and rebuild the index from remaining rows. This keeps externally visible behavior correct while avoiding a much larger underflow/merge implementation in a time-bounded educational project.

The B+ tree itself is still a genuine multi-level tree. `BPlusTreeTest` inserts 20,000 keys using order 4 specifically to force repeated leaf and internal splits.

## 11. Primary-key handling

The reference engine rejects duplicate primary keys. With an index it can use the B+ tree; without one it scans rows.

ForgeDB also guarantees primary-key uniqueness. Updates are validated before any changed rows are written, so a multi-row update cannot partly succeed and then fail because of a duplicate key.

## 12. Delete behavior and page recycling

The reference record manager compacts a page by moving its last row into the deleted row's slot. If a page becomes empty, it removes that block from the active chain and adds it to a recycled/rubbish chain.

ForgeDB uses the same compact-in-page idea and recycles empty page numbers. Since compaction can change record locations, ForgeDB rebuilds the B+ tree after deletes to make every index pointer correct.

## 13. Java-specific design changes

Several C++ techniques should not be copied into Java:

### Raw pointers
C++ objects often use pointers to buffer memory and linked-list nodes. ForgeDB uses object references, records, and collections.

### `char*` values
The original `TKey` stores a manually allocated byte buffer. ForgeDB stores typed Java values and uses `RowCodec` only at the disk boundary.

### Manual linked lists for the buffer
Java already provides an efficient access-ordered `LinkedHashMap`, so writing a custom buffer linked list would add noise without teaching more database theory.

### Boost
Filesystem and regex functionality map naturally to the Java standard library. Catalog serialization can also use the JDK.

## 14. Important improvements over a mechanical port

ForgeDB intentionally improves several areas:

- names and types replace magic numeric constants
- one clear engine facade coordinates modules
- quote-aware SQL splitting
- validation happens before dangerous writes where possible
- page file access is isolated behind `BufferPool`
- primary-key updates are validated as a set
- delete compaction is followed by index rebuilding, preventing stale row locations
- tests cover persistence and B+ tree deep splits
- no third-party runtime libraries are required

## 15. Suggested explanation in an interview

A concise way to explain ForgeDB is:

> ForgeDB is an educational relational database engine I built in Java. SQL is parsed into statement objects, the engine resolves catalog metadata, records are encoded into fixed-size rows inside 4 KB pages, and an LRU buffer pool caches pages before flushing dirty data to disk. Tables can have a primary-key B+ tree index, so equality lookups can avoid a full table scan. The project persists both catalog metadata and row/index files and includes tests for page growth, index splits, deletes, updates, and restart persistence.

Be ready to draw the path of a query:

```text
SQL -> parser -> engine -> catalog -> record/index manager -> buffer pool -> disk page
```

That explanation is more valuable than memorizing individual methods.
