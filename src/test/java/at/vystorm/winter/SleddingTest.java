package at.vystorm.winter;

import at.vystorm.winter.storage.SledRuns;
import at.vystorm.winter.world.SleddingMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SleddingTest {
    @TempDir Path folder;

    @Test
    void givenSnow_whenSpeeding_thenFasterDownhillAndCapped() {
        assertEquals(0.01, SleddingMath.speed(0.01, 1.6, true, 0.7), 1e-9, "standing still stays still");
        double flat = SleddingMath.speed(0.3, 1.6, false, 0.7), down = SleddingMath.speed(0.3, 1.6, true, 0.7);
        assertTrue(flat > 0.3 && down > flat);
        assertEquals(0.7, SleddingMath.speed(0.69, 4.0, true, 0.7), 1e-9);
        assertEquals(0.9, SleddingMath.speed(0.9, 1.6, true, 0.7), 1e-9, "never slows a faster boat down");
        assertEquals(0.3, SleddingMath.speed(0.3, 1.0, false, 0.7), 1e-9, "multiplier 1 on flat snow changes nothing");
    }

    @Test
    void givenTimes_whenRecorded_thenOnlyBestPerPlayerSorted() {
        SledRuns runs = new SledRuns(folder.resolve("sledruns.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(runs.start("alpine", "world", 0, 80, 0));
        assertTrue(runs.finish("alpine", "world", 100, 40, 0));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(new SledRuns.Result(1, true), runs.record("alpine", a, "Alex", 42_000));
        assertEquals(new SledRuns.Result(1, true), runs.record("alpine", b, "Bo", 40_000));
        assertEquals(new SledRuns.Result(2, false), runs.record("alpine", a, "Alex", 45_000));
        assertEquals(new SledRuns.Result(1, true), runs.record("alpine", a, "Alex", 39_500));
        var best = new SledRuns(folder.resolve("sledruns.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).run("alpine").orElseThrow().best;
        assertEquals(2, best.size());
        assertEquals("Alex", best.get(0).name);
        assertEquals(39_500, best.get(0).millis);
    }

    @Test
    void givenFinishInOtherWorld_whenSet_thenRefused() {
        SledRuns runs = new SledRuns(folder.resolve("sledruns.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertFalse(runs.finish("ghost", "world", 0, 0, 0));
        runs.start("alpine", "world", 0, 80, 0);
        assertFalse(runs.finish("alpine", "world_nether", 0, 0, 0));
        assertTrue(runs.delete("alpine"));
        assertFalse(runs.delete("alpine"));
    }

    @Test
    void givenNamesAndTimes_whenFormatted_thenReadable() {
        assertEquals("alpine-2", SledRuns.name(" Alpine-2 "));
        assertNull(SledRuns.name("bad name"));
        assertNull(SledRuns.name("x".repeat(25)));
        assertEquals("42.10 s", SledRuns.format(42_100));
        assertEquals("1:02.35", SledRuns.format(62_350));
    }
}
