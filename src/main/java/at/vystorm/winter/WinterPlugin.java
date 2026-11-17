package at.vystorm.winter;

import at.vystorm.winter.season.SeasonClock;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import at.vystorm.winter.season.SeasonCalendar;
import at.vystorm.winter.season.HolidayRules;
import at.vystorm.winter.season.AdventCalendar;
import at.vystorm.winter.fun.SnowballDuels;
import Vystorm.vystorm_core.item.RewardPouch;
import at.vystorm.winter.storage.*;
import at.vystorm.winter.world.*;
import java.time.*;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class WinterPlugin extends JavaPlugin {
    private WinterSettings settings;
    private WinterState state;
    private BlockJournal journal;
    private WinterTerrain terrain;
    private WinterWeather weather;
    private at.vystorm.winter.world.WinterStorms storms;
    private WinterEvents events;
    private WinterCold coldLoop;
    private WarmthReserve.Settings reserve = WarmthReserve.Settings.DEFAULTS;
    private ColdModel.Settings cold = ColdModel.Settings.DEFAULTS;
    private AdventCalendar.Settings calendarSettings;
    private CalendarStore calendarStore;
    private at.vystorm.winter.world.WinterLamps lamps;
    private WinterAdminMenu adminMenu;
    private CalendarMenu calendar;
    private SnowballDuels.Settings snowballSettings = SnowballDuels.Settings.DEFAULTS;
    private SnowballFight snowball;
    private at.vystorm.winter.storage.SledRuns sledRuns;
    private CommunityMissions missions;
    private at.vystorm.winter.world.Snowfall snowfall;
    /** The Core hub; set first thing in onEnable. */
    private API core;
    private TaskGroup tasks;
    private Lang lang;
    private RewardPouch gifts;
    private volatile SeasonCalendar.State season;
    private volatile Set<UUID> activeWorlds = Set.of();
    private volatile String holiday = "NONE";
    private long revision;
    private long generation;
    private boolean failed;
    private int seconds;
    private ZonedDateTime previousClock;
    @Override public void onEnable() {
        try {
            if (!CoreApi.atLeast("0.64.0")) throw new IllegalStateException("Vystorm Core 0.64.0 or newer is required");
            core = CoreApi.api().orElseThrow();
            core.admin().updates().spigot(this, 140367);
            saveDefaultConfig();
            try {
                core.settings().config().registerAll(this, () -> commandReload(getServer().getConsoleSender()), false);
            } catch (RuntimeException configMenuUnavailable) {
                getLogger().fine("Config menu unavailable: " + configMenuUnavailable);
            }
            settings = WinterSettings.read(getConfig());
            cold = ColdModel.Settings.read(getConfig());
            reserve = WarmthReserve.Settings.read(getConfig());
            calendarSettings = AdventCalendar.Settings.read(getConfig());
            snowballSettings = SnowballDuels.Settings.read(getConfig());
            try {
                int fixed = LangMigration.migrateFolder(core.data().files(), getDataFolder().toPath().resolve("lang"));
                if (fixed > 0) getLogger().info("Updated placeholders in " + fixed + " language file(s); backups end in .bak-1.2");
            } catch (java.io.IOException failure) {
                getLogger().warning("Could not update language placeholders: " + failure);
            }
            lang = core.lang().register(this);
            try {
                core.registerTutorial(this);
            } catch (RuntimeException noTutorial) {
                getLogger().fine("Tutorial not registered: " + noTutorial);
            }
            gifts = core.loot().pouches().open(this, core, "winter");
            state = new WinterState(getDataFolder().toPath().resolve("state.json"), core.data().files());
            journal = new BlockJournal(getDataFolder().toPath().resolve("blocks.jsonl"));
            zones = new at.vystorm.winter.world.SnowZones(getDataFolder().toPath().resolve("spots.yml"), core.data().files());
            configureZones();
            snowfall = new at.vystorm.winter.world.Snowfall(this, new at.vystorm.winter.world.Snowfall.Host() {
                public boolean winterActive(World world) {
                    return controls(world);
                }
                public boolean insideSpots(UUID world, double x, double z) {
                    return zones == null || zones.insideSpots(world, x, z);
                }
            }, core.settings().prefs());
            snowfall.configure(at.vystorm.winter.world.Snowfall.Settings.read(getConfig()));
            getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
                    snowfall.forget(event.getPlayer().getUniqueId());
                }
            }, this);
            terrain = new WinterTerrain(this, journal, core.area());
            weather = new WinterWeather(this, core.packages().clientBiomes());
            storms = new at.vystorm.winter.world.WinterStorms(this);
            refresh();
            events = new WinterEvents(this, core);
            coldLoop = new WinterCold(this, core.compat().platform());
            missions = new CommunityMissions(this, new MissionStore(getDataFolder().toPath().resolve("community-missions.json"), core.data().files()), CommunityMissions.Settings.read(getConfig()));
            getServer().getPluginManager().registerEvents(missions, this);
            calendarStore = new CalendarStore(getDataFolder().toPath().resolve("calendar.json"), core.data().files());
            lamps = new at.vystorm.winter.world.WinterLamps(this, new at.vystorm.winter.storage.LampStore(getDataFolder().toPath().resolve("lamps.json"), core.data().files()));
            getServer().getPluginManager().registerEvents(lamps, this);
            lamps.handleBlockEvents(core.priority().stages());
            adminMenu = new WinterAdminMenu(this, core);
            calendar = new CalendarMenu(this, core);
            try {
                WinterPanels.register(this, core, calendar, adminMenu);
            } catch (RuntimeException unavailable) {
                getLogger().info("Core panels unavailable; using chest menus: " + unavailable);
            }
            calendar.register();
            snowball = new SnowballFight(this, new SnowballScores(getDataFolder().toPath().resolve("snowball.json"), core.data().files()), core);
            snowball.registerPlayerMenu();
            at.vystorm.winter.world.WinterListeners blockAndWorldListeners = new WinterListeners(this, terrain);
            getServer().getPluginManager().registerEvents(blockAndWorldListeners, this);
            blockAndWorldListeners.handleBlockEvents(core.priority().stages());
            blockAndWorldListeners.handleTerrainEvents(core.priority().stages());
            getServer().getPluginManager().registerEvents(snowball, this);
            snowball.handleDamageEvents(core.priority().stages());
            getServer().getPluginManager().registerEvents(calendar, this);
            sledRuns = new at.vystorm.winter.storage.SledRuns(getDataFolder().toPath().resolve("sledruns.json"), core.data().files());
            getServer().getPluginManager().registerEvents(new at.vystorm.winter.world.Sledding(this, new at.vystorm.winter.world.Sledding.Host() {
                public at.vystorm.winter.world.Sledding.Options sleddingOptions() {
                    return sleddingOptions();
                }
                public at.vystorm.winter.storage.SledRuns sledRuns() {
                    return sledRuns;
                }
                public void sledMessage(Player player, String key, Object... placeholders) {
                    lang.send(player, key, placeholders);
                }
            }), this);
            core.command().register(this, lang, commandSpec(this));
            for (World world : getServer().getWorlds()) for (Chunk chunk : world.getLoadedChunks()) terrain.load(chunk);
            tasks = core.tasks().of(this, "WinterIsComing");
            startTasks();
            getLogger().info("WinterIsComing ready: " + season.phase() + ", journal=" + journal.size() + " blocks");
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "WinterIsComing could not start safely", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    private void startTasks() {
        tasks.timer(1, 1, () -> {
            if (!failed) terrain.cycle();
        });
        tasks.timer(5, 10, () -> coldLoop.tick());
        tasks.timer(3, 20, this::trackBuilders);
        tasks.timer(1, 20, () -> {
            if (!failed) {
                refresh();
                storms.tick();
                weather.tick();
                if (seconds++%30 == 0) events.tick();
                celebration();
                snowball.tick();
            }
        });
        tasks.timer(9, at.vystorm.winter.world.Snowfall.PERIOD_TICKS, () -> {
            if (!failed) snowfall.tick(Bukkit.getOnlinePlayers());
        });
        tasks.timer(600, 600, () -> snowball.save());
        tasks.timer(7, lamps.periodTicks(), () -> {
            if (!failed) lamps.tick();
        });
    }
    public WinterSettings settings() {
        return settings;
    }
    private at.vystorm.winter.world.SnowZones zones;
    /** Season spots and build mode: where the plugin may lay snow (1.20.0). */
    public at.vystorm.winter.world.SnowZones zones() {
        return zones;
    }
    void configureZones() {
        if (zones != null) zones.configure(getConfig().getBoolean("season-spots.only-inside", true), getConfig().getBoolean("build-mode.enabled", true) ? getConfig().getInt("build-mode.radius", 24) : 0);
    }
    private void trackBuilders() {
        if (zones == null) return;
        List<Object[]> list = new java.util.ArrayList<>();
        for (java.util.UUID id : List.copyOf(zones.buildModePlayers())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                zones.forget(id);
                continue;
            }
            list.add(new Object[]{player.getWorld().getUID(), player.getLocation().getX(), player.getLocation().getZ()});
            if (terrain != null) terrain.prioritize(player.getWorld().getUID(), player.getLocation().getX(), player.getLocation().getZ(), getConfig().getInt("build-mode.radius", 24) + 16);
            if (seconds % 10 == 0) lang.send(player, "build-active");
        }
        zones.updateBuilders(list);
    }
    void commandBuild(Player player) {
        if (!getConfig().getBoolean("build-mode.enabled", true)) {
            lang.send(player, "build-disabled");
            return;
        }
        boolean on = zones.toggleBuild(player.getUniqueId());
        lang.send(player, on ? "build-on" : "build-off", "radius", getConfig().getInt("build-mode.radius", 24));
        trackBuilders();
    }
    public at.vystorm.winter.world.Snowfall snowfall() {
        return snowfall;
    }
    /** Value for the admin slider: 0 when the falling snow is off. */
    int snowfallSlider() {
        var s = snowfall == null ? null : snowfall.settings();
        return s == null || !s.enabled() ? 0 : s.amount();
    }
    /** {@code /winter snowfall}: the player switches the falling snow around himself on or off. */
    void commandSnowfall(Player player) {
        if (snowfall == null || !snowfall.settings().enabled()) {
            lang.send(player, "snowfall-disabled");
            return;
        }
        lang.send(player, snowfall.toggle(player) ? "snowfall-on" : "snowfall-off");
    }
    void commandSpot(org.bukkit.command.CommandSender sender, String action, String name, Integer radius) {
        try {
            switch (action) {
                case "add" -> {
                    if (!(sender instanceof Player player)) {
                        lang.send(sender, "player-only");
                        return;
                    }
                    String key = name.toLowerCase(java.util.Locale.ROOT);
                    if (!key.matches("[a-z0-9_-]{1,32}")) {
                        lang.send(sender, "spot-invalid");
                        return;
                    }
                    int r = Math.max(8, Math.min(2000, radius == null ? 64 : radius));
                    zones.add(key, new at.vystorm.winter.world.SnowZones.Spot(player.getWorld().getUID(), player.getLocation().getBlockX(), player.getLocation().getBlockZ(), r));
                    lang.send(sender, "spot-added", "name", key, "radius", r);
                    if (terrain != null) terrain.prioritize(player.getWorld().getUID(), 0, 0, -1);
                }
                case "remove" -> lang.send(sender, zones.remove(name.toLowerCase(java.util.Locale.ROOT)) ? "spot-removed" : "spot-missing", "name", name);
                default -> {
                    if (zones.spots().isEmpty()) {
                        lang.send(sender, "spot-none");
                        return;
                    }
                    lang.send(sender, "spot-list", "spots", String.join(", ", zones.spots().entrySet().stream().map(e -> e.getKey() + " (" + e.getValue().x() + ", " + e.getValue().z() + ", r" + e.getValue().radius() + ")").toList()));
                }
            }
        } catch (java.io.IOException failure) {
            storageFailure(failure);
        }
    }
    public WinterState state() {
        return state;
    }
    public WinterTerrain terrain() {
        return terrain;
    }
    public WinterWeather weather() {
        return weather;
    }
    public at.vystorm.winter.world.WinterStorms storms() {
        return storms;
    }
    public WinterCold coldLoop() {
        return coldLoop;
    }
    public ColdModel.Settings cold() {
        return cold;
    }
    public WarmthReserve.Settings reserve() {
        return reserve;
    }
    public AdventCalendar.Settings calendarSettings() {
        return calendarSettings;
    }
    public CalendarStore calendarStore() {
        return calendarStore;
    }
    public at.vystorm.winter.world.WinterLamps lamps() {
        return lamps;
    }
    // The clock is read per block by the season terrain loop; rebuild it only when its three settings change (1.19.1).
    private SeasonClock clockCache;
    private String clockKey;
    public SeasonClock clock() {
        boolean enabled = getConfig().getBoolean("seasons.enabled", false);
        String mode = getConfig().getString("seasons.mode", "real"), start = getConfig().getString("seasons.start", "2026-01-05");
        String key = enabled + "|" + mode + "|" + start;
        if (clockCache == null || !key.equals(clockKey)) {
            clockCache = new SeasonClock(enabled, SeasonClock.Mode.parse(mode), SeasonClock.anchor(start));
            clockKey = key;
        }
        return clockCache;
    }
    public SeasonClock.Now seasonNow() {
        return clock().at(date(), season.intensity() > 0);
    }
    /** Admin menu: jumps to the first day of the following season. */
    void nextSeason(Player player) {
        LocalDate today = date();
        commandSetDate(player, clock().next(today, season.intensity() > 0, settings.calendar().nextStart(today)).toString());
        lang.send(player, "season-now", "season", lang.text(player, "season-name-" + seasonNow().season().name().toLowerCase(java.util.Locale.ROOT)));
    }
    String phaseName() {
        return season.phase();
    }
    String modeName() {
        return failed ? "FAULTED" : state.data().thaw ? "THAW" : state.data().manualDate == null ? "CALENDAR" : "MANUAL";
    }
    long strengthPercent() {
        return Math.round(season.intensity() * 100);
    }
    boolean manualDate() {
        return state.data().manualDate != null;
    }
    public SnowballDuels.Settings snowballSettings() {
        return snowballSettings;
    }
    CalendarMenu calendarMenu() {
        return calendar;
    }
    public boolean faulted() {
        return failed;
    }
    public Lang lang() {
        return lang;
    }
    public RewardPouch gifts() {
        return gifts;
    }
    public CommunityMissions missions() {
        return missions;
    }
    public SeasonCalendar.State season() {
        return season;
    }
    public String holiday() {
        return holiday;
    }
    public Set<UUID> activeWorldIds() {
        return activeWorlds;
    }
    public long revision() {
        return revision;
    }
    public long generation() {
        return generation;
    }
    public boolean controls(World world) {
        return !failed && settings.includes(world) && season.intensity() > 0 && !state.data().thaw;
    }
    public LocalDate date() {
        return state.data().manualDate == null ? LocalDate.now(settings.zone()) : LocalDate.parse(state.data().manualDate);
    }
    public void refresh() {
        LocalDate date = date();
        var next = settings.calendar().at(date);
        var clock = clock();
        if (clock.enabled() && clock.mode() != SeasonClock.Mode.REAL) {
            var now = clock.at(date, false);
            var run = now.season() == SeasonClock.Season.WINTER ? settings.calendar().at(settings.calendar().winterDate(now.progress())) : new SeasonCalendar.State(0, "OFF", 0, null, 0);
            next = new SeasonCalendar.State(run.intensity(), run.phase(), next.seasonYear(), next.holiday(), next.adventWeek());
        }
        if (state.data().thaw) next = new SeasonCalendar.State(0, "THAW", next.seasonYear(), next.holiday(), next.adventWeek());
        season = next;
        holiday = holidayFor(date, next);
        revision++;
        Set<UUID> ids = new HashSet<>();
        for (World world : Bukkit.getWorlds()) if (controls(world)) ids.add(world.getUID());
        activeWorlds = Set.copyOf(ids);
    }
    private String holidayFor(LocalDate date, SeasonCalendar.State s) {
        MonthDay md = MonthDay.from(date);
        boolean range = settings.christmasStart().compareTo(settings.christmasEnd()) <= 0
                ? md.compareTo(settings.christmasStart()) >= 0 && md.compareTo(settings.christmasEnd()) <= 0
                : md.compareTo(settings.christmasStart()) >= 0 || md.compareTo(settings.christmasEnd()) <= 0;
        if (settings.christmas() && range) return "CHRISTMAS";
        if (settings.newYear() && s.holiday().equals("NEW_YEAR")) return "NEW_YEAR";
        if (settings.advent() && s.holiday().equals("ADVENT")) return "ADVENT";
        return "NONE";
    }
    public void storageFailure(Exception failure) {
        if (failed) return;
        failed = true;
        getLogger().log(Level.SEVERE, "Winter storage failed; world edits, events and rewards stopped", failure);
        if (weather != null) weather.close();
        if (storms != null) storms.close();
        if (events != null) events.close();
        if (tasks != null) tasks.cancel();
        if (coldLoop != null) coldLoop.close();
    }
    private void celebration() {
        ZonedDateTime now = ZonedDateTime.now(settings.zone());
        ZonedDateTime previous = previousClock;
        previousClock = now;
        if (previous == null || state.data().manualDate != null || !settings.newYear()) return;
        ZonedDateTime midnight = LocalDate.of(now.getYear() + (now.getMonthValue() == 12 ? 1 : 0), 1, 1).atStartOfDay(settings.zone());
        long remaining = Duration.between(now, midnight).getSeconds();
        if (remaining >= 0 && remaining <= 10 && now.getMonthValue() == 12)
            for (Player player : Bukkit.getOnlinePlayers()) if (settings.includes(player.getWorld())) lang.send(player, "countdown", "seconds", remaining + 1);
        boolean celebrate = false;
        if (HolidayRules.crossedNewYear(previous, now)) {
            try {
                celebrate = state.celebrate(now.getYear());
            } catch (RuntimeException failure) {
                storageFailure(failure);
                return;
            }
        }
        if (celebrate) {
            for (Player player : Bukkit.getOnlinePlayers()) if (settings.includes(player.getWorld())) lang.send(player, "new-year");
            if (settings.fireworks()) for (World world : Bukkit.getWorlds()) if (settings.includes(world)) {
                Location at = world.getSpawnLocation().add(0, 12, 0);
                if (world.isChunkLoaded(at.getBlockX()>>4, at.getBlockZ()>>4)) {
                    world.spawnParticle(Particle.FIREWORK, at, 160, 6, 5, 6, 0.12);
                    world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 2, 1);
                }
            }
        }
    }
    // ---- command --------------------------------------------------------------------------------------------------

    /**
     * {@code /winter} as a Vystorm Core command tree (Core 0.45.0): names, aliases, usage and help come from
     * {@code commands.winter.*} in the language files ({@code /winterzeit kalender}, {@code /冬季 雪球}); English always
     * works, also from the console and for the acceptance harness. Without arguments it shows the status. {@code p} may be
     * a mock or {@code null} in tests that only look at names and help.
     */
    static Vystorm.vystorm_core.commands.CommandSpec commandSpec(WinterPlugin p) {
        return Vystorm.vystorm_core.commands.CommandSpec.command("winter")
                .executes(ctx -> p.commandStatus(ctx.sender()))
                .sub("status", s -> s.executes(ctx -> p.commandStatus(ctx.sender())))
                .sub("missions", s -> s.permission("winter.missions").playerOnly().executes(ctx -> p.guarded(ctx.sender(), () -> p.missions.show(ctx.player()))))
                .sub("warmth", s -> s.playerOnly().executes(ctx -> p.commandWarmth(ctx.player())))
                .sub("preview", s -> s.permission("winter.admin").arg(dateArg(p)).executes(ctx -> p.commandPreview(ctx.sender(), ctx.get("date"))))
                .sub("setdate", s -> s.permission("winter.admin").arg(dateArg(p)).executes(ctx -> p.commandSetDate(ctx.sender(), ctx.get("date"))))
                .sub("resume", s -> s.permission("winter.admin").executes(ctx -> p.commandResume(ctx.sender())))
                .sub("thaw", s -> s.permission("winter.admin").executes(ctx -> p.commandThaw(ctx.sender())))
                .sub("admin", s -> s.permission("winter.admin").playerOnly().executes(ctx -> p.guarded(ctx.sender(), () -> p.adminMenu.open(ctx.player()))))
                .sub("lamp", s -> s.permission("winter.admin").playerOnly().executes(ctx -> p.guarded(ctx.sender(), () -> p.lamps.toggle(ctx.player()))))
                .sub("reload", s -> s.permission("winter.admin").executes(ctx -> p.commandReload(ctx.sender())))
                .sub("claim", s -> s.executes(ctx -> p.claim(ctx.sender())))
                .sub("claims", s -> s.executes(ctx -> p.commandClaims(ctx.sender())))
                .sub("calendar", s -> s.permission(CalendarMenu.PERMISSION).playerOnly().executes(ctx -> p.commandCalendar(ctx.player())))
                .sub("snowball", s -> s.permission(SnowballFight.PERMISSION).playerOnly().executes(ctx -> p.commandSnowball(ctx.player())))
                .sub("duel", s -> s.permission(SnowballFight.PERMISSION).playerOnly()
                        .arg(Vystorm.vystorm_core.commands.Arg.word("player").suggests((ctx, prefix) -> {
                            Player player = ctx.player();
                            return Bukkit.getOnlinePlayers().stream().filter(o -> o != player && player.canSee(o)).map(Player::getName).sorted().toList();
                        }))
                        .executes(ctx -> p.commandDuel(ctx.player(), ctx.get("player"))))
                .sub("surrender", s -> s.permission(SnowballFight.PERMISSION).playerOnly().executes(ctx -> p.commandSurrender(ctx.player())))
                .sub("storm", s -> s.executes(ctx -> p.commandStorm(ctx.sender(), null))
                        .sub("snowstorm", l -> l.permission("winter.admin").executes(ctx -> p.commandStorm(ctx.sender(), "snowstorm")))
                        .sub("blizzard", l -> l.permission("winter.admin").executes(ctx -> p.commandStorm(ctx.sender(), "blizzard")))
                        .sub("stop", l -> l.permission("winter.admin").executes(ctx -> p.commandStorm(ctx.sender(), "stop"))))
                .sub("build", s -> s.permission("winter.build").playerOnly().executes(ctx -> p.commandBuild(ctx.player())))
                .sub("snowfall", s -> s.permission("winter.snowfall").playerOnly().executes(ctx -> p.commandSnowfall(ctx.player())))
                .sub("spot", s -> s.permission("winter.admin").executes(ctx -> p.commandSpot(ctx.sender(), "list", null, null))
                        .sub("list", l -> l.executes(ctx -> p.commandSpot(ctx.sender(), "list", null, null)))
                        .sub("add", l -> l.playerOnly().arg(Vystorm.vystorm_core.commands.Arg.word("spot")).arg(Vystorm.vystorm_core.commands.Arg.integer("radius", 8, 2000).optional())
                                .executes(ctx -> p.commandSpot(ctx.sender(), "add", ctx.get("spot"), ctx.<Integer>getOr("radius", null))))
                        .sub("remove", l -> l.arg(Vystorm.vystorm_core.commands.Arg.word("spot").suggests((ctx, prefix) -> p.zones == null ? List.of() : List.copyOf(p.zones.spots().keySet()))).executes(ctx -> p.commandSpot(ctx.sender(), "remove", ctx.get("spot"), null))))
                .sub("sled", s -> s.permission("winter.sled").executes(ctx -> p.commandSled(ctx.sender(), "list", null))
                        .sub("list", l -> l.executes(ctx -> p.commandSled(ctx.sender(), "list", null)))
                        .sub("top", l -> l.arg(sledArg(p)).executes(ctx -> p.commandSled(ctx.sender(), "top", ctx.get("run"))))
                        .sub("create", l -> l.permission("winter.admin").arg(Vystorm.vystorm_core.commands.Arg.word("run")).executes(ctx -> p.commandSled(ctx.sender(), "create", ctx.get("run"))))
                        .sub("finish", l -> l.permission("winter.admin").arg(sledArg(p)).executes(ctx -> p.commandSled(ctx.sender(), "finish", ctx.get("run"))))
                        .sub("delete", l -> l.permission("winter.admin").arg(sledArg(p)).executes(ctx -> p.commandSled(ctx.sender(), "delete", ctx.get("run")))))
                .build();
    }
    private static Vystorm.vystorm_core.commands.Arg<String> sledArg(WinterPlugin p) {
        return Vystorm.vystorm_core.commands.Arg.word("run").suggests((ctx, prefix) -> p == null || p.sledRuns == null ? List.of() : List.copyOf(p.sledRuns.names()));
    }
    private static Vystorm.vystorm_core.commands.Arg<String> dateArg(WinterPlugin p) {
        return Vystorm.vystorm_core.commands.Arg.word("date").suggests((ctx, prefix) -> p == null ? List.of() : p.dateOptions(prefix));
    }
    /** Today and the season boundaries for the date argument (empty before startup finished). */
    List<String> dateOptions(String prefix) {
        if (settings == null || state == null) return List.of();
        return dateSuggestions(settings.calendar(), date(), prefix);
    }
    /** Runs a command action with the old error handling: bad input → "invalid", storage trouble → "faulted". */
    private void guarded(CommandSender sender, Runnable action) {
        try {
            action.run();
        } catch (java.time.DateTimeException | IllegalArgumentException failure) {
            lang.send(sender, "invalid", "reason", failure.getMessage());
        } catch (RuntimeException failure) {
            storageFailure(failure);
            lang.send(sender, "faulted");
        }
    }
    void commandStatus(CommandSender sender) {
        guarded(sender, () -> {
            lang.send(sender, "status", "date", date(), "phase", season.phase(), "strength", Math.round(season.intensity() * 100), "holiday", holiday, "blocks", journal.size(), "mode", failed ? "FAULTED" : state.data().thaw ? "THAW" : state.data().manualDate == null ? "CALENDAR" : "MANUAL");
            if (zones != null && zones.limiting()) lang.send(sender, "status-spots", "count", zones.spots().size());
            if (snowfall != null) {
                var sf = snowfall.settings();
                if (sf.enabled()) lang.send(sender, "status-snowfall-on", "amount", sf.amount(), "radius", sf.radius());
                else lang.send(sender, "status-snowfall-off");
            }
            if (!failed && !state.data().thaw && season.phase().equals("OFF")) {
                LocalDate today = date(), next = settings.calendar().nextStart(today);
                lang.send(sender, "next-season", "date", next, "days", java.time.temporal.ChronoUnit.DAYS.between(today, next));
            }
            if (!failed && !state.data().thaw) {
                LocalDate today = date();
                settings.calendar().nextMilestone(today).ifPresent(m -> lang.send(sender, "milestone-" + m.name(), "date", m.date(),
                        "days", java.time.temporal.ChronoUnit.DAYS.between(today, m.date())));
            }
            if (sender instanceof Player player && coldLoop != null && cold.enabled() && coldLoop.level(player.getUniqueId()) > 0)
                lang.send(sender, "cold-status", "percent", Math.round(coldLoop.level(player.getUniqueId()) * 100));
            if (sender instanceof Player player && coldLoop != null && coldLoop.reserve(player.getUniqueId()) > 0)
                lang.send(sender, "warmth-status", "seconds", (int)Math.ceil(coldLoop.reserve(player.getUniqueId())));
        });
    }
    void commandWarmth(Player player) {
        guarded(player, () -> {
            if (failed || coldLoop == null) {
                lang.send(player, "faulted");
                return;
            }
            if (!cold.enabled()) {
                lang.send(player, "warmth-disabled");
                return;
            }
            lang.send(player, "warmth-overview", "cold", Math.round(coldLoop.level(player.getUniqueId()) * 100),
                    "seconds", (int)Math.ceil(coldLoop.reserve(player.getUniqueId())), "protection", coldLoop.protection(player) * 25);
            lang.send(player, "warmth-tip");
        });
    }
    void commandCalendar(Player player) {
        guarded(player, () -> calendar.open(player));
    }
    void commandSnowball(Player player) {
        guarded(player, () -> snowball.stats(player));
    }

    // ---- winter storms ---------------------------------------------------------------------------------------------
    /** {@code /winter storm}: the storm in your world (running or next); admins start one or stop it. */
    void commandStorm(CommandSender sender, String action) {
        if (storms == null || failed) {
            lang.send(sender, "faulted");
            return;
        }
        World world = sender instanceof Player player ? player.getWorld() : getServer().getWorlds().getFirst();
        if (action != null) {
            if (!admin(sender)) return;
            if (!controls(world) || !storms.options().enabled()) {
                lang.send(sender, "storm.not-here");
                return;
            }
            if (action.equals("stop")) {
                lang.send(sender, storms.stop(world) ? "storm.admin-stopped" : "storm.none-active");
                return;
            }
            storms.start(world, action.equals("blizzard") ? at.vystorm.winter.world.WinterStorms.Kind.BLIZZARD : at.vystorm.winter.world.WinterStorms.Kind.SNOWSTORM);
            lang.send(sender, "storm.admin-started", "kind", lang.plain(sender, "storm.kind." + action));
            return;
        }
        var kind = storms.active(world);
        long now = System.currentTimeMillis();
        var planned = storms.planned(world);
        if (kind.isPresent() && planned.isPresent()) lang.send(sender, "storm.status-active", "kind", lang.plain(sender, "storm.kind." + kind.get().key()), "minutes", Math.max(1, (planned.get().end() - now + 59_999) / 60_000));
        else if (planned.isPresent()) lang.send(sender, "storm.status-next", "minutes", Math.max(1, (planned.get().start() - now + 59_999) / 60_000));
        else lang.send(sender, "storm.status-none");
    }

    // ---- sledding ----------------------------------------------------------------------------------------------------

    at.vystorm.winter.world.Sledding.Options sleddingOptions() {
        return new at.vystorm.winter.world.Sledding.Options(getConfig().getBoolean("sledding.enabled", true),
                Math.clamp(getConfig().getDouble("sledding.speed-multiplier", 1.6), 1.0, 4.0), Math.clamp(getConfig().getDouble("sledding.max-speed", 0.7), 0.1, 2.0));
    }
    /** {@code /winter sled [list|top|create|finish|delete] [run]}. */
    void commandSled(CommandSender sender, String action, String rawName) {
        if (sledRuns == null) {
            lang.send(sender, "sled.unavailable");
            return;
        }
        String act = action == null ? "list" : action;
        String name = at.vystorm.winter.storage.SledRuns.name(rawName);
        switch (act) {
            case "list" -> {
                if (sledRuns.names().isEmpty()) {
                    lang.send(sender, "sled.none");
                    return;
                }
                lang.send(sender, "sled.list", "runs", String.join(", ", sledRuns.names()));
            }
            case "top" -> {
                var run = name == null ? null : sledRuns.run(name).orElse(null);
                if (run == null) {
                    lang.send(sender, "sled.unknown", "run", rawName == null ? "?" : rawName);
                    return;
                }
                if (run.best.isEmpty()) {
                    lang.send(sender, "sled.top-empty", "run", name);
                    return;
                }
                lang.send(sender, "sled.top-header", "run", name);
                for (int i = 0; i < run.best.size(); i++) lang.send(sender, "sled.top-line", "rank", i + 1, "player", run.best.get(i).name, "time", at.vystorm.winter.storage.SledRuns.format(run.best.get(i).millis));
            }
            case "create", "finish" -> {
                if (!sender.hasPermission("winter.admin")) {
                    lang.send(sender, "sled.no-permission");
                    return;
                }
                if (!(sender instanceof Player player)) {
                    lang.send(sender, "sled.players-only");
                    return;
                }
                if (name == null) {
                    lang.send(sender, "sled.bad-name");
                    return;
                }
                Location at = player.getLocation();
                boolean ok = act.equals("create") ? sledRuns.start(name, at.getWorld().getName(), at.getX(), at.getY(), at.getZ())
                        : sledRuns.finish(name, at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
                if (!ok) {
                    lang.send(sender, act.equals("create") ? "sled.limit" : "sled.finish-first", "run", name);
                    return;
                }
                lang.send(sender, act.equals("create") ? "sled.start-set" : "sled.finish-set", "run", name);
            }
            case "delete" -> {
                if (!sender.hasPermission("winter.admin")) {
                    lang.send(sender, "sled.no-permission");
                    return;
                }
                lang.send(sender, name != null && sledRuns.delete(name) ? "sled.deleted" : "sled.unknown", "run", rawName == null ? "?" : rawName);
            }
            default -> lang.send(sender, "sled.usage");
        }
    }
    void commandSurrender(Player player) {
        guarded(player, () -> snowball.surrender(player));
    }
    void commandDuel(Player player, String name) {
        guarded(player, () -> {
            Player target = Bukkit.getPlayerExact(name);
            if (target == null || !player.canSee(target)) lang.send(player, "snowball-offline");
            else snowball.challenge(player, target);
        });
    }
    /** Pending gifts are a player's own data: winter.claim is enough, admins only get the console hint. */
    void commandClaims(CommandSender sender) {
        guarded(sender, () -> {
            if (sender instanceof Player player && sender.hasPermission("winter.claim")) {
                if (failed) lang.send(sender, "faulted");
                else lang.send(sender, "claims", "pending", gifts.pending(player.getUniqueId()));
                return;
            }
            if (admin(sender)) lang.send(sender, "claims-console");
        });
    }
    /** The old admin gate: permission (also checked by the command tree) and no storage fault. */
    private boolean admin(CommandSender sender) {
        if (!sender.hasPermission("winter.admin")) {
            lang.send(sender, "denied");
            return false;
        }
        if (failed) {
            lang.send(sender, "faulted");
            return false;
        }
        return true;
    }
    void commandPreview(CommandSender sender, String input) {
        guarded(sender, () -> {
            if (!admin(sender)) return;
            LocalDate day = parseDate(input);
            if (day == null) {
                lang.send(sender, "invalid-date", "input", input);
                return;
            }
            var preview = settings.calendar().at(day);
            lang.send(sender, "preview", "date", day, "phase", preview.phase(), "strength", Math.round(preview.intensity() * 100), "holiday", holidayFor(day, preview));
        });
    }
    void commandSetDate(CommandSender sender, String input) {
        guarded(sender, () -> {
            if (!admin(sender)) return;
            LocalDate day = parseDate(input);
            if (day == null) {
                lang.send(sender, "invalid-date", "input", input);
                return;
            }
            state.mode(day.toString(), false);
            modeChanged(sender);
        });
    }
    void commandResume(CommandSender sender) {
        guarded(sender, () -> {
            if (admin(sender)) {
                state.mode(null, false);
                modeChanged(sender);
                if (season.phase().equals("OFF")) {
                    LocalDate today = date(), next = settings.calendar().nextStart(today);
                    lang.send(sender, "resume-off-season", "date", next, "days", java.time.temporal.ChronoUnit.DAYS.between(today, next));
                }
            }
        });
    }
    void commandThaw(CommandSender sender) {
        guarded(sender, () -> {
            if (admin(sender)) {
                state.mode(null, true);
                modeChanged(sender);
                lang.send(sender, "thaw-hint");
            }
        });
    }
    void commandReload(CommandSender sender) {
        guarded(sender, () -> {
            if (!admin(sender)) return;
            reloadConfig();
            configureZones();
            if (snowfall != null) snowfall.configure(at.vystorm.winter.world.Snowfall.Settings.read(getConfig()));
            WinterSettings proposed = WinterSettings.read(getConfig());
            ColdModel.Settings proposedCold = ColdModel.Settings.read(getConfig());
            WarmthReserve.Settings proposedReserve = WarmthReserve.Settings.read(getConfig());
            AdventCalendar.Settings proposedCalendar = AdventCalendar.Settings.read(getConfig());
            SnowballDuels.Settings proposedSnowball = SnowballDuels.Settings.read(getConfig());
            CommunityMissions.Settings proposedMissions = CommunityMissions.Settings.read(getConfig());
            snowball.save();
            if (failed) {
                lang.send(sender, "faulted");
                return;
            }
            generation++;
            weather.close();
            tasks.cancel();
            coldLoop.close();
            missions.configure(proposedMissions);
            reserve = proposedReserve;
            settings = proposed;
            cold = proposedCold;
            calendarSettings = proposedCalendar;
            snowballSettings = proposedSnowball;
            refresh();
            events.tick();
            startTasks();
            lang.send(sender, "reloaded");
        });
    }
    /** Strict ISO date (YYYY-MM-DD); null when the text is not a real calendar date. */
    static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException failure) {
            return null;
        }
    }
    /** Today plus the start, peak, thaw and end of the current or next season, filtered by prefix. */
    static List<String> dateSuggestions(SeasonCalendar calendar, LocalDate today, String prefix) {
        List<LocalDate> dates = new ArrayList<>();
        dates.add(today);
        dates.addAll(calendar.boundaries(today));
        return dates.stream().distinct().sorted().map(LocalDate::toString).filter(s -> s.startsWith(prefix)).toList();
    }
    private void modeChanged(CommandSender sender) {
        calendar.clearPreview();
        generation++;
        coldLoop.close();
        refresh();
        weather.close();
        events.tick();
        lang.send(sender, "changed");
    }
    void claim(CommandSender sender) {
        if (!(sender instanceof Player player) || !sender.hasPermission("winter.claim")) {
            lang.send(sender, "player-only");
            return;
        }
        if (failed) {
            lang.send(sender, "faulted");
            return;
        }
        try {
            missions.credit(player);
            // Administrative date previews never mint real calendar rewards.
            if (state.data().manualDate == null && settings.includes(player.getWorld()) && !holiday.equals("NONE")) {
                String key = HolidayRules.giftId(date(), holiday, season.adventWeek(), settings.christmasStart(), settings.christmasEnd());
                gifts.credit(player.getUniqueId(), key, new ItemStack(settings.reward(), settings.rewardAmount()));
            }
            switch (gifts.claim(player)) {
                case DELIVERED -> lang.send(sender, "gift");
                case EMPTY -> lang.send(sender, "no-gift");
                case FULL -> lang.send(sender, "full");
                case BUSY -> lang.send(sender, "gift-busy");
                case RECOVERY_REQUIRED -> lang.send(sender, "gift-recovery");
            }
        } catch (RuntimeException failure) {
            storageFailure(failure);
            lang.send(player, "faulted");
        }
    }
    @Override public void onDisable() {
        if (tasks != null) tasks.close();
        if (weather != null) weather.close();
        if (events != null) events.close();
        if (coldLoop != null) coldLoop.close();
        if (snowball != null) snowball.close();
        if (terrain != null) terrain.close();
        if (journal != null) try {
            journal.close();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not close winter journal", e);
        }
    }
}
