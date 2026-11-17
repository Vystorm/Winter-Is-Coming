package at.vystorm.winter.season;

import java.time.LocalDate;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** Pure rules of the daily Advent calendar: which door opens when, where it sits in the menu and what it holds. */
public final class AdventCalendar {
    public static final int DOORS=24;
    /** Door slots in the 54-slot menu; the bottom row stays free for the info and gift buttons. */
    public static final int MENU_SIZE=54;
    public enum Door { LOCKED, READY, OPENED, MISSED }
    public record Reward(Material material,int amount) {}
    public record Settings(boolean enabled,boolean catchUp,boolean joinReminder,List<Reward> rewards) {
        public static final List<String> DEFAULT_REWARDS=List.of("COOKIE 6","SWEET_BERRIES 12","PUMPKIN_PIE 2","CANDLE 3",
                "GLOW_BERRIES 8","SPRUCE_SAPLING 4","HONEY_BOTTLE 2","SNOWBALL 16","RED_CANDLE 2","BAKED_POTATO 6",
                "LANTERN 2","GOLDEN_CARROT 3","CAKE 1","FIREWORK_ROCKET 8","MUSHROOM_STEW 1","GREEN_CANDLE 2",
                "EXPERIENCE_BOTTLE 4","RABBIT_STEW 1","LEATHER_BOOTS 1","GLOW_INK_SAC 4","AMETHYST_SHARD 4",
                "LEATHER_CHESTPLATE 1","GOLDEN_APPLE 1","DIAMOND 1");
        public static Settings read(FileConfiguration c) {
            List<String> texts=c.getStringList("advent-calendar.rewards");
            if(texts.isEmpty())texts=DEFAULT_REWARDS;
            List<Reward> rewards=new ArrayList<>();
            for(String text:texts)rewards.add(validate(parse(text)));
            return new Settings(c.getBoolean("advent-calendar.enabled",true),c.getBoolean("advent-calendar.catch-up",true),
                    c.getBoolean("advent-calendar.join-reminder",true),List.copyOf(rewards));
        }
    }
    private AdventCalendar() {}

    /** Door state on the real date. Doors only open in December; catch-up keeps missed doors open until the 31st. */
    public static Door state(int day,LocalDate today,boolean opened,boolean catchUp) {
        if(opened)return Door.OPENED;
        if(today.getMonthValue()!=12||today.getDayOfMonth()<day)return Door.LOCKED;
        return today.getDayOfMonth()==day||catchUp?Door.READY:Door.MISSED;
    }

    /** Doors that can be opened right now. */
    public static int waiting(LocalDate today,Set<Integer> opened,boolean catchUp) {
        int count=0;
        for(int day=1;day<=DOORS;day++)if(state(day,today,opened.contains(day),catchUp)==Door.READY)count++;
        return count;
    }

    /** Stable reward ledger ID; separate from the weekly Advent gift IDs ("year/ADVENT/week"). */
    public static String giftId(int year,int day) { return year+"/CALENDAR/"+day; }

    /** Reward of a door; shorter lists repeat from the start. */
    public static Reward rewardFor(int day,List<Reward> rewards) { return rewards.get((day-1)%rewards.size()); }

    /** "MATERIAL amount" or just "MATERIAL" (amount 1). */
    public static Reward parse(String text) {
        String[] parts=text.trim().split("\\s+");
        if(parts.length<1||parts.length>2||parts[0].isEmpty())throw new IllegalArgumentException("advent-calendar.rewards entry '"+text+"' must be 'MATERIAL amount'");
        Material material=Material.matchMaterial(parts[0]);
        if(material==null)throw new IllegalArgumentException("advent-calendar.rewards: unknown material "+parts[0]);
        int amount;
        try{amount=parts.length==2?Integer.parseInt(parts[1]):1;}
        catch(NumberFormatException failure){throw new IllegalArgumentException("advent-calendar.rewards: invalid amount in '"+text+"'");}
        if(amount<1)throw new IllegalArgumentException("advent-calendar.rewards: amount must be at least 1 in '"+text+"'");
        return new Reward(material,amount);
    }

    private static Reward validate(Reward reward) {
        if(!reward.material().isItem()||reward.material().isAir())throw new IllegalArgumentException("advent-calendar.rewards: "+reward.material()+" is not an item");
        if(reward.amount()>reward.material().getMaxStackSize())throw new IllegalArgumentException("advent-calendar.rewards: at most "+reward.material().getMaxStackSize()+" "+reward.material());
        return reward;
    }

    /**
     * Menu slot of each door (index 0 = door 1). Like a real Advent calendar the doors are scattered, but always in the
     * same places: a fixed shuffle of the top five rows.
     */
    public static int[] layout() {
        List<Integer> slots=new ArrayList<>();
        for(int slot=0;slot<45;slot++)slots.add(slot);
        Collections.shuffle(slots,new Random(1224));
        int[] doors=new int[DOORS];
        for(int i=0;i<DOORS;i++)doors[i]=slots.get(i);
        return doors;
    }
}
