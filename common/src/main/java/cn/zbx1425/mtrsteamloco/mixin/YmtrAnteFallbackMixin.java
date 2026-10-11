package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.Main;
import mtr.render.RenderTrains;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ★ 让 YMTR 认为「ANTE 没有安装」，从而关闭它的 ANTE fallback 路径。
 *
 * ── 为什么必须这么做 ────────────────────────────────────────────────
 *
 * YMTR 用**类名字符串**检测 ANTE：
 *
 *     Class.forName("cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher")
 *
 * 而官方发布的 ANTE 经 forgix 合并后，forge 部分的包名带前缀，实际是
 * `forge.cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher` —— 所以这个
 * 检测**在官方 ANTE 下必然失败**，`anteFallbackActive` 恒为 false。
 *
 * 我们自行构建的 jar 是单平台扁平结构，类名恰好就是 YMTR 要找的那个，检测成功、
 * `anteFallbackActive` 变为 true，于是 RenderTrains 对**每一根铁轨**都会调用：
 *
 *     rail.getPosition(0) 与 rail.getPosition(rail.getLength())
 *
 * 这两次曲线插值算出的位置，只为和玩家做曼哈顿距离比较。开启长距离轨道渲染后
 * 通过筛选的铁轨数量很大，这笔开销每帧乘以数千根铁轨，实测把帧率压到原来的 1/5。
 *
 * ── 为什么这是"回到官方行为"而不是"砍掉 YMTR 功能" ────────────────
 *
 * · 长距离渲染由 YMTR 的 maxRailRenderDistance 滑块提供，与 isAnteInstalled() 无关；
 * · 本注入只影响 anteFallbackActive 这一个字段（已核实：RenderTrains 里
 *   isAnteInstalled() 仅被调用一次，即用于计算该字段）；
 * · 检测失败后铁轨统一走 renderRailStandard，由 ANTE 的 RenderTrainsMixin 接管，
 *   与官方 ANTE 完全一致；
 * · AnteRailCompat 独立检测 RailExtraSupplier，不受影响，轨道数据镜像照常工作。
 *
 * 注意：isAnteInstalled 在 YMTR 3.6.2.x 中确实存在（已用 javap 核实），故按严格模式注入；
 * 若将来换成不含该逻辑的原版 MTR，此处会明确报错而不是静默失效。
 */
@Mixin(value = RenderTrains.class)
public class YmtrAnteFallbackMixin {

    private static boolean ante$logged = false;

    @Inject(method = "isAnteInstalled", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ante$disableYmtrAnteDetection(CallbackInfoReturnable<Boolean> cir) {
        if (!ante$logged) {
            ante$logged = true;
            Main.LOGGER.info("[ANTE] 已接管 YMTR 的 ANTE 检测：关闭其 fallback 轨道渲染路径"
                    + "（该路径每帧会对每根铁轨调用 Rail.getPosition 两次，是掉帧的直接原因）");
        }
        cir.setReturnValue(false);
    }
}
