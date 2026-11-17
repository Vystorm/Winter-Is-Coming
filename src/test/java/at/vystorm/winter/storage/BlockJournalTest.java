package at.vystorm.winter.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockJournalTest {
    @TempDir Path tempDir;

    @Test void worldSaveSeveralLayersBehindJournalStillRestoresAfterCrash() throws Exception {
        Path path=tempDir.resolve("lagging.log");BlockJournal.Key key=key();
        try(BlockJournal journal=new BlockJournal(path)){
            journal.intent(key,"minecraft:air","minecraft:snow[layers=1]");
            journal.intent(key,"minecraft:snow[layers=1]","minecraft:snow[layers=2]");
            journal.intent(key,"minecraft:snow[layers=2]","minecraft:snow[layers=3]");
        }
        try(BlockJournal journal=new BlockJournal(path)){
            assertTrue(journal.get(key).winterBlock("minecraft:snow[layers=1]"));
            assertNotNull(journal.intent(key,"minecraft:snow[layers=1]","minecraft:air"));
            assertEquals("minecraft:air",journal.get(key).original());
        }
    }

    @Test
    void restartKeepsOriginalAndMostRecentExpectedAndPreviousValues() throws Exception {
        Path path = tempDir.resolve("blocks.log");
        BlockJournal.Key key = key();
        try (BlockJournal journal = new BlockJournal(path)) {
            assertNotNull(journal.intent(key, "minecraft:grass_block", "minecraft:snow"));
            assertNotNull(journal.intent(key, "minecraft:snow", "minecraft:packed_ice"));
        }

        try (BlockJournal journal = new BlockJournal(path)) {
            BlockJournal.Entry entry = journal.get(key);
            assertNotNull(entry);
            assertEquals("minecraft:grass_block", entry.original());
            assertEquals("minecraft:snow", entry.previous());
            assertEquals("minecraft:packed_ice", entry.expected());
            assertFalse(entry.released());
            assertEquals(1, journal.size());
        }
    }

    @Test
    void releasedKeyAndNaturalBlockMismatchRefuseNewIntents() throws Exception {
        Path path = tempDir.resolve("blocks.log");
        BlockJournal.Key releasedKey = key();
        BlockJournal.Key naturalKey = new BlockJournal.Key(releasedKey.world(), 4, 70, 9);
        try (BlockJournal journal = new BlockJournal(path)) {
            journal.intent(releasedKey, "minecraft:stone", "minecraft:snow");
            journal.release(releasedKey);
            journal.intent(naturalKey, "minecraft:stone", "minecraft:snow");
            assertNull(journal.intent(naturalKey, "minecraft:dirt", "minecraft:snow"));
        }
        try (BlockJournal journal = new BlockJournal(path)) {
            assertTrue(journal.get(releasedKey).released());
            assertNull(journal.intent(releasedKey, "minecraft:snow", "minecraft:ice"));
            assertNull(journal.intent(naturalKey, "minecraft:dirt", "minecraft:ice"));
            assertEquals(2, journal.size());
        }
    }

    @Test
    void truncatedTrailingWriteIsBackedUpAndRemoved() throws Exception {
        Path path = tempDir.resolve("blocks.log");
        BlockJournal.Key key = key();
        try (BlockJournal journal = new BlockJournal(path)) {
            journal.intent(key, "minecraft:grass_block", "minecraft:snow");
        }
        byte[] good = Files.readAllBytes(path);
        Files.writeString(path, "unfinished trailing record", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        try (BlockJournal journal = new BlockJournal(path)) {
            assertNotNull(journal.get(key));
            assertEquals(1, journal.size());
        }

        assertTrue(java.util.Arrays.equals(good, Files.readAllBytes(path)));
        try (var backups = Files.list(tempDir)) {
            Path backup = backups.filter(file -> file.getFileName().toString().startsWith("blocks.log.torn-"))
                    .findFirst().orElseThrow();
            assertTrue(Files.readString(backup).contains("unfinished trailing record"));
        }
    }

    @Test
    void checksumCorruptionFailsClosedWithoutChangingJournal() throws Exception {
        Path path = tempDir.resolve("blocks.log");
        try (BlockJournal journal = new BlockJournal(path)) {
            journal.intent(key(), "minecraft:grass_block", "minecraft:snow");
        }
        String valid = Files.readString(path);
        int jsonCharacter = valid.indexOf("grass_block");
        String corrupt = valid.substring(0, jsonCharacter) + "d" + valid.substring(jsonCharacter + 1);
        Files.writeString(path, corrupt, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new BlockJournal(path));
        assertEquals(corrupt, Files.readString(path));
    }

    @Test
    void futureJournalSchemaFailsClosedWithoutChangingJournal() throws Exception {
        Path path = tempDir.resolve("blocks.log");
        BlockJournal.Entry future = new BlockJournal.Entry(2, key(), "minecraft:stone",
                "minecraft:stone", "minecraft:snow", false);
        String json = new Gson().toJson(future);
        String line = json + '\t' + checksum(json) + '\n';
        Files.writeString(path, line, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new BlockJournal(path));
        assertEquals(line, Files.readString(path));
    }

    private static BlockJournal.Key key() {
        return new BlockJournal.Key(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1, 64, 2);
    }

    private static String checksum(String value) {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }
}
