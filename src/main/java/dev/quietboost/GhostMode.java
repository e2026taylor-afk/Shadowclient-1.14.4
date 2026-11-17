package dev.quietboost;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ghost mode = spectator (real noclip) + hidden from every non-ghost client
 * (no skin, armor, held item, nametag, tab-list entry) + punching emulated
 * server-side, because spectator clients can't normally attack.
 *
 * Vanilla ties true noclip to Spectator specifically -- Creative has flight
 * but keeps collision, and Spectator can't open or move items in containers
 * at all. Those can't both be true at once in vanilla, so: default stays
 * Spectator (noclip); right-clicking a container auto-switches to Creative
 * just long enough to use it, then snaps back on close; toggleCreativeAccess
 * is the explicit version of that same trade for anyone who wants to stay in
 * Creative (full item/creative-menu access, no noclip) until they flip it
 * back themselves.
 */
final class GhostMode implements Listener {

    private static final class State {
        GameMode mode;
        Location origin;
        boolean allowFlight;
    }

    private final QuietBoost plugin;
    private final Map<UUID, State> ghosts = new HashMap<>();
    private final Set<UUID> explicitCreative = new HashSet<>();   // chose to stay in Creative via toggleCreativeAccess
    private final Set<UUID> autoRevertOnClose = new HashSet<>();  // auto-switched for one container, revert on close

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

    /** Package-visible so ShadowAbilities can bring someone into ghost mode
     *  as a side effect of /haunt, rather than silently bypassing it (see
     *  the comment on haunt() in ShadowAbilities for why that mattered). */
    void enable(Player p) {
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

        // Belt-and-suspenders on top of hidePlayer: Invisibility is a
        // completely separate, protocol-level mechanism (the entity's own
        // data, not a per-viewer packet-suppression trick), so it keeps
        // holding even through things like the Creative switch for
        // containers, which is exactly where hidePlayer-based hiding was
        // apparently not surviving. Vanilla Invisibility hides the model,
        // armor, AND held item together -- no separate handling needed for
        // those.
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, false, false));

        announce(p, true);
        status(p, "Ghost mode ON");
    }

    void disable(Player p) {
        State s = ghosts.remove(p.getUniqueId());
        if (s == null) return;
        explicitCreative.remove(p.getUniqueId());
        autoRevertOnClose.remove(p.getUniqueId());

        p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);

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
        announce(p, false);
        status(p, "Ghost mode OFF");
    }

    /**
     * Fakes a real disconnect/reconnect: the standard yellow "X left/joined
     * the game" line to everyone else's chat, the same line to console (via
     * the console sender directly, not this plugin's own logger, so it
     * isn't prefixed with a plugin tag and reads identically to a real one),
     * and the reported player count on the server-list ping drops by one
     * (handled in onPing below) plus /list excludes them (onListCommand).
     * hidePlayer already removes their tab-list row on its own.
     */
    private void announce(Player p, boolean leaving) {
        String msg = ChatColor.YELLOW + p.getName() + (leaving ? " left the game" : " joined the game");
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p)) o.sendMessage(msg);
        }
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    @EventHandler
    public void onPing(com.destroystokyo.paper.event.server.PaperServerListPingEvent event) {
        if (!ghosts.isEmpty()) event.setNumPlayers(Math.max(0, event.getNumPlayers() - ghosts.size()));
    }

    /** Replaces vanilla /list output when any ghost is active, so ghosts never show up in it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onListCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        if (ghosts.isEmpty()) return;
        String msg = event.getMessage().trim().toLowerCase();
        if (!msg.equals("/list")) return;

        event.setCancelled(true);
        java.util.List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isGhost(p)) names.add(p.getName());
        }
        event.getPlayer().sendMessage("There are " + names.size() + " of a max of "
                + Bukkit.getMaxPlayers() + " players online: " + String.join(", ", names));
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

    // ---- Container access while ghosting -----------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isGhost(p) || p.getGameMode() != GameMode.SPECTATOR) return; // already Creative: vanilla just works
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        BlockState state = b.getState();
        if (!(state instanceof InventoryHolder)) return;

        e.setCancelled(true); // stop vanilla's restricted spectator-peek behavior
        p.setGameMode(GameMode.CREATIVE);
        autoRevertOnClose.add(p.getUniqueId());
        p.openInventory(((InventoryHolder) state).getInventory());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        if (!autoRevertOnClose.remove(p.getUniqueId())) return;
        if (isGhost(p) && !explicitCreative.contains(p.getUniqueId())) {
            p.setGameMode(GameMode.SPECTATOR);
        }
    }

    /** Explicit "/ghostcreative" toggle: swap between noclip (Spectator) and
     *  full item/creative-menu access (Creative, no noclip) while still
     *  staying a hidden ghost either way. */
    void toggleCreativeAccess(Player p) {
        if (!isGhost(p)) {
            p.sendMessage("Not in ghost mode.");
            return;
        }
        UUID id = p.getUniqueId();
        if (explicitCreative.remove(id)) {
            p.setGameMode(GameMode.SPECTATOR);
            status(p, "Noclip restored (Creative access off)");
        } else {
            explicitCreative.add(id);
            p.setGameMode(GameMode.CREATIVE);
            status(p, "Creative access on (noclip off until you toggle back)");
        }
    }
}
