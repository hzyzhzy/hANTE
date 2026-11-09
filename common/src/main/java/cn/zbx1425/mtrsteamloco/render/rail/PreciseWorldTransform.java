package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.ClientConfig;

/**
 * MTR 的方块实体（屏蔽门、吊板、指示牌、线路图、接触网等）都是通过
 * {@code StoredMatrixTransformations.transform(PoseStack)} 回放矩阵来绘制的，
 * 回放的第一步是 {@code matrices.translate(方块X, 方块Y, 方块Z)}（世界坐标）。
 *
 * <p>而 MTR 已经把相机平移烘焙进了 pose stack，这一步就变成两个 1e7 量级的
 * float 相减，远坐标下 ULP 可达 1 格 —— 这就是屏蔽门/吊板抖动的来源。
 *
 * <p>本类提供「精确替换」模式：在 {@code transform()} 期间，把**第一次**
 * {@code translate(DDD)} 解释成「世界坐标定位」，直接整体改写平移列为
 * {@code R * (世界坐标 - 相机坐标)}（double 计算后一次写回），后续的小偏移
 * 仍然走正常叠加。
 *
 * <p>由「开启 MTR 距离现象修复（Mixin）」开关控制，默认关闭。
 */
public final class PreciseWorldTransform {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> CONSUMED = ThreadLocal.withInitial(() -> false);

    private PreciseWorldTransform() { }

    public static void begin() {
        ACTIVE.set(true);
        CONSUMED.set(false);
    }

    public static void end() {
        ACTIVE.set(false);
        CONSUMED.set(false);
    }

    /**
     * @return true 表示这一次 {@code translate(DDD)} 应被当作世界坐标定位处理
     */
    public static boolean consume() {
        if (!ClientConfig.precisionFix || !ClientConfig.mixinMtrPrecision) return false;
        if (!ACTIVE.get() || CONSUMED.get()) return false;
        CONSUMED.set(true);
        return true;
    }
}
