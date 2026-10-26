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
 * <h2>二、判定（五条，逐条都有探针）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>输入</th><th>结果</th><th>枚举</th></tr>
 *   <tr><td>①</td><td>槽有声明，且 {@code recipeType ∈ 声明}</td><td><b>放行</b></td>
 *       <td>{@link Outcome#PASS_MATCHED}</td></tr>
 *   <tr><td>②</td><td>槽有声明，且声明 ∩ 机器可执行 非空，但 {@code recipeType} 不在交集里</td>
 *       <td><b>拒</b></td><td>{@link Outcome#BLOCK_MISMATCH}</td></tr>
 *   <tr><td>③</td><td>槽<b>没有</b>声明类型</td>
 *       <td>闸开 ⇒ <b>拒</b>（{@link Outcome#BLOCK_UNDECLARED}）；闸关 ⇒ <b>放行</b>
 *       （{@link Outcome#PASS_NO_DECLARATION}）</td></tr>
 *   <tr><td>④</td><td>槽有声明，但 声明 ∩ 机器可执行 = <b>空</b>（机器不支持该类型）</td>
 *       <td><b>拒</b>（{@link Outcome#BLOCK_UNSUPPORTED}）</td></tr>
 *   <tr><td>⑤</td><td>回退位开（环境变量 {@code SHANHAI_DISABLE_TYPE_FILTER=1}）</td>
 *       <td><b>一律放行</b>（{@link Outcome#PASS_DISABLED}）</td></tr>
 * </table>
 *
 * <h2>三、🔴 ③ 的语义（用户 2026-10-27 追加一）</h2>
 * 没声明类型时<b>不猜</b>，但「放不放行」交给<b>已有的那个开关</b>
 * {@code blockAmbiguousPatterns}（星律仓室上的「闸（拦歧义样板）：开/关」，默认<b>开</b>）：
 * <ul>
 *   <li>闸<b>开</b> ⇒ 未声明的槽<b>不放行</b>（并且要能提示「样板未声明类型」）；</li>
 *   <li>闸<b>关</b> ⇒ <b>放行</b>（保持宽松）。</li>
 * </ul>
 * 判据：同一个样板，只切这个开关，行为必须相反。
 *
 * <h2>四、拿不到就放行</h2>
 * {@code recipeType} 读不出（空串）/ 机器可执行类型表为空（仓室读不到控制器）⇒ 一律
 * {@link Outcome#PASS_NO_DECLARATION}（<b>不猜、不拦</b>）。这是本闸的第三条安全边界：
 * 宁可漏拦，也不许因为读不到信息把一台机器弄停。
 */
public final class ShanhaiSlotTypeGate {

    /** 回退位：环境变量 {@code SHANHAI_DISABLE_TYPE_FILTER=1} ⇒ 整闸关闭（一律放行）。 */
    public static final String DISABLE_ENV = "SHANHAI_DISABLE_TYPE_FILTER";

    private ShanhaiSlotTypeGate() {}

    /** 一次判定的结论（枚举本身带"放行/拒绝"语义，日志与探针都读它）。 */
    public enum Outcome {
        /** ①：槽有声明，且配方类型就在声明里 ⇒ 放行。 */
        PASS_MATCHED(true),
        /** ②：槽有声明、机器也支持，但这条配方不是那个类型 ⇒ 拒。 */
        BLOCK_MISMATCH(false),
        /** ③-a：槽没声明 + 闸关 ⇒ 放行（不猜）。 */
        PASS_NO_DECLARATION(true),
        /** ③-b：槽没声明 + 闸开 ⇒ 拒（要提示「样板未声明类型」）。 */
        BLOCK_UNDECLARED(false),
        /** ④：槽声明的类型这台机器一个都不支持 ⇒ 拒（要提示「本机不支持该样板声明的配方类型」）。 */
        BLOCK_UNSUPPORTED(false),
        /** ⑤：回退位开 ⇒ 放行。 */
        PASS_DISABLED(true);

        private final boolean allow;

        Outcome(boolean allow) {
            this.allow = allow;
        }

        /** true = 这个槽可以跑这条配方。 */
        public boolean allows() {
            return this.allow;
        }

        /** 拒绝的三种形态（要给用户看原因的那三种）。 */
        public boolean blocked() {
            return !this.allow;
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
     * <b>唯一判定点</b>。
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
        if (disabled()) {
            return Outcome.PASS_DISABLED;
        }
        // 拿不到就不猜：配方类型读不出，或机器类型表读不出 ⇒ 放行。
        if (recipeType == null || recipeType.isBlank() || machineTypes.isEmpty()) {
            return Outcome.PASS_NO_DECLARATION;
        }
        if (slotDeclared.isEmpty()) {
            return gateEnabled ? Outcome.BLOCK_UNDECLARED : Outcome.PASS_NO_DECLARATION;
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
                return Outcome.PASS_MATCHED;
            }
        }
        return anySupported ? Outcome.BLOCK_MISMATCH : Outcome.BLOCK_UNSUPPORTED;
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
}
