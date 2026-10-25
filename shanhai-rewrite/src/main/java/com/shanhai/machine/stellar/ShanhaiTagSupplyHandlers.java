package com.shanhai.machine.stellar;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.ISubscription;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/**
 * ⑧ <b>标签命中集合 = 一个独立的供料源</b>：两份只读 handler（物品 / 流体）。
 *
 * <h2>一、它和「库存那 160 格」是什么关系（🔴 用户 2026-10-25 拍的口径）</h2>
 * <p>用户原话：「<b>星律样板总成的标签拉取把库存的位置填了，但是实际上它们应该分开各不干扰</b>」。
 * ⇒ 上一轮那套「开关开 ⇒ 按标签重写库存配置并清尾」的做法<b>整体作废</b>：
 * 本类的两份 handler <b>一次都不碰</b> {@code shanhaiStockItemHandler} / {@code shanhaiStockFluidHandler}
 * 的 config，也<b>不</b>调用 {@code onConfigChanged()} / {@code clearInventory()}。
 * 手配的那 160 格（物品）+ 160 格（流体）<b>逐格原样</b>，标签开也好关也好都不改它。
 *
 * <h2>二、供料源怎么注册进配方 handler 列表</h2>
 * <p>与「万能仓室」那批（{@code com.shanhai.api.machine.ShanhaiSupplyOnlyItemHandler} /
 * {@code ShanhaiSupplyOnlyFluidHandler}）<b>同一条路</b>：
 * 由 {@code StellarSampleAssemblerPartMachine#shanhaiNormalSupplyHandlers()} 返回，
 * 再由 {@code com.shanhai.mixin.ShanhaiUniversalSupplyMixin} 把它插进 gtlcore 的「普通供料」视图
 * ⇒ 由 gtlcore <b>自己</b>那份 {@code handleNormalPart} 收下（落点与普通输入仓完全相同）。
 * <b>不需要</b>样板、<b>不会</b>进 ME 分支、<b>不是</b> {@code MachineTrait}（不 attach、不污染 capabilitiesProxy）。
 *
 * <h2>三、🔴 到底扣不扣 ME —— 扣，而且只在一个地方扣（不许含糊）</h2>
 * <table border="1">
 *   <tr><th>阶段</th><th>本类做什么</th><th>ME 里的量</th></tr>
 *   <tr><td>{@code simulate = true}（配方匹配 / 算料那一遍）</td>
 *       <td>{@code MEStorage#extract(key, n, Actionable.SIMULATE, actionSource)}</td>
 *       <td><b>不变</b>（SIMULATE 只问不扣）</td></tr>
 *   <tr><td>{@code simulate = false}（真跑配方那一遍）</td>
 *       <td>同一句换成 {@code Actionable.MODULATE}</td>
 *       <td><b>减少</b> —— <b>就扣在这一步</b></td></tr>
 * </table>
 * <p>⇒ 语义 = 「与 ME <b>共享内容物、不搬物品</b>」：本类<b>没有</b>任何本地缓冲/数量字段，
 * 料从来不进机器背包，扣的时候直接从网络里抽走（与库存输入仓 {@code ExportOnlyAEStocking*List}
 * 同一条语义，只是触发点是 handler 而不是槽）。
 * <p>🔴 <b>不是</b>「只声明能供、永不抽取」那种（那是
 * {@code ShanhaiSupplyOnlyItemHandler} 给<b>催化剂</b>用的语义）——
 * 那种语义放在<b>配方原料</b>上会凭空造物。这里刻意做成真抽取。
 *
 * <h2>四、抽不满怎么办（不许吞东西）</h2>
 * <p>{@code MODULATE} 返回的量少于需求（同一 tick 里被别人抢走）⇒ 把已经抽到的那部分
 * {@code MEStorage#insert(...)} <b>原样塞回去</b>，然后把这条需求<b>留在 {@code left} 里</b>
 * 交给后面的 handler。⇒ 宁可「这次供不上」，绝不「扣了料却没满足」。
 *
 * <h2>五、命中集合从哪来 / 什么时候换</h2>
 * <p>不是本类算的：本类只通过 {@link Source} 现读
 * {@code StellarSampleAssemblerPartMachine} 里那份<b>快照</b>（每库存同步周期由
 * {@code shanhaiTagPull} 用 {@code ShanhaiStockConfigLayout.accepts} + {@code ShanhaiTagFilterExpression}
 * 过滤 AE 缓存库存算出，<b>只在变化时</b>换）。
 * <p>本类把 {@code getContents()} 的结果按 {@link Source#shanhaiTagSupplyVersion()} 缓存 ——
 * 实机日志里 {@code getContents()} 被问过 18 万次，每次重建 200+ 个 {@code ItemStack} 是纯浪费。
 *
 * <h2>六、安全边界</h2>
 * <ul>
 *   <li>标签关 / 名单空 / 没连网 ⇒ {@link Source#shanhaiTagSupplyEnabled()} 为 false 或命中集合为空
 *       ⇒ {@code getContents()} 返回空表、{@code handleRecipeInner} <b>原样退回 {@code left}</b>
 *       ⇒ 后面的 handler 照常被问，<b>行为与没有本类时逐字节相同</b>；</li>
 *   <li>只处理 {@code IO.IN}；{@code IO.OUT} 一律原样退回（本机不产料）；</li>
 *   <li>只认 {@code left} 里<b>真的能被命中集合满足</b>的那几条，满足一条就把这一条从 {@code left} 里摘掉，
 *       剩下的原样交给后面的 handler（与 gtceu {@code handleIngredient} 同一套「部分满足」口径）。</li>
 * </ul>
 */
public final class ShanhaiTagSupplyHandlers {

    /** 空订阅（只读视图，不参与变更通知）。 */
    private static final ISubscription NOOP_SUBSCRIPTION = () -> {
    };

    private ShanhaiTagSupplyHandlers() {
    }

    /**
     * handler 与机器之间的<b>唯一</b>接口（机器侧实现；本文件不引用机器类 ⇒ 依赖是单向的）。
     *
     * <p>⚠️ 全部方法都必须是「现读」：handler 每次 rebuild 都会被重建（见 mixin），不能缓存机器状态。
     */
    public interface Source {

        /** 标签这一路现在是否该供料（开关开 + 名单非空 + 已连网 + 表达式没写错 + 命中集合非空）。 */
        boolean shanhaiTagSupplyEnabled();

        /** 命中集合的版本号：集合一变就变（handler 用它缓存 {@code getContents()}）。 */
        long shanhaiTagSupplyVersion();

        /** 当前命中的物品键（<b>快照</b>，调用方只读；顺序 = 命中集合的槽序，稳定）。 */
        List<AEItemKey> shanhaiTagSupplyItems();

        /** 当前命中的流体键（同上）。 */
        List<AEFluidKey> shanhaiTagSupplyFluids();

        /** 某个键在 ME 里的现量（来自最近一次刷新快照；只是<b>预筛</b>，权威判据是 {@link #shanhaiTagSupplyExtract}）。 */
        long shanhaiTagSupplyAmount(AEKey key);

        /**
         * 从 ME 抽取。
         *
         * @param simulate true = 只问不扣（{@code Actionable.SIMULATE}）；false = <b>真扣</b>（{@code Actionable.MODULATE}）
         * @return 实际（会）扣到的量；{@code 0} = 抽不到
         */
        long shanhaiTagSupplyExtract(AEKey key, long amount, boolean simulate);

        /**
         * 把刚才多抽出来的量塞回 ME（{@code Actionable.MODULATE}）。
         *
         * @return 实际塞回去的量（塞不回去的部分会进 WARN，绝不静默吞掉）
         */
        long shanhaiTagSupplyReinsert(AEKey key, long amount);

        /**
         * 打点（machine 侧计数 + {@code SUPPLYCHAIN} 一行）。
         *
         * @param fluid    true = 流体侧
         * @param contents true = 这一次是 {@code getContents()}；false = {@code handleRecipeInner}
         * @param n        contents: 内容物条数；handle: 剩余需求条数（{@code -1} = null）
         * @param simulate 仅 handle 有意义
         */
        void shanhaiTagSupplyCount(boolean fluid, boolean contents, int n, boolean simulate);
    }

    // ================================================================ 物品

    /** 标签命中集合的<b>物品</b>供料 handler：能供就 {@code null} / 摘掉那一条，并且<b>真扣 ME</b>。 */
    public static final class Item implements IRecipeHandlerTrait<Ingredient> {

        private final String label;
        private final Source source;

        /** 缓存的内容物快照（版本号一致时直接复用；{@code getContents()} 是超高频调用）。 */
        private long cachedVersion = Long.MIN_VALUE;
        private List<Object> cachedContents = List.of();
        private double cachedTotal = 0.0D;

        public Item(String label, Source source) {
            this.label = label;
            this.source = source;
        }

        /** 调试名。 */
        public String shanhaiLabel() {
            return this.label;
        }

        @Override
        public String toString() {
            return this.label;
        }

        @Override
        public IO getHandlerIO() {
            return IO.IN;
        }

        @Override
        public RecipeCapability<Ingredient> getCapability() {
            return ItemRecipeCapability.CAP;
        }

        @Override
        public boolean isProxy() {
            // 不是代理：gtlcore 的 handleNormalPart 会丢弃 isProxy()==true 的 handler
            return false;
        }

        @Override
        public ISubscription addChangedListener(Runnable listener) {
            return NOOP_SUBSCRIPTION;
        }

        @Override
        public List<Object> getContents() {
            final long version = this.source.shanhaiTagSupplyVersion();
            if (version != this.cachedVersion) {
                this.cachedVersion = version;
                this.cachedContents = shanhaiBuildItemContents(this.source);
                this.cachedTotal = shanhaiTotalOf(this.cachedContents);
            }
            this.source.shanhaiTagSupplyCount(false, true, this.cachedContents.size(), false);
            // 🔴 每次都交一份**新的可变 List**（元素还是缓存里那些 ItemStack，不重建）：
            //    本工程既有 handler（{@code ShanhaiSupplyOnlyItemHandler}）交的就是可变 List，
            //    上游拿它做什么我们不管，但**绝不能**交一个不可变 List 出去
            //    —— 万一某个调用方对它排序/清空，就会在游戏里抛 UnsupportedOperationException。
            return new ArrayList<>(this.cachedContents);
        }

        @Override
        public double getTotalContentAmount() {
            // 与 getContents 同一份缓存：本方法也是高频调用（不能每次遍历一遍 200+ 个栈）
            final long version = this.source.shanhaiTagSupplyVersion();
            if (version != this.cachedVersion) {
                this.cachedVersion = version;
                this.cachedContents = shanhaiBuildItemContents(this.source);
                this.cachedTotal = shanhaiTotalOf(this.cachedContents);
            }
            return this.cachedTotal;
        }

        /**
         * 🔴 只对 {@code IO.IN} 生效；满足一条就摘一条，剩下的原样交出去。
         *
         * @param simulate true = 只问不扣；false = <b>真的从 ME 扣</b>（扣在 {@link Source#shanhaiTagSupplyExtract}）
         */
        @Override
        public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> left,
                                                  String slotName, boolean simulate) {
            final List<Ingredient> out = this.shanhaiHandle(io, left, simulate);
            this.source.shanhaiTagSupplyCount(false, false, out == null ? -1 : out.size(), simulate);
            return out;
        }

        private List<Ingredient> shanhaiHandle(IO io, List<Ingredient> left, boolean simulate) {
            if (io != IO.IN) {
                return left;                     // 输入侧才认账
            }
            if (left == null || left.isEmpty()) {
                return null;                     // 没有需求 = 满足
            }
            if (!this.source.shanhaiTagSupplyEnabled()) {
                return left;                     // 标签关 / 名单空 / 没连网 ⇒ 与没有本类时逐字节相同
            }
            final List<AEItemKey> keys = this.source.shanhaiTagSupplyItems();
            if (keys.isEmpty()) {
                return left;
            }
            List<Ingredient> remaining = null;
            for (int i = 0; i < left.size(); i++) {
                final Ingredient need = left.get(i);
                if (need == null) {
                    if (remaining == null) {
                        remaining = new ArrayList<>(left.size());
                    }
                    remaining.add(null);
                    continue;
                }
                if (this.shanhaiSupplyOne(need, keys, simulate)) {
                    continue;
                }
                if (remaining == null) {
                    remaining = new ArrayList<>(left.size());
                }
                remaining.add(need);
            }
            return remaining == null ? null : remaining;
        }

        /** 试着用命中集合满足一条需求；成功（或模拟成功）⇒ true。 */
        private boolean shanhaiSupplyOne(Ingredient need, List<AEItemKey> keys, boolean simulate) {
            final ItemStack[] samples = need.getItems();
            if (samples == null || samples.length == 0) {
                return false;                    // 空 ingredient 不构成需求（gtceu 的 handleIngredient 也跳过）
            }
            final long required = Math.max(1L, samples[0] == null ? 0L : samples[0].getCount());
            for (int i = 0; i < keys.size(); i++) {
                final AEItemKey key = keys.get(i);
                if (key == null || !key.matches(need)) {
                    continue;                    // 先按 ingredient 本身过滤（不碰 AE，便宜）
                }
                if (this.source.shanhaiTagSupplyAmount(key) < required) {
                    continue;                    // 快照说不够 ⇒ 换下一个键（权威判据在下面那次 extract）
                }
                final long got = this.source.shanhaiTagSupplyExtract(key, required, simulate);
                if (got >= required) {
                    return true;
                }
                if (!simulate && got > 0L) {
                    // 🔴 抽不满：把已经抽到的塞回去（不许吞东西），这条需求仍然算没满足
                    this.source.shanhaiTagSupplyReinsert(key, got);
                }
            }
            return false;
        }
    }

    // ================================================================ 流体

    /** 标签命中集合的<b>流体</b>供料 handler：与 {@link Item} 逐条同构。 */
    public static final class Fluid implements IRecipeHandlerTrait<FluidIngredient> {

        private final String label;
        private final Source source;

        private long cachedVersion = Long.MIN_VALUE;
        private List<Object> cachedContents = List.of();
        private double cachedTotal = 0.0D;

        public Fluid(String label, Source source) {
            this.label = label;
            this.source = source;
        }

        public String shanhaiLabel() {
            return this.label;
        }

        @Override
        public String toString() {
            return this.label;
        }

        @Override
        public IO getHandlerIO() {
            return IO.IN;
        }

        @Override
        public RecipeCapability<FluidIngredient> getCapability() {
            return FluidRecipeCapability.CAP;
        }

        @Override
        public boolean isProxy() {
            return false;
        }

        @Override
        public ISubscription addChangedListener(Runnable listener) {
            return NOOP_SUBSCRIPTION;
        }

        @Override
        public List<Object> getContents() {
            final long version = this.source.shanhaiTagSupplyVersion();
            if (version != this.cachedVersion) {
                this.cachedVersion = version;
                this.cachedContents = shanhaiBuildFluidContents(this.source);
                this.cachedTotal = shanhaiTotalOf(this.cachedContents);
            }
            this.source.shanhaiTagSupplyCount(true, true, this.cachedContents.size(), false);
            // 同物品侧：交一份新的可变 List（元素复用缓存），不交不可变视图出去
            return new ArrayList<>(this.cachedContents);
        }

        @Override
        public double getTotalContentAmount() {
            final long version = this.source.shanhaiTagSupplyVersion();
            if (version != this.cachedVersion) {
                this.cachedVersion = version;
                this.cachedContents = shanhaiBuildFluidContents(this.source);
                this.cachedTotal = shanhaiTotalOf(this.cachedContents);
            }
            return this.cachedTotal;
        }

        @Override
        public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> left,
                                                       String slotName, boolean simulate) {
            final List<FluidIngredient> out = this.shanhaiHandle(io, left, simulate);
            this.source.shanhaiTagSupplyCount(true, false, out == null ? -1 : out.size(), simulate);
            return out;
        }

        private List<FluidIngredient> shanhaiHandle(IO io, List<FluidIngredient> left, boolean simulate) {
            if (io != IO.IN) {
                return left;
            }
            if (left == null || left.isEmpty()) {
                return null;
            }
            if (!this.source.shanhaiTagSupplyEnabled()) {
                return left;
            }
            final List<AEFluidKey> keys = this.source.shanhaiTagSupplyFluids();
            if (keys.isEmpty()) {
                return left;
            }
            List<FluidIngredient> remaining = null;
            for (int i = 0; i < left.size(); i++) {
                final FluidIngredient need = left.get(i);
                if (need == null) {
                    if (remaining == null) {
                        remaining = new ArrayList<>(left.size());
                    }
                    remaining.add(null);
                    continue;
                }
                if (this.shanhaiSupplyOne(need, keys, simulate)) {
                    continue;
                }
                if (remaining == null) {
                    remaining = new ArrayList<>(left.size());
                }
                remaining.add(need);
            }
            return remaining == null ? null : remaining;
        }

        private boolean shanhaiSupplyOne(FluidIngredient need, List<AEFluidKey> keys, boolean simulate) {
            final long required = Math.max(1L, need.getAmount());
            for (int i = 0; i < keys.size(); i++) {
                final AEFluidKey key = keys.get(i);
                if (key == null || !shanhaiFluidIngredientMatches(need, key)) {
                    continue;
                }
                if (this.source.shanhaiTagSupplyAmount(key) < required) {
                    continue;
                }
                final long got = this.source.shanhaiTagSupplyExtract(key, required, simulate);
                if (got >= required) {
                    return true;
                }
                if (!simulate && got > 0L) {
                    this.source.shanhaiTagSupplyReinsert(key, got);
                }
            }
            return false;
        }
    }

    // ================================================================ 共用小工具

    /** 流体原料匹配：直接用父类同一个判据（gtlcore {@code AEUtils#testFluidIngredient}，public static）。 */
    private static boolean shanhaiFluidIngredientMatches(FluidIngredient need, AEFluidKey key) {
        try {
            return org.gtlcore.gtlcore.integration.ae2.AEUtils.testFluidIngredient(need, key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把命中集合读成 {@code getContents()} 的形状（每个栈是<b>新对象</b>，与 ME 里的东西无关）。 */
    private static List<Object> shanhaiBuildItemContents(Source source) {
        final List<AEItemKey> keys = source.shanhaiTagSupplyItems();
        if (keys.isEmpty()) {
            return List.of();
        }
        final List<Object> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            final AEItemKey key = keys.get(i);
            if (key == null) {
                continue;
            }
            final long amount = source.shanhaiTagSupplyAmount(key);
            if (amount <= 0L) {
                continue;
            }
            out.add(key.toStack((int) Math.min(amount, Integer.MAX_VALUE)));
        }
        return List.copyOf(out);
    }

    /** 同上，流体侧（LDLib {@code FluidStack}，与 gtceu 的 {@code createFluidMap} 同一类型）。 */
    private static List<Object> shanhaiBuildFluidContents(Source source) {
        final List<AEFluidKey> keys = source.shanhaiTagSupplyFluids();
        if (keys.isEmpty()) {
            return List.of();
        }
        final List<Object> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            final AEFluidKey key = keys.get(i);
            if (key == null) {
                continue;
            }
            final long amount = source.shanhaiTagSupplyAmount(key);
            if (amount <= 0L) {
                continue;
            }
            out.add(FluidStack.create(key.getFluid(), amount, key.getTag()));
        }
        return List.copyOf(out);
    }

    /** 内容物总量（{@code getTotalContentAmount} 用；与 {@code getContents} 同一份缓存）。 */
    private static double shanhaiTotalOf(List<Object> contents) {
        if (contents.isEmpty()) {
            return 0.0D;
        }
        long total = 0L;
        for (int i = 0; i < contents.size(); i++) {
            final Object entry = contents.get(i);
            if (entry instanceof ItemStack stack) {
                if (stack != null && !stack.isEmpty()) {
                    total += stack.getCount();
                }
            } else if (entry instanceof FluidStack fluid) {
                if (fluid != null && fluid.getAmount() > 0L) {
                    total += fluid.getAmount();
                }
            }
        }
        return total;
    }
}
