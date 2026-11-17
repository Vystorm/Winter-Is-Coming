package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;
import static at.vystorm.winter.world.ColdModel.Exposure.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class WarmthReserveTest {
    private static final WarmthReserve.Settings S=WarmthReserve.Settings.DEFAULTS;

    @Test void fireFirstRemovesColdThenChargesAndCapsReserve(){
        var cold=WarmthReserve.step(0,1,WARM,-0.125,4,S);
        assertEquals(0,cold.reserve());
        assertEquals(0.5,cold.cold());
        var warm=WarmthReserve.step(0,0.5,WARM,-0.125,6,S);
        assertEquals(6,warm.reserve());
        assertEquals(0,warm.cold());
        assertEquals(60,WarmthReserve.step(6,0,WARM,-0.125,30,S).reserve());
    }

    @Test void aFullChargeProtectsForExactlyOneMinute(){
        double reserve=0,cold=0;
        for(int i=0;i<40;i++)reserve=WarmthReserve.step(reserve,0,WARM,-0.125,0.5,S).reserve();
        assertEquals(60,reserve);
        for(int i=0;i<120;i++){
            var next=WarmthReserve.step(reserve,cold,OUTSIDE,1/180.0,0.5,S);
            reserve=next.reserve();cold=next.cold();
        }
        assertEquals(0,reserve);
        assertEquals(0,cold);
        assertEquals(0.5/180,WarmthReserve.step(reserve,cold,OUTSIDE,1/180.0,0.5,S).cold(),1e-12);
    }

    @Test void fractionalExpiryOnlyExposesRemainderOfTick(){
        var next=WarmthReserve.step(0.2,0.4,OUTSIDE,0.1,0.5,S);
        assertEquals(0,next.reserve());
        assertEquals(0.43,next.cold(),1e-12);
    }

    @Test void shelterAndLeatherStillRecoverWhileReserveDrains(){
        for(var exposure:new ColdModel.Exposure[]{SHELTERED,OUTSIDE}){
            var next=WarmthReserve.step(10,0.5,exposure,-1/60.0,6,S);
            assertEquals(4,next.reserve());
            assertEquals(0.4,next.cold(),1e-12);
        }
    }

    @Test void disabledReserveDoesNotBlockColdOrCharge(){
        var off=new WarmthReserve.Settings(false,20,60);
        assertEquals(new WarmthReserve.Step(0,0.5),WarmthReserve.step(60,0,OUTSIDE,0.1,5,off));
        assertEquals(0,WarmthReserve.step(0,0,WARM,-0.125,20,off).reserve());
    }

    @Test void customTimesAndReducedCapacityAreHonoured(){
        var custom=new WarmthReserve.Settings(true,10,30);
        assertEquals(15,WarmthReserve.step(0,0,WARM,-0.125,5,custom).reserve());
        assertEquals(29,WarmthReserve.step(60,0,OUTSIDE,0.1,1,custom).reserve());
    }

    @Test void missingConfigUsesDefaultsAndExplicitSettingsSurvive(){
        var config=new YamlConfiguration();
        assertEquals(S,WarmthReserve.Settings.read(config));
        config.set("cold.reserve.enabled",false);
        config.set("cold.reserve.charge-seconds",15);
        config.set("cold.reserve.duration-seconds",90);
        assertEquals(new WarmthReserve.Settings(false,15,90),WarmthReserve.Settings.read(config));
    }

    @Test void invalidDurationsAreRejected(){
        for(String key:new String[]{"charge-seconds","duration-seconds"})
            for(double value:new double[]{0,-1,601,Double.NaN,Double.POSITIVE_INFINITY}){
                var config=new YamlConfiguration();config.set("cold.reserve."+key,value);
                assertThrows(IllegalArgumentException.class,()->WarmthReserve.Settings.read(config));
            }
    }
}
