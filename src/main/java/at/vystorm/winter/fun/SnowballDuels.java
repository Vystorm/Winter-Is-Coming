package at.vystorm.winter.fun;

import java.util.*;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Pure snowball fight rules: a per-pair hit cooldown against spam, challenges and timed duels. Times are epoch
 * milliseconds passed in by the caller, so everything here is testable without a server.
 */
public final class SnowballDuels {
    /** A challenge waits this long for an answer. */
    public static final long CHALLENGE_MILLIS=60_000;
    /** The same thrower can score on the same target at most once per second. */
    public static final long PAIR_COOLDOWN_MILLIS=1_000;
    public record Settings(boolean enabled,double chill,int duelHits,int duelSeconds,int duelSnowballs) {
        public static final Settings DEFAULTS=new Settings(true,0.05,10,120,16);
        public static Settings read(FileConfiguration c) {
            double chill=c.getDouble("snowball-fight.chill",DEFAULTS.chill);
            if(!Double.isFinite(chill)||chill<0||chill>1)throw new IllegalArgumentException("snowball-fight.chill must be 0..1");
            return new Settings(c.getBoolean("snowball-fight.enabled",DEFAULTS.enabled),chill,
                    range(c,"snowball-fight.duel-hits",DEFAULTS.duelHits,1,100),range(c,"snowball-fight.duel-seconds",DEFAULTS.duelSeconds,15,1800),
                    range(c,"snowball-fight.duel-snowballs",DEFAULTS.duelSnowballs,0,64));
        }
        private static int range(FileConfiguration c,String key,int fallback,int min,int max) {
            int n=c.getInt(key,fallback);if(n<min||n>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);return n;
        }
    }
    public enum Challenge { SENT, PENDING, STARTED, SELF, BUSY }
    /** How a running duel ended. */
    public enum End { HITS, TIME, FORFEIT, SURRENDER }

    public static final class Duel {
        private final UUID first,second;
        private final int goal;
        private final long endsAt;
        private int firstScore,secondScore;
        private End end;
        private UUID forfeited;
        Duel(UUID first,UUID second,int goal,long endsAt){this.first=first;this.second=second;this.goal=goal;this.endsAt=endsAt;}
        public UUID first(){return first;}
        public UUID second(){return second;}
        public long endsAt(){return endsAt;}
        public boolean involves(UUID player){return first.equals(player)||second.equals(player);}
        public UUID opponent(UUID player){return first.equals(player)?second:first;}
        public int score(UUID player){return first.equals(player)?firstScore:secondScore;}
        public End end(){return end;}
        /** Winner after the duel ended; null while running or for a draw. */
        public UUID winner(){
            if(end==null)return null;
            if(end==End.FORFEIT||end==End.SURRENDER)return opponent(forfeited);
            return firstScore==secondScore?null:firstScore>secondScore?first:second;
        }
        private boolean point(UUID thrower){
            if(first.equals(thrower))firstScore++;else secondScore++;
            if(score(thrower)>=goal){end=End.HITS;return true;}
            return false;
        }
    }

    private final Map<String,Long> lastHit=new HashMap<>();
    /** Challenger -> challenged, with the expiry time. */
    private final Map<UUID,Map<UUID,Long>> challenges=new HashMap<>();
    private final Map<UUID,Duel> duels=new HashMap<>();

    /** Whether this hit counts at all (not on yourself, not within the pair cooldown). */
    public boolean countHit(UUID thrower,UUID target,long now) {
        if(thrower.equals(target))return false;
        String key=thrower+">"+target;
        Long last=lastHit.get(key);
        if(last!=null&&now-last<PAIR_COOLDOWN_MILLIS)return false;
        lastHit.put(key,now);
        return true;
    }

    /**
     * Challenges another player. If that player already challenged {@code from}, this accepts instead and starts the
     * duel ({@link Challenge#STARTED}, see {@link #duelOf}).
     */
    public Challenge challenge(UUID from,UUID to,long now,int goal,long durationMillis) {
        if(from.equals(to))return Challenge.SELF;
        if(duels.containsKey(from)||duels.containsKey(to))return Challenge.BUSY;
        if(waiting(to,from,now)){
            removeChallengesOf(from);removeChallengesOf(to);
            Duel duel=new Duel(to,from,goal,now+durationMillis);
            duels.put(from,duel);duels.put(to,duel);
            return Challenge.STARTED;
        }
        if(waiting(from,to,now))return Challenge.PENDING;
        challenges.computeIfAbsent(from,k->new HashMap<>()).put(to,now+CHALLENGE_MILLIS);
        return Challenge.SENT;
    }

    /** Whether {@code challenger} has an open challenge to {@code target}. */
    public boolean waiting(UUID challenger,UUID target,long now) {
        Long until=challenges.getOrDefault(challenger,Map.of()).get(target);
        return until!=null&&now<until;
    }

    public Optional<Duel> duelOf(UUID player){return Optional.ofNullable(duels.get(player));}

    /** Scores a counted hit inside a duel between these two; returns the duel (ended when the goal was reached). */
    public Optional<Duel> hitInDuel(UUID thrower,UUID target) {
        Duel duel=duels.get(thrower);
        if(duel==null||!duel.opponent(thrower).equals(target))return Optional.empty();
        if(duel.point(thrower))remove(duel);
        return Optional.of(duel);
    }

    /** Ends duels whose time ran out and forgets old challenges and cooldowns. */
    public List<Duel> expire(long now) {
        List<Duel> ended=new ArrayList<>();
        for(Duel duel:Set.copyOf(duels.values()))if(now>=duel.endsAt){duel.end=End.TIME;remove(duel);ended.add(duel);}
        challenges.values().forEach(open->open.values().removeIf(until->now>=until));
        challenges.values().removeIf(Map::isEmpty);
        lastHit.values().removeIf(last->now-last>=PAIR_COOLDOWN_MILLIS);
        return ended;
    }

    /** A player left: a running duel is lost by forfeit, open challenges disappear. */
    public Optional<Duel> leave(UUID player) {
        removeChallengesOf(player);
        for(Map<UUID,Long> open:challenges.values())open.remove(player);
        Duel duel=duels.get(player);
        if(duel==null)return Optional.empty();
        duel.end=End.FORFEIT;duel.forfeited=player;remove(duel);
        return Optional.of(duel);
    }

    /** Gives up a running duel; a deadline already reached takes precedence. Pending challenges stay untouched. */
    public Optional<Duel> surrender(UUID player,long now) {
        Duel duel=duels.get(player);
        if(duel==null)return Optional.empty();
        duel.end=now>=duel.endsAt?End.TIME:End.SURRENDER;
        duel.forfeited=player;remove(duel);
        return Optional.of(duel);
    }

    public void clear(){lastHit.clear();challenges.clear();duels.clear();}

    private void removeChallengesOf(UUID player){challenges.remove(player);}
    private void remove(Duel duel){duels.remove(duel.first,duel);duels.remove(duel.second,duel);}
}
