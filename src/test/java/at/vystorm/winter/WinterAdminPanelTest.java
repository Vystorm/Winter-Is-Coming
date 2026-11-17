package at.vystorm.winter;

import Vystorm.vystorm_core.panel.PanelTesting;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WinterAdminPanelTest {
    private JsonObject render(boolean seasons, String code) throws Exception {
        Player player = mock(Player.class);
        var overview = new WinterAdminPanel.Overview(LocalDate.of(2026, 12, 9), "FROST", "MANUAL", 40, 16, seasons, 60);
        return PanelTesting.render("winteriscoming:winter-admin", WinterAdminPanel.panel(
                PanelTestSupport.context(player, code), overview, mock(WinterAdminPanel.Actions.class)), player);
    }

    @Test void givenAdminOverview_whenRendered_thenStatusAndControlsAreShown() throws Exception {
        JsonObject doc = render(true, "en");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("admin-tabs", "status", "strength", "auto", "winter", "thaw", "next", "set-date", "date", "speed", "snowfall", "settings", "lamp")));
        assertTrue(doc.toString().contains("Winter admin") && doc.toString().contains("2026-12-09"));
        assertTrue(doc.toString().contains("First frost") && doc.toString().contains("Manual date"));
        assertTrue(doc.toString().contains("Snow strength: 40%"));
        assertEquals(4, PanelTesting.find(doc, "speed").get("min").getAsInt());
        assertEquals(512, PanelTesting.find(doc, "speed").get("max").getAsInt());
        assertEquals(16, PanelTesting.find(doc, "speed").get("value").getAsInt());
        assertEquals(0, PanelTesting.find(doc, "snowfall").get("min").getAsInt());
        assertEquals(400, PanelTesting.find(doc, "snowfall").get("max").getAsInt());
        assertEquals(60, PanelTesting.find(doc, "snowfall").get("value").getAsInt());
    }

    @Test void givenThawAction_whenRendered_thenDestructiveChangeNeedsConfirmation() throws Exception {
        JsonObject thaw = PanelTesting.find(render(true, "en"), "thaw");
        assertEquals("danger", thaw.get("style").getAsString());
        assertTrue(thaw.getAsJsonObject("confirm").toString().contains("All winter snow and ice melts"));
    }

    @Test void givenSeasonsDisabled_whenRendered_thenNextSeasonIsAbsent() throws Exception {
        assertNull(PanelTesting.find(render(false, "en"), "next"));
    }

    @Test void givenEachLanguage_whenRendered_thenPhaseAndModeAreTranslated() throws Exception {
        for (String language : PanelTestSupport.LANGUAGES) {
            String doc = render(true, language).toString();
            assertFalse(doc.contains("panel-phase") || doc.contains("panel-mode") || doc.contains("panel-season"), language + doc);
            if (!language.equals("en")) assertFalse(doc.contains("First frost") || doc.contains("Manual date"), language + doc);
        }
    }
}
