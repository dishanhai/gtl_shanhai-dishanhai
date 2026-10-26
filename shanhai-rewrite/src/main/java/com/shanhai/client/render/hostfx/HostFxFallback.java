package com.shanhai.client.render.hostfx;

import com.shanhai.ShanhaiMod;

/**
 * 归墟视界枢核 · 动态特效的<b>降级闸门</b>。
 *
 * <h2>四个等级（只升不降）</h2>
 * <pre>
 *   LEVEL_FULL   = 1  全画（内环带 + 飞船 + 18 条光束 + 黑洞）+ 藏真块
 *   LEVEL_SIMPLE = 2  全画（内环带 + 飞船 + 18 条光束 + 黑洞）+ 藏真块；"简化"只允许减光束这类可选减法
 *   LEVEL_HIDDEN = 3  不藏、不画（渲染出问题先停手；已藏的格由渲染器的 restore 闸门按台账还回去）
 *   LEVEL_OFF    = 4  不藏、不画（彻底退出，真方块保持原样）
 * </pre>
 *
 * <h2>🔴 2026-10-24 改：<b>"藏了就必须画，不画就别藏"</b></h2>
 * 内环带与 8 艘飞船现在是<b>真方块烘焙</b>（{@code HostFxBaked} 的静态 VertexBuffer），
 * 他们的格子会被 {@link HostFxHide} 藏成 AIR ⇒ <b>只要藏了，就必须把烘好的顶点缓冲画出来</b>，
 * 否则原地留洞（旧四边形版没这个问题，因为那时根本不藏块）。所以：
 * <ul>
 *   <li>{@link #drawShips()} = {@link #drawAnything()}：画东西的档位（FULL/SIMPLE）<b>飞船照画</b>
 *       —— 飞船已经是三组烘焙之一，画它和画环带是同一批 {@code setupRenderState/drawWithShader}，<b>不额外费</b>；</li>
 *   <li>{@link #hideBlocks()} = 同理只在 FULL/SIMPLE 为 true（原来 {@code LEVEL_HIDDEN} 也藏 ⇒ 藏了不画 ⇒ 留洞）。</li>
 * </ul>
 * 这条不变式有离线静态断言守着（探针 §7：每个等级下"被藏起来但不会被绘制"的格数必须 = 0）。
 *
 * <h2>🔴 纪律</h2>
 * <ul>
 *   <li><b>任何一步失败都不许画出黑块/紫黑方块</b>：三组烘焙用的是真方块模型自己的 RenderType
 *       （贴图来自方块图集，不会缺），18 条光束是<b>无贴图</b>的 {@code POSITION_COLOR} ⇒ 结构上不可能出现
 *       "缺贴图"的紫黑格子；</li>
 *   <li>降级必须留一行可 grep 的日志：{@code [SHANHAI-HOSTFX] fallback=<级别> reason=<原因>}；</li>
 *   <li><b>只升不降</b>：降级后不在运行期"自己好起来"（否则会反复抖：画坏一帧 → 降级 → 又画）。
 *       重新进入世界（{@link #reset()}，由客户端登出/换维度时调）才回到 FULL。</li>
 * </ul>
 */
public final class HostFxFallback {

    private HostFxFallback() {}

    public static final int LEVEL_FULL = 1;
    public static final int LEVEL_SIMPLE = 2;
    public static final int LEVEL_HIDDEN = 3;
    public static final int LEVEL_OFF = 4;

    private static int level = LEVEL_FULL;
    private static String reason = "none";

    public static int level() {
        return level;
    }

    public static String reason() {
        return reason;
    }

    /** 等级名（日志/断言用）。 */
    public static String levelName() {
        return switch (level) {
            case LEVEL_FULL -> "FULL";
            case LEVEL_SIMPLE -> "SIMPLE";
            case LEVEL_HIDDEN -> "HIDDEN";
            default -> "OFF";
        };
    }

    /**
     * 是否画飞船。
     *
     * <p>🔴 2026-10-24 改：原来简化档起就不画（{@code level <= LEVEL_SIMPLE - 1}），但飞船的格子<b>已经被藏成 AIR</b>
     * ⇒ 降一级就留 4 个洞。现在只要还画东西就画飞船（它已烘进 VertexBuffer，不额外费）。
     */
    public static boolean drawShips() {
        return level <= LEVEL_SIMPLE;
    }

    /** 是否画任何东西（{@code LEVEL_HIDDEN} 及以上不画）。 */
    public static boolean drawAnything() {
        return level <= LEVEL_SIMPLE;
    }

    /**
     * 是否还做"藏真块"。
     *
     * <p>🔴 2026-10-24 改：原来 {@code level <= LEVEL_HIDDEN}（<b>只藏不画</b>）⇒ 藏了却不画 = 留洞。
     * 现在只在"画"的档位（FULL/SIMPLE）藏块。降到 HIDDEN/OFF 时渲染器的 {@code fallback_level} 闸门会调
     * {@code HostFxHide.restore}，把已经藏的格子按台账还回去（去抖 5 秒）。
     */
    public static boolean hideBlocks() {
        return level <= LEVEL_SIMPLE;
    }

    /**
     * 降级（只升不降）。
     *
     * @param newLevel 见类注释的 1..4
     * @param why      原因（写进日志，越具体越好：异常类名 + 一句话）
     */
    public static void degrade(int newLevel, String why) {
        if (newLevel <= level) {
            return;                       // 已经是更差（或相同）级别 ⇒ 不重复记
        }
        level = Math.min(LEVEL_OFF, newLevel);
        reason = (why == null) ? "unknown" : why;
        ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] fallback={} reason={}", level, reason);
    }

    /** 重新进入世界时复位（客户端登出/换维度调用）。 */
    public static void reset() {
        if (level != LEVEL_FULL) {
            ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] fallback 复位 → 1（上次降到 {}：{}）", level, reason);
        }
        level = LEVEL_FULL;
        reason = "none";
    }
}
