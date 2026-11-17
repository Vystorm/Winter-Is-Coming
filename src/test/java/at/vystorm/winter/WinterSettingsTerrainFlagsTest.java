package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class WinterSettingsTerrainFlagsTest {
    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                WinterSettingsTerrainFlagsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    @Test
    void shippedDefaultsKeepAllThreeTerrainFlagsOn() {
        WinterSettings.Terrain settings = WinterSettings.Terrain.read(defaults());
        assertTrue(settings.ramps());
        assertTrue(settings.coverStairsAndSlabs());
        assertTrue(settings.removeTallPlants());
    }

    @Test
    void configuredValuesArriveInTheSettings() {
        YamlConfiguration config = defaults();
        config.set("snow.ramps", false);
        config.set("snow.cover-stairs-and-slabs", false);
        config.set("snow.remove-tall-plants", false);
        WinterSettings.Terrain settings = WinterSettings.Terrain.read(config);
        assertFalse(settings.ramps());
        assertFalse(settings.coverStairsAndSlabs());
        assertFalse(settings.removeTallPlants());
    }

    @Test
    void missingKeysFallBackToTrueAsBefore() {
        YamlConfiguration config = defaults();
        config.set("snow.ramps", null);
        config.set("snow.cover-stairs-and-slabs", null);
        config.set("snow.remove-tall-plants", null);
        WinterSettings.Terrain settings = WinterSettings.Terrain.read(config);
        assertTrue(settings.ramps());
        assertTrue(settings.coverStairsAndSlabs());
        assertTrue(settings.removeTallPlants());
    }
}
