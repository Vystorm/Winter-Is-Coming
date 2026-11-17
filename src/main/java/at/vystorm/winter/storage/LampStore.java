package at.vystorm.winter.storage;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Positions of the Christmas lights. Saves before returning; a failed write leaves the memory unchanged. */
public final class LampStore {
    public static final class Data {
        public Set<String> lamps = new LinkedHashSet<>();
    }
    public record Position(UUID world, int x, int y, int z) {}
    private final VersionedStore<Data> store;
    private final Data data;
    /** @param files Core's file part, {@code api.data().files()} */
    public LampStore(Path path, AtomicFilesInterface files) {
        store = new VersionedStore<>(new VersionedStore.Backend() {
            public String read() {
                try {
                    return StoreFiles.read(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            public void write(String value) {
                try {
                    files.write(path, value.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }, Data.class, 1, Map.of(), Data::new, null);
        data = store.load();
        if (data.lamps == null) throw new IllegalStateException("Invalid lamp file");
    }
    public static String key(UUID world, int x, int y, int z) {
        return world + "," + x + "," + y + "," + z;
    }
    public static Position parse(String key) {
        String[] parts = key.split(",");
        if (parts.length != 4) return null;
        try {
            return new Position(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
    public boolean has(String key) {
        return data.lamps.contains(key);
    }
    public int size() {
        return data.lamps.size();
    }
    public List<String> keys() {
        return List.copyOf(data.lamps);
    }
    public void add(String key) {
        data.lamps.add(key);
        try {
            store.save(data);
        } catch (RuntimeException failure) {
            data.lamps.remove(key);
            throw failure;
        }
    }
    public void remove(String key) {
        data.lamps.remove(key);
        try {
            store.save(data);
        } catch (RuntimeException failure) {
            data.lamps.add(key);
            throw failure;
        }
    }
}
