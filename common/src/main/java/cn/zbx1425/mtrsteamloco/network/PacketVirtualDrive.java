package cn.zbx1425.mtrsteamloco.network;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.game.VirtualDriveServerData;
import io.netty.buffer.Unpooled;
import mtr.RegistryClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class PacketVirtualDrive {

    public static final ResourceLocation PACKET_VIRTUAL_DRIVE = new ResourceLocation(Main.MOD_ID, "virtual_drive");

    public static void sendVirtualDriveC2S(boolean isDriving) {
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        packet.writeBoolean(isDriving);
        RegistryClient.sendToServer(PACKET_VIRTUAL_DRIVE, packet);
    }

    public static void receiveVirtualDriveC2S(MinecraftServer server, ServerPlayer player, FriendlyByteBuf packet) {
        boolean isDriving = packet.readBoolean();
        server.execute(() -> {
            VirtualDriveServerData.updatePlayerInVirtualDrive(player, isDriving);
            PacketVirtualDrivingPlayers.broadcast(server);
        });
    }
}
