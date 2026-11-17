package at.vystorm.winter.world;

import Vystorm.vystorm_core.packet.ClientBiomesInterface;
import at.vystorm.winter.WinterPlugin;
import java.util.*;
import java.util.stream.Collectors;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Weather ownership is released on reload, thaw completion and disable. */
public final class WinterWeather implements AutoCloseable {
    private record Previous(boolean storm,boolean thunder,int weather,int thunderDuration,int clear){ }
    private final WinterPlugin plugin;
    private final ClientBiomesInterface biomes;
    private final Map<UUID,Previous> saved=new HashMap<>();
    private final Map<UUID,Long> next=new HashMap<>();
    private boolean applying;
    private boolean fallbackLogged;
    /** @param biomes Core's client biome part, {@code api.packages().clientBiomes()} */
    public WinterWeather(WinterPlugin plugin, ClientBiomesInterface biomes){this.plugin=plugin;this.biomes=biomes;}
    public boolean applying(){return applying;}
    public boolean owns(World world){return saved.containsKey(world.getUID());}
    public void tick(){
        long now=System.currentTimeMillis();var s=plugin.settings();
        Set<UUID> particleFallback=new HashSet<>();
        Set<String> excludedBiomes=s.excludedBiomes().stream()
                .map(key->key.contains(":")?key.toLowerCase(Locale.ROOT):key.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        for(Player player:Bukkit.getOnlinePlayers()){
            World world=player.getWorld();
            if(s.weather()&&plugin.controls(world)){
                if(s.packetSnow()){
                    if(!biomes.snow(plugin,player,excludedBiomes)){
                        particleFallback.add(player.getUniqueId());
                        if(!fallbackLogged){
                            plugin.getLogger().info("Client biome snow unavailable; using snow particles when weather.warm-biome-particles is enabled.");
                            fallbackLogged=true;
                        }
                    }
                }else{
                    biomes.clear(plugin,player);
                    particleFallback.add(player.getUniqueId());
                }
            }else biomes.clear(plugin,player);
        }
        for(World world:Bukkit.getWorlds()){
            if(!s.weather()||!plugin.controls(world)){restore(world);continue;}
            saved.computeIfAbsent(world.getUID(),id->new Previous(world.hasStorm(),world.isThundering(),world.getWeatherDuration(),world.getThunderDuration(),world.getClearWeatherDuration()));
            boolean forced=plugin.storms()!=null&&plugin.storms().active(world).isPresent();
            if(now>=next.getOrDefault(world.getUID(),0L)||(forced&&!world.hasStorm())){
                boolean snow=forced||Math.random()<s.snowChance()*plugin.season().intensity();
                applying=true;try{world.setStorm(snow);world.setThundering(false);world.setWeatherDuration(s.weatherSeconds()*20+40);world.setClearWeatherDuration(snow?0:s.weatherSeconds()*20+40);}finally{applying=false;}
                next.put(world.getUID(),now+s.weatherSeconds()*1000L);
            }
            if(s.particles()&&world.hasStorm()){
                // Use particles only when the optional client biome view is unavailable or disabled.
                int count=0;for(var player:world.getPlayers()){
                    if(!particleFallback.contains(player.getUniqueId()))continue;
                    if(count++>=64)break;
                    Location p=player.getLocation();int x=p.getBlockX(),z=p.getBlockZ();
                    if(world.isChunkLoaded(x>>4,z>>4)&&!s.excludedBiomes().contains(BiomeKeys.key(p.getBlock().getBiome()))&&world.getHighestBlockYAt(x,z)<=p.getY()+1)
                        player.spawnParticle(Particle.SNOWFLAKE,p.clone().add(0,5,0),24,5,3,5,0.025);
                }
            }
        }
    }
    private void restore(World world){
        Previous old=saved.remove(world.getUID());next.remove(world.getUID());if(old==null)return;
        applying=true;try{world.setStorm(old.storm());world.setThundering(old.thunder());world.setWeatherDuration(old.weather());world.setThunderDuration(old.thunderDuration());world.setClearWeatherDuration(old.clear());}finally{applying=false;}
    }
    @Override public void close(){for(UUID id:List.copyOf(saved.keySet())){World world=Bukkit.getWorld(id);if(world!=null)restore(world);}saved.clear();next.clear();biomes.release(plugin);}
}
