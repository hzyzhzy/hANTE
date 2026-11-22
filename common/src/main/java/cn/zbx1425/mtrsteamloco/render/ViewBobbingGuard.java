package cn.zbx1425.mtrsteamloco.render;

import cn.zbx1425.mtrsteamloco.ClientConfig;
import net.minecraft.client.Minecraft;

/**
 * 「修复远坐标抖动」+ 光影同时生效时，暂时关闭原版的视角晃动。
 *
 * <p>原因：轨道几何以区块相对坐标烘焙，绘制时再从 pose stack 取旋转；
 * 而原版 view bobbing 会把晃动直接叠在 pose 上，于是轨道会跟着走动一起抽动
 * （这个抖动与坐标距离无关，出生点也会出现）。
 *
 * <p>行为：
 * <ul>
 *   <li>检测到「光影启用 + 修复远坐标抖动开启」→ 把 {@code options.bobView} 置为 false，
 *       并记住玩家原本的设置；</li>
 *   <li>任一条件不再成立（关光影 / 关修复）→ 恢复玩家原本的设置值。</li>
 * </ul>
 * 玩家手动改过的话以最新读到的值为准。
 */
public final class ViewBobbingGuard {

    /** 是否由本模组临时关闭；null 表示当前没有接管。 */
    private static Boolean autoDisabled = null;
    /** 接管期间每隔多少帧检查一次。 */
    private static int cooldown = 0;

    private ViewBobbingGuard() { }

    public static void tick() {
        if (--cooldown > 0) return;
        cooldown = 20;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return;

            final boolean shaders = !ShadersModHandler.canUseCustomShader();
            final boolean needGuard = shaders && ClientConfig.precisionFix;

            if (needGuard) {
                if (autoDisabled == null) {
                    if (!mc.options.bobView().get()) return;   // 玩家本来就关着，不用管
                    autoDisabled = Boolean.TRUE;
                    mc.options.bobView().set(false);
                    cn.zbx1425.mtrsteamloco.Main.LOGGER.info(
                            "hANTE: shaders + far-coordinate fix active, temporarily disabled vanilla view bobbing");
                }
            } else if (autoDisabled != null) {
                mc.options.bobView().set(autoDisabled);
                autoDisabled = null;
                cn.zbx1425.mtrsteamloco.Main.LOGGER.info(
                        "hANTE: restored vanilla view bobbing setting");
            }
        } catch (Throwable ignored) {
        }
    }
}
