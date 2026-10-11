package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import cn.zbx1425.mtrsteamloco.data.Rolling;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow @Final Minecraft minecraft;
    @Unique private Boolean hideGuiOptionCache = null;

#if MC_VERSION >= "11903"
    @Inject(method = "getProjectionMatrix", at = @At("TAIL"), cancellable = true)
    void getProjectionMatrixTail(double fov, CallbackInfoReturnable<org.joml.Matrix4f> cir) {
        if (RailRenderDispatcher.isPreviewingModel) {
            org.joml.Matrix4f result = new org.joml.Matrix4f();
            result.translation(0.5f, 0f, 0f);
            result.scale(0.8f, 0.8f, 1f);
            result.mul(cir.getReturnValue());
#else
    @Inject(method = "getProjectionMatrix", at = @At("TAIL"), cancellable = true)
    void getProjectionMatrixTail(double fov, CallbackInfoReturnable<com.mojang.math.Matrix4f> cir) {
        if (RailRenderDispatcher.isPreviewingModel) {
            com.mojang.math.Matrix4f result = com.mojang.math.Matrix4f.createTranslateMatrix(0.5f, 0f, 0f);
            result.multiply(com.mojang.math.Matrix4f.createScaleMatrix(0.8f, 0.8f, 1f));
            result.multiply(cir.getReturnValue());
#endif

            cir.setReturnValue(result);
        }
        if (RailRenderDispatcher.isPreviewingModel && hideGuiOptionCache == null) {
            hideGuiOptionCache = minecraft.options.hideGui;
            minecraft.options.hideGui = true;
        } else if (!RailRenderDispatcher.isPreviewingModel && hideGuiOptionCache != null) {
            minecraft.options.hideGui = hideGuiOptionCache;
            hideGuiOptionCache = null;
        }
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
#if MC_VERSION >= "11903"
            target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;normal()Lorg/joml/Matrix3f;",
#else 
            target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;normal()Lcom/mojang/math/Matrix3f;",
#endif
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void injectCustomRotation(
        float partialTick,
        long finishNanoTime,
        PoseStack poseStack,
        CallbackInfo ci
    ) {
        // ★ 滚转在这里生效（旋转整个渲染 PoseStack）—— 这是实测「确实有滚转」的方式，
        // 之前在 CameraMixin 里改成旋转相机的那两次尝试都无效，已回退。
        //
        // 关键改进在 Rolling.applyRolling(PoseStack) 内部：**滚转轴点已从「相机位置」
        // 改成「车厢地板 / 轨道面」**。PoseStack 此刻的原点就是相机位置，而车厢是绕它
        // 自己的中心滚转的，两轴不同心时车厢会相对相机错开、把视野挡住 ——
        // 用户的原话是「被车厢挡住的原因就是滚转轴和车厢不一样」。
        Rolling.applyRolling(poseStack);
        // ★ 诊断：惯性偏移到底有没有进到视图矩阵里。
        //
        // 这一步之前一直没做，导致我在车厢那一侧反复改都没碰到根因。
        // 判据：视图矩阵的平移分量应当 ≈ -(相机位置)；若把「相机位置」换成
        // 「相机位置 - 惯性偏移」，两者的差值就应当等于 -惯性偏移。
        //   · 差值 ≈ -offset → 偏移生效了，问题在别处；
        //   · 差值 ≈ 0 且 offset ≠ 0 → 偏移**没进视图矩阵**（被后续 Camera.setup 覆盖），
        //     那才是「从来没生效过」的直接原因。
#if MC_VERSION >= "11903"
        if (cn.zbx1425.mtrsteamloco.data.Rolling.tickDiag("viewDiag")) {
            final org.joml.Vector3f t = poseStack.last().pose().getTranslation(new org.joml.Vector3f());
            final net.minecraft.world.phys.Vec3 cam = minecraft.gameRenderer.getMainCamera().getPosition();
            final float offMag = cn.zbx1425.mtrsteamloco.data.CameraInertia.currentOffset();
            cn.zbx1425.mtrsteamloco.Main.LOGGER.info("[ANTE-Diag] viewMatrix平移=("
                    + String.format("%.3f", t.x) + ", " + String.format("%.3f", t.y) + ", " + String.format("%.3f", t.z)
                    + ")  -(相机位置)=("
                    + String.format("%.3f", -cam.x) + ", " + String.format("%.3f", -cam.y) + ", " + String.format("%.3f", -cam.z)
                    + ")  差=(" + String.format("%.3f", t.x + cam.x)
                    + ", " + String.format("%.3f", t.y + cam.y)
                    + ", " + String.format("%.3f", t.z + cam.z)
                    + ")  当前惯性偏移量=" + String.format("%.3f", offMag));
        }
#endif
    }
}
