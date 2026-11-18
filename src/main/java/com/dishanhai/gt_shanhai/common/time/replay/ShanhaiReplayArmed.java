package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>倒流五层「挂上了没有」的自证行</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么必须有这一行（2026-11 真机实测的直接产物）</h2>
 * 真机日志里同时出现<b>两个空通道</b>：
 * <pre>
 *   progress=records=0/120000 ticks=[-1,-1] dropped=0
 *   be      =records=0/120000 ticks=[-1,-1] dropped=0
 * </pre>
 * 而「机器没在跑」与「我们的钩子根本没挂上」在日志上<b>长得一模一样</b> ——
 * 上一轮只能靠猜。工程里 AE2 那边已经有同款自证行（{@code [SHANHAI-CLOCK] ae_hooks_armed …}），
 * 本类就是把同一种手法搬到倒流五层：
 * <pre>
 *   [SHANHAI-REPLAY] hooks_armed blocks=hook:LevelChunk#setBlockState ok=true
 *                    blocks_destroy=hook:Level#removeBlock ok=true
 *                    entities=hook:EntityJoinLevelEvent ok=true
 *                    progress=hook:gtceu/RecipeLogic#setProgress ok=true
 *                    be=hook:BlockEntity#setChanged ok=true
 *                    scalars=hook:ServerTickEvent#END ok=true
 * </pre>
 *
 * <h2>2. {@code ok} 的口径（这是整行唯一有争议的地方，所以写死）</h2>
 * <b>{@code ok} 表示"注入点存在"，不是"这一轮真的记到东西"</b>：
 * 运行期的判定手法与 AE 那边一致 —— 反射数目标类里有没有我们注入的
 * {@code shanhai$…} 方法（Mixin 变换完成后它们是真身方法）。
 * 于是：<b>{@code ok=true} 但记录还是 0 ⇒ 是"世界里没发生这类事"，不是"没挂上"</b>；
 * <b>{@code ok=false} ⇒ 这一层这一轮确实不在跑</b>，后面一定跟 {@code reason=}。
 *
 * <p>⚠️ 本行<b>不参与任何业务判定</b>；它自己抛异常也只记一条 ERROR
 * （"没有这一行"不允许被读成"挂上了"）。
 */
public final class ShanhaiReplayArmed {

    /** 自证行的前缀（可 grep）。 */
    public static final String PREFIX = "hooks_armed";

    /**
     * 一层。
     *
     * @param id     层的短名（{@code blocks} / {@code progress} / …；别改，日志按它判读）
     * @param hook   挂点的人读描述（{@code LevelChunk#setBlockState} 这类）
     * @param ok     注入点是否存在
     * @param reason {@code ok=false} 时必须非空（{@code gt_absent} / {@code mixin_absent} / …）
     */
    public record Layer(String id, String hook, boolean ok, String reason) {

        public Layer {
            if (reason == null) {
                reason = "";
            }
        }

        /** 一层一项：{@code id=hook:<挂点> ok=<true|false>[ reason=…]。 */
        public String toText() {
            final StringBuilder sb = new StringBuilder();
            sb.append(this.id).append("=hook:").append(this.hook).append(" ok=").append(this.ok);
            if (!this.ok) {
                sb.append(" reason=").append(this.reason.isEmpty() ? "unknown" : this.reason);
            }
            return sb.toString();
        }
    }

    /** 拼成一整行（字段顺序 = 传入顺序，日志判读按名字查，不按位置）。 */
    public static String line(List<Layer> layers) {
        final StringBuilder sb = new StringBuilder(PREFIX);
        if (layers != null) {
            for (Layer l : layers) {
                if (l == null) {
                    continue;
                }
                sb.append(' ').append(l.toText());
            }
        }
        return sb.toString();
    }

    /** 是否全绿（调用方用它决定打 INFO 还是 ERROR）。 */
    public static boolean allArmed(List<Layer> layers) {
        if (layers == null || layers.isEmpty()) {
            return false;
        }
        for (Layer l : layers) {
            if (l == null || !l.ok()) {
                return false;
            }
        }
        return true;
    }

    /** 红的那几层的 id（报告里点名用）。 */
    public static List<String> redIds(List<Layer> layers) {
        final List<String> out = new ArrayList<>();
        if (layers == null) {
            return out;
        }
        for (Layer l : layers) {
            if (l != null && !l.ok()) {
                out.add(l.id());
            }
        }
        return out;
    }

    private ShanhaiReplayArmed() {
    }
}
