package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangMigrationTest {
    @Test void failedReplacementPreservesOriginalAndBackup(@TempDir Path dir) throws Exception {
        Path file=dir.resolve("en.yml");String original="status: '<date>'\n";
        Files.writeString(file,original);
        // Fail the final replacement after staging, as on a locked file or failed filesystem move.
        try(var files=org.mockito.Mockito.mockStatic(Files.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(()->Files.move(org.mockito.ArgumentMatchers.any(Path.class),org.mockito.ArgumentMatchers.eq(file.toAbsolutePath()),
                    org.mockito.ArgumentMatchers.any(CopyOption[].class))).thenThrow(new java.io.IOException("replacement failed"));
            assertThrows(java.io.IOException.class,()->LangMigration.migrateFolder(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, dir));
        }
        assertEquals(original,Files.readString(file));
        assertEquals(original,Files.readString(dir.resolve("en.yml.bak-1.2")));
    }
    @Test void rewritesKnownPlaceholdersButKeepsColourTags() {
        assertEquals("<aqua>Winter</aqua> {date} | {phase} {strength}% <red>x</red> <player>",
                LangMigration.migrate("<aqua>Winter</aqua> <date> | <phase> <strength>% <red>x</red> <player>"));
    }

    @Test void migratesOnlyChangedFilesAndKeepsBackups(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("de.yml"),"status: '<date> <blocks>'\n");
        Files.writeString(dir.resolve("en.yml"),"status: '{date}'\n");
        assertEquals(1,LangMigration.migrateFolder(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, dir));
        assertEquals("status: '{date} {blocks}'\n",Files.readString(dir.resolve("de.yml")));
        assertEquals("status: '<date> <blocks>'\n",Files.readString(dir.resolve("de.yml.bak-1.2")));
        assertFalse(Files.exists(dir.resolve("en.yml.bak-1.2")));
        assertEquals(0,LangMigration.migrateFolder(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, dir));
        assertEquals(0,LangMigration.migrateFolder(Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, dir.resolve("missing")));
    }
}
