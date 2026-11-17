package at.vystorm.winter.world;

import org.bukkit.configuration.file.FileConfiguration;

/** Stored fire warmth, measured in seconds of protection from ambient winter cold. */
public final class WarmthReserve {
    public record Settings(boolean enabled,double chargeSeconds,double durationSeconds) {
        public static final Settings DEFAULTS=new Settings(true,20,60);
        public static Settings read(FileConfiguration c){
            return new Settings(c.getBoolean("cold.reserve.enabled",true),
                    number(c,"cold.reserve.charge-seconds",20,1,600),
                    number(c,"cold.reserve.duration-seconds",60,1,600));
        }
        private static double number(FileConfiguration c,String key,double fallback,double min,double max){
            double value=c.getDouble(key,fallback);
            if(!Double.isFinite(value)||value<min||value>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);
            return value;
        }
    }
    public record Step(double reserve,double cold) {}
    private WarmthReserve() {}

    public static Step step(double reserve,double cold,ColdModel.Exposure exposure,double rate,double seconds,Settings s){
        if(!s.enabled())return new Step(0,ColdModel.step(cold,rate,seconds));
        reserve=Math.clamp(reserve,0,s.durationSeconds());
        if(exposure==ColdModel.Exposure.WARM){
            // Finish warming the player before charging; only the remaining time stores warmth.
            double charging=rate<0?Math.max(0,seconds-cold/-rate):0;
            return new Step(Math.min(s.durationSeconds(),reserve+charging*s.durationSeconds()/s.chargeSeconds()),
                    ColdModel.step(cold,rate,seconds));
        }
        double exposed=rate>0?Math.max(0,seconds-reserve):seconds;
        return new Step(Math.max(0,reserve-seconds),ColdModel.step(cold,rate,exposed));
    }
}
