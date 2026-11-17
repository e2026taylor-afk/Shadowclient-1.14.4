package dev.quietboost;

import dev.quietboost.net.NmsBridge;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * A fake player entity carrying Crimson_Shade's own skin, spawned via raw
 * NMS packets sent ONLY to one victim's connection -- it is never added to
 * the real Bukkit world, so there is no entity for anyone else's client to
 * ever receive a spawn packet for. That's what fixes "visible to everyone":
 * the earlier Zombie+hideEntity approach spawned a REAL world entity and
 * relied on hideEntity() reliably suppressing it for every other viewer,
 * which is exactly the part that wasn't working. This way there's nothing
 * to hide -- it was never broadcast in the first place.
 *
 * Written from the standard (well-documented, circa-2019) pattern for fake
 * NPCs on this NMS version, but entirely through reflection since there's no
 * NMS jar available to compile or test against here. If it doesn't work,
 * the constructor/field names below are the first thing to check against
 * your actual server's net.minecraft.server.<version> classes.
 */
final class FakeIllusion {

    private final Object fakePlayer; // NMS EntityPlayer, kept as Object (no NMS types at compile time)
    private final Player viewer;
    private boolean spawned;

    private FakeIllusion(Player viewer, Object fakePlayer) {
        this.viewer = viewer;
        this.fakePlayer = fakePlayer;
    }

    /** Builds and immediately sends spawn packets for one illusion, facing toward 'facingTarget'. */
    static FakeIllusion spawn(Player viewer, Player skinSource, Location at, Location facingTarget) {
        try {
            Object mcServer = NmsBridge.minecraftServer();
            Object worldHandle = NmsBridge.worldHandle(at.getWorld());
            Object profile = NmsBridge.profile(skinSource);
            if (mcServer == null || worldHandle == null || profile == null) return null;

            Class<?> entityPlayerClass = NmsBridge.nms("EntityPlayer");
            Class<?> interactManagerClass = NmsBridge.nms("PlayerInteractManager");
            Class<?> worldClass = NmsBridge.nms("World");
            Class<?> worldServerClass = NmsBridge.nms("WorldServer");
            Class<?> minecraftServerClass = NmsBridge.nms("MinecraftServer");
            Class<?> gameProfileClass = profile.getClass();
            if (entityPlayerClass == null || interactManagerClass == null) return null;

            Constructor<?> interactCtor = interactManagerClass.getConstructor(worldClass);
            Object interactManager = interactCtor.newInstance(worldHandle);

            Constructor<?> epCtor = entityPlayerClass.getConstructor(
                    minecraftServerClass, worldServerClass, gameProfileClass, interactManagerClass);
            Object fake = epCtor.newInstance(mcServer, worldHandle, profile, interactManager);

            // Face the given target in full 3D (yaw + pitch) -- Minecraft player
            // models have no roll axis, so yaw+pitch is the full extent of "facing"
            // the game supports.
            Location facing = at.clone();
            Vector dir = facingTarget.toVector().subtract(at.toVector());
            if (dir.lengthSquared() > 0.0001) facing.setDirection(dir);

            Method setPosRot = entityPlayerClass.getMethod(
                    "setPositionRotation", double.class, double.class, double.class, float.class, float.class);
            setPosRot.invoke(fake, at.getX(), at.getY(), at.getZ(), facing.getYaw(), facing.getPitch());

            FakeIllusion illusion = new FakeIllusion(viewer, fake);
            illusion.sendSpawnPackets(facing.getYaw());
            return illusion;
        } catch (Throwable t) {
            return null; // best-effort: if NMS layout doesn't match, just skip the illusion rather than error the zone
        }
    }

    private void sendSpawnPackets(float yaw) throws Exception {
        Class<?> entityPlayerClass = fakePlayer.getClass();
        Class<?> actionEnum = NmsBridge.nms("PacketPlayOutPlayerInfo$EnumPlayerInfoAction");
        Class<?> playerInfoClass = NmsBridge.nms("PacketPlayOutPlayerInfo");
        Class<?> humanClass = NmsBridge.nms("EntityHuman");
        Class<?> entityClass = NmsBridge.nms("Entity");
        Class<?> namedSpawnClass = NmsBridge.nms("PacketPlayOutNamedEntitySpawn");
        Class<?> headRotClass = NmsBridge.nms("PacketPlayOutEntityHeadRotation");

        @SuppressWarnings({"unchecked", "rawtypes"})
        Object addAction = Enum.valueOf((Class<Enum>) actionEnum, "ADD_PLAYER");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object removeAction = Enum.valueOf((Class<Enum>) actionEnum, "REMOVE_PLAYER");

        Object infoArray = Array.newInstance(entityPlayerClass, 1);
        Array.set(infoArray, 0, fakePlayer);
        Constructor<?> infoCtor = playerInfoClass.getConstructor(actionEnum, infoArray.getClass());
        Object addPacket = infoCtor.newInstance(addAction, infoArray);
        Object removePacket = infoCtor.newInstance(removeAction, infoArray);

        Constructor<?> spawnCtor = namedSpawnClass.getConstructor(humanClass);
        Object spawnPacket = spawnCtor.newInstance(fakePlayer);

        byte headYawByte = (byte) (yaw * 256.0f / 360.0f);
        Constructor<?> headCtor = headRotClass.getConstructor(entityClass, byte.class);
        Object headPacket = headCtor.newInstance(fakePlayer, headYawByte);

        NmsBridge.sendPacket(viewer, addPacket);
        NmsBridge.sendPacket(viewer, spawnPacket);
        NmsBridge.sendPacket(viewer, headPacket);
        spawned = true;

        // Drop it from the tab list a couple ticks later -- the entity stays
        // visible in the world, this just stops it cluttering the player list.
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                org.bukkit.Bukkit.getPluginManager().getPlugin("QuietBoost"),
                () -> NmsBridge.sendPacket(viewer, removePacket), 3L);
    }

    void remove() {
        if (!spawned) return;
        try {
            Method getId = fakePlayer.getClass().getMethod("getId");
            int id = (int) getId.invoke(fakePlayer);
            Class<?> destroyClass = NmsBridge.nms("PacketPlayOutEntityDestroy");
            Constructor<?> destroyCtor = destroyClass.getConstructor(int[].class);
            Object destroyPacket = destroyCtor.newInstance((Object) new int[]{id});
            NmsBridge.sendPacket(viewer, destroyPacket);
        } catch (Throwable ignored) {
        }
    }
}
