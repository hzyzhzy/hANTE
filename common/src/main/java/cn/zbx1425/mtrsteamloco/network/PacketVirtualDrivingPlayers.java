package cn.zbx1425.mtrsteamloco.network;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.game.VirtualDriveClientData;
import cn.zbx1425.mtrsteamloco.game.VirtualDriveServerData;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import mtr.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

public class PacketVirtualDrivingPlayers {

    public static final ResourceLocation PACKET_VIRTUAL_DRIVING_PLAYERS =
            new ResourceLocation(Main.MOD_ID, "virtual_driving_players");

    public static void broadcast(MinecraftServer server) {
        Set<UUID> drivingPlayers = VirtualDriveServerData.getDrivingPlayers();
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            sendToPlayer(target, drivingPlayers);
        }
    }

    public static void sendToPlayer(ServerPlayer player, Set<UUID> drivingPlayers) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeVarInt(drivingPlayers.size());
        for (UUID uuid : drivingPlayers) {
            packet.writeUUID(uuid);
        }
        Registry.sendToPlayer(player, PACKET_VIRTUAL_DRIVING_PLAYERS, packet);
    }

    public static class Client {
        public static void receiveVirtualDrivingPlayersS2C(FriendlyByteBuf packet) {
            int size = packet.readVarInt();
            Set<UUID> drivingPlayers = new ObjectArraySet<>();
            for (int i = 0; i < size; i++) {
                drivingPlayers.add(packet.readUUID());
            }
            VirtualDriveClientData.drivingPlayers = drivingPlayers;
        }
    }
}
