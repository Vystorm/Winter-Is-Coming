package at.vystorm.winter.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

/** Captures real client frames after verifying the matching live server snapshot. */
public final class WinterCaptureTest implements FabricClientGameTest {
    private static final String SERVER = System.getProperty("winter.capture.server", "");
    private static final String SNAPSHOTS = System.getProperty("winter.capture.snapshots", "");
    private static final String[] DEFAULT_STAGES = {"before", "early", "peak", "thaw", "after"};
    private static final String[] STAGES = configuredStages();
    private static final Pattern SNOW_LAYERS = Pattern.compile("(?:^|[\\[,])layers=(\\d+)");
    private static final int STATE_WAIT_TICKS = 36_000;
    private static final int START_INDEX = Integer.getInteger("winter.capture.startIndex", 0);
    private final List<Capture> captures = new ArrayList<>();
    private Camera runCamera;

    private static String[] configuredStages() {
        String value = System.getProperty("winter.capture.stages", "").trim();
        if (value.isEmpty()) return DEFAULT_STAGES.clone();
        String[] stages = value.split(",");
        check(stages.length > 0, "winter.capture.stages must not be empty");
        java.util.Set<String> allowed = java.util.Set.of(DEFAULT_STAGES);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < stages.length; i++) {
            stages[i] = stages[i].trim();
            check(allowed.contains(stages[i]), "unknown capture stage: " + stages[i]);
            check(seen.add(stages[i]), "duplicate capture stage: " + stages[i]);
        }
        return stages;
    }

    @Override public void runTest(ClientGameTestContext ctx) {
        if (Boolean.getBoolean("winter.capture.packets")) { new WinterPacketCapture().runTest(ctx); return; }
        boolean passed = false;
        Throwable failure = null;
        Path snapshotDir = null;
        try {
            check(START_INDEX >= 0 && START_INDEX < STAGES.length, "capture startIndex outside selected stages");
            check(!SERVER.isBlank(), "winter.capture.server must be set");
            check(!SNAPSHOTS.isBlank() && !SNAPSHOTS.equals("UNSET"), "winter.capture.snapshots must name the server snapshot directory");
            snapshotDir = Path.of(SNAPSHOTS).toAbsolutePath().normalize();
            try { Files.createDirectories(snapshotDir); }
            catch (IOException error) { throw new IllegalStateException("Could not access snapshot directory", error); }
            // Never let markers from an earlier run release the server's phase barrier.
            try { for (int i = START_INDEX; i < STAGES.length; i++) Files.deleteIfExists(marker(snapshotDir, i)); }
            catch (IOException error) { throw new IllegalStateException("Could not clear stale capture markers", error); }

            ctx.runOnClient(mc -> {
                mc.getWindow().setWindowed(1920, 1080);
                mc.options.fov().set(50);
                mc.options.gamma().set(1.0);
                mc.options.renderDistance().set(8);
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(120);
            });
            ctx.waitTicks(5);
            connect(ctx);
            // The isolated server teleports this spectator to one fixed overview position on join.
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F1);
            for (int i = START_INDEX; i < STAGES.length; i++) {
                Path snapshot = snapshotDir.resolve(String.format(Locale.ROOT, "%02d-%s.json", i + 1, STAGES[i]));
                awaitFile(ctx, snapshot, STATE_WAIT_TICKS, "server snapshot " + snapshot.getFileName());
                ExpectedWorld expected = readSnapshot(snapshot);
                awaitWorld(ctx, expected, STATE_WAIT_TICKS, STAGES[i]);
                // Reconnect after the phase settles: discard the previous world's render state
                // and receive a fresh chunk snapshot through the normal client lifecycle.
                disconnect(ctx);
                connect(ctx);
                awaitWorld(ctx, expected, STATE_WAIT_TICKS, STAGES[i]);
                ctx.waitTicks(200);
                waitFor(ctx, "chunk render queue settled", mc -> mc.levelRenderer.hasRenderedAllSections(), 1200);
                ctx.waitTicks(100);
                verifyCamera(ctx, expected.camera());
                clearHud(ctx);
                ctx.waitTicks(2);
                String shotName = String.format(Locale.ROOT, "winter-%02d-%s", i + 1, STAGES[i]);
                Path screenshot = ctx.takeScreenshot(shotName);
                check(Files.isRegularFile(screenshot), "screenshot was not written: " + screenshot);
                Capture capture = cameraAndCapture(ctx, STAGES[i], screenshot.toAbsolutePath().normalize());
                captures.add(capture);
                try { Files.writeString(marker(snapshotDir, i), "captured " + capture.stage() + "\n", StandardCharsets.UTF_8); }
                catch (IOException error) { throw new IllegalStateException("Could not signal captured stage " + capture.stage(), error); }
            }
            disconnect(ctx);
            passed = true;
        } catch (RuntimeException | AssertionError error) {
            failure = error;
            try { ctx.takeScreenshot("winter-failure"); }
            catch (RuntimeException | AssertionError screenshotError) { error.addSuppressed(screenshotError); }
            throw error;
        } finally {
            if (!passed) disconnectQuietly(ctx);
            writeReport(passed, failure);
        }
    }

    private void connect(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(SERVER),
                new ServerData("winter-visual", SERVER, ServerData.Type.OTHER), false, null));
        waitFor(ctx, "joined " + SERVER, mc -> mc.player != null && mc.level != null && mc.gui.screen() == null, 600);
    }

    private void disconnect(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> { if (mc.level != null) mc.disconnectFromWorld(Component.literal("winter captures complete")); });
        ctx.waitTicks(10);
        ctx.setScreen(TitleScreen::new);
        ctx.waitTicks(2);
    }

    private void disconnectQuietly(ClientGameTestContext ctx) {
        try { disconnect(ctx); } catch (RuntimeException | AssertionError ignored) { }
    }

    private void awaitFile(ClientGameTestContext ctx, Path file, int timeout, String what) {
        for (int tick = 0; tick <= timeout; tick++) {
            if (Files.isRegularFile(file)) return;
            ctx.waitTicks(1);
        }
        throw new AssertionError("Timed out after " + timeout + " ticks waiting for " + what);
    }

    private void awaitWorld(ClientGameTestContext ctx, ExpectedWorld expected, int timeout, String stage) {
        for (int tick = 0; tick <= timeout; tick += 20) {
            boolean matches = ctx.computeOnClient(mc -> matches(mc.level, expected));
            if (matches) return;
            ctx.waitTicks(20);
        }
        throw new AssertionError("Client blocks did not match " + stage + " snapshot within " + timeout + " ticks");
    }

    private static boolean matches(Level level, ExpectedWorld expected) {
        if (level == null) return false;
        Bounds bounds = expected.bounds();
        for (int cx = Math.floorDiv(bounds.minX(), 16); cx <= Math.floorDiv(bounds.maxX(), 16); cx++)
            for (int cz = Math.floorDiv(bounds.minZ(), 16); cz <= Math.floorDiv(bounds.maxZ(), 16); cz++)
                if (!level.hasChunkAt(new BlockPos(cx * 16, bounds.minY(), cz * 16))) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            pos.set(x, y, z);
            BlockState actual = level.getBlockState(pos);
            BlockExpectation wanted = expected.blocks().get(BlockPos.asLong(x, y, z));
            String material = wanted == null ? "air" : wanted.material();
            String actualMaterial = actual.isAir() ? "air" : BuiltInRegistries.BLOCK.getKey(actual.getBlock()).getPath();
            if (!actualMaterial.equals(material)) return false;
            int layers = wanted == null ? -1 : wanted.snowLayers();
            if (layers >= 0 && (!actual.hasProperty(net.minecraft.world.level.block.SnowLayerBlock.LAYERS)
                    || actual.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS) != layers)) return false;
        }
        return true;
    }

    private ExpectedWorld readSnapshot(Path path) {
        final JsonObject root;
        try { root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (IOException error) { throw new IllegalStateException("Could not read snapshot " + path, error); }
        JsonObject boundsJson = root.getAsJsonObject("bounds");
        check(boundsJson != null, "snapshot lacks bounds object: " + path);
        Bounds bounds = new Bounds(boundsJson.get("minX").getAsInt(), boundsJson.get("maxX").getAsInt(),
                boundsJson.get("minY").getAsInt(), boundsJson.get("maxY").getAsInt(),
                boundsJson.get("minZ").getAsInt(), boundsJson.get("maxZ").getAsInt());
        check(bounds.minX() <= bounds.maxX() && bounds.minY() <= bounds.maxY() && bounds.minZ() <= bounds.maxZ(),
                "invalid snapshot bounds: " + bounds);
        Map<Long, BlockExpectation> expectedBlocks = new HashMap<>();
        JsonArray blocks = root.getAsJsonArray("blocks");
        check(blocks != null, "snapshot lacks blocks array: " + path);
        for (JsonElement element : blocks) {
            JsonObject block = element.getAsJsonObject();
            int x = block.get("x").getAsInt(), y = block.get("y").getAsInt(), z = block.get("z").getAsInt();
            check(x >= bounds.minX() && x <= bounds.maxX() && z >= bounds.minZ() && z <= bounds.maxZ()
                            && y >= bounds.minY() && y <= bounds.maxY(),
                    "out-of-bounds block in snapshot: " + block);
            String material = block.get("material").getAsString().toLowerCase(Locale.ROOT);
            int layers = -1;
            if (material.equals("snow")) {
                String data = block.get("data").getAsString();
                Matcher matcher = SNOW_LAYERS.matcher(data);
                check(matcher.find(), "snow data has no layer property: " + data);
                layers = Integer.parseInt(matcher.group(1));
            }
            BlockExpectation previous = expectedBlocks.put(BlockPos.asLong(x, y, z), new BlockExpectation(material, layers));
            check(previous == null, "duplicate block position in snapshot: " + block);
        }
        Camera camera = readCamera(root);
        return new ExpectedWorld(bounds, expectedBlocks, camera);
    }

    private static Camera readCamera(JsonObject root) {
        JsonObject json = root.getAsJsonObject("camera");
        if (json == null) return new Camera(42, 89, 46, 139, 27);
        return new Camera(json.get("x").getAsDouble(), json.get("y").getAsDouble(), json.get("z").getAsDouble(),
                json.get("yaw").getAsFloat(), json.get("pitch").getAsFloat());
    }

    private void verifyCamera(ClientGameTestContext ctx, Camera expected) {
        Camera camera = ctx.computeOnClient(mc -> new Camera(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot()));
        check(Math.abs(camera.x() - expected.x()) < 0.25 && Math.abs(camera.y() - expected.y()) < 0.25
                        && Math.abs(camera.z() - expected.z()) < 0.25,
                "spectator camera position differs from expected " + expected + ": " + camera);
        check(angleDistance(camera.yaw(), expected.yaw()) < 2f && Math.abs(camera.pitch() - expected.pitch()) < 2f,
                "spectator camera rotation differs from expected " + expected + ": " + camera);
        if (runCamera == null) runCamera = expected;
        check(Math.abs(expected.x() - runCamera.x()) < 0.001 && Math.abs(expected.y() - runCamera.y()) < 0.001
                        && Math.abs(expected.z() - runCamera.z()) < 0.001
                        && angleDistance(expected.yaw(), runCamera.yaw()) < 0.001f
                        && Math.abs(expected.pitch() - runCamera.pitch()) < 0.001,
                "snapshot camera changed between stages: first=" + runCamera + ", current=" + expected);
    }

    private Capture cameraAndCapture(ClientGameTestContext ctx, String stage, Path screenshot) {
        Camera camera = ctx.computeOnClient(mc -> new Camera(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot()));
        return new Capture(stage, screenshot.toString(), camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch());
    }

    private void clearHud(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            mc.gui.hud.getChat().clearMessages(false);
            mc.gui.hud.clearTitles();
            mc.gui.toastManager().clear();
        });
    }

    private static double angleDistance(float a, float b) {
        double difference = (a - b) % 360.0;
        if (difference > 180) difference -= 360;
        if (difference < -180) difference += 360;
        return Math.abs(difference);
    }

    private void waitFor(ClientGameTestContext ctx, String what, java.util.function.Predicate<Minecraft> condition, int timeout) {
        for (int tick = 0; tick <= timeout; tick++) {
            if (ctx.computeOnClient(condition::test)) return;
            ctx.waitTicks(1);
        }
        throw new AssertionError("Timed out after " + timeout + " ticks waiting for " + what);
    }

    private static Path marker(Path dir, int stage) {
        Path snapshot = dir.resolve(String.format(Locale.ROOT, "%02d-%s.json", stage + 1, STAGES[stage]));
        return snapshot.resolveSibling(snapshot.getFileName() + ".captured");
    }

    private void writeReport(boolean passed, Throwable failure) {
        JsonObject report = new JsonObject();
        report.addProperty("passed", passed);
        report.add("captures", new JsonArray());
        JsonArray entries = report.getAsJsonArray("captures");
        for (Capture capture : captures) {
            JsonObject entry = new JsonObject();
            entry.addProperty("stage", capture.stage());
            entry.addProperty("screenshot", capture.screenshot());
            entry.addProperty("cameraX", capture.x());
            entry.addProperty("cameraY", capture.y());
            entry.addProperty("cameraZ", capture.z());
            entry.addProperty("yaw", capture.yaw());
            entry.addProperty("pitch", capture.pitch());
            entries.add(entry);
        }
        if (failure != null) report.addProperty("failure", failure.toString());
        Path reportPath = Path.of("winter-capture-report.json").toAbsolutePath().normalize();
        try { Files.writeString(reportPath, report.toString() + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { System.err.println("Could not write " + reportPath + ": " + error); }
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) { }
    private record BlockExpectation(String material, int snowLayers) { }
    private record ExpectedWorld(Bounds bounds, Map<Long, BlockExpectation> blocks, Camera camera) { }
    private record Camera(double x, double y, double z, float yaw, float pitch) { }
    private record Capture(String stage, String screenshot, double x, double y, double z, float yaw, float pitch) { }
}
