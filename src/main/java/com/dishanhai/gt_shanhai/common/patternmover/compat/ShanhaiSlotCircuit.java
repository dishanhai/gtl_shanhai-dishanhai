package com.dishanhai.gt_shanhai.common.patternmover.compat;

/**
 * 「每块通配符样板各设一个电路」的<b>纯逻辑核</b>。
 *
 * <h2>为什么单独拆一个类</h2>
 * 本类<b>不 import 任何 Minecraft / GTCEu / LDLib 类</b>，所以它可以被 javac 单独编译、
 * 在裸 JVM 上直接跑断言 —— 这是本工程「先证明检查器自己是对的」那条规矩唯一能落地的形态
 * （游戏不能启动，逻辑核必须能离线执行）。
 *
 * <h2>🔴 三条硬约束在这里的落点</h2>
 * <ol>
 *   <li><b>编号范围 1–32</b>：{@link #isValid(int)} 是唯一的判据；
 *       {@link #normalize(boolean, int)} 把越界值一律折成 {@link #NONE}。
 *       ⚠️ 调用方<b>绝不能</b>把 {@link #NONE} 传给 {@code SlotCacheManager.setCircuitCache(int)}
 *       （上游 {@code IntCircuitBehaviour.setCircuitConfiguration} 在
 *       {@code configuration < 0 || > 32} 时抛 {@code IllegalArgumentException}）——
 *       清除必须走 {@code clearCircuitCache()}，见 {@link Action#CLEAR}。</li>
 *   <li><b>「从没设过」与「设成 N」必须可分</b>：由 {@code keyPresent} 布尔量承载
 *       （样板物品 NBT 里<b>键在不在</b>），而不是靠某个魔法值。
 *       于是 {@code present=true, raw=7} ⇒ 电路 7；{@code present=false} ⇒ 没有电路。</li>
 *   <li><b>存档重载会把电路缓存擦掉</b>：那是 {@code SlotCacheManager} 的行为，与本类无关；
 *       本类只负责「从样板 NBT 算出该槽应当是什么状态」，由机器在<b>还原存档之后</b>重新调一次。</li>
 * </ol>
 */
public final class ShanhaiSlotCircuit {

    /**
     * ⚠️ 2026-10-01：这个键<b>已作废</b>，保留只为清楚说明「电平原先想写在哪、为什么不行」。
     *
     * <p>原设计把电路号写进<b>通配符样板物品</b>的 NBT（键 {@code shanhaiCircuit}）。
     * 无头专服实测（{@code temp\wildcard-circuit-probe}）之后确认这条路是自创的：
     * 上游每槽电路的权威存储是 {@code InternalSlot} 自己的
     * {@code SlotCacheManager.circuitCache}（落盘键 {@code virtualCircuit}），
     * 而采样槽 {@code wildcardPatternSlot} 只有 {@code @Persisted}、<b>没有 {@code @DescSynced}</b>
     * ⇒ 客户端读不到那份 NBT ⇒ 面板只能显示默认值
     * （用户第 1 条「重置为 0」、第 2 条「第二格显示第一格」）。
     *
     * <p>现在每槽电路存在机器的 {@code @Persisted @DescSynced int[] slotCircuit} 上。
     * <b>旧存档里可能残留这个键</b> —— 它现在只是一段无人读取的普通 NBT，不会被任何代码读走。
     */
    @Deprecated
    public static final String NBT_KEY = "shanhaiCircuit";

    /** 合法编号下界（上游 {@code IntCircuitBehaviour} 同值）。 */
    public static final int MIN = 1;

    /** 合法编号上界（上游 {@code IntCircuitBehaviour} 同值；15 与 32 是常用档）。 */
    public static final int MAX = 32;

    /**
     * 「没有电路」的规范值。
     *
     * <p>⚠️ 它<b>不是</b>一个可以被写进电路缓存的编号 —— 它只表示「该清除」。
     * 见 {@link Action#CLEAR}。
     */
    public static final int NONE = 0;

    private ShanhaiSlotCircuit() {}

    /** 唯一判据：1–32 之内。 */
    public static boolean isValid(int value) {
        return value >= MIN && value <= MAX;
    }

    /**
     * 从「键在不在 + 键里的原始值」算出规范值。
     *
     * @param keyPresent 样板 NBT 里有没有 {@link #NBT_KEY}
     * @param rawValue   键里的值（{@code keyPresent == false} 时忽略）
     * @return 1–32 的合法编号；键不存在或值越界（例如被外部脚本写坏、或旧版本遗留）⇒ {@link #NONE}
     */
    public static int normalize(boolean keyPresent, int rawValue) {
        return keyPresent && isValid(rawValue) ? rawValue : NONE;
    }

    /**
     * 界面输入 → 规范值。空串 / 非数字 / 越界 / null 一律 {@link #NONE}。
     *
     * <p>这是**兜底**：界面输入框已经用 {@code setNumbersOnly(0, 32)} 限过，
     * 但客户端输入不可信，服务端仍要过一遍这个函数。
     */
    public static int parseInput(String text) {
        if (text == null) {
            return NONE;
        }
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return NONE;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException notANumber) {
            return NONE;
        }
        return isValid(parsed) ? parsed : NONE;
    }

    /** 界面输入框里该显示什么：没有电路 ⇒ {@code "0"}（= 不设置），否则十进制编号。 */
    public static String toInputText(int normalized) {
        return Integer.toString(normalized == NONE ? 0 : normalized);
    }

    /** 面板题头里那个「当前值」：没有电路 ⇒ {@code "0 (不使用)"} 里的数字部分，用 {@link #NONE_TEXT}。 */
    public static final String NONE_TEXT = "—";

    /** 面板题头里显示的当前值文本。 */
    public static String toDisplayText(int normalized) {
        return normalized == NONE ? NONE_TEXT : Integer.toString(normalized);
    }

    /**
     * 该规范值在应用时应当执行的动作。
     *
     * <p>🔴 这就是硬约束①的机器可判定形态：调用方**只能**照它分派，
     * 于是「传 -1 / 0 给 setCircuitCache」这条错路在结构上被堵死。
     */
    public static Action actionFor(int normalized) {
        return normalized == NONE ? Action.CLEAR : Action.SET;
    }

    /** 应用动作。 */
    public enum Action {
        /** 调用 {@code slot.getCacheManager().setCircuitCache(normalized)}。 */
        SET,
        /** 调用 {@code slot.getCacheManager().clearCircuitCache()}。 */
        CLEAR
    }
}
