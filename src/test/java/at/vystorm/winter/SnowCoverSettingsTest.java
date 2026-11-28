package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.world.SnowCover;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** snow.cover and snow.biome-depth (1.27.0): defaults, checks and the upgrade of a 1.26.0 config. */
class SnowCoverSettingsTest {
    @TempDir Path folder;

    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                SnowCoverSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test void givenTheShippedConfig_whenRead_thenTheCoverIsSmoothWithTheBuiltInTable() {
        WinterSettings.Terrain t = WinterSettings.Terrain.read(defaults());
        assertEquals(SnowCover.Mode.SMOOTH, t.cover());
        for (var e : SnowCover.BiomeTable.DEFAULTS.entrySet())
            assertEquals(e.getValue(), t.biomes().share(e.getKey(), 0.5, 0.5), 1e-9, "config.yml and the built-in table agree on " + e.getKey());
    }

    @Test void givenA1260ConfigWithoutTheKeys_whenRead_thenSmoothAndTheBuiltInTableApply() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("snow.max-layers", 12);
        WinterSettings.Terrain t = WinterSettings.Terrain.read(c);
        assertEquals(SnowCover.Mode.SMOOTH, t.cover(), "existing servers get the new cover; snow.cover: classic keeps the old one");
        assertEquals(0.7, t.biomes().share("plains", 0.8, 0.4));
    }

    @Test void givenClassic_whenRead_thenTheOldLookIsKept() {
        YamlConfiguration c = defaults();
        c.set("snow.cover", "classic");
        assertEquals(SnowCover.Mode.CLASSIC, WinterSettings.Terrain.read(c).cover());
    }

    @Test void givenBadValues_whenRead_thenTheReloadIsRefused() {
        YamlConfiguration c = defaults();
        c.set("snow.cover", "fluffy");
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.Terrain.read(c));
        YamlConfiguration d = defaults();
        d.set("snow.biome-depth.plains", 2);
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.Terrain.read(d));
        YamlConfiguration e = defaults();
        e.set("snow.biome-depth.plains", "deep");
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.Terrain.read(e));
    }

    @Test void givenTheAdminsOwnEntries_whenRead_thenTheyWinAndPatternsWork() throws Exception {
        File file = folder.resolve("config.yml").toFile();
        Files.writeString(file.toPath(), "snow:\n  biome-depth:\n    plains: 0.3\n    '*_taiga': 0.5\n    my_frost_land: 1\n");
        WinterSettings.Terrain t = WinterSettings.Terrain.read(YamlConfiguration.loadConfiguration(file));
        assertEquals(0.3, t.biomes().share("plains", 0.8, 0.4));
        assertEquals(0.5, t.biomes().share("snowy_taiga", 0, 0.4));
        assertEquals(1.0, t.biomes().share("my_frost_land", 0.9, 0.4));
        assertEquals(0.4, t.biomes().share("beach", 0.8, 0.4), "not listed: the built-in table");
    }

    @Test void givenA1260Config_whenTheNewVersionStarts_thenItGainsBothKeysWithCommentsAndKeepsTheRest() throws Exception {
        YamlConfiguration old = defaults();
        old.set("snow.cover", null);
        old.set("snow.biome-depth", null);
        old.set("snow.max-layers", 20);
        File file = folder.resolve("config.yml").toFile();
        old.save(file);
        assertEquals(2, WinterSwitches.addMissing(file, defaults()));
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(file);
        assertEquals("smooth", merged.getString("snow.cover"));
        assertEquals(0.7, merged.getDouble("snow.biome-depth.plains"));
        assertEquals(1.0, merged.getDouble("snow.biome-depth.frozen_peaks"));
        assertEquals(20, merged.getInt("snow.max-layers"), "the owner's value stays");
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("How the open snow lies"), "comment of snow.cover");
        assertTrue(text.contains("Snow depth per biome"), "comment of snow.biome-depth");
        assertEquals(SnowCover.Mode.SMOOTH, WinterSettings.Terrain.read(merged).cover());
        assertEquals(0, WinterSwitches.addMissing(file, defaults()), "a second start adds nothing");
    }
}
