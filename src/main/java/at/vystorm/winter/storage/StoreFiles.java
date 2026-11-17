package at.vystorm.winter.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only an absent file is a new store; empty or unreadable existing files must fail closed. */
final class StoreFiles {
    private StoreFiles() {}
    static String read(Path path) throws IOException {
        if (Files.notExists(path)) return null;
        String value = Files.readString(path);
        if (value.isBlank()) throw new IOException("Empty winter store: " + path);
        return value;
    }
}
