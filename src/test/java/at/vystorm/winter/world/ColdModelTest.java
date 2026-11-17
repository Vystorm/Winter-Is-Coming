package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;
import at.vystorm.winter.world.ColdModel.Exposure;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ColdModelTest {
    private static final ColdModel.Settings S=ColdModel.Settings.DEFAULTS;

    @Test void outsideAtFullWinterFreezesInConfiguredMinutes() {
        double rate=ColdModel.perSecond(Exposure.OUTSIDE,S,1,false,false,false,0);
        assertEquals(1/180.0,rate,1e-12);
        double level=0;
        for(int i=0;i<360;i++)level=ColdModel.step(level,rate,0.5);
        assertEquals(1,level,1e-9);
    }

    @Test void nightStormAndWaterMakeItColderLeatherProtects() {
        double day=ColdModel.perSecond(Exposure.OUTSIDE,S,1,false,false,false,0);
        assertEquals(day*1.5*1.3*2,ColdModel.perSecond(Exposure.OUTSIDE,S,1,true,true,true,0),1e-12);
        assertEquals(day*0.5,ColdModel.perSecond(Exposure.OUTSIDE,S,1,false,false,false,2),1e-12);
        // Full leather: immune, the player slowly recovers instead.
        assertTrue(ColdModel.perSecond(Exposure.OUTSIDE,S,1,true,true,true,4)<0);
        // No winter intensity: no cold.
        assertTrue(ColdModel.perSecond(Exposure.OUTSIDE,S,0,true,false,false,0)<0);
    }

    @Test void warmthRecoversFasterThanShelter() {
        double warm=ColdModel.perSecond(Exposure.WARM,S,1,true,true,true,0);
        double shelter=ColdModel.perSecond(Exposure.SHELTERED,S,1,true,true,true,0);
        assertTrue(warm<shelter&&shelter<0);
        assertEquals(0,ColdModel.step(0.05,warm,1));
    }

    @Test void freezeTicksStayBelowDamageUnlessEnabled() {
        assertEquals(0,ColdModel.freezeTicks(0,S));
        assertEquals(56,ColdModel.freezeTicks(1,S));
        var full=new ColdModel.Settings(true,1,false,4,3,true,true,0.5);
        assertEquals(ColdModel.FULLY_FROZEN-1,ColdModel.freezeTicks(1,full));
        var damage=new ColdModel.Settings(true,0.4,true,4,3,true,true,0.5);
        assertEquals(ColdModel.FULLY_FROZEN,ColdModel.freezeTicks(1,damage));
    }

    @Test void heatSourcesCountOnlyWhileLit() {
        assertTrue(ColdModel.warms(Material.CAMPFIRE,true));
        assertFalse(ColdModel.warms(Material.CAMPFIRE,false));
        assertTrue(ColdModel.warms(Material.LAVA,false));
        assertTrue(ColdModel.warms(Material.BLAST_FURNACE,true));
        assertFalse(ColdModel.warms(Material.TORCH,true));
    }

    @Test void hotStewsAndSoupsTakeAwayColdOtherFoodDoesNot() {
        assertEquals(0.3,ColdModel.afterFood(0.8,Material.MUSHROOM_STEW,S),1e-12);
        assertEquals(0,ColdModel.afterFood(0.2,Material.RABBIT_STEW,S));
        assertTrue(ColdModel.warmFood(Material.BEETROOT_SOUP)&&ColdModel.warmFood(Material.SUSPICIOUS_STEW));
        assertEquals(0.8,ColdModel.afterFood(0.8,Material.BREAD,S));
        var off=new ColdModel.Settings(true,0.4,false,4,3,true,true,0);
        assertEquals(0.8,ColdModel.afterFood(0.8,Material.MUSHROOM_STEW,off));
    }

    @Test void aHandWarmerCountsAsOneLeatherPiece() {
        assertEquals(1,ColdModel.protection(0,true));
        assertEquals(0,ColdModel.protection(0,false));
        assertEquals(4,ColdModel.protection(4,true));
        double bare=ColdModel.perSecond(Exposure.OUTSIDE,S,1,false,false,false,ColdModel.protection(0,false));
        assertEquals(bare*0.75,ColdModel.perSecond(Exposure.OUTSIDE,S,1,false,false,false,ColdModel.protection(0,true)),1e-12);
    }

    @Test void handWarmersAreReadFromTheConfig() {
        assertEquals(java.util.Set.of(Material.LANTERN,Material.SOUL_LANTERN),ColdModel.Settings.read(new org.bukkit.configuration.file.YamlConfiguration()).handWarmers());
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        config.set("cold.hand-warmers",java.util.List.of("torch","minecraft:lantern"));
        assertEquals(java.util.Set.of(Material.TORCH,Material.LANTERN),ColdModel.Settings.read(config).handWarmers());
        config.set("cold.hand-warmers",java.util.List.of());
        assertTrue(ColdModel.Settings.read(config).handWarmers().isEmpty());
        config.set("cold.hand-warmers",java.util.List.of("heater"));
        assertThrows(IllegalArgumentException.class,()->ColdModel.Settings.read(config));
    }

    @Test void nightFollowsWorldTime() {
        assertFalse(ColdModel.night(6000));
        assertTrue(ColdModel.night(18000));
        assertTrue(ColdModel.night(24000L*5+13500));
        assertFalse(ColdModel.night(23500));
    }

    @Test void settingsReadDefaultsAndRejectOutOfRange() {
        assertEquals(S,ColdModel.Settings.read(new YamlConfiguration()));
        var config=new YamlConfiguration();
        config.set("cold.warm-radius",20);
        assertThrows(IllegalArgumentException.class,()->ColdModel.Settings.read(config));
        var nan=new YamlConfiguration();
        nan.set("cold.max-frost",Double.NaN);
        assertThrows(IllegalArgumentException.class,()->ColdModel.Settings.read(nan));
    }
}
