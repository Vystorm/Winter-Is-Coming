package at.vystorm.winter;

import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuInterface;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** "Snowball duel" in Core's player menu. */
final class SnowballMenuEntry {
    private SnowballMenuEntry() {}
    static void register(WinterPlugin plugin,PlayerMenuInterface playerMenu,SnowballFight fight){
        var lang=plugin.lang();
        playerMenu.register(plugin,PlayerMenuEntry.builder("snowball-duel")
                .priority(60)
                .visibleWhen((viewer,target)->fight.active(viewer)&&fight.active(target))
                .icon((viewer,target)->{
                    var row=fight.scores().row(target.getUniqueId(),fight.season());
                    boolean invited=fight.duels().waiting(target.getUniqueId(),viewer.getUniqueId(),System.currentTimeMillis());
                    ItemStack item=lang.name(new ItemStack(Material.SNOWBALL),viewer,invited?"snowball-menu-accept":"snowball-menu","player",target.getName());
                    return lang.lore(item,viewer,"snowball-menu-lore","player",target.getName(),"hits",row.hits(),"wins",row.wins(),
                            "goal",plugin.snowballSettings().duelHits());
                })
                .onClick((viewer,target,click)->{fight.challenge(viewer,target);return PlayerMenu.Result.CLOSE;})
                .build());
    }
}
