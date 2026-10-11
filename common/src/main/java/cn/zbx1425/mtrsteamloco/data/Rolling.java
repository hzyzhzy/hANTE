package cn.zbx1425.mtrsteamloco.data;

import net.minecraft.client.Minecraft;
import cn.zbx1425.sowcer.math.PoseStackUtil;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcer.math.Matrix4f;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import cn.zbx1425.mtrsteamloco.ClientConfig;
#if MC_VERSION >= "11903"
import org.joml.Quaternionf;
import org.joml.AxisAngle4d;
#else
import com.mojang.math.Quaternion;
#endif

public class Rolling {
    private static Rotation rotation = Rotation.IDENTITY;
    /** 上次收到车厢姿态的时间戳（纳秒），用于判断姿态数据是否仍然有效。 */
    private static long lastRotationNanos = 0L;
    /** 超过这个间隔没收到新姿态，即认为玩家已下车或数据中断。 */
    // ★ 必须是「宽松」的超时，不能贴着帧间隔设。
    // 早先设成 150ms：只要 setOffsets 的调用间隔偶尔超过它（列车停站时 ticksElapsed=0、
    // 渲染帧抖动、切镜头），riding 就会翻成 false、rotation 被清成 IDENTITY，下一帧又恢复。
    // 列车渲染与相机于是在「倾斜 / 不倾斜」之间逐帧跳 —— 表现为整列车持续闪烁。
    // 1 秒足够滤掉这类抖动，而真正下车后 1 秒内也会归零。
    private static final long ROTATION_TIMEOUT_NANOS = 1_000_000_000L;

    /**
     * 诊断开关：启动参数 -Dante.rollDebug=true 打开。
     * 每 20 帧打印一次相机滚转的数据来源，用于判断滚转失效断在哪一环：
     *   riding=false          → setRotation 从未被调用（handlePositions 注入那一环没通）
     *   riding=true 但 roll=0 → 姿态收到了，但车厢滚转角是 0（轨道没设滚转/取错了车厢）
     *   roll≠0 却看不见倾斜  → 数据正常，问题在 CameraMixin 的四元数应用环节
     */
    private static final boolean DEBUG = !System.getProperty("ante.rollDebug", "true").equalsIgnoreCase("false");
    /** 上一次输出的乘座状态，用于只在状态翻转时打日志。 */
    private static boolean lastRidingLogged = false;
    /** 首次收到姿态时打一行日志（只打一次），用于确认滚转数据链路是否打通。 */
    private static boolean loggedFirst = false;
    /**
     * 玩家当前是否正乘坐在列车上。
     * 不能用 rotation.isIdentity() 判断：列车在笔直水平轨道上时 yaw/pitch/roll 都接近 0，
     * 会误判为"没在车上"。setRotation 只在乘坐时被 VehicleRidingClientMixin 调用，故以此为准。
     */
    private static boolean riding = false;

    /** 低通后的滚转角（弧度）：抹平轨道切换处的 roll 突跳，避免相机位置单帧跳变。 */
    private static float smoothRoll = 0.0f;
    private static long lastRollSmoothNanos = 0L;
    /** 滚转角低通时间常数（秒）。 */
    private static final float ROLL_TAU = 0.15f;

    /** 诊断用：Camera.setup 里**设置之后**的相机位置，供车厢渲染侧在同一帧内对比。 */
    public static float diagSetupCamX, diagSetupCamY, diagSetupCamZ;
    public static long diagSetupNanos = 0L;

    /**
     * ★ 低通后的车厢位置 —— 滚转的轴点。
     *
     * 滚转轴必须和**车厢自身的滚转轴**同心（车厢是绕它自己的中心转的）。
     * 之前用相机位置当轴点，两轴不同心，车厢就相对相机错开、把视野挡住 ——
     * 用户的原话是「被车厢挡住的原因就是滚转轴和车厢不一样」。
     *
     * 但车厢位置来自 setOffsets，是阶梯式更新的，直接拿来当轴点会让相机一跳一跳，
     * 所以这里先对它做一阶低通再用。
     */
    private static float smoothCarX, smoothCarY, smoothCarZ;
    private static boolean hasSmoothCar = false;
    /** 轴点低通时间常数（秒）。 */
    private static final float CAR_POS_TAU = 0.10f;

    /**
     * ★ 直接生效，不走「每帧消费一次」的中转。
     *
     * 原实现把姿态存进 tempRotation，再由 update() 搬到 rotation —— 这要求 update()
     * 每帧**恰好执行一次**。但 RenderTrains.render 带 EntitySeat 参数，只要它在一帧内
     * 被调用多次（每个座位实体一次），第二次 update() 就会把 rotation 重置为 IDENTITY，
     * 相机滚转整个失效。
     *
     * 现在改为：setRotation 立即生效并盖上时间戳，update() 只负责在长时间收不到姿态时
     * 判定为「已下车」并清零。这样 update() 被调用几次都安全。
     */
    public static void setRotation(Rotation newRotation) {
        if (!loggedFirst) {
            loggedFirst = true;
            cn.zbx1425.mtrsteamloco.Main.LOGGER.info(String.format(
                    "[ANTE-Roll] 首次收到车厢姿态：roll=%.2f yaw=%.2f pitch=%.2f reversed=%s carPos=(%.1f, %.1f, %.1f)",
                    newRotation.roll, newRotation.yaw, newRotation.pitch, newRotation.reversed,
                    newRotation.pos.x(), newRotation.pos.y(), newRotation.pos.z()));
        }

        // ★ 滚转角做一阶低通 —— 这是「车体抽搐」的修复。
        //
        // 轨道切换处 roll 是**突跳**的：相邻两根轨道的 rollAngleMap 大小可以是 0 和 4，
        // 列车走过去时 roll 会在一帧内从 0 变成 5°+。相机位置现在随 roll 绕地板轴移动
        // （见 applyRolling(Vector3f,float)），单帧突跳就直接表现为画面一抽；
        // 朝向上的倾斜也会跟着僵一下。
        // 0.15 秒时间常数：把突变抹成平滑过渡，而真实超高（持续数秒）几乎不受影响。
        final long nowNanos = System.nanoTime();
        final float smoothDt = lastRollSmoothNanos == 0L ? 0.016f
                : Math.min(0.1f, (nowNanos - lastRollSmoothNanos) / 1.0e9f);
        lastRollSmoothNanos = nowNanos;
        final float aRoll = 1.0f - (float) Math.exp(-smoothDt / ROLL_TAU);
        smoothRoll += aRoll * (newRotation.roll - smoothRoll);

        // 轴点（车厢地板中心）低通：原始车厢位置是阶梯式更新的，直接用会抖。
        if (!hasSmoothCar) {
            smoothCarX = newRotation.pos.x();
            smoothCarY = newRotation.pos.y();
            smoothCarZ = newRotation.pos.z();
            hasSmoothCar = true;
        } else {
            final float aCar = 1.0f - (float) Math.exp(-smoothDt / CAR_POS_TAU);
            smoothCarX += aCar * (newRotation.pos.x() - smoothCarX);
            smoothCarY += aCar * (newRotation.pos.y() - smoothCarY);
            smoothCarZ += aCar * (newRotation.pos.z() - smoothCarZ);
        }

        rotation = smoothRoll == newRotation.roll ? newRotation
                : new Rotation(newRotation.pos.x(), newRotation.pos.y(), newRotation.pos.z(),
                        newRotation.yaw, newRotation.pitch, smoothRoll, newRotation.reversed);
        riding = true;
        lastRotationNanos = nowNanos;
    }

    public static void update() {
        final long nowNanos = System.nanoTime();
        if (nowNanos - lastRotationNanos > ROTATION_TIMEOUT_NANOS) {
            // ★ 超时（下车 / 停站期间 setOffsets 不再被调用）时**不要瞬间清零**：
            // rotation 突然变成 IDENTITY，滚转会「啪」地消失，又是一次画面抽动。
            // 让低通后的滚转角平滑衰减到 0 再归位，yaw/pitch 也一并淡出。
            if (riding) {
                final float dt = lastRollSmoothNanos == 0L ? 0.016f
                        : Math.min(0.1f, (nowNanos - lastRollSmoothNanos) / 1.0e9f);
                lastRollSmoothNanos = nowNanos;
                smoothRoll *= (float) Math.exp(-dt / ROLL_TAU);
                if (Math.abs(smoothRoll) < 1.0e-4f) {
                    rotation = Rotation.IDENTITY;
                    smoothRoll = 0.0f;
                    lastRollSmoothNanos = 0L;
                    riding = false;
                } else {
                    rotation = new Rotation(rotation.pos.x(), rotation.pos.y(), rotation.pos.z(),
                            rotation.yaw, rotation.pitch, smoothRoll, rotation.reversed);
                }
            } else {
                rotation = Rotation.IDENTITY;
            }
        }

        // 只在「上车 / 下车」状态翻转时打一行。
        // 早先是「每 20 次调用」，而 update() 一帧可能被调用多次（RenderTrains.render 带
        // EntitySeat 参数时会按座位实体重复进入），同样是刷屏源头。状态翻转日志信息量更足。
        if (DEBUG && riding != lastRidingLogged) {
            lastRidingLogged = riding;
            cn.zbx1425.mtrsteamloco.Main.LOGGER.info(String.format(
                    "[ANTE-Roll] 乘座状态变化：riding=%s roll=%.3f yaw=%.1f pitch=%.1f carPos=(%.1f, %.1f, %.1f)",
                    riding, rotation.roll, rotation.yaw, rotation.pitch,
                    rotation.pos.x(), rotation.pos.y(), rotation.pos.z()));
        }
    }

    private static long lastRollDiagNanos = 0L;
    private static long lastHandleDiagNanos = 0L;

    /** 诊断最小间隔（纳秒）。 */
    private static final long DIAG_INTERVAL_NANOS = 500_000_000L;

    /**
     * ★ 诊断日志总开关：**默认关闭**（要排查时加启动参数 `-Dante.rollDiag=true`）。
     *
     * 这些日志是同步写在渲染线程上的，节流再密也仍然要拼字符串 + 落盘；
     * 用户报「晃动得厉害时会卡」，所以常态下把整条诊断路径断掉，
     * 只留一次静态 boolean 判断的开销。
     */
    public static final boolean DIAG_ENABLED = System.getProperty("ante.rollDiag", "false").equalsIgnoreCase("true");

    /**
     * 诊断节流 —— ★ 必须按**时间**限速，不能按调用次数。
     *
     * getRollAngleAt 是「每帧 × 每列车 × 每车厢 × 前后端」调用的：一帧轻松几百次。
     * 早先写成「每 40 次调用放行一行」，结果一帧就要打十几行日志；而日志是同步写盘的，
     * 直接把渲染线程拖垮 —— 实测表现为**整个列车渲染持续闪烁**、日志文件从 3MB 涨到 15MB。
     * 改成 0.5 秒一行后，与调用频率无关。
     */
    public static boolean tickRollDiag() {
        if (!DIAG_ENABLED) return false;
        final long now = System.nanoTime();
        if (now - lastRollDiagNanos < DIAG_INTERVAL_NANOS) return false;
        lastRollDiagNanos = now;
        return true;
    }

    /** 诊断节流（handlePositions HEAD 用，与上面独立计时）。 */
    public static boolean tickHandleDiag() {
        if (!DIAG_ENABLED) return false;
        final long now = System.nanoTime();
        if (now - lastHandleDiagNanos < DIAG_INTERVAL_NANOS) return false;
        lastHandleDiagNanos = now;
        return true;
    }

    private static long lastCamDiagNanos = 0L;

    /** 诊断节流（CameraMixin 的滚转注入用，与上面独立计时）。 */
    public static boolean tickCamDiag() {
        if (!DIAG_ENABLED) return false;
        final long now = System.nanoTime();
        if (now - lastCamDiagNanos < DIAG_INTERVAL_NANOS) return false;
        lastCamDiagNanos = now;
        return true;
    }

    /** 通用诊断节流表：同一 key 每 0.5 秒放行一次。 */
    private static final java.util.Map<String, Long> DIAG_LAST = new java.util.HashMap<>();

    public static boolean tickDiag(String key) {
        if (!DIAG_ENABLED) return false;
        final long now = System.nanoTime();
        final Long last = DIAG_LAST.get(key);
        if (last != null && now - last < DIAG_INTERVAL_NANOS) return false;
        DIAG_LAST.put(key, now);
        return true;
    }

    /** 玩家是否正乘坐在列车上（相机惯性等功能应只在此状态下生效）。 */
    public static boolean isRiding() {
        return riding;
    }

    /**
     * 当前乘坐车厢的世界坐标位置。
     *
     * 相机惯性的驱动量必须用它，而不是相机位置：只有**列车自身的运动**才该产生惯性。
     * 若用相机位置驱动，玩家在车厢里走动、转头都会被算成"加速度"而触发晃动。
     */
    public static Vector3f getRidingCarPos() {
        return rotation.pos;
    }

    /** 当前车厢朝向（度）。诊断用。 */
    public static float getRidingCarYaw() {
        return rotation.yaw;
    }

    /** 当前车厢滚转角（度）。诊断用。 */
    public static float getRidingCarRoll() {
        return rotation.roll;
    }

    /**
     * 世界滚转（乘在渲染 PoseStack 上）—— 实测确实有滚转的那条路。
     *
     * ★ 轴点用的是**车厢地板 / 轨道面**，不是 PoseStack 原点（原点就是相机位置）。
     * 车厢是绕它自己的中心滚转的；若滚转轴落在相机位置，两轴不同心，车厢就会相对
     * 相机错开、把第一人称的视野挡住 —— 用户的原话是
     * 「被车厢挡住的原因就是滚转轴和车厢不一样」「滚转轴点应该直接改到轨道上」。
     *
     * PoseStack 此刻的坐标原点即相机位置，所以轴点要换算成相对相机的偏移，
     * 平移到轴点再旋转、最后平移回来。
     */
    public static void applyRolling(PoseStack poseStack) {
        if (!ClientConfig.enableRolling) return;
        if (rotation.isIdentity()) return;
        if (smoothRoll == 0.0f && rotation.roll == 0.0f) return;

        final Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        final float px = smoothCarX - (float) camPos.x;
        final float py = (smoothCarY - 1.0f) - (float) camPos.y;   // 轨道面 = 车厢中心下 1 格
        final float pz = smoothCarZ - (float) camPos.z;

        poseStack.translate(px, py, pz);
        poseStack.mulPose(getRollQuaternion(true));
        poseStack.translate(-px, -py, -pz);
    }

    /**
     * ★ 相机位置的姿态修正：让相机随车体**绕车厢地板高度的纵轴**刚性滚转。
     *
     * 为什么必须同时动位置：相机滚转有两种做法 ——
     *   ① 只转朝向（原地倾斜）：坐姿下眼睛离地板约 1.6 格，一倾斜视线就扫到车厢内壁，
     *      **车厢会把视野挡住**；
     *   ② 随车体刚性滚转：位置与朝向一起绕**地板高度的纵轴**旋转，眼睛始终对着车厢中央，
     *      不会撞到壁上。
     * 这里做的是 ② —— 朝向那半由 GameRendererMixin 的 applyRolling(PoseStack) 负责。
     *
     * 轴点取车厢地板：MTR 的 calculateCar 给出的车厢中心是「轨道面 + 1 格」，
     * 所以 pos.y - 1 就是地板/轨道面高度（VehicleRidingClientMixin 里绕 (0,-1,0) 转动
     * 用的也是同一个高度）。
     *
     * 实现上不写罗德里格公式，而是把坐标系摆正再转：
     *   平移到轴点 → 绕 Y 轴 -yaw（把车体前向对齐到 +Z）→ 绕 Z 轴转 roll（这就是滚转）
     *   → 绕 Y 轴 +yaw 转回来 → 平移回去。
     */
    public static Vector3f applyRolling(Vector3f pos, float eyeHeight) {
        if (!ClientConfig.enableRolling) return pos;
        if (rotation.isIdentity()) return pos;

        final Rotation rot = rotation;
        // roll 为 0 时旋转是恒等变换，直接跳过（省掉每帧的矩阵分配）。
        if (rot.roll == 0.0f) return pos;

        // ★ 轴点 = 车厢地板中心（低通后），**不是**相机位置。
        // 车厢是绕它自己的中心滚转的；相机若绕自身位置转，两轴不同心，车厢就会相对
        // 相机错开并把视野挡住（用户的原话：「滚转轴和车厢不一样」）。
        // 高度取 pos.y - 1：MTR 给的车厢中心是「轨道面 + 1 格」，所以这是地板/轨道面。
        final float pivotX = smoothCarX;
        final float pivotY = smoothCarY - 1.0f;
        final float pivotZ = smoothCarZ;

        final Matrix4f mat = new Matrix4f();
        mat.translate(pivotX, pivotY, pivotZ);
        mat.rotateY(-rot.yaw);
        // 符号与朝向上的滚转保持一致（getRollQuaternion(true) 的约定），否则位置与朝向会互相扭。
        mat.rotateZ(rot.reversed ? -rot.roll : rot.roll);
        mat.rotateY(rot.yaw);
        mat.translate(-pivotX, -pivotY, -pivotZ);

        return mat.transform(pos);
    }



#if MC_VERSION >= "11903"
    public static Quaternionf getRollQuaternion() {
        return getRollQuaternion(false);
    }

    public static Quaternionf getRollQuaternion(boolean reversed) {
        if (rotation.isIdentity()) return new Quaternionf();

        Rotation rot = rotation;

        Matrix4f mat = new Matrix4f();
        mat.rotateX(rot.pitch);
        mat.rotateY(rot.yaw);
        Vector3f fp = mat.transform(new Vector3f(0, 0, 1));
        Quaternionf q = new Quaternionf(new AxisAngle4d(reversed != rot.reversed ? rot.roll : -rot.roll, fp.x(), fp.y(), fp.z()));

        return q;
    }
#else

    public static Quaternion getRollQuaternion() {
        return getRollQuaternion(false);
    }

    public static Quaternion getRollQuaternion(boolean reversed) {
        if (rotation.isIdentity()) return Quaternion.ONE.copy();
        Rotation rot = rotation;

        Matrix4f mat = new Matrix4f();
        mat.rotateX(rot.pitch);
        mat.rotateY(rot.yaw);
        Vector3f fp = mat.transform(new Vector3f(0, 0, 1));
        Quaternion q = new Quaternion(fp.asMoj(), reversed != rot.reversed ? rot.roll : -rot.roll, false);

        return q;
    }
#endif

    public static class Rotation {
        public final Vector3f pos;
        public final float yaw;
        public final float pitch;
        public final float roll;
        public final boolean reversed;

        public static final Rotation IDENTITY = new Rotation(0, 0, 0, 0, 0, 0, false);

        public Rotation(double x, double y, double z, float yaw, float pitch, float roll, boolean reversed) {
            this.pos = new Vector3f(x, y, z);
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.reversed = reversed;
        }

        public boolean isIdentity() {
            return yaw == 0 && pitch == 0 && roll == 0;
        }

        public Rotation copy() {
            return new Rotation(pos.x(), pos.y(), pos.z(), yaw, pitch, roll, reversed);
        }
    }
}