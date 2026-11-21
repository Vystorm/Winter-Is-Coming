package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.storage.LampStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The block break and place handlers of the winter terrain and the lamps, called by Vystorm Core's real stage
 * dispatcher together with the handlers of a second plugin.
 */
class WinterBlockStagesTest {
    @TempDir Path dir;
    private final List<String> calls = new ArrayList<>();
    private final Plugin other = EventStageTesting.plugin("AnotherPlugin");
    private final Player player = mock(Player.class);
    private WinterPlugin plugin;
    private WinterTerrain terrain;
    private WinterListeners listeners;
    private Block block, above;

    @BeforeEach
    void setUp() {
        plugin = mock(WinterPlugin.class);
        when(plugin.getName()).thenReturn("WinterIsComing");
        when(plugin.isEnabled()).thenReturn(true);
        terrain = mock(WinterTerrain.class);
        listeners = new WinterListeners(plugin, terrain);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        block = mock(Block.class);
        above = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getRelative(BlockFace.UP)).thenReturn(above);
        when(above.getType()).thenReturn(Material.AIR);
    }

    private BlockPlaceEvent placing() {
        return new BlockPlaceEvent(block, mock(BlockState.class), block, mock(ItemStack.class), player, true, EquipmentSlot.HAND);
    }

    @Test
    void givenWinterSnow_whenBroken_thenItIsTakenAwayWithoutDropsAfterTheFeaturesAndNothingObservesABreak() {
        when(terrain.owned(block)).thenReturn(true);
        EventStageTesting harness = EventStageTesting.detached();
        EventStages stages = harness.stages();
        stages.handle(other, BlockBreakEvent.class, EventStage.ACT, event -> calls.add("act"));
        stages.handle(other, BlockBreakEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        listeners.handleBlockEvents(stages);

        BlockBreakEvent event = harness.fire(new BlockBreakEvent(block, player));

        assertTrue(event.isCancelled());
        verify(terrain).clear(block);
        verify(terrain, never()).release(any());
        assertEquals(List.of("act"), calls);
    }

    @Test
    void givenWinterSnow_whenAProtectionDeniesTheBreak_thenTheSnowStays() {
        when(terrain.owned(block)).thenReturn(true);
        EventStageTesting harness = EventStageTesting.detached();
        EventStages stages = harness.stages();
        listeners.handleBlockEvents(stages);
        stages.handle(other, BlockBreakEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));

        assertTrue(harness.fire(new BlockBreakEvent(block, player)).isCancelled());

        verify(terrain, never()).clear(any());
        verify(terrain, never()).release(any());
    }

    @Test
    void givenOrdinaryBlock_whenBrokenOrPlaced_thenItsSpotIsReleasedOnlyIfTheActionWasNotDenied() {
        boolean[] deny = {true};
        EventStageTesting harness = EventStageTesting.detached();
        EventStages stages = harness.stages();
        listeners.handleBlockEvents(stages);
        stages.handle(other, BlockBreakEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));
        stages.handle(other, BlockPlaceEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));

        harness.fire(new BlockBreakEvent(block, player));
        harness.fire(placing());
        verify(terrain, never()).release(any());
        verify(terrain, never()).placed(any());

        deny[0] = false;
        assertFalse(harness.fire(new BlockBreakEvent(block, player)).isCancelled());
        verify(terrain).release(block);
        assertFalse(harness.fire(placing()).isCancelled());
        verify(terrain, org.mockito.Mockito.times(2)).release(block);
        // 1.25.0: a placed block also queues an early re-check of sheltered snow it may now block
        verify(terrain).placed(block);
    }

    @Test
    void givenLamp_whenItsBreakIsDenied_thenItStaysALampAndIsForgottenOnlyWhenReallyBroken() {
        LampStore store = new LampStore(dir.resolve("lamps.json"), AtomicFilesInterface.DEFAULT);
        String key = LampStore.key(block.getWorld().getUID(), 0, 0, 0);
        store.add(key);
        WinterLamps lamps = new WinterLamps(plugin, store);
        boolean[] deny = {true};
        EventStageTesting harness = EventStageTesting.detached();
        EventStages stages = harness.stages();
        lamps.handleBlockEvents(stages);
        stages.handle(other, BlockBreakEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));

        harness.fire(new BlockBreakEvent(block, player));
        assertTrue(store.has(key));

        deny[0] = false;
        harness.fire(new BlockBreakEvent(block, player));
        assertFalse(store.has(key));
    }

    /** A second plugin's handlers in every later stage, to see what still runs after the winter terrain decided. */
    private EventStageTesting terrainAndAnotherPlugin(Class<? extends org.bukkit.event.Event> type) {
        EventStageTesting harness = EventStageTesting.detached();
        harness.handle(other, type, EventStage.ADJUST, event -> calls.add("adjust"));
        harness.handle(other, type, EventStage.ACT, event -> calls.add("act"));
        harness.handle(other, type, EventStage.OBSERVE, event -> calls.add("observe"));
        listeners.handleTerrainEvents(harness.stages());
        return harness;
    }

    @Test
    void givenWinterSnow_whenAPistonWaterOrMeltingWouldChangeIt_thenItIsDeniedBeforeAnyLaterHandler() {
        when(terrain.owned(block)).thenReturn(true);
        Block piston = mock(Block.class), source = mock(Block.class);

        assertTrue(terrainAndAnotherPlugin(org.bukkit.event.block.BlockPistonExtendEvent.class)
                .fire(new org.bukkit.event.block.BlockPistonExtendEvent(piston, List.of(block), BlockFace.EAST)).isCancelled());
        assertTrue(terrainAndAnotherPlugin(org.bukkit.event.block.BlockPistonRetractEvent.class)
                .fire(new org.bukkit.event.block.BlockPistonRetractEvent(piston, List.of(block), BlockFace.EAST)).isCancelled());
        assertTrue(terrainAndAnotherPlugin(org.bukkit.event.block.BlockFromToEvent.class)
                .fire(new org.bukkit.event.block.BlockFromToEvent(source, block)).isCancelled());
        assertTrue(terrainAndAnotherPlugin(org.bukkit.event.block.BlockFadeEvent.class)
                .fire(new org.bukkit.event.block.BlockFadeEvent(block, mock(BlockState.class))).isCancelled());

        assertEquals(List.of(), calls);
    }

    @Test
    void givenOrdinaryBlocks_whenAPistonMovesThem_thenTheLaterHandlersRun() {
        Block piston = mock(Block.class), pushed = mock(Block.class), free = mock(Block.class);
        when(pushed.getRelative(any(BlockFace.class))).thenReturn(free);

        assertFalse(terrainAndAnotherPlugin(org.bukkit.event.block.BlockPistonExtendEvent.class)
                .fire(new org.bukkit.event.block.BlockPistonExtendEvent(piston, List.of(pushed), BlockFace.EAST)).isCancelled());

        assertEquals(List.of("adjust", "act", "observe"), calls);
    }

    @Test
    void givenWinterSnowInAnExplosion_thenItIsTakenOffTheListBeforeAnotherPluginReadsIt() {
        Block ordinary = mock(Block.class), air = mock(Block.class);
        when(ordinary.getRelative(BlockFace.UP)).thenReturn(air);
        when(terrain.owned(block)).thenReturn(true);
        List<Block> blocks = new ArrayList<>(List.of(block, ordinary));
        org.bukkit.event.entity.EntityExplodeEvent explosion = mock(org.bukkit.event.entity.EntityExplodeEvent.class);
        when(explosion.blockList()).thenReturn(blocks);
        EventStageTesting harness = EventStageTesting.detached();
        List<Block> seen = new ArrayList<>();
        harness.handle(other, org.bukkit.event.entity.EntityExplodeEvent.class, EventStage.ADJUST, event -> seen.addAll(event.blockList()));
        listeners.handleTerrainEvents(harness.stages());

        harness.fire(explosion);

        assertEquals(List.of(ordinary), seen);
    }

    @Test
    void givenWinterWorld_whenVanillaSnowWouldForm_thenTheRuleRefusesItAndNothingObservesIt() {
        when(plugin.controls(any())).thenReturn(true);
        BlockState snow = mock(BlockState.class);
        when(snow.getType()).thenReturn(Material.SNOW);
        EventStageTesting harness = EventStageTesting.detached();
        harness.handle(other, org.bukkit.event.block.BlockFormEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        listeners.handleTerrainEvents(harness.stages());

        assertTrue(harness.fire(new org.bukkit.event.block.BlockFormEvent(block, snow)).isCancelled());
        assertEquals(List.of(), calls);

        when(terrain.applying()).thenReturn(true);
        assertFalse(harness.fire(new org.bukkit.event.block.BlockFormEvent(block, snow)).isCancelled());
        assertEquals(List.of("observe"), calls);
    }
}
