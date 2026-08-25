package io.forgedb;

import io.forgedb.index.BPlusTree;

public class BPlusTreeTest {
    public static void main(String[] args) {
        BPlusTree<Integer, String> tree = new BPlusTree<>(4);
        for (int i = 0; i < 20_000; i++) {
            tree.insert(i, "v" + i);
        }
        if (tree.size() != 20_000) throw new AssertionError("wrong tree size");
        for (int i = 0; i < 20_000; i += 37) {
            String value = tree.search(i);
            if (!("v" + i).equals(value)) throw new AssertionError("missing key " + i);
        }
        if (tree.search(30_000) != null) throw new AssertionError("unexpected missing-key result");
        System.out.println("BPlusTree deep split test PASSED");
    }
}
