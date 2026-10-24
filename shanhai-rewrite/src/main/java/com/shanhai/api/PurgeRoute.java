package com.shanhai.api;

import java.util.function.IntSupplier;

/**
 * {@code /shanhai purge} 这一次到底走哪条路：<b>扩展的逐级版</b> 还是 <b>本体自己的 {@code kill @e}</b>。
 *
 * <h2>为什么单独一个类（而不是把 if 写在命令里）</h2>
 * 这条判定是"跨 mod 桥"的<b>全部语义</b>，而它需要的输入只有四个：桥在不在、是不是只数不抹、
 * 桥返回了几只、桥有没有抛异常。把它们抽成 {@code (boolean, boolean, int, boolean) -> Decision}
 * 之后，这个类<b>零 MC、零 Forge、零 slf4j</b>，于是可以用<b>纯 JDK 值表</b>把四种走法（含负对照）
 * 跑一遍，不必进游戏、不必起服务端。命令那边只剩"取值 → 调它 → 按结果执行"。
 *
 * <h2>两个入口</h2>
 * <pre>
 *   decide(bridgePresent, dryRun, addResult, addThrew)   纯判定（值表直接测它）
 *   ask(bridgePresent, dryRun, IntSupplier)              取数 ＋ 判定（命令调它；抛异常在这里就地收住）
 * </pre>
 * {@code ask} 内部对"没抛异常"的分支就是调 {@link #decide}，所以两条入口<b>不可能判出两种结果</b>。
 *
 * <h2>判定顺序（顺序本身就是语义，改顺序会改行为）</h2>
 * <pre>
 *   ① 没装 add（bridgePresent=false）          ⇒ SIMPLE reason=add_missing
 *   ② 装了但调用抛异常（addThrew=true）        ⇒ SIMPLE reason=add_threw          ← 异常永远压过返回值
 *   ③ 只数不抹（dryRun=true）                  ⇒ SIMPLE reason=dry_run            ← 接口的 purgeAll 没有 dryRun 参数，
 *                                                                                  真调它会把实体删掉 ⇒ 一律不调
 *   ④ addResult &lt; 0（= NOT_IMPLEMENTED）      ⇒ SIMPLE reason=add_not_implemented
 *   ⑤ 其余（addResult &gt;= 0）                 ⇒ ADD    reason=add                ← 0 也是有效结果（"一只都没有"）
 * </pre>
 *
 * <h2>🔴 三个反例（写错就会静默错）</h2>
 * <ol>
 *   <li>{@code addResult <= 0} 当成"未实现"：会把"这一轮确实没有实体"错判成回退，
 *       于是同一条命令先数出 0、再走一遍只能数不能删的路 —— 数字看着没变，行为已经变了。</li>
 *   <li>把 {@code dryRun} 的调用也发给桥：{@code /shanhai purge count} 会真的删东西。
 *       这是本条判定里<b>最贵</b>的一个错，所以 {@code dryRun} 排在返回值之前单独判。</li>
 *   <li>桥抛异常就整条命令失败：本体自己明明能抹，却因为扩展坏了而失效 ——
 *       "装了扩展反而更弱"，与拆分口径直接矛盾。所以异常在这里被收住（{@link Decision#error} 带出去打日志）。</li>
 * </ol>
 */
public final class PurgeRoute {

    /** 这一次走的是谁的实现。 */
    public enum Via {
        /** 扩展（{@code ShanhaiAdd.purgeAll}）给的数。 */
        ADD,
        /** 本体自己的 {@code com.shanhai.common.guard.ShanhaiPurgeSimple}。 */
        SIMPLE
    }

    /** {@link Decision#why} 取值：真的用了扩展。 */
    public static final String REASON_ADD = "add";
    /** {@link Decision#why} 取值：没装扩展（{@link ShanhaiAddHolder#optional()} 是 {@code null}）。 */
    public static final String REASON_ADD_MISSING = "add_missing";
    /** {@link Decision#why} 取值：装了扩展但它抛异常了。 */
    public static final String REASON_ADD_THREW = "add_threw";
    /** {@link Decision#why} 取值：扩展返回 {@link ShanhaiAdd#NOT_IMPLEMENTED}。 */
    public static final String REASON_ADD_NOT_IMPLEMENTED = "add_not_implemented";
    /** {@link Decision#why} 取值：只数不抹（{@code /shanhai purge count}），不走扩展。 */
    public static final String REASON_DRY_RUN = "dry_run";

    private PurgeRoute() {}

    /**
     * 纯判定（同样的入参永远同样的结果；无副作用、不读任何全局状态）。
     *
     * @param bridgePresent {@link ShanhaiAddHolder#optional()} 是否拿到了对象
     * @param dryRun        是不是"只数不抹"（{@code /shanhai purge count}）
     * @param addResult     扩展的返回值；没调它时传 {@link ShanhaiAdd#NOT_IMPLEMENTED}
     * @param addThrew      调扩展时是否抛了异常（抛了的话 {@code addResult} 不可信）
     */
    public static Decision decide(boolean bridgePresent, boolean dryRun, int addResult, boolean addThrew) {
        if (!bridgePresent) {
            return new Decision(Via.SIMPLE, 0, REASON_ADD_MISSING, null);
        }
        if (addThrew) {
            return new Decision(Via.SIMPLE, 0, REASON_ADD_THREW, null);
        }
        if (dryRun) {
            return new Decision(Via.SIMPLE, 0, REASON_DRY_RUN, null);
        }
        if (addResult < 0) {
            // 只有负数才是"未实现"；0 是"这一轮真的没有实体"，是有效结果
            return new Decision(Via.SIMPLE, 0, REASON_ADD_NOT_IMPLEMENTED, null);
        }
        return new Decision(Via.ADD, addResult, REASON_ADD, null);
    }

    /**
     * 取数 ＋ 判定：唯一被命令调用的入口（<b>永远不会抛异常</b>）。
     *
     * @param bridgePresent 是否拿到了扩展实现
     * @param dryRun        是否只数不抹（true ⇒ <b>绝不调用</b> {@code ask}）
     * @param ask           真正去调扩展的取数器（一般是 {@code () -> bridge.purgeAll(owner)}）；
     *                      为 {@code null} 或不该调用时可以不传
     */
    public static Decision ask(boolean bridgePresent, boolean dryRun, IntSupplier ask) {
        if (!bridgePresent || dryRun) {
            return decide(bridgePresent, dryRun, ShanhaiAdd.NOT_IMPLEMENTED, false);
        }
        if (ask == null) {
            // 防御：说好要调却没给取数器 ⇒ 当成"没有可用实现"（照样回退，绝不 NPE）
            return new Decision(Via.SIMPLE, 0, REASON_ADD_MISSING, null);
        }
        try {
            return decide(true, false, ask.getAsInt(), false);
        } catch (Throwable t) {
            // 收住扩展的异常：返回"回退 + 把异常带出去"，由调用方决定怎么记日志
            return new Decision(Via.SIMPLE, 0, REASON_ADD_THREW, t);
        }
    }

    /** 一次判定的结果（不可变）。 */
    public static final class Decision {

        /** 走谁。 */
        public final Via via;
        /** 只有 {@link Via#ADD} 时才有意义（= 扩展报的抹除数）；{@link Via#SIMPLE} 恒为 0。 */
        public final int removed;
        /** 为什么这么走（见 {@code REASON_*} 常量）。 */
        public final String why;
        /** {@link #why} = {@link #REASON_ADD_THREW} 时是扩展抛出的那个异常（供调用方打日志）；否则 {@code null}。 */
        public final Throwable error;

        Decision(Via via, int removed, String why, Throwable error) {
            this.via = via;
            this.removed = removed;
            this.why = why;
            this.error = error;
        }

        /** {@code true} = 用扩展给的数（命令据此决定是 {@code fromBridge} 还是 {@code killAll}）。 */
        public boolean usesAdd() {
            return via == Via.ADD;
        }

        /** 日志里的 {@code purge_via=} 取值：{@code add} / {@code simple}。 */
        public String viaToken() {
            return usesAdd() ? "add" : "simple";
        }

        /**
         * 一行机器可判日志（本体每次 {@code /shanhai purge} 都打一条）。
         *
         * <p>形状：{@code purge_via=add reason=add add_removed=137} ／
         * {@code purge_via=simple reason=add_not_implemented}。
         */
        public String logLine() {
            return usesAdd()
                    ? "purge_via=add reason=" + why + " add_removed=" + removed
                    : "purge_via=simple reason=" + why;
        }

        @Override
        public String toString() {
            return viaToken() + " reason=" + why + " removed=" + removed
                    + (error == null ? "" : " error=" + error.getClass().getSimpleName());
        }
    }
}
