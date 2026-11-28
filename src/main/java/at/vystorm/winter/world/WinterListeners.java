package at.vystorm.winter.world;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import at.vystorm.winter.WinterPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.*;
import org.bukkit.event.world.*;

/** Event gates are synchronous; ignore previously cancelled actions and preserve foreign protection. */
public final class WinterListeners implements Listener {
    private final WinterPlugin plugin;
    private final WinterTerrain terrain;
    public WinterListeners(WinterPlugin plugin,WinterTerrain terrain){this.plugin=plugin;this.terrain=terrain;}
    @EventHandler public void load(ChunkLoadEvent event){terrain.load(event.getChunk());}
    /** The chunk record is stored in the chunk already (1.24.2); the chunk leaves the memory. */
    @EventHandler(priority=EventPriority.MONITOR) public void unload(ChunkUnloadEvent event){terrain.unload(event.getChunk());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void unloadWorld(WorldUnloadEvent event){terrain.unloadWorld(event.getWorld());}
    @EventHandler public void join(PlayerJoinEvent event){plugin.gifts().reconnected(event.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent event){if(plugin.coldLoop()!=null)plugin.coldLoop().release(event.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void eat(PlayerItemConsumeEvent event){
        if(plugin.coldLoop()!=null)plugin.coldLoop().ate(event.getPlayer(),event.getItem().getType());
        if(plugin.missions()!=null && java.util.Set.of(Material.MUSHROOM_STEW,Material.RABBIT_STEW,Material.BEETROOT_SOUP,Material.SUSPICIOUS_STEW).contains(event.getItem().getType()))
            plugin.missions().contribute(event.getPlayer(),at.vystorm.winter.CommunityMissions.Objective.HOT_MEAL);
    }
    /** Stage ADJUST, registered in {@link #handleTerrainEvents}. */
    public void form(BlockFormEvent e){
        if(!terrain.applying()&&plugin.controls(e.getBlock().getWorld())&&(e.getNewState().getType()==Material.SNOW||e.getNewState().getType()==Material.ICE))e.setCancelled(true);
    }
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void fade(BlockFadeEvent e){if(terrain.owned(e.getBlock()))e.setCancelled(true);}
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void physics(BlockPhysicsEvent e){
        if(e.getBlock().getType()==Material.SNOW&&terrain.owned(e.getBlock()))e.setCancelled(true);
    }
    /**
     * The events that could change the plugin's own snow go through Core's stages. Keeping owned snow from melting,
     * falling, being flooded, moved by pistons, changed by entities or blown up only denies: PROTECT, before every
     * feature of another plugin. Refusing vanilla snow and ice in a winter world is a rule of the plugin: ADJUST. None
     * of them sees a cancelled event.
     */
    public void handleTerrainEvents(EventStages stages){
        stages.handle(plugin,BlockFormEvent.class,EventStage.ADJUST,this::form);
        stages.handle(plugin,BlockFadeEvent.class,EventStage.PROTECT,this::fade);
        stages.handle(plugin,BlockPhysicsEvent.class,EventStage.PROTECT,this::physics);
        stages.handle(plugin,BlockFromToEvent.class,EventStage.PROTECT,this::flow);
        stages.handle(plugin,EntityChangeBlockEvent.class,EventStage.PROTECT,this::entity);
        stages.handle(plugin,EntityExplodeEvent.class,EventStage.PROTECT,(EntityExplodeEvent e)->explode(e));
        stages.handle(plugin,BlockExplodeEvent.class,EventStage.PROTECT,(BlockExplodeEvent e)->explode(e));
        stages.handle(plugin,BlockPistonExtendEvent.class,EventStage.PROTECT,this::extend);
        stages.handle(plugin,BlockPistonRetractEvent.class,EventStage.PROTECT,this::retract);
    }
    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins.
     * Breaking is FINISH: after every protection and feature the plugin takes its own snow away without drops, at the
     * same Bukkit priority (HIGHEST) as before. Placing only releases the spot: OBSERVE. Neither sees a cancelled event.
     */
    public void handleBlockEvents(EventStages stages){
        stages.handle(plugin,BlockBreakEvent.class,EventStage.FINISH,this::breakBlock);
        stages.handle(plugin,BlockPlaceEvent.class,EventStage.OBSERVE,this::place);
    }
    /** Stage FINISH, registered in {@link #handleBlockEvents}. */
    public void breakBlock(BlockBreakEvent e){
        if(terrain.owned(e.getBlock())){e.setCancelled(true);terrain.clear(e.getBlock());}
        else terrain.release(e.getBlock());
        // Remove unsupported artificial snow before the support disappears; never create free drops.
        Block above=e.getBlock().getRelative(org.bukkit.block.BlockFace.UP);
        if(!e.isCancelled()&&above.getType()==Material.SNOW&&terrain.owned(above))terrain.clear(above);
        // Snow-free halo around the broken block (1.28.0); the plugin's own snow (shovelling) is its own rule.
        if(!e.isCancelled())plugin.builtNear(e.getPlayer(),e.getBlock(),false);
    }
    /**
     * Stage OBSERVE, registered in {@link #handleBlockEvents}: releases the spot; sheltered snow whose wind the new block
     * may block is checked again soon (1.25.0); the plugin's snow under the block stays and a snow-free halo starts (1.28.0).
     */
    public void place(BlockPlaceEvent e){terrain.release(e.getBlock());terrain.placed(e.getBlock());plugin.builtNear(e.getPlayer(),e.getBlock(),true);}
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void flow(BlockFromToEvent e){if(terrain.owned(e.getToBlock()))e.setCancelled(true);}
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void entity(EntityChangeBlockEvent e){if(terrain.owned(e.getBlock()))e.setCancelled(true);}
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void explode(EntityExplodeEvent e){e.blockList().removeIf(this::protectedExplosion);}
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void explode(BlockExplodeEvent e){e.blockList().removeIf(this::protectedExplosion);}
    private boolean protectedExplosion(Block b){return terrain.owned(b)||terrain.owned(b.getRelative(org.bukkit.block.BlockFace.UP));}
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void extend(BlockPistonExtendEvent e){
        if(e.getBlocks().stream().anyMatch(b->terrain.owned(b)||terrain.owned(b.getRelative(e.getDirection()))||terrain.owned(b.getRelative(org.bukkit.block.BlockFace.UP))))e.setCancelled(true);
    }
    /** Stage PROTECT, registered in {@link #handleTerrainEvents}. */
    public void retract(BlockPistonRetractEvent e){
        if(e.getBlocks().stream().anyMatch(b->terrain.owned(b)||terrain.owned(b.getRelative(e.getDirection()))||terrain.owned(b.getRelative(org.bukkit.block.BlockFace.UP))))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void weather(WeatherChangeEvent e){if(plugin.weather().owns(e.getWorld())&&!plugin.weather().applying())e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void thunder(ThunderChangeEvent e){if(plugin.weather().owns(e.getWorld())&&!plugin.weather().applying())e.setCancelled(true);}
}
