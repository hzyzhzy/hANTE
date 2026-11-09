package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.render.rail.PreciseWorldTransform;
import cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase;
import cn.zbx1425.sowcer.math.Matrix4f;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 见 {@link PreciseWorldTransform}。只在 MTR 回放 StoredMatrixTransformations 期间生效，
 * 平时完全走原版逻辑。
 */
@Mixin(PoseStack.class)
public class PoseStackMixin {

    @Inject(method = "translate(DDD)V", at = @At("HEAD"), cancellable = true)
    private void ante$preciseWorldTranslate(double x, double y, double z, CallbackInfo ci) {
        if (!PreciseWorldTransform.consume()) return;
        try {
            PoseStack self = (PoseStack) (Object) this;
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            RailChunkBase.setCameraRelativeTranslation(new Matrix4f(self.last().pose()),
                    x - cam.x, y - cam.y, z - cam.z);
            ci.cancel();
        } catch (Throwable ignored) {
            // 出错就走原版路径
        }
    }
}
