package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import Vystorm.vystorm_core.worldevent.*;
import Vystorm.vystorm_core.worldevent.engine.*;
import java.time.*;
import java.util.*;
import org.bukkit.*;

/** Adds an owned Core event domain, never replaces the singleton WorldEventServices provider. */
public final class WinterEvents implements AutoCloseable {
    private final WinterPlugin plugin;
    private final WorldEvents.Domain domain;
    private final RuntimeContextHub context;
    public WinterEvents(WinterPlugin plugin,API core){
        this.plugin=plugin;
        context=core.runtime().context();
        domain=core.events().domain(plugin,"winter-is-coming");
        for(String id:List.of("winter_season","winter_advent","winter_christmas","winter_new_year")){
            domain.register(new EventDefinition(id,Set.of(EventScope.WORLD),EventType.MIXED,"season",0,
                    Set.of("winter",id),Duration.ZERO,Duration.ofDays(370),Duration.ofDays(370),Set.of(),Set.of(),Set.of(id),0,0,128));
        }
        domain.source(new WorldEventSource(){
            public List<WorldEventView> active(){return domain.runtime().activeEvents().stream().map(WorldEvents::view).toList();}
            public List<WorldEventView> activeAt(UUID world,int x,int y,int z){return active().stream().filter(e->e.scopeKey().equals(world.toString())).toList();}
            public double modifier(String key,UUID worldId,int x,int y,int z){
                World world=Bukkit.getWorld(worldId);var s=plugin.settings();
                if(world==null||!plugin.controls(world)||!s.spawns()||!plugin.terrain().allowed(new Location(world,x,y,z)))return 1;
                double target=switch(key){case "spawn.skeleton.weight"->s.skeleton();case "spawn.spider.weight"->s.spider();case "spawn.creeper.weight"->s.creeper();default->1;};
                return 1+(target-1)*plugin.season().intensity();
            }
        });
        context.register(plugin,new RuntimeContextHub.Provider(){
            public String id(){return "winter-is-coming";}
            public Set<RuntimeContextHub.Channel> channels(){return Set.of(RuntimeContextHub.Channel.WORLD_EVENT);}
            public RuntimeContextHub.Snapshot snapshot(RuntimeContextHub.Channel channel,RuntimeContextHub.Query q){
                if(!plugin.activeWorldIds().contains(q.worldId()))return null;
                var season=plugin.season();
                return new RuntimeContextHub.Snapshot(id(),channel,0,plugin.revision(),Set.of("WINTER", "WINTER:"+season.phase(),"HOLIDAY:"+plugin.holiday()),
                        Map.of("winter.phase",season.phase(),"winter.holiday",plugin.holiday()),Map.of("winter.intensity",season.intensity()));
            }
        });
    }
    public void tick(){
        Set<String> desired=new HashSet<>();
        for(World world:Bukkit.getWorlds())if(plugin.settings().includes(world)){
            if(plugin.controls(world))desired.add("winter_season/"+world.getUID());
            String holiday=plugin.holiday();if(!holiday.equals("NONE"))desired.add("winter_"+holiday.toLowerCase(Locale.ROOT)+"/"+world.getUID());
        }
        Set<String> existing=new HashSet<>();
        for(var event:List.copyOf(domain.runtime().activeEvents())){
            String key=event.definition().id()+"/"+event.scopeKey();
            if(!desired.contains(key))domain.stop(event.instanceId());else existing.add(key);
        }
        for(String key:desired)if(!existing.contains(key)){
            String[] parts=key.split("/");domain.start(parts[0],EventScope.WORLD,parts[1]);
        }
    }
    @Override public void close(){context.unregisterOwner(plugin);domain.close();}
}
