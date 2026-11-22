package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import at.vystorm.winter.fun.SnowballDuels;
import at.vystorm.winter.storage.SnowballScores;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rankings winter:snowball-hits and winter:snowball-wins in Core's placeholders (Winter 1.26.0, Core 0.67.0). */
class WinterRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final Plugin WINTER = PlaceholderTesting.plugin("WinterIsComing");
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes()), SAM = UUID.nameUUIDFromBytes("sam".getBytes()),
            KIM = UUID.nameUUIDFromBytes("kim".getBytes()), LEE = UUID.nameUUIDFromBytes("lee".getBytes());
    private static final int SEASON = 2026;

    @TempDir Path folder;

    /** The snowball leaderboard as {@code snowball.json} holds it: written by one store, read back by a fresh one (nobody online). */
    private SnowballScores fixture() {
        Path file = folder.resolve("snowball.json");
        SnowballScores scores = new SnowballScores(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        for (int i = 0; i < 1200; i++) scores.hit(SAM, "Sam", SEASON);
        for (int i = 0; i < 40; i++) scores.hit(ALEX, "Alex", SEASON);
        for (int i = 0; i < 3; i++) scores.win(ALEX, "Alex", SEASON);
        for (int i = 0; i < 7; i++) scores.hit(KIM, "Kim", SEASON);
        for (int i = 0; i < 5; i++) scores.win(KIM, "Kim", SEASON);
        scores.saveIfDirty();
        return new SnowballScores(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
    }

    private static Throwable declare(PlaceholderTesting placeholders, boolean coreHasRankings, boolean hits, boolean wins, Supplier<List<SnowballScores.Row>> rows) {
        return WinterPlugin.rankings(coreHasRankings, WINTER, placeholders::placeholders, hits, wins, rows, key -> "ref:" + key);
    }

    @Test void givenAStoredLeaderboard_whenDeclared_thenBothRankingsListTheOfflinePlayersInOrder() {
        SnowballScores scores = fixture();
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, true, true, () -> scores.rows(SEASON)));
        assertEquals(List.of("winter:snowball-hits", "winter:snowball-wins"),
                placeholders.rankings().stream().map(PlaceholderRanking::id).sorted().toList());

        List<RankingSource.Row> hits = placeholders.top("winter:snowball-hits", 10);
        assertEquals(List.of("Sam", "Alex", "Kim"), hits.stream().map(RankingSource.Row::name).toList());
        assertEquals(List.of(SAM, ALEX, KIM), hits.stream().map(RankingSource.Row::player).toList());
        assertEquals(List.of(1200.0, 40.0, 7.0), hits.stream().map(RankingSource.Row::value).toList());

        List<RankingSource.Row> wins = placeholders.top("winter:snowball-wins", 10);
        assertEquals(List.of("Kim", "Alex"), wins.stream().map(RankingSource.Row::name).toList(), "Sam never won a duel");
        assertEquals(List.of(5.0, 3.0), wins.stream().map(RankingSource.Row::value).toList());

        assertEquals("Sam", placeholders.resolve(null, "%vystorm_winter_top_snowball-hits_1_name%"));
        assertEquals("1,200", placeholders.resolve(null, "%vystorm_winter_top_snowball-hits_1_value%"));
        assertEquals("1.200", placeholders.language("de").resolve(null, "%vystorm_winter_top_snowball-hits_1_value%"));
        assertEquals("1200", placeholders.resolve(null, "%vystorm_winter_top_snowball-hits_1_raw%"));
        assertEquals("Alex", placeholders.resolve(null, "{winter:top_snowball-wins:2:name}"));
        assertEquals("", placeholders.resolve(null, "%vystorm_winter_top_snowball-wins_3_name%"));
        assertEquals("ref:rankings.snowball-hits.label", placeholders.ranking("winter:snowball-hits").orElseThrow().info().label());
        assertEquals("ref:rankings.snowball-wins.description", placeholders.ranking("winter:snowball-wins").orElseThrow().info().description());
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenTiesAndAnotherSeason_whenAsked_thenNamesBreakTiesAndAnOldSeasonIsEmpty() {
        List<SnowballScores.Row> rows = List.of(new SnowballScores.Row(SAM, "sam", 4, 0), new SnowballScores.Row(ALEX, "Alex", 4, 1),
                new SnowballScores.Row(LEE, "Lee", 0, 0));
        assertEquals(List.of("Alex", "sam"), WinterRankings.top(rows, SnowballScores.Row::hits, 10).stream().map(RankingSource.Row::name).toList());
        assertEquals(1, WinterRankings.top(rows, SnowballScores.Row::hits, 1).size());
        assertEquals(List.of(), WinterRankings.top(null, SnowballScores.Row::hits, 10));
        assertEquals(List.of(), fixture().rows(SEASON + 1), "a new season starts with an empty board");
    }

    @Test void givenNewHits_whenAskedAgain_thenTheRankingFollows() {
        SnowballScores scores = fixture();
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        declare(placeholders, true, true, true, () -> scores.rows(SEASON));
        for (int i = 0; i < 2000; i++) scores.hit(LEE, "Lee", SEASON);
        placeholders.advanceTicks(1);
        assertEquals("Lee", placeholders.resolve(null, "%vystorm_winter_top_snowball-hits_1_name%"));
        assertEquals("Sam", placeholders.resolve(null, "%vystorm_winter_top_snowball-hits_2_name%"));
    }

    @Test void givenTheSwitchesOff_whenApplied_thenTheRankingsAreNotOfferedOrRemoved() {
        SnowballScores scores = fixture();
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, false, false, () -> scores.rows(SEASON)));
        assertTrue(placeholders.rankings().isEmpty());
        assertNull(declare(placeholders, true, true, true, () -> scores.rows(SEASON)));
        assertEquals(2, placeholders.rankings().size());
        assertNull(declare(placeholders, true, true, false, () -> scores.rows(SEASON)));   // reload with duels off
        assertEquals(List.of("winter:snowball-hits"), placeholders.rankings().stream().map(PlaceholderRanking::id).toList());
        assertNull(declare(placeholders, true, false, false, () -> scores.rows(SEASON)));   // reload with rankings.enabled: false
        assertTrue(placeholders.rankings().isEmpty());
    }

    @Test void givenThePluginSwitches_whenRead_thenRankingsFollowRankingsSnowballFightsAndDuels() throws Exception {
        assertTrue(plugin(WinterSwitches.DEFAULTS, SnowballDuels.Settings.DEFAULTS).hitsRanked());
        assertTrue(plugin(WinterSwitches.DEFAULTS, SnowballDuels.Settings.DEFAULTS).winsRanked());
        WinterPlugin off = plugin(WinterSwitches.without(WinterSwitches.Feature.RANKINGS), SnowballDuels.Settings.DEFAULTS);
        assertFalse(off.hitsRanked());
        assertFalse(off.winsRanked());
        WinterPlugin noDuels = plugin(WinterSwitches.without(WinterSwitches.Feature.DUELS), SnowballDuels.Settings.DEFAULTS);
        assertTrue(noDuels.hitsRanked());
        assertFalse(noDuels.winsRanked());
        SnowballDuels.Settings d = SnowballDuels.Settings.DEFAULTS;
        WinterPlugin noFights = plugin(WinterSwitches.DEFAULTS, new SnowballDuels.Settings(false, d.chill(), d.duelHits(), d.duelSeconds(), d.duelSnowballs()));
        assertFalse(noFights.hitsRanked());
        assertFalse(noFights.winsRanked());
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                WinterRankingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        assertTrue(config.getBoolean("rankings.enabled", false), "on by default");
        assertTrue(WinterSwitches.allPaths().contains("rankings.enabled"));
    }

    @Test void givenACoreWithoutRankings_whenApplied_thenNothingIsDeclaredAndNothingThrows() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(WinterPlugin.rankings(false, WINTER, () -> { throw new AssertionError("an older Core must not be asked"); },
                true, true, List::of, key -> key));
        assertTrue(placeholders.rankings().isEmpty());
        assertInstanceOf(NoClassDefFoundError.class, WinterPlugin.rankings(true, WINTER, () -> { throw new NoClassDefFoundError("RankingInfo"); },
                true, true, List::of, key -> key));
        PlaceholderTesting taken = PlaceholderTesting.detached();
        taken.register(PlaceholderTesting.plugin("SomeoneElse"), "winter");
        assertInstanceOf(IllegalStateException.class, declare(taken, true, true, true, List::of));
    }

    @Test void givenTheWinterPlaceholders_whenRankingsAreAdded_thenTheOldValuesStillAnswer() {
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        WinterPlaceholders.Source source = mock(WinterPlaceholders.Source.class);
        WinterPlaceholders.register(placeholders.placeholders(), WINTER, null, source);
        assertNull(declare(placeholders, true, true, true, List::of));
        assertEquals(2, placeholders.rankings().size());
        assertEquals("", placeholders.resolve(null, "%vystorm_winter_top_snowball-hits_1_name%"));
        assertEquals(WinterPlaceholders.NAMESPACE, "winter");
    }

    @Test void givenEveryLanguage_whenRead_thenBothRankingsAndTheSwitchHaveTexts() throws Exception {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new InputStreamReader(
                    WinterRankingsTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
            for (String key : List.of(WinterRankings.HITS, WinterRankings.WINS)) {
                assertFalse(lang.getString("rankings." + key + ".label", "").isBlank(), code + " " + key);
                assertFalse(lang.getString("rankings." + key + ".description", "").isBlank(), code + " " + key);
            }
            assertFalse(lang.getString(WinterSwitches.labelKey("rankings.enabled"), "").isBlank(), code);
            assertFalse(lang.getString(WinterSwitches.descriptionKey("rankings.enabled"), "").isBlank(), code);
        }
        assertEquals("Rankings", YamlConfiguration.loadConfiguration(new InputStreamReader(
                WinterRankingsTest.class.getResourceAsStream("/lang/en.yml"), StandardCharsets.UTF_8)).getString("switch-names.rankings"));
        assertTrue(Files.readString(Path.of("src/main/java/at/vystorm/winter/WinterPlugin.java")).contains("CoreApi.atLeast(\"0.65.0\")"), "the minimum Core version stays");
    }

    /** Only WinterRankings may name Core's ranking types: on an older Core a class that does fails to load. */
    @Test void givenTheSources_whenRead_thenOnlyWinterRankingsNamesCoresRankingTypes() throws Exception {
        List<String> offending = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("WinterRankings.java")) continue;
                String text = Files.readString(file);
                for (String type : List.of("RankingInfo", "RankingSource", "PlaceholderRanking", "removeRanking", "ranking("))
                    if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(type)).matcher(text).find()) offending.add(file.getFileName() + ": " + type);
            }
        }
        assertEquals(List.of(), offending);
    }

    private static WinterPlugin plugin(WinterSwitches switches, SnowballDuels.Settings snowball) throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class, CALLS_REAL_METHODS);
        set(plugin, "switches", switches);
        set(plugin, "snowballSettings", snowball);
        return plugin;
    }

    private static void set(WinterPlugin plugin, String name, Object value) throws Exception {
        var field = WinterPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
