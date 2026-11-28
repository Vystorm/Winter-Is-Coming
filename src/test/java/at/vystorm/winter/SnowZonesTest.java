package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.world.SnowZones;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnowZonesTest {
    @Test
    void spotsLimitOnlyTheirOwnWorldAndBuildersKeepSnowAway(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), builder = UUID.randomUUID();
        SnowZones zones = new SnowZones(dir.resolve("spots.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        zones.configure(true);
        assertTrue(zones.allows(a, 500, 500), "no spots: snow everywhere");
        zones.add("town", new SnowZones.Spot(a, 0, 0, 50));
        assertTrue(zones.allows(a, 30, 30));
        assertFalse(zones.allows(a, 60, 0), "outside the spot");
        assertTrue(zones.allows(b, 60, 0), "a world without spots keeps snow");
        var areas = new at.vystorm.winter.world.BuildAreas(() -> 0L);
        zones.areas(areas);
        assertEquals(at.vystorm.winter.world.BuildAreas.Outcome.STARTED, areas.start(builder, a, 10, 10, 24, false, null).outcome());
        assertFalse(zones.allows(a, 20, 20), "near a builder");
        assertTrue(zones.allows(a, 30, -10), "inside the spot, away from the builder");
        assertTrue(new SnowZones(dir.resolve("spots.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).spots().containsKey("town"), "saved");
        assertTrue(zones.limiting());
        zones.configure(false);
        assertFalse(zones.limiting(), "only-inside off: spots do not limit");
        areas.clear();
        assertTrue(zones.allows(a, 60, 0), "only-inside off");
    }

    /** 1.24.1: with spots limiting a world, every visible winter effect stops at the spot border. */
    @Test
    void visibleWinterStopsAtTheSpotBorder(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        SnowZones zones = new SnowZones(dir.resolve("spots.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        zones.configure(true);
        assertFalse(zones.limiting(a), "no spot yet");
        assertTrue(SnowZones.winterAt(true, zones, a, 500, 500), "no spots: winter everywhere");
        assertTrue(zones.projection(a, 0, 0, 64, -64, 320).isEmpty(), "no spots: the whole-world snow view is used, no spot cells");
        zones.add("town", new SnowZones.Spot(a, 0, 0, 40));
        assertTrue(zones.limiting(a));
        assertFalse(zones.limiting(b), "another world is not limited");
        // placed snow and ice, flakes, storms and cold (winterAt), personal snowfall
        assertTrue(zones.allows(a, 10, 10));
        assertFalse(zones.allows(a, 50, 0));
        assertTrue(zones.insideSpots(a, 10, 10));
        assertFalse(zones.insideSpots(a, 50, 0));
        assertTrue(SnowZones.winterAt(true, zones, a, 10, 10), "inside: storms and cold");
        assertFalse(SnowZones.winterAt(true, zones, a, 50, 0), "outside: no storm effects, no cold");
        assertFalse(SnowZones.winterAt(false, zones, a, 10, 10), "winter not active in the world");
        assertTrue(SnowZones.winterAt(true, zones, b, 50, 0), "world without spots");
        assertEquals(org.bukkit.WeatherType.DOWNFALL, at.vystorm.winter.world.WinterWeather.personalWeather(true, true, true), "inside while it snows");
        assertNull(at.vystorm.winter.world.WinterWeather.personalWeather(true, false, true), "outside: the world's own weather");
        assertNull(at.vystorm.winter.world.WinterWeather.personalWeather(true, true, false));
        assertNull(at.vystorm.winter.world.WinterWeather.personalWeather(false, true, true), "not limited: the world weather is the plugin's");
        // snow colours and falling snow (client biome view): only cells inside the spot, within Core's overlay limits
        List<SnowZones.Cell> inside = zones.projection(a, 5, 5, 70, -64, 320);
        assertFalse(inside.isEmpty());
        assertTrue(inside.size() <= SnowZones.MAX_CELLS);
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        for (SnowZones.Cell c : inside) {
            assertEquals(0, c.x() & 3); assertEquals(0, c.y() & 3); assertEquals(0, c.z() & 3);
            assertTrue(Math.hypot(c.x() + 2, c.z() + 2) <= 40, "cell centre inside the spot: " + c);
            assertTrue(c.y() >= 56 && c.y() < 56 + 64);
            chunks.add(((long) (c.x() >> 4) << 32) ^ (c.z() >> 4 & 0xffffffffL));
        }
        assertTrue(chunks.size() <= 16);
        // a viewer far outside gets the edge of the spot nearest to him, never cells outside
        List<SnowZones.Cell> far = zones.projection(a, 300, 0, 70, -64, 320);
        assertFalse(far.isEmpty(), "the spot edge facing the viewer");
        for (SnowZones.Cell c : far) assertTrue(Math.hypot(c.x() + 2, c.z() + 2) <= 40);
        assertTrue(zones.projection(b, 0, 0, 70, -64, 320).isEmpty(), "other world: no spot cells");
        zones.configure(false);
        assertFalse(zones.limiting(a), "only-inside off");
        assertTrue(SnowZones.winterAt(true, zones, a, 50, 0));
    }
}
