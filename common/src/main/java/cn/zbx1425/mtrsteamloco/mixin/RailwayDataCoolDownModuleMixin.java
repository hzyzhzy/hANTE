package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.game.VirtualDriveServerData;
import mtr.Registry;
import mtr.data.RailwayDataCoolDownModule;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * YMTR 3.6.3 does not have NeoMTR's playerInVirtualDrive field. Without this, the normal
 * riding cooldown module keeps ticking the player's old riding entry and clears noGravity /
 * noPhysics right after virtual drive starts.
 */
@Mixin(value = RailwayDataCoolDownModule.class, remap = false)
public abstract class RailwayDataCoolDownModuleMixin {

    @Shadow(remap = false)
    @Final
    private Map<Player, Integer> playerRidingCoolDown;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void ante$keepVirtualDrivePlayers(CallbackInfo ci) {
        for (Player player : VirtualDriveServerData.getDrivingPlayerObjects()) {
            playerRidingCoolDown.put(player, 2);
            Registry.setInTeleportationState(player, true);
        }
    }

    @Inject(method = "onPlayerDisconnect", at = @At("HEAD"), remap = false)
    private void ante$clearVirtualDriveOnDisconnect(Player player, CallbackInfo ci) {
        VirtualDriveServerData.getDrivingPlayers().remove(player.getUUID());
    }
}
