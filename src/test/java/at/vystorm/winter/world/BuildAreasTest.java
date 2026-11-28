package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Snow-free building (1.28.0): the bookkeeping of players' areas and the halos around fresh blocks. */
class BuildAreasTest {
    static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000b1"), OTHER = UUID.randomUUID();
    static final UUID ANNA = UUID.randomUUID(), BEN = UUID.randomUUID(), CARL = UUID.randomUUID(), ADMIN = UUID.randomUUID();
    static final long MIN = 60_000L;
    long now = 1_000_000L;
    final BuildAreas areas = new BuildAreas(() -> now);

    /** radius 32, 30 min after, at most 2 areas, 10 s cooldown, halos radius 6 for 30 min, at most 3 halos. */
    void small() {
        areas.configure(new BuildAreas.Settings(32, 64, 30 * MIN, 2, 10_000, true, 6, 30 * MIN, 3, 64));
    }

    @Test void givenAreaSwitchedOn_whenAsked_thenTheCircleAroundTheSpotIsFreeAndItStaysWhileOn() {
        small();
        var started = areas.start(ANNA, WORLD, 100, 200, 0, false, null);
        assertEquals(BuildAreas.Outcome.STARTED, started.outcome());
        assertEquals(32, started.area().radius(), "0 takes the configured radius");
        assertTrue(areas.free(WORLD, 100, 200));
        assertTrue(areas.free(WORLD, 132, 200), "on the edge");
        assertTrue(areas.free(WORLD, 122, 222), "22,22 is inside 32");
        assertFalse(areas.free(WORLD, 133, 200), "just outside");
        assertFalse(areas.free(WORLD, 124, 224), "24,24 is outside 32");
        assertFalse(areas.free(OTHER, 100, 200), "other world");
        now += 24 * 60 * MIN;
        areas.expire();
        assertTrue(areas.free(WORLD, 100, 200), "stays as long as it is on, a day later too");
        assertTrue(areas.area(ANNA).orElseThrow().on());
    }

    @Test void givenAreaSwitchedOff_whenTheAfterTimeRuns_thenItStaysFreeAndEndsOnTime() {
        small();
        areas.start(ANNA, WORLD, 0, 0, 0, false, null);
        now += 5 * MIN;
        var stopped = areas.stop(ANNA).orElseThrow();
        assertFalse(stopped.on());
        assertEquals(30, stopped.minutesLeft(now));
        now += 29 * MIN;
        assertTrue(areas.expire().isEmpty());
        assertTrue(areas.free(WORLD, 10, 10), "still free in minute 29");
        assertEquals(1, areas.area(ANNA).orElseThrow().minutesLeft(now));
        now += MIN;
        assertFalse(areas.free(WORLD, 10, 10), "free() ends at the minute even before the cleanup");
        var ended = areas.expire();
        assertEquals(1, ended.size());
        assertEquals(ANNA, ended.get(0).player());
        assertTrue(areas.area(ANNA).isEmpty());
        assertTrue(areas.empty(), "nothing left: lookups are one empty check");
        assertTrue(areas.stop(ANNA).isEmpty(), "nothing to stop");
    }

    @Test void givenPlayerLogsOut_whenLeft_thenTheAreaCountsAsSwitchedOff() {
        small();
        areas.start(ANNA, WORLD, 0, 0, 0, false, null);
        areas.left(ANNA);
        assertFalse(areas.area(ANNA).orElseThrow().on());
        assertTrue(areas.free(WORLD, 0, 0));
        now += 30 * MIN;
        assertEquals(1, areas.expire().size());
        assertFalse(areas.free(WORLD, 0, 0));
    }

    @Test void givenServerCap_whenAThirdPlayerStarts_thenRefusedButAdminsAndMovesAreNotCounted() {
        small();
        assertEquals(BuildAreas.Outcome.STARTED, areas.start(ANNA, WORLD, 0, 0, 0, false, null).outcome());
        assertEquals(BuildAreas.Outcome.STARTED, areas.start(BEN, WORLD, 500, 0, 0, false, null).outcome());
        assertEquals(BuildAreas.Outcome.LIMIT, areas.start(CARL, WORLD, 1000, 0, 0, false, null).outcome());
        assertFalse(areas.free(WORLD, 1000, 0));
        assertEquals(BuildAreas.Outcome.STARTED, areas.start(ADMIN, WORLD, 2000, 0, 64, true, null).outcome(), "unlimited");
        assertEquals(64, areas.area(ADMIN).orElseThrow().radius());
        assertEquals(2, areas.counted());
        now += 11_000;
        var moved = areas.start(ANNA, WORLD, 300, 0, 0, false, null);
        assertEquals(BuildAreas.Outcome.STARTED, moved.outcome(), "his own area does not count against him");
        assertFalse(areas.free(WORLD, 0, 0), "one area per player: the old one ended");
        assertTrue(areas.free(WORLD, 300, 0));
        areas.stop(BEN);
        assertEquals(BuildAreas.Outcome.LIMIT, areas.start(CARL, WORLD, 1000, 0, 0, false, null).outcome(), "an area in its after-time still counts");
        now += 30 * MIN;
        areas.expire();
        assertEquals(BuildAreas.Outcome.STARTED, areas.start(CARL, WORLD, 1000, 0, 0, false, null).outcome());
    }

    @Test void givenCooldown_whenMovedTooSoon_thenRefusedWithTheRestOfTheWait() {
        small();
        areas.start(ANNA, WORLD, 0, 0, 0, false, null);
        now += 4_000;
        var refused = areas.start(ANNA, WORLD, 50, 0, 0, false, null);
        assertEquals(BuildAreas.Outcome.COOLDOWN, refused.outcome());
        assertEquals(6_000, refused.waitMillis());
        assertTrue(areas.free(WORLD, 0, 0), "the old area stays");
        assertEquals(BuildAreas.Outcome.STARTED, areas.start(ADMIN, WORLD, 0, 0, 0, true, null).outcome());
        assertEquals(BuildAreas.Outcome.STARTED, areas.start(ADMIN, WORLD, 9, 0, 0, true, null).outcome(), "no cooldown for unlimited");
    }

    @Test void givenTwoPlayersOverlap_whenOneEnds_thenTheOverlapStaysFreeThroughTheOther() {
        small();
        areas.start(ANNA, WORLD, 0, 0, 0, false, null);
        areas.start(BEN, WORLD, 40, 0, 0, false, null);
        assertTrue(areas.free(WORLD, 20, 0));
        areas.stop(ANNA);
        now += 31 * MIN;
        areas.expire();
        assertTrue(areas.free(WORLD, 20, 0), "Ben's area still covers it");
        assertFalse(areas.free(WORLD, -20, 0), "Anna's own part is back to winter");
    }

    @Test void givenProtectedColumns_whenStarted_thenOnlyThePlayersOwnColumnsAreFree() {
        small();
        // a neighbour's plot east of x = 10
        areas.start(ANNA, WORLD, 0, 0, 0, false, (x, z) -> x < 10);
        assertTrue(areas.free(WORLD, 9, 5));
        assertFalse(areas.free(WORLD, 10, 5), "the neighbour's plot keeps its snow");
        assertFalse(areas.free(WORLD, 30, 0));
    }

    @Test void givenRadiusOverTheCap_whenStarted_thenCapped() {
        areas.configure(new BuildAreas.Settings(200, 500, 0, 16, 0, true, 6, MIN, 10, 64));
        assertEquals(BuildAreas.Settings.HARD_RADIUS, areas.settings().maxRadius());
        assertEquals(BuildAreas.Settings.HARD_RADIUS, areas.start(ANNA, WORLD, 0, 0, 0, false, null).area().radius());
        assertEquals(BuildAreas.Settings.HARD_RADIUS, areas.start(ADMIN, WORLD, 0, 0, 10_000, true, null).area().radius());
        areas.stop(ANNA);
        assertTrue(areas.area(ANNA).isEmpty(), "after-minutes 0: the snow returns at once");
    }

    // ---- halos: no snow on fresh player blocks ---------------------------------------------------------------------------

    @Test void givenABlockPlaced_whenHaloMade_thenNoSnowFormsOnOrAroundItForItsTimeThroughTheZones(@TempDir Path dir) {
        small();
        SnowZones zones = new SnowZones(dir.resolve("spots.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        zones.areas(areas);
        assertTrue(zones.allows(WORLD, 7.5, 7.5), "winter before the block");
        var clear = areas.halo(BEN, WORLD, 7, 7, () -> null);
        assertEquals(new BuildAreas.Clear(WORLD, 7, 7, 6), clear, "a new halo is cleared at once");
        assertFalse(zones.allows(WORLD, 7.5, 7.5), "the new block itself");
        assertFalse(zones.allows(WORLD, 13.2, 7.9), "six blocks east");
        assertTrue(zones.allows(WORLD, 14.0, 7.0), "seven blocks east: winter");
        assertFalse(zones.allows(WORLD, 3, 3), "four blocks diagonally");
        assertFalse(zones.allows(WORLD, 1.0, 7.0), "six blocks west");
        assertNotNull(areas.halo(BEN, WORLD, 17, 30, () -> null));
        assertFalse(zones.allows(WORLD, 14, 30), "a halo near a chunk border reaches into the next chunk (x 14 is chunk 0)");
        now += 29 * MIN;
        assertFalse(zones.allows(WORLD, 7, 7), "still free in minute 29");
        now += MIN;
        assertTrue(zones.allows(WORLD, 7, 7), "after 30 min the snow may come back");
        areas.expire();
        assertEquals(0, areas.halos());
    }

    @Test void givenBlocksPlacedSideBySide_whenHalos_thenNearOnesMergeAndRefreshTheTime() {
        small();
        assertNotNull(areas.halo(BEN, WORLD, 0, 0, () -> null));
        now += 20 * MIN;
        assertNull(areas.halo(BEN, WORLD, 1, 1, () -> null), "two blocks away: the same halo, refreshed");
        assertEquals(1, areas.halos());
        now += 20 * MIN;
        assertTrue(areas.free(WORLD, 0, 0), "refreshed 20 min ago");
        assertNotNull(areas.halo(BEN, WORLD, 5, 0, () -> null), "farther: a halo of its own");
        assertEquals(2, areas.halos());
    }

    @Test void givenHaloCap_whenMoreHalos_thenTheOldestEndsFirst() {
        small();
        areas.halo(BEN, WORLD, 0, 0, () -> null);
        areas.halo(BEN, WORLD, 100, 0, () -> null);
        areas.halo(BEN, WORLD, 200, 0, () -> null);
        areas.halo(BEN, WORLD, 300, 0, () -> null);
        assertEquals(3, areas.halos());
        assertFalse(areas.free(WORLD, 0, 0), "the oldest went");
        assertTrue(areas.free(WORLD, 300, 0));
    }

    @Test void givenOwnAreaOn_whenBlockPlacedInside_thenNoExtraHalo() {
        small();
        areas.start(ANNA, WORLD, 0, 0, 0, false, null);
        assertNull(areas.halo(ANNA, WORLD, 10, 10, () -> null));
        assertEquals(0, areas.halos());
        assertNotNull(areas.halo(ANNA, WORLD, 30, 0, () -> null), "near the edge the halo reaches out of the area");
    }

    @Test void givenHalosSwitchedOff_whenBlockPlaced_thenNothingAndOldHalosGo() {
        small();
        areas.halo(BEN, WORLD, 0, 0, () -> null);
        areas.configure(new BuildAreas.Settings(32, 64, 30 * MIN, 2, 10_000, false, 6, 30 * MIN, 3, 64));
        assertEquals(0, areas.halos());
        assertNull(areas.halo(BEN, WORLD, 0, 0, () -> null));
        assertFalse(areas.free(WORLD, 0, 0));
    }

    @Test void givenChunks_whenTouches_thenOnlyChunksAnActiveCircleReaches() {
        small();
        areas.start(ANNA, WORLD, 0, 0, 0, false, null);
        assertTrue(areas.touches(WORLD, -2, -2), "x -32 is chunk -2");
        assertTrue(areas.touches(WORLD, 2, 0), "x 32 is chunk 2");
        assertFalse(areas.touches(WORLD, 3, 0));
        assertFalse(areas.touches(OTHER, 0, 0));
    }

    @Test void nearestFirstOrdersRingByRingAndTopBeforeBottom() {
        var cells = new java.util.ArrayList<int[]>(java.util.List.of(new int[]{5, 64, 0}, new int[]{0, 64, 1}, new int[]{0, 65, 1}, new int[]{0, 70, 0}, new int[]{-3, 64, -3}));
        cells.sort(BuildAreas.nearestFirst(0, 0));
        assertArrayEquals(new int[]{0, 70, 0}, cells.get(0));
        assertArrayEquals(new int[]{0, 65, 1}, cells.get(1), "the upper snow of a column before the lower");
        assertArrayEquals(new int[]{0, 64, 1}, cells.get(2));
        assertArrayEquals(new int[]{-3, 64, -3}, cells.get(3));
        assertArrayEquals(new int[]{5, 64, 0}, cells.get(4));
    }
}
