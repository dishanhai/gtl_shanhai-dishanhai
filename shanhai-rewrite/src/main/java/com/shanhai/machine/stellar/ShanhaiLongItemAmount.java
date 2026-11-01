package com.shanhai.machine.stellar;

/**
 * 🔴 <b>「物品可用量」与「每份需求 ⇒ 并行份数」的唯一一份纯算术核
 * （只 import {@code java.*}／不碰 Minecraft、不碰 GT、不碰 AE）。</b>
 *
 * <h2>它为什么存在（2026-10-27 实机读数）</h2>
 * 用户从日志读到（原文）：
 * <pre>
 *   [SHANHAI-INFPAR] pmax-dummy machine=shanhai:..._crystallization_furnace
 *       in=238609294 out=238609294 budget=9223372036854775807 pick=238609294 why=in perItem=9 perFluid=-1
 *   [SHANHAI-INFPAR] pmax-dummy machine=shanhai:primordial_void_induction_armature
 *       in=0 out=-1 budget=9223372036854775807 pick=9223372036854775 why=in perItem=-1 perFluid=1000
 * </pre>
 * 反推「可用量」：
 * <ul>
 *   <li>物品侧：{@code pick × perItem = 238609294 × 9 = 2147483646} ⇒ 可用量被压在
 *       {@code 2^31−1 = 2147483647}（{@code floor(2147483647 ÷ 9) = 238609294}，逐位吻合）；</li>
 *   <li>流体侧：{@code pick × perFluid = 9223372036854775 × 1000 = 9.223e18} ⇒
 *       <b>流体是 long，一点没被压</b>。</li>
 * </ul>
 * <h2>截断点（不是本类，本类只是把口径抽成唯一一份）</h2>
 * <ol>
 *   <li>{@code ShanhaiTagSupplyHandlers#shanhaiBuildItemContents} 里
 *       {@code key.toStack((int) Math.min(amount, Integer.MAX_VALUE))} ——
 *       <b>我们自己的</b>一行 int 钳制（同文件的流体侧那一行没有钳制）；</li>
 *   <li>上游结构点：{@code gtlcore RecipeHandlePart#createItemMap} 对「没有
 *       {@code MEStock$IOptimizedMEList}」的 handler 走 {@code getContents()} ⇒
 *       {@code ItemStack#getCount()}（<b>int</b>，字节码 {@code m_41613_():()I; i2l}）
 *       ⇒ <b>凡是通过 {@code getContents()} 报物品的 handler，单项上限天然 2^31−1</b>。
 *       gtlcore 为此留了 long 通道：同一方法偏移 70–96
 *       {@code instanceof MEStock$IOptimizedMEList → getMEItemMap()}（{@code Object2LongMap}）。</li>
 * </ol>
 * ⇒ 修法 = <b>物品侧改走那条 long 通道</b>（{@link #availableFromMe(long)} 原样 long），
 * 而不是把 int 钳制挪个地方。流体侧本来就走 long，<b>本类一个字节都不改它的口径</b>
 * （{@link #fluidAvailableFromMe(long)} 与 {@link #fluidPickFor(long, long)} 只是把"不变"写成可断言的纯函数）。
 *
 * <h2>🔴 为什么算术必须住在这个"裸 javac 就能编"的类里</h2>
 * 本工程的硬纪律：「凡我自己写的检查脚本报出的结果（PASS 或 FAIL），采信前必须先证明脚本自己是对的」。
 * 若判定只活在带 Minecraft 依赖的类里，离线自证只能<b>照抄一遍算式</b>
 * ⇒ 「抄错的到底是脚本还是生产代码」永远分不清。
 * ⇒ 生产侧（{@code ShanhaiTagSupplyHandlers＃getMEItemMap} 与
 * {@code StellarSampleAssemblerPartMachine＃appendStockItemContents}）调的都是<b>本类的同一份字节码</b>，
 * 离线判据驱动的也是它 ⇒ 不存在第二份算式。
 *
 * <h2>不变式（{@link #selfTest()} 逐条断言，加载期/离线都能跑）</h2>
 * <pre>
 *   ① 可用物品 = 9.22e18（Long.MAX）、每份 9        ⇒ pick = 1,024,819,115,206,086,200（long，不再被压）
 *      老口径（int 截断）                          ⇒ pick = 238,609,294 ← 与实机读数【逐位相同】(负面对照)
 *   ② 可用流体 = 9.22e18、每份 1000                 ⇒ pick = 9,223,372,036,854,775（逐位不变，回归）
 *   ③ 可用量 ≤ 2^31−1 时，新旧两套口径给出【同一个数】⇒ 「p ≤ Integer.MAX_VALUE 的机器行为逐位不变」
 *   ④ ItemStack 只能装 int（{@link #stackCountForVanilla(long)} 钳到 2^31−1）
 *      —— 这是「清单里那一份展示量」的结构上限，<b>不是</b>可用量的上限
 * </pre>
 */
public final class ShanhaiLongItemAmount {

    /**
     * {@code ItemStack#getCount()} 的真实上限 = {@code Integer.MAX_VALUE} = 2^31−1 = 2,147,483,647。
     *
     * <p>⚠️ 它是 <b>ItemStack 这个容器的</b>上限，不是"ME 里有多少料"的上限 ——
     * 两者被混为一谈正是本条 bug 的成因。
     */
    public static final long ITEM_STACK_CEILING = 2147483647L;

    /** 实机档：用户 AE 里的物品可用量（= {@code Long.MAX_VALUE}，与日志里的 {@code budget} 同量级）。 */
    public static final long FIELD_AVAILABLE = Long.MAX_VALUE;

    /** 实机档：蜉蝣炉那条配方每份吃 9 个（日志 {@code perItem=9}）。 */
    public static final long FIELD_PER_ITEM = 9L;

    /** 实机档：被 int 截断后倒推出来的可用量（= 2^31−1）。 */
    public static final long FIELD_TRUNCATED_AVAILABLE = ITEM_STACK_CEILING;

    /** 实机档：修好之后物品侧应当报出来的份数（{@code floor(Long.MAX ÷ 9)}，已用 BigInteger 核对）。 */
    public static final long FIELD_PICK_AFTER_FIX = 1024819115206086200L;

    /**
     * 实机档：<b>截断时</b>观测到的份数 = {@code floor((2^31−1) ÷ 9)} = 238,609,294。
     * 它与用户日志里的 {@code pick=238609294} <b>逐位相同</b> ⇒ 负面对照是"复现实机"而不是"随便挑个数"。
     */
    public static final long FIELD_PICK_TRUNCATED = 238609294L;

    /** 实机档：流体侧每份 1000（日志 {@code perFluid=1000}）。 */
    public static final long FIELD_PER_FLUID = 1000L;

    /** 实机档：流体侧份数 = {@code floor(Long.MAX ÷ 1000)} = 9,223,372,036,854,775（日志 {@code pick=9223372036854775}）。 */
    public static final long FIELD_PICK_FLUID = 9223372036854775L;

    private ShanhaiLongItemAmount() {}

    // ═══════════════════════ ① 物品侧：可用量（唯一一份口径） ═══════════════════════

    /**
     * 🔴 <b>物品可用量 = ME 里的现量，原样 long，一次都不压 int。</b>
     *
     * <p>负数（哨兵 {@code -1} = 读不到 / 没有这个键）一律按 <b>0</b> 算：
     * 0 会让上游的 {@code calculate} 走"料不够 ⇒ 返回 0"，是<b>安全的那一侧</b>；
     * 而把 {@code -1} 当 long 用会变成 9.2e18 级的<b>正数</b>（凭空造料）。
     *
     * @param meAmount ME 网络里这个键的现量（AE 侧本来就是 long）
     * @return 可用量（≥ 0，long；上限 = {@code Long.MAX_VALUE}）
     */
    public static long availableFromMe(long meAmount) {
        return Math.max(0L, meAmount);
    }

    /**
     * <b>老口径（被修掉的那一份）：把可用量压到 int 上限。</b>
     *
     * <p>它<b>只</b>用于负面对照与离线判据 —— 生产代码<b>一次都不调用</b>它。
     * 留着的理由：判据必须能证明"改回 int 截断 ⇒ 读数回到 238,609,294"，
     * 否则"修好了"这句话没有分辨力（本工程反复吃过"判据自己没红过"的亏）。
     */
    public static long intTruncatedAvailable(long meAmount) {
        return Math.min(availableFromMe(meAmount), ITEM_STACK_CEILING);
    }

    /**
     * <b>清单里那一份"展示量"</b>：{@code List<Object>} 里装的 {@link Object}（实际是
     * {@code ItemStack}）只能装 int ⇒ 这里必须钳。
     *
     * <p>🔴 <b>这一格不是可用量的权威</b>：它对 {@code > 2^31−1} 的料必然显示不出真实的量
     * （容器结构如此，改不了 —— 那正是 gtlcore 给 {@code IOptimizedMEList} 留 long 通道的原因）。
     * 权威值走 {@link #availableFromMe(long)} ⇒ {@code getMEItemMap()} 那条 long 通道。
     */
    public static int stackCountForVanilla(long meAmount) {
        final long amount = availableFromMe(meAmount);
        return amount >= ITEM_STACK_CEILING ? Integer.MAX_VALUE : (int) amount;
    }

    // ═══════════════════════ ② 流体侧：口径与改动前逐位相同 ═══════════════════════

    /**
     * <b>流体可用量</b> —— 与 {@link #availableFromMe(long)} <b>逐位相同</b>。
     *
     * <p>存在的唯一理由：把"流体侧一点没动"写成一条<b>可断言的等式</b>
     * （{@link #selfTest()} ② 段），而不是一句注释里的口头承诺。
     * {@code FluidStack#getAmount()} 本来就是 long ⇒ 流体侧从来不需要这一步。
     */
    public static long fluidAvailableFromMe(long meAmount) {
        return availableFromMe(meAmount);
    }

    /** <b>流体份数</b> = {@link #pickFor(long, long)}（同一个 floor 除法；流体侧只走这一条纯函数，没有孪生实现）。 */
    public static long fluidPickFor(long available, long perCopy) {
        return pickFor(available, perCopy);
    }

    // ═══════════════════════ ③ 份数：floor(可用量 ÷ 每份需求) ═══════════════════════

    /**
     * <b>份数 = {@code floor(可用量 ÷ 每份需求)}</b>（与上游 {@code IParallelLogic＃calculate}
     * 偏移 145–151 的 {@code ldiv + Math.min} 同一口径）。
     *
     * <ul>
     *   <li>{@code perCopy <= 0}（该配方没有这一类输入 / 读不到）⇒ <b>0</b>
     *       —— 与上游「该能力下无需求 ⇒ 返回 0」同向；</li>
     *   <li>两个操作数都是正 long ⇒ 整数除法就是数学上的 floor ⇒ <b>不会溢出、不需要饱和</b>（本式没有乘法）；</li>
     *   <li>结果上限 = {@code Long.MAX_VALUE}（{@code perCopy == 1} 时），<b>不再经过任何 int 窄化</b>。</li>
     * </ul>
     *
     * <p>⚠️ 与 {@code IParallelLogic} 的真实口径有一处<b>刻意的简化</b>：上游是"逐条目分别除、再取 min"，
     * 本方法是"总可用量 ÷ 总每份需求"。二者只在"多条输入共用同一份库存"时才不等
     * ⇒ 本方法用于<b>判据与解释</b>（日志里的 {@code pick} 由上游算），不作为生产运算量。
     *
     * @param available 可用量（long；≤ 0 ⇒ 0 份）
     * @param perCopy   每份需求（long；≤ 0 ⇒ 0 份）
     */
    public static long pickFor(long available, long perCopy) {
        if (available <= 0L || perCopy <= 0L) {
            return 0L;
        }
        return available / perCopy;
    }

    // ═══════════════════════ ④ 加载期自检 / 离线判据（同一份字节码） ═══════════════════════

    /**
     * <b>自检（正向对照 + 负面对照 + 流体回归 + int 档恒等），返回一行可 grep 的读数。</b>
     *
     * <p>调用点：离线判据（裸 {@code javac} 驱动本类）与需要时的人工排查。
     * 任一条不成立 ⇒ 抛 {@link IllegalStateException}（本项目风格：fail-fast，不静默）。
     */
    public static String selfTest() {
        // ── ① 正向：物品侧可用量报 long ⇒ 份数到 1.02e18 量级，不再是 2.4e8 ──
        final long after = pickFor(availableFromMe(FIELD_AVAILABLE), FIELD_PER_ITEM);
        assertEq(after, FIELD_PICK_AFTER_FIX, "① 修好后 Long.MAX ÷ 9 的份数");

        // ── ② 负面对照：把 long 通道改回 int 截断 ⇒ 必须回到实机那个 238,609,294 ──
        final long before = pickFor(intTruncatedAvailable(FIELD_AVAILABLE), FIELD_PER_ITEM);
        assertEq(before, FIELD_PICK_TRUNCATED, "② 负面对照（int 截断）必须逐位复现实机读到的 238,609,294");
        if (before >= after) {
            throw new IllegalStateException("[SHANHAI-ITEMLONG] 自检的【负面对照】失败：int 截断档 "
                    + before + " 竟然不小于 long 档 " + after + " ⇒ 本核什么都没证明。");
        }

        // ── ③ 流体回归：可用流体的口径与份数【逐位不变】 ──
        assertEq(fluidAvailableFromMe(FIELD_AVAILABLE), availableFromMe(FIELD_AVAILABLE),
                "③ 流体可用量必须与改动前逐位相同");
        assertEq(fluidPickFor(FIELD_AVAILABLE, FIELD_PER_FLUID), FIELD_PICK_FLUID,
                "③ 流体份数必须仍是实机读到的 9,223,372,036,854,775");

        // ── ④ 「p ≤ Integer.MAX_VALUE 的老机器逐位不变」：可用量 ≤ 2^31−1 时两套口径同值 ──
        final long[] small = {0L, 1L, 8L, 9L, 10L, 64L, 1000L, 1048576L, ITEM_STACK_CEILING};
        for (int i = 0; i < small.length; i++) {
            final long per = FIELD_PER_ITEM;
            if (pickFor(availableFromMe(small[i]), per) != pickFor(intTruncatedAvailable(small[i]), per)) {
                throw new IllegalStateException("[SHANHAI-ITEMLONG] 自检失败：可用量 " + small[i]
                        + " ≤ 2^31−1 时两套口径给出了不同份数 ⇒ 「p ≤ Integer.MAX_VALUE 逐位不变」不成立。");
            }
        }
        // 实机那一档（2^31−1）逐个能力都对得上（不只 perItem = 9）
        final long[] perCopies = {1L, 2L, 3L, 5L, 9L, 16L, 64L, 1000L};
        int sameCount = 0;
        for (int i = 0; i < perCopies.length; i++) {
            final long p = perCopies[i];
            if (pickFor(availableFromMe(ITEM_STACK_CEILING), p) != ITEM_STACK_CEILING / p) {
                throw new IllegalStateException("[SHANHAI-ITEMLONG] 自检失败：可用量 = 2^31−1、每份 = " + p
                        + " 时份数不等于 " + (ITEM_STACK_CEILING / p) + "（int 档必须与老口径逐位相同）。");
            }
            sameCount++;
        }

        // ── ⑤ 容器边界：ItemStack 只能装 int（那份"展示量"钳到 2^31−1），但可用量本身不受影响 ──
        assertEq((long) stackCountForVanilla(FIELD_AVAILABLE), (long) Integer.MAX_VALUE,
                "⑤ 展示用 ItemStack 的 count 上限 = Integer.MAX_VALUE");
        assertEq((long) stackCountForVanilla(7L), 7L, "⑤ 域内值原样（7 ⇒ 7）");
        assertEq((long) stackCountForVanilla(-1L), 0L, "⑤ 负数（读不到）按 0 算，绝不变成 9.2e18");
        if (availableFromMe(FIELD_AVAILABLE) <= ITEM_STACK_CEILING) {
            throw new IllegalStateException("[SHANHAI-ITEMLONG] 自检失败：可用量仍被压在 int 上限 "
                    + ITEM_STACK_CEILING + " ⇒ 物品侧的 long 通道没有生效。");
        }

        // ── ⑥ 无状态 / 可重复（顺序无关） ──
        if (pickFor(availableFromMe(FIELD_AVAILABLE), FIELD_PER_ITEM) != after
                || pickFor(intTruncatedAvailable(FIELD_AVAILABLE), FIELD_PER_ITEM) != before) {
            throw new IllegalStateException("[SHANHAI-ITEMLONG] 自检失败：重复计算不同值（本核应当无状态）。");
        }

        return "[SHANHAI-ITEMLONG] 物品可用量 long 化自检通过："
                + "可用物品 " + FIELD_AVAILABLE + " ÷ 每份 " + FIELD_PER_ITEM + " ⇒ 份数 " + after
                + "（不再是 int 截断档 " + before + "，那是实机读数，逐位吻合）；"
                + "可用流体 " + fluidAvailableFromMe(FIELD_AVAILABLE) + " ÷ 每份 " + FIELD_PER_FLUID
                + " ⇒ 份数 " + fluidPickFor(FIELD_AVAILABLE, FIELD_PER_FLUID) + "（逐位不变）；"
                + "可用量 ≤ 2^31−1 的 " + small.length + " 档 + 每份 " + sameCount
                + " 档与老口径逐位相同（p ≤ Integer.MAX_VALUE 的机器行为不变）；"
                + "展示用 ItemStack count 仍钳 " + Integer.MAX_VALUE + "（容器结构，非可用量上限）。";
    }

    private static void assertEq(long actual, long expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException("[SHANHAI-ITEMLONG] 自检失败：" + what
                    + "，实际 " + actual + "，应为 " + expected + "。");
        }
    }
}
