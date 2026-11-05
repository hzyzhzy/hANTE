package cn.zbx1425.mtrsteamloco.fabric;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.mtrsteamloco.gui.ConfigScreen;
import cn.zbx1425.mtrsteamloco.gui.ScriptDebugOverlay;
import cn.zbx1425.mtrsteamloco.gui.VirtualDriveHmi;
import cn.zbx1425.mtrsteamloco.game.DriveInputHandler;
import cn.zbx1425.mtrsteamloco.game.TrainVirtualDrive;
import cn.zbx1425.mtrsteamloco.network.PacketVirtualDrive;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import cn.zbx1425.mtrsteamloco.render.RenderUtil;
import cn.zbx1425.mtrsteamloco.render.train.SteamSmokeParticle;
import mtr.mappings.Text;
import net.fabricmc.api.ClientModInitializer;
#if MC_VERSION >= "11900"
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
#else
import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.minecraft.Util;
#endif
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
#if MC_VERSION < "11903"
import net.fabricmc.fabric.api.event.client.ClientSpriteRegistryCallback;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
#endif
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import cn.zbx1425.mtrsteamloco.render.scripting.ScriptContextManager;

public class MainFabricClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
#if MC_VERSION < "11903"
		ClientSpriteRegistryCallback.event(InventoryMenu.BLOCK_ATLAS).register(((atlasTexture, registry) -> {
			registry.register(new ResourceLocation(Main.MOD_ID, "particle/steam_smoke"));
		}));
#endif

		ParticleFactoryRegistry.getInstance().register(Main.PARTICLE_STEAM_SMOKE, SteamSmokeParticle.Provider::new);

#if MC_VERSION >= "11900"
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(
#else
		ClientCommandManager.DISPATCHER.register(
#endif

					ClientCommandManager.literal("mtrnte")
							.then(ClientCommandManager.literal("config")
									.executes(context -> {
										Minecraft.getInstance().tell(() -> {
											Minecraft.getInstance().setScreen(ConfigScreen.createScreen(Minecraft.getInstance().screen));
										});
										return 1;
									}))
							.then(ClientCommandManager.literal("hideriding")
                                    .executes(context -> {
                                        ClientConfig.hideRidingTrain = !ClientConfig.hideRidingTrain;
                                        return 1;
                                    }))
							.then(ClientCommandManager.literal("clearDebugInfo")
                                    .executes(context -> {
                                        ScriptContextManager.clearDebugInfo();
                                        return 1;
                                    }))
							.then(ClientCommandManager.literal("virtdrive")
									.executes(context -> {
										if (!TrainVirtualDrive.startDrivingRidingTrain()) {
											throw new SimpleCommandExceptionType(Text.literal("你需要先坐在一辆自动运行的列车上")).create();
										}
										return 1;
									})
									.then(ClientCommandManager.literal("atpcutout")
											.executes(context -> {
												if (TrainVirtualDrive.activeTrain == null) {
													throw new SimpleCommandExceptionType(Text.literal("当前没有正在手动驾驶的列车")).create();
												}
												TrainVirtualDrive.activeTrain.atpCutout = !TrainVirtualDrive.activeTrain.atpCutout;
												if (TrainVirtualDrive.activeTrain.atpCutout) {
													TrainVirtualDrive.activeTrain.atpEmergencyBrake = false;
												}
												return 1;
											}))
									.then(ClientCommandManager.literal("reset")
											.executes(context -> {
												PacketVirtualDrive.sendVirtualDriveC2S(false);
												return 1;
											})))
							.then(ClientCommandManager.literal("stat")
									.executes(context -> {
										Minecraft.getInstance().tell(() -> {
											String info = RenderUtil.getRenderStatusMessage();
#if MC_VERSION >= "11900"
											Minecraft.getInstance().player.sendSystemMessage(Text.literal(info));
#else
											Minecraft.getInstance().player.sendMessage(Text.literal(info), Util.NIL_UUID);
#endif
										});
										return 1;
									}))
			);

#if MC_VERSION >= "11900"
		});
#endif

		HudRenderCallback.EVENT.register((guiGraphics, delta) -> {
			if (TrainVirtualDrive.activeTrain != null) {
				TrainVirtualDrive.keepAlive();
				DriveInputHandler.tick();
				VirtualDriveHmi.render(guiGraphics);
			}
			ScriptDebugOverlay.render(guiGraphics);
		});

		WorldRenderEvents.END.register(event -> MainClient.incrementGameTick());
		MainClient.init();
	}

}