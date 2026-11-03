package dev.quietboost;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SendHome / Scare / Haunt / Possess, driven either by /commands or by the
 * client mod's buttons over the "shadowclient:main" channel (see net.ShadowNetwork).
 */
final class ShadowAbilities implements Listener {

    private final QuietBoost plugin;
    private final StealthAdmin stealth;
    private final DarknessZone darkness;
    private final Map<UUID, UUID> hauntTarget = new HashMap<>();   // shadow -> target
    private final Set<UUID> possessing = new HashSet<>();           // shadow uuids currently possessing
    private final Map<UUID, BukkitTask> possessionTasks = new HashMap<>();

    ShadowAbilities(QuietBoost plugin, StealthAdmin stealth, DarknessZone darkness) {
        this.plugin = plugin;
        this.stealth = stealth;
        this.darkness = darkness;
    }

    boolean isStealthAdmin(Player p) {
        return stealth.isAdmin(p.getUniqueId());
    }

    // ---- Darkness (delegated to DarknessZone) ------------------------------

    void onDarknessToggle(Player shadow) {
        darkness.toggle(shadow);
    }

    void onDarknessHoldTick(Player shadow) {
        darkness.onHoldTick();
    }

    // ---- Send home ----------------------------------------------------------

    void sendHome(Player shadow, Player explicitTarget) {
        Player target = explicitTarget;
        if (target == null) {
            UUID ht = hauntTarget.get(shadow.getUniqueId());
            if (ht != null) target = Bukkit.getPlayer(ht);
        }
        if (target == null) target = lookingAt(shadow);
        if (target == null) {
            shadow.sendMessage("No one to send home: name someone, or look at them.");
            return;
        }

        Location home = target.getBedSpawnLocation();
        if (home == null) home = target.getWorld().getSpawnLocation();
        target.teleport(home); // inventory/"their stuff" travels automatically with the entity
    }

    private Player lookingAt(Player shadow) {
        RayTraceResult hit = shadow.getWorld().rayTraceEntities(
                shadow.getEyeLocation(), shadow.getEyeLocation().getDirection(), 60, 0.4,
                e -> e instanceof Player && !e.equals(shadow));
        Entity e = hit == null ? null : hit.getHitEntity();
        return e instanceof Player ? (Player) e : null;
    }

    // ---- Scare ----------------------------------------------------------------

    void scare(Player target, String message) {
        target.sendTitle(message, "", 5, 50, 10);
    }

    // ---- Haunt / possess --------------------------------------------------------

    void haunt(Player shadow, Player target) {
        endHaunt(shadow); // clean slate
        hauntTarget.put(shadow.getUniqueId(), target.getUniqueId());
        if (shadow.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
            shadow.setGameMode(org.bukkit.GameMode.SPECTATOR);
        }
        try {
            shadow.setSpectatorTarget(target); // Paper/Spigot spectate API; if your build lacks it, this line needs adjusting
        } catch (Throwable t) {
            shadow.sendMessage("Could not attach your camera (API mismatch) -- you're in spectator mode but not locked to them.");
        }
    }

    void endHaunt(Player shadow) {
        UUID id = shadow.getUniqueId();
        UUID targetId = hauntTarget.remove(id);
        stopPossession(shadow, targetId);
        try {
            shadow.setSpectatorTarget(null);
        } catch (Throwable ignored) {
        }
    }

    void togglePossess(Player shadow) {
        UUID id = shadow.getUniqueId();
        UUID targetId = hauntTarget.get(id);
        if (targetId == null) {
            shadow.sendMessage("Not haunting anyone -- use /haunt <player> first.");
            return;
        }
        if (possessing.contains(id)) {
            stopPossession(shadow, targetId);
        } else {
            startPossession(shadow, targetId);
        }
    }

    private void startPossession(Player shadow, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) return;
        possessing.add(shadow.getUniqueId());

        int interval = Math.max(1, plugin.getConfig().getInt("haunt.possession-tick-interval", 1));
        double speed = plugin.getConfig().getDouble("haunt.possession-speed", 0.18);

        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player s = Bukkit.getPlayer(shadow.getUniqueId());
            Player v = Bukkit.getPlayer(targetId);
            if (s == null || v == null) return;

            // Choppy, approximate "puppeting": walk the target toward wherever
            // Shadows is looking, and keep their head aimed the same way.
            // Minecraft has no real way to let one client drive another client's
            // avatar, so this is the closest practical approximation.
            float yaw = s.getLocation().getYaw();
            float pitch = s.getLocation().getPitch();
            Vector forward = s.getLocation().getDirection().setY(0).normalize().multiply(speed);
            Location next = v.getLocation().add(forward);
            next.setYaw(yaw);
            next.setPitch(pitch);
            v.teleport(next);

            tintScreen(v);
        }, 0L, interval);

        possessionTasks.put(shadow.getUniqueId(), t);
        shadow.sendMessage("Possessing " + target.getName() + ".");
    }

    private void stopPossession(Player shadow, UUID targetId) {
        possessing.remove(shadow.getUniqueId());
        BukkitTask t = possessionTasks.remove(shadow.getUniqueId());
        if (t != null) t.cancel();
    }

    private void tintScreen(Player target) {
        // No real screen-tint shader is possible on a client with no mod --
        // this fakes it with a small, dense cluster of red dust particles
        // parked right in front of the player's own eyes, visible only to them
        // (Player#spawnParticle only sends to that one player).
        Location eye = target.getEyeLocation();
        Location spot = eye.clone().add(eye.getDirection().multiply(0.4));
        target.spawnParticle(Particle.REDSTONE, spot, 10, 0.35, 0.25, 0.35, 0,
                new Particle.DustOptions(Color.fromRGB(140, 0, 0), 2.2f));
    }

    // ---- Chat redirect while possessing ----------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player shadow = event.getPlayer();
        if (!possessing.contains(shadow.getUniqueId())) return;
        UUID targetId = hauntTarget.get(shadow.getUniqueId());
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (target == null) return;

        event.setCancelled(true);
        Bukkit.broadcastMessage("<" + target.getName() + "> " + event.getMessage());
    }

    /** While possessed, the target's own movement input is overridden (see startPossession). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTargetMove(PlayerMoveEvent event) {
        UUID pid = event.getPlayer().getUniqueId();
        for (Map.Entry<UUID, UUID> e : hauntTarget.entrySet()) {
            if (possessing.contains(e.getKey()) && e.getValue().equals(pid)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        endHaunt(event.getPlayer());
        // Also clear anyone who was haunting this player as their target.
        for (UUID shadowId : new HashSet<>(hauntTarget.keySet())) {
            if (id.equals(hauntTarget.get(shadowId))) {
                Player shadow = Bukkit.getPlayer(shadowId);
                if (shadow != null) endHaunt(shadow);
                else hauntTarget.remove(shadowId);
            }
        }
    }

    void shutdown() {
        for (BukkitTask t : possessionTasks.values()) t.cancel();
        possessionTasks.clear();
        possessing.clear();
        hauntTarget.clear();
    }
}
