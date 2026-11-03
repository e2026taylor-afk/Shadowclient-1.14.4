package dev.quietboost;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
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
 * A persistent, toggleable area of "darkness" that Shadows drops at their
 * current chunk: anyone standing inside is kept blinded (Blindness stands in
 * for Darkness, which 1.14.4 doesn't have) and sees silent shadow-figures
 * that only they can see, placed as real entities in the world so they stay
 * put correctly as the victim looks around.
 *
 * The figures are stylised (a silent, AI-less, nameless Zombie) rather than
 * a true 1:1 copy of Shadows' skin -- doing that accurately needs a fake
 * EntityPlayer built from raw NMS constructors, which is too fragile to ship
 * without being able to compile/test it. This version only uses the public
 * Bukkit API, so it should actually run as-is.
 */
final class DarknessZone {

    private final QuietBoost plugin;
    private final Random random = new Random();

    private boolean active;
    private Location center;
    private int radiusChunks;
    private int holdTicksSinceGrow;
    private BukkitTask upkeepTask;

    // victim uuid -> their shadow-figure entity uuids
    private final Map<UUID, List<UUID>> illusions = new HashMap<>();
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

        for (Map.Entry<UUID, List<UUID>> e : illusions.entrySet()) {
            removeIllusionsFor(e.getKey(), e.getValue());
        }
        illusions.clear();
    }

    /** Kept as a harmless no-op: growth is now automatic (see upkeep()), so a
     *  future client's hold-tick packets don't need to change anything here. */
    void onHoldTick() {
    }

    private void upkeep() {
        if (!active || center.getWorld() == null) return;

        // No held key needed any more (works from a plain command): grow
        // automatically on the same cadence instead of waiting for client hold-ticks.
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
                    inside.add(victim.getUniqueId());
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, true, false, false));
                    ensureIllusions(victim);
                }
            }
        }
        // Anyone no longer inside loses their illusions immediately.
        for (UUID u : new ArrayList<>(illusions.keySet())) {
            if (!inside.contains(u)) {
                removeIllusionsFor(u, illusions.remove(u));
            }
        }

        int refreshEvery = Math.max(20, plugin.getConfig().getInt("darkness.illusions.refresh-every-ticks", 100));
        int reapply = Math.max(1, plugin.getConfig().getInt("darkness.reapply-every-ticks", 10));
        sinceIllusionRefresh += reapply;
        if (sinceIllusionRefresh >= refreshEvery) {
            sinceIllusionRefresh = 0;
            for (UUID u : inside) {
                Player victim = Bukkit.getPlayer(u);
                if (victim != null) {
                    removeIllusionsFor(u, illusions.remove(u));
                    ensureIllusions(victim);
                }
            }
        }
    }

    private void ensureIllusions(Player victim) {
        if (!plugin.getConfig().getBoolean("darkness.illusions.enabled", true)) return;
        if (illusions.containsKey(victim.getUniqueId())) return;

        int min = plugin.getConfig().getInt("darkness.illusions.count-min", 2);
        int max = Math.max(min, plugin.getConfig().getInt("darkness.illusions.count-max", 4));
        int count = min + (max > min ? random.nextInt(max - min + 1) : 0);
        double radius = plugin.getConfig().getDouble("darkness.illusions.radius-blocks", 6);

        List<UUID> spawned = new ArrayList<>();
        World w = victim.getWorld();
        for (int i = 0; i < count; i++) {
            Location loc = randomNearby(victim.getLocation(), radius);
            if (loc == null) continue;
            Zombie z = w.spawn(loc, Zombie.class, e -> {
                e.setSilent(true);
                e.setAI(false);
                e.setCollidable(false);
                e.setInvulnerable(true);
                e.setRemoveWhenFarAway(true);
                e.setCustomNameVisible(false);
                e.setBaby(false);
                e.setCanPickupItems(false);
            });
            // Hide from everyone except the one victim who should see it.
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(victim.getUniqueId())) {
                    other.hideEntity(plugin, z);
                }
            }
            spawned.add(z.getUniqueId());
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

    private void removeIllusionsFor(UUID victimId, List<UUID> entityIds) {
        if (entityIds == null) return;
        for (UUID id : entityIds) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    /** Called from QuietBoost#onDisable and stealth-admin removal so nothing is left dangling. */
    void shutdown() {
        stop();
    }
}
