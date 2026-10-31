package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * <b>【每一个样板槽（池）自己的类型闸】—— 纯逻辑核（零 Minecraft / GT 依赖 ⇒ 可离线 java 单跑探针）。</b>
 *
 * <h2>一、用户口径（2026-10-27，逐字）</h2>
 * <pre>
 *   「样板声明 ∩ 机器可执行，是对于那单独一个槽（池）的，这就是为什么我说要先分池，
 *     这样只会一个池对于一条配方，根本不可能会串」
 * </pre>
 * ⇒ 判据的<b>粒度是槽</b>，不是整机：引擎拿一条配方来问某个槽时，<b>那个槽</b>自己回答
 * 「我声明的类型里有没有它、这台机器能不能跑」。全局收窄候选类型集是错的粒度（上一批的错误）。
 *
 * <h2>二、七种 verdict ⇔ {@link #decide} 的七个 return（一一对应，改动必须同步本表）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>{@code decide} 的分支条件</th><th>枚举</th><th>日志 verdict=</th></tr>
 *   <tr><td>1</td><td>{@link #disabled()}（回退位开）</td>
 *       <td>{@link Outcome#PASS_DISABLED}</td><td>{@code ALLOW_DISABLED}</td></tr>
 *   <tr><td>2</td><td>{@code recipeType} 读不出 或 {@code machineTypes} 为空，<b>且槽没声明</b></td>
 *       <td>{@link Outcome#PASS_NO_DECLARATION}</td><td>{@code ALLOW_NO_DECL}</td></tr>
 *   <tr><td>3</td><td>{@code recipeType} 读不出 或 {@code machineTypes} 为空，<b>但槽有声明</b>（判不了 ⇒ 放行不猜）</td>
 *       <td>{@link Outcome#PASS_DECLARED_UNRESOLVED}</td><td>{@code ALLOW_DECLARED_UNRESOLVED}</td></tr>
 *   <tr><td>4</td><td>槽没声明 且 闸开（{@code blockAmbiguousPatterns=true}）</td>
 *       <td>{@link Outcome#BLOCK_UNDECLARED}</td><td>{@code DENY_UNDECLARED}</td></tr>
 *   <tr><td>5</td><td>槽没声明 且 闸关（放行、不猜）</td>
 *       <td>{@link Outcome#PASS_NO_DECLARATION}</td><td>{@code ALLOW_NO_DECL}</td></tr>
 *   <tr><td>6</td><td>{@code recipeType} 就在 声明 ∩ 机器可执行 里</td>
 *       <td>{@link Outcome#PASS_MATCHED}</td><td>{@code ALLOW_TYPE_MATCH}</td></tr>
 *   <tr><td>7</td><td>声明 ∩ 机器可执行 非空，但 {@code recipeType} 不在交集里</td>
 *       <td>{@link Outcome#BLOCK_MISMATCH}</td><td>{@code DENY_TYPE_MISMATCH}</td></tr>
 *   <tr><td>8</td><td>声明 ∩ 机器可执行 = <b>空</b>（这台机器一个都不支持）</td>
 *       <td>{@link Outcome#BLOCK_UNSUPPORTED}</td><td>{@code DENY_MACHINE_UNSUPPORTED}</td></tr>
 * </table>
 * ⚠️ 上表有 <b>8 行</b>，但只有 <b>7 个</b> verdict 名字 —— 第 2 与第 5 行<b>同一个 verdict</b>
 * {@code ALLOW_NO_DECL}（两处 return 的是同一个枚举常量、同一个语义：<i>槽没声明且不拦</i>），
 * 判据完全一样，所以日志里不区分。反过来：<b>7 个 verdict 名字各自只对应一个枚举常量</b>。
 *
 * <h2>三、③ 的语义（用户 2026-10-27 追加一）</h2>
 * 没声明类型时<b>不猜</b>，但「放不放行」交给<b>已有的那个开关</b>
 * {@code blockAmbiguousPatterns}（星律仓室上的「闸（拦歧义样板）：开/关」，默认<b>开</b>）：
 * <ul>
 *   <li>闸<b>开</b> ⇒ 未声明的槽<b>不放行</b>（并且要能提示「样板未声明类型」）；</li>
 *   <li>闸<b>关</b> ⇒ <b>放行</b>（保持宽松）。</li>
 * </ul>
 * 判据：同一个样板，只切这个开关，行为必须相反。
 *
 * <h2>四、拿不到就放行</h2>
 * {@code recipeType} 读不出（空串）/ 机器可执行类型表为空（仓室读不到控制器）⇒ 一律放行
 * （第 2 或第 3 行）。这是本闸的第三条安全边界：
 * 宁可漏拦，也不许因为读不到信息把一台机器弄停。
 *
 * <h2>五、日志纪律（2026-10-28 补）</h2>
 * 本类是<b>纯逻辑核</b>，不打任何日志（这样它能离线单跑）。日志全部由
 * {@code com.shanhai.mixin.ShanhaiSlotTypeGateMixin} 里的 {@code ShanhaiSlotGateLog} 负责，
 * 它读的正是这里的 {@link Outcome#verdict()} 与 {@link #envStatus()}。
 */
public final class ShanhaiSlotTypeGate {

    /** 回退位：环境变量 {@code SHANHAI_DISABLE_TYPE_FILTER=1} ⇒ 整闸关闭（一律放行）。 */
    public static final String DISABLE_ENV = "SHANHAI_DISABLE_TYPE_FILTER";

    private ShanhaiSlotTypeGate() {}

    /** 一次判定的结论（枚举本身带"放行/拒绝"语义，日志与探针都读它）。 */
    public enum Outcome {
        /** 6：槽有声明，且配方类型就在 声明 ∩ 机器可执行 里 ⇒ 放行。 */
        PASS_MATCHED(true, "ALLOW_TYPE_MATCH"),
        /** 7：槽有声明、机器也支持，但这条配方不是那个类型 ⇒ 拒。 */
        BLOCK_MISMATCH(false, "DENY_TYPE_MISMATCH"),
        /** 2/5：槽没声明 + 不拦（闸关，或类型表读不出）⇒ 放行（不猜）。 */
        PASS_NO_DECLARATION(true, "ALLOW_NO_DECL"),
        /** 3：槽有声明，但配方类型 / 机器类型表读不出 ⇒ 判不了 ⇒ 放行（不猜）。 */
        PASS_DECLARED_UNRESOLVED(true, "ALLOW_DECLARED_UNRESOLVED"),
        /** 4：槽没声明 + 闸开 ⇒ 拒（要提示「样板未声明类型」）。 */
        BLOCK_UNDECLARED(false, "DENY_UNDECLARED"),
        /** 8：槽声明的类型这台机器一个都不支持 ⇒ 拒（要提示「本机不支持该样板声明的配方类型」）。 */
        BLOCK_UNSUPPORTED(false, "DENY_MACHINE_UNSUPPORTED"),
        /** 1：回退位开 ⇒ 放行。 */
        PASS_DISABLED(true, "ALLOW_DISABLED");

        /** 日志里用的 verdict 名（七种之一，与 {@link #decide} 的分支一一对应）。 */
        public final String verdictId;

        private final boolean allow;

        Outcome(boolean allow, String verdictId) {
            this.allow = allow;
            this.verdictId = verdictId;
        }

        /** true = 这个槽可以跑这条配方。 */
        public boolean allows() {
            return this.allow;
        }

        /** 拒绝的三种形态（要给用户看原因的那三种）。 */
        public boolean blocked() {
            return !this.allow;
        }

        /** 日志用的 verdict 名（{@code grep 'verdict='} 就靠它）。 */
        public @NotNull String verdict() {
            return this.verdictId;
        }
    }

    /**
     * 回退位是否打开（每次现读系统属性/环境变量，<b>不缓存</b> ⇒ 运行期也能改）。
     *
     * <p>读两处：{@code -D} 系统属性 与 环境变量（部署时多半用环境变量）。
     */
    public static boolean disabled() {
        try {
            final String prop = System.getProperty(DISABLE_ENV);
            if (isTruthy(prop)) {
                return true;
            }
            final String env = System.getenv(DISABLE_ENV);
            return isTruthy(env);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTruthy(String raw) {
        if (raw == null) {
            return false;
        }
        final String v = raw.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    /**
     * 回退位的<b>一眼可读状态</b>（启动期那一行 {@code loaded=true ... env=…} 用它）。
     *
     * <pre>
     *   两个来源都没设   ⇒ "SHANHAI_DISABLE_TYPE_FILTER=unset gate=ENABLED"
     *   设了但不是真值 ⇒ "…=off gate=ENABLED"
     *   设成真值       ⇒ "…=1(prop)/…=true(env) gate=DISABLED"
     * </pre>
     * 永不抛（读环境变量在极少数安全管理器下会抛 SecurityException）。
     */
    public static @NotNull String envStatus() {
        String prop = null;
        String env = null;
        try {
            prop = System.getProperty(DISABLE_ENV);
        } catch (Throwable ignored) {
            prop = "?";
        }
        try {
            env = System.getenv(DISABLE_ENV);
        } catch (Throwable ignored) {
            env = "?";
        }
        final StringBuilder sb = new StringBuilder(DISABLE_ENV).append('=');
        if (prop == null && env == null) {
            sb.append("unset");
        } else {
            sb.append("prop:").append(prop == null ? "null" : prop)
                    .append("/env:").append(env == null ? "null" : env);
        }
        sb.append(" gate=").append(disabled() ? "DISABLED" : "ENABLED");
        return sb.toString();
    }

    /**
     * <b>唯一判定点</b>（七种 verdict、七个 return，见类注释 §二 的对应表）。
     *
     * @param slotDeclared  该槽<b>声明的</b>配方类型 id（可多值；空表 = 没声明）
     * @param machineTypes  机器<b>可执行的</b>类型 id（控制器 {@code getRecipeTypes()}）
     * @param recipeType    这一条配方的类型 id（读不出 = 空串 ⇒ 放行，不猜）
     * @param gateEnabled   {@code blockAmbiguousPatterns}（决定"没声明"那一支）
     */
    public static @NotNull Outcome decide(@NotNull List<String> slotDeclared,
                                         @NotNull List<String> machineTypes,
                                         @NotNull String recipeType,
                                         boolean gateEnabled) {
        // ── 分支 1：回退位开 ⇒ 一律放行（verdict=ALLOW_DISABLED）
        if (disabled()) {
            return Outcome.PASS_DISABLED;
        }

        final boolean declaredEmpty = slotDeclared.isEmpty();
        // ── 分支 2/3：拿不到就不猜（配方类型读不出 / 机器类型表读不出）⇒ 放行
        if (recipeType == null || recipeType.isBlank() || machineTypes.isEmpty()) {
            if (declaredEmpty) {
                // 分支 2（verdict=ALLOW_NO_DECL）
                return Outcome.PASS_NO_DECLARATION;
            }
            // 分支 3：槽有声明但判不了 ⇒ 放行（verdict=ALLOW_DECLARED_UNRESOLVED）
            return Outcome.PASS_DECLARED_UNRESOLVED;
        }

        // ── 分支 4/5：槽没声明 ⇒ 由已有那个开关决定
        if (declaredEmpty) {
            if (gateEnabled) {
                // 分支 4（verdict=DENY_UNDECLARED）
                return Outcome.BLOCK_UNDECLARED;
            }
            // 分支 5（verdict=ALLOW_NO_DECL）
            return Outcome.PASS_NO_DECLARATION;
        }

        final String wanted = recipeType.trim();
        // 机器能不能跑这个类型？（声明 ∩ 机器可执行）
        final LinkedHashSet<String> machine = new LinkedHashSet<>();
        for (String id : machineTypes) {
            if (id != null && !id.isBlank()) {
                machine.add(id.trim());
            }
        }
        boolean anySupported = false;
        for (String id : slotDeclared) {
            if (id == null || id.isBlank()) {
                continue;
            }
            final String norm = id.trim();
            if (!machine.contains(norm)) {
                continue;
            }
            anySupported = true;
            if (norm.equals(wanted)) {
                // 分支 6（verdict=ALLOW_TYPE_MATCH）
                return Outcome.PASS_MATCHED;
            }
        }
        if (anySupported) {
            // 分支 7（verdict=DENY_TYPE_MISMATCH）
            return Outcome.BLOCK_MISMATCH;
        }
        // 分支 8（verdict=DENY_MACHINE_UNSUPPORTED）
        return Outcome.BLOCK_UNSUPPORTED;
    }

    /** 便捷：直接要"放不放行"。 */
    public static boolean allows(@NotNull List<String> slotDeclared, @NotNull List<String> machineTypes,
                                 @NotNull String recipeType, boolean gateEnabled) {
        return decide(slotDeclared, machineTypes, recipeType, gateEnabled).allows();
    }

    /** 一行式证据串（机器侧日志与探针共用，格式稳定便于 grep）。 */
    public static @NotNull String describe(@NotNull Outcome outcome, @NotNull String recipeType,
                                           @NotNull List<String> slotDeclared) {
        return "verdict=" + outcome.name()
                + " allow=" + outcome.allows()
                + " recipeType=" + (recipeType == null || recipeType.isBlank() ? "none" : recipeType)
                + " declared=" + slotDeclared;
    }

    /**
     * 判定行的<b>统一格式</b>（mixin 的每一行判定日志都用它拼，保证 grep 口径只有一个）。
     *
     * <pre>
     *   pos=1,64,-3 slot=7 sim=true declared=[gtceu:assembler] recipeType=gtceu:assembler verdict=ALLOW_TYPE_MATCH
     * </pre>
     * {@code declared} 传 {@code null} ⇒ 打 {@code declared=?}（读不到，不假装"没声明"）。
     */
    public static @NotNull String line(@NotNull String pos, int slot, List<String> declared,
                                       String recipeType, @NotNull Outcome outcome) {
        return line(pos, slot, null, declared, recipeType, outcome);
    }

    /**
     * <b>2026-10-31（wildfix T1）：判定行加 {@code sim=} 字段。</b>
     *
     * <h2>为什么必须加（这是取证报告 §7 的第一条待办）</h2>
     * {@code meHandleRecipeInner} 的第 3 个参数 {@code simulate} 是<b>整条取证链的枢纽</b>：
     * 报告 §1.3 已把「料被谁吃掉」钉死为「{@code simulate==false} 的那一遍才会
     * {@code itemInventory.removeLong/put}」，而 {@code [SHANHAI-SLOTGATE]} 这一行原先<b>没打它</b> ⇒
     * 光看日志分不出「这一遍是模拟还是真跑」，于是「{@code ALLOW_TYPE_MATCH} 连着出现却 {@code items} 归零」
     * 这件事无法定论。
     *
     * <p>只读：这个参数只进字符串，不参与任何判定。
     *
     * @param simulate {@code null} ⇒ 打 {@code sim=?}（调用方没传；老的四参重载走这一支）
     */
    public static @NotNull String line(@NotNull String pos, int slot, Boolean simulate, List<String> declared,
                                       String recipeType, @NotNull Outcome outcome) {
        final String decl = declared == null ? "?" : declared.toString();
        final String rt = recipeType == null || recipeType.isBlank() ? "none" : recipeType.trim();
        return "pos=" + pos + " slot=" + slot + " sim=" + simText(simulate) + " declared=" + decl
                + " recipeType=" + rt + " verdict=" + outcome.verdict();
    }

    /**
     * 同一格式、但没有 verdict 的行（短路口：不是星律机器 / 配方为空 / 抛出）。
     *
     * <p>为什么单列：<b>七种 verdict 必须与 {@link #decide} 一一对应</b>，
     * 短路不算判定 ⇒ 一律打 {@code verdict=NONE reason=…}，这样
     * {@code grep 'verdict='} 得到的是"闸真正做过的判定"，而 {@code verdict=NONE} 一眼可分离。
     */
    public static @NotNull String skipLine(@NotNull String pos, int slot, String recipeType,
                                           @NotNull String reason) {
        return skipLine(pos, slot, null, recipeType, reason);
    }

    /** {@link #skipLine(String, int, String, String)} 的 {@code sim=} 版本（2026-10-31 wildfix T1）。 */
    public static @NotNull String skipLine(@NotNull String pos, int slot, Boolean simulate, String recipeType,
                                           @NotNull String reason) {
        final String rt = recipeType == null || recipeType.isBlank() ? "none" : recipeType.trim();
        return "pos=" + pos + " slot=" + slot + " sim=" + simText(simulate) + " declared=? recipeType=" + rt
                + " verdict=NONE reason=" + reason;
    }

    /** {@code sim=} 字段的取值（{@code true/false/?}；{@code ?} 只出现在调用方没传时，绝不假装成 false）。 */
    private static @NotNull String simText(Boolean simulate) {
        return simulate == null ? "?" : (simulate.booleanValue() ? "true" : "false");
    }
}
