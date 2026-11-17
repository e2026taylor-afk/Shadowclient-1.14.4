package dev.quietboost;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * A persistent, toggleable area of "darkness" that Crimson_Shade drops at
 * their current chunk: anyone standing inside (Crimson_Shade excluded) is
 * kept blinded (Blindness stands in for Darkness, which 1.14.4 doesn't have)
 * and sees silent figures wearing Crimson_Shade's own skin, facing them,
 * that only they can see -- true fake-player packets sent to one connection
 * only (see FakeIllusion), not a real world entity.
 */
final class DarknessZone {

    private final QuietBoost plugin;
    private final Random random = new Random();

    private boolean active;
    private UUID adminId;          // whoever toggled this zone on -- excluded from its effects
    private Location center;
    private int radiusChunks;
    private int holdTicksSinceGrow;
    private BukkitTask upkeepTask;

    private final Map<UUID, List<FakeIllusion>> illusions = new HashMap<>();
    private int sinceIllusionRefresh;

    DarknessZone(QuietBoost plugin) {
        this.plugin = plugin;
    }

    boolean isActive() {
        return active;
    }

    void toggle(Player trigger) {
        if (active) stop();
        else start(trigger);
    }

    private void start(Player trigger) {
        active = true;
        adminId = trigger.getUniqueId();
        center = trigger.getLocation().clone();
        radiusChunks = Math.max(0, plugin.getConfig().getInt("darkness.base-radius-chunks", 2));
        holdTicksSinceGrow = 0;
        sinceIllusionRefresh = 0;

        int reapply = Math.max(1, plugin.getConfig().getInt("darkness.reapply-every-ticks", 10));
        upkeepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::upkeep, reapply, reapply);
    }

    void stop() {
        if (!active) return;
        active = false;
        if (upkeepTask != null) upkeepTask.cancel();
        upkeepTask = null;

        for (List<FakeIllusion> list : illusions.values()) removeIllusions(list);
        illusions.clear();
    }

    /** Kept as a harmless no-op: growth is automatic (see upkeep()); a future
     *  client's hold-tick packets don't need to change anything here. */
    void onHoldTick() {
    }

    private void upkeep() {
        if (!active || center.getWorld() == null) return;

        int growEvery = Math.max(1, plugin.getConfig().getInt("darkness.grow-every-ticks", 10));
        int max = plugin.getConfig().getInt("darkness.max-radius-chunks", 4);
        if (++holdTicksSinceGrow >= growEvery) {
            holdTicksSinceGrow = 0;
            if (radiusChunks < max) radiusChunks++;
        }

        World w = center.getWorld();
        Chunk base = center.getChunk();
        int ticks = Math.max(1, plugin.getConfig().getInt("darkness.blindness-duration-ticks", 30));

        Set<UUID> inside = new HashSet<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = base.getX() + dx, cz = base.getZ() + dz;
                if (!w.isChunkLoaded(cx, cz)) continue;
                for (Entity e : w.getChunkAt(cx, cz).getEntities()) {
                    if (!(e instanceof Player)) continue;
                    Player victim = (Player) e;
                    if (victim.getUniqueId().equals(adminId)) continue; // never affects whoever triggered this zone (config-driven name, not hardcoded)
                    inside.add(victim.getUniqueId());
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, true, false, false));
                    ensureIllusions(victim);
                }
            }
        }
        for (UUID u : new ArrayList<>(illusions.keySet())) {
            if (!inside.contains(u)) removeIllusions(illusions.remove(u));
        }

        int refreshEvery = Math.max(20, plugin.getConfig().getInt("darkness.illusions.refresh-every-ticks", 100));
        int reapply = Math.max(1, plugin.getConfig().getInt("darkness.reapply-every-ticks", 10));
        sinceIllusionRefresh += reapply;
        if (sinceIllusionRefresh >= refreshEvery) {
            sinceIllusionRefresh = 0;
            for (UUID u : inside) {
                Player victim = Bukkit.getPlayer(u);
                if (victim != null) {
                    removeIllusions(illusions.remove(u));
                    ensureIllusions(victim);
                }
            }
        }
    }

    private void ensureIllusions(Player victim) {
        if (!plugin.getConfig().getBoolean("darkness.illusions.enabled", true)) return;
        if (illusions.containsKey(victim.getUniqueId())) return;

        Player admin = Bukkit.getPlayer(adminId);
        if (admin == null) return; // need Crimson_Shade's skin/profile to build the illusion

        int min = plugin.getConfig().getInt("darkness.illusions.count-min", 2);
        int max = Math.max(min, plugin.getConfig().getInt("darkness.illusions.count-max", 4));
        int count = min + (max > min ? random.nextInt(max - min + 1) : 0);
        double radius = plugin.getConfig().getDouble("darkness.illusions.radius-blocks", 6);

        List<FakeIllusion> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location loc = randomNearby(victim.getLocation(), radius);
            if (loc == null) continue;
            FakeIllusion illusion = FakeIllusion.spawn(victim, admin, loc, victim.getEyeLocation());
            if (illusion != null) spawned.add(illusion);
        }
        if (!spawned.isEmpty()) illusions.put(victim.getUniqueId(), spawned);
    }

    private Location randomNearby(Location origin, double radius) {
        World w = origin.getWorld();
        for (int attempt = 0; attempt < 6; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 1.5 + random.nextDouble() * radius;
            double x = origin.getX() + Math.cos(angle) * dist;
            double z = origin.getZ() + Math.sin(angle) * dist;
            int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            Location loc = new Location(w, x, y, z);
            if (loc.getBlock().isPassable()) return loc;
        }
        return null;
    }

    private void removeIllusions(List<FakeIllusion> list) {
        if (list == null) return;
        for (FakeIllusion i : list) i.remove();
    }

    /** Called from QuietBoost#onDisable and stealth-admin removal so nothing is left dangling. */
    void shutdown() {
        stop();
    }
}
