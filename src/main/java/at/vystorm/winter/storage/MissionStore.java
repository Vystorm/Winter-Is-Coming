package at.vystorm.winter.storage;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import at.vystorm.winter.CommunityMissions.Definition;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Copy-on-write seasonal progress. First contribution freezes the goal and reward until season end. */
public final class MissionStore {
    public record Progress(int season, Definition definition, int count) {
        public Progress {
            if (season < 1900 || season > 3000 || definition == null || count < 0 || count > definition.goal())
                throw new IllegalArgumentException("Invalid mission progress");
        }
        public boolean complete() {
            return count == definition.goal();
        }
        public String rewardId() {
            return "community/" + season + "/" + definition.id();
        }
    }
    public static final class Data {
        public Map<String, Progress> progress = new LinkedHashMap<>();
    }
    private final VersionedStore<Data> store;
    private Data data;
    /** @param files Core's file part, {@code api.data().files()} */
    public MissionStore(Path path, AtomicFilesInterface files) {
        this(new VersionedStore.Backend() {
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
    });
}
    public MissionStore(VersionedStore.Backend backend) {
        store = new VersionedStore<>(backend, Data.class, 1, Map.of(), Data::new, null);
        data = store.load();
        if (data.progress == null || data.progress.size() > 256) throw new IllegalStateException("Invalid mission ledger");
        data.progress.forEach((key, p) -> {
            if (p == null || !key.equals(key(p.season(), p.definition().id()))) throw new IllegalStateException("Invalid mission row");
            // Revalidate deserialized fields, including frozen definitions.
            Definition d = p.definition();
            new Definition(d.id(), d.objective(), d.goal(), d.reward(), d.amount());
            new Progress(p.season(), d, p.count());
        });
    }
    private static String key(int season, String id) {
        return season + "/" + id;
    }
    public Progress progress(int season, Definition definition) {
        return data.progress.getOrDefault(key(season, definition.id()), new Progress(season, definition, 0));
    }
    public boolean advance(int season, Definition definition) {
        Progress previous = progress(season, definition);
        if (previous.complete()) return false;
        Data next = new Data();
        next.progress.putAll(data.progress);
        // Retain the current and four previous seasons; receipt IDs remain globally stable in Core's pouch.
        next.progress.values().removeIf(p -> p.season() < season - 4);
        if (next.progress.size() >= 256 && !next.progress.containsKey(key(season, definition.id()))) throw new IllegalStateException("Mission ledger capacity");
        Progress updated = new Progress(season, previous.definition(), previous.count() + 1);
        next.progress.put(key(season, definition.id()), updated);
        store.save(next);
        data = next;
        return updated.complete();
    }
    public List<Progress> completed(int season) {
        return data.progress.values().stream().filter(p -> p.season() == season && p.complete()).toList();
    }
}
