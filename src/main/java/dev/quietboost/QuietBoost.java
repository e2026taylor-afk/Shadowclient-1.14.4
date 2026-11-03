package dev.quietboost;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QuietBoost extends JavaPlugin implements Listener {

    private static final int VANILLA_ITEM_LIFETIME = 6000;

    private BukkitTask task;
    private StealthAdmin stealth;
    private GhostMode ghost;
    private DarknessZone darkness;
    private ShadowAbilities abilities;
    private dev.quietboost.net.ShadowNetwork network;

    private int interval;
    private int minEntities;
    private final Set<String> disabledWorlds = new HashSet<>();

    private boolean adaptive;
    private double lowTps;

    private boolean junkEnabled;
    private int junkLifetimeTicks;
    private final Set<Material> junk = EnumSet.noneOf(Material.class);

    private boolean itemCapEnabled;
    private int itemCap;
    private int itemMinAgeTicks;

    private boolean mobCapEnabled;
    private int mobCap;
    private final Set<EntityType> capTypes = EnumSet.noneOf(EntityType.class);

    private long removedItems;
    private long removedMobs;
    private long passes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        ghost = new GhostMode(this);
        getServer().getPluginManager().registerEvents(ghost, this);
        stealth = new StealthAdmin(this, ghost);
        getServer().getPluginManager().registerEvents(stealth, this);
        darkness = new DarknessZone(this);
        abilities = new ShadowAbilities(this, stealth, darkness);
        getServer().getPluginManager().registerEvents(abilities, this);
        network = new dev.quietboost.net.ShadowNetwork(this, abilities);
        network.register();
        load();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (ghost != null) ghost.disableAll();
        if (darkness != null) darkness.shutdown();
        if (abilities != null) abilities.shutdown();
    }

    private void load() {
        reloadConfig();
        if (task != null) task.cancel();

        interval = Math.max(20, getConfig().getInt("interval-ticks", 200));
        minEntities = Math.max(1, getConfig().getInt("min-entities-in-chunk", 12));
        disabledWorlds.clear();
        disabledWorlds.addAll(getConfig().getStringList("disabled-worlds"));

        adaptive = getConfig().getBoolean("adaptive.enabled", true);
        lowTps = getConfig().getDouble("adaptive.low-tps", 17.5);

        junkEnabled = getConfig().getBoolean("junk-items.enabled", true);
        junkLifetimeTicks = Math.max(20, getConfig().getInt("junk-items.lifetime-seconds", 60) * 20);
        junk.clear();
        for (String s : getConfig().getStringList("junk-items.materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) junk.add(m);
            else getLogger().warning("Unknown material in junk-items: " + s);
        }

        itemCapEnabled = getConfig().getBoolean("item-cap.enabled", true);
        itemCap = Math.max(16, getConfig().getInt("item-cap.per-chunk", 200));
        itemMinAgeTicks = Math.max(0, getConfig().getInt("item-cap.min-age-seconds", 60) * 20);

        mobCapEnabled = getConfig().getBoolean("mob-cap.enabled", true);
        mobCap = Math.max(8, getConfig().getInt("mob-cap.per-chunk-per-type", 64));
        capTypes.clear();
        for (String s : getConfig().getStringList("mob-cap.types")) {
            try {
                capTypes.add(EntityType.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Unknown entity type in mob-cap: " + s);
            }
        }

        if (itemCapEnabled || mobCapEnabled) {
            task = getServer().getScheduler().runTaskTimer(this, this::cleanupPass, interval, interval);
        }
    }

    // ---- 1) Junk items despawn sooner ------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!junkEnabled) return;
        final Item item = event.getEntity();
        if (!junk.contains(item.getItemStack().getType())) return;

        // The thrower is only set after the spawn event, so check next tick
        // and leave anything a player dropped alone.
        getServer().getScheduler().runTask(this, () -> {
            if (!item.isValid() || item.getThrower() != null) return;
            int age = Math.max(1, VANILLA_ITEM_LIFETIME - junkLifetimeTicks);
            if (item.getTicksLived() < age) item.setTicksLived(age);
        });
    }

    // ---- 2 & 3) Per-chunk caps -------------------------------------------

    private void cleanupPass() {
        passes++;

        double factor = 1.0;
        if (adaptive) {
            try {
                double[] tps = Bukkit.getTPS();
                if (tps.length > 0 && tps[0] < lowTps) factor = 0.5;
            } catch (Throwable ignored) {
                // getTPS unavailable: just run at normal strength
            }
        }
        final int iCap = Math.max(16, (int) (itemCap * factor));
        final int mCap = Math.max(8, (int) (mobCap * factor));

        for (World world : getServer().getWorlds()) {
            if (disabledWorlds.contains(world.getName())) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                Entity[] entities = chunk.getEntities();
                if (entities.length < minEntities) continue;

                List<Item> items = new ArrayList<>();
                Map<EntityType, List<LivingEntity>> mobs = new EnumMap<>(EntityType.class);

                for (Entity e : entities) {
                    if (itemCapEnabled && e instanceof Item) {
                        items.add((Item) e);
                    } else if (mobCapEnabled && e instanceof LivingEntity && capTypes.contains(e.getType())) {
                        mobs.computeIfAbsent(e.getType(), k -> new ArrayList<>()).add((LivingEntity) e);
                    }
                }

                if (items.size() > iCap) trimItems(items, iCap);
                for (List<LivingEntity> list : mobs.values()) {
                    if (list.size() > mCap) trimMobs(list, mCap);
                }
            }
        }
    }

    private void trimItems(List<Item> items, int cap) {
        List<Item> candidates = new ArrayList<>();
        for (Item i : items) {
            if (i.getTicksLived() < itemMinAgeTicks) continue;
            if (i.getItemStack().hasItemMeta()) continue; // named, enchanted, lore, books...
            candidates.add(i);
        }
        Collections.sort(candidates, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Integer.compare(b.getTicksLived(), a.getTicksLived()); // oldest first
            }
        });
        int toRemove = Math.min(candidates.size(), items.size() - cap);
        for (int i = 0; i < toRemove; i++) {
            candidates.get(i).remove();
            removedItems++;
        }
    }

    private void trimMobs(List<LivingEntity> mobs, int cap) {
        List<LivingEntity> candidates = new ArrayList<>();
        for (LivingEntity m : mobs) {
            if (m.getCustomName() != null) continue;
            if (m.isLeashed()) continue;
            if (m.isInsideVehicle() || !m.getPassengers().isEmpty()) continue;
            if (m instanceof Tameable && ((Tameable) m).isTamed()) continue;
            candidates.add(m);
        }
        Collections.sort(candidates, new Comparator<LivingEntity>() {
            @Override
            public int compare(LivingEntity a, LivingEntity b) {
                return Integer.compare(b.getTicksLived(), a.getTicksLived());
            }
        });
        int toRemove = Math.min(candidates.size(), mobs.size() - cap);
        for (int i = 0; i < toRemove; i++) {
            candidates.get(i).remove();
            removedMobs++;
        }
    }

    // ---- Command ----------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (label.equalsIgnoreCase("sendhome")) return cmdSendHome(sender, args);
        if (label.equalsIgnoreCase("scare")) return cmdScare(sender, args);
        if (label.equalsIgnoreCase("haunt")) return cmdHaunt(sender, args);
        if (label.equalsIgnoreCase("darkness")) return cmdDarkness(sender);
        if (label.equalsIgnoreCase("possess")) return cmdPossess(sender);
        if (label.equalsIgnoreCase("endhaunt")) return cmdEndHaunt(sender);

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("QuietBoost: " + passes + " passes, removed "
                    + removedItems + " items and " + removedMobs + " mobs since startup.");
            return true;
        }
        if (args[0].equalsIgnoreCase("stealth")) {
            return stealth.handle(sender, args);
        }
        if (args[0].equalsIgnoreCase("reload")) {
            load();
            sender.sendMessage("QuietBoost config reloaded.");
            return true;
        }
        return false;
    }

    private boolean requireShadow(CommandSender sender) {
        if (!(sender instanceof Player) || !stealth.isAdmin(((Player) sender).getUniqueId())) {
            sender.sendMessage(org.bukkit.ChatColor.RED + "Unknown command. Type \"/help\" for help.");
            return false;
        }
        return true;
    }

    private boolean cmdSendHome(CommandSender sender, String[] args) {
        if (!requireShadow(sender)) return true;
        Player shadow = (Player) sender;
        Player target = args.length > 0 ? getServer().getPlayerExact(args[0]) : null;
        if (args.length > 0 && target == null) {
            sender.sendMessage(org.bukkit.ChatColor.RED + "Player not found or not online.");
            return true;
        }
        abilities.sendHome(shadow, target);
        return true;
    }

    private boolean cmdScare(CommandSender sender, String[] args) {
        if (!requireShadow(sender)) return true;
        if (args.length < 2) {
            sender.sendMessage("/scare <player> <message>");
            return true;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(org.bukkit.ChatColor.RED + "Player not found or not online.");
            return true;
        }
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        abilities.scare(target, message);
        return true;
    }

    private boolean cmdHaunt(CommandSender sender, String[] args) {
        if (!requireShadow(sender)) return true;
        Player shadow = (Player) sender;
        if (args.length < 1) {
            sender.sendMessage("/haunt <player>");
            return true;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(org.bukkit.ChatColor.RED + "Player not found or not online.");
            return true;
        }
        abilities.haunt(shadow, target);
        return true;
    }

    private boolean cmdDarkness(CommandSender sender) {
        if (!requireShadow(sender)) return true;
        abilities.onDarknessToggle((Player) sender);
        return true;
    }

    private boolean cmdPossess(CommandSender sender) {
        if (!requireShadow(sender)) return true;
        abilities.togglePossess((Player) sender);
        return true;
    }

    private boolean cmdEndHaunt(CommandSender sender) {
        if (!requireShadow(sender)) return true;
        abilities.endHaunt((Player) sender);
        return true;
    }
}
