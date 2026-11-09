package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.render.rail.PreciseWorldTransform;
import com.mojang.blaze3d.vertex.PoseStack;
import mtr.render.StoredMatrixTransformations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 MTR 回放矩阵期间打开「精确世界坐标」模式。 */
@Mixin(value = StoredMatrixTransformations.class, remap = false)
public class StoredMatrixTransformationsMixin {

    @Inject(method = "transform", at = @At("HEAD"))
    private void ante$beginPrecise(PoseStack poseStack, CallbackInfo ci) {
        PreciseWorldTransform.begin();
    }

    @Inject(method = "transform", at = @At("RETURN"))
    private void ante$endPrecise(PoseStack poseStack, CallbackInfo ci) {
        PreciseWorldTransform.end();
    }
}
