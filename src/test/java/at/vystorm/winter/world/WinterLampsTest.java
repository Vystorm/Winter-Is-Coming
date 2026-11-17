package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.storage.LampStore;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WinterLampsTest {
    @TempDir Path dir;

    @Test void coloursStayInRangeAlsoForNegativeCoordinates() {
        for (int x = -50; x < 50; x++) {
            int c = WinterLamps.colour(x, -60, -x * 7, 12345, 5);
            assertTrue(c >= 0 && c < 5);
        }
    }
    @Test void everyLampChangesWithTheStep() {
        assertNotEquals(WinterLamps.colour(1, 2, 3, 0, 5), WinterLamps.colour(1, 2, 3, 1, 5));
    }
    @Test void neighboursDiffer() {
        assertNotEquals(WinterLamps.colour(0, 64, 0, 0, 5), WinterLamps.colour(1, 64, 0, 0, 5));
    }
    @Test void onlyGlassCanBeALamp() {
        assertTrue(WinterLamps.lampMaterial(Material.GLASS));
        assertTrue(WinterLamps.lampMaterial(Material.RED_STAINED_GLASS));
        assertFalse(WinterLamps.lampMaterial(Material.GLASS_PANE));
        assertFalse(WinterLamps.lampMaterial(Material.STONE));
    }
    @Test void lampsSurviveARestartAndCanBeRemoved() {
        UUID world = UUID.randomUUID();
        String key = LampStore.key(world, -4, 70, 9);
        var store = new LampStore(dir.resolve("lamps.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        store.add(key);
        var again = new LampStore(dir.resolve("lamps.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(again.has(key));
        assertEquals(new LampStore.Position(world, -4, 70, 9), LampStore.parse(key));
        again.remove(key);
        assertEquals(0, new LampStore(dir.resolve("lamps.json"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).size());
    }
    @Test void snowCoversSmallPlantsButNotTallOnes() {
        assertTrue(WinterTerrain.smallPlant(Material.SHORT_GRASS));
        assertTrue(WinterTerrain.smallPlant(Material.POPPY));
        assertFalse(WinterTerrain.smallPlant(Material.TALL_GRASS));
        assertFalse(WinterTerrain.smallPlant(Material.ROSE_BUSH));
        assertFalse(WinterTerrain.smallPlant(Material.WHEAT));
    }
    @Test void brokenKeysAreIgnored() {
        assertNull(LampStore.parse("nope"));
        assertNull(LampStore.parse("a,b,c,d"));
    }
}
