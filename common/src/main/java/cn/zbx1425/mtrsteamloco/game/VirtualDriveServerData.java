package cn.zbx1425.mtrsteamloco.game;

import mtr.Registry;
import mtr.data.RailwayData;
import mtr.data.RailwayDataCoolDownModule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Field;

/**
 * Server-side bookkeeping for virtual driving.
 *
 * <p>NeoMTR's built-in NTE hooks this into {@code RailwayDataCoolDownModule}; YMTR 3.6.3 does
 * not have those members, so the state lives here instead. The player is kept in the same
 * no-gravity/no-clip/teleportation state MTR uses while a player rides a train; this is what
 * stops the server from dropping them out of the world while the client-side virtual train moves.
 */
public final class VirtualDriveServerData {

    private static final Map<UUID, Player> DRIVING_PLAYERS = new ConcurrentHashMap<>();
    private static Field playerRidingCoolDownField;
    private static Field playerRidingRouteField;
    private static Field playerSeatsField;
    private static Field playerSeatCoolDownsField;
    private static boolean fieldsTried;

    private VirtualDriveServerData() {
    }

    public static Set<UUID> getDrivingPlayers() {
        return DRIVING_PLAYERS.keySet();
    }

    public static Collection<Player> getDrivingPlayerObjects() {
        return DRIVING_PLAYERS.values();
    }

    /** Called from MTR's server tick event; no mixin needed. */
    public static void tick(MinecraftServer server) {
        for (Player player : DRIVING_PLAYERS.values()) {
            if (player == null || player.isRemoved()) continue;
            holdRidingCooldown(player);
            player.fallDistance = 0.0F;
            player.setNoGravity(true);
            player.noPhysics = true;
            player.getAbilities().mayfly = true;
            if (player instanceof ServerPlayer serverPlayer) serverPlayer.onUpdateAbilities();
            Registry.setInTeleportationState(player, true);
        }
    }

    /**
     * MTR's RailwayDataCoolDownModule decrements playerRidingCoolDown and calls
     * player.stopRiding() when it reaches zero. Holding it at 2 keeps the virtual-drive
     * player attached. Reflection is used so no new Mixin is added at startup.
     */
    private static void initFields() {
        if (fieldsTried) return;
        fieldsTried = true;
        try {
            Class<?> type = RailwayDataCoolDownModule.class;
            playerRidingCoolDownField = type.getDeclaredField("playerRidingCoolDown");
            playerRidingCoolDownField.setAccessible(true);
            playerRidingRouteField = type.getDeclaredField("playerRidingRoute");
            playerRidingRouteField.setAccessible(true);
            playerSeatsField = type.getDeclaredField("playerSeats");
            playerSeatsField.setAccessible(true);
            playerSeatCoolDownsField = type.getDeclaredField("playerSeatCoolDowns");
            playerSeatCoolDownsField.setAccessible(true);
            cn.zbx1425.mtrsteamloco.Main.LOGGER.info("[VD] server riding fields attached");
        } catch (Throwable t) {
            cn.zbx1425.mtrsteamloco.Main.LOGGER.warn("[VD] server riding fields unavailable", t);
        }
    }

    private static void clearRidingState(Player player) {
        initFields();
        try {
            RailwayData data = RailwayData.getInstance(player.level());
            if (data == null) return;
            Object module = data.railwayDataCoolDownModule;
            removeFromMap(player, module, playerRidingCoolDownField);
            removeFromMap(player, module, playerRidingRouteField);
            removeFromMap(player, module, playerSeatsField);
            removeFromMap(player, module, playerSeatCoolDownsField);
        } catch (Throwable ignored) {
        }
    }

    private static void removeFromMap(Player player, Object module, Field field) throws IllegalAccessException {
        if (field == null) return;
        Object map = field.get(module);
        if (map instanceof Map<?, ?>) {
            ((Map<?, ?>) map).remove(player);
        }
    }

    private static void holdRidingCooldown(Player player) {
        initFields();
        if (playerRidingCoolDownField == null) return;
        try {
            RailwayData data = RailwayData.getInstance(player.level());
            if (data == null) return;
            Object map = playerRidingCoolDownField.get(data.railwayDataCoolDownModule);
            if (map instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<Player, Integer> cooldown = (Map<Player, Integer>) map;
                cooldown.put(player, 2);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void updatePlayerInVirtualDrive(Player player, boolean isDriving) {
        if (isDriving) {
            try {
                if (player.isPassenger()) player.stopRiding();
                clearRidingState(player);
                player.fallDistance = 0.0F;
                player.setNoGravity(true);
                player.noPhysics = true;
                player.getAbilities().mayfly = true;
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.onUpdateAbilities();
                }
                holdRidingCooldown(player);
                DRIVING_PLAYERS.put(player.getUUID(), player);
                Registry.setInTeleportationState(player, true);
                cn.zbx1425.mtrsteamloco.Main.LOGGER.info("[VD] server enter player={}", player.getUUID());
            } catch (Throwable t) {
                cn.zbx1425.mtrsteamloco.Main.LOGGER.warn("[VD] server enter failed", t);
            }
        } else {
            DRIVING_PLAYERS.remove(player.getUUID());
            try {
                if (player.isPassenger()) player.stopRiding();
                clearRidingState(player);
            } catch (Throwable t) {
                cn.zbx1425.mtrsteamloco.Main.LOGGER.warn("[VD] server riding cleanup failed", t);
            } finally {
                player.setNoGravity(false);
                player.noPhysics = false;
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.gameMode.getGameModeForPlayer().updatePlayerAbilities(player.getAbilities());
                    serverPlayer.onUpdateAbilities();
                }
                Registry.setInTeleportationState(player, false);
                cn.zbx1425.mtrsteamloco.Main.LOGGER.info("[VD] server exit player={}", player.getUUID());
            }
        }
    }
}
