package at.vystorm.winter.world;

import at.vystorm.winter.WinterPlugin;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Main-thread player loop: winter cold outside, warmth near fire and indoors, shown as vanilla frost. */
public final class WinterCold implements AutoCloseable {
    static final double SECONDS_PER_TICK=0.5;
    private final WinterPlugin plugin;
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;
    private final Map<UUID,Double> levels=new HashMap<>();
    private final Map<UUID,Double> reserves=new HashMap<>();
    private final Set<UUID> warned=new HashSet<>();
    private int ticks;
    /** @param platform Core's platform part, {@code api.compat().platform()} */
    public WinterCold(WinterPlugin plugin, Vystorm.vystorm_core.compat.PlatformCompatInterface platform){this.plugin=plugin;this.platform=platform;}

    /** Runs every 10 ticks. */
    public void tick(){
        ColdModel.Settings s=plugin.cold();
        ticks++;
        for(Player player:Bukkit.getOnlinePlayers()){
            UUID id=player.getUniqueId();
            double level=levels.getOrDefault(id,0.0);
            World world=player.getWorld();
            boolean eligible=s.enabled()&&plugin.winterAt(player)&&!plugin.season().phase().equals("THAW")&&!player.isDead()&&!inPowderSnow(player)
                    &&(player.getGameMode()==GameMode.SURVIVAL||player.getGameMode()==GameMode.ADVENTURE)
                    &&!plugin.settings().excludedBiomes().contains(BiomeKeys.key(player.getLocation().getBlock().getBiome()));
            if(!eligible){
                release(player);
                continue;
            }
            ColdModel.Exposure exposure=exposure(player,s);
            double rate=ColdModel.perSecond(exposure,s,plugin.season().intensity(),ColdModel.night(world.getTime()),
                    plugin.snowingAt(player),player.isInWater(),ColdModel.protection(leather(player),handWarmer(player,s)));
            if(exposure==ColdModel.Exposure.OUTSIDE&&rate>0&&plugin.storms()!=null)rate*=plugin.storms().coldFactor(world);
            double stored=reserve(id);
            WarmthReserve.Step step=WarmthReserve.step(stored,level,exposure,rate,SECONDS_PER_TICK,plugin.reserve());
            if(step.reserve()>0)reserves.put(id,step.reserve());else reserves.remove(id);
            if(s.warnings()&&plugin.reserve().enabled()){
                if(stored<plugin.reserve().durationSeconds()&&step.reserve()>=plugin.reserve().durationSeconds())
                    plugin.lang().actionBar(player,"warmth-ready","seconds",(int)Math.ceil(step.reserve()));
                else if(stored>0&&step.reserve()==0)plugin.lang().actionBar(player,"warmth-empty");
            }
            double next=step.cold();
            if(next<=0){
                if(level>0)clearCold(player);
                continue;
            }
            levels.put(id,next);
            platform.lockFreezeTicks(player, true);
            platform.setFreezeTicks(player, ColdModel.freezeTicks(next,s));
            if(s.warnings()){
                if(next>=0.5&&warned.add(id))plugin.lang().actionBar(player,"cold-warning");
                else if(exposure==ColdModel.Exposure.WARM&&level>=0.3&&ticks%6==0)plugin.lang().actionBar(player,"cold-warming");
            }
            // Visible breath every two seconds once noticeably cold.
            if(s.breath()&&next>=0.15&&Math.floorMod(ticks+id.hashCode(),4)==0){
                Location mouth=player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.35)).add(0,-0.12,0);
                world.spawnParticle(Particle.CLOUD,mouth,2,0.03,0.02,0.03,0.006);
            }
        }
    }

    private ColdModel.Exposure exposure(Player player,ColdModel.Settings s){
        Location at=player.getLocation();World world=player.getWorld();
        int r=s.warmRadius(),x=at.getBlockX(),y=at.getBlockY(),z=at.getBlockZ();
        for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){
            if(!world.isChunkLoaded((x+dx)>>4,(z+dz)>>4))continue;
            for(int dy=-2;dy<=2;dy++){
                int by=y+dy;if(by<world.getMinHeight()||by>=world.getMaxHeight())continue;
                Block block=world.getBlockAt(x+dx,by,z+dz);
                Material type=block.getType();
                if(type.isAir()||type==Material.STONE||type==Material.DIRT||type==Material.GRASS_BLOCK)continue;
                boolean lit=block.getBlockData() instanceof Lightable light&&light.isLit();
                if(ColdModel.warms(type,lit))return ColdModel.Exposure.WARM;
            }
        }
        // Roofed or deep underground: the head position no longer sees much sky.
        return player.getEyeLocation().getBlock().getLightFromSky()<12?ColdModel.Exposure.SHELTERED:ColdModel.Exposure.OUTSIDE;
    }

    /** Vanilla handles powder snow freezing itself; stay out of its way there. */
    private static boolean inPowderSnow(Player player){
        return player.getLocation().getBlock().getType()==Material.POWDER_SNOW||player.getEyeLocation().getBlock().getType()==Material.POWDER_SNOW;
    }

    static int leather(Player player){
        int count=0;
        for(ItemStack item:player.getInventory().getArmorContents()){
            if(item==null)continue;
            switch(item.getType()){
                case LEATHER_HELMET,LEATHER_CHESTPLATE,LEATHER_LEGGINGS,LEATHER_BOOTS->count++;
                default->{}
            }
        }
        return count;
    }

    /** A lantern (cold.hand-warmers) in either hand blocks as much cold as one leather piece. */
    static boolean handWarmer(Player player,ColdModel.Settings s){
        if(s.handWarmers().isEmpty())return false;
        return s.handWarmers().contains(player.getInventory().getItemInMainHand().getType())
                ||s.handWarmers().contains(player.getInventory().getItemInOffHand().getType());
    }

    public double reserve(UUID player){return reserves.getOrDefault(player,0.0);}

    /** Equipment-only inspection for the personal warmth command; does not scan terrain or change cold. */
    public int protection(Player player){return ColdModel.protection(leather(player),handWarmer(player,plugin.cold()));}

    public double level(UUID player){return levels.getOrDefault(player,0.0);}

    /** A hot stew or soup takes away part of the cold; the next loop tick updates the frost overlay. */
    public void ate(Player player,Material food){
        ColdModel.Settings s=plugin.cold();
        UUID id=player.getUniqueId();
        Double level=levels.get(id);
        if(level==null||!s.enabled()||s.warmFood()<=0||!ColdModel.warmFood(food))return;
        double next=ColdModel.afterFood(level,food,s);
        if(next<=0)clearCold(player);
        else{levels.put(id,next);platform.setFreezeTicks(player, ColdModel.freezeTicks(next,s));if(next<0.5)warned.remove(id);}
        if(s.warnings())plugin.lang().actionBar(player,"cold-food");
    }

    /** A snowball hit adds a little cold; the next loop tick checks eligibility and updates the frost overlay. */
    public void chill(Player player,double amount){
        ColdModel.Settings s=plugin.cold();
        if(!s.enabled()||amount<=0||!plugin.winterAt(player)||plugin.season().phase().equals("THAW")||inPowderSnow(player)
                ||(player.getGameMode()!=GameMode.SURVIVAL&&player.getGameMode()!=GameMode.ADVENTURE))return;
        UUID id=player.getUniqueId();
        // Leather keeps the snow out just like it blocks the cold.
        double gain=amount*Math.max(0,1-0.25*leather(player));
        if(gain>0)levels.put(id,ColdModel.step(levels.getOrDefault(id,0.0),gain,1));
    }

    /** Hands freeze ticks back to vanilla. */
    public void release(Player player){reserves.remove(player.getUniqueId());clearCold(player);}

    private void clearCold(Player player){
        UUID id=player.getUniqueId();
        if(levels.remove(id)!=null){platform.setFreezeTicks(player, 0);platform.lockFreezeTicks(player, false);}
        warned.remove(id);
    }

    @Override public void close(){
        for(Player player:Bukkit.getOnlinePlayers())release(player);
        levels.clear();warned.clear();reserves.clear();
    }
}
