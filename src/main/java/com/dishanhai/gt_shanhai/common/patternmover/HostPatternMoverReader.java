package com.dishanhai.gt_shanhai.common.patternmover;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternContainer;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternBridge;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>Shift+右键主机 ⇒ 读取全部样板并入物品</b>（第一刀唯一的"动作"）。
 *
 * <h2>一、从「点了哪个方块」走到「全部样板总成」（用户/取证指定的唯一路线）</h2>
 * <pre>
 *   ① 点到的机器自己就是一台总成（AE2 供应器 / 直接点到样板仓）⇒ 收下
 *   ② 点到的是一台 part ⇒ 走它的 {@code IMultiPart#getControllers()}
 *   ③ 点到的是一台 controller（主机 / 模块都算）⇒ 见 ④⑤
 *   ④ {@code controller.getParts()} 里筛 {@code instanceof PatternContainer}  ← ★ 落点 A
 *   ⑤ 模块【不是 part】⇒ {@code IModularMachineHost#getModuleSet()}，
 *      再把每个模块【当 controller 递归】走 ④                           ← ★ 落点 B
 * </pre>
 * ⚠️ <b>判据是接口，不是类名清单</b>：一条 {@code instanceof PatternContainer} 覆盖
 * gtlcore 各总成 / gtladditions 超级样板总成 / gtceu 原生 / 我们的星律 ＋ 超级通配符 / AE2 自己的供给器。
 * <p>⚠️ <b>代理部件</b>（{@code MEPatternBufferProxyPartMachine}）不是 {@code PatternContainer}，
 * 取证 §2.1 第 10 行说它的 {@code getBuffer()} 是 public ⇒ 这里补一条分支把它指向的<b>真身</b>收进来
 * （gtceu 那个代理的 {@code getBuffer()} 是 private、拿不到 —— 那种情形会在报告里列成已知缺口）。
 *
 * <h2>二、🔴 读法是「逐槽 {@code getStackInSlot(i)}」，不是 {@code getAvailablePatterns()}</h2>
 * 取证 §2.2 已证死：gtlcore 那批会 {@code putIfAbsent} 去重、丢解析失败项、<b>不带槽号</b>；
 * 通配符机器给的是<b>展开结果</b>；星律给的是<b>神锻改写后的实例</b>（不是槽里那块 ItemStack）。
 * ⇒ 只有逐槽读才是"槽里真实的那一块"。见 {@link HostPatternMoverScan}。
 *
 * <h2>三、🔴 只读</h2>
 * 本类<b>没有任何</b> {@code setItemDirect} / {@code extractItem} / 清源调用。
 * 用户口径：读取<b>不</b>清源（要"剪切"语义得另行确认）。写回是第二刀的事。
 *
 * <h2>四、类型推断（A：通配符自动识别；多义不猜）</h2>
 * 每条样板先调 {@link PatternRecipeTypeHelper#readRecipeTypeId(ItemStack)} 读它<b>自己</b>的类型：
 * <ul>
 *   <li>{@code UNIQUE} ⇒ 记那个类型 id；</li>
 *   <li>{@code NONE}（一处都没标）/ {@code AMBIGUOUS}（≥2 处不一致）⇒ 记 {@code ""}
 *       ⇒ 面板归到「类型未识别」组。</li>
 * </ul>
 * 然后：<b>自身没类型的样板</b>再走一遍自动识别（用户 2026-11-01 原话的口径）：
 * <pre>
 *   老主机（原始终焉引擎一脉）⇒ 用【它所属模块】的配方类型（有效类型集）
 *   新主机（归墟视界枢核）    ⇒ 用【星律样板总成】里声明的类型（machineRecipeTypeIds）
 *   都识别不到                ⇒ 不写类型（type=""），放置时照旧跳过
 *   来源恰好给出 1 个 ⇒ 写；≥2 个 ⇒ 【不写】并计入「多义未写」（绝不取第一个）
 * </pre>
 * 🔴 <b>第五刀追加</b>（用户原话：「新增一个功能，若普通样板未填写配方类型，则尝试自动填写
 * （只有老主机上的样板才能这样做）」）：
 * <pre>
 *   普通样板（非通配符）＋ 自身没类型：
 *     老主机 ＋ 该模块有效类型【恰好 1 个】 ⇒ 补上那一个（第一行 {@code stampPlain=N}）
 *     老主机 ＋ 【≥2 个】                  ⇒ 不补（{@code stampPlainSkip=N}，多义不猜）
 *     归墟                                 ⇒ 一律不补
 * </pre>
 * 补出来的类型只落在<b>搬运物品</b>的 {@code type} 字段上；写进槽的那一下再由
 * {@link HostPatternMoverWildcard#typeToStampPlain} 决定要不要落进样板自己的 NBT。
 * 三项计数（写入 / 多义未写 / 识别不到）落进物品根上的 {@code shanhai:mover_read}
 * （见 {@link HostPatternMoverNbt#K_READ_STATS}）⇒ 面板随时重开都看得到。
 * 同时把<b>来源主机</b>记进物品 NBT 的 {@code fromHost}：
 * <pre>
 *   primordial_omega_engine  ← 原始终焉引擎（老主机）
 *   guixu_vision_core        ← 归墟视界枢核（新主机；它 extends 老主机，所以先判它）
 * </pre>
 *
 * <h2>四·五、🔴 第六刀 ＋ 第七刀：通配符槽的「编程电路」也一起读走</h2>
 * 用户原话：「等一下，通配符样板的编程电路没有复制和黏贴」；第七刀他的补充：
 * 「通配符还是没有电路，这可能是由于通配符的电路选择是我们那里比较特殊的选法导致的」。
 * 根因：电路<b>不是样板物品的一部分</b>，它住在机器上，而且是<b>两套不同的 API、两个不同的槽号域</b>：
 * <ul>
 *   <li><b>星律样板总成</b>：{@code RecipeTypePatternBufferPartMachine#shanhaiWildcardCircuit}
 *       （{@code byte[50]}，下标 = 母槽号；容器槽号 {@code 1080 + 母槽号}）；</li>
 *   <li><b>超级通配符总成</b>：{@code SuperWildcardPatternBufferPartMachine#shanhaiSlotCircuit}
 *       （{@code int[10]}，下标 = <b>它自己的槽号</b>；容器槽号 {@code i} 就是那个 {@code i}）。</li>
 * </ul>
 * ⇒ 两台都读，统一交给纯核 {@link HostPatternMoverCircuit} 判"这一格在不在电路区、索引是几"
 * （来源由 {@link HostPatternMoverTargets#circuitSourceOf} 按容器类型分派），
 * 记进条目的 {@code circuit} 键（非 0 才写键；0 = 不设置 ⇒ <b>不写</b>，
 * 所以老物品/普通槽<b>一个字节都不多</b>）。
 * <p>读取汇总行因此多一个 {@code circuits=<记下了几个非 0>} 字段，
 * 那一行还会带 {@code src=stellar=1|super=1}（见 {@link #describeCircuitKinds}）。
 *
 * <h2>五、留给第二刀的取证结论（这里只记，不实现）</h2>
 * <ul>
 *   <li><b>模块支持哪些配方类型</b>：{@code IRecipeLogicMachine#getRecipeTypes() → GTRecipeType[]}
 *       （javap：{@code IRecipeLogicMachine} 有 {@code getRecipeTypes/getRecipeType/getActiveRecipeType}）；
 *       注意一个模块可挂 2～5 个类型 ⇒ 匹配是「样板类型 ∈ 模块类型集合」，不是相等。</li>
 *   <li><b>写回</b>：{@code PatternContainer#getTerminalPatternInventory().setItemDirect(i, stack)}，
 *       上游自己会做 {@code onContentsChanged} + {@code onPatternChange}（不需要也不能我们自己调）。
 *       ⚠️ 它<b>不校验槽号、不校验 filter、不报错</b> ⇒ 必须自己判 {@code size()} 与
 *       {@code isItemValid}；且<b>星律那张合并视图的 {@code isItemValid} 恒 true</b>
 *       ⇒ 通配符槽要用我们自己的判据。</li>
 * </ul>
 */
public final class HostPatternMoverReader {

    /** 全包统一前缀（用户要求可 grep）。 */
    public static final String PREFIX = HostPatternMoverLog.PREFIX;

    /** 老主机（原始终焉引擎）的 fromHost 值。 */
    public static final String HOST_PRIMORDIAL = "primordial_omega_engine";

    /** 新主机（归墟视界枢核）的 fromHost 值。 */
    public static final String HOST_GUIXU = "guixu_vision_core";

    /** 模块递归的层数上限（模块套模块只是理论可能；防环）。 */
    private static final int MAX_MODULE_DEPTH = 3;

    /**
     * 一次读取的读数（= 用户要求的那一行日志的全部字段）。
     *
     * @param wildcardResolved     通配符样板自动识别后<b>写进类型</b>的块数（A）
     * @param wildcardAmbiguous    <b>多义未写</b>的块数（来源给出 ≥2 个类型 ⇒ 一个字都不写）
     * @param wildcardUnidentified 两个来源都没给出类型 ⇒ 不写的块数
     * @param stampPlain           第五刀：<b>普通样板</b>补上类型的块数（老主机 ＋ 模块有效类型恰好 1 个）
     * @param stampPlainSkip       第五刀：<b>普通样板</b>因多义（模块有效类型 ≥2）没补的块数
     * @param circuits             第六刀：从<b>星律通配符母槽</b>上记下来的<b>非 0</b>编程电路个数
     *                             （0 = 这一段没读到任何电路；普通槽 / 非星律总成恒不计数）
     */
    public record Summary(int assemblies, int slots, int nonEmpty, int added, int merged, int rejected,
                          int bytes, int limit, String fromHost,
                          int wildcardResolved, int wildcardAmbiguous, int wildcardUnidentified,
                          int stampPlain, int stampPlainSkip, int circuits) {

        /**
         * 用户指定的一行日志：
         * <pre>
         *   [SHANHAI-MOVER] read pos=… assemblies=N slots=T nonEmpty=K added=A dedup=D bytes=B/1572864
         * </pre>
         * 后半段（{@code | rejected=… fromHost=… wildcard… stampPlain=… stampPlainSkip=… circuits=…}）
         * 是附加读数，不影响前半段的固定形状。
         */
        public String logLine(BlockPos pos) {
            return PREFIX + " read pos=" + (pos == null ? "?" : pos.toShortString())
                    + " assemblies=" + this.assemblies
                    + " slots=" + this.slots
                    + " nonEmpty=" + this.nonEmpty
                    + " added=" + this.added
                    + " dedup=" + this.merged
                    + " bytes=" + this.bytes + "/" + this.limit
                    + " | rejected=" + this.rejected
                    + " fromHost=" + (this.fromHost.isEmpty() ? "-" : this.fromHost)
                    + " wildcardResolved=" + this.wildcardResolved
                    + " wildcardAmbiguous=" + this.wildcardAmbiguous
                    + " wildcardUnidentified=" + this.wildcardUnidentified
                    // 🔴 第五刀：普通样板补类型的两项计数（用户点名要的字段名与顺序）
                    + " stampPlain=" + this.stampPlain
                    + " stampPlainSkip=" + this.stampPlainSkip
                    // 🔴 第六刀：记下来的通配符母槽编程电路个数（0 = 这次没读到任何电路）
                    + " circuits=" + this.circuits;
        }
    }

    private HostPatternMoverReader() {}

    // ================================================================ 入口

    /**
     * 从 {@code clicked} 出发读取全部样板并入 {@code stack}（服务端调用）。
     *
     * @param clicked 被右键的机器（{@code null} ⇒ 什么也不做，返回 {@code null}）
     * @return 读数；{@code clicked == null} 或不是任何已知宿主 ⇒ {@code null}（调用方回 PASS）
     */
    public static Summary readIntoItem(ItemStack stack, MetaMachine clicked, BlockPos pos) {
        if (stack == null || stack.isEmpty() || clicked == null) {
            return null;
        }
        // ── ① 收集"样板总成"（落点 A/B 见类注释）
        final List<Assembly> assemblies = new ArrayList<>();
        final Set<BlockPos> seen = new HashSet<>();
        collect(clicked, assemblies, seen);
        if (assemblies.isEmpty()) {
            return null;
        }
        final String detectedHost = classifyHost(clicked);
        // ── ①b 先把「来源主机」定下来（识别不出就用物品里记着的那个）
        //     🔴 通配符的识别来源跟着<b>这个</b>走，不是跟着"这一次点到什么"走：
        //        同一件物品前后两次读的是同一台主机，来源口径不能来回跳。
        final CompoundTag mover = HostPatternMoverNbt.moverOf(stack.getTag());
        final String prevHost = HostPatternMoverNbt.readFromHost(mover);
        final String fromHost = detectedHost.isEmpty() ? prevHost : detectedHost;

        // ── ② 逐槽扫（只读）
        //     ★A：每台总成的视图都知道【它所属模块的有效类型集】与【星律样板总成声明的类型】
        //        ⇒ 读到"自身没类型"的通配符样板时当场自动识别（多义不猜）
        final Set<String> assemblerTypes = assemblerTypesOf(assemblies);
        final HostPatternMoverWildcard.Origin origin = originOf(fromHost);
        final List<InventoryView> views = new ArrayList<>(assemblies.size());
        for (Assembly a : assemblies) {
            views.add(new InventoryView(a.label(), a.container().getTerminalPatternInventory(),
                    HostPatternMoverTargets.effectiveRecipeTypesOf(a.owner()), origin, assemblerTypes,
                    // 第六刀 ＋ 第七刀：这台总成的「槽号 ⇒ 电路号」来源
                    //   （星律 / 超级通配符各一种；其它总成 = Source.NONE ⇒ 恒 0）
                    HostPatternMoverTargets.circuitSourceOf(a.container())));
        }
        final HostPatternMoverScan.Result<HostPatternMoverEntry> scan = HostPatternMoverScan.scan(views);
        final HostPatternMoverWildcard.Tally tally = new HostPatternMoverWildcard.Tally();
        final HostPatternMoverCircuit.Tally circuitTally = new HostPatternMoverCircuit.Tally();
        // 🆕 第七刀：这一批总成里各自用的是哪种电路来源（stellar / super）——「读侧一个都没抓到」
        //    这类故障必须能从日志一眼判出来（用户实机就是 cards=0 且不知道读的是哪台）。
        final Map<String, Integer> circuitKinds = new LinkedHashMap<>();
        for (InventoryView view : views) {
            tally.merge(view.tally());
            circuitTally.merge(view.circuitTally());
            final String kind = view.circuitSource().kind();
            if (!HostPatternMoverCircuit.KIND_NONE.equals(kind)) {
                circuitKinds.merge(kind, Integer.valueOf(1), Integer::sum);
            }
        }

        // ── ③ 并入物品 NBT（同键覆盖；超限拒绝并记账）
        final HostPatternMoverData<HostPatternMoverEntry> data = HostPatternMoverNbt.readData(
                mover, fromHost, HostPatternMoverData.LIMIT_BYTES);
        final HostPatternMoverData.BatchReport batch = data.addAll(scan.payloads());

        // ── ④ 写回物品（超限 ⇒ 不写；这里不该发生，因为闸就在同一口径上）
        if (!storeTo(stack, data.entries(), fromHost)) {
            HostPatternMoverLog.warn("{} 写回被拒（实算超限），本条读取不落盘 bytes={}/{}",
                    PREFIX, data.bytes(), data.limitBytes());
        }
        // ── ⑤ 把「通配符识别三项计数」记到物品根上（面板之后要显示；不进字节闸）
        try {
            HostPatternMoverNbt.writeStats(stack,
                    new HostPatternMoverNbt.ReadStats(tally.resolved(), tally.ambiguous(), tally.unidentified()));
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 记通配符识别计数失败 err={}", PREFIX, t.toString());
        }

        final Summary summary = new Summary(scan.assemblies(), scan.slots(), scan.nonEmpty(),
                batch.added(), batch.merged(), batch.rejected(), batch.bytesAfter(), batch.limitBytes(),
                fromHost, tally.resolved(), tally.ambiguous(), tally.unidentified(),
                tally.stampPlain(), tally.stampPlainSkip(), circuitTally.captured());
        HostPatternMoverLog.info("{}", summary.logLine(pos));
        if (circuitTally.captured() > 0 || !circuitKinds.isEmpty()) {
            // 第六刀：电路是"机器的状态"、不是样板的一部分 —— 记下多少块要能一眼判出来
            // 🆕 第七刀：再带上"这批总成用的是哪种电路来源"（stellar=1 / super=1）
            HostPatternMoverLog.info("{} read circuit {}{}", PREFIX, circuitTally.describe(),
                    describeCircuitKinds(circuitKinds));
        }
        if (tally.ambiguous() > 0) {
            // 用户点名「多义未写 N 块」要看得见：这一行是给 grep 的，面板里另有一行
            HostPatternMoverLog.warn("{} read wildcard_ambiguous count={} (type=\"\" 不猜，放置时跳过)",
                    PREFIX, tally.ambiguous());
        }
        if (tally.stampPlainSkip() > 0) {
            // 第五刀：普通样板因多义没补的，也单独给一行（汇总行里有字段，这一行便于当场判）
            HostPatternMoverLog.warn("{} read plain_type_ambiguous count={} (模块有效类型 ≥2 ⇒ 不补)",
                    PREFIX, tally.stampPlainSkip());
        }
        return summary;
    }

    /**
     * 星律样板总成的「声明的类型」并集（<b>新主机</b>的通配符来源）。
     *
     * <p>用现成 API {@link RecipeTypePatternBufferPartMachine#snapshotWildcardHostTypes(List, List)}：它只返回
     * 「四来源去重后恰好 1 个」的那些类型（未标注 / 歧义 / 通配符样板本身都不进集合）
     * ⇒ 正好符合「多义不猜」：多义的那些不会把候选撑大。
     */
    public static Set<String> assemblerTypesOf(List<Assembly> assemblies) {
        final Set<String> out = new LinkedHashSet<>();
        if (assemblies == null) {
            return out;
        }
        for (Assembly a : assemblies) {
            if (a == null || !(a.container() instanceof RecipeTypePatternBufferPartMachine stellar)) {
                continue;
            }
            try {
                final List<String> ids = new ArrayList<>();
                stellar.snapshotWildcardHostTypes(ids, new ArrayList<>());
                if (ids != null) {
                    for (String id : ids) {
                        if (id != null && !id.trim().isEmpty()) {
                            out.add(id.trim());
                        }
                    }
                }
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 读星律样板总成的类型失败 label={} err={}",
                        PREFIX, a.label(), t.toString());
            }
        }
        return out;
    }

    /** 来源主机标签 ⇒ 通配符识别的来源种类（{@code ""} = 认不出）。 */
    public static HostPatternMoverWildcard.Origin originOf(String fromHost) {
        if (HOST_GUIXU.equals(fromHost)) {
            return HostPatternMoverWildcard.Origin.NEW_HOST;
        }
        if (HOST_PRIMORDIAL.equals(fromHost)) {
            return HostPatternMoverWildcard.Origin.OLD_HOST;
        }
        return HostPatternMoverWildcard.Origin.UNKNOWN;
    }

    /**
     * 这批总成各自用的是<b>哪一种</b>电路来源 ⇒ 日志片段（{@code ""} = 一台都没有电路 API）。
     *
     * <pre>
     *   {}                      ⇒ ""
     *   {stellar=1}             ⇒ " src=stellar=1"
     *   {stellar=1, super=2}    ⇒ " src=stellar=1,super=2"
     * </pre>
     *
     * <p>为什么要这一句：用户实机报「通配符还是没有电路」时，日志只有
     * {@code circuits=0}，<b>分不清</b>"没读到"和"他设电路的那台压根没被识别成电路来源"。
     * 有了它，读取汇总那一行会写成 {@code read circuit captured=0 … src=super=1}
     * （= 认到一台超级通配符总成、但它上面一个非 0 电路都没有）或 {@code … src=stellar=1}
     * （= 他只在一台上设过，另一台没设）。纯字符串、可离线断言。
     */
    public static String describeCircuitKinds(Map<String, Integer> kinds) {
        if (kinds == null || kinds.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(" src=");
        final int base = sb.length();
        for (Map.Entry<String, Integer> e : kinds.entrySet()) {
            if (sb.length() > base) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 把条目写进物品 NBT（{@code null} = 超限，<b>物品一个字节都不改</b>）。
     *
     * <p>⚠️ 只在这里 {@code getOrCreateTag()}（服务端写路径）；读路径一律 {@code contains} + {@code get}。
     *
     * <h2>🔴 第五刀 ①：这里是<b>唯一</b>写 {@code shanhai:mover} 的地方 ⇒ 修订号也在这里 ＋1</h2>
     * 写入成功之后调 {@link HostPatternMoverNbt#bumpRevTag}（物品 tag 根上的 {@code shanhai:mover_rev}）。
     * 这样「数据变了 ⇒ 版本必变」是<b>结构性</b>的：取出 / 清空物品 / 放置写回 / 读取并入
     * 四条路都从这里过，谁也不会漏掉一次版本。版本用来让客户端那条周期镜像
     * <b>不许</b>拿"还没同步的旧 NBT"盖掉服务端刚推的新状态（见 {@link HostPatternMoverPanelState#afterMirror}）。
     */
    public static boolean storeTo(ItemStack stack, List<HostPatternMoverEntry> entries, String fromHost) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final CompoundTag tag = HostPatternMoverNbt.toTag(entries, fromHost, HostPatternMoverData.LIMIT_BYTES);
        if (tag == null) {
            return false;
        }
        final CompoundTag root = stack.getOrCreateTag();
        root.put(HostPatternMoverNbt.ROOT_KEY, tag);
        // 版本戳在【根】上，不动 shanhai:mover 的字节口径（见 K_REV 的注释）
        HostPatternMoverNbt.bumpRevTag(root);
        return true;
    }

    // ================================================================ 收集总成

    /**
     * 一台样板总成：给人看的标号 ＋ 它的槽容器 ＋ <b>它的"东家"</b>（控制器或模块）。
     *
     * <p>🔴 {@code owner} 是第二刀加的（第一刀只用了前两项）：放置的<b>分配判据</b>是
     * 「样板类型 ∈ 东家模块的 {@code IRecipeLogicMachine#getRecipeTypes()}」，
     * 所以每台总成必须记住它是从哪台控制器／模块下面收上来的。
     * <p>它<b>只影响放置</b>，第一刀的读取路径一个字没改（读取仍然只看 {@code container}）。
     */
    record Assembly(String label, PatternContainer container, MetaMachine owner) {}

    /** 从任意机器出发收集（落点 A：{@code getParts()} 筛 {@code PatternContainer}；落点 B：{@code getModuleSet()}）。 */
    static void collect(MetaMachine clicked, List<Assembly> out, Set<BlockPos> seen) {
        // ① 点到的自己就是总成（例如 AE2 样板供应器，或直接点到某台样板仓）
        addAssembly(out, seen, clicked, clicked);
        // ② 点到的是一台 part ⇒ 收它的 controller
        if (clicked instanceof IMultiPart part) {
            try {
                for (IMultiController controller : part.getControllers()) {
                    collectController(controller, out, seen, 0);
                }
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 取 part 的 controller 失败 pos={} err={}",
                        PREFIX, clicked.getPos(), t.toString());
            }
        }
        // ③ 点到的是一台 controller（主机 / 模块）
        if (clicked instanceof IMultiController controller) {
            collectController(controller, out, seen, 0);
        }
    }

    /**
     * ★落点 A：{@code controller.getParts()} 里筛 {@link PatternContainer}；
     * ★落点 B：模块不是 part ⇒ {@code IModularMachineHost#getModuleSet()}，再当 controller 递归。
     *
     * <p>每台收上来的总成都会带上 {@code owner = 那台控制器自己}（= 第二刀的分配判据来源）。
     */
    private static void collectController(IMultiController controller, List<Assembly> out, Set<BlockPos> seen,
                                          int depth) {
        if (controller == null || depth > MAX_MODULE_DEPTH) {
            return;
        }
        // controller 自己也可能就是一台总成（"控制器本身直接实现 PatternContainer"在别的机型上真实存在）
        addAssembly(out, seen, controller.self(), controller.self());
        try {
            for (IMultiPart part : controller.getParts()) {
                if (part == null) {
                    continue;
                }
                final MetaMachine pm = part.self();
                if (pm == null) {
                    continue;
                }
                // ★落点 A：判据是接口，不写死类名
                if (pm instanceof PatternContainer) {
                    addAssembly(out, seen, pm, controller.self());
                    continue;
                }
                // 代理部件不是 PatternContainer；gtlcore 那个的 getBuffer() 是 public（取证 §2.1 第 10 行）
                if (pm instanceof MEPatternBufferProxyPartMachine proxy) {
                    try {
                        // getBuffer() 的返回类型本身就是 MetaMachine ⇒ 模式匹配是无条件的，
                        // -source 17 不允许（"unconditional patterns in instanceof"）⇒ 直接取值判空。
                        MetaMachine real = proxy.getBuffer();
                        if (real != null) {
                            addAssembly(out, seen, real, controller.self());
                        }
                    } catch (Throwable ignored) {
                        // 未绑定的代理 / 上游改签名 ⇒ 跳过，不影响其它总成
                    }
                }
            }
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 取 getParts 失败 controller={} err={}",
                    PREFIX, controller.self() == null ? "?" : controller.self().getPos(), t.toString());
        }
        // ★落点 B：模块不是 part
        if (controller instanceof IModularMachineHost<?> host) {
            try {
                for (IModularMachineModule<?, ?> module : host.getModuleSet()) {
                    if (module instanceof IMultiController moduleController) {
                        // 模块本身就是一台多方块 ⇒ 当 controller 递归（它自己的 getParts 里有它的总成）
                        collectController(moduleController, out, seen, depth + 1);
                    } else if (module instanceof MetaMachine moduleMachine) {
                        addAssembly(out, seen, moduleMachine, moduleMachine);
                    }
                }
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 遍历模块失败 err={}", PREFIX, t.toString());
            }
        }
    }

    /** 收一台总成（按坐标去重：代理 + 真身同时在 parts 里时只算一次）。 */
    private static void addAssembly(List<Assembly> out, Set<BlockPos> seen, MetaMachine machine, MetaMachine owner) {
        if (machine == null || !(machine instanceof PatternContainer container)) {
            return;
        }
        final BlockPos pos = machine.getPos();
        if (pos != null && !seen.add(pos)) {
            return;
        }
        for (Assembly a : out) {
            if (a.container() == container) {
                return; // 对象身份去重：坐标拿不到时兜底
            }
        }
        out.add(new Assembly(pos == null ? "?" : pos.toShortString(), container,
                owner == null ? machine : owner));
    }

    // ================================================================ 台账

    /**
     * 来源主机标签（第二刀的分配线索）。
     *
     * <p>🔴 归墟<b>先判</b>：{@code GuixuVisionCoreMachine extends PrimordialOmegaEngineMachine}
     * ⇒ 反过来判会把新主机认成老主机。
     * <p>点到的若是<b>模块</b>，从 {@code IModularMachineModule#getHost()} 反查它的主机。
     */
    static String classifyHost(MetaMachine clicked) {
        if (clicked instanceof GuixuVisionCoreMachine) {
            return HOST_GUIXU;
        }
        if (clicked instanceof PrimordialOmegaEngineMachine) {
            return HOST_PRIMORDIAL;
        }
        if (clicked instanceof IModularMachineModule<?, ?> module) {
            try {
                final Object host = module.getHost();
                if (host instanceof GuixuVisionCoreMachine) {
                    return HOST_GUIXU;
                }
                if (host instanceof PrimordialOmegaEngineMachine) {
                    return HOST_PRIMORDIAL;
                }
            } catch (Throwable ignored) {
                // 未连主机 ⇒ 没有来源标签
            }
        }
        return "";
    }

    // ================================================================ 槽视图（生产实现）

    /**
     * 一台总成的<b>只读</b>槽视图：包住 {@code InternalInventory}，只暴露
     * {@code size()} 与 {@code getStackInSlot(i)}。
     *
     * <p>🔴 这个类<b>没有</b>写方法（{@link HostPatternMoverScan.SlotView} 上就没有），
     * 所以"只读"是结构性的；{@code getStackInSlot} 返回的活对象也<b>不会</b>被改
     * （{@code serializeNBT()} 天生返回新标签，且内部 {@code tag} 也拷了一份）。
     *
     * <h2>🆕 A：通配符样板的自动识别就发生在这里的 {@link #view(int)}</h2>
     * 每读到一块<b>自身没类型</b>的样板，就按 {@link HostPatternMoverWildcard} 的规则取类型：
     * <ul>
     *   <li><b>通配符样板</b>（第三刀）：来源是「它所属模块的有效类型集」或「星律样板总成声明的类型」，
     *       取<b>恰好 1 个</b>写进去；一个都取不到 ⇒ 不写；≥2 个 ⇒ <b>多义不写</b>并记一笔
     *       （{@link #tally()}）；</li>
     *   <li><b>普通样板</b>（第五刀）：只有老主机来源才补，判据同样是"恰好 1 个才写"，
     *       ≥2 个 ⇒ 不补并记一笔（{@code stampPlainSkip}）。</li>
     * </ul>
     *
     * <h2>🆕 第六刀（第七刀扩到两台）：编程电路也在这里读（它是<b>机器</b>的状态，不在样板物品里）</h2>
     * 来源槽落在<b>有电路 API 的通配符槽</b>上（星律 {@code 1080..1129}；超级通配符 {@code 0..9}）
     * ⇒ 顺手读那台的电路并记进条目的 {@code circuit} 键（非 0 才写键）。
     * 判据全部在纯核 {@link HostPatternMoverCircuit#pick} 里（{@link #circuitSource()} 只是转交，
     * 来源由 {@link HostPatternMoverTargets#circuitSourceOf} 按容器类型分派），
     * 所以「普通槽 ⇒ 一个字节都不多」这条可以被离线断言真的打中。
     */
    static final class InventoryView implements HostPatternMoverScan.SlotView<HostPatternMoverEntry> {

        private final String label;
        private final InternalInventory inventory;
        private final Set<String> moduleTypes;
        private final HostPatternMoverWildcard.Origin origin;
        private final Set<String> assemblerTypes;
        private final HostPatternMoverCircuit.Source circuitSource;
        private final HostPatternMoverWildcard.Tally tally = new HostPatternMoverWildcard.Tally();
        private final HostPatternMoverCircuit.Tally circuitTally = new HostPatternMoverCircuit.Tally();

        InventoryView(String label, InternalInventory inventory, Set<String> moduleTypes,
                      HostPatternMoverWildcard.Origin origin, Set<String> assemblerTypes,
                      HostPatternMoverCircuit.Source circuitSource) {
            this.label = label;
            this.inventory = inventory;
            this.moduleTypes = moduleTypes == null ? Set.of() : moduleTypes;
            this.origin = origin == null ? HostPatternMoverWildcard.Origin.UNKNOWN : origin;
            this.assemblerTypes = assemblerTypes == null ? Set.of() : assemblerTypes;
            this.circuitSource = circuitSource == null
                    ? HostPatternMoverCircuit.Source.NONE : circuitSource;
        }

        @Override
        public String label() {
            return this.label;
        }

        @Override
        public int size() {
            return this.inventory.size();
        }

        /** 本台总成上「通配符自动识别」的三项计数（读完由外面合并）。 */
        HostPatternMoverWildcard.Tally tally() {
            return this.tally;
        }

        /** 本台总成上「记下了几个非 0 电路」的计数（读完由外面合并）。 */
        HostPatternMoverCircuit.Tally circuitTally() {
            return this.circuitTally;
        }

        /** 这台总成的「槽号 ⇒ 电路号」来源（没有电路 API 的总成 = {@link HostPatternMoverCircuit.Source#NONE}）。 */
        HostPatternMoverCircuit.Source circuitSource() {
            return this.circuitSource;
        }

        @Override
        public HostPatternMoverEntry view(int slot) {
            final ItemStack stack = this.inventory.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            final String ownType = typeOf(stack);
            final boolean wildcard = isWildcard(stack);
            final HostPatternMoverWildcard.Resolution resolution = HostPatternMoverWildcard.resolve(
                    wildcard, ownType, this.origin, this.moduleTypes, this.assemblerTypes);
            this.tally.add(resolution);
            // 第六刀：母槽上的编程电路（非母槽 / 非星律 ⇒ 恒 0 ⇒ 条目里不会出现 circuit 键）
            final int circuit = this.circuitSource.circuitAt(slot);
            if (circuit != HostPatternMoverCircuit.NONE) {
                this.circuitTally.addCaptured();
            }
            return HostPatternMoverEntry.of(stack.serializeNBT(), resolution.type(), circuit);
        }
    }

    /** 与 master 母槽同口径：精选配方样板或外部通配符样板；不依赖可选 mod 存在。 */
    static boolean isWildcard(ItemStack stack) {
        try {
            return SelectedRecipePattern.isPattern(stack) || WildcardPatternBridge.isWildcardPattern(stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 样板自己的配方类型（{@code ""} = 未识别 / 歧义）。
     *
     * <p>走的是本工程<b>已有</b>的读取层 {@link PatternRecipeTypeHelper#readRecipeTypeId(ItemStack)}
     * （四个来源：gtlcore 快速上传元数据 / {@code gt_shanhai_recipe_type} /
     * {@code gt_shanhai_recipe_id} / 权威声明；去重后只剩 1 个才采信，永不抛）。
     */
    static String typeOf(ItemStack stack) {
        try {
            return PatternRecipeTypeHelper.readRecipeTypeId(stack);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Shift+右键宿主时给玩家的一句话（服务端调 {@code displayClientMessage} 用）。 */
    public static net.minecraft.network.chat.Component feedback(Summary s) {
        if (s == null) {
            return net.minecraft.network.chat.Component.translatable("shanhai.mover.not_host");
        }
        if (s.rejected() > 0) {
            return net.minecraft.network.chat.Component.translatable("shanhai.mover.read_full",
                    s.added(), s.merged(), s.rejected(), s.bytes(), s.limit());
        }
        return net.minecraft.network.chat.Component.translatable("shanhai.mover.read",
                s.assemblies(), s.added(), s.merged(), s.bytes(), s.limit());
    }

    /**
     * 通配符自动识别的<b>第二行</b>聊天栏提示（三项计数非零才给，否则 {@code null}）。
     *
     * <p>为什么不塞进 {@link #feedback} 那一句：那两句 lang 的参数表已经定死（第一刀验收过），
     * 加参数要动已验收的字串；「多义未写」是新增的读数，单独一行更清楚。
     */
    public static net.minecraft.network.chat.Component wildcardFeedback(Summary s) {
        if (s == null || (s.wildcardResolved() == 0 && s.wildcardAmbiguous() == 0
                && s.wildcardUnidentified() == 0)) {
            return null;
        }
        return net.minecraft.network.chat.Component.translatable("shanhai.mover.read_wildcard",
                s.wildcardResolved(), s.wildcardAmbiguous(), s.wildcardUnidentified());
    }

    /** 供物品侧判断"要不要吃掉这次右键"（两侧必须同判，见 {@code HostPatternMoverItem}）。 */
    public static boolean looksLikeTarget(MetaMachine machine) {
        if (machine == null) {
            return false;
        }
        if (machine instanceof PatternContainer || machine instanceof IMultiController
                || machine instanceof IMultiPart) {
            return true;
        }
        return machine instanceof IModularMachineModule<?, ?>;
    }

    /**
     * 「右键放置」这一刀的<b>入口判据</b>：这一下点的到底是不是<b>主机</b>
     * （老主机 {@code primordial_omega_engine} / 新主机 {@code guixu_vision_core}，或它们的一个模块）。
     *
     * <h2>🔴 为什么不复用 {@link #looksLikeTarget}</h2>
     * {@code looksLikeTarget} 是<b>读</b>的判据，宽到「任何 part / 任何 controller」——
     * 因为读取是只读的，多认几台不伤人。但<b>放置</b>不一样：一旦认了，
     * 这次右键就被 Item 吃掉了（方块自己的 GUI 打不开）。
     * 若照抄那个宽判据，玩家手里拿着有样板的搬运工具时，<b>右键任何 GT 机器的一个仓室都不会开 GUI</b>
     * （本包 89 个 jar、几十种仓室）—— 那是个比"少支持一种机型"严重得多的回归。
     * <p>所以这里只认<b>真正的主机</b>：{@link PrimordialOmegaEngineMachine} 的实例，
     * 或者一个 {@code getHost()} 指回它的模块。两侧都算得出来（不依赖遍历总成）。
     */
    public static boolean looksLikePlaceHost(MetaMachine machine) {
        if (machine == null) {
            return false;
        }
        if (machine instanceof PrimordialOmegaEngineMachine) {
            return true;
        }
        if (machine instanceof IModularMachineModule<?, ?> module) {
            try {
                return module.getHost() instanceof PrimordialOmegaEngineMachine;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}
