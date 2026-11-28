package at.vystorm.winter;

import Vystorm.vystorm_core.panel.PanelTesting;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WinterPlayerPanelTest {
    private static final List<WinterPlayerPanel.Mission> MISSIONS = List.of(
            new WinterPlayerPanel.Mission("snowball_festival", "Snowball hits", 40, 100),
            new WinterPlayerPanel.Mission("warm_supper", "Hot meals eaten", 50, 50));

    private static WinterPlayerPanel.Overview overview(Boolean snowfall, List<WinterPlayerPanel.Mission> missions, boolean duel, boolean picker) {
        return new WinterPlayerPanel.Overview(LocalDate.of(2026, 12, 9), "WINTER", 80, true, 35, 12, 2,
                snowfall, missions, true, 3, true, duel, picker);
    }

    private static JsonObject render(WinterPlayerPanel.Overview o, String code) throws Exception {
        Player player = mock(Player.class);
        return PanelTesting.render("winteriscoming:winter", WinterPlayerPanel.panel(
                PanelTestSupport.context(player, code), o, mock(WinterPlayerPanel.Actions.class)), player);
    }

    @Test void givenEverythingOn_whenRendered_thenStatusSnowfallMissionsClaimAndDuelAreShown() throws Exception {
        JsonObject doc = render(overview(true, MISSIONS, true, true), "en");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("season", "strength", "warmth", "snowfall", "missions",
                "mission-snowball_festival", "mission-warm_supper", "missions-paused", "claim", "duel")), doc.toString());
        assertNull(PanelTesting.find(doc, "duel-name"));
        assertTrue(PanelTesting.find(doc, "snowfall").get("value").getAsBoolean());
        String text = doc.toString();
        assertTrue(text.contains("Snow strength: 80%") && text.contains("Cold: 35%") && text.contains("Equipment protection: 50%"), text);
        assertTrue(text.contains("snowball_festival: Snowball hits") && text.contains("40/100"), text);
        assertTrue(text.contains("Claim (3)") && text.contains("Snowball duel"), text);
    }

    @Test void givenCoreWithoutPlayerPicker_whenRendered_thenDuelIsATypedName() throws Exception {
        JsonObject doc = render(overview(false, MISSIONS, true, false), "en");
        assertNull(PanelTesting.find(doc, "duel"));
        assertNotNull(PanelTesting.find(doc, "duel-name"));
        assertFalse(PanelTesting.find(doc, "snowfall").get("value").getAsBoolean());
    }

    @Test void givenFeaturesOffOrNotAllowed_whenRendered_thenTheirPartsAreAbsent() throws Exception {
        JsonObject doc = render(overview(null, null, false, true), "en");
        for (String id : List.of("snowfall", "missions", "duel", "duel-name")) assertNull(PanelTesting.find(doc, id), id);
        assertNotNull(PanelTesting.find(doc, "claim"));
    }

    /** 1.28.0: the snow-free building switch, its three states and its tile label in every language. */
    @Test void givenSnowFreeBuilding_whenRendered_thenTheSwitchShowsItsStateInEveryLanguage() throws Exception {
        WinterPlayerPanel.Overview none = overview(true, MISSIONS, true, true);
        assertNull(PanelTesting.find(render(none, "en"), "build"), "not offered: no switch");
        for (var state : List.of(new WinterPlayerPanel.Build(true, 32, 0), new WinterPlayerPanel.Build(false, 32, 12), new WinterPlayerPanel.Build(false, 24, 0))) {
            var o = new WinterPlayerPanel.Overview(none.date(), none.phase(), none.strength(), none.coldOn(), none.cold(), none.warmth(), none.protection(),
                    none.snowfall(), none.missions(), none.missionsPaused(), none.pending(), none.claim(), none.duel(), none.picker(), state);
            JsonObject en = render(o, "en");
            assertEquals(state.on(), PanelTesting.find(en, "build").get("value").getAsBoolean());
            String text = en.toString();
            if (state.on()) assertTrue(text.contains("no plugin snow within 32 blocks"), text);
            else if (state.minutesLeft() > 0) assertTrue(text.contains("snow-free for 12 more min"), text);
            else assertTrue(text.contains("Clears the snow within 24 blocks"), text);
            for (String language : PanelTestSupport.LANGUAGES) {
                JsonObject doc = render(o, language);
                assertFalse(doc.toString().contains("player-panel-"), language + doc);
                String label = PanelTesting.find(doc, "build").get("label").getAsString();
                assertTrue(label.length() <= 14, language + " build " + label);
            }
        }
    }

    @Test void givenEachLanguage_whenRendered_thenNoKeyIsShownAndTileLabelsFit() throws Exception {
        for (String language : PanelTestSupport.LANGUAGES) {
            JsonObject doc = render(overview(true, MISSIONS, true, true), language);
            assertFalse(doc.toString().contains("player-panel-"), language + doc);
            for (String id : List.of("claim", "duel")) {
                String label = PanelTesting.find(doc, id).get("label").getAsString();
                assertTrue(label.length() <= 14, language + " " + id + " " + label);
            }
        }
    }
}
