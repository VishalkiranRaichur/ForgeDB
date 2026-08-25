package io.forgedb.index;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A compact educational B+ tree. Values live only in leaves and leaf nodes are
 * linked, which makes the structure suitable for database indexes.
 */
public class BPlusTree<K extends Comparable<K>, V> {
    private final int order;
    private final int maxKeys;
    private Node<K, V> root;

    public BPlusTree(int order) {
        if (order < 4) {
            throw new IllegalArgumentException("B+ tree order must be at least 4");
        }
        this.order = order;
        this.maxKeys = order - 1;
        this.root = new Node<>(true);
    }

    public V search(K key) {
        Node<K, V> leaf = findLeaf(key);
        int index = Collections.binarySearch(leaf.keys, key);
        return index >= 0 ? leaf.values.get(index) : null;
    }

    public void insert(K key, V value) {
        Node<K, V> leaf = findLeaf(key);
        int index = Collections.binarySearch(leaf.keys, key);
        if (index >= 0) {
            leaf.values.set(index, value);
            return;
        }

        int insertionPoint = -index - 1;
        leaf.keys.add(insertionPoint, key);
        leaf.values.add(insertionPoint, value);

        if (leaf.keys.size() > maxKeys) {
            splitLeaf(leaf);
        }
    }

    public List<Map.Entry<K, V>> entries() {
        List<Map.Entry<K, V>> result = new ArrayList<>();
        Node<K, V> leaf = leftMostLeaf();
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                result.add(new AbstractMap.SimpleImmutableEntry<>(leaf.keys.get(i), leaf.values.get(i)));
            }
            leaf = leaf.next;
        }
        return result;
    }

    public int size() {
        int count = 0;
        Node<K, V> leaf = leftMostLeaf();
        while (leaf != null) {
            count += leaf.keys.size();
            leaf = leaf.next;
        }
        return count;
    }

    public int getOrder() {
        return order;
    }

    private Node<K, V> findLeaf(K key) {
        Node<K, V> node = root;
        while (!node.leaf) {
            int childIndex = 0;
            while (childIndex < node.keys.size() && key.compareTo(node.keys.get(childIndex)) >= 0) {
                childIndex++;
            }
            node = node.children.get(childIndex);
        }
        return node;
    }

    private Node<K, V> leftMostLeaf() {
        Node<K, V> node = root;
        while (!node.leaf) {
            node = node.children.get(0);
        }
        return node;
    }

    private void splitLeaf(Node<K, V> leaf) {
        int splitPoint = (leaf.keys.size() + 1) / 2;
        Node<K, V> right = new Node<>(true);
        right.parent = leaf.parent;

        right.keys.addAll(new ArrayList<>(leaf.keys.subList(splitPoint, leaf.keys.size())));
        right.values.addAll(new ArrayList<>(leaf.values.subList(splitPoint, leaf.values.size())));
        leaf.keys.subList(splitPoint, leaf.keys.size()).clear();
        leaf.values.subList(splitPoint, leaf.values.size()).clear();

        right.next = leaf.next;
        leaf.next = right;

        K separator = right.keys.get(0);
        insertIntoParent(leaf, separator, right);
    }

    private void splitInternal(Node<K, V> node) {
        int middle = node.keys.size() / 2;
        K promoted = node.keys.get(middle);

        Node<K, V> right = new Node<>(false);
        right.parent = node.parent;
        right.keys.addAll(new ArrayList<>(node.keys.subList(middle + 1, node.keys.size())));
        right.children.addAll(new ArrayList<>(node.children.subList(middle + 1, node.children.size())));
        for (Node<K, V> child : right.children) {
            child.parent = right;
        }

        node.keys.subList(middle, node.keys.size()).clear();
        node.children.subList(middle + 1, node.children.size()).clear();

        insertIntoParent(node, promoted, right);
    }

    private void insertIntoParent(Node<K, V> left, K key, Node<K, V> right) {
        if (left.parent == null) {
            Node<K, V> newRoot = new Node<>(false);
            newRoot.keys.add(key);
            newRoot.children.add(left);
            newRoot.children.add(right);
            left.parent = newRoot;
            right.parent = newRoot;
            root = newRoot;
            return;
        }

        Node<K, V> parent = left.parent;
        int leftIndex = parent.children.indexOf(left);
        parent.keys.add(leftIndex, key);
        parent.children.add(leftIndex + 1, right);
        right.parent = parent;

        if (parent.keys.size() > maxKeys) {
            splitInternal(parent);
        }
    }

    private static final class Node<K extends Comparable<K>, V> {
        private final boolean leaf;
        private final List<K> keys = new ArrayList<>();
        private final List<V> values = new ArrayList<>();
        private final List<Node<K, V>> children = new ArrayList<>();
        private Node<K, V> parent;
        private Node<K, V> next;

        private Node(boolean leaf) {
            this.leaf = leaf;
        }
    }
}
