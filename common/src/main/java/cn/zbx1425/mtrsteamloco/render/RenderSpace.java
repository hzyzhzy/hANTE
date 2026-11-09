package cn.zbx1425.mtrsteamloco.render;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.render.rail.RailChunkBase;
import cn.zbx1425.sowcer.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 处理「宿主 pose stack 里到底有没有烘焙相机平移」这件事。
 *
 * <p>背景：MTR（以及 YMTR 3.x）在 {@code RenderTrains.render} 开头会做一次
 * {@code matrices.translate(-cameraX, -cameraY, -cameraZ)}，于是后面所有绘制都工作在
 * 「相机相对空间」，往栈里塞世界坐标即可。这也是远处坐标精度崩坏的根源之一：
 * 世界坐标 1000 万时 float 的最小分辨率已经约 1 格。
 *
 * <p>YMTR 上游的「距离现象修复」（提交 80395926）把这次平移**去掉了**，改成每个绘制点
 * 自己在 double 下减相机坐标。于是同一个 mod 在两种宿主上所需的坐标补偿正好相反：
 *
 * <ul>
 *   <li>原版 MTR / 未修复的 YMTR：栈里已含 {@code -cam}，世界坐标直接用；</li>
 *   <li>已修复的 YMTR：栈里不含 {@code -cam}，必须自己减。</li>
 * </ul>
 *
 * <p>本类每帧从一处「已知内容干净」的矩阵（{@code RenderTrainsMixin.renderTail} 里的
 * viewMatrix）探测出宿主属于哪种，供各渲染器查询。探测失败的默认值是「已烘焙」，
 * 也就是 1.1.5 及以前的行为，保证不会比现在更糟。
 */
public final class RenderSpace {

    /** 宿主是否仍把相机平移烘焙在 pose stack 里。默认 true = 老行为。 */
    private static boolean hostCameraBakedIn = true;

    static {
        // YMTR 的「距离现象修复」（80395926）会给 mtr.render.RenderTrains 增加 renderCameraPos 字段，
        // 原版 MTR 没有。这里只用于日志确认，实际判定仍以矩阵几何为准。
        try {
            Class.forName("mtr.render.RenderTrains").getDeclaredField("renderCameraPos");
            Main.LOGGER.info("ANTE render space: host RenderTrains has renderCameraPos -> YMTR with distance-fix");
        } catch (Throwable t) {
            Main.LOGGER.info("ANTE render space: host RenderTrains has NO renderCameraPos -> stock-style MTR");
        }
    }

    private RenderSpace() { }

    /**
     * 每帧调用一次，用「已知形态」的 viewMatrix 判断宿主类型。
     * 传进来的矩阵必须是「相机旋转 × 平移」的干净形态，不能夹带实体变换。
     */
    public static void update(Matrix4f viewMatrix, Vec3 cameraPos) {
        // 相机贴近原点时两种坐标系等价（-cam ≈ 0），直接按「已烘焙」处理。
        // 这样即使探测条件不理想（例如光影的阴影 pass），也不会把整个场景推飞。
        if (cameraPos.lengthSqr() < 256.0 * 256.0) {
            setHostCameraBakedIn(true);
            return;
        }
        setHostCameraBakedIn(RailChunkBase.isCameraBakedIn(viewMatrix, cameraPos));
    }

    private static void setHostCameraBakedIn(boolean value) {
        if (hostCameraBakedIn != value) {
            Main.LOGGER.info("ANTE render space: host pose stack {} camera translation",
                    value ? "includes" : "does NOT include");
        }
        hostCameraBakedIn = value;
    }

    public static boolean isHostCameraBakedIn() {
        return hostCameraBakedIn;
    }

    /**
     * 把宿主给的 view 矩阵统一成「原版 MTR 形态」{@code R * T(-cam)}，
     * 让下游代码（世界坐标脚本、声源定位、轨道脚本模型）不必关心宿主差异。
     */
    public static Matrix4f normalizeView(Matrix4f viewPose) {
        if (!ClientConfig.precisionFix || hostCameraBakedIn) return viewPose;
        try {
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            return RailChunkBase.cameraRelativeMatrix(viewPose, -cam.x, -cam.y, -cam.z);
        } catch (Throwable t) {
            Main.LOGGER.error("ANTE render space: normalizeView failed, falling back", t);
            return viewPose;
        }
    }

    /**
     * 往 pose stack 里塞世界坐标时需要额外叠加的平移。
     * <ul>
     *   <li>老宿主：{@link Vec3#ZERO}（栈里已经减过相机了）；</li>
     *   <li>新宿主：{@code -cameraPos}（我们自己减，且在 double 下减）；</li>
     *   <li>关闭「精度修复」开关：{@link Vec3#ZERO}，回到 1.1.5 的行为。</li>
     * </ul>
     */
    public static Vec3 worldShift() {
        if (!ClientConfig.precisionFix || hostCameraBakedIn) return Vec3.ZERO;
        try {
            return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().scale(-1.0);
        } catch (Throwable t) {
            return Vec3.ZERO;
        }
    }
}
