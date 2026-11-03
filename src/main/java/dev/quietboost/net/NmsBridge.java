package dev.quietboost.net;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Thin reflection layer over the server's NMS/CraftBukkit classes, resolved
 * by the server's own version string so it isn't hardcoded to one build.
 * Everything here is best-effort: every public method swallows its own
 * exceptions and returns a "did nothing" result rather than throwing, and
 * logs ONE warning the first time something fails so the rest of the plugin
 * (which does not depend on this) keeps working either way.
 *
 * This is the least certain part of the project -- it was written without
 * being able to compile against an actual NMS jar. If illusions or the map
 * flip don't work, this is the first place to look.
 */
public final class NmsBridge {

    private static final Logger LOG = Bukkit.getLogger();
    private static boolean warned = false;
    public static final String VERSION = Bukkit.getServer().getClass().getPackage().getName()
            .substring("org.bukkit.craftbukkit.".length());

    private NmsBridge() {
    }

    private static void warn(String what, Throwable t) {
        if (warned) return;
        warned = true;
        LOG.warning("[QuietBoost] NMS reflection failed (" + what + "): " + t
                + " -- illusions/map-flip will be skipped. Server version: " + VERSION);
    }

    public static Class<?> nms(String simpleName) {
        try {
            return Class.forName("net.minecraft.server." + VERSION + "." + simpleName);
        } catch (Throwable t) {
            warn("nms class " + simpleName, t);
            return null;
        }
    }

    public static Class<?> obc(String simpleName) {
        try {
            return Class.forName("org.bukkit.craftbukkit." + VERSION + "." + simpleName);
        } catch (Throwable t) {
            warn("obc class " + simpleName, t);
            return null;
        }
    }

    public static Object handle(Player p) {
        try {
            Method m = obc("entity.CraftPlayer").getMethod("getHandle");
            return m.invoke(p);
        } catch (Throwable t) {
            warn("getHandle", t);
            return null;
        }
    }

    public static Object profile(Player p) {
        try {
            Method m = obc("entity.CraftPlayer").getMethod("getProfile");
            return m.invoke(p);
        } catch (Throwable t) {
            warn("getProfile", t);
            return null;
        }
    }

    public static Object connection(Player p) {
        try {
            Object h = handle(p);
            if (h == null) return null;
            Field f = h.getClass().getField("playerConnection");
            Object conn = f.get(h);
            return conn.getClass().getField("networkManager").get(conn);
        } catch (Throwable t) {
            warn("connection", t);
            return null;
        }
    }

    public static Object playerConnection(Player p) {
        try {
            Object h = handle(p);
            if (h == null) return null;
            return h.getClass().getField("playerConnection").get(h);
        } catch (Throwable t) {
            warn("playerConnection", t);
            return null;
        }
    }

    public static void sendPacket(Player p, Object packet) {
        if (packet == null) return;
        try {
            Object pc = playerConnection(p);
            Method send = pc.getClass().getMethod("sendPacket", nms("Packet"));
            send.invoke(pc, packet);
        } catch (Throwable t) {
            warn("sendPacket", t);
        }
    }

    public static Constructor<?> firstConstructor(Class<?> c) {
        if (c == null) return null;
        Constructor<?>[] ctors = c.getDeclaredConstructors();
        if (ctors.length == 0) return null;
        ctors[0].setAccessible(true);
        return ctors[0];
    }
}
