# Native Minecraft comparison captures

Test-only Fabric client for Minecraft 26.2, Java 25 and Gradle 9.7.1.
Never distribute this project or WinterVisual with the customer plugin.
Requires a working graphical display and Minecraft assets/dependencies.

1. Build `visualJar` in the root project. Start `../visual-tools/run_visual.py`
   with its normal arguments plus `--capture` and a fresh output directory.
   This creates an isolated loopback-only server on port 25716. For natural
   terrain captures, start the visual server with its natural-world mode and
   select `before,peak,after` below.
2. From the root project, run:

   ```sh
   test-harness/client/gradlew -p test-harness/client runClientGameTest \
     -PcaptureSnapshots=/absolute/RUN/plugins/WinterVisual/snapshots \
     -PcaptureStages=before,early,peak,thaw,after \
     -PcaptureRunDir=build/run/clientCapture
   ```

3. Require both server `plugins/WinterVisual/result.json` and client
   `build/run/clientCapture/winter-capture-report.json` to report success.
   Run `verify_snapshots.py` independently against the server snapshots.
4. Run `compose_client_captures.py CLIENT_REPORT OUTPUT_DIRECTORY` with
   pycairo and Pillow installed. It preserves original screenshot pixels,
   adds labels outside the images, checks pixel equality, and records hashes.

The client checks every block in the snapshot bounds before each capture,
including air at omitted sparse positions. After the stage settles, it reconnects
through the normal client lifecycle to discard the previous world's render state,
receives fresh chunks, repeats the full block comparison, and waits for rendering.
A queue-empty check alone is not proof that every mesh is ready; final screenshots
are also visually inspected. The client acknowledges each capture to release
the server's next phase.
Stage selection is
comma-separated; the default is `before,early,peak,thaw,after`. For natural
captures, use `-PcaptureStages=before,peak,after`. Snapshots may include a
`camera` object with `x`, `y`, `z`, `yaw`, and `pitch`; the client checks it
against the connected spectator and requires it to remain the same across
captured stages. Older snapshots without that object use the default camera
(42,89,46), yaw 139, pitch 27. FOV 50;
1920x1080, render distance 8, vanilla textures, no shaders, clear midday.
All mutations and client world reads use the respective game threads.

The default synthetic fixture accelerates seasonal dates and uses an ocean radius of 8 instead
of the default 40. Its real client screenshots show an artificial fixture.
Use the natural-world mode below to capture normal generated terrain. Neither
mode validates precipitation or holiday particle effects.
The test-only server performs extra terrain cycles to settle loaded chunks.
Use fresh run directories; never point this harness at a live server/world.

## Natural-world server

Build the root `naturalVisualJar` task and pass its `WinterNaturalVisual.jar`
to `run_visual.py --capture --natural` with a fresh output directory. This
overrides the copied server settings with normal Minecraft generation and
seed 20261024. No world, structure, block, or biome is copied from the small
fixture. A natural sea-level shore near spawn is selected automatically.

Use `-PcaptureStages=before,peak,after` for this server. Snapshots carry the
selected camera and dynamic bounds. Run `verify_natural_snapshots.py` instead
of the synthetic fixture verifier, then compose with `--natural`.

The observed region is 96 by 96 blocks; Core flags restrict seasonal edits to
that region. Default frost radii (ocean 40 / inland 12) and the current plugin snow settings
are preserved. Dates/cycles are accelerated. Random ticks, mob griefing,
weather changes and daylight changes are disabled to isolate terrain changes.
The initial synchronous spawn survey can cause a server slow-tick warning;
it runs before the client joins and is test preparation, not plugin gameplay.

The natural harness waits for a connected player and at least 600 ticks of
world warmup, including 200 ticks of unchanged block states, before exporting
the baseline. This lets normal scheduled leaf-distance updates settle. The
client also waits for visible chunk meshes to finish rendering. After the
baseline capture the isolated server switches to `tick rate 80`. These
accelerated evidence runs are not a performance benchmark.

Natural snapshot exports are published with an atomic move after the complete
JSON has been written. A capture-only recovery can use `-PcaptureStartIndex=2`
with the same full stage list while the server waits for the final marker.
Keep partial and resumed reports separate; an aggregate must identify its
source sessions and must never claim the interrupted session passed.
Use `compose_client_captures.py ... --natural --web` to also export uniformly
scaled web previews below the site's 5MiB limit. Full-resolution comparison
regions are still checked pixel-for-pixel against their original captures.

The natural runner accepts `--tick-rate 300 --ram-journal` for accelerated visual
captures. The latter uses temporary tmpfs storage and copies the journal back
on normal shutdown. These runs are visual evidence only; separate normal-disk
Paper/Purpur tests cover journal restart behavior. Use `--version 1.1.0` when
composing the 1.1.0 captures and `--require-stacks --require-details` for the
natural snapshot verifier to require canopy stacks, covered plants, varying
depths and a thinner border.
