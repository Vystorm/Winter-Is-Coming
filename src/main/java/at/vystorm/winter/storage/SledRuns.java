package at.vystorm.winter.storage;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.storage.VersionedStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Sled runs ({@code sledruns.json}): named tracks with a start and a finish point and the ten best times per track
 * (one entry per player, only their best). Server thread.
 */
public final class SledRuns {
    public static final int TOP = 10, MAX_RUNS = 64;

    public static final class Data {
        public Map<String, Run> runs = new TreeMap<>();
    }
    public static final class Run {
        public String world;
        public double sx, sy, sz;
        public boolean hasFinish;
        public double fx, fy, fz;
        public List<Time> best = new ArrayList<>();
    }
    public static final class Time {
        public String player, name;
        public long millis;
        public Time() {}
        public Time(String player, String name, long millis) {
            this.player = player;
            this.name = name;
            this.millis = millis;
        }
    }
    /** Place of a new time: 1-based rank on the board (0 = not in the top ten), and whether it is the player's new best. */
    public record Result(int rank, boolean personalBest) {}

    private final VersionedStore<Data> store;
    private Data data;

    /** @param files Core's file part, {@code api.data().files()} */
    public SledRuns(Path path, AtomicFilesInterface files) {
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
        if (data.runs == null) data.runs = new TreeMap<>();
        data.runs.values().removeIf(Objects::isNull);
        for (Run run : data.runs.values()) if (run.best == null) run.best = new ArrayList<>();
    }

    /** Lowercase name of 1-24 letters, digits, - or _, or null. */
    public static String name(String raw) {
        if (raw == null) return null;
        String n = raw.strip().toLowerCase(Locale.ROOT);
        return n.matches("[a-z0-9_-]{1,24}") ? n : null;
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(data.runs.keySet());
    }
    public Optional<Run> run(String name) {
        return Optional.ofNullable(data.runs.get(name));
    }

    /** Sets (or moves) the start; false when the run limit is reached. */
    public boolean start(String name, String world, double x, double y, double z) {
        Run run = data.runs.get(name);
        if (run == null) {
            if (data.runs.size() >= MAX_RUNS) return false;
            run = new Run();
            data.runs.put(name, run);
        } else if (!world.equals(run.world)) {
            run.hasFinish = false;
            run.best.clear();
        }
        run.world = world;
        run.sx = x;
        run.sy = y;
        run.sz = z;
        run.best.clear();
        store.save(data);
        return true;
    }

    /** Sets the finish of an existing run in the same world; false otherwise. Clears the board (the track changed). */
    public boolean finish(String name, String world, double x, double y, double z) {
        Run run = data.runs.get(name);
        if (run == null || !world.equals(run.world)) return false;
        run.hasFinish = true;
        run.fx = x;
        run.fy = y;
        run.fz = z;
        run.best.clear();
        store.save(data);
        return true;
    }

    public boolean delete(String name) {
        if (data.runs.remove(name) == null) return false;
        store.save(data);
        return true;
    }

    /** Records a finished run; saves when the board changed. */
    public Result record(String name, UUID player, String playerName, long millis) {
        Run run = data.runs.get(name);
        if (run == null || millis <= 0) return new Result(0, false);
        Result result = insert(run.best, player.toString(), playerName, millis);
        store.save(data);
        return result;
    }

    /** Keeps each player's best time only, sorted, at most {@link #TOP}. */
    static Result insert(List<Time> best, String player, String name, long millis) {
        Time own = best.stream().filter(t -> player.equals(t.player)).findFirst().orElse(null);
        boolean personal = own == null || millis < own.millis;
        if (personal) {
            best.removeIf(t -> player.equals(t.player));
            best.add(new Time(player, name, millis));
            best.sort(Comparator.comparingLong(t -> t.millis));
            while (best.size() > TOP) best.removeLast();
        }
        int rank = 0;
        for (int i = 0; i < best.size(); i++) if (player.equals(best.get(i).player)) {
            rank = i + 1;
            break;
        }
        return new Result(rank, personal);
    }

    /** "1:02.35" or "42.10 s". */
    public static String format(long millis) {
        long cs = millis / 10, minutes = cs / 6000, seconds = cs / 100 % 60, hundredths = cs % 100;
        return minutes > 0 ? String.format(Locale.ROOT, "%d:%02d.%02d", minutes, seconds, hundredths)
                : String.format(Locale.ROOT, "%d.%02d s", seconds, hundredths);
    }
}
