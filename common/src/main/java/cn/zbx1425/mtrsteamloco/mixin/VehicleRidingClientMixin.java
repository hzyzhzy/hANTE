package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.data.Rolling;
import cn.zbx1425.mtrsteamloco.data.Rolling.Rotation;
import cn.zbx1425.mtrsteamloco.data.VehicleRidingClientExtraSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import mtr.data.VehicleRidingClient;

import java.util.Map;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 相机滚转的姿态数据来源。
 *
 * ★ 设计原则（重要）：**不替换 MTR 的任何方法**。
 *
 * 上游原本的做法是在 setOffsets / renderPlayerAndGetOffset 的 HEAD 处 cancel 掉 MTR 的实现，
 * 用自己的一套重算玩家座位位置、遍历乘客、重渲染玩家模型。这两个方法都位于列车渲染热路径上
 * （RenderTrains.render → simulateTrain → simulateCar），实测把渲染线程吃掉 80%+，
 * 是"不开光影也只有 20 帧"的直接原因。而且经 spark 归因，这部分开销会被记在 mtr 名下，
 * 看起来像 MTR 自己的问题，极具误导性。
 *
 * 现在改为：让 MTR 的 setOffsets 完整跑完（保留其全部优化与副作用），
 * 我们只在 TAIL 处**读取**这一帧的车厢姿态（位置/朝向/俯仰/滚转），
 * 交给 Rolling 供 CameraMixin 给相机用。MTR 的内部状态一概不碰。
 *
 * 代价：玩家实体位置不再叠加滚转偏移（原上游实现会移）。但相机滚转本身完全保留，
 * 而这也是我们真正想要的效果。
 */
@Mixin(VehicleRidingClient.class)
public abstract class VehicleRidingClientMixin implements VehicleRidingClientExtraSupplier {

    /** 每节车厢的滚转角，由 TrainClientMixin 每帧填充。 */
    private float[] roll = new float[0];
    /** 首次 setRoll 时打一行日志，用于确认滚转角数组是否真的送到了。 */
    private boolean rollLogged = false;
    /** 车厢位置数组，由 TrainClientMixin 每帧填充。 */
    private Vec3[] positions;
    /** 列车是否反向，由 TrainClientMixin 每帧填充。 */
    private boolean reversed;

    @Override
    public float getRoll(int index) {
        if (index >= roll.length || index < 0) return 0;
        return roll[index];
    }

    @Override
    public void setRoll(float[] roll) {
        if (!rollLogged) {
            rollLogged = true;
            final StringBuilder sb = new StringBuilder();
            for (float v : roll) sb.append(String.format("%.2f ", v));
            cn.zbx1425.mtrsteamloco.Main.LOGGER.info(
                    "[ANTE-Roll] setRoll 已调用，共 " + roll.length + " 节车厢的滚转角: [" + sb.toString().trim() + "]");
        }
        this.roll = roll;
    }

    @Override
    public void setPositions(Vec3[] positions) {
        this.positions = positions;
    }

    @Override
    public void setReversed(boolean reversed) {
        this.reversed = reversed;
    }

    /** 只读取，不修改。 */
    @Shadow(remap = false) private Map<UUID, Float> percentagesZ;

    /**
     * 只读注入：MTR 的 setOffsets 已经跑完，这里仅把本地玩家所处车厢的姿态记录下来。
     * 不 cancel、不改参数、不碰字段，因此不会影响 MTR 的任何行为。
     */
    @Inject(method = "setOffsets", at = @At("TAIL"), remap = false)
    public void voxy$captureRotationForCamera(
            UUID uuid, double x, double y, double z, float yaw, float pitch,
            double length, int width, boolean doorLeftOpen, boolean doorRightOpen,
            boolean hasPitchAscending, boolean hasPitchDescending,
            float riderOffset, float riderOffsetDismounting,
            boolean shouldSetOffset, boolean shouldSetYaw,
            Runnable clientPlayerCallback, CallbackInfo ci) {

        // ★ 修正玩家实体的插值基准 —— **必须在下面的滚转开关判断之前**，它与滚转无关。
        //
        // MTR 移动玩家的方式是：每渲染帧直接 setPos 到它算好的插值位置，同时把 xo/yo/zo
        // 设为**上一渲染帧**的位置。而原版渲染相机时还会再做一次
        // `lerp(partialTick, xo, x)`，其中 partialTick 是**游戏 tick 内**的插值系数
        // （tick 内固定、跨 tick 重置）。两者语义错位 —— 相机位置于是被 partialTick 调制，
        // 每帧推进量不相等，表现就是「行驶时旁边景物完全不是匀速后退，而是一抽一抽」。
        //
        // 既然 MTR 给的 x/y/z 本身就是渲染帧级的插值结果，把 xo/yo/zo 对齐到它，
        // 原版那次多余的插值就退化成恒等变换，位置恢复逐帧均匀。
        final LocalPlayer fixPlayer = Minecraft.getInstance().player;
        if (fixPlayer != null) {
            fixPlayer.xo = fixPlayer.getX();
            fixPlayer.yo = fixPlayer.getY();
            fixPlayer.zo = fixPlayer.getZ();
        }

        if (!ClientConfig.enableRolling) return;
        if (positions == null || positions.length < 2) return;
        if (percentagesZ == null) return;

        final LocalPlayer clientPlayer = Minecraft.getInstance().player;
        if (clientPlayer == null) return;
        if (!uuid.equals(clientPlayer.getUUID())) return;

        final Float pctZ = percentagesZ.get(uuid);
        if (pctZ == null) return;

        final int ridingCar = Mth.clamp((int) Math.floor(pctZ), 0, positions.length - 2);

        // 与 MTR 一致：只有列车在该方向具有爬坡能力时才应用俯仰
        final float effectivePitch = (pitch < 0 ? hasPitchAscending : hasPitchDescending) ? pitch : 0.0f;

        Rolling.setRotation(new Rotation(
                x, y, z,
                yaw,
                effectivePitch,
                getRoll(ridingCar),
                reversed));
    }
}
