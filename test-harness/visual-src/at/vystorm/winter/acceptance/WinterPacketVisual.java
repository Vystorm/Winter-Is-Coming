package at.vystorm.winter.acceptance;
import at.vystorm.winter.WinterPlugin;
import Vystorm.vystorm_core.region.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
/** Isolated normal-world test: actual Winter weather integration and native-client handshake. */
public final class WinterPacketVisual extends JavaPlugin implements Listener {
    private World world; private WinterPlugin winter; private Path output; private int stage, ticks; private String baseline;
    private final String[] names={"rain","snow","reconnected","excluded","resnow","restored"};
    @Override public void onEnable(){ Bukkit.getScheduler().runTaskLater(this,()->{try{start();}catch(Exception e){fail(e);}},40); }
    private void start() throws Exception {
        world=Bukkit.getWorld("world"); winter=(WinterPlugin)Bukkit.getPluginManager().getPlugin("WinterIsComing");
        output=getDataFolder().toPath().resolve("snapshots");Files.createDirectories(output);
        var c=winter.getConfig();c.set("worlds",List.of("world"));c.set("weather.enabled",true);c.set("weather.packet-snow",true);
        c.set("weather.snow-chance-at-peak",1.0);c.set("weather.warm-biome-particles",false);c.set("weather.interval-seconds",10);
        c.set("spawns.enabled",false);c.set("holidays.fireworks",false);c.set("performance.period-ticks",1200);
        winter.saveConfig();command("reload");command("setdate 2026-11-01");
        RegionService.get().register(this,Region.of("packet-test","unchanged",new RegionShape.Box(world.getUID(),-30000000,-64,-30000000,30000000,320,30000000)).withPriority(100).withFlags(Map.of("winter",false)));
        world.setGameRule(GameRule.RANDOM_TICK_SPEED,0);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);
        world.setTime(6000);world.setClearWeatherDuration(0);world.setStorm(true);world.setWeatherDuration(1000000);
        world.getChunkAt(-3,2);baseline=digest();
        Bukkit.getPluginManager().registerEvents(this,this);
        Files.writeString(output.resolve("ready"),"normal world; particles off; server biomes never edited");
        Bukkit.getScheduler().runTaskTimer(this,()->{try{tick();}catch(Exception e){fail(e);}},1,1);
    }
    @EventHandler public void join(PlayerJoinEvent e){Bukkit.getScheduler().runTaskLater(this,()->{e.getPlayer().setGameMode(GameMode.SPECTATOR);e.getPlayer().teleport(new Location(world,-44,106,32,-90,35));},10);}
    private void tick() throws Exception {
        if(world.getPlayers().isEmpty())return;
        if(++ticks==100)Files.writeString(output.resolve(names[stage]+".ready"),"ready");
        if(!Files.exists(output.resolve(names[stage]+".captured")))return;
        if(!baseline.equals(digest()))throw new IllegalStateException("Server biomes changed");
        stage++;ticks=0;
        if(stage==6){Files.writeString(output.resolve("result.json"),"{\"passed\":true,\"serverBiomesUnchanged\":true,\"stages\":6}");Bukkit.shutdown();return;}
        if(stage==1)command("setdate 2026-12-24");
        if(stage==3){winter.getConfig().set("snow.excluded-biomes",java.util.stream.StreamSupport.stream(org.bukkit.Registry.BIOME.spliterator(),false).map(b->b.getKey().toString()).toList());winter.saveConfig();command("reload");}
        if(stage==4){winter.getConfig().set("snow.excluded-biomes",List.of());winter.saveConfig();command("reload");}
        if(stage==5)command("setdate 2027-03-02");
        world.setClearWeatherDuration(0);world.setStorm(true);world.setWeatherDuration(1000000);
    }
    private String digest(){StringBuilder s=new StringBuilder();for(int x=-48;x<-32;x+=4)for(int z=32;z<48;z+=4)for(int y=world.getMinHeight();y<world.getMaxHeight();y+=4)s.append(world.getBiome(x,y,z).getKey());return s.toString();}
    private void command(String s){if(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"winter "+s))throw new IllegalStateException(s);}
    private void fail(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Packet visual failed",e);Bukkit.shutdown();}
}
