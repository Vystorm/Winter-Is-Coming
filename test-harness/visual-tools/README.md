# Seasonal block-state comparison

This is an isolated **server-state visualization**, not a Minecraft client screenshot
or a visual test of precipitation, particles, textures, lighting, or gift delivery.
The renderer draws only materials and snow-layer heights exported by the running
server. Its flat colors and orthographic projection are deliberately schematic.

`WinterVisual` builds one 32×32 controlled coastal fixture with a cabin, trees,
path, and protected Core plot. It exports the same coordinates before winter,
on November 30, December 24, February 15, and after winter on March 2. All snow
and ice in the snapshots must come from the production plugin. The test uses
an ocean radius of 8 blocks instead of the production default of 40, and calls
the production terrain cycle more frequently. Weather and holiday particles are
disabled so this comparison isolates terrain behavior.

The test requires at least 600 ticks per changing phase and 100 ticks with an
unchanged block/ownership digest. It fails on timeout, snow in the protected
plot, absent peak snow/ice, retained winter materials after the season, or any
final block-data difference from the initial fixture. These are controlled
functional checks, not server-load benchmarks.

Build the test-only JAR with `./gradlew --offline visualJar`. It is separate from
the published plugin and must never be installed on a live server. Both the
test JAR and the JVM property `-Dwinter.visual=true` are required to enable it.

`run_visual.py` accepts `--base` (an existing server cache with `server.jar`,
`eula.txt`, and `server.properties`), `--output` (a new directory), `--java`,
`--winter`, `--core`, and `--harness`. It copies only runtime prerequisites,
creates a new world, binds to loopback port 25716, records input hashes, and
stops after the test. It does not download a server or accept an EULA.

With Pillow installed, run:

```sh
python3 render_snapshots.py RUN/plugins/WinterVisual/snapshots OUTPUT_IMAGES --version 1.0.1
```

The output contains five labeled phase panels, a comparison sheet, a top-down
coastal comparison, and a manifest tying the images to SHA-256 hashes of the
source snapshots. All panels use the same projection and material palette.
The yellow annotation marks the Core-protected grass plot.

Keep raw snapshots and the server log alongside the images for audit. Publish
the explicit data-render label with every image; never describe these as game
captures. A real Minecraft client is still required to inspect native weather
and rendered particle effects.
