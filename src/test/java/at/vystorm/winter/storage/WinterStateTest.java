package at.vystorm.winter.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WinterStateTest {
    @TempDir Path tempDir;

    @Test
    void newYearCelebrationCannotRepeatAfterRestart() {
        Path path=tempDir.resolve("winter-state.json");
        assertTrue(new WinterState(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).celebrate(2027));
        assertFalse(new WinterState(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).celebrate(2027));
        assertTrue(new WinterState(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).celebrate(2028));
    }

    @Test
    void manualModePersistsAcrossReopen() {
        Path path = tempDir.resolve("winter-state.json");
        WinterState state = new WinterState(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        state.mode("2026-12-24", true);

        WinterState reopened = new WinterState(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals("2026-12-24", reopened.data().manualDate);
        assertTrue(reopened.data().thaw);
    }

    @Test
    void futureSchemaFailsWithoutOverwritingExistingState() throws Exception {
        Path path = tempDir.resolve("winter-state.json");
        String future = "{\"schema\":9,\"data\":{\"manualDate\":\"2099-01-01\",\"thaw\":false,\"claims\":{},\"celebratedYears\":[]}}";
        Files.writeString(path, future);

        assertThrows(IllegalStateException.class, () -> new WinterState(path, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        assertEquals(future, Files.readString(path));
    }
}
