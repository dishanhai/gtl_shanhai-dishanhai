package com.shanhai.common.patternmover;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternContainer;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.machine.stellar.StellarSampleAssemblerPartMachine;
import com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt;
import com.shanhai.machine.wildcard.ShanhaiWildcardCompat;
import com.shanhai.machine.wildcard.SuperWildcardPatternBufferPartMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第三刀（放置）的<b>机器侧</b>：把「被右键的那台主机」变成一张
 * 「目标总成表」（每台总成带<b>它所属模块支持的配方类型</b>），并把它包成
 * {@link HostPatternMoverPlacer.Target}。
 *
 * <h2>一、遍历与第一刀完全同源（这是刻意的）</h2>
 * 复用 {@link HostPatternMoverReader#collect}：{@code getParts()} 里
 * {@code instanceof PatternContainer} ＋ {@code IModularMachineHost#getModuleSet()} 递归 ＋
 * 代理走 {@code getBuffer()}。第一刀读取用的就是这一份，所以「读得到什么」与「写得进什么」
 * <b>一定是同一批总成</b>（不会出现"读得进、写不进"或者反过来的分叉）。
 *
 * <h2>二、🔴 分配判据 = 「<b>有效</b>类型集」，不是 {@code getRecipeTypes()} 的全部</h2>
 * 用户实测（2026-11-08）：「在没有跨配方种类并行的老主机上使用时，它把模块没有选择的配方类型的配方
 * 也放进去了」⇒ 老主机上的模块同一时刻<b>只跑一个类型</b>（{@code getRecipeType()}），
 * 声明里其余的那些放进去也用不上。所以每台总成记的是
 * {@link HostPatternMoverTargets#effectiveRecipeTypesOf}：
 * <pre>
 *   宿主 grantsCrossTypeParallel()（目前只有归墟）⇒ 声明的全部 getRecipeTypes()
 *   否则（老主机）                                ⇒ 只有当前选中的那一个 getRecipeType()
 * </pre>
 * 类型不在这个集合里 ⇒ {@code SKIP_NO_MODULE}（面板显示成「无对应模块 / 当前类型不符」）。
 * <p>注意判据用 {@code registryName}（{@code GTRecipeType} 的 public final 字段），不用
 * {@code toString()} —— 后者在别的模组覆写过的类型上不保证是 id。
 *
 * <h2>三、🔴 星律样板总成的分区（用户点名的那条）</h2>
 * {@link StellarSampleAssemblerPartMachine#getTerminalPatternInventory()} 返回的是
 * 「1080 普通 ＋ 50 通配符」的<b>合并视图</b>，而那个视图的 {@code isItemValid} 是
 * {@code InternalInventory} 的 <b>default（恒 true）</b>（它只覆写了
 * {@code size/getStackInSlot/setItemDirect}，见 {@code StellarSampleAssemblerPartMachine.java:4597-4634}）
 * ⇒ 光问 {@code isItemValid} 会把通配符样板写进普通槽（也会反过来）。
 * 所以本类对它<b>声明分区</b>：普通槽 {@code 0..1079}、通配符槽 {@code 1080..1129}
 * （常量取 {@code PATTERN_SLOT_COUNT} / {@code WILDCARD_SLOT_COUNT}，<b>不写字面量</b>），
 * 分区判据由 {@link HostPatternMoverPlacer#slotOk} 执行 —— 它<b>在</b> {@code isItemValid} <b>之前</b>。
 * <p>「判据用 {@link ShanhaiWildcardCompat#isWildcardPattern}，拿不到就按槽号区间硬判」：
 * 这里拿得到（{@code ShanhaiWildcardCompat} 是本仓库自己的类），所以<b>用真判据</b>；
 * 缺 {@code wildcard_pattern} 那个 mod 时它恒 false ⇒ 通配符样板会被当成普通样板
 * ⇒ 那时 <b>槽号区间仍会把它们挡在 0..1079 之外</b>（因为普通槽数 = 1080 正好等于区间上界）。
 *
 * <h2>四、写回：只调 {@code setItemDirect}，<b>不</b>自己发同步包、也<b>不</b>再补 onPatternChange</h2>
 * 上游 {@code MEPatternBufferPartMachine.internalPatternInventory#setItemDirect} 的三句是
 * {@code setStackInSlot → onContentsChanged → onPatternChange}（反编译
 * {@code temp/pattern-mover/decomp/org/gtlcore/.../MEPatternBufferPartMachine.java:122-126}），
 * 星律那台合并视图对 ≥1080 的槽也会自己调 {@code onPatternChange(slot)}（同文件 4617-4629）。
 * ⇒ 「写入之后必须触发一次缓存失效 ＋ AE 同步」这件事<b>由上游那一次完成</b>，
 * 我们再补一次就是<b>两次</b>（缓存失效做两遍、AE 重推两次）。这一点写在交付报告里。
 */
public final class HostPatternMoverTargets {

    /** 一批目标总成的编译结果：{@code targets} 与 {@code infos} <b>等长同序</b>（建不出来的那一台两边都不出现）。 */
    public record Compiled(List<HostPatternMoverPlacer.Target> targets, List<AssemblyInfo> infos) {

        public static final Compiled EMPTY = new Compiled(List.of(), List.of());

        public int count() {
            return this.targets.size();
        }
    }

    private HostPatternMoverTargets() {}

    // ================================================================ 批量写入（第四刀 ①）

    /**
     * <b>搬运工具写槽的【唯一入口】</b>：在一层「批量写入」里跑完 {@code body}，无论怎么退出都收尾。
     *
     * <pre>
     *   HostPatternMoverTargets.inBatchWrite(() -> HostPatternMoverPlacer.apply(plan, units, targets));
     * </pre>
     *
     * <h2>为什么必须包在这里（而不是逐格包）</h2>
     * 星律样板总成<b>每写一格</b>就会整表重建一次（{@code setItemDirect → onPatternChange → shanhaiRebuildPatternTables}）。
     * 放置 1168 格 = 1168 次重建（实测单 tick 40 秒，见 {@link HostPatternMoverBatch} 的类注释）。
     * 批量模式把这段区间内的重建请求全部并成收尾的<b>一次</b>
     * （机器侧判据行：{@code [SHANHAI-STELLAR] PATREBUILD_BATCH writes=1168 rebuilds=1}）。
     *
     * <h2>🔴 异常安全</h2>
     * {@code finally} 里一定调 {@code shanhaiEndBatchWrite()} ⇒ 中途抛异常也<b>一定</b>会：
     * ① 把批量深度归零（不会永久卡在"只置脏不重建"那种状态）；② 该建的那一次照建。
     * 批量的可重入/异常语义在纯核 {@link HostPatternMoverBatch} 里（自检 C25 组跑的就是它）。
     *
     * <h2>🔴 只影响写槽这一段</h2>
     * 批量标志只在这一段里为真；编辑器 / AE / 玩家手动放 / 读档那些路径<b>根本不会</b>看到它
     * （{@code HostPatternMoverBatch.suppress} 在批量外直接返回 false）⇒ 行为逐位不变。
     */
    public static <T> T inBatchWrite(java.util.function.Supplier<T> body) {
        StellarSampleAssemblerPartMachine.shanhaiBeginBatchWrite();
        try {
            return body == null ? null : body.get();
        } finally {
            StellarSampleAssemblerPartMachine.shanhaiEndBatchWrite();
        }
    }

    // ================================================================ 收集

    /**
     * 从被右键的机器出发，收集**目标总成**（含各自的支持类型）。
     *
     * @return 永不为 {@code null}；没有总成 ⇒ 空表
     */
    public static List<HostPatternMoverReader.Assembly> collect(MetaMachine clicked) {
        final List<HostPatternMoverReader.Assembly> out = new ArrayList<>();
        if (clicked == null) {
            return out;
        }
        final Set<BlockPos> seen = new HashSet<>();
        HostPatternMoverReader.collect(clicked, out, seen);
        return out;
    }

    /**
     * 把一批总成包成 placer 能吃的目标（每台一个 {@link InventoryTarget}）＋ 面板摘要。
     *
     * <p>单台建不出来（容器坏了 / 机器被挖）⇒ <b>跳过那一台</b>并 WARN，其余照常。
     * 返回的两张表<b>等长同序</b>（{@link Compiled}）。
     */
    public static Compiled compile(List<HostPatternMoverReader.Assembly> assemblies) {
        final List<HostPatternMoverPlacer.Target> targets = new ArrayList<>();
        final List<AssemblyInfo> infos = new ArrayList<>();
        if (assemblies == null) {
            return Compiled.EMPTY;
        }
        for (HostPatternMoverReader.Assembly a : assemblies) {
            if (a == null) {
                continue;
            }
            final InventoryTarget target;
            try {
                target = new InventoryTarget(a);
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 目标总成不可用（跳过）label={} err={}",
                        HostPatternMoverLog.PREFIX, a.label(), t.toString());
                continue;
            }
            targets.add(target);
            int free = 0;
            for (int slot = 0; slot < target.size(); slot++) {
                if (target.keyAt(slot) == null) {
                    free++;
                }
            }
            infos.add(new AssemblyInfo(target.label(), target.moduleId(), target.size(),
                    target.zoned() ? target.normalSlots() : -1,
                    target.zoned() ? target.wildcardSlots() : -1, free));
        }
        return new Compiled(targets, infos);
    }

    /** 把物品里的条目变成 placer 能吃的「块」（含 ItemStack 与通配符标志）。 */
    public static List<HostPatternMoverPlacer.Unit> units(List<HostPatternMoverEntry> entries) {
        final List<HostPatternMoverPlacer.Unit> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (HostPatternMoverEntry e : entries) {
            out.add(new Unit(e, stackOf(e)));
        }
        return out;
    }

    // ================================================================ 分区 / 电路（第六刀）

    /**
     * 这台总成有没有「普通槽 / 通配符母槽」分区（<b>只认星律样板总成</b>）。
     *
     * <p>为什么不能问 {@code isItemValid}：星律那张合并视图的 {@code isItemValid} 是
     * {@code InternalInventory} 的 default（恒 true），见类注释 §三。
     */
    public static boolean zonedContainer(PatternContainer container) {
        return container instanceof StellarSampleAssemblerPartMachine;
    }

    /** 普通槽数（= 通配符区在合并视图里的<b>起点</b>）；不分区 ⇒ {@code -1}。 */
    public static int normalSlotsOf(PatternContainer container) {
        return zonedContainer(container) ? StellarSampleAssemblerPartMachine.PATTERN_SLOT_COUNT : -1;
    }

    /** 通配符母槽数；不分区 ⇒ {@code -1}。 */
    public static int wildcardSlotsOf(PatternContainer container) {
        return zonedContainer(container) ? StellarSampleAssemblerPartMachine.WILDCARD_SLOT_COUNT : -1;
    }

    /**
     * 这台总成的「槽号 ⇒ <b>编程电路</b>」来源（第六刀 ＋ 第七刀）。
     *
     * <p>🔴 <b>按容器类型分派两种来源</b>（判定只有一套：槽号 ⇒ 电路索引走纯核
     * {@link HostPatternMoverCircuit#motherSlotOf}，两种来源只是"索引起点"不同）：
     * <pre>
     *   StellarSampleAssemblerPartMachine      起点 1080、50 格 ⇒ 槽 1080+i ⇔ 母槽 i
     *                                          shanhaiWildcardCircuit / shanhaiApplyWildcardCircuit
     *   SuperWildcardPatternBufferPartMachine  起点 0、   10 格 ⇒ 槽 i      ⇔ 它自己的槽 i
     *                                          shanhaiSlotCircuit    / shanhaiApplySlotCircuit
     *   其它（gtlcore 普通总成 / 认不出的）    {@link HostPatternMoverCircuit.Source#NONE}
     * </pre>
     * <p>🔴 两套 API 的槽号域<b>不是</b>同一个东西，这是本刀的核心结论（用户反馈「通配符还是没有电路，
     * 这可能是由于通配符的电路选择是我们那里比较特殊的选法导致的」—— 他设电路的那台是超级通配符总成）：
     * 星律按<b>母槽号</b>（{@code slot - 1080}），超级通配符按<b>它自己的槽号</b>（{@code 0..9}，
     * 与 {@code getTerminalPatternInventory()} 的槽号 1:1 —— 依据见
     * {@link HostPatternMoverCircuit.Source#selfIndexed}）。
     * <p>⚠️ 超级通配符<b>不算</b> {@link #zonedContainer} 的"分区"：那台的 10 格<b>全是</b>通配符槽、
     * 而且 {@code isItemValid} 本来就只收通配符样板 ⇒ 它不需要"普通槽/通配符槽"这道闸。
     * 「分区」（{@link #zonedContainer}）与「电路来源」（本方法）是<b>两件事</b>，别混。
     * <p>⚠️ {@code shanhaiWildcardCircuit(int)} / {@code shanhaiSlotCircuit(int)} 自己都会挡越界
     * （返回 {@code NONE}），不会抛。
     */
    public static HostPatternMoverCircuit.Source circuitSourceOf(PatternContainer container) {
        if (container instanceof StellarSampleAssemblerPartMachine stellar) {
            // 星律：索引 = 母槽号（1080 起的那 50 格）
            return HostPatternMoverCircuit.Source.mothers(HostPatternMoverCircuit.KIND_STELLAR,
                    StellarSampleAssemblerPartMachine.PATTERN_SLOT_COUNT,
                    StellarSampleAssemblerPartMachine.WILDCARD_SLOT_COUNT,
                    stellar::shanhaiWildcardCircuit,
                    // 机器侧那个 API 是 void（内部 isRemote()/越界/同值都会静默早退）⇒ 按"没抛就是接受"
                    (index, circuit) -> {
                        stellar.shanhaiApplyWildcardCircuit(index, circuit);
                        return true;
                    });
        }
        if (container instanceof SuperWildcardPatternBufferPartMachine superWildcard) {
            // 超级通配符：索引 = 它自己的槽号（0 起的那 10 格，无偏移）
            return HostPatternMoverCircuit.Source.selfIndexed(HostPatternMoverCircuit.KIND_SUPER_WILDCARD,
                    SuperWildcardPatternBufferPartMachine.WILDCARD_SLOT_COUNT,
                    superWildcard::shanhaiSlotCircuit,
                    // 它返回 boolean：false = 值没变 / 该格没有通配符样板 / 客户端侧（由调用方记一行）
                    superWildcard::shanhaiApplySlotCircuit);
        }
        return HostPatternMoverCircuit.Source.NONE;
    }

    /**
     * 一格现有样板的去重键（空槽 / 读不出来 ⇒ {@code null}）。
     *
     * <p>放在外层而不是 {@link InventoryTarget} 里面：{@link #fingerprint} 也要用它，
     * 而 {@code fingerprint} 是静态方法（面板/动作两侧都要能调，且不需要先建适配器）。
     */
    static String keyOfSlot(InternalInventory inv, int slot) {
        if (inv == null) {
            return null;
        }
        final ItemStack stack;
        try {
            stack = inv.getStackInSlot(slot);
        } catch (Throwable t) {
            return null;
        }
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            return HostPatternMoverEntry.keyOf(stack.serializeNBT());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 条目 → ItemStack（造不出来 ⇒ {@code ItemStack.EMPTY}，调用方按"放不进"处理）。 */
    static ItemStack stackOf(HostPatternMoverEntry entry) {        if (entry == null) {
            return ItemStack.EMPTY;
        }
        try {
            final CompoundTag tag = entry.patternNbt();
            if (tag == null || tag.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return ItemStack.of(tag);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    // ================================================================ 支持类型

    /**
     * 一台机器<b>声明</b>的全部配方类型（{@code registryName} 字符串）。
     *
     * <p>⚠️ 这<b>不是</b>「支持」的判据（见 {@link #effectiveRecipeTypesOf}）：老主机上的模块
     * 声明 5 个类型、同一时刻只跑 1 个。本方法只作为 {@code effective} 的入参，别处不要再直接用它判。
     */
    public static Set<String> declaredRecipeTypesOf(MetaMachine machine) {
        final Set<String> out = new LinkedHashSet<>();
        if (!(machine instanceof IRecipeLogicMachine rlm)) {
            return out;
        }
        try {
            final GTRecipeType[] types = rlm.getRecipeTypes();
            if (types == null) {
                return out;
            }
            for (GTRecipeType type : types) {
                if (type == null || type.registryName == null) {
                    continue;
                }
                out.add(type.registryName.toString());
            }
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 读 getRecipeTypes 失败 machine={} err={}",
                    HostPatternMoverLog.PREFIX, machine.getPos(), t.toString());
        }
        return out;
    }

    /**
     * 一台机器<b>当前选中</b>的配方类型（{@code IRecipeLogicMachine#getRecipeType()}）；读不到 ⇒ {@code ""}。
     *
     * <p>这就是老主机上「模块同一时刻只跑一个类型」的那<b>一个</b>。
     */
    public static String activeRecipeTypeOf(MetaMachine machine) {
        if (!(machine instanceof IRecipeLogicMachine rlm)) {
            return "";
        }
        try {
            final GTRecipeType type = rlm.getRecipeType();
            return type == null || type.registryName == null ? "" : type.registryName.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 这台机器（模块 / 主机）的宿主是否<b>授予跨配方种类并行</b>。
     *
     * <p>{@code PrimordialOmegaEngineMachine#grantsCrossTypeParallel()} 默认 {@code false}，
     * 归墟视界枢核覆写为 {@code true}。模块 ⇒ 从 {@code IModularMachineModule#getHost()} 反查；
     * 拿不到宿主 ⇒ {@code false}（按老主机口径处理，宁严不宽）。
     */
    public static boolean grantsCrossTypeParallel(MetaMachine machine) {
        if (machine == null) {
            return false;
        }
        try {
            if (machine instanceof PrimordialOmegaEngineMachine host) {
                return host.grantsCrossTypeParallel();
            }
            if (machine instanceof IModularMachineModule<?, ?> module) {
                final Object host = module.getHost();
                if (host instanceof PrimordialOmegaEngineMachine h) {
                    return h.grantsCrossTypeParallel();
                }
            }
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 判 grantsCrossTypeParallel 失败 machine={} err={}",
                    HostPatternMoverLog.PREFIX, machine.getPos(), t.toString());
        }
        return false;
    }

    /**
     * ★<b>「这台模块支持哪些配方类型」的唯一口径</b>（E 那条实测反馈的修法）。
     *
     * <pre>
     *   宿主授予跨配方种类并行 ⇒ 声明的全部（getRecipeTypes）
     *   否则（老主机）        ⇒ 只有当前选中的那一个（getRecipeType）
     * </pre>
     * 同一份集合同时给：① 放置分配（{@link InventoryTarget#supportsType}）
     * ② 通配符自动识别（老主机那条"用它所属模块的配方类型"）。
     */
    public static Set<String> effectiveRecipeTypesOf(MetaMachine machine) {
        return HostPatternMoverRecipes.effective(grantsCrossTypeParallel(machine),
                declaredRecipeTypesOf(machine), activeRecipeTypeOf(machine));
    }

    /**
     * 一台机器的「显示名 id」（面板把它翻成中文）。
     *
     * <p>取 {@code MachineDefinition#getId()}（形如 {@code shanhai:xxx}）而<b>不是</b>
     * {@code getName()}：后者在服务端是未翻译的 lang key，直接显示会露出裸键。
     */
    public static String displayIdOf(MetaMachine machine) {
        if (machine == null) {
            return "";
        }
        try {
            final var def = machine.getDefinition();
            if (def == null) {
                return "";
            }
            final ResourceLocation id = def.getId();
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ================================================================ 清空（G）

    /**
     * 把一批总成包成 {@link HostPatternMoverClear.Slots}（<b>只</b>给"清空主机"用）。
     *
     * <p>写空走 {@code getTerminalPatternInventory().setItemDirect(slot, ItemStack.EMPTY)}——
     * 上游 {@code setItemDirect} 自己会 {@code setStackInSlot + onContentsChanged + onPatternChange}
     * （与放置那条同源，见类注释 §四），所以这里<b>不</b>再补通知。
     */
    public static List<HostPatternMoverClear.Slots> clearViews(List<HostPatternMoverReader.Assembly> assemblies) {
        final List<HostPatternMoverClear.Slots> out = new ArrayList<>();
        if (assemblies == null) {
            return out;
        }
        for (HostPatternMoverReader.Assembly a : assemblies) {
            if (a == null) {
                continue;
            }
            try {
                out.add(new ClearView(a.label(), a.container().getTerminalPatternInventory()));
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 清空视图建不出来（跳过）label={} err={}",
                        HostPatternMoverLog.PREFIX, a.label(), t.toString());
            }
        }
        return out;
    }

    /** 清空用的槽视图（生产实现：真的 {@code setItemDirect(slot, EMPTY)}）。 */
    record ClearView(String label, InternalInventory inventory) implements HostPatternMoverClear.Slots {

        @Override
        public String label() {
            return this.label;
        }

        @Override
        public int size() {
            return this.inventory.size();
        }

        @Override
        public boolean occupied(int slot) {
            final ItemStack stack = this.inventory.getStackInSlot(slot);
            return stack != null && !stack.isEmpty();
        }

        @Override
        public boolean clear(int slot) {
            this.inventory.setItemDirect(slot, ItemStack.EMPTY);
            return true;
        }
    }

    // ================================================================ 适配器

    /**
     * 一块待放置样板（placer 的 {@link HostPatternMoverPlacer.Unit} 的生产实现）。
     */
    public static final class Unit implements HostPatternMoverPlacer.Unit {

        private final HostPatternMoverEntry entry;
        private final ItemStack stack;
        private final boolean wildcard;

        Unit(HostPatternMoverEntry entry, ItemStack stack) {
            this.entry = entry;
            this.stack = stack == null ? ItemStack.EMPTY : stack;
            this.wildcard = ShanhaiWildcardCompat.isWildcardPattern(this.stack);
        }

        public HostPatternMoverEntry entry() {
            return this.entry;
        }

        public ItemStack stack() {
            return this.stack;
        }

        @Override
        public String dedupKey() {
            return this.entry == null ? "" : this.entry.dedupKey();
        }

        @Override
        public String type() {
            return this.entry == null ? "" : this.entry.type();
        }

        @Override
        public boolean wildcard() {
            return this.wildcard;
        }

        /**
         * 第六刀：这条从星律母槽读来的<b>编程电路</b>（{@code 0} = 不设置）。
         *
         * <p>⚠️ 它<b>不是</b>样板物品 NBT 的一部分：写进机器的
         * {@code shanhaiWildcardCircuit} 那一格（见 {@link InventoryTarget#applyCircuit}）。
         */
        @Override
        public int circuit() {
            return this.entry == null ? HostPatternMoverCircuit.NONE : this.entry.circuit();
        }

        @Override
        public String toString() {
            return "Unit{key=" + dedupKey() + ", type='" + type() + "', wildcard=" + this.wildcard
                    + ", circuit=" + circuit() + "}";
        }
    }

    /**
     * 一台目标总成的生产适配器（placer 的 {@link HostPatternMoverPlacer.Target}）。
     *
     * <p>构造时把该总成<b>全部非空槽的去重键</b>算一次（{@code keyAt} 之后就是数组读）——
     * 面板/执行各建一次，1080 格的机器也只是一次线性扫。
     */
    public static final class InventoryTarget implements HostPatternMoverPlacer.Target {

        private final String label;
        private final String moduleId;
        private final PatternContainer container;
        private final InternalInventory inventory;
        private final Set<String> types;
        private final boolean crossTypeParallel;
        private final boolean oldHost;
        private final String activeType;
        private final boolean zoned;
        private final int normalSlots;
        private final int wildcardSlots;
        /**
         * 🔴 2026-11-06（第七刀补）：<b>整台总成的槽全是通配符样板槽</b>（超级通配符总成 = 10 格）。
         *
         * <p>为什么单列一个字段：{@link HostPatternMoverWildcard#typeToStamp} 原来要求 {@code zoned=true}
         * 才允许把类型落进样板 NBT，而超级通配符<b>不是</b>分区总成 ⇒ 从别处搬过来的通配符样板
         * 会被写进槽、却<b>不带类型</b>（和用户 2026-11-06 报的那个"黏贴过来没类型"是同一类 bug）。
         * 这台机器的"分区"是退化的：{@code normalSlots = 0}、全部 {@code wildcardSlots} 格都是通配符槽。
         */
        private final boolean wildcardOnly;
        /**
         * 第六刀 ＋ 第七刀：这台总成的「槽号 ⇒ 电路索引」来源
         * （没有电路 API 的总成 = {@link HostPatternMoverCircuit.Source#NONE}）。
         */
        private final HostPatternMoverCircuit.Source circuitSource;
        private final String[] slotKeys;
        private final int size;

        InventoryTarget(HostPatternMoverReader.Assembly assembly) {
            this.label = assembly.label();
            this.container = assembly.container();
            final MetaMachine owner = assembly.owner();
            this.moduleId = displayIdOf(owner);
            // ★E：有效类型集（跨类型并行 ⇒ 全部声明；老主机 ⇒ 当前选中的那一个）
            this.crossTypeParallel = grantsCrossTypeParallel(owner);
            // ★第五刀 ②：普通样板补类型只对【老主机】来源做（归墟一律不补）。
            //   判据用「这台总成的东家认出来的来源主机」—— 与读取时 fromHost 的判定同一个方法，
            //   拿不到宿主（= 认不出 / 模块没连主机）⇒ 不补（宁严不宽：补错会把样板钉死在错的类型上）。
            this.oldHost = HostPatternMoverReader.HOST_PRIMORDIAL.equals(
                    HostPatternMoverReader.classifyHost(owner));
            this.activeType = activeRecipeTypeOf(owner);
            this.types = HostPatternMoverRecipes.effective(this.crossTypeParallel,
                    declaredRecipeTypesOf(owner), this.activeType);
            this.inventory = assembly.container().getTerminalPatternInventory();
            int s = 0;
            try {
                s = Math.max(0, this.inventory.size());
            } catch (Throwable t) {
                s = 0;
            }
            this.size = s;
            // ★分区判据：星律样板总成的合并视图 isItemValid 恒 true ⇒ 必须自己声明区间
            //   （三份 helper 与读取侧 {@link #circuitSourceOf} 共用同一口径，不各写一遍）
            this.zoned = zonedContainer(assembly.container());
            this.normalSlots = normalSlotsOf(assembly.container());
            this.wildcardSlots = wildcardSlotsOf(assembly.container());
            this.wildcardOnly = assembly.container() instanceof SuperWildcardPatternBufferPartMachine;
            this.circuitSource = circuitSourceOf(assembly.container());
            this.slotKeys = new String[this.size];
            for (int i = 0; i < this.size; i++) {
                this.slotKeys[i] = keyOfSlot(this.inventory, i);
            }
        }

        public String moduleId() {
            return this.moduleId;
        }

        public Set<String> types() {
            return this.types;
        }

        /** 宿主是否授予跨配方种类并行（面板把这条判据写出来给玩家看）。 */
        public boolean crossTypeParallel() {
            return this.crossTypeParallel;
        }

        /**
         * 第五刀 ②：这台总成的东家是不是<b>老主机</b>（{@code primordial_omega_engine} 一脉）。
         *
         * <p>归墟（{@code guixu_vision_core}）会让普通样板"补了就是猜" ⇒ 一律不补；
         * 认不出来源主机也不补（宁严不宽）。
         */
        public boolean oldHost() {
            return this.oldHost;
        }

        /** 模块当前选中的类型（{@code ""} = 读不到；老主机上这就是唯一"支持"的那个）。 */
        public String activeType() {
            return this.activeType;
        }

        public boolean zoned() {
            return this.zoned;
        }

        public PatternContainer container() {
            return this.container;
        }

        @Override
        public String label() {
            return this.label;
        }

        @Override
        public int size() {
            return this.size;
        }

        @Override
        public String keyAt(int slot) {
            return slot < 0 || slot >= this.size ? null : this.slotKeys[slot];
        }

        @Override
        public boolean supportsType(String type) {
            return HostPatternMoverRecipes.supports(this.types, type);
        }

        @Override
        public int normalSlots() {
            return this.normalSlots;
        }

        @Override
        public int wildcardSlots() {
            return this.wildcardSlots;
        }

        /**
         * 总成自己的 {@code isItemValid}。
         *
         * <p>分区总成这里<b>不</b>直接返回 true：它那个合并视图的 {@code isItemValid} 确实是恒 true，
         * 但那不代表"任何物品都能进普通样板槽" —— 父类 {@code MEPatternBufferPartMachine} 的
         * 正常判据是 {@code slot 在范围内 && AEUtils.PROCESS_FILTER.apply(stack)}。
         * ⇒ 普通槽这一段<b>把那条 filter 补回来</b>（否则「放入」能把一块泥土塞进普通样板槽）；
         * 通配符槽不补：通配符样板块本来就不是 processing pattern，它由
         * {@code ShanhaiWildcardCompat#isWildcardPattern} 判过（见 {@link Unit#wildcard()}）。
         */
        @Override
        public boolean validAt(int slot, HostPatternMoverPlacer.Unit unit) {
            if (unit == null || slot < 0 || slot >= this.size) {
                return false;
            }
            final ItemStack stack = unit instanceof Unit u ? u.stack() : ItemStack.EMPTY;
            if (stack.isEmpty()) {
                return false; // NBT 造不出物品 ⇒ 不写（写了就是往机器里塞个空）
            }
            try {
                if (this.zoned) {
                    // 分区判据由 HostPatternMoverPlacer#slotOk 已经判过（这里只判"物品本身像不像样板"）
                    if (unit.wildcard()) {
                        return true;
                    }
                    final java.util.function.Function<ItemStack, Boolean> filter = processFilter();
                    return filter == null || Boolean.TRUE.equals(filter.apply(stack));
                }
                return this.inventory.isItemValid(slot, stack);
            } catch (Throwable t) {
                return false;
            }
        }

        /** 上游那条「这是不是一块样板」的判据（{@code AEUtils.PROCESS_FILTER}；拿不到 ⇒ {@code null}）。 */
        private static java.util.function.Function<ItemStack, Boolean> processFilter() {
            try {
                return org.gtlcore.gtlcore.integration.ae2.AEUtils.PROCESS_FILTER;
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * ★写一格：{@code setItemDirect}（上游自己会 {@code onPatternChange}，见类注释 §四）。
         *
         * <p>🔴 <b>第四刀 ②</b>：若这一格是<b>通配符母槽</b>、这块又是通配符样板，并且我们已经识别出了
         * 它的类型 ⇒ <b>顺手把类型写进样板自己的 NBT</b>（{@code gt_shanhai_recipe_type}，
         * 走机器侧同一个落笔处 {@link ShanhaiWildcardTypeNbt#write}）。
         * 否则机器重建时读到的是"空 = 自动识别"，玩家还得在星律面板里手动选一次
         * （用户原话：「通配符放入星律样板总成不会自动选择要执行的配方类型」）。
         * <p>🔴 <b>第五刀 ②</b>：<b>普通样板</b>没填类型、来源老主机、模块有效类型恰好 1 个时，
         * 读的时候补出来的类型同样要落到样板 NBT 上（用户原话：「若普通样板未填写配方类型，
         * 则尝试自动填写（只有老主机上的样板才能这样做）」）。判据在纯核
         * {@link HostPatternMoverWildcard#typeToStampPlain}（多义 / 自己已有类型 ⇒ 一个字都不写）。
         * <p>🔴 <b>2026-11-06 修正</b>：这句原来还写着「归墟 ⇒ 一个字都不写」，已去掉 ——
         * 「推断」才分主机（那条在 {@code resolve()} 里），「把已知类型落盘」与来源主机无关。
         * 旧行为导致用户实测的那条 bug：从老主机读出来（类型推断成功、物品 type 有值）再黏贴到
         * <b>归墟</b>机器上时，样板放得进去、机器却读不到类型（星律「未标注类型不执行」把它拦死）。
         * <p>⚠️ 写的是 {@code stack.copy()} 上的 tag：先改副本、再 {@code setItemDirect}
         * ⇒ 「看起来改了、其实没存档」那条老病根（原样改活对象不触发内容变更）不会出现。
         */
        @Override
        public void write(int slot, HostPatternMoverPlacer.Unit unit) {
            if (!(unit instanceof Unit u) || u.stack().isEmpty()) {
                throw new IllegalArgumentException("不是本 mod 的样板块 / 物品造不出来");
            }
            final ItemStack stack = u.stack().copy();
            final boolean wildcard = u.wildcard();
            // 🔴 第七刀补：交给纯核的"槽数"必须按机型取（HostPatternMoverWildcard#stampSlotCount）
            //   分区总成（星律）      → wildcardSlotsOf(...) = 50
            //   全通配符槽总成（超级） → 容器槽数 = 10；⚠️ wildcardSlotsOf(...) 对它回 -1，
            //                            直接传进去会撞上 `wildcardSlots <= 0 ⇒ 不写` 那条早退（整个口径失效）
            final int stampSlots = HostPatternMoverWildcard.stampSlotCount(this.wildcardOnly,
                    this.wildcardSlots, this.size);
            final String stamp = wildcard
                    ? HostPatternMoverWildcard.typeToStamp(this.zoned, this.normalSlots,
                    stampSlots, slot, true, this.wildcardOnly, u.type())
                    // 第五刀 ②：普通样板的分支（老主机 ＋ 自己没类型 ＋ 恰好 1 个候选才写）
                    : HostPatternMoverWildcard.typeToStampPlain(this.zoned, this.normalSlots, slot,
                    false, !HostPatternMoverReader.typeOf(stack).isEmpty(),
                    this.oldHost, u.type());
            if (!stamp.isEmpty()) {
                try {
                    final CompoundTag tag = stack.getOrCreateTag();
                    final boolean changed = ShanhaiWildcardTypeNbt.write(tag, stamp);
                    if (tag.isEmpty()) {
                        stack.setTag(null);
                    }
                    if (wildcard) {
                        HostPatternMoverLog.info("{} stamp_wildcard slot={} type={} item={} changed={}",
                                HostPatternMoverLog.PREFIX, Integer.valueOf(slot), stamp,
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()),
                                Boolean.valueOf(changed));
                    } else {
                        // 第五刀 ②：普通样板补类型的落盘读数（实机判读用这一行）
                        HostPatternMoverLog.info("{} stamp_plain slot={} type={} item={} changed={}",
                                HostPatternMoverLog.PREFIX, Integer.valueOf(slot), stamp,
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()),
                                Boolean.valueOf(changed));
                    }
                } catch (Throwable t) {
                    // 落类型失败绝不阻断放置：最坏退化成"玩家还得手选一次"，样板本身照写。
                    HostPatternMoverLog.warn("{} stamp 失败（照样写入样板，类型留空=自动识别）"
                            + " slot={} type={} wildcard={} err={}", HostPatternMoverLog.PREFIX,
                            Integer.valueOf(slot), stamp, Boolean.valueOf(wildcard), t.toString());
                }
            }
            this.inventory.setItemDirect(slot, stack);
        }

        /**
         * 这一格的<b>电路索引</b>（第六刀 ＋ 第七刀）⇒ {@code >= 0} 可写；否则 {@code -1}。
         *
         * <p>🔴 <b>唯一判定点</b>：索引怎么算全在纯核（{@link HostPatternMoverCircuit#motherSlotOf}），
         * 本方法只把它转出去。两种来源的差别只有"索引起点"（星律 1080 / 超级通配符 0），
         * <b>没有第二份区间判断</b>。
         * <p>纯算术 ⇒ 自检可以拿假总成离线打中它。
         * <p>🔴 <b>只</b>回一个数、<b>不</b>写机器：吃不吃这个数由纯核
         * {@link HostPatternMoverPlacer#apply} 决定（它要据此记 {@code circuitSkipped}；
         * 写在 {@link #write} 里面就没法判"目标不是有电路 API 的那一格"这件事）。
         */
        @Override
        public int wildcardMotherAt(int slot) {
            return this.circuitSource.indexAt(slot);
        }

        /**
         * 第六刀 ＋ 第七刀：把电路写回这一格（<b>样板已经写成功之后</b>才由纯核调用）。
         *
         * <p>🔴 {@code circuit == 0} 不会走到这里（纯核那道 {@code if} 就是为它设的，
         * {@link HostPatternMoverCircuit.Source#applyAt} 里还有第二道）：
         * 0 = 「源那边没设置」，<b>不是</b>「目标必须清空」—— 拿 0 去写会把目标原有的电路清掉，
         * 那是凭空删用户的数据（理由写全在 {@link HostPatternMoverCircuit} 类注释 §三）。
         * <p>🔴 <b>写哪台机器的哪个 API</b> 由 {@code circuitSource} 决定（第七刀）：
         * 星律 ⇒ {@code shanhaiApplyWildcardCircuit(母槽号, 值)}；
         * 超级通配符 ⇒ {@code shanhaiApplySlotCircuit(它自己的槽号, 值)}；其它 ⇒ 压根走不到这里
         * （{@link #wildcardMotherAt} 回 -1，由纯核记 {@code circuitSkipped}）。
         * <p>⚠️ 这里再判一次「在不在电路区」：纯核已经用 {@link #wildcardMotherAt} 挡过一遍，
         * 但两层判据都在才敢调机器侧 API（多判一次不花钱，错写一次是数据损失）。
         */
        @Override
        public void applyCircuit(int slot, int circuit) {
            if (wildcardMotherAt(slot) < 0) {
                return; // 不是可写电路的那一格：纯核那边会记 circuitSkipped（这里静默返回不算错）
            }
            // 写入口就在来源里（与读同一个索引算法）：星律/超级通配符各一个适配器，判定不复制
            if (!this.circuitSource.writableAt(slot)) {
                // 报到过索引、却写不进去 ⇒ 这是"写坏了"，不许静默（纯核会记 circuitFailed ＋ 一行 WARN）
                throw new IllegalStateException("电路来源报得出索引却没有写适配器: "
                        + this.circuitSource.kind());
            }
            final int index = this.circuitSource.indexAt(slot);
            final boolean accepted = this.circuitSource.applyAt(slot, circuit);
            // 「值没变」在超级通配符上是正常情形（重贴一次同样的电路）⇒ 只影响日志用词，
            //   🔴 绝不影响样板：样板已经在槽里、绝不回滚。
            HostPatternMoverLog.info("{} circuit_{} slot={} mother={} circuit={} target={} src={}",
                    HostPatternMoverLog.PREFIX, accepted ? "applied" : "rejected",
                    Integer.valueOf(slot), Integer.valueOf(index),
                    Integer.valueOf(HostPatternMoverCircuit.normalize(circuit)), this.label,
                    this.circuitSource.kind());
        }

        @Override
        public String toString() {
            return "Target{" + this.label + ", module=" + this.moduleId + ", slots=" + this.size
                    + ", zoned=" + this.zoned + ", types=" + this.types.size() + "}";
        }
    }

    // ================================================================ 面板要的摘要

    /**
     * 一台总成的面板摘要（几十字节，随界面同步包走）。
     *
     * @param label    坐标标号（{@code 12, 3, -4}）
     * @param moduleId 所属模块的 {@code MachineDefinition#getId()}（客户端翻中文；{@code ""} = 认不出）
     * @param slots    槽数
     * @param normal   普通槽数（{@code -1} = 不分区）
     * @param wildcard 通配符槽数（{@code -1} = 不分区）
     * @param free     现在空着且能放「至少一种被请求类型」的槽数（面板显示「余量」）
     */
    public record AssemblyInfo(String label, String moduleId, int slots, int normal, int wildcard, int free) {

        public static final AssemblyInfo EMPTY = new AssemblyInfo("?", "", 0, -1, -1, 0);

        public void write(net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeUtf(this.label == null ? "" : this.label, 64);
            buf.writeUtf(this.moduleId == null ? "" : this.moduleId, 128);
            buf.writeVarInt(this.slots);
            buf.writeVarInt(this.normal + 1);
            buf.writeVarInt(this.wildcard + 1);
            buf.writeVarInt(this.free);
        }

        public static AssemblyInfo read(net.minecraft.network.FriendlyByteBuf buf) {
            return new AssemblyInfo(buf.readUtf(64), buf.readUtf(128), buf.readVarInt(),
                    buf.readVarInt() - 1, buf.readVarInt() - 1, buf.readVarInt());
        }
    }

    /** 收集一批总成在<b>面板上要显示</b>的摘要（服务端调用；见 {@link #compile}）。 */
    public static List<AssemblyInfo> summarize(List<HostPatternMoverReader.Assembly> assemblies) {
        return compile(assemblies).infos();
    }

    // ================================================================ 槽位诊断（写入前后对账用）

    /** 一台总成的「整机指纹」：每个槽的去重键（空槽写 {@code -}）；写入前后各取一次即可判"变没变"。 */
    public static String fingerprint(PatternContainer container) {
        if (container == null) {
            return "?";
        }
        final InternalInventory inv;
        try {
            inv = container.getTerminalPatternInventory();
        } catch (Throwable t) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(256);
        int size;
        try {
            size = Math.max(0, inv.size());
        } catch (Throwable t) {
            return "?";
        }
        sb.append(size).append(':');
        final Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < size; i++) {
            final String key = keyOfSlot(inv, i);
            if (key == null) {
                sb.append('-');
            } else {
                sb.append('#');
                counts.merge(key, Integer.valueOf(1), Integer::sum);
            }
        }
        sb.append("|distinct=").append(counts.size());
        return sb.toString();
    }
}
