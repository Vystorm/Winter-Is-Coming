package at.vystorm.winter.storage;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.CRC32;

/** Serialized write-ahead log with lock-free snapshot reads. Durable intent precedes world edits. Fail closed on corruption. */
public final class BlockJournal implements AutoCloseable {
    public record Key(UUID world, int x, int y, int z) { }
    public record Entry(int schema, Key key, String original, String previous, String expected, boolean released, Set<String> history) {
        public Entry {
            history = history == null ? Set.of() : Set.copyOf(history);
        }
        public Entry(int schema, Key key, String original, String previous, String expected, boolean released) {
            this(schema, key, original, previous, expected, released, Set.of());
        }
        public boolean accepts(String blockData) {
            return !released && (expected.equals(blockData) || previous.equals(blockData) || history.contains(blockData));
        }
        public boolean winterBlock(String blockData) {
            return accepts(blockData) && !original.equals(blockData);
        }
    }
    private final Gson gson = new Gson();
    private final Map<Key, Entry> entries = new java.util.concurrent.ConcurrentHashMap<>();
    private final FileChannel channel;
    private boolean failed;
    public BlockJournal(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        if (Files.exists(path)) {
            // An incomplete trailing intent is safe to discard: its world edit never ran.
            byte[] bytes = Files.readAllBytes(path);
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] != '\n') end--;
            String content = new String(bytes, 0, end, StandardCharsets.UTF_8);
            for (String line : content.split("\n")) if (!line.isBlank()) {
                int split = line.lastIndexOf('\t');
                if (split < 0 || !checksum(line.substring(0, split)).equals(line.substring(split + 1))) throw new IOException("Corrupt winter journal; refusing world edits");
                Entry e = gson.fromJson(line.substring(0, split), Entry.class);
                if (e == null || e.schema() != 1 || e.key() == null || e.key().world() == null || e.original() == null || e.previous() == null || e.expected() == null) throw new IOException("Unsupported/invalid winter journal");
                entries.put(e.key(), e);
            }
            if (end != bytes.length) {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".torn-" + System.currentTimeMillis()));
                try (FileChannel repair = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    repair.truncate(end);
                    repair.force(true);
                }
            }
        }
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }
    public Entry get(Key key) {
        return entries.get(key);
    }
    public int size() {
        return entries.size();
    }
    public Collection<Entry> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }
    public synchronized Entry intent(Key key, String current, String next) throws IOException {
        Entry old = entries.get(key);
        if (old != null && (old.released() || !old.accepts(current))) return null;
        // A world save may lag several mutations behind the durable log. Keep every generated
        // state, not just the last pair, so an older snow layer after a crash can still be restored.
        Set<String> history = new HashSet<>();
        if (old != null) {
            history.addAll(old.history());
            history.add(old.previous());
            history.add(old.expected());
        }
        history.add(current);
        history.add(next);
        Entry entry = new Entry(1, key, old == null ? current : old.original(), current, next, false, history);
        append(entry);
        return entry;
    }
    /** One planned change; {@code release} gives the position up instead. */
    public record Request(Key key, String current, String next, boolean release) {}
    /**
     * Writes several changes with a single disk sync. Each result is the new entry, the entry after a release, or null
     * when the change was refused (exactly like {@link #intent} and {@link #release}).
     */
    public synchronized List<Entry> apply(List<Request> requests) throws IOException {
        List<Entry> out = new ArrayList<>(requests.size());
        for (Request r : requests) {
            Entry old = entries.get(r.key());
            if (r.release()) {
                if (old != null && !old.released()) write(new Entry(1, r.key(), old.original(), old.previous(), old.expected(), true, old.history()));
                out.add(entries.get(r.key()));
                continue;
            }
            if (old != null && (old.released() || !old.accepts(r.current()))) {
                out.add(null);
                continue;
            }
            Set<String> history = new HashSet<>();
            if (old != null) {
                history.addAll(old.history());
                history.add(old.previous());
                history.add(old.expected());
            }
            history.add(r.current());
            history.add(r.next());
            Entry entry = new Entry(1, r.key(), old == null ? r.current() : old.original(), r.current(), r.next(), false, history);
            write(entry);
            out.add(entry);
        }
        try {
            channel.force(true);
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
        return out;
    }
    private void write(Entry entry) throws IOException {
        if (failed) throw new IOException("Winter journal is faulted; restart after repairing storage");
        String json = gson.toJson(entry);
        ByteBuffer data = StandardCharsets.UTF_8.encode(json + '\t' + checksum(json) + '\n');
        try {
            while (data.hasRemaining()) channel.write(data);
            entries.put(entry.key(), entry);
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
    }
    /** Permanent relinquishment also protects same-material player replacements after a restart. */
    public synchronized void release(Key key) throws IOException {
        Entry old = entries.get(key);
        if (old == null || old.released()) return;
        append(new Entry(1, key, old.original(), old.previous(), old.expected(), true, old.history()));
    }
    private void append(Entry entry) throws IOException {
        if (failed) throw new IOException("Winter journal is faulted; restart after repairing storage");
        String json = gson.toJson(entry);
        ByteBuffer data = StandardCharsets.UTF_8.encode(json + '\t' + checksum(json) + '\n');
        try {
            while (data.hasRemaining()) channel.write(data);
            channel.force(true);
            entries.put(entry.key(), entry);
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
    }
    private static String checksum(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }
    @Override public synchronized void close() throws IOException {
        channel.close();
    }
}
