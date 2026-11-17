package at.vystorm.winter.storage;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Opened Advent calendar doors of the current December, for the menu. The reward pouch ledger stays the authority
 * against double gifts; this file only remembers what to draw. A new year starts with an empty calendar.
 */
public final class CalendarStore {
    public static final class Data {
        public int year;
        public Map<String, Set<Integer>> opened = new HashMap<>();
    }
    private final VersionedStore<Data> store;
    private Data data;
    /** @param files Core's file part, {@code api.data().files()} */
    public CalendarStore(Path path, AtomicFilesInterface files) {
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
        if (data.opened == null || data.opened.values().stream().anyMatch(Objects::isNull)) throw new IllegalStateException("Invalid calendar ledger");
        if (data.opened.values().stream().flatMap(Set::stream).anyMatch(day -> day == null || day < 1 || day > 24)) throw new IllegalStateException("Invalid calendar day");
    }
    public Set<Integer> opened(UUID player, int year) {
        if (data.year != year) return Set.of();
        Set<Integer> days = data.opened.get(player.toString());
        return days == null ? Set.of() : Set.copyOf(days);
    }
    /** Saves before returning; restores the previous state if the write fails. */
    public void open(UUID player, int year, int day) {
        int previousYear = data.year;
        Map<String, Set<Integer>> previous = copy(data.opened);
        if (data.year != year) {
            data.year = year;
            data.opened = new HashMap<>();
        }
        data.opened.computeIfAbsent(player.toString(), k -> new TreeSet<>()).add(day);
        try {
            store.save(data);
        } catch (RuntimeException failure) {
            data.year = previousYear;
            data.opened = previous;
            throw failure;
        }
    }
    private static Map<String, Set<Integer>> copy(Map<String, Set<Integer>> map) {
        Map<String, Set<Integer>> copy = new HashMap<>();
        map.forEach((k, v) -> copy.put(k, new TreeSet<>(v)));
        return copy;
    }
}
