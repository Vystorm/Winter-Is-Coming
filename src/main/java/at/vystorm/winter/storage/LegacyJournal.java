package at.vystorm.winter.storage;

import at.vystorm.winter.storage.TrackedBlocks.ChunkId;
import at.vystorm.winter.storage.TrackedBlocks.Key;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.CRC32;

/**
 * The journal of 1.24.1 and older ({@code blocks.jsonl}: one checksummed JSON line per change, never compacted), read
 * once and handed to {@link TrackedBlocks} chunk by chunk (1.24.2). A copy of the original file is kept as
 * {@code blocks.jsonl.bak-1.24.1}. Lines of a chunk leave this journal only after the chunk was read back from the saved
 * world with its new record in a later server run; what is left is written back compacted (one line per position, the
 * old format, so an older version could still read it), through a new file that replaces the old one in one step.
 * Corruption fails closed like before.
 */
public final class LegacyJournal implements AutoCloseable {
    /** One position as the old journal stored it. */
    public record Line(int schema, Key key, String original, String previous, String expected, boolean released, Set<String> history) {
        public boolean accepts(String blockData) {
            return !released && (expected.equals(blockData) || previous.equals(blockData) || history != null && history.contains(blockData));
        }
    }
    public static final String BACKUP_SUFFIX = ".bak-1.24.1";
    private final Gson gson = new Gson();
    private final Path path;
    private final Map<ChunkId, List<Line>> chunks = new HashMap<>();
    private int size;
    private boolean changed;
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Winter-old-journal");
        thread.setDaemon(true);
        return thread;
    });
    private java.util.concurrent.Future<?> pending;
    /** A background save failed: the old file is still whole; the next save writes everything again. */
    private volatile boolean writeFailed;

    private LegacyJournal(Path path) {
        this.path = path;
    }

    /** Reads the old journal; null when there is none or it is empty. */
    public static LegacyJournal open(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) return null;
        Path backup = path.resolveSibling(path.getFileName() + BACKUP_SUFFIX);
        if (!Files.exists(backup)) Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
        LegacyJournal journal = new LegacyJournal(path);
        journal.read();
        return journal.size == 0 ? null : journal;
    }

    private void read() throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int end = bytes.length;
        // An incomplete trailing line is safe to ignore: its world edit never ran.
        while (end > 0 && bytes[end - 1] != '\n') end--;
        Map<Key, Line> last = new HashMap<>();
        Map<String, String> strings = new HashMap<>();
        int start = 0;
        for (int i = 0; i < end; i++) {
            if (bytes[i] != '\n') continue;
            String line = new String(bytes, start, i - start, StandardCharsets.UTF_8);
            start = i + 1;
            if (line.isBlank()) continue;
            int split = line.lastIndexOf('\t');
            if (split < 0 || !checksum(line.substring(0, split)).equals(line.substring(split + 1))) throw new IOException("Corrupt winter journal; refusing world edits");
            Line e = gson.fromJson(line.substring(0, split), Line.class);
            if (e == null || e.schema() != 1 || e.key() == null || e.key().world() == null || e.original() == null || e.previous() == null || e.expected() == null) throw new IOException("Unsupported/invalid winter journal");
            Set<String> history = new HashSet<>();
            if (e.history() != null) for (String s : e.history()) history.add(strings.computeIfAbsent(s, k -> k));
            last.put(e.key(), new Line(1, e.key(), strings.computeIfAbsent(e.original(), k -> k), strings.computeIfAbsent(e.previous(), k -> k),
                    strings.computeIfAbsent(e.expected(), k -> k), e.released(), Set.copyOf(history)));
        }
        for (Line line : last.values()) chunks.computeIfAbsent(ChunkId.of(line.key()), unused -> new ArrayList<>()).add(line);
        size = last.size();
    }

    public boolean has(ChunkId chunk) {
        return chunks.containsKey(chunk);
    }
    public List<Line> lines(ChunkId chunk) {
        return chunks.getOrDefault(chunk, List.of());
    }
    /** The chunk's lines are saved in the chunk now. */
    public void drop(ChunkId chunk) {
        List<Line> gone = chunks.remove(chunk);
        if (gone == null) return;
        size -= gone.size();
        changed = true;
    }
    public int size() {
        return size;
    }
    public Path path() {
        return path;
    }

    /** Writes the remaining lines in the background when something was dropped since the last save. */
    public synchronized void saveLater() {
        if (pending != null && !pending.isDone()) return;
        if (writeFailed) {
            writeFailed = false;
            changed = true;
        }
        if (!changed) return;
        List<Line> snapshot = snapshot();
        changed = false;
        pending = writer.submit(() -> {
            try {
                write(snapshot);
            } catch (IOException failure) {
                writeFailed = true;
            }
        });
    }
    private List<Line> snapshot() {
        List<Line> out = new ArrayList<>(size);
        for (List<Line> lines : chunks.values()) out.addAll(lines);
        return out;
    }
    /** New file next to the old one, synced, then moved over it in one step: a crash leaves either file whole. */
    private void write(List<Line> lines) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            StringBuilder chunk = new StringBuilder(64 * 1024);
            for (Line line : lines) {
                String json = gson.toJson(line);
                chunk.append(json).append('\t').append(checksum(json)).append('\n');
                if (chunk.length() > 60 * 1024) {
                    out.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
                    chunk.setLength(0);
                }
            }
            out.write(chunk.toString().getBytes(StandardCharsets.UTF_8));
        }
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String checksum(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    /** Waits for a background save, then writes what is still unsaved. */
    @Override public synchronized void close() throws IOException {
        writer.shutdown();
        try {
            if (pending != null) pending.get();
        } catch (Exception ignored) {
            changed = true;
        }
        if (changed || writeFailed) {
            write(snapshot());
            writeFailed = false;
            changed = false;
        }
    }
}
