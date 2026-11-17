package dev.quietboost;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * While haunting, Shadows gets a 54-slot window: their own main inventory on
 * one side, the haunted player's on the other, items freely draggable
 * between them. Opened automatically by ShadowAbilities#haunt, closed by
 * ShadowAbilities#endHaunt.
 *
 * Bukkit's custom inventories are independent ItemStack arrays with no live
 * binding back to a real player's inventory, so this is kept in sync by
 * hand: every click is allowed to resolve against the GUI's own slots
 * normally, then pushed into both real inventories a tick later; a periodic
 * pull catches changes from outside the GUI (picking something up, dying,
 * etc.) on whichever side wasn't just clicked.
 *
 * Scope: only each player's 27 main slots (not hotbar, armor, or offhand) --
 * 27+27 is what actually fits in one double-chest-sized window. Say if you
 * want the rest included too and I'll work out a layout for it.
 */
final class InventoryBridge implements Listener {

    private static final int HALF = 27;
    private static final int FIRST_MAIN_SLOT = 9; // PlayerInventory raw array: 0-8 hotbar, 9-35 main

    private final QuietBoost plugin;
    private final Map<UUID, UUID> openFor = new HashMap<>();   // shadow -> target, while open
    private final Map<UUID, Inventory> guiOf = new HashMap<>(); // shadow -> their GUI instance
    private BukkitTask refreshTask;

    InventoryBridge(QuietBoost plugin) {
        this.plugin = plugin;
    }

    void open(Player shadow, Player target) {
        closeFor(shadow); // no stale GUI/state left over from a previous haunt
        Inventory gui = Bukkit.createInventory(null, 54, "Yours | " + target.getName() + "'s");
        pull(gui, 0, shadow);
        pull(gui, HALF, target);
        shadow.openInventory(gui);
        openFor.put(shadow.getUniqueId(), target.getUniqueId());
        guiOf.put(shadow.getUniqueId(), gui);
        ensureRefreshTask();
    }

    /** Pushes any final changes back and forgets this session -- call when a haunt ends. */
    void closeFor(Player shadow) {
        Inventory gui = guiOf.remove(shadow.getUniqueId());
        openFor.remove(shadow.getUniqueId());
        if (gui != null) {
            push(gui);
            shadow.closeInventory();
        }
    }

    private void ensureRefreshTask() {
        if (refreshTask != null) return;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, 20L);
    }

    private void refreshAll() {
        if (openFor.isEmpty()) {
            if (refreshTask != null) {
                refreshTask.cancel();
                refreshTask = null;
            }
            return;
        }
        for (Map.Entry<UUID, UUID> e : openFor.entrySet()) {
            Player shadow = Bukkit.getPlayer(e.getKey());
            Player target = Bukkit.getPlayer(e.getValue());
            Inventory gui = guiOf.get(e.getKey());
            if (shadow == null || target == null || gui == null) continue;
            pull(gui, 0, shadow);
            pull(gui, HALF, target);
        }
    }

    private void pull(Inventory gui, int offset, Player from) {
        ItemStack[] contents = from.getInventory().getContents();
        for (int i = 0; i < HALF; i++) {
            gui.setItem(offset + i, contents[FIRST_MAIN_SLOT + i]);
        }
    }

    private void push(Inventory gui) {
        UUID shadowId = null;
        for (Map.Entry<UUID, Inventory> e : guiOf.entrySet()) {
            if (e.getValue().equals(gui)) {
                shadowId = e.getKey();
                break;
            }
        }
        if (shadowId == null) return;
        Player shadow = Bukkit.getPlayer(shadowId);
        UUID targetId = openFor.get(shadowId);
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (shadow == null || target == null) return;

        for (int i = 0; i < HALF; i++) {
            shadow.getInventory().setItem(FIRST_MAIN_SLOT + i, gui.getItem(i));
            target.getInventory().setItem(FIRST_MAIN_SLOT + i, gui.getItem(HALF + i));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!guiOf.containsValue(event.getInventory())) return;
        // Let the click resolve against the GUI's own slots first (shift-click,
        // drag, number-key swap -- Bukkit already handles all of that for a
        // plain custom inventory), then push the result into both real
        // inventories one tick later.
        Bukkit.getScheduler().runTask(plugin, () -> push(event.getInventory()));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player shadow = (Player) event.getPlayer();
        if (!guiOf.containsKey(shadow.getUniqueId())) return;
        push(event.getInventory());
        guiOf.remove(shadow.getUniqueId());
        openFor.remove(shadow.getUniqueId());
    }
}
