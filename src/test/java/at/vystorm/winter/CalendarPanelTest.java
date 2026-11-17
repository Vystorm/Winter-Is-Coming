package at.vystorm.winter;

import Vystorm.vystorm_core.panel.PanelTesting;
import at.vystorm.winter.season.AdventCalendar.Door;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalendarPanelTest {
    private final CalendarPanel.Actions actions = mock(CalendarPanel.Actions.class);

    private CalendarPanel.Overview overview(boolean preview, String selected) {
        List<CalendarPanel.Row> rows = IntStream.rangeClosed(1, 24).mapToObj(day ->
                new CalendarPanel.Row(day, switch (day) {
                    case 1 -> Door.OPENED;
                    case 2 -> Door.MISSED;
                    case 3, 9 -> Door.READY;
                    default -> Door.LOCKED;
                }, Material.COOKIE, 6, "item.minecraft.cookie")).toList();
        return new CalendarPanel.Overview(LocalDate.of(2026, 12, 9), rows, 1, 2, 3, preview, selected);
    }

    private JsonObject render(boolean preview, String selected, String code) throws Exception {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return PanelTesting.render("winteriscoming:advent-calendar",
                CalendarPanel.panel(PanelTestSupport.context(player, code), overview(preview, selected), actions, (p, key) -> {}), player);
    }

    @Test void givenCalendar_whenRendered_thenDoorsProgressAndGiftsAreShown() throws Exception {
        JsonObject doc = render(false, null, "en");
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("doors", "days-0", "days-1", "days-2", "calendar-progress", "gifts", "open-3")));
        assertTrue(doc.toString().contains("Advent calendar 2026"));
        assertTrue(doc.toString().contains("Opened 1/24 doors, 2 waiting"));
        assertTrue(doc.toString().contains("Pending gifts: 3"));
        assertFalse(PanelTesting.find(doc, "open-3").get("disabled").getAsBoolean());
        assertNull(PanelTesting.find(doc, "preview-note"));
    }

    @Test void givenChosenDoor_whenRendered_thenSelectionAndTabFollowTheChoice() throws Exception {
        JsonObject doc = render(false, "9", "en");
        assertEquals("9", PanelTesting.find(doc, "days-1").get("selected").getAsString());
        assertEquals("days-9", PanelTesting.find(doc, "doors").get("selected").getAsString());
        assertFalse(PanelTesting.find(doc, "open-9").get("disabled").getAsBoolean());
        assertNull(PanelTesting.find(doc, "open-10"));
    }

    @Test void givenUnavailableDoors_whenRendered_thenOpeningIsDisabled() throws Exception {
        for (String day : List.of("1", "2", "4")) {
            JsonObject doc = render(false, day, "en");
            assertTrue(PanelTesting.find(doc, "open-" + day).get("disabled").getAsBoolean());
        }
    }

    @Test void givenAdministrativePreview_whenRendered_thenNoRewardWarningIsVisible() throws Exception {
        JsonObject doc = render(true, "1", "en");
        assertNotNull(PanelTesting.find(doc, "preview-note"));
        assertTrue(doc.toString().contains("Doors give nothing and do not count"));
        assertTrue(doc.toString().contains("minecraft:cookie"));
    }

    @Test void givenEachLanguage_whenRendered_thenAllPanelTextsResolve() throws Exception {
        for (String language : PanelTestSupport.LANGUAGES) {
            String doc = render(true, "1", language).toString();
            assertFalse(doc.contains("panel-days") || doc.contains("panel-door-") || doc.contains("calendar-title"), language + doc);
            assertFalse(doc.contains("{first}") || doc.contains("{year}"), language + doc);
        }
    }
}
