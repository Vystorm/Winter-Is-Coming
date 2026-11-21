package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@code /vadmin features} lists every boolean {@code enabled} key and every top-level boolean of config.yml, plus the
 * declared ones. Each of them must have a readable name and a description in all seven languages, so the screen never
 * falls back to a key path such as "Snow › Spring thaw".
 */
class FeatureLabelsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    /** Core's housekeeping names that are never listed as features (ConfigEditorService.NOT_FEATURES). */
    private static final Set<String> NOT_FEATURES = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");

    private static YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                FeatureLabelsTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    private static boolean listedByCore(String path) {
        if (path.equals("enabled") || path.endsWith(".enabled")) return true;
        return path.indexOf('.') < 0 && !NOT_FEATURES.contains(path);
    }

    @Test void givenEveryBooleanCoreListsAsAFeature_whenChecked_thenItIsAKnownSwitch() {
        YamlConfiguration config = resource("config.yml");
        Set<String> known = new HashSet<>(WinterSwitches.allPaths());
        for (String path : config.getKeys(true))
            if (config.isBoolean(path) && listedByCore(path)) assertTrue(known.contains(path), "feature without a name: " + path);
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchHasANameAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = resource("lang/" + code + ".yml");
            for (String path : WinterSwitches.allPaths()) {
                String label = lang.getString(WinterSwitches.labelKey(path), "");
                assertFalse(label.isBlank(), code + " " + WinterSwitches.labelKey(path));
                assertFalse(label.contains("."), code + " " + path + " name looks like a key: " + label);
                assertFalse(lang.getString(WinterSwitches.descriptionKey(path), "").isBlank(), code + " " + WinterSwitches.descriptionKey(path));
            }
            String needs = lang.getString(WinterSwitches.NEEDS_MESSAGE, "");
            assertTrue(needs.contains("{name}") && needs.contains("{switch}"), code + " needs message");
        }
        // 1.25.0: wind-blown snow is one of the switches checked above, in every language
        assertTrue(WinterSwitches.allPaths().contains("snow.wind-blown.enabled"));
        assertEquals("Wind-blown snow", resource("lang/en.yml").getString(WinterSwitches.labelKey("snow.wind-blown.enabled")));
        assertEquals("switch-names.snow-spring-thaw", WinterSwitches.labelKey("snow.spring-thaw.enabled"));
        assertEquals("switch-names.snow-cover-leaves", WinterSwitches.labelKey("snow.cover-leaves"));
    }

    @Test void givenEnglish_whenRead_thenTheNamesAreDistinct() {
        YamlConfiguration en = resource("lang/en.yml");
        Set<String> seen = new HashSet<>();
        for (String path : WinterSwitches.allPaths()) assertTrue(seen.add(en.getString(WinterSwitches.labelKey(path))), "twice: " + path);
    }
}
