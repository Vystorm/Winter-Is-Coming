package at.vystorm.winter;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.blocks.CellTest;
import Vystorm.vystorm_core.blocks.EditAsk;
import Vystorm.vystorm_core.blocks.EditVerdict;
import at.vystorm.winter.world.BuildAreas;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Where a player may clear snow, from Core's {@code api.area().mayEdit} (Core 0.69.0, capability {@code edit-ask}): the
 * same answer bulk edits get, from Core's regions and every guard (claims, plots, Protect). Only loaded behind
 * {@code CoreApi.has("edit-ask")}, so an older Core never sees these types.
 */
final class EditPermits {
    private EditPermits() {}

    /**
     * One question per chunk the circle touches, for the cells from 24 below to 24 above {@code y}; a guard that fails
     * counts as no.
     */
    static BuildAreas.Permit of(Plugin plugin, API core, Player player, World world, int x, int y, int z, int radius) {
        int minY = Math.max(world.getMinHeight(), y - 24), maxY = Math.max(minY, Math.min(world.getMaxHeight() - 1, y + 24));
        Map<Long, EditVerdict> verdicts = new HashMap<>();
        boolean all = true;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
            EditVerdict verdict;
            try {
                verdict = core.area().mayEdit(new EditAsk(plugin, player.getUniqueId(), world, cx, cz, minY, maxY));
            } catch (RuntimeException failure) {
                verdict = new EditVerdict.Deny("guard-failed");
            }
            if (!(verdict instanceof EditVerdict.Allow)) all = false;
            verdicts.put(key(cx, cz), verdict);
        }
        if (all) return BuildAreas.Permit.ALL;
        return (px, pz) -> {
            EditVerdict v = verdicts.get(key(px >> 4, pz >> 4));
            if (v instanceof EditVerdict.Allow) return true;
            if (v instanceof EditVerdict.Partial(CellTest test)) {
                try {
                    return test.allowed(px, y, pz);
                } catch (RuntimeException failure) {
                    return false;
                }
            }
            return false;
        };
    }

    private static long key(int cx, int cz) { return (long) cx << 32 ^ (cz & 0xFFFFFFFFL); }
}
