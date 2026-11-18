package at.vystorm.winter.world;

import at.vystorm.winter.WinterPlugin;
import at.vystorm.winter.storage.LampStore;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Christmas lights: glass blocks marked with {@code /winter lamp} change colour during the season. The colours are sent
 * only to nearby players; the world keeps the real glass, so nothing is left behind when the season ends.
 */
public final class WinterLamps implements Listener {
    public static final int MAX_LAMPS=4096, MAX_PER_PLAYER=512;
    private static final Material[] COLOURS={Material.RED_STAINED_GLASS,Material.LIME_STAINED_GLASS,Material.YELLOW_STAINED_GLASS,Material.LIGHT_BLUE_STAINED_GLASS,Material.ORANGE_STAINED_GLASS};
    private final WinterPlugin plugin;
    private final LampStore store;
    private long step;
    private boolean shown;
    public WinterLamps(WinterPlugin plugin,LampStore store){this.plugin=plugin;this.store=store;}

    /** The colour index of a lamp in a cycle step; neighbours differ, so a tree twinkles instead of flashing as one. */
    public static int colour(int x,int y,int z,long step,int colours){
        return (int)Math.floorMod(x*31L+y*17L+z*13L+step,(long)colours);
    }
    public static boolean lampMaterial(Material material){return material==Material.GLASS||material.name().endsWith("_STAINED_GLASS");}
    private static String key(Block b){return LampStore.key(b.getWorld().getUID(),b.getX(),b.getY(),b.getZ());}
    private double range(){return Math.clamp(plugin.getConfig().getDouble("lamps.range",48),8,128);}
    public int periodTicks(){return Math.clamp(plugin.getConfig().getInt("lamps.period-ticks",20),5,200);}

    public void toggle(Player player){
        var lang=plugin.lang();
        if(!plugin.getConfig().getBoolean("lamps.enabled",true)){lang.send(player,at.vystorm.winter.WinterSwitches.OFF_MESSAGE);return;}
        Block block=player.getTargetBlockExact(16);
        if(block==null||!lampMaterial(block.getType())){lang.send(player,"lamp-not-glass");return;}
        try{
            String key=key(block);
            if(store.has(key)){store.remove(key);restore(block);lang.send(player,"lamp-removed","count",store.size());}
            else if(store.size()>=MAX_LAMPS)lang.send(player,"lamp-full","max",MAX_LAMPS);
            else{store.add(key);lang.send(player,"lamp-added","count",store.size());}
        }catch(RuntimeException failure){plugin.storageFailure(failure);lang.send(player,"faulted");}
    }
    private void restore(Block block){
        for(Player p:block.getWorld().getPlayers())if(p.getLocation().distanceSquared(block.getLocation())<16384)p.sendBlockChange(block.getLocation(),block.getBlockData());
    }
    private record Lamp(Location location,int x,int y,int z){}
    private List<Lamp> loaded(){
        List<Lamp> out=new ArrayList<>();
        for(String key:store.keys()){
            LampStore.Position p=LampStore.parse(key);
            if(p==null)continue;
            World world=Bukkit.getWorld(p.world());
            if(world==null||!world.isChunkLoaded(p.x()>>4,p.z()>>4))continue;
            if(!lampMaterial(world.getBlockAt(p.x(),p.y(),p.z()).getType()))continue;
            out.add(new Lamp(new Location(world,p.x(),p.y(),p.z()),p.x(),p.y(),p.z()));
        }
        return out;
    }
    public void tick(){
        boolean active=plugin.getConfig().getBoolean("lamps.enabled",true)&&plugin.season().intensity()>0;
        if(!active){if(shown){shown=false;loaded().forEach(l->restore(l.location().getBlock()));}return;}
        shown=true;step++;
        List<Lamp> lamps=loaded();
        if(lamps.isEmpty())return;
        double max=range()*range();
        BlockData[] colours=new BlockData[COLOURS.length];
        for(int i=0;i<COLOURS.length;i++)colours[i]=COLOURS[i].createBlockData();
        for(Player player:Bukkit.getOnlinePlayers()){
            int sent=0;
            Location at=player.getLocation();
            for(Lamp lamp:lamps){
                if(sent>=MAX_PER_PLAYER)break;
                if(lamp.location().getWorld()!=at.getWorld()||lamp.location().distanceSquared(at)>max)continue;
                player.sendBlockChange(lamp.location(),colours[colour(lamp.x(),lamp.y(),lamp.z(),step,colours.length)]);
                sent++;
            }
        }
    }
    /**
     * Block breaks go through Core's stages, so Core decides when they run among all Vystorm plugins. A broken lamp is
     * only forgotten: OBSERVE, the final outcome, never a cancelled event.
     */
    public void handleBlockEvents(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages){
        stages.handle(plugin,BlockBreakEvent.class,Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.OBSERVE,this::broken);
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void broken(BlockBreakEvent event){
        String key=key(event.getBlock());
        if(!store.has(key))return;
        try{store.remove(key);}catch(RuntimeException failure){plugin.storageFailure(failure);}
    }
}
