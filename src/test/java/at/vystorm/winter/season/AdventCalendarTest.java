package at.vystorm.winter.season;

import static org.junit.jupiter.api.Assertions.*;

import at.vystorm.winter.season.AdventCalendar.Door;
import at.vystorm.winter.season.AdventCalendar.Reward;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AdventCalendarTest {
    private static final LocalDate DEC_5=LocalDate.of(2026,12,5);

    @Test void doorsOnlyOpenInDecemberOnOrAfterTheirDay() {
        assertEquals(Door.LOCKED,AdventCalendar.state(1,LocalDate.of(2026,11,30),false,true));
        assertEquals(Door.READY,AdventCalendar.state(5,DEC_5,false,false));
        assertEquals(Door.LOCKED,AdventCalendar.state(6,DEC_5,false,true));
        assertEquals(Door.OPENED,AdventCalendar.state(5,DEC_5,true,true));
        assertEquals(Door.LOCKED,AdventCalendar.state(1,LocalDate.of(2027,1,2),false,true));
    }

    @Test void catchUpDecidesAboutMissedDoors() {
        assertEquals(Door.READY,AdventCalendar.state(2,DEC_5,false,true));
        assertEquals(Door.MISSED,AdventCalendar.state(2,DEC_5,false,false));
        assertEquals(Door.READY,AdventCalendar.state(24,LocalDate.of(2026,12,31),false,true));
        assertEquals(Door.MISSED,AdventCalendar.state(24,LocalDate.of(2026,12,25),false,false));
    }

    @Test void waitingCountsOpenableDoors() {
        assertEquals(5,AdventCalendar.waiting(DEC_5,Set.of(),true));
        assertEquals(3,AdventCalendar.waiting(DEC_5,Set.of(1,4),true));
        assertEquals(1,AdventCalendar.waiting(DEC_5,Set.of(1,4),false));
        assertEquals(0,AdventCalendar.waiting(DEC_5,Set.of(5),false));
        assertEquals(0,AdventCalendar.waiting(LocalDate.of(2026,11,20),Set.of(),true));
    }

    @Test void giftIdsAreStablePerYearAndDayAndDifferFromWeeklyAdventGifts() {
        assertEquals("2026/CALENDAR/5",AdventCalendar.giftId(2026,5));
        assertNotEquals(AdventCalendar.giftId(2026,1),AdventCalendar.giftId(2027,1));
        assertFalse(AdventCalendar.giftId(2026,1).contains("ADVENT"));
    }

    @Test void rewardsRepeatWhenTheListIsShorter() {
        List<Reward> rewards=List.of(new Reward(Material.COOKIE,6),new Reward(Material.CAKE,1));
        assertEquals(Material.COOKIE,AdventCalendar.rewardFor(1,rewards).material());
        assertEquals(Material.CAKE,AdventCalendar.rewardFor(2,rewards).material());
        assertEquals(Material.COOKIE,AdventCalendar.rewardFor(3,rewards).material());
        assertEquals(Material.CAKE,AdventCalendar.rewardFor(24,rewards).material());
    }

    @Test void parsesRewardEntries() {
        assertEquals(new Reward(Material.COOKIE,6),AdventCalendar.parse("COOKIE 6"));
        assertEquals(new Reward(Material.DIAMOND,1),AdventCalendar.parse("  diamond "));
        for(String bad:List.of("","NOT_A_THING 2","COOKIE x","COOKIE 0","COOKIE 2 3"))
            assertThrows(IllegalArgumentException.class,()->AdventCalendar.parse(bad),bad);
    }

    @Test void layoutPlacesEveryDoorOnItsOwnSlotAboveTheButtonRow() {
        int[] layout=AdventCalendar.layout();
        assertEquals(AdventCalendar.DOORS,layout.length);
        Set<Integer> seen=new HashSet<>();
        for(int slot:layout){assertTrue(slot>=0&&slot<45,"slot "+slot);assertTrue(seen.add(slot));}
        assertArrayEquals(layout,AdventCalendar.layout(),"doors must stay in place between openings");
    }

    @Test void bundledConfigHasOneValidRewardPerDoor() throws Exception {
        try(var in=AdventCalendarTest.class.getResourceAsStream("/config.yml")){
            YamlConfiguration config=YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));
            List<String> rewards=config.getStringList("advent-calendar.rewards");
            assertEquals(AdventCalendar.Settings.DEFAULT_REWARDS,rewards);
            assertEquals(AdventCalendar.DOORS,rewards.size());
            rewards.forEach(AdventCalendar::parse);
            assertTrue(config.getBoolean("advent-calendar.enabled"));
            assertTrue(config.getBoolean("advent-calendar.catch-up"));
        }
    }
}
