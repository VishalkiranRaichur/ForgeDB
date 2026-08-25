package io.forgedb.storage;

import java.io.Serializable;

public record RecordLocation(int pageNumber, int slot) implements Serializable {
}
