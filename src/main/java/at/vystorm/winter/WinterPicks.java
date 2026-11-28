package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickVerdict;
import Vystorm.vystorm_core.picker.PlayerPick;
import Vystorm.vystorm_core.picker.PlayerSource;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * Everything that names Core 0.71.0 types (player picker, {@code PickReturn}, {@code FallbackContext}). Reached only
 * after {@code CoreApi.has("player-picker")} or {@code CoreApi.has("panel-fallback-context")}, so the plugin still
 * loads on Core 0.65.0 to 0.70.x (1.28.0).
 */
final class WinterPicks {
    private WinterPicks() {}

    /** Online and nearby players who can take part in a snowball fight right now; one click challenges. */
    static PlayerPick duelPick(WinterPlugin plugin, SnowballFight fight) {
        var lang = plugin.lang();
        return PlayerPick.builder(lang.ref("player-panel-duel"))
                .subtitle(lang.ref("player-panel-duel-text"))
                .sources(PlayerSource.NEARBY, PlayerSource.ONLINE)
                .includeSelf(false)
                .rule((viewer, c) -> c.player().filter(fight::active).isPresent() ? PickVerdict.ok()
                        : PickVerdict.deny(lang.ref("player-panel-duel-not-here")))
                .onPick((viewer, picks) -> {
                    if (picks.chosen().isEmpty()) return Result.ok();
                    Player target = picks.chosen().getFirst().player().orElse(null);
                    if (target == null) return Result.error(lang.plain(viewer, "snowball-offline"));
                    fight.challenge(viewer, target);
                    return Result.ok();
                })
                .build();
    }

    /** The panel's duel button: the pick comes back to the panel with its state. */
    static Result duel(API core, WinterPlugin plugin, SnowballFight fight, PanelAction action) {
        return core.gui().pickers().players(action, duelPick(plugin, fight));
    }

    /** The duel button of the plain chest: the pick comes back to that chest. */
    static void duelToChest(API core, WinterPlugin plugin, SnowballFight fight, Player player, Consumer<Player> chest) {
        core.gui().pickers().players(plugin, player, duelPick(plugin, fight), PickReturn.to(chest));
    }

    /**
     * Registers the chest as a context fallback (Core 0.71.0 {@code panel-fallback-context}): a pick started from the
     * chest comes back to the panel by the normal route (screen when it can open now, else this chest).
     */
    static PanelSpec.Builder contextFallback(PanelSpec.Builder builder, API core, WinterPlugin plugin, WinterPlayerMenu menu) {
        return builder.contextFallback((player, ctx) -> menu.openChest(player, pickDuel(core, plugin, ctx)));
    }

    private static Consumer<Player> pickDuel(API core, WinterPlugin plugin, FallbackContext ctx) {
        if (!WinterPlayerMenu.pickerAvailable() || plugin.snowballFight() == null) return null;
        return p -> core.gui().pickers().players(plugin, p, duelPick(plugin, plugin.snowballFight()), PickReturn.panel(ctx));
    }
}
