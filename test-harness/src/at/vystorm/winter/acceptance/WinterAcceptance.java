package at.vystorm.winter.acceptance;

import at.vystorm.winter.WinterPlugin;
import Vystorm.vystorm_core.region.*;
import Vystorm.vystorm_core.worldevent.WorldEventServices;
import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Snow;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Destructive fixture builder, only enabled by explicit JVM test property in an isolated server. */
public final class WinterAcceptance extends JavaPlugin implements Listener {
    private WinterPlugin winter;
    private World world;
    private int cycles;
    private int stage;
    private int initialChunks;
    private boolean inCycle;
    private boolean forcedLoad;
    private long maxNanos;
    @Override public void onEnable(){
        if(!Boolean.getBoolean("winter.acceptance")){getServer().getPluginManager().disablePlugin(this);return;}
        winter=(WinterPlugin)getServer().getPluginManager().getPlugin("WinterIsComing");
        getServer().getScheduler().runTaskLater(this,()->{
            try{
                world=Bukkit.getWorld("world");
                winter.getConfig().set("snow.drift-strength",0.0);
                winter.getConfig().set("snow.transition-blocks",0);
                winter.getConfig().set("snow.max-layers",3); // legacy depth remains supported
                winter.getConfig().set("performance.period-ticks",1200);
                winter.getConfig().set("performance.columns-per-cycle",256);
                winter.getConfig().set("performance.changes-per-cycle",32);
                winter.getConfig().set("frost.ocean-radius",4);
                winter.getConfig().set("frost.inland-radius",4);
                winter.getConfig().set("weather.snow-chance-at-peak",1.0);
                winter.saveConfig();cmd("reload");
                getServer().getPluginManager().registerEvents(this,this);
                world.getChunkAt(0,0).addPluginChunkTicket(this);
                RegionService.get().register(this,Region.of("test","outside-fixture",new RegionShape.Box(world.getUID(),-30000000,-64,-30000000,30000000,320,30000000)).withPriority(0).withFlags(Map.of("winter",false)));
                RegionService.get().register(this,Region.of("test","fixture",new RegionShape.Box(world.getUID(),0,65,0,15,80,15)).withPriority(10).withFlags(Map.of("winter",true)));
                RegionService.get().register(this,Region.of("test","winter-protected",new RegionShape.Box(world.getUID(),3,65,3,3,80,3)).withPriority(20).withFlags(Map.of("winter",false)));
                stage=Files.exists(getDataFolder().toPath().resolve("restart"))?2:1;
                if(stage==1)prepare();else{
                    check(winter.date().toString().equals("2026-12-24"),"manual date persisted");
                    check(world.getBlockAt(8,70,8).getType()==Material.ICE,"ice persisted");
                    check(winter.terrain().owned(world.getBlockAt(8,70,8)),"journal restored ownership");
                    world.getBlockAt(5,71,5).setType(Material.DIAMOND_BLOCK,false);
                    cmd("thaw");
                }
                initialChunks=world.getLoadedChunks().length;
                getServer().getScheduler().runTaskTimer(this,()->{
                    try{
                        for(int i=0;i<8;i++){long start=System.nanoTime();inCycle=true;try{winter.terrain().cycle();}finally{inCycle=false;}maxNanos=Math.max(maxNanos,System.nanoTime()-start);}
                        cycles++;
                        boolean ready=stage==1?world.getBlockAt(5,71,5).getType()==Material.SNOW&&((Snow)world.getBlockAt(5,71,5).getBlockData()).getLayers()==3&&world.getBlockAt(8,70,8).getType()==Material.ICE
                                :world.getBlockAt(8,70,8).getType()==Material.WATER&&world.getBlockAt(7,71,7).getType()==Material.AIR;
                        if(cycles>=1200||(cycles>=180&&ready)){validate();getServer().getScheduler().cancelTasks(this);}
                    }catch(Throwable failure){fail(failure);}
                },1,1);
            }catch(Throwable failure){fail(failure);}
        },40);
    }
    @EventHandler public void loaded(org.bukkit.event.world.ChunkLoadEvent e){if(inCycle)forcedLoad=true;}
    private void prepare(){
        for(int x=0;x<16;x++)for(int z=0;z<16;z++){
            for(int y=68;y<=70;y++)world.getBlockAt(x,y,z).setType(z<8?Material.STONE:Material.WATER,false);
            for(int y=71;y<76;y++)world.getBlockAt(x,y,z).setType(Material.AIR,false);
            world.setBiome(x,70,z,Biome.FROZEN_OCEAN);
        }
        Snow natural=(Snow)Material.SNOW.createBlockData();natural.setLayers(5);world.getBlockAt(1,71,1).setBlockData(natural,false);
        world.getBlockAt(15,70,15).setType(Material.ICE,false);
        world.getBlockAt(4,73,4).setType(Material.GLASS,false); // roof, no snow underneath
        cmd("setdate 2026-12-24");winter.weather().tick();
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void protect(BlockFormEvent e){
        if(e.getBlock().getX()==2&&e.getBlock().getZ()==2)e.setCancelled(true);
    }
    private void validate()throws Exception{
        check(!forcedLoad,"no forced chunk loading during terrain cycle");
        check(world.getBlockAt(1,71,1).getType()==Material.SNOW&&((Snow)world.getBlockAt(1,71,1).getBlockData()).getLayers()==5,"natural snow preserved");
        check(world.getBlockAt(15,70,15).getType()==Material.ICE,"natural ice preserved");
        check(world.getBlockAt(2,71,2).getType()==Material.AIR,"foreign form cancellation respected");
        check(world.getBlockAt(3,71,3).getType()==Material.AIR,"Core region protected");
        check(world.getBlockAt(4,71,4).getType()==Material.AIR,"roof protects interior");
        if(stage==1){
            UUID recipient=UUID.fromString("00000000-0000-0000-0000-000000000123");
            check(winter.gifts().credit(recipient,"2026/CHRISTMAS",new org.bukkit.inventory.ItemStack(Material.COOKIE,8)),"Core gift credited");
            check(!winter.gifts().credit(recipient,"2026/CHRISTMAS",new org.bukkit.inventory.ItemStack(Material.COOKIE,8)),"duplicate gift refused");
            check(winter.gifts().pending(recipient)==1,"Core gift pending");
            check(world.getBlockAt(5,71,5).getType()==Material.SNOW,"snow accumulation");
            check(world.getBlockAt(8,70,8).getType()==Material.ICE,"shore froze");
            check(world.getBlockAt(8,70,14).getType()==Material.WATER,"offshore budget respected");
            BlockFadeEvent fade=new BlockFadeEvent(world.getBlockAt(8,70,8),world.getBlockAt(8,70,8).getState());Bukkit.getPluginManager().callEvent(fade);check(fade.isCancelled(),"owned ice does not melt naturally");
            double mod=WorldEventServices.modifier("spawn.skeleton.weight",new Location(world,8,71,8));check(Math.abs(mod-1.4)<0.001,"Core spawn modifier");
            check(WorldEventServices.modifier("spawn.skeleton.weight",new Location(world,3,71,3))==1,"protected spawn modifier neutral");
            var context=RuntimeContextHub.get().merged(RuntimeContextHub.Channel.WORLD_EVENT,RuntimeContextHub.Query.world(world.getUID(),8,71,8));
            check(context.tags().contains("WINTER"),"Core seasonal context");
            check(world.hasStorm(),"weather controlled");
            // Snow/ice manually cleared through the owned path produces no drops and stays relinquished.
            winter.terrain().clear(world.getBlockAt(6,71,6));
            check(world.getBlockAt(6,71,6).getType()==Material.AIR,"owned snow cleared");
            Files.createDirectories(getDataFolder().toPath());Files.writeString(getDataFolder().toPath().resolve("restart"),"ready");
        }else{
            check(world.getBlockAt(8,70,8).getType()==Material.WATER,"ice thaw restored water");
            UUID recipient=UUID.fromString("00000000-0000-0000-0000-000000000123");
            if(Boolean.getBoolean("winter.verifyGiftRestart")){check(winter.gifts().pending(recipient)==1,"Core gift persists after restart");
            check(!winter.gifts().credit(recipient,"2026/CHRISTMAS",new org.bukkit.inventory.ItemStack(Material.COOKIE,8)),"duplicate gift refused after restart");}
            check(world.getBlockAt(7,71,7).getType()==Material.AIR,"snow thaw restored air");
            check(world.getBlockAt(5,71,5).getType()==Material.DIAMOND_BLOCK,"foreign replacement preserved");
            check(world.getBlockAt(6,71,6).getType()==Material.AIR,"cleared snow stayed cleared");
            check(!winter.weather().owns(world),"weather ownership released");
            check(WorldEventServices.modifier("spawn.skeleton.weight",new Location(world,8,71,8))==1,"spawn modifier neutral during thaw");
            cmd("preview 2026-12-25");check(winter.state().data().thaw,"preview is read-only");
        }
        getLogger().info("WINTER_ACCEPTANCE_PASSED stage="+stage+" cycles="+cycles+" maxCycleMs="+(maxNanos/1e6));
        world.save();Bukkit.shutdown();
    }
    private void cmd(String command){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"winter "+command);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void fail(Throwable failure){getLogger().log(java.util.logging.Level.SEVERE,"WINTER_ACCEPTANCE_FAILED",failure);getServer().getScheduler().cancelTasks(this);Bukkit.shutdown();}
}
