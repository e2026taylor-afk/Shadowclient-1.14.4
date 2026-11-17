package dev.quietboost;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
    private final GhostMode ghost;
    private final StealthAdmin stealth;
    private final DarknessZone darkness;
    private final InventoryBridge inventoryBridge;
    private final Map<UUID, UUID> hauntTarget = new HashMap<>();   // shadow -> target
    private final Set<UUID> possessing = new HashSet<>();           // shadow uuids currently possessing
    private final Map<UUID, BukkitTask> possessionTasks = new HashMap<>();

    ShadowAbilities(QuietBoost plugin, GhostMode ghost, StealthAdmin stealth, DarknessZone darkness) {
        this.plugin = plugin;
        this.ghost = ghost;
        this.stealth = stealth;
        this.darkness = darkness;
        this.inventoryBridge = new InventoryBridge(plugin);
    }

    InventoryBridge inventoryBridge() {
        return inventoryBridge;
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

    /** Haunts whoever Shadows is currently looking at (bound to a client button). */
    void hauntLookingAt(Player shadow) {
        Player target = lookingAt(shadow);
        if (target == null) {
            shadow.sendMessage("No one in view to haunt.");
            return;
        }
        haunt(shadow, target);
    }

    void haunt(Player shadow, Player target) {
        endHaunt(shadow); // clean slate

        // This used to set GameMode.SPECTATOR directly, bypassing GhostMode
        // entirely. That meant: not hidden from other players while haunting
        // (GhostMode's visibility bookkeeping never ran), and -- the actual
        // reported bug -- permanently stuck in Spectator after /endhaunt if
        // you hadn't separately toggled ghost mode first, since nothing was
        // tracking an original gamemode to restore. Now it goes through
        // GhostMode properly, same as the manual "/" toggle does, and only
        // if they weren't already ghosted (so it doesn't clobber that state
        // if they were).
        if (!ghost.isGhost(shadow)) {
            ghost.enable(shadow);
        }

        hauntTarget.put(shadow.getUniqueId(), target.getUniqueId());
        try {
            shadow.setSpectatorTarget(target); // Paper/Spigot spectate API; if your build lacks it, this line needs adjusting
        } catch (Throwable t) {
            shadow.sendMessage("Could not attach your camera (API mismatch) -- you're in spectator mode but not locked to them.");
        }

        inventoryBridge.open(shadow, target);
    }

    void endHaunt(Player shadow) {
        UUID id = shadow.getUniqueId();
        UUID targetId = hauntTarget.remove(id);
        stopPossession(shadow, targetId);
        inventoryBridge.closeFor(shadow);
        try {
            shadow.setSpectatorTarget(null);
        } catch (Throwable ignored) {
        }
        // Deliberately does NOT call ghost.disable() here: haunting stacks on
        // top of ghost mode rather than implying it. Ending a haunt just
        // detaches the camera -- you're left as a free-floating ghost, same
        // as if you'd never haunted anyone, and still exit that the normal
        // way (the "/" toggle).
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

    // Simulated vertical velocity per possessed target -- tracked by hand
    // instead of relying on Entity#getVelocity()/setVelocity(), because the
    // previous version called setVelocity() and then teleport() in the same
    // tick, and teleport() resets an entity's velocity to zero as a side
    // effect on this server implementation. That meant the velocity we'd
    // just set was being wiped before physics ever got a chance to apply it
    // -- the actual cause of the floating. This version does one combined,
    // self-contained position update per tick instead of two competing ones.
    private final Map<UUID, Double> possessVerticalVelocity = new HashMap<>();
    private final Map<UUID, Boolean> possessOriginalAllowFlight = new HashMap<>();

    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double TERMINAL_VELOCITY = -3.92;

    private void startPossession(Player shadow, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) return;
        possessing.add(shadow.getUniqueId());
        possessVerticalVelocity.put(targetId, 0.0);

        // Vanilla's own "flying is not enabled" kick fires off a player's own
        // allowFlight flag, checked against their OWN movement packets -- it
        // has nothing to do with how we move them server-side, but repeated
        // forced repositioning can make the gap between what their client
        // still thinks is true and what the server now says grow large
        // enough to trip it anyway. Exempting them for the duration removes
        // that risk outright regardless of the exact mechanism.
        possessOriginalAllowFlight.put(targetId, target.getAllowFlight());
        target.setAllowFlight(true);

        int interval = Math.max(1, plugin.getConfig().getInt("haunt.possession-tick-interval", 1));
        double speed = plugin.getConfig().getDouble("haunt.possession-speed", 0.18);

        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player s = Bukkit.getPlayer(shadow.getUniqueId());
            Player v = Bukkit.getPlayer(targetId);
            if (s == null || v == null) return;

            // Choppy, approximate "puppeting" -- real Minecraft netcode has no
            // way to let one client drive another's avatar, so some jank is
            // unavoidable. What this version actually fixes: gravity and
            // collision are now simulated by hand (one teleport per tick,
            // not fighting a second velocity call), so the target properly
            // falls, lands, and can't be moved into a solid block.
            Location cur = v.getLocation();
            World w = cur.getWorld();

            double vy = possessVerticalVelocity.getOrDefault(targetId, 0.0);
            if (v.isOnGround() && vy < 0) vy = 0;
            vy = Math.max(vy - GRAVITY_PER_TICK, TERMINAL_VELOCITY);

            Vector horizontal = s.getLocation().getDirection().setY(0);
            if (horizontal.lengthSquared() > 0.0001) horizontal.normalize();
            horizontal.multiply(speed);

            double nx = cur.getX() + horizontal.getX();
            double ny = cur.getY() + vy;
            double nz = cur.getZ() + horizontal.getZ();

            // Don't walk them into a solid block (this is what was causing
            // the suffocation) -- if the horizontal step would land inside
            // something solid, cancel just that axis.
            if (solid(w, nx, cur.getY(), cur.getZ()) || solid(w, nx, cur.getY() + 1, cur.getZ())) nx = cur.getX();
            if (solid(w, cur.getX(), cur.getY(), nz) || solid(w, cur.getX(), cur.getY() + 1, nz)) nz = cur.getZ();

            // Don't fall/sink into solid ground -- land on top of it instead.
            if (vy < 0 && solid(w, nx, ny, nz)) {
                ny = Math.floor(ny) + 1.0;
                vy = 0;
            }
            possessVerticalVelocity.put(targetId, vy);

            Location next = new Location(w, nx, ny, nz, s.getLocation().getYaw(), s.getLocation().getPitch());
            v.teleport(next);

            tintScreen(v);
        }, 0L, interval);

        possessionTasks.put(shadow.getUniqueId(), t);
        shadow.sendMessage("Possessing " + target.getName() + ".");
    }

    private boolean solid(World w, double x, double y, double z) {
        return w.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).getType().isSolid();
    }

    private void stopPossession(Player shadow, UUID targetId) {
        possessing.remove(shadow.getUniqueId());
        BukkitTask t = possessionTasks.remove(shadow.getUniqueId());
        if (t != null) t.cancel();
        possessVerticalVelocity.remove(targetId);

        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        Boolean original = targetId == null ? null : possessOriginalAllowFlight.remove(targetId);
        if (target != null && original != null) target.setAllowFlight(original);
    }

    /**
     * Vanilla 1.14.4 has no server-controllable full-screen color overlay --
     * no API, no potion effect, nothing that does what a client shader does.
     * The closest REAL thing available is the built-in red damage flash,
     * which is normally tied to actually taking damage. This triggers just
     * that visual (an EntityStatus packet, status 2) with no real damage, no
     * health loss, sent only to the target about themselves -- repeated
     * every tick so it re-fires before the previous flash fully fades,
     * which reads as close to a held red tint as vanilla allows. It will
     * still have a faint pulse to it rather than being perfectly flat; an
     * actual solid "red plastic over the lens" tint needs a client-side mod
     * or shader on the target's end, which they don't have.
     */
    private void tintScreen(Player target) {
        try {
            Object handle = dev.quietboost.net.NmsBridge.handle(target);
            Class<?> statusClass = dev.quietboost.net.NmsBridge.nms("PacketPlayOutEntityStatus");
            Class<?> entityClass = dev.quietboost.net.NmsBridge.nms("Entity");
            if (handle == null || statusClass == null || entityClass == null) return;
            java.lang.reflect.Constructor<?> ctor = statusClass.getConstructor(entityClass, byte.class);
            Object packet = ctor.newInstance(handle, (byte) 2); // 2 = hurt-flash animation, purely cosmetic
            dev.quietboost.net.NmsBridge.sendPacket(target, packet);
        } catch (Throwable ignored) {
        }
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

    // No more PlayerMoveEvent cancellation for the possessed target: fighting
    // their own client's movement packets every tick (teleporting them back)
    // was part of what caused the instability. Pushing them with velocity
    // instead (see startPossession) works WITH the client's own physics
    // simulation rather than against it.

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
