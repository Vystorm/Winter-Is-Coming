package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.WinterSwitches.Feature;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Wind-blown snow (1.25.0): its keys, their checks, the switch and the upgrade of a 1.24.3 config. */
class WindBlownSettingsTest {
    @TempDir Path folder;

    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                WindBlownSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }
    private static YamlConfiguration with(String path, Object value) {
        YamlConfiguration c = defaults();
        c.set(path, value);
        return c;
    }

    @Test void givenTheShippedConfig_whenRead_thenWindBlownSnowIsOnWithTheDesignsDefaults() {
        YamlConfiguration c = defaults();
        assertEquals(WinterSettings.WindBlown.DEFAULTS, WinterSettings.WindBlown.read(c));
        assertTrue(WinterSwitches.read(c).on(Feature.WIND_BLOWN));
        assertEquals("snow.wind-blown.enabled", Feature.WIND_BLOWN.path());
        assertEquals(new at.vystorm.winter.world.WindRay.Wind(20, 270, 12), WinterSettings.WindBlown.DEFAULTS.wind());
    }

    @Test void givenAConfigWithoutTheKeys_whenRead_thenTheDefaultsApply() {
        YamlConfiguration c = defaults();
        c.set("snow.wind-blown", null);
        assertEquals(WinterSettings.WindBlown.DEFAULTS, WinterSettings.WindBlown.read(c));
        assertTrue(WinterSwitches.read(c).on(Feature.WIND_BLOWN), "old configs: on, as the owner decided");
    }

    @Test void givenTheSwitchOff_whenRead_thenItIsOff() {
        assertFalse(WinterSwitches.read(with("snow.wind-blown.enabled", false)).on(Feature.WIND_BLOWN));
        assertFalse(WinterSwitches.without(Feature.WIND_BLOWN).on(Feature.WIND_BLOWN));
    }

    @Test void givenWindDirections_whenRead_thenCompassNamesAndDegreesWork() {
        assertEquals(270, WinterSettings.WindBlown.windFrom("west"));
        assertEquals(0, WinterSettings.WindBlown.windFrom("North"));
        assertEquals(90, WinterSettings.WindBlown.windFrom("east"));
        assertEquals(180, WinterSettings.WindBlown.windFrom(" south "));
        assertEquals(225, WinterSettings.WindBlown.windFrom("225"));
        assertEquals(0, WinterSettings.WindBlown.windFrom("360"));
        assertEquals(225, WinterSettings.WindBlown.read(with("snow.wind-blown.wind-from", 225)).from());
        for (String bad : new String[]{"westward", "-5", "400", "NaN"})
            assertThrows(IllegalArgumentException.class, () -> WinterSettings.WindBlown.windFrom(bad), bad);
    }

    @Test void givenValuesOutOfRange_whenRead_thenTheReloadIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.WindBlown.read(with("snow.wind-blown.angle", 41)));
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.WindBlown.read(with("snow.wind-blown.max-height", 1)));
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.WindBlown.read(with("snow.wind-blown.max-height", 25)));
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.WindBlown.read(with("snow.wind-blown.max-layers", 0)));
        assertThrows(IllegalArgumentException.class, () -> WinterSettings.WindBlown.read(with("snow.wind-blown.max-layers", 9)));
        assertEquals(0, WinterSettings.WindBlown.read(with("snow.wind-blown.angle", 0)).angle());
        assertEquals(40, WinterSettings.WindBlown.read(with("snow.wind-blown.angle", 40)).angle());
        assertFalse(WinterSettings.WindBlown.read(with("snow.wind-blown.leaves-block", false)).leavesBlock());
    }

    @Test void givenA1243Config_whenTheNewVersionStarts_thenItGainsEveryWindKeyWithCommentsAndKeepsTheRest() throws Exception {
        YamlConfiguration old = defaults();
        old.set("snow.wind-blown", null);
        old.set("snow.cover", null);         // 1.27.0 keys: a 1.24.3 file has none of them either
        old.set("snow.biome-depth", null);
        old.set("snow.max-layers", 16);
        // 1.28.0 keys of snow-free building: a 1.24.3 file has only build-mode.enabled and build-mode.radius
        for (String key : java.util.List.of("max-radius", "after-minutes", "max-areas", "cooldown-seconds", "clear-per-tick", "halo", "under-blocks"))
            old.set("build-mode." + key, null);
        File file = folder.resolve("config.yml").toFile();
        old.save(file);
        assertEquals(1 + WinterSwitches.ADDED_KEYS.size() + 2, WinterSwitches.addMissing(file, defaults()), "with the two building switches");
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(file);
        assertTrue(merged.getBoolean("snow.wind-blown.enabled"));
        assertEquals(20, merged.getInt("snow.wind-blown.angle"));
        assertEquals("west", merged.getString("snow.wind-blown.wind-from"));
        assertEquals(12, merged.getInt("snow.wind-blown.max-height"));
        assertEquals(4, merged.getInt("snow.wind-blown.max-layers"));
        assertTrue(merged.getBoolean("snow.wind-blown.leaves-block"));
        assertEquals(16, merged.getInt("snow.max-layers"), "the owner's value stays");
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("Never inside closed rooms"), "comment of the section");
        assertTrue(text.contains("Where the wind comes from"), "comment of wind-from");
        assertEquals(WinterSettings.WindBlown.DEFAULTS, WinterSettings.WindBlown.read(merged));
        assertEquals(0, WinterSwitches.addMissing(file, defaults()), "second start adds nothing");
    }

    @Test void givenAnOwnerWhoSetTheWind_whenMerged_thenHisValuesStay() throws Exception {
        YamlConfiguration c = defaults();
        c.set("snow.wind-blown.wind-from", "north");
        c.set("snow.wind-blown.max-layers", null);
        File file = folder.resolve("config.yml").toFile();
        c.save(file);
        assertEquals(1, WinterSwitches.addMissing(file, defaults()));
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(file);
        assertEquals("north", merged.getString("snow.wind-blown.wind-from"));
        assertEquals(4, merged.getInt("snow.wind-blown.max-layers"));
    }

    @Test void givenStatusNumbers_whenShown_thenWholeDegreesHaveNoFraction() {
        assertEquals("20", WinterPlugin.plainNumber(20));
        assertEquals("22.5", WinterPlugin.plainNumber(22.5));
    }
}
