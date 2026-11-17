package at.vystorm.winter.fun;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.fun.SnowballDuels.Challenge;
import at.vystorm.winter.fun.SnowballDuels.Duel;
import at.vystorm.winter.fun.SnowballDuels.End;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SnowballDuelsTest {
    private final UUID alex=UUID.randomUUID(),sam=UUID.randomUUID(),kim=UUID.randomUUID();
    private final SnowballDuels duels=new SnowballDuels();

    @Test void hitsCountOncePerSecondPerPairAndNeverOnYourself() {
        assertTrue(duels.countHit(alex,sam,1000));
        assertFalse(duels.countHit(alex,sam,1500));
        assertTrue(duels.countHit(sam,alex,1500),"the other direction has its own cooldown");
        assertTrue(duels.countHit(alex,kim,1500));
        assertTrue(duels.countHit(alex,sam,2000));
        assertFalse(duels.countHit(alex,alex,5000));
    }

    @Test void challengeThenCounterChallengeStartsTheDuel() {
        assertEquals(Challenge.SENT,duels.challenge(alex,sam,0,3,60_000));
        assertTrue(duels.waiting(alex,sam,0));
        assertEquals(Challenge.PENDING,duels.challenge(alex,sam,10,3,60_000));
        assertEquals(Challenge.STARTED,duels.challenge(sam,alex,20,3,60_000));
        Duel duel=duels.duelOf(alex).orElseThrow();
        assertSame(duel,duels.duelOf(sam).orElseThrow());
        assertEquals(sam,duel.opponent(alex));
        assertFalse(duels.waiting(alex,sam,20));
        assertEquals(Challenge.BUSY,duels.challenge(kim,alex,30,3,60_000));
        assertEquals(Challenge.SELF,duels.challenge(kim,kim,30,3,60_000));
    }

    @Test void challengesExpire() {
        duels.challenge(alex,sam,0,3,60_000);
        assertFalse(duels.waiting(alex,sam,SnowballDuels.CHALLENGE_MILLIS));
        assertEquals(Challenge.SENT,duels.challenge(sam,alex,SnowballDuels.CHALLENGE_MILLIS,3,60_000),"an expired challenge is not accepted");
    }

    @Test void firstToTheGoalWins() {
        start(3);
        assertTrue(duels.hitInDuel(kim,alex).isEmpty(),"outsiders do not score");
        duels.hitInDuel(alex,sam);duels.hitInDuel(sam,alex);duels.hitInDuel(alex,sam);
        Duel duel=duels.hitInDuel(alex,sam).orElseThrow();
        assertEquals(End.HITS,duel.end());
        assertEquals(alex,duel.winner());
        assertEquals(3,duel.score(alex));assertEquals(1,duel.score(sam));
        assertTrue(duels.duelOf(alex).isEmpty());assertTrue(duels.duelOf(sam).isEmpty());
    }

    @Test void timeUpPicksTheLeaderOrADraw() {
        start(10);
        duels.hitInDuel(sam,alex);
        assertTrue(duels.expire(59_999).isEmpty());
        Duel duel=duels.expire(60_000).get(0);
        assertEquals(End.TIME,duel.end());
        assertEquals(sam,duel.winner());

        start(10);
        Duel draw=duels.expire(60_000).get(0);
        assertNull(draw.winner());
    }

    @Test void leavingForfeitsTheDuel() {
        start(10);
        duels.hitInDuel(alex,sam);
        Duel duel=duels.leave(alex).orElseThrow();
        assertEquals(End.FORFEIT,duel.end());
        assertEquals(sam,duel.winner());
        assertTrue(duels.duelOf(sam).isEmpty());
        assertTrue(duels.leave(kim).isEmpty());
    }

    @Test void leavingDropsOpenChallenges() {
        duels.challenge(alex,sam,0,3,60_000);duels.challenge(kim,alex,0,3,60_000);
        duels.leave(alex);
        assertFalse(duels.waiting(alex,sam,1));
        assertFalse(duels.waiting(kim,alex,1));
    }

    @Test void settingsUseDefaultsForOldConfigsAndRejectBadValues() {
        assertEquals(SnowballDuels.Settings.DEFAULTS,SnowballDuels.Settings.read(new YamlConfiguration()));
        YamlConfiguration bad=new YamlConfiguration();bad.set("snowball-fight.chill",2.0);
        assertThrows(IllegalArgumentException.class,()->SnowballDuels.Settings.read(bad));
        YamlConfiguration hits=new YamlConfiguration();hits.set("snowball-fight.duel-hits",0);
        assertThrows(IllegalArgumentException.class,()->SnowballDuels.Settings.read(hits));
    }

    @Test void surrenderAwardsOpponentEvenWhenLeadingAndCannotEndTwice() {
        start(10);
        duels.hitInDuel(alex,sam);
        Duel duel=duels.surrender(alex,1000).orElseThrow();
        assertEquals(End.SURRENDER,duel.end());
        assertEquals(sam,duel.winner());
        assertEquals(1,duel.score(alex));
        assertTrue(duels.duelOf(alex).isEmpty());
        assertTrue(duels.duelOf(sam).isEmpty());
        assertTrue(duels.surrender(alex,1001).isEmpty());
        assertTrue(duels.leave(sam).isEmpty());
        assertTrue(duels.expire(60_000).isEmpty());
        assertTrue(duels.hitInDuel(sam,alex).isEmpty());
    }

    @Test void surrenderAtDeadlinePreservesTimedResult() {
        start(10);
        duels.hitInDuel(alex,sam);
        Duel duel=duels.surrender(alex,60_000).orElseThrow();
        assertEquals(End.TIME,duel.end());
        assertEquals(alex,duel.winner());
        start(10);
        assertNull(duels.surrender(alex,60_001).orElseThrow().winner());
    }

    @Test void surrenderWithoutDuelLeavesInvitationsAndOtherDuelsAlone() {
        duels.challenge(alex,sam,0,10,60_000);
        assertTrue(duels.surrender(alex,1).isEmpty());
        assertTrue(duels.waiting(alex,sam,1));
        duels.challenge(sam,alex,2,10,60_000);
        assertTrue(duels.surrender(kim,3).isEmpty());
        assertTrue(duels.duelOf(alex).isPresent());
    }

    private void start(int goal) {
        duels.challenge(alex,sam,0,goal,60_000);
        assertEquals(Challenge.STARTED,duels.challenge(sam,alex,0,goal,60_000));
    }
}
