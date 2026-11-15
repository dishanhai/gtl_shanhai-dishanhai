package com.shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「把物品里的样板<b>放进目标机器的样板总成</b>」这件事的<b>纯核</b>
 * （不 import 任何 Minecraft / GTCEu / AE2 / LDLib 类 ⇒ 裸 JVM 可跑断言）。
 *
 * <h2>一、用户拍板的分配规则（逐条落在这里）</h2>
 * <pre>
 *   ① 每块样板按它自己 NBT 里的 type 去匹配目标模块的 IRecipeLogicMachine#getRecipeTypes()
 *      · type 为空（未识别）            ⇒ 不放入 → {@link Decision#SKIP_UNKNOWN_TYPE}
 *      · 目标里没有任何模块支持这个 type ⇒ 不放入 → {@link Decision#SKIP_NO_MODULE}
 *   ② 落槽（按【总成顺序】逐台找）
 *      · 已有同配方 id 的样板 ⇒ 覆盖那一格（{@link Decision#PLACE_REPLACE}）
 *      · 否则               ⇒ 填第一个空槽（{@link Decision#PLACE_NEW}）
 *      · 两台都做不到        ⇒ {@link Decision#SKIP_NO_SLOT}
 * </pre>
 *
 * <h2>二、🔴 为什么分成两个 pass（先「覆盖」全局跑一遍，再「填空」跑一遍）</h2>
 * 单遍写法（逐块：先在这台找同 id，找不到就填空）会有一个真实后果：
 * <pre>
 *   总成 A 有空槽、总成 B 里已有同 id 的样板
 *   ⇒ 单遍：这块被填进 A 的空槽
 *   ⇒ 结果：A、B 各一块【同一个配方】的样板 = 重复
 * </pre>
 * 用户的原话是「同 id 新覆盖旧」—— 那就不该造出第二块。所以：
 * <pre>
 *   pass 1 「覆盖」：只在【已经存在同 id】的格子里换（绝不新增块数）
 *   pass 2 「填空」：pass 1 没处理的块，才去占空槽
 * </pre>
 * ⇒ 「同 id 覆盖」这一条<b>优先于</b>「填第一个空槽」，而且<b>不会</b>产生重复。
 *
 * <h2>三、🔴 槽校验（用户点名的那条，缺了会污染机器）</h2>
 * {@link Target#validAt(int, Unit)} 由调用方实现，但<b>分区总成</b>（星律样板总成的合并视图
 * {@code isItemValid} 恒 true）必须回答两件事：
 * <ul>
 *   <li>{@link Target#zoned()} = true 时，本核<b>自己</b>按槽号区间判：
 *       普通样板只能进 {@code [0, normalSlots)}，通配符样板只能进
 *       {@code [normalSlots, normalSlots + wildcardSlots)}；</li>
 *   <li>然后<b>再</b>问 {@link Target#validAt(int, Unit)}（= 真总成的 {@code isItemValid}）。</li>
 * </ul>
 * 两条都要过。所以「拿掉分区判据」这种退化会被 {@link #validAtCached} 那一层真的拦住 ——
 * 自检里那条负对照就是把这层换掉，然后要求它<b>红</b>（写进了普通槽）。
 *
 * <h2>四、「半途失败」语义（用户拍板）</h2>
 * 某块放不进去 ⇒ <b>跳过它并记账</b>，绝不中断整批：
 * <ul>
 *   <li>{@link #plan} 不改任何机器状态（也不写物品）；</li>
 *   <li>{@link #apply} 逐条写，单条写失败只影响那一条（回落到 {@link Decision#SKIP_NO_SLOT}）；</li>
 *   <li>调用方拿 {@link Result#unplacedIndices()} 原样<b>留在物品里</b>。</li>
 * </ul>
 *
 * <h2>五、🔴 第六刀：编程电路写在「样板放成功之后」，而且 0 一次都不调</h2>
 * 用户原话：「等一下，通配符样板的编程电路没有复制和黏贴」。
 * 电路是<b>机器槽的状态</b>（星律 {@code shanhaiWildcardCircuit[50]}；
 * 超级通配符 {@code slotCircuit[10]}），不是样板物品的一部分
 * ⇒ 必须单独搬（判据/值域/为什么"0 不动目标"见 {@link HostPatternMoverCircuit}）。
 * 本类里的三条纪律：
 * <pre>
 *   C1 顺序：槽校验 → 类型分配 → tg.write() 【成功之后】 → 才 applyCircuit()
 *      ⇒ 放不进去的那一块【不会】顺带改机器的电路设置
 *   C2 0 不调：unit.circuit() == 0 ⇒ 连 wildcardMotherAt 都不问（它是"源没设置"，不是"清空目标"）
 *   C3 不回滚：applyCircuit 抛异常 ⇒ 记 circuitFailed ＋ 本批<b>一行</b> WARN，
 *      样板已经在槽里、绝不撤回（撤回等于把玩家刚搬过去的样板弄丢）
 * </pre>
 * 目标那一格没有电路 API / 不在它的电路区（{@link Target#wildcardMotherAt} 回 -1）⇒ <b>忽略</b>并把
 * {@code circuitSkipped} ＋1（放置汇总里看得见，不静默）。
 * <p>🆕 <b>第七刀</b>：「有电路 API 的总成」不止一台 —— 星律样板总成（索引 = 母槽号）
 * 与超级通配符总成（索引 = 它自己的槽号）都由 {@link Target#wildcardMotherAt} 如实报出索引，
 * <b>本类的这三条纪律一个字都没改</b>（两种来源的差别只活在各台总成的适配器里）。
 */
public final class HostPatternMoverPlacer {

    /** 一块待放置的样板（生产实现 = {@code HostPatternMoverTargets.Unit}：条目 ＋ ItemStack ＋ 通配符标志）。 */
    public interface Unit {

        /** 去重键（与第一刀完全同口径：{@code recipe:} / {@code nbt:}）。 */
        String dedupKey();

        /** 配方类型 id；{@code ""} = 未识别。 */
        String type();

        /** 是不是通配符样板（只有分区总成关心）。 */
        boolean wildcard();

        /**
         * 🔴 第六刀：这条样板从<b>有电路 API 的通配符槽</b>读来的<b>编程电路</b>（1..32）；
         * {@link HostPatternMoverCircuit#NONE}（0）= 不设置 ⇒ <b>一次都不许往下传</b>。
         *
         * <p>默认 0 ⇒ 本刀之前写的所有 {@code Unit} 实现（含自检里的假块）<b>逐位不变</b>。
         */
        default int circuit() {
            return HostPatternMoverCircuit.NONE;
        }
    }

    /** 一台目标总成（生产实现 = {@code HostPatternMoverTargets.InventoryTarget}）。 */
    public interface Target {

        /** 日志/面板用的标号（坐标 ＋ 模块名）。 */
        String label();

        /** 槽数（{@code getTerminalPatternInventory().size()}）。 */
        int size();

        /** 这一格现有的样板的去重键；<b>空槽 ⇒ {@code null}</b>。 */
        String keyAt(int slot);

        /** 这台总成所属的模块支持哪些配方类型（{@code IRecipeLogicMachine#getRecipeTypes()}）。 */
        boolean supportsType(String type);

        /** 是否声明了「普通槽 / 通配符槽」分区（星律样板总成 = true）。 */
        boolean zoned();

        /** 普通槽数（{@code zoned()} 时有效）。 */
        int normalSlots();

        /** 通配符槽数（{@code zoned()} 时有效）。 */
        int wildcardSlots();

        /** 真总成自己的槽校验（{@code isItemValid}）；分区判据在 {@link #validAtCached} 里另外做。 */
        boolean validAt(int slot, Unit unit);

        /**
         * 写一格。
         *
         * <p>🔴 生产实现必须走 {@code getTerminalPatternInventory().setItemDirect(slot, stack)} ——
         * 上游的 {@code setItemDirect} 自己会做 {@code setStackInSlot + onContentsChanged + onPatternChange}
         * （{@code MEPatternBufferPartMachine.java:122-126}），所以<b>不许</b>再补一次
         * {@code onPatternChange}（补了就是两次失效/两次 AE 重推）。
         * 断言实现必须在这里记一次「通知」（= 模拟上游那一次），见
         * {@code HostPatternMoverSelfCheck} 的第 7 组。
         * <p>🔴 第六刀：<b>写电路不在这里</b> —— 它必须发生在 {@code write} <b>成功之后</b>，
         * 由 {@link #apply} 调 {@link #applyCircuit}（这样"写样板失败"绝不会连累电路，反之亦然）。
         */
        void write(int slot, Unit unit);

        /**
         * 🔴 第六刀：这一格的<b>电路索引</b>（星律 = 母槽号 {@code 0..49}；
         * 超级通配符 = 它自己的槽号 {@code 0..9}；第七刀）。
         *
         * @return 可写电路的索引；不是那一格 / 没有该 API ⇒ {@code -1}
         *         （默认 {@code -1} ⇒ 本刀之前写的所有 {@code Target} 实现逐位不变）
         */
        default int wildcardMotherAt(int slot) {
            return -1;
        }

        /**
         * 🔴 第六刀：把电路写回这一格（<b>只由 {@link #apply} 在样板写成功之后调</b>，
         * 且只在 {@code circuit != 0} 且 {@link #wildcardMotherAt} 给出了索引时调）。
         *
         * <p>默认实现<b>故意抛</b>而不是静默：一个"报了有索引、却没实现写电路"的总成是
         * <b>写坏了</b>，静默会让电路丢失且无人上报；抛出去会被 {@link #apply} 记成
         * {@code circuitFailed} 并打一行 WARN（样板<b>不回滚</b>）。
         */
        default void applyCircuit(int slot, int circuit) {
            throw new UnsupportedOperationException("这台总成没有「写通配符母槽电路」的实现");
        }
    }

    /** 每块样板的结论。 */
    public enum Decision {
        /** 填进了空槽。 */
        PLACE_NEW,
        /** 覆盖了同 id 的那一格。 */
        PLACE_REPLACE,
        /** 不放入：类型未识别（空）。 */
        SKIP_UNKNOWN_TYPE,
        /** 不放入：目标里没有模块支持这个类型。 */
        SKIP_NO_MODULE,
        /** 不放入：没有空槽 / 槽校验不过。 */
        SKIP_NO_SLOT
    }

    /** 一条写入指令（{@code targetIndex} 指向 {@link #plan} 收到的那张表）。 */
    public record Write(int targetIndex, int slot, int unitIndex, boolean replaced) {

        public String describe() {
            return "t" + this.targetIndex + "@" + this.slot + (this.replaced ? "(替换)" : "");
        }
    }

    /** 一份<b>不落盘</b>的计划（确认面板显示的就是它）。 */
    public record Plan(List<Write> writes, Decision[] decisions, int requested) {

        public Plan {
            writes = List.copyOf(writes);
        }

        public int count(Decision d) {
            int n = 0;
            for (Decision x : this.decisions) {
                if (x == d) {
                    n++;
                }
            }
            return n;
        }

        /** 计划里会被写进去的块数（新增＋覆盖）。 */
        public int plannedWrite() {
            return this.writes.size();
        }

        public int plannedAdd() {
            int n = 0;
            for (Write w : this.writes) {
                if (!w.replaced()) {
                    n++;
                }
            }
            return n;
        }

        public int plannedReplace() {
            return this.writes.size() - plannedAdd();
        }

        /** 放不下的块数（三个 SKIP 相加）。 */
        public int plannedSkip() {
            return this.requested - this.writes.size();
        }

        /** 一行式读数（面板/日志用，格式稳定）。 */
        public String describe() {
            return "requested=" + this.requested + " put=" + plannedWrite()
                    + "(+new " + plannedAdd() + " / ~rep " + plannedReplace() + ")"
                    + " skip=" + plannedSkip()
                    + "(unknownType " + count(Decision.SKIP_UNKNOWN_TYPE)
                    + " / noModule " + count(Decision.SKIP_NO_MODULE)
                    + " / noSlot " + count(Decision.SKIP_NO_SLOT) + ")";
        }
    }

    /**
     * 一份<b>已执行</b>的读数（用户要求的那一行日志的字段全在这里）。
     *
     * @param placed              真的写进去了几块（= added + replaced）
     * @param added               其中填进空槽的
     * @param replaced            其中覆盖同 id 的
     * @param skippedUnknownType  没放的：类型未识别
     * @param skippedNoModule     没放的：目标没有模块支持这个类型
     * @param skippedNoSlot       没放的：没空槽 / 槽校验不过
     * @param unplacedIndices     没放进去的那些条目在原表里的下标（调用方要<b>留在物品里</b>）
     * @param written             真的写成功的那些指令
     * @param circuits            第六刀：<b>写回</b>了几个非 0 的编程电路
     * @param circuitSkipped      第六刀：因为目标那一格<b>没有电路 API</b>（或不在它的电路区）而忽略的电路个数
     * @param circuitFailed       第六刀：调了机器侧 API 但抛异常的次数（样板<b>不回滚</b>）
     */
    public record Result(int placed, int added, int replaced,
                         int skippedUnknownType, int skippedNoModule, int skippedNoSlot,
                         List<Integer> unplacedIndices, List<Write> written,
                         int circuits, int circuitSkipped, int circuitFailed) {

        public Result {
            unplacedIndices = List.copyOf(unplacedIndices);
            written = List.copyOf(written);
        }

        /**
         * 兼容用的旧形状（第六刀之前那 8 个分量）：电路三项全 0。
         *
         * <p>留着是为了让"只关心样板账"的调用点 / 断言不必写三个 0（口读性），
         * 语义与 {@code new Result(..., 0, 0, 0)} 逐位相同。
         */
        public Result(int placed, int added, int replaced,
                      int skippedUnknownType, int skippedNoModule, int skippedNoSlot,
                      List<Integer> unplacedIndices, List<Write> written) {
            this(placed, added, replaced, skippedUnknownType, skippedNoModule, skippedNoSlot,
                    unplacedIndices, written, 0, 0, 0);
        }

        public static final Result EMPTY = new Result(0, 0, 0, 0, 0, 0, List.of(), List.of());

        public int skipped() {
            return this.skippedUnknownType + this.skippedNoModule + this.skippedNoSlot;
        }

        /** 一行式读数（日志用，字段名与用户点名的日志行一致）。 */
        public String describe() {
            return "placed=" + this.placed + " added=" + this.added + " replaced=" + this.replaced
                    + " skippedEmpty=" + this.skippedUnknownType
                    + " skippedNoModule=" + this.skippedNoModule
                    + " skippedNoSlot=" + this.skippedNoSlot
                    // 第六刀：电路的两项读数（用户点名要的字段名）
                    + " circuits=" + this.circuits
                    + " circuitSkipped=" + this.circuitSkipped;
        }
    }

    private HostPatternMoverPlacer() {}

    // ================================================================ 计划

    /**
     * 算一份计划（<b>一个字节都不写</b>；两台参数都不会被改）。
     *
     * @param units   待放置的样板（顺序 = 物品 NBT 顺序 ⇒ 可复现）
     * @param targets 目标总成（顺序 = 遍历顺序 ⇒ 可复现）
     */
    public static Plan plan(List<? extends Unit> units, List<? extends Target> targets) {
        final int n = units == null ? 0 : units.size();
        final Decision[] decisions = new Decision[n];
        final List<Write> writes = new ArrayList<>();
        if (n == 0 || targets == null || targets.isEmpty()) {
            for (int i = 0; i < n; i++) {
                // 一台总成都没有时仍然按「类型未识别 / 无对应模块」分开记（不许把两种原因混成一堆）
                decisions[i] = units.get(i) != null && !norm(units.get(i).type()).isEmpty()
                        ? Decision.SKIP_NO_MODULE : Decision.SKIP_UNKNOWN_TYPE;
            }
            return new Plan(writes, decisions, n);
        }

        // ── 每台总成的「已占格」快照：key → 槽号（同 key 只记最小的那一格，与"第一个空槽"同向）
        final Overlay[] overlays = new Overlay[targets.size()];
        for (int t = 0; t < overlays.length; t++) {
            overlays[t] = new Overlay(targets.get(t));
        }

        // ── pass 1：覆盖（只在已经存在同 id 的格子里换）
        for (int i = 0; i < n; i++) {
            final Unit u = units.get(i);
            final String type = norm(u.type());
            if (type.isEmpty()) {
                decisions[i] = Decision.SKIP_UNKNOWN_TYPE;
                continue;
            }
            int hitTarget = -1;
            int hitSlot = -1;
            for (int t = 0; t < overlays.length; t++) {
                final Target tg = targets.get(t);
                if (!tg.supportsType(type)) {
                    continue;
                }
                final int slot = overlays[t].firstSlotOfKey(u.dedupKey());
                if (slot >= 0 && validAtCached(tg, slot, u)) {
                    hitTarget = t;
                    hitSlot = slot;
                    break;
                }
            }
            if (hitTarget >= 0) {
                decisions[i] = Decision.PLACE_REPLACE;
                writes.add(new Write(hitTarget, hitSlot, i, true));
                overlays[hitTarget].occupy(hitSlot, u.dedupKey());
            }
        }

        // ── pass 2：填空（pass 1 没处理的，才去占空槽）
        for (int i = 0; i < n; i++) {
            if (decisions[i] != null) {
                continue;
            }
            final Unit u = units.get(i);
            final String type = norm(u.type());
            boolean anySupport = false;
            int hitTarget = -1;
            int hitSlot = -1;
            for (int t = 0; t < overlays.length; t++) {
                final Target tg = targets.get(t);
                if (!tg.supportsType(type)) {
                    continue;
                }
                anySupport = true;
                final int slot = overlays[t].firstFreeValid(u);
                if (slot >= 0) {
                    hitTarget = t;
                    hitSlot = slot;
                    break;
                }
            }
            if (!anySupport) {
                decisions[i] = Decision.SKIP_NO_MODULE;
            } else if (hitTarget < 0) {
                decisions[i] = Decision.SKIP_NO_SLOT;
            } else {
                decisions[i] = Decision.PLACE_NEW;
                writes.add(new Write(hitTarget, hitSlot, i, false));
                overlays[hitTarget].occupy(hitSlot, u.dedupKey());
            }
        }
        return new Plan(writes, decisions, n);
    }

    // ================================================================ 执行

    /**
     * 执行一份计划（真正写机器；<b>不碰物品</b>）。
     *
     * <p>🔴 单条失败只影响那一条：写之前<b>再校验一次</b>（槽号在范围内 ＋ 分区判据 ＋
     * {@code validAt}），失败就把它记成 {@link Decision#SKIP_NO_SLOT} 并继续下一条。
     */
    public static Result apply(Plan plan, List<? extends Unit> units, List<? extends Target> targets) {
        if (plan == null || units == null || targets == null || targets.isEmpty()) {
            return plan == null ? Result.EMPTY
                    : new Result(0, 0, 0, plan.count(Decision.SKIP_UNKNOWN_TYPE),
                    plan.count(Decision.SKIP_NO_MODULE), plan.count(Decision.SKIP_NO_SLOT),
                    allIndices(units == null ? 0 : units.size()), List.of(), 0, 0, 0);
        }
        int added = 0;
        int replaced = 0;
        int noSlot = plan.count(Decision.SKIP_NO_SLOT);
        int circuits = 0;
        int circuitSkipped = 0;
        int circuitFailed = 0;
        // 🔴 第六刀：写电路的 WARN 同一批最多 1 行（否则 1168 格全失败时日志会被刷爆）
        boolean circuitWarned = false;
        final List<Integer> unplaced = new ArrayList<>();
        final List<Write> written = new ArrayList<>();

        // 先按「每条样板」归拢：一块样板只会有一条写指令
        final Map<Integer, Write> byUnit = new HashMap<>();
        for (Write w : plan.writes()) {
            byUnit.put(w.unitIndex(), w);
        }
        for (int i = 0; i < units.size(); i++) {
            final Unit u = units.get(i);
            final Write w = byUnit.get(i);
            if (w == null) {
                // 🔴 没有写指令 ⇒ 这一块一定【留在物品里】（三个 SKIP 全算）
                unplaced.add(i);
                if (plan.decisions()[i] == Decision.PLACE_NEW
                        || plan.decisions()[i] == Decision.PLACE_REPLACE) {
                    // 理论上不可能（PLACE_* 必有 Write）—— 记成放不下，不静默
                    noSlot++;
                }
                continue;
            }
            final Target tg = targets.get(w.targetIndex());
            int slot = w.slot();
            if (!slotOk(tg, slot, u)) {
                // 落点在这几毫秒里被别人占了 / 槽号越界 ⇒ 在这台总成里重新找一个空槽
                slot = firstFreeValidNow(tg, u);
            }
            if (slot < 0) {
                noSlot++;
                unplaced.add(i);
                continue;
            }
            try {
                tg.write(slot, u);
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 写入失败（跳过这一块）target={} slot={} err={}",
                        HostPatternMoverLog.PREFIX, tg.label(), slot, t.toString());
                noSlot++;
                unplaced.add(i);
                continue;
            }
            written.add(new Write(w.targetIndex(), slot, i, w.replaced() && slot == w.slot()));
            if (w.replaced() && slot == w.slot()) {
                replaced++;
            } else {
                added++;
            }

            // ── 🔴 第六刀 C：**样板已经放成功之后**才写电路。
            //    顺序是刻意的：槽校验/类型分配/真写入全部走完，这里才碰机器的另一份状态。
            //    写电路失败→只记一行 WARN，**绝不回滚样板**（样板已经在槽里了，回滚等于把它弄丢）。
            //    circuit == 0（源那边没设置）⇒ 一次都不调：0 是"不设置"，不是"清除目标"。
            final int circuit = HostPatternMoverCircuit.normalize(u.circuit());
            if (circuit != HostPatternMoverCircuit.NONE) {
                final int circuitIndex = tg.wildcardMotherAt(slot);
                if (circuitIndex < 0) {
                    circuitSkipped++; // 目标那一格没有电路 API / 不在它的电路区 ⇒ 忽略（汇总里看得见）
                } else {
                    try {
                        tg.applyCircuit(slot, circuit);
                        circuits++;
                    } catch (Throwable t) {
                        circuitFailed++;
                        if (!circuitWarned) {
                            circuitWarned = true;
                            HostPatternMoverLog.warn("{} 写通配符电路失败（样板不回滚，继续放置；"
                                            + "本批只报这一行）target={} slot={} circuit={} err={}",
                                    HostPatternMoverLog.PREFIX, tg.label(), Integer.valueOf(slot),
                                    Integer.valueOf(circuit), t.toString());
                        }
                    }
                }
            }
        }
        return new Result(added + replaced, added, replaced,
                plan.count(Decision.SKIP_UNKNOWN_TYPE), plan.count(Decision.SKIP_NO_MODULE), noSlot,
                unplaced, written, circuits, circuitSkipped, circuitFailed);
    }

    // ================================================================ 槽校验（唯一判据点）

    /**
     * ★<b>槽校验的唯一判据点</b>（自检第 4 组就是打这一行）。
     *
     * <pre>
     *   ① 槽号必须在 [0, size)
     *   ② 分区总成：普通样板只在 [0, normalSlots)；通配符样板只在 [normalSlots, +wildcardSlots)
     *   ③ 再问总成自己的 isItemValid
     * </pre>
     */
    static boolean validAtCached(Target target, int slot, Unit unit) {
        return slotOk(target, slot, unit) && validAtOwn(target, slot, unit);
    }

    /** 槽号 ＋ 分区两件事（不含 {@code isItemValid}）—— "分区"这一层可以被单独拆掉做负对照。 */
    static boolean slotOk(Target target, int slot, Unit unit) {
        if (target == null || unit == null || slot < 0 || slot >= target.size()) {
            return false;
        }
        if (!target.zoned()) {
            return true;
        }
        final int normal = target.normalSlots();
        final int wild = target.wildcardSlots();
        if (wild < 0 || normal < 0) {
            return false;
        }
        if (unit.wildcard()) {
            return slot >= normal && slot < normal + wild;
        }
        return slot < normal;
    }

    /** 总成自己的 {@code isItemValid}（分区总成由本核先判完，这里只再兜一次）。 */
    static boolean validAtOwn(Target target, int slot, Unit unit) {
        try {
            return target.validAt(slot, unit);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 现在（不看计划）在这台总成里找一个能放的空槽；找不到 ⇒ -1。 */
    static int firstFreeValidNow(Target target, Unit unit) {
        if (target == null) {
            return -1;
        }
        final int size;
        try {
            size = target.size();
        } catch (Throwable t) {
            return -1;
        }
        for (int slot = 0; slot < size; slot++) {
            final String key;
            try {
                key = target.keyAt(slot);
            } catch (Throwable t) {
                continue;
            }
            if (key != null && !key.isEmpty()) {
                continue;
            }
            if (validAtCached(target, slot, unit)) {
                return slot;
            }
        }
        return -1;
    }

    // ================================================================ 遮罩

    /**
     * 一台总成的「计划占用」遮罩：{@code slot → key} ＋ {@code key → slot}。
     *
     * <p>它存在的意义：plan 必须<b>不改机器</b>也能算出「第 2、第 3 块会落到哪一格」，
     * 所以计划过程中占用的格子记在这里，而不是真写进去。
     */
    private static final class Overlay {
        private final Target target;
        private final int size;
        private final Map<Integer, String> slotKey = new HashMap<>();
        private final Map<String, Integer> keySlot = new HashMap<>();

        Overlay(Target target) {
            this.target = target;
            int s = 0;
            try {
                s = Math.max(0, target.size());
            } catch (Throwable t) {
                s = 0;
            }
            this.size = s;
            for (int slot = 0; slot < this.size; slot++) {
                String key;
                try {
                    key = target.keyAt(slot);
                } catch (Throwable t) {
                    continue;
                }
                if (key == null || key.isEmpty()) {
                    continue;
                }
                this.slotKey.put(Integer.valueOf(slot), key);
                this.keySlot.putIfAbsent(key, Integer.valueOf(slot));
            }
        }

        int firstSlotOfKey(String key) {
            if (key == null || key.isEmpty()) {
                return -1;
            }
            final Integer at = this.keySlot.get(key);
            return at == null ? -1 : at.intValue();
        }

        int firstFreeValid(Unit unit) {
            for (int slot = 0; slot < this.size; slot++) {
                if (this.slotKey.containsKey(Integer.valueOf(slot))) {
                    continue;
                }
                if (validAtCached(this.target, slot, unit)) {
                    return slot;
                }
            }
            return -1;
        }

        void occupy(int slot, String key) {
            this.slotKey.put(Integer.valueOf(slot), key == null ? "" : key);
            if (key != null && !key.isEmpty()) {
                this.keySlot.put(key, Integer.valueOf(slot));
            }
        }
    }

    // ================================================================ 小工具

    private static List<Integer> allIndices(int n) {
        final List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return Collections.unmodifiableList(out);
    }

    static String norm(String type) {
        return type == null ? "" : type.trim();
    }
}
