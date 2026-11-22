package at.vystorm.winter;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import at.vystorm.winter.storage.SnowballScores;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The rankings of WinterIsComing in Core's placeholders (1.26.0, Core 0.67.0, capability {@code placeholder-rankings}):
 * {@code winter:snowball-hits} (snowball hits in the current winter season) and {@code winter:snowball-wins} (snowball
 * duels won in that season). Both read the seasonal snowball leaderboard that {@code snowball.json} already holds
 * (player, last known name, hits, wins), so offline players are listed from the first start. Only names and numbers.
 *
 * <p>This is the only class that names Core's ranking types. An older Core lacks them, so it is only touched after
 * {@code CoreApi.has("placeholder-rankings")} ({@link WinterPlugin#rankings}).
 */
final class WinterRankings {
    static final String HITS = "snowball-hits", WINS = "snowball-wins";

    private WinterRankings() {}

    /**
     * Declares each ranking that is on and removes each that is off (a reload switched it off). {@code rows} answers
     * from memory on the server thread: the players of the current season's board.
     */
    static void apply(Plugin plugin, PlaceholderInterface registry, boolean hitsOn, boolean winsOn,
                      Supplier<? extends Collection<SnowballScores.Row>> rows, UnaryOperator<String> ref) {
        if (!hitsOn && !winsOn) {
            registry.namespace(WinterPlaceholders.NAMESPACE).ifPresent(ns -> { ns.removeRanking(HITS); ns.removeRanking(WINS); });
            return;
        }
        PlaceholderNamespace ns = registry.register(plugin, WinterPlaceholders.NAMESPACE);
        if (hitsOn) ns.ranking(HITS, RankingInfo.of(ref.apply("rankings." + HITS + ".label"), ref.apply("rankings." + HITS + ".description")),
                n -> top(rows.get(), SnowballScores.Row::hits, n));
        else ns.removeRanking(HITS);
        if (winsOn) ns.ranking(WINS, RankingInfo.of(ref.apply("rankings." + WINS + ".label"), ref.apply("rankings." + WINS + ".description")),
                n -> top(rows.get(), SnowballScores.Row::wins, n));
        else ns.removeRanking(WINS);
    }

    /** The best {@code n} players by {@code value}, players with 0 left out, ties by name. */
    static List<RankingSource.Row> top(Collection<SnowballScores.Row> rows, ToIntFunction<SnowballScores.Row> value, int n) {
        if (rows == null) return List.of();
        List<RankingSource.Row> out = new ArrayList<>();
        for (SnowballScores.Row row : rows) {
            int v = value.applyAsInt(row);
            if (v > 0) out.add(new RankingSource.Row(row.player(), row.name() == null ? "" : row.name(), v));
        }
        out.sort(Comparator.comparingDouble(RankingSource.Row::value).reversed().thenComparing(row -> row.name().toLowerCase(Locale.ROOT)));
        return out.size() > n ? List.copyOf(out.subList(0, n)) : out;
    }
}
