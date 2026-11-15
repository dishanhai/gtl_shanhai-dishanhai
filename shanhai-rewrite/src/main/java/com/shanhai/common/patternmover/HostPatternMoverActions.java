package com.shanhai.common.patternmover;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>服务端动作层</b>：放置（含确认）＋ 取出 ＋ 清空物品 ＋ 清空主机。
 *
 * <h2>一、这一层负责什么、不负责什么</h2>
 * <ul>
 *   <li><b>负责</b>：把「物品 NBT 里那份数据」与「机器上那些槽」之间的账算清楚，
 *       并在每一步都把读数报给玩家（聊天栏）与日志（{@code [SHANHAI-MOVER]} 一行）；</li>
 *   <li><b>不负责</b>：分配/落槽的规则本身（在 {@link HostPatternMoverPlacer} 纯核里）、
 *       清空/通配符识别/分页的规则（分别在 {@link HostPatternMoverClear}、
 *       {@link HostPatternMoverWildcard}、{@link HostPatternMoverPager} 三个纯核里）、
 *       目标总成的遍历（在 {@link HostPatternMoverTargets}）、界面（在
 *       {@link HostPatternMoverPanel} / {@link HostPatternMoverConfirmPanel} /
 *       {@link HostPatternMoverClearPanel}）。</li>
 * </ul>
 *
 * <h2>二、🔴 「取消」为什么是结构性的</h2>
 * {@link #preview}（右键主机时）只<b>算</b>计划，一个槽都不写；
 * 真正写机器的只有 {@link #confirmPlace}（确认面板那个按钮调的）。
 * 同理「清空主机」：{@link #clearPreview} 只算，{@link #confirmClear} 才写。
 * ⇒ 取消 = 预览之后什么也没发生，目标机器的指纹必然<b>逐字节不变</b>
 * （自检里拿这个当判据，并配一条"真执行 ⇒ 指纹必须变"的负对照）。
 *
 * <h2>三、动作的通道</h2>
 * 全部由 LDLib 的控件回调触发：{@code ButtonWidget} 的点击会
 * {@code writeClientAction → CPacketUIClientAction → 服务端同 id 控件的 handleClientAction}
 * （本工程既有实证见 {@code StarRenderConfigurator} 类注释）；
 * 服务端那次回调里 {@code ClickData#isRemote} 为 {@code false} ⇒ 本类只在那一侧执行。
 * ⇒ <b>没有新开任何自定义网络包</b>。
 *
 * <h2>四、物品上手（找不到就明说，不静默）</h2>
 * 面板里的动作要改物品 NBT，而物品在玩家手上。这里按
 * 「主手 → 副手」找一个 {@code shanhai:host_pattern_mover}；都没有 ⇒ 返回 {@code null}，
 * 调用方报「搬运工具不在手上」（面板不关，玩家可以放回去再点）。
 * <p>⚠️ {@link #findMover} 收的是 {@link Player} 而不是 {@code ServerPlayer}：
 * 客户端那份面板也要用它把手上的物品<b>镜像重读</b>一遍（C：点完「取出」页面要自己变）。
 */
public final class HostPatternMoverActions {

    private static final String PREFIX = HostPatternMoverLog.PREFIX;

    private HostPatternMoverActions() {}

    // ================================================================ ① 放置：预览

    /**
     * 右键主机（<b>不按 Shift</b>）时的入口：算一份计划，弹<b>确认面板</b>（不写任何槽）。
     *
     * @return 有没有真的弹面板
     */
    public static boolean preview(ServerPlayer player, ItemStack stack, MetaMachine clicked, BlockPos pos) {
        if (player == null || stack == null || stack.isEmpty() || clicked == null) {
            return false;
        }
        final List<HostPatternMoverEntry> entries =
                HostPatternMoverNbt.readEntries(HostPatternMoverNbt.moverOf(stack.getTag()));
        if (entries.isEmpty()) {
            player.displayClientMessage(Component.translatable("shanhai.mover.place_empty_item"), false);
            return false;
        }
        final List<HostPatternMoverReader.Assembly> assemblies = HostPatternMoverTargets.collect(clicked);
        if (assemblies.isEmpty()) {
            player.displayClientMessage(Component.translatable("shanhai.mover.no_assembly"), false);
            return false;
        }
        final HostPatternMoverTargets.Compiled compiled = HostPatternMoverTargets.compile(assemblies);
        if (compiled.count() == 0) {
            player.displayClientMessage(Component.translatable("shanhai.mover.no_assembly"), false);
            return false;
        }
        final List<HostPatternMoverPlacer.Unit> units = HostPatternMoverTargets.units(entries);
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, compiled.targets());
        if (plan.plannedWrite() == 0) {
            // 一块都放不进 ⇒ 不弹空面板，直接把三种原因报清楚（面板只做"确认"，不做无意义的确认）
            player.displayClientMessage(Component.translatable("shanhai.mover.place_nothing",
                    plan.requested(), plan.count(HostPatternMoverPlacer.Decision.SKIP_UNKNOWN_TYPE),
                    plan.count(HostPatternMoverPlacer.Decision.SKIP_NO_MODULE),
                    plan.count(HostPatternMoverPlacer.Decision.SKIP_NO_SLOT)), false);
            HostPatternMoverLog.info("{} place_preview host={} assemblies={} {} | pos={}",
                    PREFIX, hostTag(clicked), compiled.count(), plan.describe(), pos.toShortString());
            return false;
        }
        final HostPatternMoverFactory.PlacePreview preview = new HostPatternMoverFactory.PlacePreview(
                pos, plan.requested(), plan.plannedAdd(), plan.plannedReplace(),
                plan.plannedSkip(), plan.count(HostPatternMoverPlacer.Decision.SKIP_UNKNOWN_TYPE),
                plan.count(HostPatternMoverPlacer.Decision.SKIP_NO_MODULE),
                plan.count(HostPatternMoverPlacer.Decision.SKIP_NO_SLOT),
                compiled.infos());
        HostPatternMoverLog.info("{} place_preview host={} assemblies={} {} | pos={}",
                PREFIX, hostTag(clicked), compiled.count(), plan.describe(), pos.toShortString());
        return HostPatternMoverFactory.openConfirm(player, preview);
    }

    // ================================================================ ② 放置：执行

    /**
     * 确认面板的「确认放置」：<b>重新算一遍</b>计划并执行（面板显示的是几秒前的现场，
     * 真正写之前必须按<b>此刻</b>的槽状态再判一次）。
     *
     * <p>执行顺序（用户拍板）：先写机器 → 再把<b>已放入的那些</b>从物品 NBT 删掉 →
     * 放不下的原样留在物品里 → 打一行 INFO。
     */
    public static boolean confirmPlace(ServerPlayer player, ItemStack stack, BlockPos pos) {
        if (player == null || stack == null || pos == null) {
            return false;
        }
        final Level level = player.level();
        final MetaMachine clicked = machineAt(level, pos);
        if (clicked == null || !HostPatternMoverReader.looksLikeTarget(clicked)) {
            player.displayClientMessage(Component.translatable("shanhai.mover.place_host_gone"), false);
            return false;
        }
        final CompoundTag mover = HostPatternMoverNbt.moverOf(stack.getTag());
        final String fromHost = HostPatternMoverNbt.readFromHost(mover);
        final List<HostPatternMoverEntry> entries = HostPatternMoverNbt.readEntries(mover);
        if (entries.isEmpty()) {
            player.displayClientMessage(Component.translatable("shanhai.mover.place_empty_item"), false);
            return false;
        }
        final List<HostPatternMoverReader.Assembly> assemblies = HostPatternMoverTargets.collect(clicked);
        final HostPatternMoverTargets.Compiled compiled = HostPatternMoverTargets.compile(assemblies);
        final List<HostPatternMoverPlacer.Unit> units = HostPatternMoverTargets.units(entries);
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, compiled.targets());
        // 🔴 第四刀 ①：整段放置放进【批量写入】—— 星律样板总成每写一格就整表重建一次，
        //    1168 格 ⇒ 1168 次重建（实机单 tick 40 秒被看门狗抓）。批量里只置脏，收尾建一次。
        //    try/finally 在 HostPatternMoverTargets#inBatchWrite 里（异常也一定收尾）。
        final HostPatternMoverPlacer.Result result = HostPatternMoverTargets.inBatchWrite(
                () -> HostPatternMoverPlacer.apply(plan, units, compiled.targets()));

        // ── 把已放入的从物品里删掉；放不下的留在物品里（顺序 = 原 NBT 顺序）
        final Set<Integer> unplaced = new LinkedHashSet<>(result.unplacedIndices());
        final List<HostPatternMoverEntry> keep = new ArrayList<>(unplaced.size());
        for (int i = 0; i < entries.size(); i++) {
            if (unplaced.contains(Integer.valueOf(i))) {
                keep.add(entries.get(i));
            }
        }
        final boolean stored = HostPatternMoverReader.storeTo(stack, keep, fromHost);
        if (!stored) {
            // 只可能发生在"删掉条目之后反而超限"这种反直觉情形（上限被调小过）—— 如实报，不吞
            HostPatternMoverLog.warn("{} 放置后写回物品失败（物品保持原样）keep={} err=byte_gate",
                    PREFIX, keep.size());
            player.displayClientMessage(Component.translatable("shanhai.mover.place_store_failed"), false);
            return false;
        }
        final int remaining = remainingBytes(stack);
        player.displayClientMessage(Component.translatable("shanhai.mover.place_done",
                result.placed(), result.replaced(), result.added(),
                result.skippedUnknownType(), result.skippedNoModule(), result.skippedNoSlot(),
                remaining, HostPatternMoverData.LIMIT_BYTES), false);
        // 🔴 第六刀：电路是"机器的另一份状态"，没写回去必须让玩家看见（不静默丢）
        if (result.circuitSkipped() > 0 || result.circuitFailed() > 0) {
            player.displayClientMessage(Component.translatable("shanhai.mover.place_circuit_lost",
                    result.circuitSkipped(), result.circuitFailed()), false);
        }
        HostPatternMoverLog.info("{} place host={} assemblies={} requested={} placed={} replaced={}"
                        + " skippedEmpty={} skippedNoModule={} skippedNoSlot={} remaining={}/{}"
                        // 🔴 第六刀：写回几个非 0 电路 / 因目标那一格没有电路 API 而忽略几个 / 写入失败几个
                        + " circuits={} circuitSkipped={} circuitFailed={} | pos={}",
                PREFIX, hostTag(clicked), compiled.count(), plan.requested(), result.placed(),
                result.replaced(), result.skippedUnknownType(), result.skippedNoModule(),
                result.skippedNoSlot(), remaining, HostPatternMoverData.LIMIT_BYTES,
                result.circuits(), result.circuitSkipped(), result.circuitFailed(),
                pos.toShortString());
        return true;
    }

    // ================================================================ ③ 取出（交出物品，不销毁）

    /**
     * 取出：把物品里<b>某一组（按 type）</b>的样板<b>作为物品交给玩家</b>（用户 2026-11-06 改的口径）。
     *
     * <h2>🔴 三条硬规则（顺序不能换）</h2>
     * <pre>
     *   ① 先做【乐观预检】：假设"这一组全被取走"，这份 NBT 还写不写得回去？
     *      写不回（只可能来自被手改过的超限物品）⇒ 一个字节都不动，直接告诉玩家 ——
     *      否则会出现"物品已经交出去了、NBT 又写不回去"⇒ 玩家白赚一份（复制）。
     *   ② 再走纯核 {@link HostPatternMoverTakeout#run}：逐块 try 背包 → try 掉落 → 都不成 = 留在物品里。
     *   ③ 最后才把"该留下的那些"写回物品 NBT。
     *      ⇒ 任何一步失败，最坏结果是"没取成"（数据还在），【不可能】是"取没了"。
     * </pre>
     * <p>预检能过的证明：真正要写的 keep 表是 entries 的子集（只删不增），
     * 而 entries 现在就已经在物品里（≤ 上限）⇒ 子集必然 ≤ 上限。
     *
     * @param type 类型 id（{@code ""} = 类型未识别那一组）
     * @return 账（{@code removed == toInventory + dropped}）；{@code null} 表示<b>预检没过、物品一个字节都没动</b>
     */
    public static HostPatternMoverTakeout.Result takeOut(ServerPlayer player, ItemStack stack, String type) {
        if (player == null || stack == null || stack.isEmpty()) {
            return HostPatternMoverTakeout.Result.EMPTY;
        }
        final CompoundTag mover = HostPatternMoverNbt.moverOf(stack.getTag());
        final String fromHost = HostPatternMoverNbt.readFromHost(mover);
        final List<HostPatternMoverEntry> entries = HostPatternMoverNbt.readEntries(mover);
        final String target = norm(type);

        // ① 乐观预检（见类注释）
        final List<HostPatternMoverEntry> optimistic = new ArrayList<>(entries.size());
        for (HostPatternMoverEntry e : entries) {
            if (!norm(e.type()).equals(target)) {
                optimistic.add(e);
            }
        }
        if (HostPatternMoverNbt.toTag(optimistic, fromHost, HostPatternMoverData.LIMIT_BYTES) == null) {
            player.displayClientMessage(Component.translatable("shanhai.mover.take_blocked"), false);
            HostPatternMoverLog.warn("{} take 预检失败（假设全取走也写不回 NBT）—— 物品一个字节都没动 type={}",
                    PREFIX, target.isEmpty() ? "<unknown>" : target);
            return null;
        }

        // ② 交出（纯核记账：背包 / 掉落 / 留在物品里）
        final List<HostPatternMoverPlacer.Unit> units = HostPatternMoverTargets.units(entries);
        final HostPatternMoverTakeout.Result result =
                HostPatternMoverTakeout.run(units, target, new PlayerHandout(player));
        if (result.removed() == 0) {
            return result; // 一块都没交出去 ⇒ 物品不动（不做"删了但没给"）
        }

        // ③ 写回"留下的那些"
        final Set<Integer> keptSet = new LinkedHashSet<>(result.keptIndices());
        final List<HostPatternMoverEntry> keep = new ArrayList<>(entries.size() - result.removed());
        for (int i = 0; i < entries.size(); i++) {
            final boolean matched = norm(entries.get(i).type()).equals(target);
            if (matched && !keptSet.contains(Integer.valueOf(i))) {
                continue; // 这一块已经交给玩家了
            }
            keep.add(entries.get(i));
        }
        if (!HostPatternMoverReader.storeTo(stack, keep, fromHost)) {
            // 预检过了还写不回 ⇒ 只能如实记账（那几块现在"物品里也还在" ⇒ 多给了一份，必须出声）
            HostPatternMoverLog.warn("{} take 写回物品失败（块已交给玩家、物品未变）type={} taken={}",
                    PREFIX, target.isEmpty() ? "<unknown>" : target, result.removed());
            player.displayClientMessage(Component.translatable("shanhai.mover.place_store_failed"), false);
            return result;
        }
        player.displayClientMessage(Component.translatable("shanhai.mover.take_done",
                result.removed(), result.toInventory(), result.dropped(), result.kept(),
                remainingBytes(stack)), false);
        HostPatternMoverLog.info("{} take type={} taken={} inventory={} dropped={} kept={} left={} bytes={}/{}",
                PREFIX, target.isEmpty() ? "<unknown>" : target, result.removed(), result.toInventory(),
                result.dropped(), result.kept(), keep.size(), remainingBytes(stack),
                HostPatternMoverData.LIMIT_BYTES);
        return result;
    }

    /**
     * {@link HostPatternMoverTakeout.Handout} 的生产实现：<b>背包 → 掉落</b>。
     *
     * <p>🔴 只交 <b>Count == 1</b> 的那些：一块样板本来就是 1 个物品，而
     * {@code Inventory#add} 的"部分收下"语义在不同版本上不一样
     * ⇒ 遇到 Count&gt;1 的怪 NBT（手改过）时，<b>宁可留在物品里并出声</b>，也不做可能有损的拆分。
     */
    private record PlayerHandout(ServerPlayer player) implements HostPatternMoverTakeout.Handout {

        @Override
        public boolean toInventory(HostPatternMoverPlacer.Unit unit, int index) {
            final ItemStack give = single(unit);
            if (give == null) {
                return false;
            }
            final ItemStack copy = give.copy();
            final int want = copy.getCount();
            final boolean added = this.player.getInventory().add(copy);
            final int left = Math.max(0, copy.getCount());
            if (!added) {
                return false;
            }
            // add 有的版本消耗参数、有的不消耗 ⇒ 两种都认（"说收下了且没剩" 或 "说收下了且参数没动"）
            return left == 0 || left == want;
        }

        @Override
        public boolean drop(HostPatternMoverPlacer.Unit unit, int index) {
            final ItemStack give = single(unit);
            if (give == null) {
                return false;
            }
            try {
                // 🔴 掉在脚下（不是丢出去）：玩家能当场看见，不会"凭空消失"
                return this.player.drop(give.copy(), false) != null;
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 取出：掉落失败 err={}", PREFIX, t.toString());
                return false;
            }
        }

        /** 这一块对应的「1 个」物品；造不出 / Count != 1 ⇒ {@code null}（= 留在物品里）。 */
        private static ItemStack single(HostPatternMoverPlacer.Unit unit) {
            if (!(unit instanceof HostPatternMoverTargets.Unit u)) {
                return null;
            }
            final ItemStack give = u.stack();
            if (give == null || give.isEmpty() || give.getCount() != 1) {
                return null;
            }
            return give;
        }
    }

    // ================================================================ ④ 清空「物品里」的全部样板（F）

    /**
     * 清空物品里的<b>全部</b>样板（用户 2026-11-08 新增：面板里一个按钮，<b>点一次直接执行</b>）。
     *
     * <p>🔴 只碰物品 NBT：{@code shanhai:mover.patterns} 清空（结构保留 ⇒ 字节口径与
     * {@code storeTo} 同一条路），并把物品根上的「上一次通配符识别计数」一并清掉
     * （那些计数描述的是已经不存在的样板，留着就是假读数）。
     *
     * @return 清掉了几块（0 = 本来就没有）
     */
    public static int clearItem(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return 0;
        }
        final CompoundTag mover = HostPatternMoverNbt.moverOf(stack.getTag());
        final int n = HostPatternMoverNbt.countOf(mover);
        if (n <= 0) {
            player.displayClientMessage(Component.translatable("shanhai.mover.clear_item_none"), false);
            return 0;
        }
        final String fromHost = HostPatternMoverNbt.readFromHost(mover);
        if (!HostPatternMoverReader.storeTo(stack, List.of(), fromHost)) {
            // 只可能来自被手改过的超限物品 —— 如实报，不吞
            HostPatternMoverLog.warn("{} clear_item 写回失败（物品原样）count={} bytes={}/{}",
                    PREFIX, n, HostPatternMoverNbt.effectiveBytes(mover), HostPatternMoverData.LIMIT_BYTES);
            player.displayClientMessage(Component.translatable("shanhai.mover.place_store_failed"), false);
            return 0;
        }
        HostPatternMoverNbt.writeStats(stack, HostPatternMoverNbt.ReadStats.EMPTY);
        player.displayClientMessage(Component.translatable("shanhai.mover.clear_item_done", n,
                remainingBytes(stack), HostPatternMoverData.LIMIT_BYTES), false);
        HostPatternMoverLog.info("{} clear_item count={} remaining={}/{} host={}",
                PREFIX, n, remainingBytes(stack), HostPatternMoverData.LIMIT_BYTES,
                fromHost.isEmpty() ? "-" : fromHost);
        return n;
    }

    // ================================================================ ⑤ 清空「主机及其模块」（G）

    /**
     * 右键主机面板里那个「清空」按钮的入口：<b>只算</b>一遍（几个总成 / 共几块），
     * 再弹<b>二次确认页</b>（{@link HostPatternMoverClearPanel}）。一个槽都不写。
     *
     * @return 有没有真的弹确认页
     */
    public static boolean openClearConfirm(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return false;
        }
        final MetaMachine clicked = machineAt(player.level(), pos);
        if (clicked == null || !HostPatternMoverReader.looksLikeTarget(clicked)) {
            player.displayClientMessage(Component.translatable("shanhai.mover.place_host_gone"), false);
            return false;
        }
        final HostPatternMoverFactory.ClearPreview preview = clearPreview(clicked, pos);
        HostPatternMoverLog.info("{} clear_preview host={} {} | pos={}",
                PREFIX, hostTag(clicked), preview.describe(), pos.toShortString());
        return HostPatternMoverFactory.openClear(player, preview);
    }

    /** 只读：这台主机（＋ 它的模块）上有几个总成、共几块样板（{@link HostPatternMoverClear#plan}）。 */
    public static HostPatternMoverFactory.ClearPreview clearPreview(MetaMachine clicked, BlockPos pos) {
        if (clicked == null) {
            return HostPatternMoverFactory.ClearPreview.EMPTY;
        }
        final List<HostPatternMoverReader.Assembly> assemblies = HostPatternMoverTargets.collect(clicked);
        final List<HostPatternMoverClear.Slots> views = HostPatternMoverTargets.clearViews(assemblies);
        final HostPatternMoverClear.Plan plan = HostPatternMoverClear.plan(views);
        return new HostPatternMoverFactory.ClearPreview(pos, hostTag(clicked), plan.assemblies(),
                plan.slots(), plan.occupied(), HostPatternMoverTargets.summarize(assemblies));
    }

    /**
     * 确认页的「确认清空」：<b>重新收集一遍</b>（面板显示的是几秒前的现场）再逐槽清空。
     *
     * <p>清空范围 = 这台主机 ＋ 它的模块上的<b>所有</b>样板总成（沿用同一个遍历器
     * {@link HostPatternMoverTargets#collect}）；逐槽
     * {@code getTerminalPatternInventory().setItemDirect(i, ItemStack.EMPTY)}（上游内部会通知）。
     */
    public static HostPatternMoverClear.Result confirmClear(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return HostPatternMoverClear.Result.EMPTY;
        }
        final MetaMachine clicked = machineAt(player.level(), pos);
        if (clicked == null || !HostPatternMoverReader.looksLikeTarget(clicked)) {
            player.displayClientMessage(Component.translatable("shanhai.mover.place_host_gone"), false);
            return HostPatternMoverClear.Result.EMPTY;
        }
        final List<HostPatternMoverReader.Assembly> assemblies = HostPatternMoverTargets.collect(clicked);
        // 🔴 第四刀 ①：清空同样是「逐格 setItemDirect」⇒ 与放置共用同一个批量口
        //    （否则清 1168 格照样是 1168 次整表重建，卡死 40 秒的那条路换了个入口而已）。
        final HostPatternMoverClear.Result result = HostPatternMoverTargets.inBatchWrite(
                () -> HostPatternMoverClear.run(HostPatternMoverTargets.clearViews(assemblies)));
        player.displayClientMessage(Component.translatable(result.failed() > 0
                ? "shanhai.mover.clear_host_partial" : "shanhai.mover.clear_host_done",
                result.cleared(), result.failed()), false);
        HostPatternMoverLog.info("{} clear host={} assemblies={} cleared={} occupied={} failed={} | pos={}",
                PREFIX, hostTag(clicked), result.assemblies(), result.cleared(), result.occupied(),
                result.failed(), pos.toShortString());
        return result;
    }

    // ================================================================ 工具

    /** 物品上「已用字节」（读档值优先，没有就现量 —— 与面板同一口径）。 */
    static int remainingBytes(ItemStack stack) {
        final int used = HostPatternMoverNbt.effectiveBytes(HostPatternMoverNbt.moverOf(
                stack == null ? null : stack.getTag()));
        return Math.max(0, HostPatternMoverData.LIMIT_BYTES - used);
    }

    /**
     * 玩家手上那件搬运工具（先 {@code preferred}、再主手、再副手）。没有 ⇒ {@code null}。
     *
     * <p>⚠️ 收 {@link Player} 而不是 {@code ServerPlayer}：客户端那份面板也要用它
     * （C：动作回执之外，客户端还自己把手上的物品镜像重读一遍，见 {@link HostPatternMoverPanel}）。
     */
    public static ItemStack findMover(Player player, InteractionHand preferred) {
        if (player == null) {
            return null;
        }
        if (preferred != null) {
            final ItemStack inPreferred = player.getItemInHand(preferred);
            if (inPreferred.getItem() instanceof HostPatternMoverItem) {
                return inPreferred;
            }
        }
        for (InteractionHand hand : InteractionHand.values()) {
            final ItemStack s = player.getItemInHand(hand);
            if (s.getItem() instanceof HostPatternMoverItem) {
                return s;
            }
        }
        return null;
    }

    /** 主机标签（{@link HostPatternMoverReader#classifyHost} 的结果；认不出 ⇒ {@code "?"}）。 */
    static String hostTag(MetaMachine machine) {
        try {
            final String tag = HostPatternMoverReader.classifyHost(machine);
            return tag == null || tag.isEmpty() ? "?" : tag;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 机器（{@code null} 安全）。 */
    static MetaMachine machineAt(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            return MetaMachine.getMachine(level, pos);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String norm(String type) {
        return type == null ? "" : type.trim();
    }
}
