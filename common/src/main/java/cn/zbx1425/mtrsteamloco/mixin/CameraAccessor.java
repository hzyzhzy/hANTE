package cn.zbx1425.mtrsteamloco.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 让其他 mixin 能调用 {@code Camera.setPosition(Vec3)}。
 *
 * 该方法在 1.20.1 里是 **protected**（javap：`protected void m_90581_(Vec3)`），
 * 只有 Camera 自己的 mixin 能通过 @Shadow 调到。车厢渲染那边（RenderTrainsMixin）
 * 需要把相机位置恢复成 Camera.setup 时的值，好让车厢与世界用同一个相机位置，
 * 因此这里开一个 @Invoker 通道。
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Invoker("setPosition")
    void anete$setPosition(Vec3 pos);

    /** getPosition 同样是 protected，RenderTrainsMixin 需要它来算「减掉偏移」后的位置。 */
    @Invoker("getPosition")
    Vec3 anete$getPosition();
}
