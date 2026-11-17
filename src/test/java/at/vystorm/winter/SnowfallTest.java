package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.world.Snowfall;
import at.vystorm.winter.world.SnowZones;
import java.nio.file.Files;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SnowfallTest {
    @Test void flakesPerRunFollowTheAmountAndCarryTheFraction() {
        assertEquals(12, Snowfall.step(60, 0).flakes());
        assertEquals(0, Snowfall.step(60, 0).carry(), 1e-9);
        // 3 per second = 0.6 per run: 0,1,0,1,1 over five runs = 3
        double carry = 0;
        int total = 0;
        for (int i = 0; i < 5; i++) { var s = Snowfall.step(3, carry); total += s.flakes(); carry = s.carry(); }
        assertEquals(3, total);
        total = 0; carry = 0;
        for (int i = 0; i < 50; i++) { var s = Snowfall.step(1, carry); total += s.flakes(); carry = s.carry(); }
        assertEquals(10, total);
        assertEquals(80, Snowfall.step(9999, 0).flakes());
        assertEquals(0, Snowfall.step(-5, Double.NaN).flakes() - Snowfall.step(1, 0).flakes());
    }

    @Test void settingsAreClampedAndDefaultsAreOff() {
        assertFalse(Snowfall.Settings.read(new YamlConfiguration()).enabled());
        var y = new YamlConfiguration();
        y.set("snowfall.enabled", true); y.set("snowfall.amount", 9999); y.set("snowfall.radius", 1); y.set("snowfall.height", 99);
        y.set("snowfall.flake-size", 9.0); y.set("snowfall.only-under-sky", false);
        var s = Snowfall.Settings.read(y);
        assertTrue(s.enabled());
        assertEquals(400, s.amount()); assertEquals(4, s.radius()); assertEquals(16, s.height());
        assertEquals(2.0, s.flakeSize()); assertFalse(s.onlyUnderSky()); assertTrue(s.onlyInWinter());
        y.set("snowfall.amount", 0); y.set("snowfall.flake-size", 0.01);
        assertEquals(1, Snowfall.Settings.read(y).amount());
        assertEquals(0.5, Snowfall.Settings.read(y).flakeSize());
    }

    @Test void sliderZeroMeansOffAndOtherValuesClamp() {
        assertEquals(0, Snowfall.sliderAmount(0));
        assertEquals(0, Snowfall.sliderAmount(Double.NaN));
        assertEquals(10, Snowfall.sliderAmount(9.6));
        assertEquals(400, Snowfall.sliderAmount(12345));
    }

    @Test void roofMeansHighestBlockAboveTheHead() {
        assertFalse(Snowfall.roofed(63, 64), "standing on the ground");
        assertFalse(Snowfall.roofed(65, 64), "snow layer or slab at the feet");
        assertTrue(Snowfall.roofed(66, 64), "block above the head");
    }

    @Test void skipDecisionFollowsEveryCondition() {
        assertTrue(Snowfall.shows(true, false, true, true, false, true, true, true));
        assertFalse(Snowfall.shows(false, false, true, true, false, true, true, true), "feature off");
        assertFalse(Snowfall.shows(true, true, true, true, false, true, true, true), "player switched it off");
        assertFalse(Snowfall.shows(true, false, false, true, false, true, true, true), "chunk not loaded");
        assertFalse(Snowfall.shows(true, false, true, true, true, true, true, true), "roof");
        assertTrue(Snowfall.shows(true, false, true, false, true, true, true, true), "roof ignored when only-under-sky is off");
        assertFalse(Snowfall.shows(true, false, true, true, false, true, false, true), "no winter");
        assertTrue(Snowfall.shows(true, false, true, true, false, false, false, true), "no winter needed");
        assertFalse(Snowfall.shows(true, false, true, true, false, true, true, false), "outside the spots");
    }

    @Test void spotsLimitFlakesOnlyInWorldsThatHaveSpots() throws Exception {
        UUID w = UUID.randomUUID(), other = UUID.randomUUID();
        var zones = new SnowZones(Files.createTempDirectory("spots").resolve("spots.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        zones.configure(true, 24);
        assertTrue(zones.insideSpots(w, 500, 500));
        zones.add("a", new SnowZones.Spot(w, 0, 0, 50));
        assertTrue(zones.insideSpots(w, 10, 10));
        assertFalse(zones.insideSpots(w, 500, 500));
        assertTrue(zones.insideSpots(other, 500, 500));
        zones.configure(false, 24);
        assertTrue(zones.insideSpots(w, 500, 500));
    }
}
