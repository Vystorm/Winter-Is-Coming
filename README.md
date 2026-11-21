# Winter Is Coming


## Wind-blown snow (1.25.0)

The snow comes in at an angle with the wind, so it also settles under roof edges, porches, bridges, overhangs and tree canopies that are open to the wind. The wind blows from one direction in every world, west unless you change it. How far the snow gets under a roof depends on the height of the roof. The snow is deepest at the windward edge and gets thinner towards the inside, so there is no hard line where it ends. It lies like a drift, not like stairs: every row along the edge has its own drift line, so in one row the snow stays deep for an extra block and in the next it thins out sooner, and these differences wander along the edge in soft waves a few blocks long. The pattern belongs to the place (the same blocks every time, also after a restart). With the default angle of 20° and `max-layers: 4`, the layers per block counted from the windward edge of a roof that is open on every side, the most common rows first:

| Free blocks under the roof | Snow reaches | Deep winter | About a quarter into the winter |
| --- | --- | --- | --- |
| 2 | 1 to 2 blocks | 2, 1 (sometimes 2) | 2, 1 (sometimes 2) |
| 3 | 2 to 3 blocks | 4, 2 / 4, 3, 1 / 4, 3, 2 | 3, 2, 1 / 3, 1 / 3, 2 |
| 5 | 2 to 4 blocks | 4, 2, 1 / 4, 3, 2, 1 / 4, 3, 1 | 3, 2, 1 / 3, 3, 2, 1 |
| 8 | 3 to 5 blocks | 4, 3, 2, 1 / 4, 4, 3, 2, 1 / 4, 2, 1 | 3, 3, 2, 1 / 3, 2, 1 / 3, 2, 2, 1 |
| 12 | 4 to 6 blocks | 4, 4, 3, 2, 1 / 4, 3, 2, 1 / 4, 3, 3, 2, 1 | 3, 2, 2, 1 / 3, 3, 2, 2, 1 |

The first block under the roof always gets the full depth; from one block to the next the snow never drops by more than 2 layers. Along the sides of a roof that face the wind at up to 45°, the snow also forms a drift from that side, so the corners are rounded. Early in the winter single blocks stay bare, as on open ground. A block that gets fewer paths of snow (for example near a post) can have less. Under a roof that is open on several sides, some snow also comes in from the sides that face the wind at up to 45°. Under an opening only two blocks high (a low porch or a doorway) there are never more than 2 layers, so a player can still walk in.

Never inside closed rooms. The plugin follows each path of the snow block by block and counts every block that touches the path, also at a corner or an edge, as a wall. Walls, glass, panes, bars, fences, slabs, stairs, doors and trapdoors (open or closed) keep the snow out, and so do leaves unless you set `leaves-block: false`. A house with a door and windows gets no snow inside, whichever way the wind blows. When a player closes a porch with blocks, its snow melts again within seconds; a space that is closed by commands or by another plugin loses its snow when the plugin's pass next visits that chunk.

```yaml
snow:
  wind-blown:
    enabled: true       # the switch "Wind-blown snow" in /vadmin features
    angle: 20           # 0..40 degrees from the vertical
    wind-from: west     # west, north, east, south or a compass degree (0 = north, 90 = east)
    max-height: 12      # 2..24: the highest roof or canopy the snow still gets under
    max-layers: 4       # 1..8 layers under a roof
    leaves-block: true  # leaves stop the snow, so under a tree it lies along the windward rim only
```

How the depth under a roof is worked out: the full depth is the depth the snow has on open ground at that moment, but at most `max-layers`, at most 7 when a block lies right above (the snow never touches it) and at most 2 under an opening two blocks high. Each row has a drift line: from the full depth at the first block it falls to nothing between the reach of a single path and two blocks further in; where exactly it ends, whether it stays deep first or falls early, and where its one-layer steps land come from fixed patterns of the position (smooth along the edge, so neighbouring rows are similar). The distance is counted straight into the wind, or along the sides at 45° when that is shorter. A block that a quarter or more of the paths reach gets the drift line's depth, at most that share of the full depth and at least one layer. Behind the last block the paths reach, snow drifts in from the block in front of it (on the windward side) if that block lies under the roof too: up to the drift line, and always at least one layer less than that block. That drift only moves from one free block to the next at the same height, never through a wall, door, pane, fence or across a corner, so closed rooms stay empty. Existing configs get these keys on the first start, with their comments (a backup of the old file is made first). `/winter status` shows whether wind-blown snow is on, the wind and how many paths were traced since the start.

What stays as it was: open ground keeps the same snow as before (there is no shadow behind walls), falling flakes (weather, client snow, particles) still fall straight down, and the thaw brings back every original block, under roofs as well. With the switch off, no new snow forms under roofs; snow that already lies there stays until it melts with the season or with `/winter thaw`.

The work stays small: only columns with lower ground on the windward side are looked at, at most 128 paths are traced per tick inside the existing pass, a path that would enter an unloaded chunk counts as blocked (no chunk is ever loaded for it), and nothing scans the area around players.

## Feature switches (1.24.0)

Every feature has its own on/off switch in `config.yml`. All switches are listed with a readable name and a one-line description in `/vadmin features` (Vystorm Core 0.65.0) and apply at once, without a restart. The only exception is `tutorial.enabled`, which needs a restart. New switches are written into an existing `config.yml` on the first start, with their comments (a backup `config.yml.bak-<time>` is made first). A missing key means "on", so a server keeps the behaviour it had.

- Master switch of the snow: `snow.enabled`. Off: no new snow or ice forms, and what lies stays as it is until `/winter thaw` (which still removes it and brings the original blocks back) or until the switch is on again.
- Snow details: `snow.spring-thaw.enabled`, `snow.drifts.enabled`, `snow.soft-edges.enabled`, `snow.wind-blown.enabled` (1.25.0), `season-spots.enabled`, and the older keys `snow.cover-leaves`, `snow.cover-small-plants`, `snow.remove-tall-plants`, `snow.ramps`, `snow.cover-stairs-and-slabs`, `protection.protect-core-regions`, `frost.enabled`, `build-mode.enabled`, `seasons.enabled`.
- Weather and cold: `weather.enabled`, `weather.packet-snow`, `weather.warm-biome-particles`, `snowfall.enabled`, `storms.enabled`, `storms.darkness`, `cold.enabled`, `cold.damage`, `cold.breath`, `cold.warnings`, `cold.hot-meals.enabled`, `cold.warm-hands.enabled`, `cold.reserve.enabled`.
- Holidays and games: `holidays.enabled` (master for gifts, countdown and fireworks), `holidays.countdown.enabled`, `holidays.advent`, `holidays.christmas`, `holidays.new-year`, `holidays.fireworks`, `advent-calendar.enabled`, `advent-calendar.catch-up`, `advent-calendar.join-reminder`, `snowball-fight.enabled`, `snowball-fight.duels.enabled`, `snowball-fight.hit-chill.enabled`, `community-missions.enabled`, `sledding.enabled`, `sledding.runs.enabled`, `lamps.enabled`.
- Administration: `admin-menu.enabled`, `date-tools.enabled` (`/winter preview`, `setdate`, `resume`), `world-events.enabled`, `spawns.enabled`, `tutorial.enabled`.

The older keys keep their names and still work; they are declared as switches so they show in `/vadmin features`. `season-spots.only-inside` is still read as before. Values that switched a feature off before (`drift-strength: 0`, `transition-blocks: 0`, `warm-food: 0`, `hand-warmers: []`, `chill: 0`) keep that meaning.

A command of a switched-off feature answers "This feature is switched off on this server." Its entry in tab completion and help disappears at once and comes back when the switch is on again. A command that also needs another switch (`/winter duel` and `/winter surrender` need `snowball-fight.enabled`, `/winter sled` needs `sledding.enabled`; the date and lamp buttons of the admin menu need `date-tools.enabled` and `lamps.enabled`) stays listed and names that switch: "This needs “Snowball fights” (snowball-fight.enabled), which is switched off on this server." Stored data (scores, sled times, spots, calendar doors, gifts in the pouch) is kept. `/winter status`, `/winter thaw`, `/winter reload`, `/winter claim` and `/winter claims` always work: they show the state, undo the snow, apply the switches and hand out what players already earned.

## Placeholders (1.24.0)

With Vystorm Core 0.65.0 the plugin registers the namespace `winter`. Each value works as `%vystorm_winter_<key>%` (also through PlaceholderAPI) and as `{winter:<key>}` in Vystorm texts. `/vadmin placeholders winter` lists them. A value whose feature is switched off is empty.

| Key | Value |
| --- | --- |
| `phase` | Season phase: `OFF`, `FROST`, `WINTER` or `THAW` |
| `strength` | Snow strength in percent, 0 to 100 |
| `holiday` | `NONE`, `ADVENT`, `CHRISTMAS` or `NEW_YEAR` |
| `season_year` | Year in which the current season started |
| `date` | Winter date, the test date while one is set |
| `days_to_next_milestone` | Days to the next peak, thaw or end |
| `active` | `true` or `false`: winter is active in the player's world |
| `cold`, `warmth_seconds`, `cold_protection` | Cold of the player in percent, seconds of stored warmth, protection in percent |
| `snowball_hits`, `snowball_wins`, `snowball_rank` | Snowball scores of the current season |
| `calendar_opened`, `calendar_waiting` | Advent doors opened this year and waiting today |

Examples: `%vystorm_winter_phase%` shows `WINTER`, `{winter:cold}` shows `35`. `phase`, `strength`, `holiday`, `season_year` and `active` also answer off the server thread (scoreboard and tab plugins); the other values answer on the server thread.

## Visible snowfall (1.22.0)

White dust particles fall around every player, independent of the real weather and the biome, and only that player sees them. Off by default:

```yaml
snowfall:
  enabled: true
  amount: 200         # flakes per player per second (1..400)
  radius: 10          # half width of the snow box in blocks (4..24)
  height: 8           # height above the player (3..16)
  flake-size: 1.5     # 0.5..2.0
  only-under-sky: true   # no flakes under a roof
  only-in-winter: true   # only while the plugin's winter is active in that world
```

`/winter reload` applies changes. Players switch it for themselves with `/winter snowfall` (permission `winter.snowfall`, default true; stored through Core's player preferences). With season spots, flakes fall only inside the spots. `/winter status` shows the state and the winter admin panel has a "Snowfall amount" slider (0 = off). The "snow speed" slider is about snow on the ground, not about falling snow.

## Sledding (1.18.0)

Boats glide faster on snow and downhill. Admins set up timed sled runs (`/winter sled create` / `finish`); players race for the ten best times (`/winter sled top <run>`).

## Community winter missions (1.14.0)

`/winter missions` shows the whole server's progress toward seasonal snowball, hot-meal and Advent-calendar goals. Real accepted snowball hits, consumed stews and newly opened Advent doors contribute in enabled winter worlds. Cancelled events, Creative/Spectator play and administrator date overrides do not contribute.

Configure up to 16 goals under `community-missions.goals`, each with `objective` (`SNOWBALL_HIT`, `HOT_MEAL` or `ADVENT_DOOR`), `goal`, reward `Material` and `amount`. Defaults are 100 snowball hits, 50 hot meals and 24 Advent doors. The first contribution freezes that season's objective, target and reward, so editing a live goal cannot change its earned reward. Give a new goal a different ID when you intentionally want another mission.

Progress is saved atomically in `community-missions.json` before completion is announced. Every player with `winter.missions` (default true) receives each completed goal once per season through the existing Core RewardPouch; eligible players who join later can collect it too. `/winter claim` retrieves pending rewards, one entry per use. The permanent season/goal receipt prevents duplicate rewards after reloads, reconnects and restarts. Disabling missions pauses progress and new credits; existing pouch entries remain claimable. All new text is included in English, German, Simplified Chinese, French, Brazilian Portuguese, Italian and Russian.

[Download on Spigot](https://www.spigotmc.org/resources/140367/)

Free seasonal winter Java plugin, powered by **Vystorm Core 0.65.0+**. Plugin bytecode requires Java 21 or newer; use the Java version required by your server (Java 25 for current 26.x servers). The build compiles against Paper 26.2 and, in the `compat1206` check, against Paper 1.20.6; Spigot and CraftBukkit 26.3 were last started with Winter 1.13.0 (see `release/VALIDATION.md`, where real-server results and tested versions are recorded).

Snow gradually accumulates between configurable calendar dates, including tree canopies. Owned snow can stack beyond one block (eight layers per block); the default maximum total depth is 12 layers and the configurable range is 1–64. Surface water freezes outward from natural-looking shores, within a configurable ocean/inland distance budget. February thaw gradually restores plugin-owned snow/ice. Only loaded chunks are processed, with bounded column, mutation and shoreline-search budgets. Every changed position is recorded with its original block data before the edit, in a compact record stored inside its chunk (since 1.24.2), so the record is always saved together with the chunk's blocks; only loaded chunks are held in memory. A change is recorded at the end of one tick and applied at the start of the next, after rechecking the block, chunk, protection and reload generation. A position whose original block is back leaves the record.

## Install

Install Vystorm Core and this JAR in `plugins/`. Start once, set the explicit `worlds` allowlist and dates in `plugins/WinterIsComing/config.yml`, then `/winter reload`. Defaults: November 15 start, December 24 peak, February 1 thaw, March 1 end, Europe/Vienna timezone. Nether and End are excluded. Core regions are protected by default; a region's `winter: true` flag opts it in. Other protection plugins may cancel proposed `BlockFormEvent`s; explicit Core regions are recommended.

`/winter status`, `/winter preview YYYY-MM-DD` (read only), `/winter setdate YYYY-MM-DD`, `/winter resume`, `/winter thaw`, `/winter reload`, `/winter claim`, `/winter claims`, `/winter calendar`, `/winter snowball`, `/winter duel <player>`. Administrative actions require `winter.admin`; gifts require `winter.claim` (default true), which also lets players list their pending gifts with `/winter claims`. `setdate` changes the world gradually and persists until `resume`; it cannot mint gifts. `thaw` continues across restarts until `resume`. Date arguments tab-complete today and the current or upcoming season boundaries; outside the season, `/winter status` also shows when the next winter starts. New messages (`invalid-date`, `next-season`) fall back to the bundled texts, so existing language files need no changes.

## Snow depth and upgrading

`/winter warmth` (German: `/winter wärme`) shows your current cold percentage, remaining stored warmth in seconds and equipment protection against ambient cold. It also explains how to warm up. Available to all players; reads existing cold state and equipment without scanning blocks or changing gameplay. The displayed equipment protection does not cover vanilla powder-snow damage.

```yaml
snow:
  max-layers: 12
  cover-leaves: true
```

`max-layers` is total column depth, not a per-block limit: 8 is one block,
12 is one and a half, and 64 is eight blocks. Actual depth varies with the
season and local variation. Stacks grow from the bottom and melt from the top;
clearing a lower owned snow block also clears its owned upper stack. Leaf
cover can be disabled independently. Flowers, crops and tall plants are not replaced; optional small-plant coverage is described below.

Existing configs retain their previous depth (often 3). Set `max-layers: 12`
and run `/winter reload` for the thicker default; a missing `cover-leaves`
setting defaults to true. Keep the existing world and plugin journals when
updating from 1.0.x. No journal schema migration is needed.

## Integration and holidays

Core owns scheduling, localization, regions, versioned settings state and a dedicated world-event domain. Optional SpawnDirector consumes Core skeleton/spider/creeper weight multipliers while retaining spawn caps, protection and anti-farm rules. WorldDirector can observe the shared winter/holiday event catalog. Neither director is required. No separate spawning engine or replacement of the global Core event provider is installed.

Advent gifts are available once per Advent week, Christmas gifts once per calendar year and New Year gifts once per New Year. Christmas dates are configurable. A real-time New Year countdown and harmless particle fireworks run at loaded world spawn locations. Missed midnight celebrations are not replayed. The release does not include custom bosses, structures or a quest engine.

## Operational limits

- With `weather.packet-snow: true`, Core sends a client-only snowy-biome view to players in controlled worlds. This uses vanilla snowy-plains visuals, leaves stored/server biome data unchanged, and observes `snow.excluded-biomes`. Players on unsupported runtimes use snow particles when `weather.warm-biome-particles` is enabled; set `weather.packet-snow: false` to use particles everywhere. The packet view is restored on thaw, reload, world changes and plugin disable.
- Frost searches stop at unloaded chunks or their search budget. Shoreline searches have their own per-tick budget (half of `frost.search-budget` in cells, at least one search per tick) and never hold up the snow; a search only reaches as far as the frost of that day. A deep natural-material shoreline is required; artificial islands made from those same materials cannot be reliably distinguished from generated land.
- Original natural snow/ice is never adopted. Artificial ice/snow cannot be harvested for drops. Clearing it relinquishes that position permanently. Player block placements also relinquish tracked positions. Restore compares block data and leaves mismatches alone. External editors that silently replace a block with identical data cannot be distinguished; exclude their regions first.
- The restoration records live in the world's chunks (persistent chunk data, key `winteriscoming:tracked`): back up the world and `state.json` together. A chunk record that cannot be read is left untouched and the plugin leaves that chunk alone. The old `blocks.jsonl` of 1.24.1 and older is migrated chunk by chunk when the chunks load (a copy stays as `blocks.jsonl.bak-1.24.1`); keep it until `/winter status` no longer lists old positions. Corruption of the old journal fails closed. `performance.max-tracked-blocks` is a safety limit for the positions in memory; the limit in use grows with the loaded chunks, and reaching it stops expansion with a console message and a line in `/winter status`, while existing restoration continues. Removing the JAR does not remove existing snow/ice: run `/winter thaw` and load affected chunks first.
- Gifts use Core 0.33.0 RewardPouch with stable seasonal credit IDs, inventory receipts and recovery. Full inventories retain gifts in the pouch; `/winter claim` can collect pending gifts after the holiday. Temporary managed visits block delivery. Conflicting interrupted deliveries require administrator review; never delete the reward ledger or player receipts. Keep player data, `rewards/winter/`, journal and state in consistent backups.
- No automatic live-server deployment or external command rewards.

## Build

`JAVA_HOME=/path/to/jdk-25 ./gradlew build --offline --max-workers=2 --no-daemon --project-cache-dir build/gradle-cache -PvystormCoreJar=/path/to/Vystorm_Core.jar` (without `-PvystormCoreJar`, the build uses `libs/Vystorm_Core.jar`)

`check` includes `compat1206` (all main sources compiled with release 21 against Paper 1.20.6) and `compat1206Test` (compatibility tests using the release classes with the old API). The offline libraries default to `<home>/source/tools/smoke-1206/libraries`; override with `-Ppaper1206Libraries=/path/to/1.20.6/libraries`. Build with JDK 25; every source set emits Java 21 bytecode. All current features use APIs available on 1.20.6, so no separate modern adapter module is necessary.

On 1.20.6, Core does not support client biome packet snow: `weather.warm-biome-particles: true` provides the existing cosmetic particle fallback, with one console notice per plugin instance. Terrain snow/ice, cold, warmth reserves, the chest calendar, gifts and snowball duels remain available. Custom material settings must name materials present on that server; invalid settings retain their existing validation errors. Winter uses Core's shared compatibility package; Winter 1.24.0 requires Core 0.65.0 or newer on every supported server.

`./gradlew integrationJar` builds the test-only acceptance plugin. Never install the acceptance plugin on a live server: it constructs an isolated fixture, changes dates and stops its test server.

No license activation or paid feature gate.

### Winter landscape details (1.1.0)

`snow.drift-strength: 0.45` creates smooth patches of different snow depths.
The range is 0–1; 0 gives uniform depth. `snow.max-layers` remains the total
ceiling (12 layers by default). This is a deterministic terrain pattern,
not a wind or physical snow transport simulation.

`snow.transition-blocks: 4` tapers snow on the permitted side of Core protection
and excluded-biome boundaries. Set 0 to disable (maximum 12). No protected
blocks are touched and no neighboring chunk is force-loaded. Newly loaded
chunks catch up gradually through the normal budgeted terrain queue.

`snow.cover-small-plants: true` temporarily covers SHORT_GRASS, FERN and the
other small plants as soon as the column gets snow, with one layer like the bare
ground next to it (until 1.24.0 only from four layers on). The durable journal keeps their exact
original block data, including across restarts; thaw or disabling coverage
restores them. Player replacements take precedence. Flowers, tall plants,
crops and containers are not replaced. Clearing artificial snow creates no
free snow drops. The feature uses the existing block-form protection events.

### Native snowfall through Core (1.2.0)

`weather.packet-snow: true` (also the default for existing configs) uses Core 0.33.0 to send snowy biome data to each player. Precipitation is vanilla client-rendered snow; no resource pack, ProtocolLib or client mod is required. Snow/ice placement remains a separate seasonal feature. The packet view changes client biome colours and potentially biome sounds, but never the saved biome or server spawning rules.

The view is per player across the controlled world, with the existing biome exclusions. While season spots limit a world (`season-spots.only-inside: true` and at least one spot there), the world keeps its own weather: players inside a spot get the snowfall as their personal weather and see only the spot as snowy biome (the part of the spots within about 32 blocks of them); players outside see the normal biome and weather. Storms and the cold also act only inside the spots. Core regions still protect terrain edits; they do not mask this cosmetic weather view. Newly delivered chunks receive the overlay through a bounded queue, so a brief original-biome appearance is possible. Changes from other biome-packet plugins are repaired every ten seconds. Set `weather.packet-snow: false` and reload to disable it.

### Cold and warmth (1.3.0)

While a world is in winter (not during thaw), survival and adventure players standing outside slowly get cold: the vanilla frost overlay creeps in, they walk a little slower and their breath becomes visible. At full winter during the day it takes about three minutes to get fully cold; nights (x1.5), snowfall (x1.3) and swimming (x2) are colder, and the season strength scales it.

- **Warm up**: fire, soul fire, lava, magma blocks and lit campfires, furnaces, smokers and blast furnaces within `warm-radius` (4 blocks) warm players quickly.
- **Shelter**: with a roof overhead or underground (sky light at head height below 12) players recover slowly.
- **Hot meals** (1.4.0): eating mushroom stew, rabbit stew, beetroot soup or suspicious stew removes `warm-food` (0.5 = half of full cold) at once; 0 disables it.
- **Clothing**: each leather armour piece blocks 25% of the cold; full leather makes players immune, just like vanilla powder snow.
- **Hand warmers**: a lantern or soul lantern in the main or off hand blocks as much cold as one leather piece (`cold.hand-warmers`, `[]` turns it off).
- **No damage by default**: `max-frost: 0.4` caps the effect at a light overlay (about 20% slower walking) and players are never fully frozen. `damage: true` lets the cold reach vanilla freeze damage.
- Action bar hints appear once when getting cold and while warming up at a fire. `/winter status` shows your current cold level. Excluded biomes, creative/spectator mode and dead players are never affected; inside powder snow vanilla takes over.

```yaml
cold:
  enabled: true
  max-frost: 0.4
  damage: false
  warm-radius: 4
  minutes-to-freeze: 3
  breath: true
  warnings: true
  warm-food: 0.5
```

Tested with unit tests (rates, leather, heat sources, freeze cap) and a server start on Purpur 26.2 with Core 0.39.1. Not yet tested in-game with a real client.

### Advent calendar (1.5.0)

A daily Advent calendar in a chest menu: 24 doors, scattered like on a real calendar, one small gift behind each from December 1 to 24. Players open it with `/winter calendar`, from the Core main menu (`/menu`, category World, shown in November and December) or with the clickable **[Open]** hint they get on join while a door is waiting.

- Today's door glows; click it to get the gift. Future doors show their date, opened doors show what was inside.
- Missed a day? With `catch-up: true` (default) missed doors can still be opened until December 31; with `false` each door only opens on its own day.
- Gifts go through the Core reward pouch with a stable ID per door and year, so a door can never pay out twice, not even after a crash. With a full inventory the gift waits in the pouch: the menu's gift button or `/winter claim` delivers it later.
- Only the real date counts (`/winter setdate` never opens doors), and doors open only in the configured winter worlds.
- Rewards are plain vanilla items, one `MATERIAL amount` entry per door (shorter lists repeat). The default list goes from cookies and candles via stews and leather armour to a diamond on December 24.

```yaml
advent-calendar:
  enabled: true
  catch-up: true
  join-reminder: true
  rewards: [COOKIE 6, SWEET_BERRIES 12, ..., DIAMOND 1]   # 24 entries in the bundled config
```

Permission `winter.calendar` (default: everyone). Opened doors are stored in `calendar.json` (current December only); the reward ledger stays in `rewards/winter/`.

### Snowball fights (1.5.0)

In winter worlds while the season runs (not during thaw), every snowball that hits another player counts: the thrower sees **Hit! 12 snowball hits this winter** in the action bar and climbs the seasonal leaderboard. Each hit also chills the target a little (`chill`, reduced by leather armour), so a snowball fight makes you want a campfire.

- `/winter snowball` shows your hits, duel wins and rank, plus the top 5 of the winter. The board starts fresh each season.
- **Duels**: `/winter duel <player>` or, on Core 0.43.0+, sneak and right-click a player and pick **Snowball duel** in the player menu. The other player gets a clickable **[Accept]** (or picks the same entry). First to `duel-hits` hits wins (default 10); after `duel-seconds` (120) the leader wins, or it is a draw. Both duelists are topped up to `duel-snowballs` snowballs at the start. Leaving the server forfeits.
- Hits are counted at most once per second per thrower and target. Hits cancelled by PvP or region protection do not count, creative and spectator players are ignored, and nothing is rewarded except glory, so there is nothing to farm.

```yaml
snowball-fight:
  enabled: true
  chill: 0.05
  duel-hits: 10
  duel-seconds: 120
  duel-snowballs: 16
```

Permission `winter.snowball` (default: everyone). Scores are kept in `snowball.json` and written every 30 seconds and on shutdown.

Both features work with existing configs: missing keys use the defaults above, and new texts fall back to the bundled language files.

### Give up a snowball duel (1.8.0)

`/winter surrender` ends your running snowball duel immediately and awards your opponent one win, even if you were leading. Both players receive the result. German: `/winter aufgeben`; Simplified Chinese: `/winter 认输`. Requires `winter.snowball` (default: everyone), with no new configuration. Pending invitations stay unchanged. If time has already run out, the normal timed result applies. You can also surrender after leaving a winter world or after snowball fights have been disabled.

### Placeholder fix (1.3.0)

Messages up to 1.2.2 showed `<date>`, `<phase>` and similar instead of the values. 1.3.0 fixes the bundled language files and rewrites these placeholders in existing `plugins/WinterIsComing/lang/*.yml` once on start (backups end in `.bak-1.2`). Custom texts and colour tags stay untouched.
### Stored warmth (1.9.0)

Prepare for a winter journey at an existing heat source: once your cold reaches zero, another 20 seconds nearby fills a 60-second warmth reserve. It blocks additional ambient winter cold while you travel. Partial charges work too. Away from heat the reserve counts down, including under shelter or in leather armour; normal recovery still works. Snowball chill and vanilla powder snow are unaffected.

Action bar hints announce a full or exhausted reserve (controlled by `cold.warnings`). `/winter status` shows the remaining seconds whenever a reserve is present. No new command or permission is needed.

| Configuration key | Default | Meaning |
| --- | --- | --- |
| `cold.reserve.enabled` | `true` | Enable stored warmth |
| `cold.reserve.charge-seconds` | `20` | Time at heat after fully warming up to fill the reserve (1–600) |
| `cold.reserve.duration-seconds` | `60` | Maximum protection time away from heat (1–600) |

Existing configs use these defaults for missing keys, following the existing config upgrade behaviour; custom values remain unchanged. No new terrain scans or tasks are added. Reserves are temporary: leaving the server, death, becoming ineligible for winter cold (including entering powder snow), reload, administrative date/mode changes and shutdown clear them. Heat sources and eligibility follow the existing cold settings.

## License

Copyright (c) 2026 Esoren

Versions after 1.22.1 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.22.1 and earlier were released under the MIT License ([LICENSE-MIT-until-1.22.1.txt](LICENSE-MIT-until-1.22.1.txt)) and stay available under those terms.
