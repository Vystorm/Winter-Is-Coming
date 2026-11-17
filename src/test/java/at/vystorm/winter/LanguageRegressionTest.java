package at.vystorm.winter;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LanguageRegressionTest {
    @ParameterizedTest @ValueSource(strings={"en","de","zh_cn","fr","pt_br","it","ru"})
    void recoveryPathIsLiteralAndButtonsAreLocalized(String language) throws Exception {
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/lang/"+language+".yml"),StandardCharsets.UTF_8)) {
            YamlConfiguration texts=new YamlConfiguration();texts.load(reader);
            assertTrue(texts.getString("claims-console").contains("plugins/WinterIsComing/rewards/winter/"));
            if(!language.equals("en")) {
                assertFalse(texts.getString("calendar-open-button").contains("[Open]"));
                assertFalse(texts.getString("snowball-accept-button").contains("[Accept]"));
                assertFalse(texts.getString("calendar-opened").contains("Merry Advent"));
            }
            assertFalse(texts.getString("calendar-opened").contains("{item}"),"the pouch may deliver an older pending gift");
            assertFalse(texts.getString("warmth-overview").contains("Telaio"));
        }
    }
}
