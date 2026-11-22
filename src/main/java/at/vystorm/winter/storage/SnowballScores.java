package at.vystorm.winter.storage;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Snowball fight leaderboard of one winter season. Hits arrive often, so changes are kept in memory and written by
 * {@link #saveIfDirty()} (periodically and on shutdown). A new season starts with an empty board.
 */
public final class SnowballScores {
    public static final class Data {
        public int season;
        public Map<String, Entry> players = new HashMap<>();
    }
    public static final class Entry {
        public String name;
        public int hits;
        public int wins;
    }
    public record Row(UUID player, String name, int hits, int wins) {}
    private final VersionedStore<Data> store;
    private Data data;
    private boolean dirty;
    /** @param files Core's file part, {@code api.data().files()} */
    public SnowballScores(Path path, AtomicFilesInterface files) {
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
        if (data.players == null || data.players.values().stream().anyMatch(Objects::isNull)) throw new IllegalStateException("Invalid snowball ledger");
        data.players.keySet().forEach(UUID::fromString);
    }
    public void hit(UUID player, String name, int season) {
        entry(player, name, season).hits++;
    }
    public void win(UUID player, String name, int season) {
        entry(player, name, season).wins++;
    }

    public Row row(UUID player, int season) {
        Entry entry = data.season == season ? data.players.get(player.toString()) : null;
        return entry == null ? new Row(player, "", 0, 0) : new Row(player, entry.name, entry.hits, entry.wins);
    }

    /** Every player on the board of {@code season}, unsorted, from memory (offline players included); empty for another season. */
    public List<Row> rows(int season) {
        if (data.season != season) return List.of();
        List<Row> rows = new ArrayList<>(data.players.size());
        data.players.forEach((id, e) -> rows.add(new Row(UUID.fromString(id), e.name == null ? "" : e.name, e.hits, e.wins)));
        return rows;
    }

    /** Best players by hits, then duel wins, then name. */
    public List<Row> top(int limit, int season) {
        if (data.season != season) return List.of();
        return data.players.entrySet().stream()
                .map(e -> new Row(UUID.fromString(e.getKey()), e.getValue().name, e.getValue().hits, e.getValue().wins))
                .sorted(Comparator.comparingInt(Row::hits).reversed().thenComparing(Comparator.comparingInt(Row::wins).reversed())
                        .thenComparing(Row::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(limit).toList();
    }

    /** 1-based place on the board, 0 without any hits or wins this season. */
    public int rank(UUID player, int season) {
        Row own = row(player, season);
        if (own.hits() == 0 && own.wins() == 0) return 0;
        List<Row> all = top(Integer.MAX_VALUE, season);
        for (int i = 0; i < all.size(); i++) if (all.get(i).player().equals(player)) return i + 1;
        return 0;
    }

    /** Writes pending changes; on failure they stay pending for the next attempt. */
    public void saveIfDirty() {
        if (!dirty) return;
        store.save(data);
        dirty = false;
    }

    private Entry entry(UUID player, String name, int season) {
        if (data.season != season) {
            data.season = season;
            data.players = new HashMap<>();
        }
        Entry entry = data.players.computeIfAbsent(player.toString(), k -> new Entry());
        entry.name = name;
        dirty = true;
        return entry;
    }
}
