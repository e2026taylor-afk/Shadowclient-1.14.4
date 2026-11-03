package dev.quietboost.net;

import dev.quietboost.QuietBoost;
import dev.quietboost.ShadowAbilities;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Bridges the "shadowclient:main" plugin channel from the Shadow client mod.
 * Every inbound message is a single byte opcode; anyone other than a current
 * stealth admin sending on this channel is ignored outright (belt-and-braces,
 * since only Shadows is expected to have the mod installed at all).
 *
 * Opcodes (client -> server), all zero-payload beyond the opcode byte:
 *   0 = darkness_toggle   1 = darkness_hold_tick
 *   2 = send_home         3 = toggle_possess   4 = end_haunt
 */
public final class ShadowNetwork implements PluginMessageListener {

    public static final String CHANNEL = "shadowclient:main";

    public static final byte OP_DARKNESS_TOGGLE = 0;
    public static final byte OP_DARKNESS_HOLD = 1;
    public static final byte OP_SEND_HOME = 2;
    public static final byte OP_TOGGLE_POSSESS = 3;
    public static final byte OP_END_HAUNT = 4;

    private final QuietBoost plugin;
    private final ShadowAbilities abilities;

    public ShadowNetwork(QuietBoost plugin, ShadowAbilities abilities) {
        this.plugin = plugin;
        this.abilities = abilities;
    }

    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(CHANNEL) || message.length < 1) return;
        if (!abilities.isStealthAdmin(player)) return; // not Shadows: ignore silently

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            byte op = in.readByte();
            switch (op) {
                case OP_DARKNESS_TOGGLE:
                    abilities.onDarknessToggle(player);
                    break;
                case OP_DARKNESS_HOLD:
                    abilities.onDarknessHoldTick(player);
                    break;
                case OP_SEND_HOME:
                    abilities.sendHome(player, null);
                    break;
                case OP_TOGGLE_POSSESS:
                    abilities.togglePossess(player);
                    break;
                case OP_END_HAUNT:
                    abilities.endHaunt(player);
                    break;
                default:
                    break;
            }
        } catch (IOException ignored) {
        }
    }
}
