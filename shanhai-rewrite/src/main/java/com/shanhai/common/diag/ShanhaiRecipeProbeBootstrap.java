package com.shanhai.common.diag;

import com.shanhai.ShanhaiMod;

import net.minecraftforge.event.server.ServerStartedEvent;

/**
 * 「配方进度被清零」只读诊断探针的<b>装配点</b>（由 {@code ShanhaiMod} 构造器显式挂到 FORGE 总线）。
 *
 * <h2>它只做两件事</h2>
 * <ol>
 *   <li>打一行 {@code config …} —— 当前 {@code [diag] recipe_probe} 的值（「这一轮到底开没开」可判）；</li>
 *   <li>打一行 {@code armed …} —— <b>混入到底挂上了没有</b>的反射实证（「没数据」与「没挂上」可分开）。</li>
 * </ol>
 *
 * <h2>🔴 为什么是 {@code ServerStartedEvent}，而不是 {@code @Mod} 构造器 / CommonSetup</h2>
 * 本工程 2026-09 的实测血账（见 {@code ModuleSetBlockWatch} 类注释）：
 * <b>太早做反射自证会看到"未混入的类"，打出一条假阴性的「探针未挂上」</b>。
 * 世界加载完成那一刻，GT / gtladditions 的类早已被 mixin 变换过，读到的才是真身。
 * 代价是：<b>专用服务端不启动世界就看不到这两行</b> —— 但那种情况本来也不会有机器在跑。
 *
 * <h2>为什么不用 {@code @Mod.EventBusSubscriber}</h2>
 * 自动订阅<b>成功时一行日志都没有</b>，「探针挂上了没有」在日志上不可判
 * （同一口径见 {@code ShanhaiMod} 里那三条取证探针的注释）。显式注册 + 紧跟一行日志 ⇒ 可判。
 */
public final class ShanhaiRecipeProbeBootstrap {

    private ShanhaiRecipeProbeBootstrap() {}

    /** FORGE 总线入口：{@code ServerStartedEvent}（单机的集成服务器也会触发）。 */
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            ShanhaiMod.LOGGER.info("{} {}", RecipeProbe.PREFIX, RecipeProbe.configLine());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} config_line_failed err={}", RecipeProbe.PREFIX, t.toString());
        }
        try {
            final String armed = RecipeProbe.armedLine();
            if (RecipeProbe.armedAll()) {
                ShanhaiMod.LOGGER.info("{} {}", RecipeProbe.PREFIX, armed);
            } else {
                ShanhaiMod.LOGGER.error("{} {} —— 期望全是 true。"
                                + "🔴 上面任何一项 =false 都【不代表现场没有该事件】，"
                                + "只代表那个探针这一轮没在跑（别把「没数据」读成「没发生」）",
                        RecipeProbe.PREFIX, armed);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} armed_selfcheck_failed err={} ⇒ "
                            + "本次日志里没有 armed 行【不能】当作「探针挂上了」的证据",
                    RecipeProbe.PREFIX, t.toString(), t);
        }
    }
}
