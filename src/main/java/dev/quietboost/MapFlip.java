package dev.quietboost;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Flips the direction Shadows' own "you are here" arrow points on any map
 * they view -- everyone else looking at the same map sees a normal arrow.
 * Pure public Bukkit Map API, no packet hacking: we turn off each map's
 * built-in position tracking and draw our own cursor per viewer instead,
 * rotated 180 degrees only when the viewer is Shadows.
 */
final class MapFlip implements Runnable {

    private final QuietBoost plugin;
    private final UUID shadowId;
    private final Set<Integer> patched = new HashSet<>();
    private BukkitTask task;

    MapFlip(QuietBoost plugin, UUID shadowId) {
        this.plugin = plugin;
        this.shadowId = shadowId;
    }

    void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 20L, 10L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("mapflip.enabled", true)) return;
        Player shadow = Bukkit.getPlayer(shadowId);
        if (shadow == null) return;

        patch(shadow.getInventory().getItemInMainHand());
        patch(shadow.getInventory().getItemInOffHand());
    }

    private void patch(ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.FILLED_MAP) return;
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.MapMeta)) return;
        org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) item.getItemMeta();
        MapView view = meta.getMapView();
        if (view == null || !patched.add(view.getId())) return;

        view.setTrackingPosition(false); // stop vanilla's own "you are here" cursor for everyone viewing it
        view.addRenderer(new SelfCursorRenderer(shadowId));
    }

    private static final class SelfCursorRenderer extends MapRenderer {
        private final UUID shadowId;

        SelfCursorRenderer(UUID shadowId) {
            super(false);
            this.shadowId = shadowId;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (!player.getWorld().equals(map.getWorld())) return;

            double blocksPerPixel = Math.pow(2, map.getScale().getValue());
            double relX = player.getLocation().getX() - map.getCenterX();
            double relZ = player.getLocation().getZ() - map.getCenterZ();
            // Approximate pixel placement (vanilla-like, not pixel-perfect); the
            // part that matters -- the flip -- is exact regardless.
            int px = clamp((int) Math.floor(64 + relX / blocksPerPixel));
            int pz = clamp((int) Math.floor(64 + relZ / blocksPerPixel));

            float yaw = player.getLocation().getYaw();
            while (yaw < 0) yaw += 360;
            yaw %= 360;
            byte rot = (byte) (((int) Math.floor(yaw * 16.0 / 360.0 + 0.5)) & 0xF);
            if (player.getUniqueId().equals(shadowId)) rot = (byte) ((rot + 8) & 0xF);

            MapCursorCollection cursors = canvas.getCursors();
            MapCursor cursor;
            if (cursors.size() == 0) {
                cursor = cursors.addCursor((byte) px, (byte) pz, rot, MapCursor.Type.WHITE_POINTER.getValue(), true);
            } else {
                cursor = cursors.getCursor(0);
                cursor.setX((byte) px);
cursor.setY((byte) pz);
                cursor.setDirection(rot);
                cursor.setVisible(true);
            }
        }

        private int clamp(int v) {
            return Math.max(-128, Math.min(127, v));
        }
    }
}
