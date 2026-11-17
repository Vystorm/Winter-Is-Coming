package at.vystorm.winter.season;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import at.vystorm.winter.season.SeasonClock.Mode;
import at.vystorm.winter.season.SeasonClock.Season;
import at.vystorm.winter.world.SeasonPlants;
import java.time.LocalDate;
import org.bukkit.Material;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.Test;

class SeasonClockTest {
    static final LocalDate A = LocalDate.of(2026, 1, 5);
    SeasonClock weekly = new SeasonClock(true, Mode.WEEKLY, A), monthly = new SeasonClock(true, Mode.MONTHLY, A), real = new SeasonClock(true, Mode.REAL, A);

    @Test void weeklyTurnsEverySevenDaysAlsoBeforeTheAnchor() {
        assertEquals(Season.SPRING, weekly.at(A, false).season());
        assertEquals(Season.SPRING, weekly.at(A.plusDays(6), false).season());
        assertEquals(Season.SUMMER, weekly.at(A.plusDays(7), false).season());
        assertEquals(Season.WINTER, weekly.at(A.plusDays(21), false).season());
        assertEquals(Season.SPRING, weekly.at(A.plusDays(28), false).season());
        assertEquals(Season.WINTER, weekly.at(A.minusDays(1), false).season());
        assertEquals(6 / 7d, weekly.at(A.minusDays(1), false).progress(), 1e-9);
    }
    @Test void monthlyTurnsWithTheMonth() {
        assertEquals(Season.SPRING, monthly.at(LocalDate.of(2026, 1, 31), false).season());
        assertEquals(Season.SUMMER, monthly.at(LocalDate.of(2026, 2, 1), false).season());
        assertEquals(Season.WINTER, monthly.at(LocalDate.of(2026, 4, 15), false).season());
        assertEquals(Season.SPRING, monthly.at(LocalDate.of(2026, 5, 1), false).season());
    }
    @Test void realFollowsTheYearAndTheWinterCalendar() {
        assertEquals(Season.SPRING, real.at(LocalDate.of(2026, 4, 1), false).season());
        assertEquals(Season.SUMMER, real.at(LocalDate.of(2026, 7, 1), false).season());
        assertEquals(Season.AUTUMN, real.at(LocalDate.of(2026, 10, 1), false).season());
        assertEquals(Season.WINTER, real.at(LocalDate.of(2026, 11, 20), true).season());
        assertEquals(Season.WINTER, real.at(LocalDate.of(2026, 1, 20), false).season());
    }
    @Test void nextIsTheFirstDayOfTheFollowingSeason() {
        assertEquals(A.plusDays(7), weekly.next(A.plusDays(3), false, null));
        assertEquals(A.plusDays(7), weekly.next(A, false, null));
        assertEquals(LocalDate.of(2026, 3, 1), monthly.next(LocalDate.of(2026, 2, 28), false, null));
        assertEquals(LocalDate.of(2026, 6, 1), real.next(LocalDate.of(2026, 4, 1), false, null));
        LocalDate start = LocalDate.of(2026, 11, 15);
        assertEquals(start, real.next(LocalDate.of(2026, 10, 1), false, start));
        assertEquals(LocalDate.of(2027, 3, 1), real.next(LocalDate.of(2026, 12, 24), true, start));
    }
    @Test void brokenSettingsFallBack() {
        assertEquals(Mode.REAL, Mode.parse("nonsense"));
        assertEquals(Mode.WEEKLY, Mode.parse(" weekly "));
        assertEquals(SeasonClock.DEFAULT_ANCHOR, SeasonClock.anchor("x"));
    }
    @Test void plantsAreStableAndSpringFillsIn() {
        int early = 0, late = 0;
        for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) {
            assertEquals(SeasonPlants.plan(Season.SUMMER, 0, 9, x, z), SeasonPlants.plan(Season.SUMMER, 1, 9, x, z));
            if (SeasonPlants.plan(Season.SPRING, 0.1, 9, x, z) != SeasonPlants.Plant.NONE) early++;
            if (SeasonPlants.plan(Season.SPRING, 1, 9, x, z) != SeasonPlants.Plant.NONE) late++;
            assertEquals(SeasonPlants.Plant.NONE, SeasonPlants.plan(Season.WINTER, 1, 9, x, z));
        }
        assertTrue(early > 0 && late > early * 3, early + " " + late);
    }
    @Test void autumnHasBerriesAndNoTallPlants() {
        boolean berry = false;
        for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) {
            var p = SeasonPlants.plan(Season.AUTUMN, 1, 9, x, z);
            assertNotEquals(SeasonPlants.Plant.TALL, p);
            berry |= p == SeasonPlants.Plant.BERRY;
        }
        assertTrue(berry);
    }
    @Test void tallPlantsAreRecognised() {
        assertTrue(SeasonPlants.tallPlant(Material.ROSE_BUSH));
        assertFalse(SeasonPlants.tallPlant(Material.POPPY));
        assertTrue(SeasonPlants.tallPlantData("minecraft:tall_grass[half=upper]"));
        assertFalse(SeasonPlants.tallPlantData("minecraft:air"));
    }
    @Test void overlayKeepsPlayerBuildsLookingRight() {
        Stairs bottom = mock(Stairs.class); when(bottom.getHalf()).thenReturn(Bisected.Half.BOTTOM);
        Stairs top = mock(Stairs.class); when(top.getHalf()).thenReturn(Bisected.Half.TOP);
        Slab low = mock(Slab.class); when(low.getType()).thenReturn(Slab.Type.BOTTOM);
        Slab high = mock(Slab.class); when(high.getType()).thenReturn(Slab.Type.TOP);
        assertEquals(SeasonPlants.Overlay.STAIRS, SeasonPlants.overlay(bottom));
        assertEquals(SeasonPlants.Overlay.NONE, SeasonPlants.overlay(top));
        assertEquals(SeasonPlants.Overlay.SLAB, SeasonPlants.overlay(low));
        assertEquals(SeasonPlants.Overlay.NONE, SeasonPlants.overlay(high));
    }
}
