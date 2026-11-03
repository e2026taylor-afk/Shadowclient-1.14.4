package dev.quietboost;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ghost mode = spectator (real noclip) + hidden from every non-ghost client
 * (no skin, armor, held item, nametag, tab-list entry) + punching emulated
 * server-side, because spectator clients can't normally attack.
 */
final class GhostMode implements Listener {

    private static final class State {
        GameMode mode;
        Location origin;
        boolean allowFlight;
    }

    private final QuietBoost plugin;
    private final Map<UUID, State> ghosts = new HashMap<>();

    GhostMode(QuietBoost plugin) {
        this.plugin = plugin;
    }

    boolean isGhost(Player p) {
        return ghosts.containsKey(p.getUniqueId());
    }

    void toggle(Player p) {
        if (isGhost(p)) disable(p);
        else enable(p);
    }

    private void enable(Player p) {
        State s = new State();
        s.mode = p.getGameMode();
        s.origin = p.getLocation().clone();
        s.allowFlight = p.getAllowFlight();
        ghosts.put(p.getUniqueId(), s);

        // Hide first, then switch mode, so nobody sees even a one-tick flash.
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p)) continue;
            if (isGhost(o)) p.showPlayer(plugin, o); // ghosts can see each other
            else o.hidePlayer(plugin, p);
        }
        p.setGameMode(GameMode.SPECTATOR);
        status(p, "Ghost mode ON");
    }

    void disable(Player p) {
        State s = ghosts.remove(p.getUniqueId());
        if (s == null) return;

        boolean mayFly = s.allowFlight || s.mode == GameMode.CREATIVE;
        Location safe = findSafe(p.getLocation(), s.origin, !mayFly);
        p.teleport(safe);
        p.setFallDistance(0f);
        p.setGameMode(s.mode);
        if (s.mode == GameMode.SURVIVAL || s.mode == GameMode.ADVENTURE) {
            p.setAllowFlight(s.allowFlight);
            if (!s.allowFlight) p.setFlying(false);
        }

        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p)) o.showPlayer(plugin, p);
        }
        for (UUID id : ghosts.keySet()) { // no longer a ghost: other ghosts vanish from us again
            Player g = Bukkit.getPlayer(id);
            if (g != null) p.hidePlayer(plugin, g);
        }
        status(p, "Ghost mode OFF");
    }

    void disableAll() {
        for (UUID id : new ArrayList<>(ghosts.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) disable(p);
        }
        ghosts.clear();
    }

    private void status(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(ChatColor.GRAY + text));
    }

    // ---- Safe exit from walls ---------------------------------------------

    private boolean open(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        return b.isPassable() && !b.isLiquid();
    }

    private Location findSafe(Location cur, Location origin, boolean dropToGround) {
        World w = cur.getWorld();
        int x = cur.getBlockX(), z = cur.getBlockZ();
        int top = w.getMaxHeight() - 2;
        int ty = Math.max(cur.getBlockY(), 1);
        while (ty < top && !(open(w, x, ty, z) && open(w, x, ty + 1, z))) ty++;
        if (ty >= top) return origin;
        if (dropToGround) {
            int guard = 0;
            while (ty > 1 && open(w, x, ty - 1, z) && guard++ < 320) ty--;
            if (ty <= 1) return origin;
        }
        return new Location(w, x + 0.5, ty, z + 0.5, cur.getYaw(), cur.getPitch());
    }

    // ---- Visibility bookkeeping -------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isGhost(p)) return;
        for (UUID id : ghosts.keySet()) {
            Player g = Bukkit.getPlayer(id);
            if (g != null) p.hidePlayer(plugin, g);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        try {
            disable(e.getPlayer());
        } catch (Throwable ignored) {
        }
    }

    // ---- Punching while in ghost mode --------------------------------------

    /**
     * Spectators can't normally hit anything, so an arm swing is used as the
     * attack trigger: raytrace for the nearest living entity in front of the
     * player and damage it exactly as Player#damage(amount, damager) would.
     * This raytrace passes through blocks (it's a ghost), so it can punch
     * through thin walls within range -- expected, but say if you want line
     * of sight required instead.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (!isGhost(p) || !plugin.getConfig().getBoolean("ghost.punch", true)) return;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        double reach = plugin.getConfig().getDouble("ghost.reach", 4.5);
        boolean friendlyFire = plugin.getConfig().getBoolean("ghost.friendly-fire", false);
        Location eye = p.getEyeLocation();

        RayTraceResult hit = p.getWorld().rayTraceEntities(eye, eye.getDirection(), reach, 0.3, entity -> {
            if (entity.equals(p) || !(entity instanceof LivingEntity)) return false;
            if (!friendlyFire && entity instanceof Player && isGhost((Player) entity)) return false;
            return true;
        });

        Entity target = hit == null ? null : hit.getHitEntity();
        if (!(target instanceof LivingEntity)) return;

        double damage = plugin.getConfig().getDouble("ghost.punch-damage", 1.0);
        ((LivingEntity) target).damage(damage, p);
    }
}
