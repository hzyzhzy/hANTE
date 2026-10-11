package cn.zbx1425.mtrsteamloco.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.util.Mth;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.data.CameraInertia;
import cn.zbx1425.mtrsteamloco.render.ShadersModHandler;
import cn.zbx1425.mtrsteamloco.data.Rolling;
#if MC_VERSION >= "11903"
import org.joml.Quaternionf;
#endif

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机侧只负责两件事：**相机惯性**（弹簧-阻尼）与相机位置的姿态修正。
 *
 * ★ 相机滚转（视角随车体倾斜）**不在这里** —— 它由 GameRendererMixin 把滚转四元数乘到
 *   整个渲染 PoseStack 上完成，观感是地平线跟着车体歪过去。
 *
 * 这里曾经也有一份 `rotation.mul(roll)`，注入目标是 Camera.setRotation 内部的
 * `Quaternionf.set(FFFF)`。但 1.20.1 的 setRotation 只调用 `rotationYXZ`，那个注入点
 * **从未命中、而且不会报错**（静默失效）。于是「两处重复，删掉一处」的判断一旦做错，
 * 滚转就会整个消失 —— 实测就是这么丢的。所以这里不再保留任何滚转相关代码：
 * 留着一个不生效的空实现，只会让人误以为它是等效替代。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow public abstract void setPosition(Vec3 p_90582_);
    @Shadow protected abstract Vec3 getPosition();
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;
#if MC_VERSION >= "11903"
    @Shadow @Final private Quaternionf rotation;
    /**
     * 相机的视图基向量访问器。javap 确认这两个方法就是 `getfield f_90555_/f_90556_` + `areturn`，
     * 即**返回字段本身的引用**，因此可以就地旋转它们来改变相机的实际朝向。
     */
    @Shadow public abstract org.joml.Vector3f getUpVector();
    @Shadow public abstract org.joml.Vector3f getLeftVector();
#endif

    /**
     * 相机滚转**已停用**，滚转改由 GameRendererMixin 旋转世界实现（那条路实测确实有滚转）。
     *
     * 试过并失败的两种相机滚转写法，记在这里免得再走一遍：
     *   · 注入 rotationYXZ 之后乘 rotation —— 编译期即报
     *     "Unable to locate method mapping for @At(INVOKE.<target>)"，注入点很可能根本没命中；
     *   · 注入 RETURN 后旋转 getUpVector() / getLeftVector() —— 注入成功但转错了对象：
     *     GameRenderer.renderLevel 渲染时用的是 camera.rotation()（即 f_90559_），
     *     根本不是 up/left 这两个向量。
     * 如果以后要改回「相机滚转」（车厢相对画面静止、只有世界歪），
     * 正确写法应是：RETURN 注入 + rotation.mul(q)。
     */
    @Inject(method = "setRotation(FF)V", at = @At("RETURN"))
    private void anteApplyRollToCamera(float yaw, float pitch, CallbackInfo ci) {
        // 当前不做任何事：滚转由 GameRendererMixin 负责。
    }

    /**
     * ★ 世界侧偏移的放大倍数（= 2）。
     *
     * 惯性偏移现在在 `Camera.setup` 里直接烘焙进相机位置（见 injectSetup），
     * 而车厢那边由 RenderTrainsMixin 的 `anteCarUsesRawCameraPos` 减掉 **1×** 偏移，于是：
     *   · 世界（地形 / 铁轨）晃 2× 偏移；
     *   · 相机相对车厢晃 1× 偏移（≈0.35 格，远小于车厢半宽 1.3 格，不会撞出车壁）。
     *
     * 这个 2 不是随便取的：早先的写法是「setup 里不加、渲染时在 getPosition() 的返回值上加」，
     * 而 setup 内部读到的位置本身已经含偏移、又被写回，实际世界偏移约等于 2×；
     * 用户正是按那个幅度把手感调好的，所以改成烘焙后必须显式乘 2 才保持一致。
     */
    private static final float WORLD_OFFSET_SCALE = 2.0f;

    @Inject(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void injectSetup(BlockGetter p_90576_, Entity p_90577_, boolean p_90578_, boolean p_90579_, float p_90580_, CallbackInfo ci) {
        // 总开关：关掉「视角滚转」时这里立即返回，效果等同于 CameraMixin 根本不存在
        // （不会二次调用 setPosition，也不会推进惯性积分）。这样在游戏内把设置里的
        // 滚转开关一关，就能立刻对比帧率，判断掉帧到底是不是滚转相关代码造成的。
        if (!ClientConfig.enableRolling) return;

        // ★ 这里**不需要**任何帧节流，因为不会累加：每次 Camera.setup 开头，原版都会先用
        //   setPosition(DDD) 把位置重置成玩家真实位置，所以我们下面读到的永远是干净值、
        //   写回去也只影响这一帧。（早先那版是在 getPosition() 的返回值上加偏移，
        //   setup 读到含偏移的值又写回字段，一帧内 setup 被调用多次就会把偏移叠成好几倍 ——
        //   那才是「速度 100 以上时车厢突然晃到一边、又马上弹回来」的真正原因。）

        final float eyeHeight = (float) Mth.lerp(p_90580_, this.eyeHeightOld, this.eyeHeight);

        // ★ 先推进滚转状态机（超时判定「已下车」并清零）。
        // Rolling.update() 原本只挂在 MTR 的 RenderTrains.render(EntitySeat...) 上 ——
        // 那是**座位实体**的渲染器，座位被视锥剔除或玩家已下车时根本不执行，
        // 于是「下车后 1 秒归零」这条兜底会失效，表现为人已下车、世界还一直歪着。
        // Camera.setup 每帧必经，挂在这里才可靠（时间戳机制下多调用几次也无害）。
        Rolling.update();

        // ★ 记录当前相机位置（原版刚设好的干净值，不含滚转位移与惯性偏移）。
        //
        // 用途见 RenderTrainsMixin：车厢渲染要拿到「不含惯性偏移」的位置，
        // 才能让车厢在画面上钉住不动 —— 用户要的观感是「相机在车厢内部晃动，
        // 晃的只有外面的世界」，而不是「相机连同车厢一起被推走」。
        final Vec3 rawPos = getPosition();
        Rolling.diagSetupCamX = (float) rawPos.x;
        Rolling.diagSetupCamY = (float) rawPos.y;
        Rolling.diagSetupCamZ = (float) rawPos.z;
        Rolling.diagSetupNanos = System.nanoTime();

        Vector3f pos = new Vector3f(rawPos);
        // 上游在这里让相机绕脚点旋转（pos -= eyeHeight; pos += R*(0,eyeHeight,0)），
        // 旋转中心落在脚下，表现为「滚转轴偏下、像绕腰在转」。现在保持位置不动。
        pos = Rolling.applyRolling(pos, eyeHeight);

        // 先积分出本帧的惯性偏移（apply 只维护状态，不改 pos）。
        CameraInertia.apply(pos);

        // ★ 把惯性偏移**烘焙进相机位置**，而不是在渲染时给 getPosition() 的返回值加。
        //   Camera.getPosition() 每帧被实体/区块/粒子渲染调用几百次，挂在它上面意味着
        //   每次调用都要过一遍注入回调、方法无法内联，晃动大时还要 new 一个 Vec3 ——
        //   这就是「晃动得厉害时会卡」。烘焙之后那条热路径完全干净，这里每帧只写一次位置。
        //
        //   ⚠ 倍数取 WORLD_OFFSET_SCALE 是为了保持改动前的手感，别去掉（原因见常量注释）。
        if (ClientConfig.enableCameraInertia) {
            final float ox = CameraInertia.getSmoothOffX() * WORLD_OFFSET_SCALE;
            final float oy = CameraInertia.getSmoothOffY() * WORLD_OFFSET_SCALE;
            final float oz = CameraInertia.getSmoothOffZ() * WORLD_OFFSET_SCALE;
            if (ox != 0.0f || oy != 0.0f || oz != 0.0f) {
                pos = new Vector3f(pos.x() + ox, pos.y() + oy, pos.z() + oz);
            }
        }
        setPosition(pos.toVec3());
    }
}
