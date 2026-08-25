# Attribution

ForgeDB is a new Java reimplementation created for educational study of database internals.
Its feature set and high-level six-module architecture were studied from Yan Chen's
`nrthyrk/minidb` C++ project (2014), which is distributed under the GNU GPL v3.

The Java source in this repository was rewritten rather than produced as a line-by-line
syntax translation. ForgeDB keeps the educational ideas that make the reference project
valuable—SQL interpretation, catalog metadata, records stored in 4 KB pages, an LRU
buffer manager, and B+ tree indexing—while using Java-native design and adding tests and
correctness checks.

Reference project: https://github.com/nrthyrk/minidb
Original author: Yan Chen
Original license: GNU General Public License v3
