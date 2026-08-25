package io.forgedb.storage;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

public class Page {
    public static final int PAGE_SIZE = 4096;
    public static final int HEADER_SIZE = 12;

    private final Path file;
    private final int pageNumber;
    private final byte[] data;
    private boolean dirty;

    Page(Path file, int pageNumber, byte[] data) {
        this.file = file;
        this.pageNumber = pageNumber;
        this.data = data;
    }

    public void reset() {
        Arrays.fill(data, (byte) 0);
        setPreviousPage(-1);
        setNextPage(-1);
        setRecordCount(0);
        dirty = true;
    }

    public int getPreviousPage() {
        return buffer().getInt(0);
    }

    public void setPreviousPage(int pageNumber) {
        buffer().putInt(0, pageNumber);
        dirty = true;
    }

    public int getNextPage() {
        return buffer().getInt(4);
    }

    public void setNextPage(int pageNumber) {
        buffer().putInt(4, pageNumber);
        dirty = true;
    }

    public int getRecordCount() {
        return buffer().getInt(8);
    }

    public void setRecordCount(int count) {
        buffer().putInt(8, count);
        dirty = true;
    }

    public byte[] readRecord(int slot, int recordLength) {
        int start = HEADER_SIZE + slot * recordLength;
        return Arrays.copyOfRange(data, start, start + recordLength);
    }

    public void writeRecord(int slot, int recordLength, byte[] record) {
        int start = HEADER_SIZE + slot * recordLength;
        System.arraycopy(record, 0, data, start, recordLength);
        dirty = true;
    }

    public void clearRecord(int slot, int recordLength) {
        int start = HEADER_SIZE + slot * recordLength;
        Arrays.fill(data, start, start + recordLength, (byte) 0);
        dirty = true;
    }

    Path getFile() {
        return file;
    }

    int getPageNumberInternal() {
        return pageNumber;
    }

    byte[] getDataInternal() {
        return data;
    }

    boolean isDirty() {
        return dirty;
    }

    void markClean() {
        dirty = false;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    private ByteBuffer buffer() {
        return ByteBuffer.wrap(data);
    }
}
