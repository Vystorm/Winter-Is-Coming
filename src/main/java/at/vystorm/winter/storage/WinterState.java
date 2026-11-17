package at.vystorm.winter.storage;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Low-frequency administrative state and at-most-once New Year celebrations. */
public final class WinterState {
    public static final class Data {
        public String manualDate;
        public boolean thaw;
        public Set<Integer> celebratedYears = new HashSet<>();
    }
    private final VersionedStore<Data> store;
    private Data data;
    /** @param files Core's file part, {@code api.data().files()} */
    public WinterState(Path path, AtomicFilesInterface files) {
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
        if (data.celebratedYears == null) throw new IllegalStateException("Invalid winter state");
    }
    public Data data() {
        return data;
    }
    public void save() {
        store.save(data);
    }
    public void mode(String date, boolean thaw) {
        String previous = data.manualDate;
        boolean previousThaw = data.thaw;
        data.manualDate = date;
        data.thaw = thaw;
        try {
            save();
        } catch (RuntimeException failure) {
            data.manualDate = previous;
            data.thaw = previousThaw;
            throw failure;
        }
    }
    public boolean celebrate(int year) {
        if (!data.celebratedYears.add(year)) return false;
        try {
            save();
            return true;
        } catch (RuntimeException failure) {
            data.celebratedYears.remove(year);
            throw failure;
        }
    }
}
