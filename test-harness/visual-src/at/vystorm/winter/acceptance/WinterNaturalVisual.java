package at.vystorm.winter.acceptance;

import Vystorm.vystorm_core.region.*;
import at.vystorm.winter.WinterPlugin;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only normal-world capture. Does not construct terrain or change biomes. */
public final class WinterNaturalVisual extends JavaPlugin {
    private WinterPlugin winter;
    private World world;
    private Path output;
    private int minX,maxX,minZ,maxZ,minY=60,maxY,stage,ticks,stable,waiting=-2;
    private Location camera;
    private String baseline,last;
    private boolean failed;
    private final String[] names={"before","peak","after"};
    private final String[] dates={"2026-11-01","2026-12-24","2027-03-02"};
    @Override public void onEnable() {
        if (!Boolean.getBoolean("winter.visual.natural")) {getServer().getPluginManager().disablePlugin(this);return;}
        getServer().getScheduler().runTaskLater(this,()->{try{start();}catch(Throwable e){fail(e);}},40L);
    }
    private void start() throws Exception {
        winter=(WinterPlugin)Bukkit.getPluginManager().getPlugin("WinterIsComing"); world=Bukkit.getWorld("world");
        if(world==null||winter==null)throw new IllegalStateException("Missing isolated world/plugin");
        output=getDataFolder().toPath().resolve("snapshots");Files.createDirectories(output);
        var c=winter.getConfig();c.set("performance.columns-per-cycle",256);c.set("performance.changes-per-cycle",32);c.set("performance.period-ticks",1200);
        c.set("weather.enabled",false);c.set("spawns.enabled",false);c.set("holidays.fireworks",false);winter.saveConfig();command("reload");command("setdate "+dates[0]);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED,0);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.MOB_GRIEFING,false);world.setTime(6000);world.setStorm(false);
        int sx=world.getSpawnLocation().getBlockX(),sz=world.getSpawnLocation().getBlockZ();
        int best=-1,bx=sx,bz=sz,dx=0,dz=1;
        // Search only naturally generated terrain; pick a sea-level coast with visible soil.
        for(int x=sx-96;x<=sx+96;x+=8)for(int z=sz-96;z<=sz+96;z+=8){
            Block b=world.getHighestBlockAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if(b.getType()!=Material.WATER||b.getY()!=62)continue;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
                Block land=world.getHighestBlockAt(x+d[0]*16,z+d[1]*16,HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if(land.getY()<63||land.getY()>80||!Set.of(Material.GRASS_BLOCK,Material.SAND,Material.DIRT,Material.SHORT_GRASS).contains(land.getType()))continue;
                int score=0;
                for(int a=-24;a<=24;a+=8)for(int b2=-24;b2<=24;b2+=8){
                    Block t=world.getHighestBlockAt(x+a,z+b2,HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    if(t.getType()==Material.GRASS_BLOCK||t.getType()==Material.SHORT_GRASS)score+=2;
                    if(t.getType()==Material.WATER)score++;
                }
                if(score>best){best=score;bx=x;bz=z;dx=-d[0];dz=-d[1];}
            }
        }
        if(best<0)throw new IllegalStateException("No suitable natural shore in spawn survey");
        minX=Math.floorDiv(bx-48,16)*16;maxX=minX+95;minZ=Math.floorDiv(bz-48,16)*16;maxZ=minZ+95;
        int terrainTop=82;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)terrainTop=Math.max(terrainTop,world.getHighestBlockYAt(x,z));
        maxY=Math.min(world.getMaxHeight()-1,terrainTop+at.vystorm.winter.world.SnowDepth.MAX_BLOCKS);
        float yaw=dx==1?90:dx==-1?-90:dz==1?180:0;
        camera=new Location(world,bx+dx*36,Math.max(94,terrainTop+11),bz+dz*36,yaw,35);
        RegionService.get().register(this,Region.of("winter-natural","outside",new RegionShape.Box(world.getUID(),-30000000,-64,-30000000,30000000,320,30000000)).withPriority(0).withFlags(Map.of("winter",false)));
        RegionService.get().register(this,Region.of("winter-natural","view",new RegionShape.Box(world.getUID(),minX,-64,minZ,maxX,320,maxZ)).withPriority(10).withFlags(Map.of("winter",true)));
        for(int x=minX>>4;x<=maxX>>4;x++)for(int z=minZ>>4;z<=maxZ>>4;z++){var chunk=world.getChunkAt(x,z);chunk.addPluginChunkTicket(this);winter.terrain().load(chunk);}
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener(){
            @org.bukkit.event.EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent e){getServer().getScheduler().runTaskLater(WinterNaturalVisual.this,()->{if(e.getPlayer().isOnline()){e.getPlayer().setGameMode(GameMode.SPECTATOR);e.getPlayer().teleport(camera);}},10L);}
        },this);
        // Baseline is taken only after a player activates the normal-world scheduled ticks.
        getLogger().info("NATURAL_READY seed="+world.getSeed()+" shore="+bx+","+bz+" camera="+camera+" bounds="+minX+","+maxX+","+minZ+","+maxZ+" maxY="+maxY);
        getServer().getScheduler().runTaskTimer(this,()->{try{tick();}catch(Throwable e){fail(e);}},1L,1L);
    }
    private void tick() throws Exception {
        if(failed)return;
        if(waiting==-2){
            if(world.getPlayers().isEmpty())return;
            ticks++;
            if(ticks%20!=0)return;
            String current=digest();stable=current.equals(last)?stable+20:0;last=current;
            if(ticks>=600&&stable>=200){baseline=current;export();waiting=0;ticks=stable=0;}
            else if(ticks>=6000)throw new IllegalStateException("Natural world failed to settle before baseline");
            return;
        }
        if(waiting>=0){
            if(Files.exists(output.resolve(String.format("%02d-%s.json.captured",stage+1,names[stage])))){
                if(stage==2){finish();return;}stage++;if(stage==1)Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"tick rate "+Math.clamp(Integer.getInteger("winter.visual.tickRate",80),20,1000));waiting=-1;ticks=stable=0;last=null;command("setdate "+dates[stage]);
            }else if(++ticks>360000)throw new IllegalStateException("Capture timeout");
            return;
        }
        for(int i=0;i<256;i++)winter.terrain().cycle();
        ticks++;
        if(ticks%100!=0)return;
        String now=digest();stable=now.equals(last)?stable+100:0;last=now;
        if(ticks%1200==0)getLogger().info("NATURAL_PROGRESS stage="+names[stage]+" ticks="+ticks+" stable="+stable);
        if(ticks>=6000&&stable>=1200){
            int[] counts=counts();
            if(stage==1&&(counts[0]==0||counts[1]==0))throw new IllegalStateException("Expected natural snow and ice");
            if(stage==2&&(!baseline.equals(now)||counts[2]!=0))throw new IllegalStateException("Natural terrain did not restore exactly");
            export();waiting=stage;ticks=0;
        }else if(ticks>=360000)throw new IllegalStateException("Natural stage did not converge");
    }
    private String digest() throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++)sha.update((world.getBlockAt(x,y,z).getBlockData().getAsString()+"\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha.digest());
    }
    private int[] counts(){int snow=0,ice=0,owned=0;for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++){Block b=world.getBlockAt(x,y,z);if(b.getType()==Material.SNOW)snow++;if(b.getType()==Material.ICE)ice++;if(winter.terrain().owned(b))owned++;}return new int[]{snow,ice,owned};}
    private void export() throws Exception {
        StringBuilder blocks=new StringBuilder();int layers=0;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++){
            Block b=world.getBlockAt(x,y,z);if(b.getType().isAir())continue;
            if(b.getType()==Material.SNOW)layers+=((org.bukkit.block.data.type.Snow)b.getBlockData()).getLayers();
            if(!blocks.isEmpty())blocks.append(',');blocks.append("{\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z).append(",\"material\":\"").append(b.getType()).append("\",\"data\":\"").append(b.getBlockData().getAsString()).append("\"}");
        }
        int[] n=counts();
        String json="{\"stage\":\""+names[stage]+"\",\"date\":\""+dates[stage]+"\",\"seed\":"+world.getSeed()+",\"generator\":\"minecraft:normal\",\"bounds\":{\"minX\":"+minX+",\"maxX\":"+maxX+",\"minY\":"+minY+",\"maxY\":"+maxY+",\"minZ\":"+minZ+",\"maxZ\":"+maxZ+"},\"camera\":{\"x\":"+camera.getX()+",\"y\":"+camera.getY()+",\"z\":"+camera.getZ()+",\"yaw\":"+camera.getYaw()+",\"pitch\":"+camera.getPitch()+"},\"summary\":{\"snowBlocks\":"+n[0]+",\"iceBlocks\":"+n[1]+",\"ownedBlocks\":"+n[2]+",\"snowLayers\":"+layers+"},\"blockDigest\":\""+digest()+"\",\"blocks\":["+blocks+"]}";
        Path target=output.resolve(String.format("%02d-%s.json",stage+1,names[stage]));
        Path temporary=target.resolveSibling(target.getFileName()+".tmp");
        Files.writeString(temporary,json);
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        getLogger().info("NATURAL_STAGE "+names[stage]+" snow="+n[0]+" ice="+n[1]+" owned="+n[2]);
    }
    private void finish() throws Exception {Files.writeString(getDataFolder().toPath().resolve("result.json"),"{\"passed\":true,\"stagesCompleted\":3,\"allFinalBlockStatesRestored\":true}");getServer().getScheduler().cancelTasks(this);world.save();Bukkit.shutdown();}
    private void command(String s){Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"winter "+s);}
    private void fail(Throwable e){failed=true;getLogger().log(java.util.logging.Level.SEVERE,"NATURAL_FAILED",e);try{Files.createDirectories(getDataFolder().toPath());Files.writeString(getDataFolder().toPath().resolve("result.json"),"{\"passed\":false}");}catch(Exception ignored){}getServer().getScheduler().cancelTasks(this);Bukkit.shutdown();}
}
