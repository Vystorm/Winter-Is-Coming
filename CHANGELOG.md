## 1.24.3 — 2026-11-20

- `/vadmin features` shows every Winter switch with a readable name in the admin's language, such as "Spring thaw" or "Snowball duels", together with its one-line description. Before, the screen built most names from the config keys.
- `/winter duel`, `/winter surrender` and `/winter sled` now say which switch they need. With `snowball-fight.enabled` or `sledding.enabled` off they answer "This needs “Snowball fights” (snowball-fight.enabled), which is switched off on this server." These commands stay in tab completion as long as their own switch is on.
- The date buttons and the lamp button of the admin menu name `date-tools.enabled` or `lamps.enabled` in the same way when that switch is off. Config keys and defaults are unchanged.

## 1.24.2 — 2026-11-19

- Snow near the coast keeps up with the calendar. Coastal frost now has its own budget: the search for the nearest shore no longer ends the snow pass of that tick, so worlds with sea or lakes in the loaded chunks get their snow as fast as worlds without water. Before, every search for a shore stopped the pass for the rest of that tick, and near the sea the snow could lag behind the date by many minutes.
- The shore search only looks as far as the frost reaches on that day. At low frost this is a few blocks instead of the whole `frost.ocean-radius`, so the frost costs the server much less early in the season. Ice still forms and melts on exactly the same water as before.
- Frost and snow share the `performance.changes-per-cycle` budget fairly: the frost may take up to a quarter of it while the snow is still growing and everything the snow leaves unused. No new settings.
- Winter no longer stops on a normal world. The plugin remembers every block it changed so the thaw can bring the original back. Until now this list was one file for the whole server (`blocks.jsonl`) with a fixed limit of 100000 positions: the area around a single player could reach it on the way to full winter, and then the snow simply stopped without a word. The list is now kept per chunk, inside the chunk itself, and only loaded chunks are held in memory (about 22 bytes per position instead of about 900, and about 5 bytes per position on disk instead of 350 bytes for every single change).
- `performance.max-tracked-blocks` is now a safety limit far above normal use: the default is 2000000, and the limit in use grows with the loaded chunks (256 per block of `snow.max-layers` plus 512 per loaded chunk), so an existing config with 100000 no longer limits the snow. If the limit is ever reached, the console says once "Winter stopped placing snow: tracking limit N reached; raise performance.max-tracked-blocks" and `/winter status` shows the same line.
- The thaw now gives the memory back: a position whose original block is back leaves the list, so "Tracked positions" in `/winter status` goes down while the snow melts and reaches 0 after a full thaw. Each position stores only its original block and the plugin's current block, no longer every snow layer it ever had.
- The old `blocks.jsonl` is read on the first start and its positions move into their chunks when those chunks load. A copy of the old file is kept as `blocks.jsonl.bak-1.24.1`. A chunk's positions leave the old file only after the chunk was saved with its new list and read back after a restart; the rest of the old file is rewritten in a compact form through a new file that replaces it in one step, so a crash at any moment loses no original block. `/winter status` shows how many old positions still wait for their chunk.
- Because the list is saved together with the chunk's blocks, a crash or a world rollback always returns both to the same moment. Natural snow and ice are never taken over or removed, and blocks that players broke or replaced stay theirs, as before.
- **Warning before going back to an older version:** run `/winter thaw` with 1.24.2 first and wait until "Tracked positions" in `/winter status` is 0. Older versions do not know the new per-chunk storage: 1.24.1 refuses to start with the new `performance.max-tracked-blocks` value, and an older version would leave the snow of 1.24.2 in the world because it cannot find the original blocks.

## 1.24.1 — 2026-11-19

- Early winter looks like early winter. The first snow now appears in patches that fill in over the first third of the season. The first layers now appear scattered over open ground, also on grass, ferns and flowers, which are covered with one layer as soon as their column gets snow. Before, small plants were only covered from four layers on and then at once with four layers, so at low frost the meadows stayed bare while the ground in front of every step and the roofs turned white in thick lines.
- Snow ramps in front of a step and on stepped roofs grow with the snow around them: the extra layers are at most as many as the snow already lying in that place, so there is no ramp on bare ground. At full winter ramps are as before.
- The terrain pass looks at the columns of a chunk in a shuffled order instead of row by row, so snow that is still growing lies scattered instead of in straight lines.
- With season spots (`season-spots.only-inside: true` and at least one spot in the world), all visible winter now stays inside the spots: the falling snow and the snowy biome colours, storms and the cold. The world outside keeps its own weather and colours. Before, only the snow and ice on the ground respected the spots.
- Snow that an older version laid too deep for the current date shrinks to the new depth during the normal passes; covered plants come back from the journal when their column has no snow yet.

## 1.24.0 — 2026-11-18

- Requires Vystorm Core 0.65.0. Update Core before you update this plugin.
- Every feature can now be switched on and off. All switches are listed in `/vadmin features` with a one-line description in all seven languages, and a change applies at once (only `tutorial.enabled` needs a restart). On the first start the new switches are written into your existing `config.yml`, with their comments; a backup of the old file is made first. A missing switch counts as on, so nothing changes until you switch something off.
- New switches for the snow: `snow.enabled` (master switch: off means no new snow or ice, and what lies stays until `/winter thaw`), `snow.spring-thaw.enabled` (off: the snow stays after the season until `/winter thaw` or the next winter), `snow.drifts.enabled`, `snow.soft-edges.enabled`, `season-spots.enabled`.
- New switches for holidays and cold: `holidays.enabled` (master for gifts, countdown and fireworks), `holidays.countdown.enabled`, `cold.hot-meals.enabled`, `cold.warm-hands.enabled`.
- New switches for the games: `snowball-fight.duels.enabled`, `snowball-fight.hit-chill.enabled`, `sledding.runs.enabled`.
- New switches for administration: `admin-menu.enabled`, `date-tools.enabled` (`/winter preview`, `setdate`, `resume`), `world-events.enabled` (winter events for Core's event catalogue), `tutorial.enabled`.
- Older keys keep their names and now also show in `/vadmin features`: `snow.cover-leaves`, `snow.cover-small-plants`, `snow.remove-tall-plants`, `snow.ramps`, `snow.cover-stairs-and-slabs`, `protection.protect-core-regions`, `weather.packet-snow`, `weather.warm-biome-particles`, `holidays.advent`, `holidays.christmas`, `holidays.new-year`, `holidays.fireworks`, `cold.damage`, `cold.breath`, `cold.warnings`, `advent-calendar.catch-up`, `advent-calendar.join-reminder`, `storms.darkness`. `season-spots.only-inside` is still read.
- A command of a switched-off feature answers "This feature is switched off on this server." It is also gone from tab completion and help while the switch is off. The player menu entry for duels and the admin panel disappear while their switch is off. Scores, sled times, spots, calendar doors and gifts in the pouch are kept.
- Placeholders: the plugin registers the namespace `winter` with 15 values (season phase and strength, holiday, date, days to the next milestone, cold and warmth of a player, snowball scores, Advent doors). Examples: `%vystorm_winter_phase%` and `{winter:cold}`. They work in Vystorm texts and, through PlaceholderAPI, in other plugins. A value of a switched-off feature is empty.
- Tab completion and help follow a switch at once, without registering the command again.
- Fix: `sledding.enabled: false` now also stops the timed sled runs; before, the clock kept running.

## 1.23.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`). The protection of the snow and ice that winter placed (melting, liquids, mobs, explosions and pistons) now denies before other plugins act.
- Lighter on the server: the settings `snow.ramps`, `snow.cover-stairs-and-slabs` and `snow.remove-tall-plants` are read once with the other settings instead of for every block. Behaviour is unchanged.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.22.1 — 2026-11-17

- Visible snowfall is easier to see: the default is now 200 flakes per second at flake size 1.5 (it was 60 at size 1.0, which was hardly visible), and the flakes stay close around the player instead of spreading far beyond the set radius. Servers that already set `snowfall.amount` or `snowfall.flake-size` keep their values.

## 1.22.0 — 2026-11-17

- **Visible snowfall:** white dust particles fall around every player, whatever the weather and the biome, and only that player sees them. It is off by default; switch it on with `snowfall.enabled: true` in `config.yml` and run `/winter reload` (it is also a switch in the Core config editor). Settings: `amount` (flakes per player per second, 1–400, default 60), `radius` (4–24 blocks) and `height` (3–16 blocks) of the snow box around the player, `flake-size` (0.5–2.0), `only-under-sky` (no flakes under a roof, default on) and `only-in-winter` (only while the plugin's winter is active in that world, default on). When season spots limit the snow, flakes only fall inside the spots.
- Players can switch the falling snow off or on for themselves with `/winter snowfall` (permission `winter.snowfall`, default true; the choice is remembered). `/winter status` shows whether snowfall is on, and the winter admin panel has a "Snowfall amount" slider (0 = off, up to 400).
- The "snow speed" slider only sets how fast snow grows on the ground; it does not change the falling snow. In all seven languages.

## 1.21.0 — 2026-11-16

- `/winter status` now tells admins when season spots limit the snow ("snow only falls inside N spots"), so a missing snowfall elsewhere is easy to explain. In all seven languages.

## 1.20.1 — 2026-11-15

- Season spots only limit the world they are in; other worlds keep snow everywhere.
- Snow outside the spots and around builders melts quickly instead of over hours: those chunks are checked first, and the snow is removed in one step instead of layer by layer. In build mode the plugin's ice nearby turns back into water too.

## 1.20.0 — 2026-11-15

- **Season spots:** snow can be limited to places instead of whole worlds. Admins set a circle where they stand with `/winter spot add NAME [RADIUS]` (8–2000 blocks, default 64). `/winter spot list` and `/winter spot remove NAME` manage the spots. Once one spot exists, the plugin's snow only falls inside the spots, and edges thin out softly (`season-spots.only-inside`).
- **Build mode:** `/winter build` lets players build without the plugin's snow. No new snow forms within 24 blocks of them, and the plugin's existing snow there melts away. Natural snow stays (`build-mode.radius`, permission `winter.build`).
- Rebuild the Advent calendar and winter admin menus as Core Panels, with door selection, progress, date and snow-speed controls, and thaw confirmation.
- Keep both original chest menus as fallbacks for clients or Core installations without Panels. No item-moving menus were found.

## 1.19.1 — 2026-11-14

- Performance: with seasons switched on, the season terrain pass re-read the season settings and parsed the start date for every single block. The season clock is now cached until its settings change and the season is worked out once per pass.

## 1.19.0 — 2026-11-12

- Winter weather events: now and then a winter world gets a snowstorm or a blizzard. Players are warned a few minutes before; during the storm players outside get colder faster, see dense snow and (blizzard) pulsing darkness, and the action bar tells them to find a roof or a fire. `/winter storm` shows the next or running storm; admins start one with `/winter storm snowstorm|blizzard` or end it with `/winter storm stop`. Weak early or late winter makes storms rarer. Settings under `storms`, also in the first-run setup.

## 1.18.0 — 2026-11-11

- Sledding: boats glide faster on snow and faster still downhill and on snow ramps, up to a speed cap (`sledding.*`). Admins build timed sled runs with `/winter sled create <name>` at the start and `/winter sled finish <name>` at the finish; crossing the start in a boat starts the clock, and `/winter sled top <name>` shows the ten best times.

## 1.17.0 — 2026-11-11

- Snow speed slider in /winter admin: 4 to 512 changes per second. All changes of a tick share one journal write.
- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- Snow spreads faster on new installs (16 changes per second instead of 4), with a setup question for the speed.

## 1.16.0 — 2026-11-09

- Year-round seasons (optional): real year, one season per month or per week. Spring grows grass and flowers, summer tall plants, autumn berry bushes. Plants under a roof and on farmland stay as they are.
- Snow now settles on top of stairs and bottom slabs. The blocks themselves are never changed.
- Fixed snow spreading far slower than `performance.changes-per-cycle` allows: only one change per cycle was made.
- Snow forms ramps towards one-block steps instead of lying flat.
- Tall plants disappear in winter and come back on thaw.

## 1.15.0 — 2026-11-09

- New `/winter admin` menu: follow the real calendar, jump to winter, choose a date, thaw, open all settings and place lamps without remembering commands.
- Test the Advent calendar: while a date is set, admins see the calendar on that date. Their doors give nothing and do not count.
- Snow now also covers single-block flowers and other small plants, not only short grass and ferns. They come back on thaw.
- Christmas lights: `/winter lamp` turns the glass block you look at into a colour-changing light during winter. Only nearby players see the colours; the world keeps the real glass.

## 1.14.2 — 2026-11-09

- Locked Advent doors now look locked (the first door looked like an opened one) and clicking one says when it opens.
- `/winter thaw` explains how to bring winter back; `/winter resume` outside the season says why there is no snow and how to test.

## 1.14.1 — 2026-11-08

- Preserve empty or invalid saved data for recovery instead of resetting it; migrate language files atomically.
- Stop winter work consistently after reward, score or New Year storage failures, including during reload.
- Ignore snowballs from Creative, Spectator and disconnected players, and preserve duel results once time expires.
- Pause cold and snowball fights during the seasonal thaw, and respect excluded biomes in fallback snow particles.
- Ignore Advent calendar clicks after disconnect and correct gift confirmations, recovery paths and translated messages.

## 1.14.0 — 2026-11-07

- Added community winter missions. `/winter missions` shows the whole server's progress toward seasonal snowball, hot-meal and Advent-calendar goals. Real accepted snowball hits, consumed stews and newly opened Advent doors contribute in enabled winter worlds. Cancelled events, Creative/Spectator play and administrator date overrides do not contribute.
- A mission reward storage failure during claim now halts further rewards and world edits safely.
- Added all seven languages, persistent progress, permission checks and focused regression tests. Existing Spigot/CraftBukkit and Paper paths remain supported.
- See `FEATURE_VALIDATION.md` for the tested build and native server flows.

## 1.13.1 (2026-11-07)

- Register the update checker against the newly published Winter Is Coming resource (Spigot 140367).
- Correct the compatibility documentation: Core 0.51.0 or newer is required.
- No gameplay or configuration changes from the validated 1.13.0 release.

## 1.13.0 (2026-11-07)

- Route rich messages, translated material names and freeze control through Core 0.51.0's Bukkit compatibility layer.
- Keep winter cold locked between updates on CraftBukkit/Spigot while preserving explicit warming, thawing and disconnect cleanup.
- Requires Vystorm Core 0.51.0 or newer. Actual server validation is recorded in release/VALIDATION.md.

## 1.12.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.10.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode and api-version 1.20.6; build toolchain remains JDK 25.
- Use the stable Keyed contract for biomes across the enum/interface API transition, preserving biome exclusions and shoreline detection.
- On 1.20.6, native client biome packet snow is unavailable in Core; the existing optional snow particles provide the fallback, now with one console notice. Terrain, cold/warmth, gifts, chest calendar and duels remain available. Custom materials must exist on the running server.
- Offline compat1206 compilation and compatibility tests against both old and current APIs are part of check. No new Core compat-package dependency or minimum Core version increase.

## 1.9.0 (2026-11-02)

- New: stored warmth for winter journeys. After fully warming up at an existing heat source, players charge a temporary reserve (20 seconds to fill, 60 seconds of ambient-cold protection by default). Away from heat it drains even indoors; partial charges work and normal cold recovery continues. Snowball chill and vanilla powder snow keep their existing behaviour.
- Configure with `cold.reserve.enabled`, `cold.reserve.charge-seconds` and `cold.reserve.duration-seconds`; older configs use the defaults. Localized full/empty hints respect `cold.warnings`, and `/winter status` shows remaining seconds. No new permissions. Reserves clear on logout, ineligibility, reload, date/mode changes and shutdown.

## 1.8.0 (2026-11-02)

- New: give up a running snowball duel with `/winter surrender` (`/winter aufgeben`, `/winter 认输`) without disconnecting. Your opponent receives one win, and both players get a localized result. Requires the existing `winter.snowball` permission; no new settings. A duel whose deadline has already passed keeps its normal timed result.

## 1.7.0 (unreleased)

- New: hand warmers. A lantern or soul lantern held in the main or off hand blocks as much winter cold as one leather armour piece (25%), so you can head out into the snow with a light instead of a full leather set. Configure the items with `cold.hand-warmers` (default `[LANTERN, SOUL_LANTERN]`; `[]` switches them off, unknown names are rejected on reload). The cold warning now mentions the lantern.

## 1.6.0 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation.
- `/winter` now runs through Vystorm Core's command system: subcommands, help, tab completion and error messages in the player's language (`/winter kalender`, `/winter schneeball`, `/winter 决斗 Alex` work next to the English names for everyone), a clickable `/winter help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/winterzeit` (German) and `/凛冬将至` (Chinese) from the language files (`commands.winter.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/winter` and all English subcommands and arguments work as before, also from the console. Admin subcommands (`preview`, `setdate`, `resume`, `thaw`, `reload`) are hidden from players without `winter.admin`. The command is no longer declared in plugin.yml; the old `usage` and `player-command` texts are gone (Core writes usage lines and errors).

## 1.5.0 (2026-10-30)

- New: Advent calendar. `/winter calendar` (also in the Core main menu, category World, and as a clickable hint on join) opens a chest menu with 24 doors; from December 1 to 24 each door holds a small vanilla gift. Missed doors can be opened later in December (`advent-calendar.catch-up`, default true). Gifts use the Core reward pouch with one ID per door and year (never paid twice; full inventories keep them for `/winter claim`). Real date only, winter worlds only. Configure the 24 rewards under `advent-calendar.rewards`; permission `winter.calendar` (default true). Opened doors are stored in `calendar.json`.
- New: snowball fights. Snowball hits on other players in winter worlds count for a seasonal leaderboard (`/winter snowball`: own hits, duel wins, rank and top 5) and chill the target a little (`snowball-fight.chill`, leather reduces it). Snowball duels via `/winter duel <player>` or the Core 0.43.0 player menu (sneak + right-click a player), with a clickable [Accept]: first to 10 hits or the leader after 120 seconds wins; both players are topped up to 16 snowballs. PvP/region protection that cancels a hit also cancels the point; no item rewards. Permission `winter.snowball` (default true); scores in `snowball.json`.
- Existing configs need no changes: missing `advent-calendar` and `snowball-fight` settings use the defaults.

## 1.4.0 (2026-10-29)

- New: hot meals warm you up. Eating mushroom stew, rabbit stew, beetroot soup or suspicious stew removes half of the full winter cold at once (action bar hint "The hot meal warms you up."). Configure with `cold.warm-food` (0..1, default 0.5; 0 disables). Missing in older configs means 0.5.
- Fixed: `/winter claims` (pending gifts in your reward pouch) required `winter.admin`, so normal players could not see their own pending gifts. It now only needs `winter.claim` and is offered in tab completion for players. Admins in the console still get the reward-records hint.

## 1.3.1 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

## 1.3.0 (2026-10-28)

- New: cold and warmth. In a controlled winter world, survival/adventure players outside slowly get cold: vanilla frost overlay, slight slowdown and visible breath clouds. Fire, lava, magma blocks and lit campfires/furnaces within 4 blocks warm them up quickly; roofs and caves (little sky light) let them recover slowly. Each leather armour piece blocks 25% of the cold, full leather makes players immune (like powder snow). Nights, snowfall and swimming are colder; cold scales with the season strength. No damage by default (`cold.damage: false` keeps players just below fully frozen); action bar hints when getting cold and when warming up. Vanilla powder snow keeps working as before. Configure under `cold` or set `cold.enabled: false`. `/winter status` shows your current cold level.
- Fixed: all messages with values (status, preview, countdown, claims, invalid date, next season) showed raw tags like `<date>` instead of the value, because Core's language system uses `{date}`. Bundled files are fixed, and existing copies in `plugins/WinterIsComing/lang/` are updated automatically on start (backup `*.yml.bak-1.2`).
- `/winter status` now shows the next milestone during the season: peak, thaw or end, with date and days left.

## 1.2.2 (2026-10-27)

- Tab-complete dates for /winter preview and /winter setdate: today plus the start, peak, thaw and end of the current or upcoming season.
- Invalid dates (for example 2026-02-30 or 24.12.2026) now show a clear YYYY-MM-DD hint instead of a raw Java parser message.
- Outside the season, /winter status also shows when the next winter starts and how many days remain.
- The Gradle build now defaults to libs/Vystorm_Core.jar; -PvystormCoreJar still overrides it.

## 1.2.1 (2026-10-26)

- Reject extra command arguments before thaw/resume/reload or gift actions execute. Date commands require exactly one date; malformed commands show usage without changing state.

## 1.2.0

- Use Vystorm Core 0.33.0 client-only biome packets for native snowfall in warm biomes; enable with weather.packet-snow (default true).
- Preserve server/saved biomes and spawning rules; honour configured biome exclusions. Vanilla snowy-biome colours apply to the client.
- Release the view on reload, season end, world change and disable. Restore current original biome data; reapply after reconnect and newly sent chunks.
- Keep optional snow particles as a fallback when packet snow is disabled or unavailable.
- Validate native client precipitation and real screenshots on Paper and Purpur 26.2, including reconnect and restoration.

## 1.1.0

- Snow covers supported leaf canopies and stacks across blocks, with 1–64 total layers and a new-install default of 12.
- Coherent configurable snow drifts and softer transitions inside protected/excluded-biome borders.
- Optional temporary covering of short grass and ferns with journaled restoration after thaw/restart; foreign replacements are preserved.
- Snow clears and thaws from the top of each owned stack downward.
- Existing max-layers values remain unchanged; set snow.max-layers to 12 to opt into the deeper default.

# 1.0.1

- Fix coastal frost fairness across multiple loaded chunks: exhausting the shore-search budget now ends the cycle instead of advancing past unexamined water columns.
- Add a five-phase server fixture and independent snapshot checks for continuous shoreline freezing, Core protection, and exact restoration after winter.
- Add the winter-themed Vystorm V resource icon.
- Update safely by replacing the Winter JAR and restarting; keep the existing config and block journal. No data migration or reset is needed.

# 1.0.0

- Calendar-based gradual snow, coastal/inland frost and thaw with explicit world and biome controls.
- Core region protection, owned event domain, seasonal SpawnDirector modifiers and localized administration.
- Durable asynchronous block intents, compare-before-apply checks, restart recovery and bounded loaded-chunk processing.
- Advent/Christmas/New Year gifts through Core RewardPouch; real-time New Year countdown and cosmetic fireworks.
- MIT-licensed initial free release for Paper/Purpur 26.2 and Java 25; requires Vystorm Core 0.32.0+.
