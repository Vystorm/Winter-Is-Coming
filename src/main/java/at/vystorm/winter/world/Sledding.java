package at.vystorm.winter.world;

import at.vystorm.winter.storage.SledRuns;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Sledding: boats glide faster on snow (layers, snow blocks, powder snow) and faster still downhill, up to a speed cap;
 * ice keeps its vanilla speed. Optional timed sled runs: crossing a run's start in a boat starts the clock, reaching its
 * finish stops it; leaving the boat or ten minutes cancel. Server thread.
 */
public final class Sledding implements Listener {
    /** Settings from {@code sledding.*}. */
    public record Options(boolean enabled, double multiplier, double maxSpeed) {}
    /** Messages and runs come from the plugin. */
    public interface Host {
        Options sleddingOptions();
        SledRuns sledRuns();
        void sledMessage(Player player, String key, Object... placeholders);
    }

    static final double TRIGGER = 2.5;
    static final long TIMEOUT = 10 * 60_000L;
    private record Active(String run, long started) {}

    private final Host host;
    private final Map<UUID, Active> running = new HashMap<>();

    public Sledding(Plugin plugin, Host host) { this.host = host; }

    static boolean snow(Material type) {
        return type == Material.SNOW || type == Material.SNOW_BLOCK || type == Material.POWDER_SNOW;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void move(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) return;
        Player rider = null;
        for (Entity passenger : boat.getPassengers()) if (passenger instanceof Player p) { rider = p; break; }
        if (rider == null) return;
        Options options = host.sleddingOptions();
        if (options != null && options.enabled() && onSnow(boat.getLocation())) {
            Vector v = boat.getVelocity();
            double horizontal = Math.hypot(v.getX(), v.getZ());
            double next = SleddingMath.speed(horizontal, options.multiplier(), event.getTo().getY() < event.getFrom().getY() - 0.01, options.maxSpeed());
            if (next > horizontal + 1e-4) boat.setVelocity(new Vector(v.getX() * next / horizontal, v.getY(), v.getZ() * next / horizontal));
        }
        timing(rider, event.getTo());
    }

    private static boolean onSnow(Location at) {
        Block here = at.getBlock(), below = at.clone().subtract(0, 0.2, 0).getBlock();
        return snow(here.getType()) || snow(below.getType());
    }

    private void timing(Player player, Location at) {
        SledRuns runs = host.sledRuns();
        if (runs == null) return;
        long now = System.currentTimeMillis();
        Active active = running.get(player.getUniqueId());
        if (active != null) {
            if (now - active.started() > TIMEOUT) { running.remove(player.getUniqueId()); host.sledMessage(player, "sled.timeout", "run", active.run()); return; }
            var run = runs.run(active.run()).orElse(null);
            if (run == null) { running.remove(player.getUniqueId()); return; }
            if (run.hasFinish && near(at, run.world, run.fx, run.fy, run.fz)) {
                running.remove(player.getUniqueId());
                long millis = now - active.started();
                SledRuns.Result result = runs.record(active.run(), player.getUniqueId(), player.getName(), millis);
                host.sledMessage(player, result.personalBest() ? "sled.finished-best" : "sled.finished", "run", active.run(),
                        "time", SledRuns.format(millis), "rank", result.rank());
                return;
            }
        }
        for (String name : runs.names()) {
            var run = runs.run(name).orElse(null);
            if (run == null || !run.hasFinish || !near(at, run.world, run.sx, run.sy, run.sz)) continue;
            if (active != null && active.run().equals(name) && now - active.started() < 3000) return; // still crossing the start
            running.put(player.getUniqueId(), new Active(name, now));
            host.sledMessage(player, "sled.started", "run", name);
            return;
        }
    }

    private static boolean near(Location at, String world, double x, double y, double z) {
        return at.getWorld() != null && at.getWorld().getName().equals(world)
                && Math.abs(at.getX() - x) <= TRIGGER && Math.abs(at.getZ() - z) <= TRIGGER && Math.abs(at.getY() - y) <= 3;
    }

    @EventHandler
    public void exit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player && running.remove(player.getUniqueId()) != null) host.sledMessage(player, "sled.cancelled");
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) { running.remove(event.getPlayer().getUniqueId()); }
}
