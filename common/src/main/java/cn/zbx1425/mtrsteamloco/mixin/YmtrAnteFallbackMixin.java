package cn.zbx1425.mtrsteamloco.mixin;

import cn.zbx1425.mtrsteamloco.Main;
import mtr.render.RenderTrains;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 YMTR 的 ANTE 检测返回 false，从而关闭它的 fallback 轨道渲染路径。
 *
 * ── 背景 ──────────────────────────────────────────────────────────
 *
 * YMTR 用类名字符串检测 ANTE：
 *
 *     Class.forName("cn.zbx1425.mtrsteamloco.render.rail.RailRenderDispatcher")
 *
 * 该名字能否解析，取决于 ANTE 的打包布局：forgix 合并的多平台包会把 forge 部分
 * 放在 `forge.` 前缀包下，类名与上面这个不同；而本仓库产出的是单平台扁平结构，
 * 类名恰好与 YMTR 查找的一致。
 *
 * 在后一种情形下，anteFallbackActive 会被置为 true，于是 RenderTrains 对
 * **每一根铁轨**都会调用：
 *
 *     rail.getPosition(0) 与 rail.getPosition(rail.getLength())
 *
 * 这两次曲线插值的结果仅用于和玩家做曼哈顿距离比较。长距离轨道渲染开启后，
 * 通过筛选的铁轨数量很大，这笔开销每帧乘以数千根铁轨，实测会把帧率压到原来的 1/5。
 *
 * ── 本注入的影响范围 ──────────────────────────────────────────────
 *
 * · 长距离渲染由 YMTR 的 maxRailRenderDistance 滑块提供，与本检测无关；
 * · 只影响 anteFallbackActive 一个字段（已核实：RenderTrains 里 isAnteInstalled()
 *   仅被调用一次，即用于计算该字段）；
 * · 关闭后铁轨统一走 renderRailStandard，由本模组的 RenderTrainsMixin 接管；
 * · AnteRailCompat 独立检测 RailExtraSupplier，不受影响，轨道数据镜像照常工作。
 *
 * 注意：isAnteInstalled 在 YMTR 3.6.2.x 中存在（已用 javap 核实），故按严格模式注入；
 * 若换成不含该逻辑的原版 MTR，此处会明确报错而不是静默失效。
 */
@Mixin(value = RenderTrains.class)
public class YmtrAnteFallbackMixin {

    private static boolean ante$logged = false;

    @Inject(method = "isAnteInstalled", at = @At("HEAD"), cancellable = true, remap = false)
    private static void ante$disableYmtrAnteDetection(CallbackInfoReturnable<Boolean> cir) {
        if (!ante$logged) {
            ante$logged = true;
            Main.LOGGER.info("[hANTE] 已关闭 YMTR 的 ANTE fallback 轨道渲染路径"
                    + "（该路径每帧对每根铁轨调用 Rail.getPosition 两次）");
        }
        cir.setReturnValue(false);
    }
}
