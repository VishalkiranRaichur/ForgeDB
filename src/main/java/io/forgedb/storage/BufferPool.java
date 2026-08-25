package io.forgedb.storage;

import io.forgedb.exception.ForgeDbException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small LRU buffer pool. A page is loaded only when needed and dirty pages are
 * written back on eviction or flush.
 */
public class BufferPool implements AutoCloseable {
    private final int capacity;
    private final LinkedHashMap<PageKey, Page> pages = new LinkedHashMap<>(16, 0.75f, true);

    public BufferPool(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("Buffer pool capacity must be at least 2 pages");
        }
        this.capacity = capacity;
    }

    public synchronized Page getPage(Path file, int pageNumber) {
        Path normalized = file.toAbsolutePath().normalize();
        PageKey key = new PageKey(normalized, pageNumber);
        Page cached = pages.get(key);
        if (cached != null) {
            return cached;
        }

        evictIfNeeded();
        Page loaded = loadPage(normalized, pageNumber);
        pages.put(key, loaded);
        return loaded;
    }

    public synchronized Page resetPage(Path file, int pageNumber) {
        Page page = getPage(file, pageNumber);
        page.reset();
        return page;
    }

    public synchronized void flushAll() {
        for (Page page : pages.values()) {
            flushPage(page);
        }
    }

    public synchronized void discardFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Iterator<Map.Entry<PageKey, Page>> iterator = pages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PageKey, Page> entry = iterator.next();
            if (entry.getKey().file().equals(normalized)) {
                iterator.remove();
            }
        }
    }

    private Page loadPage(Path file, int pageNumber) {
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
            byte[] data = new byte[Page.PAGE_SIZE];
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                long offset = (long) pageNumber * Page.PAGE_SIZE;
                if (raf.length() >= offset + Page.PAGE_SIZE) {
                    raf.seek(offset);
                    raf.readFully(data);
                }
            }
            return new Page(file, pageNumber, data);
        } catch (IOException e) {
            throw new ForgeDbException("Could not read page " + pageNumber + " from " + file, e);
        }
    }

    private void evictIfNeeded() {
        if (pages.size() < capacity) {
            return;
        }
        Iterator<Map.Entry<PageKey, Page>> iterator = pages.entrySet().iterator();
        if (iterator.hasNext()) {
            Map.Entry<PageKey, Page> eldest = iterator.next();
            flushPage(eldest.getValue());
            iterator.remove();
        }
    }

    private void flushPage(Page page) {
        if (!page.isDirty()) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(page.getFile().toFile(), "rw")) {
            raf.seek((long) page.getPageNumberInternal() * Page.PAGE_SIZE);
            raf.write(page.getDataInternal());
            page.markClean();
        } catch (IOException e) {
            throw new ForgeDbException("Could not flush database page", e);
        }
    }

    @Override
    public void close() {
        flushAll();
        pages.clear();
    }

    private record PageKey(Path file, int pageNumber) {
    }
}
