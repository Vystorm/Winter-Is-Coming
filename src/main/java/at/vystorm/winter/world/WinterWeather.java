package at.vystorm.winter.world;

import Vystorm.vystorm_core.packet.ClientBiomesInterface;
import Vystorm.vystorm_core.visual.BlockPosition;
import at.vystorm.winter.WinterPlugin;
import java.util.*;
import java.util.stream.Collectors;
import org.bukkit.*;
import org.bukkit.entity.Player;

/**
 * Weather ownership is released on reload, thaw completion and disable.
 * <p>
 * Without season spots the plugin sets the world's weather and shows every player the whole world as a snowy biome.
 * While spots limit a world (1.24.1) the world's own weather is left alone: players inside a spot get the snowfall as
 * their personal weather and see only the spot cells as snowy biome; players outside see the normal biome and weather.
 */
public final class WinterWeather implements AutoCloseable {
    private record Previous(boolean storm,boolean thunder,int weather,int thunderDuration,int clear){ }
    private static final NamespacedKey SNOWY = NamespacedKey.minecraft("snowy_plains");
    private final WinterPlugin plugin;
    private final ClientBiomesInterface biomes;
    private final Map<UUID,Previous> saved=new HashMap<>();
    private final Map<UUID,Long> next=new HashMap<>();
    /** The plugin's snowfall decision per world, also while spots limit the world and its real weather is untouched. */
    private final Map<UUID,Boolean> snowing=new HashMap<>();
    /** Players with a personal weather or a spot projection set by this plugin. */
    private final Set<UUID> personal=new HashSet<>(), projected=new HashSet<>();
    private boolean applying;
    private boolean fallbackLogged;
    /** @param biomes Core's client biome part, {@code api.packages().clientBiomes()} */
    public WinterWeather(WinterPlugin plugin, ClientBiomesInterface biomes){this.plugin=plugin;this.biomes=biomes;}
    public boolean applying(){return applying;}
    public boolean owns(World world){return saved.containsKey(world.getUID());}

    /** The personal weather a player gets: snowfall inside a spot while the plugin lets it snow there, otherwise none (the world weather). */
    public static WeatherType personalWeather(boolean limited, boolean inside, boolean snowing) {
        return limited && inside && snowing ? WeatherType.DOWNFALL : null;
    }

    /** True while the plugin lets it snow where this player is (used for the cold). */
    public boolean snowingFor(Player player){
        World world=player.getWorld();
        if(plugin.zones()==null||!plugin.zones().limiting(world.getUID()))return world.hasStorm();
        return plugin.winterAt(player)&&snowing.getOrDefault(world.getUID(),false);
    }

    private boolean limited(World world){return plugin.zones()!=null&&plugin.zones().limiting(world.getUID());}

    public void tick(){
        long now=System.currentTimeMillis();var s=plugin.settings();
        Set<UUID> particleFallback=new HashSet<>();
        Set<String> excludedBiomes=s.excludedBiomes().stream()
                .map(key->key.contains(":")?key.toLowerCase(Locale.ROOT):key.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        for(World world:Bukkit.getWorlds()){
            UUID id=world.getUID();
            if(!s.weather()||!plugin.controls(world)){restore(world);snowing.remove(id);next.remove(id);continue;}
            boolean forced=plugin.storms()!=null&&plugin.storms().active(world).isPresent();
            boolean limited=limited(world);
            if(limited)restore(world);   // the world keeps its own weather; spot players get theirs below
            else saved.computeIfAbsent(id,unused->new Previous(world.hasStorm(),world.isThundering(),world.getWeatherDuration(),world.getThunderDuration(),world.getClearWeatherDuration()));
            boolean current=snowing.getOrDefault(id,false);
            if(now>=next.getOrDefault(id,0L)||(forced&&!current)){
                current=forced||Math.random()<s.snowChance()*plugin.season().intensity();
                snowing.put(id,current);
                next.put(id,now+s.weatherSeconds()*1000L);
                if(!limited){applying=true;try{world.setStorm(current);world.setThundering(false);world.setWeatherDuration(s.weatherSeconds()*20+40);world.setClearWeatherDuration(current?0:s.weatherSeconds()*20+40);}finally{applying=false;}}
            }
        }
        for(Player player:Bukkit.getOnlinePlayers()){
            World world=player.getWorld();
            UUID pid=player.getUniqueId();
            boolean active=s.weather()&&plugin.controls(world);
            boolean limited=active&&limited(world);
            WeatherType personalWeather=active?personalWeather(limited,plugin.winterAt(player),snowing.getOrDefault(world.getUID(),false)):null;
            if(personalWeather!=null){
                if(player.getPlayerWeather()!=personalWeather)player.setPlayerWeather(personalWeather);
                personal.add(pid);
            }else if(personal.remove(pid))player.resetPlayerWeather();
            if(!active){biomes.clear(plugin,player);unproject(player);continue;}
            if(limited){
                // No whole-world snow view: only the spot cells near the player turn snowy.
                biomes.clear(plugin,player);
                if(!s.packetSnow()){unproject(player);if(plugin.winterAt(player))particleFallback.add(pid);continue;}
                Location at=player.getLocation();
                int ground=world.isChunkLoaded(at.getBlockX()>>4,at.getBlockZ()>>4)?world.getHighestBlockYAt(at.getBlockX(),at.getBlockZ()):at.getBlockY();
                List<SnowZones.Cell> cells=plugin.zones().projection(world.getUID(),at.getX(),at.getZ(),Math.min(ground,at.getBlockY()),world.getMinHeight(),world.getMaxHeight());
                if(cells.isEmpty()){unproject(player);continue;}
                Map<BlockPosition,NamespacedKey> overlay=new HashMap<>();
                for(SnowZones.Cell c:cells)overlay.put(new BlockPosition(c.x(),c.y(),c.z()),SNOWY);
                if(biomes.project(plugin,player,overlay))projected.add(pid);
                else if(plugin.winterAt(player))particleFallback.add(pid);
                continue;
            }
            unproject(player);
            if(s.packetSnow()){
                if(!biomes.snow(plugin,player,excludedBiomes)){
                    particleFallback.add(pid);
                    if(!fallbackLogged){
                        plugin.getLogger().info("Client biome snow unavailable; using snow particles when weather.warm-biome-particles is enabled.");
                        fallbackLogged=true;
                    }
                }
            }else{
                biomes.clear(plugin,player);
                particleFallback.add(pid);
            }
        }
        if(!s.particles())return;
        // Use particles only when the optional client biome view is unavailable or disabled.
        int count=0;
        for(Player player:Bukkit.getOnlinePlayers()){
            if(!particleFallback.contains(player.getUniqueId())||!snowingFor(player))continue;
            if(count++>=64)break;
            World world=player.getWorld();
            Location p=player.getLocation();int x=p.getBlockX(),z=p.getBlockZ();
            if(world.isChunkLoaded(x>>4,z>>4)&&!s.excludedBiomes().contains(BiomeKeys.key(p.getBlock().getBiome()))&&world.getHighestBlockYAt(x,z)<=p.getY()+1)
                player.spawnParticle(Particle.SNOWFLAKE,p.clone().add(0,5,0),24,5,3,5,0.025);
        }
    }
    private void unproject(Player player){if(projected.remove(player.getUniqueId()))biomes.clearProjection(plugin,player);}
    private void restore(World world){
        Previous old=saved.remove(world.getUID());if(old==null)return;
        next.remove(world.getUID());snowing.remove(world.getUID());
        applying=true;try{world.setStorm(old.storm());world.setThundering(old.thunder());world.setWeatherDuration(old.weather());world.setThunderDuration(old.thunderDuration());world.setClearWeatherDuration(old.clear());}finally{applying=false;}
    }
    @Override public void close(){
        for(UUID id:List.copyOf(saved.keySet())){World world=Bukkit.getWorld(id);if(world!=null)restore(world);}
        for(UUID id:personal){Player player=Bukkit.getPlayer(id);if(player!=null)player.resetPlayerWeather();}
        personal.clear();projected.clear();saved.clear();next.clear();snowing.clear();biomes.release(plugin);
    }
}
