package at.vystorm.winter.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WinterStormsTest {
    private static final WinterStorms.Options OPTIONS = new WinterStorms.Options(true, 60, 120, 2, 6, 0.5, 1.6, 2.5, true);

    @Test
    void givenFullWinter_whenPlanned_thenGapWarningAndDurationFit() {
        for (int seed = 0; seed < 50; seed++) {
            WinterStorms.Storm storm = WinterStorms.plan(0, OPTIONS, 1, new Random(seed));
            assertNotNull(storm);
            assertTrue(storm.start() >= 60 * 60_000L && storm.start() <= 120 * 60_000L);
            assertEquals(storm.start() - 2 * 60_000L, storm.warnAt());
            assertEquals(storm.start() + 6 * 60_000L, storm.end());
        }
    }

    @Test
    void givenWeakOrNoWinter_whenPlanned_thenRarerOrNone() {
        assertNull(WinterStorms.plan(0, OPTIONS, 0, new Random(1)));
        WinterStorms.Storm weak = WinterStorms.plan(0, OPTIONS, 0.25, new Random(1));
        assertTrue(weak.start() >= 4 * 60 * 60_000L, "a quarter-strength winter stretches the gap four times");
        assertNull(WinterStorms.plan(0, new WinterStorms.Options(false, 60, 120, 2, 6, 0.5, 1.6, 2.5, true), 1, new Random(1)));
    }

    @Test
    void givenKinds_whenColdFactor_thenBlizzardColdest() {
        assertEquals(1, WinterStorms.coldFactor(null, OPTIONS));
        assertEquals(1.6, WinterStorms.coldFactor(WinterStorms.Kind.SNOWSTORM, OPTIONS));
        assertEquals(2.5, WinterStorms.coldFactor(WinterStorms.Kind.BLIZZARD, OPTIONS));
        assertTrue(new WinterStorms.Storm(WinterStorms.Kind.BLIZZARD, 0, 10, 20).running(15));
    }
}
