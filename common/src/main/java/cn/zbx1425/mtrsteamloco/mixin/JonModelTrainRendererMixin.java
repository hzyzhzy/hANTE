package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.render.RenderUtil;
import cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase;
import cn.zbx1425.sowcer.math.Matrix4f;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import cn.zbx1425.mtrsteamloco.data.TrainExtraSupplier;
import mtr.data.TrainClient;
import mtr.render.TrainRendererBase;
import mtr.render.JonModelTrainRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import cn.zbx1425.sowcer.math.PoseStackUtil;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = JonModelTrainRenderer.class, remap = false)
public abstract class JonModelTrainRendererMixin extends TrainRendererBase{

    @Shadow @Final private TrainClient train;

    @Inject(method = "renderCar", at = @At("HEAD"), remap = false, cancellable = true)
    public void renderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen, CallbackInfo ci) {
        if (RenderUtil.shouldSkipRenderTrain(train)) ci.cancel();
    }

    @Inject(method = "renderCar", at = @At(value = "INVOKE", target = "Lmtr/mappings/UtilitiesClient;rotateX(Lcom/mojang/blaze3d/vertex/PoseStack;F)V", ordinal = 0, shift = At.Shift.AFTER), remap = true)
    private void injectRenderCar(int carIndex, double x, double y, double z, float yaw, float pitch, boolean doorLeftOpen, boolean doorRightOpen, CallbackInfo ci) {
        float roll = TrainExtraSupplier.getRollAngleAt(train, carIndex);
        boolean isReversed = train.isReversed();
        matrices.translate(0D, 1D, 0D);
        PoseStackUtil.rotZ(matrices, isReversed ? roll : -roll);
        matrices.translate(0D, -1D, 0D);
    }

    /**
     * MTR 自己的列车渲染器里是 {@code matrices.translate(x, y, z)}（世界坐标）。
     * 由于 pose stack 里已经烘焙了 {@code R * (-cam)}，这一步是两个 1e7 量级的 float 相减，
     * 远处坐标下 ULP 达到 1 格，车厢就会抖动。
     * <p>
     * 这里把平移列整体改写成 {@code R * (车厢坐标 - cam)}（double 计算后一次写回），
     * 结果与原来语义等价，但全程只有小数值参与运算。
     * <p>
     * {@code require = 0}：MTR 版本不匹配时静默跳过，绝不让游戏崩。
     */
    @Redirect(method = "renderCar",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0),
            remap = true, require = 0)
    private void antePreciseCarTranslate(PoseStack poseStack, double x, double y, double z) {
        if (!ClientConfig.precisionFix || !ClientConfig.mixinMtrPrecision) {
            poseStack.translate(x, y, z);
            return;
        }
        // 玩家在车上时 MTR 传进来的坐标已包含玩家偏移，pose stack 也不是常规形态，
        // 这时改写平移列会把车厢推飞（表现为上车后模型消失），直接退回原始平移。
        if (train != null && train.getViewOffset() != null) {
            poseStack.translate(x, y, z);
            return;
        }
        try {
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            RailChunkBase.setCameraRelativeTranslation(new Matrix4f(poseStack.last().pose()),
                    x - cam.x, y - cam.y, z - cam.z);
        } catch (Throwable t) {
            poseStack.translate(x, y, z);
        }
    }

    @Redirect(method = "renderConnection",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0),
            remap = true, require = 0)
    private void antePreciseConnTranslate(PoseStack poseStack, double x, double y, double z) {
        if (!ClientConfig.precisionFix || !ClientConfig.mixinMtrPrecision) {
            poseStack.translate(x, y, z);
            return;
        }
        // 玩家在车上时 MTR 传进来的坐标已包含玩家偏移，pose stack 也不是常规形态，
        // 这时改写平移列会把车厢推飞（表现为上车后模型消失），直接退回原始平移。
        if (train != null && train.getViewOffset() != null) {
            poseStack.translate(x, y, z);
            return;
        }
        try {
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            RailChunkBase.setCameraRelativeTranslation(new Matrix4f(poseStack.last().pose()),
                    x - cam.x, y - cam.y, z - cam.z);
        } catch (Throwable t) {
            poseStack.translate(x, y, z);
        }
    }

    @Inject(method = "renderConnection", at = @At("HEAD"), cancellable = true)
    public void renderConnection(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        if (RenderUtil.shouldSkipRenderTrain(train)) ci.cancel();
    }

    @Inject(method = "renderBarrier", at = @At("HEAD"), cancellable = true)
    public void renderBarrier(Vec3 prevPos1, Vec3 prevPos2, Vec3 prevPos3, Vec3 prevPos4, Vec3 thisPos1, Vec3 thisPos2, Vec3 thisPos3, Vec3 thisPos4, double x, double y, double z, float yaw, float pitch, CallbackInfo ci) {
        if (RenderUtil.shouldSkipRenderTrain(train)) ci.cancel();
    }
}
