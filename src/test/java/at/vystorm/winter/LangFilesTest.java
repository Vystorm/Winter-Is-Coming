package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangFilesTest {
    private static final String[] FILES = {"lang/en.yml", "lang/de.yml", "lang/zh_cn.yml"};

    private static YamlConfiguration load(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(LangFilesTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    @Test
    void everyLanguageHasTheSameKeysAndPlaceholders() {
        YamlConfiguration en = load("lang/en.yml");
        for (String file : FILES) {
            YamlConfiguration other = load(file);
            assertEquals(en.getKeys(true), other.getKeys(true), file);
            for (String key : en.getKeys(true)) {
                if (en.isConfigurationSection(key)) continue;
                assertEquals(placeholders(en, key), placeholders(other, key), file + " " + key);
            }
        }
    }

    @Test
    void textsHaveNoRawArgumentTags() {
        for (String file : FILES) {
            YamlConfiguration yaml = load(file);
            for (String key : yaml.getKeys(true)) {
                if (yaml.isConfigurationSection(key)) continue;
                assertFalse(text(yaml, key).contains("<arg"), file + " " + key);
            }
        }
    }

    private static String text(YamlConfiguration yaml, String key) {
        return yaml.isList(key) ? String.join("\n", yaml.getStringList(key)) : yaml.getString(key);
    }

    private static TreeSet<String> placeholders(YamlConfiguration yaml, String key) {
        TreeSet<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\{[^}]+}").matcher(text(yaml, key));
        while (matcher.find()) found.add(matcher.group());
        return found;
    }
}
