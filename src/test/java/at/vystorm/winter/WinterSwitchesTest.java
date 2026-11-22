package at.vystorm.winter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.runtime.RuntimeContextHub;
import Vystorm.vystorm_core.worldevent.WorldEvents;
import at.vystorm.winter.WinterSwitches.Feature;
import at.vystorm.winter.fun.SnowballDuels;
import at.vystorm.winter.season.SeasonCalendar;
import at.vystorm.winter.storage.SledRuns;
import at.vystorm.winter.world.ColdModel;
import at.vystorm.winter.world.Sledding;
import at.vystorm.winter.world.SnowZones;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** The 1.24.0 feature switches: off stops the feature, on behaves as before, old configs keep working and gain the keys. */
class WinterSwitchesTest {
    @TempDir Path folder;

    private static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                WinterSwitchesTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static YamlConfiguration language(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                WinterSwitchesTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
    }

    /** The shipped config of 1.23.0: the same file without the keys that are new in 1.24.0. */
    private static YamlConfiguration oldConfig() {
        YamlConfiguration config = defaults();
        for (Feature feature : Feature.values()) {
            config.set(feature.path(), null);
            String parent = feature.path().substring(0, feature.path().length() - ".enabled".length());
            if (parent.contains(".") && config.getConfigurationSection(parent) != null
                    && config.getConfigurationSection(parent).getKeys(false).isEmpty()) config.set(parent, null);
        }
        for (String top : List.of("admin-menu", "date-tools", "world-events", "tutorial", "rankings")) config.set(top, null);
        return config;
    }

    private static YamlConfiguration with(String path, boolean value) {
        YamlConfiguration config = defaults();
        config.set(path, value);
        return config;
    }

    private static void set(WinterPlugin plugin, String name, Object value) throws Exception {
        var field = WinterPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private static WinterPlugin plugin(Lang lang, WinterSwitches switches) throws Exception {
        WinterPlugin plugin = mock(WinterPlugin.class, CALLS_REAL_METHODS);
        set(plugin, "lang", lang);
        set(plugin, "switches", switches);
        set(plugin, "snowballSettings", SnowballDuels.Settings.DEFAULTS);
        return plugin;
    }

    private static CommandSender admin() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        return sender;
    }

    // ---- shipped file, old files, descriptions -------------------------------------------------------------------

    @Test void givenShippedConfig_whenRead_thenEverySwitchIsABooleanAndOnByDefault() {
        YamlConfiguration config = defaults();
        for (String path : WinterSwitches.allPaths()) assertTrue(config.isBoolean(path), path);
        WinterSwitches read = WinterSwitches.read(config);
        for (Feature feature : Feature.values()) assertTrue(read.on(feature), feature.path());
        assertFalse(read.on("seasons.enabled"));
        assertFalse(read.on("snowfall.enabled"));
        assertTrue(read.on("cold.enabled"));
    }

    @Test void givenOldConfigWithoutNewKeys_whenRead_thenBehavesAsBefore() {
        YamlConfiguration old = oldConfig();
        for (Feature feature : Feature.values()) assertFalse(old.contains(feature.path()), feature.path());
        WinterSwitches read = WinterSwitches.read(old);
        for (Feature feature : Feature.values()) assertTrue(read.on(feature), feature.path());
        assertEquals(WinterSettings.driftStrength(defaults()), WinterSettings.driftStrength(old));
        assertEquals(WinterSettings.transitionBlocks(defaults()), WinterSettings.transitionBlocks(old));
        for (String key : List.of("holidays.advent", "holidays.christmas", "holidays.new-year", "holidays.fireworks"))
            assertTrue(WinterSettings.holiday(old, key), key);
        assertEquals(ColdModel.Settings.read(defaults()), ColdModel.Settings.read(old));
        assertEquals(SnowballDuels.Settings.read(defaults()), SnowballDuels.Settings.read(old));
    }

    @Test void givenOldConfigFile_whenMerged_thenGainsTheNewKeysWithCommentsAndKeepsValues() throws Exception {
        YamlConfiguration old = oldConfig();
        old.set("cold.warm-food", 0.25);
        File file = folder.resolve("config.yml").toFile();
        old.save(file);
        int added = WinterSwitches.addMissing(file, defaults());
        assertEquals(Feature.values().length, added);
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(file);
        for (Feature feature : Feature.values()) assertTrue(merged.getBoolean(feature.path(), false), feature.path());
        assertEquals(0.25, merged.getDouble("cold.warm-food"));
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("Master switch of the plugin's snow and ice"), "comment of snow.enabled");
        assertTrue(text.contains("Winter admin menu (/winter admin)"), "comment of admin-menu");
        try (var backups = Files.list(folder)) {
            assertEquals(1, backups.filter(p -> p.getFileName().toString().startsWith("config.yml.bak-")).count());
        }
        assertEquals(0, WinterSwitches.addMissing(file, defaults()), "second start adds nothing");
    }

    @Test void givenOwnerSwitchedSomethingOff_whenMerged_thenTheValueStays() throws Exception {
        YamlConfiguration config = defaults();
        config.set("snow.drifts.enabled", false);
        config.set("tutorial", null);
        File file = folder.resolve("config.yml").toFile();
        config.save(file);
        assertEquals(1, WinterSwitches.addMissing(file, defaults()));
        YamlConfiguration merged = YamlConfiguration.loadConfiguration(file);
        assertFalse(merged.getBoolean("snow.drifts.enabled"));
        assertTrue(merged.getBoolean("tutorial.enabled"));
    }

    @Test void givenEverySwitch_whenDescribed_thenAllSevenLanguagesHaveTheLine() {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            YamlConfiguration lang = language(code);
            for (String path : WinterSwitches.allPaths()) {
                String key = WinterSwitches.descriptionKey(path);
                assertFalse(lang.getString(key, "").isBlank(), code + " " + key);
            }
            assertFalse(lang.getString(WinterSwitches.OFF_MESSAGE, "").isBlank(), code + " off message");
            for (String key : WinterPlaceholders.KEYS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
        assertEquals("features.snow-spring-thaw", WinterSwitches.descriptionKey("snow.spring-thaw.enabled"));
        assertEquals("features.snow-cover-leaves", WinterSwitches.descriptionKey("snow.cover-leaves"));
    }

    // ---- snow and landscape --------------------------------------------------------------------------------------

    @Test void givenSnowOff_whenTerrainAsks_thenNothingNewFormsButAForcedThawStillClears() {
        WinterSwitches off = WinterSwitches.without(Feature.SNOW);
        assertFalse(off.laysSnow());
        assertFalse(off.touchesSnow(false));
        assertTrue(off.touchesSnow(true));
        assertTrue(WinterSwitches.DEFAULTS.laysSnow());
        assertTrue(WinterSwitches.DEFAULTS.touchesSnow(false));
    }

    @Test void givenSpringThawOff_whenPastThePeak_thenTheSeasonMeltsNothingUntilAForcedThaw() {
        WinterSwitches off = WinterSwitches.without(Feature.SPRING_THAW);
        assertTrue(off.holdsSeasonMelt(false, "THAW"));
        assertTrue(off.holdsSeasonMelt(false, "OFF"));
        assertFalse(off.holdsSeasonMelt(false, "WINTER"));
        assertFalse(off.holdsSeasonMelt(true, "THAW"), "/winter thaw still melts");
        assertFalse(WinterSwitches.DEFAULTS.holdsSeasonMelt(false, "THAW"));
    }

    @Test void givenDriftsOrSoftEdgesOff_whenRead_thenUniformDepthAndHardEdges() {
        assertEquals(0.45, WinterSettings.driftStrength(defaults()));
        assertEquals(4, WinterSettings.transitionBlocks(defaults()));
        assertEquals(0, WinterSettings.driftStrength(with("snow.drifts.enabled", false)));
        assertEquals(0, WinterSettings.transitionBlocks(with("snow.soft-edges.enabled", false)));
    }

    @Test void givenSpotsOff_whenSpotCommand_thenOffMessageAndOnListsSpots() throws Exception {
        Lang lang = mock(Lang.class);
        CommandSender sender = admin();
        plugin(lang, WinterSwitches.without(Feature.SPOTS)).commandSpot(sender, "list", null, null);
        verify(lang).send(sender, "feature-off");
        WinterPlugin on = plugin(lang, WinterSwitches.DEFAULTS);
        set(on, "zones", new SnowZones(folder.resolve("spots.yml"), AtomicFilesInterface.DEFAULT));
        on.commandSpot(sender, "list", null, null);
        verify(lang).send(sender, "spot-none");
    }

    // ---- holidays ------------------------------------------------------------------------------------------------

    @Test void givenHolidaysOff_whenRead_thenNoHolidayGiftOrFireworks() {
        YamlConfiguration off = with("holidays.enabled", false);
        for (String key : List.of("holidays.advent", "holidays.christmas", "holidays.new-year", "holidays.fireworks")) {
            assertTrue(WinterSettings.holiday(defaults(), key), key);
            assertFalse(WinterSettings.holiday(off, key), key);
        }
        assertFalse(WinterSettings.holiday(with("holidays.christmas", false), "holidays.christmas"), "single keys still work");
    }

    @Test void givenCountdownOff_whenLastSecondsOfTheYear_thenNoCountdown() {
        assertTrue(WinterPlugin.countdownShown(5, 12, true));
        assertFalse(WinterPlugin.countdownShown(5, 12, false));
        assertFalse(WinterPlugin.countdownShown(30, 12, true));
        assertFalse(WinterSwitches.read(with("holidays.countdown.enabled", false)).on(Feature.COUNTDOWN));
    }

    // ---- cold ----------------------------------------------------------------------------------------------------

    @Test void givenHotMealsOrWarmHandsOff_whenRead_thenFoodAndLanternsDoNothing() {
        ColdModel.Settings on = ColdModel.Settings.read(defaults());
        assertEquals(0.5, on.warmFood());
        assertEquals(Set.of(Material.LANTERN, Material.SOUL_LANTERN), on.handWarmers());
        ColdModel.Settings meals = ColdModel.Settings.read(with("cold.hot-meals.enabled", false));
        assertEquals(0, meals.warmFood());
        assertEquals(0.8, ColdModel.afterFood(0.8, Material.MUSHROOM_STEW, meals));
        assertTrue(ColdModel.Settings.read(with("cold.warm-hands.enabled", false)).handWarmers().isEmpty());
    }

    // ---- snowball fights -----------------------------------------------------------------------------------------

    @Test void givenHitChillOff_whenRead_thenNoChillOnAHit() {
        assertEquals(0.05, SnowballDuels.Settings.read(defaults()).chill());
        assertEquals(0, SnowballDuels.Settings.read(with("snowball-fight.hit-chill.enabled", false)).chill());
    }

    @Test void givenDuelsOff_whenDuelOrSurrender_thenOffMessage() throws Exception {
        Lang lang = mock(Lang.class);
        Player alex = mock(Player.class);
        WinterPlugin off = plugin(lang, WinterSwitches.without(Feature.DUELS));
        off.commandDuel(alex, "Sam");
        off.commandSurrender(alex);
        verify(lang, times(2)).send(alex, "feature-off");
        assertFalse(off.duelsOn());
        WinterPlugin on = plugin(lang, WinterSwitches.DEFAULTS);
        assertTrue(on.duelsOn());
        try (var bukkit = mockStatic(Bukkit.class)) {
            on.commandDuel(alex, "Sam");
            verify(lang).send(alex, "snowball-offline");
        }
        set(on, "snowballSettings", new SnowballDuels.Settings(false, 0.05, 10, 120, 16));
        assertFalse(on.duelsOn(), "duels need snowball-fight.enabled");
    }

    // ---- sledding ------------------------------------------------------------------------------------------------

    private static VehicleMoveEvent boatMove(Player rider) {
        Boat boat = mock(Boat.class);
        when(boat.getPassengers()).thenReturn(List.of(rider));
        Location at = mock(Location.class);
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(at.getBlock()).thenReturn(stone);
        when(at.clone()).thenReturn(at);
        when(at.subtract(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(at);
        when(boat.getLocation()).thenReturn(at);
        VehicleMoveEvent event = mock(VehicleMoveEvent.class);
        when(event.getVehicle()).thenReturn(boat);
        when(event.getTo()).thenReturn(at);
        when(event.getFrom()).thenReturn(at);
        return event;
    }

    @Test void givenSledRunsOrSleddingOff_whenRiding_thenNoClockRuns() {
        Sledding.Host host = mock(Sledding.Host.class);
        Player rider = mock(Player.class);
        when(rider.getUniqueId()).thenReturn(UUID.randomUUID());
        when(host.sledRuns()).thenReturn(null);
        when(host.sleddingOptions()).thenReturn(new Sledding.Options(true, 1.6, 0.7, false));
        Sledding sledding = new Sledding(null, host);
        sledding.move(boatMove(rider));
        verify(host, never()).sledRuns();
        when(host.sleddingOptions()).thenReturn(new Sledding.Options(false, 1.6, 0.7, true));
        sledding.move(boatMove(rider));
        verify(host, never()).sledRuns();
        when(host.sleddingOptions()).thenReturn(new Sledding.Options(true, 1.6, 0.7, true));
        sledding.move(boatMove(rider));
        verify(host).sledRuns();
        assertTrue(new Sledding.Options(true, 1, 1).timed(), "the old three-value options keep the runs on");
    }

    @Test void givenSledRunsOff_whenSledCommand_thenOffMessageAndOnAsBefore() throws Exception {
        Lang lang = mock(Lang.class);
        CommandSender sender = admin();
        plugin(lang, WinterSwitches.without(Feature.SLED_RUNS)).commandSled(sender, "list", null);
        verify(lang).send(sender, "feature-off");
        WinterPlugin on = plugin(lang, WinterSwitches.DEFAULTS);
        set(on, "sledRuns", new SledRuns(folder.resolve("sledruns.json"), AtomicFilesInterface.DEFAULT));
        on.commandSled(sender, "list", null);
        verify(lang).send(sender, "sled.none");
    }

    // ---- a feature that needs another switch ---------------------------------------------------------------------

    @Test void givenSnowballFightsOffButDuelsOn_whenDuelOrSurrender_thenTheAnswerNamesSnowballFights() throws Exception {
        Lang lang = mock(Lang.class);
        Player alex = mock(Player.class);
        when(lang.plain(alex, "switch-names.snowball-fight")).thenReturn("Snowball fights");
        WinterPlugin plugin = plugin(lang, WinterSwitches.DEFAULTS);
        set(plugin, "snowballSettings", new SnowballDuels.Settings(false, 0.05, 10, 120, 16));
        plugin.commandDuel(alex, "Sam");
        plugin.commandSurrender(alex);
        verify(lang, times(2)).send(alex, "feature-needs", "name", "Snowball fights", "switch", "snowball-fight.enabled");
        verify(lang, never()).send(alex, "feature-off");
        WinterPlugin bothOff = plugin(lang, WinterSwitches.without(Feature.DUELS));
        set(bothOff, "snowballSettings", new SnowballDuels.Settings(false, 0.05, 10, 120, 16));
        bothOff.commandDuel(alex, "Sam");
        verify(lang).send(alex, "feature-off");
        verify(lang, times(2)).send(alex, "feature-needs", "name", "Snowball fights", "switch", "snowball-fight.enabled");
    }

    @Test void givenSleddingOffButRunsOn_whenSledCommand_thenTheAnswerNamesSledding() throws Exception {
        Lang lang = mock(Lang.class);
        CommandSender sender = admin();
        when(lang.plain(sender, "switch-names.sledding")).thenReturn("Sledding");
        WinterPlugin plugin = plugin(lang, WinterSwitches.read(with("sledding.enabled", false)));
        set(plugin, "sledRuns", new SledRuns(folder.resolve("sledruns.json"), AtomicFilesInterface.DEFAULT));
        plugin.commandSled(sender, "list", null);
        verify(lang).send(sender, "feature-needs", "name", "Sledding", "switch", "sledding.enabled");
        verify(lang, never()).send(sender, "feature-off");
        verify(lang, never()).send(sender, "sled.none");
    }

    @Test void givenOnlyTheParentSwitchOff_whenTabCompleting_thenTheDependentCommandStaysListedSoItCanNameIt() throws Exception {
        YamlConfiguration config = with("snowball-fight.enabled", false);
        config.set("sledding.enabled", false);
        WinterPlugin plugin = plugin(mock(Lang.class), WinterSwitches.read(config));
        var spec = WinterPlugin.commandSpec(plugin);
        for (String name : List.of("duel", "surrender", "sled")) assertTrue(spec.children().get(name).visibleTo(null), name);
        assertFalse(spec.children().get("snowball").visibleTo(null), "snowball follows its own switch");
    }

    @Test void givenDateToolsOff_whenAdminMenuDateButtons_thenTheyNameDateTools() throws Exception {
        Lang lang = mock(Lang.class);
        when(lang.plain(any(CommandSender.class), anyString(), any(Object[].class))).thenAnswer(call -> {
            StringBuilder text = new StringBuilder();
            for (Object argument : call.getArguments()) if (!(argument instanceof CommandSender)) text.append(argument).append(' ');
            return text.toString().trim();
        });
        Player admin = mock(Player.class);
        when(admin.hasPermission(anyString())).thenReturn(true);
        when(admin.isOnline()).thenReturn(true);
        WinterPlugin plugin = plugin(lang, WinterSwitches.without(Feature.DATE_TOOLS));
        doReturn(true).when(plugin).isEnabled();
        var actions = WinterPanels.adminActions(plugin, Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface.DEFAULT, mock(WinterAdminMenu.class));
        for (var result : List.of(actions.run(admin, "auto"), actions.run(admin, "winter"), actions.date(admin, "2026-12-24"))) {
            assertFalse(result.success());
            assertTrue(result.message().contains("feature-needs") && result.message().contains("date-tools.enabled")
                    && result.message().contains("switch-names.date-tools"), result.message());
        }
        verify(plugin, never()).commandResume(any());
        verify(plugin, never()).commandSetDate(any(), any());
        assertNull(plugin(lang, WinterSwitches.DEFAULTS).needsText(admin, Feature.DATE_TOOLS.path()), "date tools on: no message");
    }

    // ---- administration ------------------------------------------------------------------------------------------

    @Test void givenAdminMenuOff_whenCommand_thenOffMessageAndOnOpens() throws Exception {
        Lang lang = mock(Lang.class);
        Player admin = mock(Player.class);
        WinterAdminMenu menu = mock(WinterAdminMenu.class);
        WinterPlugin off = plugin(lang, WinterSwitches.without(Feature.ADMIN_MENU));
        set(off, "adminMenu", menu);
        off.commandAdmin(admin);
        verify(lang).send(admin, "feature-off");
        verify(menu, never()).open(any());
        assertFalse(WinterPanels.adminMenuOn(off));
        WinterPlugin on = plugin(lang, WinterSwitches.DEFAULTS);
        set(on, "adminMenu", menu);
        on.commandAdmin(admin);
        verify(menu).open(admin);
        assertTrue(WinterPanels.adminMenuOn(on));
    }

    @Test void givenDateToolsOff_whenPreviewSetdateResume_thenOffMessageAndNoChange() throws Exception {
        Lang lang = mock(Lang.class);
        CommandSender sender = admin();
        WinterPlugin off = plugin(lang, WinterSwitches.without(Feature.DATE_TOOLS));
        off.commandPreview(sender, "2026-12-24");
        off.commandSetDate(sender, "2026-12-24");
        off.commandResume(sender);
        verify(lang, times(3)).send(sender, "feature-off");
        WinterPlugin on = plugin(lang, WinterSwitches.DEFAULTS);
        on.commandPreview(sender, "not-a-date");
        verify(lang).send(sender, "invalid-date", "input", "not-a-date");
    }

    @Test void givenWorldEventsOff_whenTicked_thenNoEventAndNoStateForCore() throws Exception {
        API core = mock(API.class, RETURNS_DEEP_STUBS);
        RuntimeContextHub context = mock(RuntimeContextHub.class);
        WorldEvents.Domain domain = mock(WorldEvents.Domain.class, RETURNS_DEEP_STUBS);
        when(core.runtime().context()).thenReturn(context);
        when(core.events().domain(any(), anyString())).thenReturn(domain);
        when(domain.runtime().activeEvents()).thenReturn(List.of());
        WinterPlugin plugin = plugin(mock(Lang.class), WinterSwitches.without(Feature.WORLD_EVENTS));
        UUID world = UUID.randomUUID();
        set(plugin, "activeWorlds", Set.of(world));
        set(plugin, "season", new SeasonCalendar.State(1, "WINTER", 2026, "NONE", 0));
        set(plugin, "holiday", "NONE");
        WinterEvents events = new WinterEvents(plugin, core);
        ArgumentCaptor<RuntimeContextHub.Provider> provider = ArgumentCaptor.forClass(RuntimeContextHub.Provider.class);
        verify(context).register(any(), provider.capture());
        events.tick();
        verify(domain, never()).start(anyString(), any(), anyString());
        var query = RuntimeContextHub.Query.world(world, 0, 64, 0);
        assertNull(provider.getValue().snapshot(RuntimeContextHub.Channel.WORLD_EVENT, query));
        set(plugin, "switches", WinterSwitches.DEFAULTS);
        assertNotNull(provider.getValue().snapshot(RuntimeContextHub.Channel.WORLD_EVENT, query), "on: winter state as before");
    }

    @Test void givenTutorialSwitch_whenRead_thenItIsTheRestartOnlySwitch() {
        assertFalse(WinterSwitches.read(with("tutorial.enabled", false)).on(Feature.TUTORIAL));
        assertTrue(WinterSwitches.read(defaults()).on(Feature.TUTORIAL));
        assertTrue(defaults().getComments("tutorial").stream().anyMatch(c -> c != null && c.contains("restart")));
    }

    // ---- command tree --------------------------------------------------------------------------------------------

    @Test void givenFeaturesSwitched_whenTabCompleting_thenTheSameTreeFollowsTheSwitchAtOnce() throws Exception {
        YamlConfiguration config = with("storms.enabled", false);
        for (Feature feature : List.of(Feature.ADMIN_MENU, Feature.DATE_TOOLS, Feature.SPOTS, Feature.SLED_RUNS, Feature.DUELS))
            config.set(feature.path(), false);
        WinterPlugin plugin = plugin(mock(Lang.class), WinterSwitches.read(config));
        var spec = WinterPlugin.commandSpec(plugin);
        List<String> switched = List.of("admin", "preview", "setdate", "resume", "spot", "sled", "duel", "surrender", "storm");
        for (String name : switched) {
            assertFalse(spec.children().get(name).visibleTo(null), name);
            assertFalse(spec.children().get(name).hidden(), name + " is not hidden for good");
        }
        for (String shown : List.of("status", "thaw", "reload", "claim", "calendar", "snowball", "warmth"))
            assertTrue(spec.children().get(shown).visibleTo(null), shown);
        set(plugin, "switches", WinterSwitches.DEFAULTS);
        for (String name : switched) assertTrue(spec.children().get(name).visibleTo(null), name + " after switching on");
        assertTrue(spec.children().values().stream().filter(c -> !c.name().equals("snowfall")).allMatch(c -> c.visibleTo(null)), "defaults: everything listed");
        assertFalse(spec.children().get("snowfall").visibleTo(null), "snowfall ships switched off");
        set(plugin, "switches", WinterSwitches.read(with("snowfall.enabled", true)));
        assertTrue(spec.children().get("snowfall").visibleTo(null), "snowfall switched on");
    }

    @Test void givenReload_whenASwitchChanged_thenCommandListsAreRefreshedOnce() {
        var commands = mock(Vystorm.vystorm_core.commands.CoreCommandsInterface.class);
        WinterPlugin.switchesReloaded(commands, WinterSwitches.DEFAULTS, WinterSwitches.read(defaults()));
        verify(commands, never()).refresh();
        WinterPlugin.switchesReloaded(commands, WinterSwitches.DEFAULTS, WinterSwitches.without(Feature.DUELS));
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }
}
