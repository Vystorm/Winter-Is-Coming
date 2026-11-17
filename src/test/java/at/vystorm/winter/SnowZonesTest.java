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
        zones.configure(true, 24);
        assertTrue(zones.allows(a, 500, 500), "no spots: snow everywhere");
        zones.add("town", new SnowZones.Spot(a, 0, 0, 50));
        assertTrue(zones.allows(a, 30, 30));
        assertFalse(zones.allows(a, 60, 0), "outside the spot");
        assertTrue(zones.allows(b, 60, 0), "a world without spots keeps snow");
        assertTrue(zones.toggleBuild(builder));
        zones.updateBuilders(List.<Object[]>of(new Object[]{a, 10.0, 10.0}));
        assertFalse(zones.allows(a, 20, 20), "near a builder");
        assertTrue(zones.allows(a, 30, -10), "inside the spot, away from the builder");
        assertTrue(new SnowZones(dir.resolve("spots.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).spots().containsKey("town"), "saved");
        assertTrue(zones.limiting());
        zones.configure(false, 24);
        assertFalse(zones.limiting(), "only-inside off: spots do not limit");
        zones.updateBuilders(List.of());
        assertTrue(zones.allows(a, 60, 0), "only-inside off");
    }
}
