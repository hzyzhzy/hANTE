package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.MainClient;
import cn.zbx1425.mtrsteamloco.render.RailPicker;
import cn.zbx1425.mtrsteamloco.render.RenderUtil;
import cn.zbx1425.mtrsteamloco.render.ShadersModHandler;
import cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher;
import cn.zbx1425.mtrsteamloco.render.scripting.ScriptContextManager;
import cn.zbx1425.sowcer.util.GlStateTracker;
import cn.zbx1425.sowcerext.model.integration.BufferSourceProxy;
import com.mojang.blaze3d.vertex.PoseStack;
import cn.zbx1425.sowcer.math.Matrix4f;
import mtr.data.Rail;
import net.minecraft.client.player.LocalPlayer;
import mtr.entity.EntitySeat;
import mtr.data.IGui;
import cn.zbx1425.mtrsteamloco.render.block.BlockEntityEyeCandyRenderer;
import cn.zbx1425.mtrsteamloco.render.block.BlockEntityDirectNodeRenderer;
import mtr.render.RenderTrains;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import cn.zbx1425.mtrsteamloco.Main;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import cn.zbx1425.mtrsteamloco.gui.DirectNodeScreen;
import cn.zbx1425.mtrsteamloco.render.RailDistanceRenderer;
import cn.zbx1425.mtrsteamloco.data.CameraInertia;
import cn.zbx1425.mtrsteamloco.data.Rolling;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTrains.class)
public class RenderTrainsMixin implements IGui{

    @Inject(at = @At("HEAD"),
            method = "render(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")
    private static void renderHead(EntitySeat entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, CallbackInfo ci) {
        // 注意：这里**不**拦截阴影 pass。MTR 自己的列车渲染必须在阴影 pass 里照常进行，
        // 否则列车不会往阴影贴图投影（会没有影子）。真正每帧重复且昂贵的是下面 renderTail 里
        // ANTE 那一套轨道渲染/几何提交，拦截点在那里。
        Minecraft.getInstance().level.getProfiler().popPush("MTRRailwayData");
        RenderUtil.commonVertexConsumers = vertexConsumers;
        RenderUtil.commonPoseStack = matrices;
        RenderUtil.updateElapsedTicks();

        // 这里保持干净：不做任何相机位置补偿。
    }

    /**
     * ★ 车厢渲染改用「不含惯性偏移」的相机位置 —— 「相机在车厢里晃、车厢在画面上稳住」的关键。
     *
     * 背景：CameraMixin 把惯性偏移烘焙进了相机位置（`Camera.setup` 里 setPosition），
     * 而 MTR 渲染车厢时用的正是同一个 `Camera.getPosition()` 去算车厢相对相机的位置。
     * 不处理的话车厢会跟着相机一起被推走，两者相对静止 —— 用户的原话就是「相机和车厢硬连接」。
     *
     * 这里减掉 **1×** 偏移（世界侧烘焙的是 2×，见 CameraMixin.WORLD_OFFSET_SCALE）：
     * 车厢落在「真实位置 + 1×偏移」，相机在其外侧 1×偏移处，相对位移 1×偏移
     * （≈0.35 格，远小于车厢半宽 1.3 格，不会穿墙）。
     */
    @Redirect(
        method = "render(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getPosition()Lnet/minecraft/world/phys/Vec3;")
    )
    private static Vec3 anteCarUsesRawCameraPos(Camera camera) {
        final Vec3 pos = ((CameraAccessor) camera).anete$getPosition();
        // 两个开关都要判：关掉滚转时 CameraMixin 根本不会烘焙偏移，此时减偏移会凭空把车厢挪走。
        if (!ClientConfig.enableRolling || !ClientConfig.enableCameraInertia) return pos;
        return pos.subtract(
                CameraInertia.getSmoothOffX(),
                CameraInertia.getSmoothOffY(),
                CameraInertia.getSmoothOffZ());
    }

    @Inject(at = @At("TAIL"),
            method = "render(Lmtr/entity/EntitySeat;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")
    private static void renderTail(EntitySeat entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, CallbackInfo ci) {
        // ★ 只在主 pass 更新滚转状态：Rolling.update() 每帧必须**恰好执行一次**，
        //   光影的阴影 pass 若再执行一次，会把 rotation 重置为 IDENTITY，相机滚转直接失效。
        //
        // ⚠️ 但**绝不能**因此把整个 renderTail 在阴影 pass 里跳过 —— 那正是掉帧的直接原因：
        //   MTR 在阴影 pass 里照常渲染铁轨，触发 onRenderRailStandard 注入 → registerRail
        //   往 currentFrameRails 里塞数据；而负责消费并 clear 它的 prepareDraw/drawRails
        //   却在下面被 return 跳过。队列于是跨帧累积，再以错误的 viewMatrix 提交 ——
        //   表现就是「天空中出现一个随视角移动的列车幻影」，几何量同时暴涨把帧率压到个位数。
        //   上游 1.1.0 在阴影 pass 里是照常渲染的，这里同样照常渲染。
        if (!ShadersModHandler.isRenderingShadowPass()) {
            Rolling.update();
        }

        Minecraft.getInstance().level.getProfiler().popPush("NTERailwayData");
        Matrix4f viewMatrix = new Matrix4f(matrices.last().pose());
        MainClient.railRenderDispatcher.prepareDraw();
        if (ClientConfig.getRailRenderLevel() >= 2) {
            GlStateTracker.capture();
            MainClient.railRenderDispatcher.drawRails(Minecraft.getInstance().level, MainClient.drawScheduler, viewMatrix);
            MainClient.drawScheduler.commitRaw(MainClient.drawContext);

            GlStateTracker.restore();
            if (Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes() && !Minecraft.getInstance().showOnlyReducedInfo()) {
                MainClient.railRenderDispatcher.drawBoundingBoxes(matrices, vertexConsumers.getBuffer(RenderType.lines()));
            }

            MainClient.railRenderDispatcher.drawRailNodes(Minecraft.getInstance().level, MainClient.drawScheduler, viewMatrix);
        }

        // if (ShadersModHandler.isRenderingShadowPass()) {
        //     Main.LOGGER.info("0 shadow pass" + System.currentTimeMillis() + " " + entity);
        // } else {
        //     Main.LOGGER.info("0 normal pass" + System.currentTimeMillis() + " " + entity);
        // }

        BlockEntityEyeCandyRenderer.commit(matrices, vertexConsumers);
        BlockEntityDirectNodeRenderer.commit(matrices, vertexConsumers);

        MainClient.drawContext.drawWithBlaze = !ClientConfig.useRenderOptimization();
        MainClient.drawContext.sortTranslucentFaces = ClientConfig.translucentSort;
        BufferSourceProxy vertexConsumersProxy = new BufferSourceProxy(vertexConsumers);
        MainClient.drawScheduler.commit(vertexConsumersProxy, MainClient.drawContext);
        vertexConsumersProxy.commit();

        if (Minecraft.getInstance().player != null && RailRenderDispatcher.isHoldingBrush) {
            RailPicker.pick();
            RailPicker.render(matrices, vertexConsumers);
            RailDistanceRenderer.render(matrices, vertexConsumers);
        } else {
            RailPicker.pickedRail = null;
        }

        ScriptContextManager.disposeDeadContexts();
    }

    @Shadow(remap = false) 
    private static void renderRailStandard(Level world, Rail rail, float yOffset, boolean renderColors, float railWidth) {
        throw new IllegalStateException("Mixin failed to apply");
    }

    @Inject(at = @At("HEAD"), cancellable = true,
            method = "renderRailStandard(Lnet/minecraft/world/level/Level;Lmtr/data/Rail;FZFLjava/lang/String;FFFF)V")
    private static void onRenderRailStandard(Level world, Rail rail, float yOffset, boolean renderColors, float railWidth, String texture, float u1, float v1, float u2, float v2, CallbackInfo ci) {
        if (ClientConfig.getRailRenderLevel() == 0) {
            ci.cancel();
            return;
        }
        if (ClientConfig.getRailRenderLevel() >= 2) {
            boolean railAccepted = MainClient.railRenderDispatcher.registerRail(rail);
            if (railAccepted) ci.cancel();
        }
    }

}
