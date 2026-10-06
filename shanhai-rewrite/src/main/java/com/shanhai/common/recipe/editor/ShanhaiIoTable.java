package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.shanhai.ShanhaiMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作区面板里那 <b>24 个格子</b>（输入物品 9 ＋ 输入流体 3 ＋ 输出物品 9 ＋ 输出流体 3）的编辑缓冲。
 *
 * <h2>1. 为什么需要"缓冲"这一层（而不是直接改配方）</h2>
 * 用户的手势是"拖一个进来 / 右键删掉 / 中键改数量"，然后<b>点保存</b>。
 * 如果每一次手势都直接调用一次 {@code ShanhaiRecipeEditorOps}，那么：
 * <ul>
 *   <li>每拖一次就要重建一次 GT 索引（实测 25~94 ms）＋ 重写一次原版两张表（100~160 ms）
 *       ＝ 手感直接烂掉；</li>
 *   <li>而且"改了但没保存"这件事在界面上就<b>说不清</b>了（保存按钮也就没有意义）。</li>
 * </ul>
 * ⇒ 编辑落在本类的缓冲里，<b>只有"保存"那一下</b>才走那条已有验证的后端链
 * （{@link ShanhaiRecipeEditorOps#setIo}）。
 *
 * <h2>2. 🔴 未动过的格子必须原样带回原位（否则会静默毁掉配方语义）</h2>
 * GT 的每个 {@link Content} 可能是<b>标签</b>（{@code forge:lenses/diamond}）、<b>电路</b>
 * （{@code gtceu:circuit} 带 configuration）、带 {@code chance/maxChance/tierChanceBoost} 的
 * <b>概率产物</b>…… 这些都<b>不能</b>用一个"具体物品栈"表示。
 * 所以每个格子都握着它从配方里读出来的那个 <b>原始 {@code Content}</b>：
 * <pre>
 *   dirty == false  ⇒ 回写时【原样放回那个 Content】（一个字节都不动）
 *   dirty == true   ⇒ 只换掉"材料"那一部分，并把原来的 chance/maxChance/tierChanceBoost 抄过去
 * </pre>
 * ⇒ <b>打开一条配方、什么都不动、直接保存 ＝ 内容完全不变</b>（自检里有这一拍）。
 *
 * <h2>3. 三种"改成什么"的落盘形状都是 GT 自己的 codec 出的</h2>
 * <ul>
 *   <li>物品格子：{@code {"type":"gtceu:sized","count":N,"ingredient":{"item":"…"}}}
 *       —— 这个形状是第二刀 <b>运行期验过</b>的那一个（{@code edit_io in=7} 那一拍）；</li>
 *   <li>流体格子：{@link FluidIngredient#of(FluidStack...)} ＋ {@code Content.codec(FluidRecipeCapability.CAP)}
 *       ⇒ 形状由 GT 自己产生，不猜；</li>
 *   <li>没动过的格子：{@code encodeContent(cap, original)}。</li>
 * </ul>
 */
public final class ShanhaiIoTable {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 默认的四元组（老的固定 24 格口径；{@code new ShanhaiIoTable()} 用这一组）。 */
    public static final int ITEM_IN = 9;
    public static final int FLUID_IN = 3;
    public static final int ITEM_OUT = 9;
    public static final int FLUID_OUT = 3;

    /** 默认总格数（兼容旧读数；真实格数请用 {@link #cellCount()}）。 */
    public static final int CELLS = ITEM_IN + FLUID_IN + ITEM_OUT + FLUID_OUT;

    /**
     * 单区上限（防止某个畸形类型把面板撑爆）。**不是**业务上限：
     * {@link ShanhaiRecipeTypes#maxIoOf} 给的才是权威，这里只兜底。
     * <p>🔴 2026-10-05 用户点单：「有时需要满足<b>非常巨大的输入和输出格</b>」
     * ⇒ 上限按现有最夸张的类型留足余量（原初物质解构 103 物品出 / 原初深空汲取 108 物品出），
     * 并且<b>格子区做成可分页</b>（{@link ShanhaiRecipeEditorWorkspace#CELLS_PER_PAGE}），
     * 所以上限提上去也不会把面板撑爆。
     */
    public static final int MAX_PER_SECTION = 128;

    /**
     * 🔴🔴 2026-10-05（第 6 轮）<b>一个物品格的【数量】上限 —— 不是 64</b>。
     *
     * <h4>用户原话（逐字）</h4>
     * <blockquote>「额外：他这个不让我输入超过64的数字啊，<b>实际上机器可以接受超过64个的啊</b>，如图1」</blockquote>
     * 以及此前定过的口径（逐字）：
     * <blockquote>「不需要改格子数，<b>一个格子可以超过64</b>，那个格子只是给你jei看着用的，
     * 实际上根本不会限制机器的输入上限，真正限制上限的是各种总成，而它的输入上限是9.22E」</blockquote>
     *
     * <h4>真值 = GT 自己的容器类型，不是我们拍的（javap 实证）</h4>
     * <pre>
     *   com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient
     *     protected final int amount;              ← int
     *     public int getAmount();                  ← int
     *     public static SizedIngredient create(Ingredient, int);   ← 无任何 clamp（偏移 0-9 只有 new+invokespecial）
     *   ⇒ 本工程能表达的最大数量 = Integer.MAX_VALUE = 2147483647
     *   （用户说的 9.22E 是【总成吞吐】的口径，大于本条链路上任何字段能存的数；
     *     真正卡住的是字段类型，所以这里取 int 的上界并如实写出来。）
     * </pre>
     *
     * <h4>为什么原来会卡在 64</h4>
     * {@link Cell#setItem} 里有一句 {@code Math.min(count, item.getMaxStackSize())} ——
     * 那是<b>原版容器</b>的语义（一次插入不能超过一摞），搬到"配方的一格"上是错的：
     * 配方那一格存的是 {@code SizedIngredient.amount}，不是一摞物品。
     * <p>⚠️ 与之对照：<b>读回</b>那条路从来没有这个 clamp
     * （{@link #representativeItem} 走 {@code Ingredient.getItems()} ⇒ count 原样带回），
     * 所以界面上【显示】1024 是正常的、而【填】1024 会被压成 64 —— 用户截图里那一屏正是这个反差。
     */
    public static final int MAX_ITEM_COUNT = Integer.MAX_VALUE;

    /** 一格的种类。 */
    public enum Kind { ITEM_IN, FLUID_IN, ITEM_OUT, FLUID_OUT }

    // ================================================================== 一个格子

    /** 一个格子。{@code itemKind=true} 的格子用 {@link #item}，否则用 {@link #fluid}。 */
    public static final class Cell {

        public final boolean itemKind;

        /** 从配方里读出来的那一条原文（新加的格子为 {@code null}）。 */
        public Content original;

        public ItemStack item = ItemStack.EMPTY;
        public FluidStack fluid = FluidStack.empty();

        /** 用户动过这个格子吗（动过 = 回写时用上面的物品/流体重建 Content）。 */
        public boolean dirty;

        // ── 🆕 2026-10-05（用户点单 B6/B7/A4/A5：催化剂 / 概率 / 随电压递增 都要能看能改）──
        //
        // 这三个数与 GT 的 Content 一一对应：
        //   chance           —— 概率产出的"基准概率"，GT 口径 10000 = 100%（JEI 显示 10.00% 就是 chance=1000）
        //   maxChance        —— 显示分母（GT 里恒为 ChanceLogic.getMaxChancedValue()，一般 10000）
        //   tierChanceBoost  —— "随电压等级递增"那一部分（每升一档加多少，同样 10000 = 100%）
        // notConsumable = 催化剂（GT 的语义：chance==0 且 maxChance==0 ⇒ 这一条不消耗）
        //   —— 定义不是我编的：GTRecipeBuilder.notConsumable(...) 走的就是把 chance/maxChance 归零这条路，
        //      而 JEI/GT 那一页的"不消耗"标记也正是按 chance==0 判的。

        /** 概率（10000 = 100%）。读不出来时按 10000（= 必出，与 GT 的默认一致）。 */
        public int chance = 10000;
        public int maxChance = 10000;
        /** 随电压等级递增的那一部分（10000 = 每升一档 +100%）。 */
        public int tierChanceBoost;

        /** 催化剂（不消耗）。 */
        public boolean notConsumable;

        /** 用户改过概率/递增/催化剂吗（与 {@link #dirty} 分开：它决定 chance 三件套要不要写回）。 */
        public boolean chanceDirty;

        /**
         * 🆕 2026-10-05（A6）：这一格是<b>已经写坏的老数据</b>（催化剂被写成 {@code chance=0,maxChance=0}）,
         * 读进来时已经就地修正成 GT 原生的 {@code (0, 10000)}，<b>保存时要把它写回配方</b>。
         *
         * <p>为什么不直接置 {@link #chanceDirty}：那会让"只打开看一眼、什么都没改"的配方也被判成脏
         * （界面上会出现"你有未保存的改动"这种假象）。分开一个位，语义才准：
         * <b>用户没改，但数据要修</b>。
         */
        public boolean catalystRepair;

        public Cell(boolean itemKind) {
            this.itemKind = itemKind;
        }

        /** 这套数是不是"必出且不递增"（= GT 的默认值，界面上不必标任何角标）。 */
        public boolean plainChance() {
            return !notConsumable && chance == maxChance && maxChance > 0 && tierChanceBoost == 0;
        }

        /** 概率百分数文本（GT/JEI 的口径：{@code chance/maxChance*100}，两位小数）。 */
        public String chanceText() {
            if (notConsumable || maxChance <= 0) {
                return "";
            }
            if (chance >= maxChance) {
                return "";
            }
            return String.format(java.util.Locale.ROOT, "%.2f%%", (double) chance * 100.0 / (double) maxChance);
        }

        /**
         * 把概率三件套 + 催化剂一次性设上（界面上那一个小窗的"确定"走这里）。
         *
         * <h4>🔴🔴 2026-10-05 A6 修正：催化剂原来写成 chance=0 ＋ maxChance=0，GT 会画成 {@code NaN%}</h4>
         * 用户原话：「我要是选择催化剂它在 jei 里面显示是这样的（黄字 NaN%），
         * <b>而且作为催化剂不是把概率调到 0 啊</b>」。
         *
         * <p><b>GT 原生 notConsumable 到底是什么值</b>（javap {@code GTRecipeBuilder.notConsumable(ItemStack)}，
         * 偏移 0–21 逐条读出来的）：它 <b>只把 {@code builder.chance} 临时置 0</b>，
         * {@code maxChance} 一个字节都没碰 —— 而构造器里 {@code chance = maxChance = getMaxChancedValue()}（=10000）
         * ⇒ 落到 {@code Content} 上的实际值是 <b>{@code (chance=0, maxChance=10000)}</b>。
         *
         * <p><b>GT 画那行字的算式</b>（javap {@code Content.drawChance}）：
         * <pre>
         *   percent = 100f * chance / maxChance;              // 偏移 41–55
         *   text    = (percent == 0) ? "§c不消耗§r" : formatPercent(percent) + "%"   // 66–93
         *   color   = (percent == 0) ? 红 : 黄                  // 95–111
         * </pre>
         * ⇒ {@code (0, 0)} 时 {@code 0f/0f = NaN}，而 <b>{@code NaN == 0} 是 false</b>
         * ⇒ 走"非零"那一支 ⇒ <b>黄字 ＋ "NaN%"</b> —— 与用户截图那一格逐字对上 ✓
         * <br>⇒ {@code (0, 10000)} 时 {@code percent = 0} ⇒ 走"零"那一支
         * ⇒ <b>红字 ＋ {@code gtceu.gui.content.chance_0_short}（lang 实测 = "§c不消耗§r"）</b> ✓
         *
         * <p>⇒ 所以"催化剂"在 GT 的词汇表里本来就<b>是</b>输入侧的那条 {@code Content} 的
         * {@code chance=0}（GT 没有第二个字段表达"不消耗"）；本工程要修的<b>不是</b>换成别的机制，
         * 而是把 {@code maxChance} 补回满值 —— 补上之后 GT 自己就会把它画成红字「不消耗」。
         */
        public void setChanceAndCatalyst(int newChance, int newBoost, boolean catalyst) {
            this.notConsumable = catalyst;
            if (catalyst) {
                this.chance = 0;
                this.maxChance = MAX_CHANCE_VALUE;   // ← 曾经是 0，产出 NaN%
                this.tierChanceBoost = 0;
            } else {
                final int c = Math.max(1, Math.min(MAX_CHANCE_VALUE, newChance));
                this.chance = c;
                this.maxChance = MAX_CHANCE_VALUE;
                this.tierChanceBoost = Math.max(0, Math.min(MAX_CHANCE_VALUE, newBoost));
            }
            this.chanceDirty = true;
            this.dirty = true;
            this.catalystRepair = false;             // 用户自己做的决定 ⇒ 不再是"待修复"
        }

        public boolean empty() {
            return itemKind ? item.isEmpty() : fluid.isEmpty();
        }

        /** 显示用数量（物品 = 个数、流体 = mB）。 */
        public int shownCount() {
            if (itemKind) {
                return item.isEmpty() ? 0 : item.getCount();
            }
            return fluid.isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, fluid.getAmount());
        }

        /**
         * 放一个物品栈进这一格。
         *
         * <h4>🔴🔴 2026-10-05（第 6 轮）删掉了这里的 {@code Math.min(count, maxStackSize)}</h4>
         * 原来那一句把数量压到 {@code item.getMaxStackSize()}（多数物品 = 64）——
         * 那是<b>原版容器</b>的语义，对"配方的一格"是错的：配方那一格真正的载体是
         * {@code SizedIngredient.amount}（{@code int}），跟"一摞最多几个"毫无关系。
         * <p>证据链（三层，全部实测所得）：
         * <ol>
         *   <li>{@code ItemStack.setCount(int)} 本身【不 clamp】（javap {@code m_41764_}：
         *       偏移 0-5 只有 {@code putfield count; return}）⇒ 唯一的那道闸就是我们自己写的；</li>
         *   <li>写回配方走 {@link #itemIngredientOf} → {@code SizedIngredient.create(Ingredient, int)}，
         *       而它也没有 clamp（javap：偏移 0-9 只有 {@code new / invokespecial / areturn}）⇒
         *       把这个数放开之后，它能一路原样进配方 json 的 {@code "count"};</li>
         *   <li>读回走 {@link #representativeItem} → {@code Ingredient.getItems()}
         *       （{@code SizedIngredient} 重写过 {@code getItems}，会把 amount 贴回每个栈）
         *       ⇒ 所以"读回来还是 99999"这条判据在【这条路的另一端】本来就成立。</li>
         * </ol>
         * <p>⚠️ 下限仍然是 1：{@code count <= 0} 的语义是"这一格不存在"（清空），不是"要 0 个"。
         */
        public void setItem(ItemStack stack, int count) {
            if (stack == null || stack.isEmpty() || count <= 0) {
                item = ItemStack.EMPTY;
            } else {
                item = stack.copy();
                // 🔴 只按 int 的可用区间夹，**不按 maxStackSize**（理由见上面整段注释）
                item.setCount(Math.min(MAX_ITEM_COUNT, Math.max(1, count)));
            }
            dirty = true;
        }

        public void setFluid(FluidStack stack) {
            fluid = (stack == null || stack.isEmpty()) ? FluidStack.empty() : stack.copy();
            dirty = true;
        }

        public void clear() {
            item = ItemStack.EMPTY;
            fluid = FluidStack.empty();
            dirty = true;
        }

        public Cell copy() {
            final Cell c = new Cell(itemKind);
            c.original = original;
            c.item = item.copy();
            c.fluid = fluid.isEmpty() ? FluidStack.empty() : fluid.copy();
            c.dirty = dirty;
            c.chance = chance;
            c.maxChance = maxChance;
            c.tierChanceBoost = tierChanceBoost;
            c.notConsumable = notConsumable;
            c.chanceDirty = chanceDirty;
            c.catalystRepair = catalystRepair;
            return c;
        }

        public String describe() {
            if (itemKind) {
                return item.isEmpty() ? "(空)" : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(item.getItem()) + " x" + item.getCount();
            }
            if (fluid.isEmpty()) {
                return "(空)";
            }
            return net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid())
                    + " " + fluid.getAmount() + "mB";
        }
    }

    // ================================================================== 缓冲本体

    /**
     * 🔴 GT 自己的"必出"分母：{@code ChanceLogic.getMaxChancedValue()}（javap 实测返回常量 10000）。
     *
     * <p>催化剂那一支必须写它、<b>绝不能写 0</b> —— 见
     * {@link Cell#setChanceAndCatalyst(int, int, boolean)} 里那段 A6 取证。
     * 放在外层（不是 {@link Cell} 里）是因为自检与 {@link #gtPercent} 都要用它。
     */
    public static final int MAX_CHANCE_VALUE =
            com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic.getMaxChancedValue();

    private final List<Cell> cells = new ArrayList<>();
    private final List<Cell> itemsIn = new ArrayList<>();
    private final List<Cell> fluidsIn = new ArrayList<>();
    private final List<Cell> itemsOut = new ArrayList<>();
    private final List<Cell> fluidsOut = new ArrayList<>();

    private int itemIn;
    private int fluidIn;
    private int itemOut;
    private int fluidOut;

    /** 默认口径（9/3/9/3）。 */
    public ShanhaiIoTable() {
        this(ITEM_IN, FLUID_IN, ITEM_OUT, FLUID_OUT);
    }

    /**
     * <b>按配方类型（与这条配方真实用量）决定的四元组</b>建缓冲。
     *
     * <h4>为什么不是固定的 24 格（用户原话）</h4>
     * 「B3我要求的<b>编辑屏的输入和输出不是一个固定的，而是随着配方类型变化的</b>」
     * 「有时需要满足<b>非常巨大的输入和输出格</b>」。
     * 四元组的来源见 {@link ShanhaiRecipeEditorWorkspace#loadBuffer}：
     * 类型自己的 {@code setMaxIOSize}（经 {@code GTRecipeType.getMaxInputs/getMaxOutputs} 读，
     * 所以原版/别的 mod 的类型也拿得到）× 与这条配方实际用到的条数取大。
     */
    public ShanhaiIoTable(int itemIn, int fluidIn, int itemOut, int fluidOut) {
        this.itemIn = clampSection(itemIn);
        this.fluidIn = clampSection(fluidIn);
        this.itemOut = clampSection(itemOut);
        this.fluidOut = clampSection(fluidOut);
        build();
    }

    private static int clampSection(int n) {
        return Math.max(0, Math.min(MAX_PER_SECTION, n));
    }

    private void build() {
        cells.clear();
        itemsIn.clear();
        fluidsIn.clear();
        itemsOut.clear();
        fluidsOut.clear();
        for (int i = 0; i < itemIn; i++) {
            itemsIn.add(add(true));
        }
        for (int i = 0; i < fluidIn; i++) {
            fluidsIn.add(add(false));
        }
        for (int i = 0; i < itemOut; i++) {
            itemsOut.add(add(true));
        }
        for (int i = 0; i < fluidOut; i++) {
            fluidsOut.add(add(false));
        }
    }

    public int cellCount() {
        return cells.size();
    }

    public int itemIn() {
        return itemIn;
    }

    public int fluidIn() {
        return fluidIn;
    }

    public int itemOut() {
        return itemOut;
    }

    public int fluidOut() {
        return fluidOut;
    }

    /** 输入侧总格数 / 输出侧总格数（分栏分页用）。 */
    public int inSection() {
        return itemIn + fluidIn;
    }

    public int outSection() {
        return itemOut + fluidOut;
    }

    /** 该下标属于哪一区。 */
    public Kind kindOf(int index) {
        if (index < itemIn) {
            return Kind.ITEM_IN;
        }
        if (index < itemIn + fluidIn) {
            return Kind.FLUID_IN;
        }
        if (index < itemIn + fluidIn + itemOut) {
            return Kind.ITEM_OUT;
        }
        return Kind.FLUID_OUT;
    }

    /** 输入栏里的第 i 格在整表里的下标（输入栏 = 物品在前、流体在后）。 */
    public int inIndex(int i) {
        return i;
    }

    /** 输出栏里的第 i 格在整表里的下标。 */
    public int outIndex(int i) {
        return inSection() + i;
    }

    /**
     * 把缓冲改成另一个四元组（<b>就地重建</b>；已有内容能保住的就保住）。
     *
     * <p>⚠️ 重建之后 {@link #cells} 与四个分组列表<b>必须一起换</b>
     * （理由与 {@link #copyFrom} 那条血账相同：两套引用指同一批格子，换一半就会出现
     * "界面看得见、回写读不到"）。
     */
    public void resize(int newItemIn, int newFluidIn, int newItemOut, int newFluidOut) {
        final int ni = clampSection(newItemIn);
        final int nf = clampSection(newFluidIn);
        final int no = clampSection(newItemOut);
        final int nfo = clampSection(newFluidOut);
        if (ni == itemIn && nf == fluidIn && no == itemOut && nfo == fluidOut) {
            return;
        }
        final List<Cell> oldItemsIn = new ArrayList<>(itemsIn);
        final List<Cell> oldFluidsIn = new ArrayList<>(fluidsIn);
        final List<Cell> oldItemsOut = new ArrayList<>(itemsOut);
        final List<Cell> oldFluidsOut = new ArrayList<>(fluidsOut);
        this.itemIn = ni;
        this.fluidIn = nf;
        this.itemOut = no;
        this.fluidOut = nfo;
        build();
        carry(oldItemsIn, itemsIn);
        carry(oldFluidsIn, fluidsIn);
        carry(oldItemsOut, itemsOut);
        carry(oldFluidsOut, fluidsOut);
    }

    private static void carry(List<Cell> from, List<Cell> to) {
        for (int i = 0; i < to.size() && i < from.size(); i++) {
            final Cell src = from.get(i);
            final Cell dst = to.get(i);
            dst.original = src.original;
            dst.item = src.item;
            dst.fluid = src.fluid;
            dst.dirty = src.dirty;
            dst.chance = src.chance;
            dst.maxChance = src.maxChance;
            dst.tierChanceBoost = src.tierChanceBoost;
            dst.notConsumable = src.notConsumable;
            dst.chanceDirty = src.chanceDirty;
        }
    }

    private Cell add(boolean itemKind) {
        final Cell c = new Cell(itemKind);
        cells.add(c);
        return c;
    }

    /** 按下标取格子（面板只认这个下标，两侧一致）。 */
    public Cell cell(int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : null;
    }

    public List<Cell> all() {
        return cells;
    }

    /** 该下标属于哪张表：{@code "inputs"} 或 {@code "outputs"}。 */
    public static String tableOf(int index) {
        return index < ITEM_IN + FLUID_IN ? "inputs" : "outputs";
    }

    // ================================================================== 从配方读

    /** 把一条配方的 inputs/outputs 读进缓冲（未动过 = {@code dirty=false}）。默认四元组。 */
    public static ShanhaiIoTable fromRecipe(GTRecipe recipe) {
        return fromRecipe(recipe, ITEM_IN, FLUID_IN, ITEM_OUT, FLUID_OUT);
    }

    /** 把一条配方的 inputs/outputs 读进缓冲，按给定的四元组开格子（见 {@link #ShanhaiIoTable(int,int,int,int)}）。 */
    public static ShanhaiIoTable fromRecipe(GTRecipe recipe, int itemIn, int fluidIn, int itemOut, int fluidOut) {
        final ShanhaiIoTable t = new ShanhaiIoTable(itemIn, fluidIn, itemOut, fluidOut);
        if (recipe == null) {
            return t;
        }
        fill(t.itemsIn, contentsOf(recipe, "inputs", ItemRecipeCapability.CAP), true, true);
        fill(t.fluidsIn, contentsOf(recipe, "inputs", FluidRecipeCapability.CAP), false, true);
        fill(t.itemsOut, contentsOf(recipe, "outputs", ItemRecipeCapability.CAP), true, false);
        fill(t.fluidsOut, contentsOf(recipe, "outputs", FluidRecipeCapability.CAP), false, false);
        return t;
    }

    /** 一条配方在四个区里各用了多少条（给"按下限开格子"用）。 */
    public static int[] usedBy(GTRecipe recipe) {
        if (recipe == null) {
            return new int[]{0, 0, 0, 0};
        }
        return new int[]{
                contentsOf(recipe, "inputs", ItemRecipeCapability.CAP).size(),
                contentsOf(recipe, "inputs", FluidRecipeCapability.CAP).size(),
                contentsOf(recipe, "outputs", ItemRecipeCapability.CAP).size(),
                contentsOf(recipe, "outputs", FluidRecipeCapability.CAP).size()};
    }

    private static Map<RecipeCapability<?>, List<Content>> table(GTRecipe r, String which) {
        return "inputs".equals(which) ? r.inputs : r.outputs;
    }

    private static List<Content> contentsOf(GTRecipe r, String which, RecipeCapability<?> cap) {
        final Map<RecipeCapability<?>, List<Content>> t = table(r, which);
        if (t == null) {
            return List.of();
        }
        final List<Content> list = t.get(cap);
        return list == null ? List.of() : list;
    }

    private static void fill(List<Cell> target, List<Content> contents, boolean itemKind, boolean inputSide) {
        for (int i = 0; i < target.size(); i++) {
            final Cell c = target.get(i);
            if (i >= contents.size()) {
                continue;
            }
            final Content content = contents.get(i);
            if (content == null) {
                continue;
            }
            c.original = content;
            c.dirty = false;
            c.chance = content.chance;
            c.maxChance = content.maxChance <= 0 ? MAX_CHANCE_VALUE : content.maxChance;
            c.tierChanceBoost = content.tierChanceBoost;
            c.notConsumable = isNotConsumable(content);
            c.chanceDirty = false;
            // 🔴 A6 迁移（2026-10-05）：老版本把催化剂写成 (chance=0, maxChance=0)，GT 会画 NaN%。
            //    只在【输入侧】修 —— 输出侧出现 chance=0 不是"不消耗"的意思（那是 0% 产出），
            //    乱改输出会静默改变配方语义。
            c.catalystRepair = inputSide && isMalformedCatalyst(content);
            if (itemKind) {
                c.item = representativeItem(content);
            } else {
                c.fluid = representativeFluid(content);
            }
        }
    }

    /**
     * 这一条 {@link Content} 是不是<b>催化剂（不消耗）</b>。
     *
     * <p>判据 = GT 自己的语义：<b>{@code chance == 0}</b>。
     * 依据（不是猜的，是 javap）：
     * <ul>
     *   <li>{@code GTRecipeBuilder.notConsumable(...)} 只把 {@code chance} 置 0，
     *       {@code maxChance} 保持 {@code getMaxChancedValue()} = 10000
     *       ⇒ 真实的催化剂 Content 长 {@code (0, 10000)}；</li>
     *   <li>{@code Content.drawChance} 用 {@code percent = 100*chance/maxChance} 判"是不是 0"，
     *       而 {@code chance==0} 正是"不消耗"（红字 {@code §c不消耗§r}）那条分支。</li>
     * </ul>
     *
     * <p>⚠️ <b>2026-10-05 A6 修正</b>：老判据要求 {@code chance==0 && maxChance==0}，
     * 那是本编辑器自己产出的<b>畸形</b>数据（GT 的 JEI 会把它画成 {@code NaN%}），
     * 不是"催化剂"的定义。新判据只认 {@code chance==0}，于是：
     * <ul>
     *   <li>GT 原生的催化剂 {@code (0, 10000)} ⇒ 认出 ✓</li>
     *   <li>老版本写坏的 {@code (0, 0)} ⇒ <b>也</b>认出（并在 {@code fill} 里标成待修复）✓</li>
     * </ul>
     */
    public static boolean isNotConsumable(Content content) {
        return content != null && content.chance == 0;
    }

    /** 这条 {@link Content} 是不是本编辑器老版本写坏的那种（{@code chance=0} 但 {@code maxChance<=0}）。 */
    public static boolean isMalformedCatalyst(Content content) {
        return content != null && content.chance == 0 && content.maxChance <= 0;
    }

    /**
     * 🆕 2026-10-05 第 11 刀：<b>这个物品栈上写的是第几号编程电路</b>。
     *
     * <h2>为什么需要它</h2>
     * 用户原话（逐字）：「<b>这个编程电路的显示有问题，它没有具体显示几号电路</b>」。
     * 卡片那一格原来只存了「物品 id ＋ 数量」，<b>NBT 在造卡片那一步就被丢了</b>
     * （{@link ShanhaiRecipeQuery.Chip} 的旧版只有三个字段）⇒ 电路号根本传不到客户端。
     * 本方法在<b>服务端</b>把编号取出来，随 chip 一起推下去。
     *
     * <h2>编号从哪读（javap 实测，不是猜的）</h2>
     * {@code javap -p -c com.gregtechceu.gtceu.common.item.IntCircuitBehaviour}：
     * <pre>
     *   public static int getCircuitConfiguration(ItemStack);
     *     0: aload_0
     *     1: invokestatic  isIntegratedCircuit:(Lnet/minecraft/world/item/ItemStack;)Z
     *     4: ifne 9   7: iconst_0   8: ireturn        // 不是编程电路 ⇒ 恒 0
     *     9: aload_0  10: invokevirtual ItemStack.getTag:()Lnet/minecraft/nbt/CompoundTag;
     *    14: ifnull 25
     *    18: aload_1  19: ldc "Configuration"          // ← NBT 键就叫这个
     *    21: invokevirtual CompoundTag.getInt:(Ljava/lang/String;)I   24: ireturn
     *    25: iconst_0  26: ireturn                     // 没有 NBT ⇒ 0
     * </pre>
     * ⚠️ <b>{@code getCircuitConfiguration} 自己分不清"电路 0"与"压根不是电路/没有编号"</b>
     * （两者都返回 0）⇒ 本方法先自己把这两种情形挑出来：
     * <ol>
     *   <li>不是 {@code gtceu:integrated_circuit} 这个物品 ⇒ {@code -1}（没编号）；</li>
     *   <li>是这个物品、但 NBT 里<b>没有</b> {@code Configuration} 键 ⇒ {@code -1}（没编号）；</li>
     *   <li>有那个键 ⇒ 原样交出编号（<b>包括 0</b> —— 电路 0 是合法的，卡片上要显示 0）。</li>
     * </ol>
     * 三种情形在卡片上的差别是可见的：只有第 3 种会画角标（见 {@code ShanhaiRecipeCardWidget}）。
     *
     * @return 电路号（0..32）；{@code -1} = 这一格没有电路号（调用方<b>不要</b>画角标）
     */
    public static int circuitOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        try {
            if (!IntCircuitBehaviour.isIntegratedCircuit(stack)) {
                return -1;
            }
            final CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains("Configuration")) {
                return -1;
            }
            return IntCircuitBehaviour.getCircuitConfiguration(stack);
        } catch (Throwable t) {
            // 读不出来 ⇒ 当作"没有编号"（宁可少画一个角标，也不画一个错的号）
            return -1;
        }
    }

    /**
     * <b>逐字节复刻 GT 画那一行概率字的算式</b>（A6 的判据用它）。
     *
     * <p>出处：{@code javap -c com.gregtechceu.gtceu.api.recipe.content.Content} 的
     * {@code drawChance} 偏移 41–55：
     * <pre>
     *   41: ldc 100.0f
     *   44: getfield chance:I     48: i2f     49: fmul
     *   50: getfield maxChance:I  54: i2f     55: fdiv
     * </pre>
     * ⇒ {@code percent = 100f * (float) chance / (float) maxChance}。
     * 之后 {@code if (percent == 0)} 选红字 {@code 不消耗} / 黄字百分数 —— 而 {@code NaN == 0} 为 <b>false</b>，
     * 所以 {@code (0, 0)} 会掉进"黄字百分数"那一支并被 {@code FormattingUtil.formatPercent(NaN)} 印成 {@code NaN%}。
     */
    public static float gtPercent(int chance, int maxChance) {
        return 100.0f * (float) chance / (float) maxChance;
    }

    /**
     * 一个物品 {@link Content} 的代表性物品栈（<b>只用于显示与拖动的起点</b>）。
     * 读不出来就返回空 —— 界面显示空格子，但 {@code original} 仍在，保存时原样放回。
     */
    public static ItemStack representativeItem(Content content) {
        if (content == null) {
            return ItemStack.EMPTY;
        }
        final Object raw = content.getContent();
        if (raw instanceof ItemStack stack) {
            return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        }
        try {
            final Ingredient ing = ItemRecipeCapability.CAP.of(raw);
            if (ing != null) {
                final ItemStack[] stacks = ing.getItems();
                if (stacks != null && stacks.length > 0 && !stacks[0].isEmpty()) {
                    return stacks[0].copy();
                }
            }
        } catch (Throwable ignored) {
            // 单个 content 读不出来 ⇒ 返回空（不许因此整条链失败）
        }
        return ItemStack.EMPTY;
    }

    /** 一个流体 {@link Content} 的代表性流体栈（同 {@link #representativeItem} 的口径）。 */
    public static FluidStack representativeFluid(Content content) {
        if (content == null) {
            return FluidStack.empty();
        }
        final Object raw = content.getContent();
        if (raw instanceof FluidStack stack) {
            return stack.isEmpty() ? FluidStack.empty() : stack.copy();
        }
        try {
            final FluidIngredient ing = FluidRecipeCapability.CAP.of(raw);
            if (ing != null) {
                final FluidStack[] stacks = ing.getStacks();
                if (stacks != null && stacks.length > 0 && !stacks[0].isEmpty()) {
                    return stacks[0].copy();
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return FluidStack.empty();
    }

    // ================================================================== 回写

    /**
     * 把一张表拼回 GT 形状的 JSON（{@code {"item":[…],"fluid":[…]}}）。
     *
     * <p>每个格子出<b>一个</b> JSON 元素：没动过的格子出它原本那条 {@code Content} 的编码
     * （＝语义不变），动过的格子出按用户改的值重建的那一条。
     *
     * @param which {@code "inputs"} / {@code "outputs"}
     */
    public JsonObject json(String which) {
        final boolean inputs = "inputs".equals(which);
        final List<Cell> items = inputs ? itemsIn : itemsOut;
        final List<Cell> fluids = inputs ? fluidsIn : fluidsOut;

        final JsonObject out = new JsonObject();
        out.add("item", arrayOf(items, ItemRecipeCapability.CAP));
        out.add("fluid", arrayOf(fluids, FluidRecipeCapability.CAP));

        for (Cell c : allDirty(items, fluids)) {
            ShanhaiMod.LOGGER.info("{} io_cell_edited table={} was={} now={} "
                            + "(他本人动过这个格子 ⇒ 回写时按现在显示的值重建；chance 沿用原来那条)",
                    PREFIX, which, shortOf(c.original), c.describe());
        }
        return out;
    }

    /** 这张表里被他动过的格子（只为日志）。 */
    private static List<Cell> allDirty(List<Cell> items, List<Cell> fluids) {
        final List<Cell> out = new ArrayList<>();
        for (Cell c : items) {
            if (c.dirty || c.chanceDirty) {
                out.add(c);
            }
        }
        for (Cell c : fluids) {
            if (c.dirty || c.chanceDirty) {
                out.add(c);
            }
        }
        return out;
    }

    private JsonArray arrayOf(List<Cell> cells, RecipeCapability<?> cap) {
        final JsonArray arr = new JsonArray();
        for (Cell c : cells) {
            if (c.empty()) {
                continue;       // 空格子 = 这一条不存在（GT 的"空"就是不在数组里）
            }
            final Content content = contentFor(c, cap);
            if (content == null) {
                continue;
            }
            // 形状一律由 GT 自己的 codec 产生（这一条与 setDuration/setIo 那条链用的是同一个出口）
            final JsonElement el = ShanhaiRecipeIoApply.encodeContent(cap, content);
            if (el != null) {
                arr.add(el);
            }
        }
        return arr;
    }

    private static String shortOf(Content c) {
        return c == null ? "(none)" : String.valueOf(c.getContent()).substring(0, Math.min(60,
                String.valueOf(c.getContent()).length()));
    }

    // ================================================================== 两侧同步

    /** 把格子的"显示内容"写给客户端（面板上一次同步里的一小段）。 */
    public void writeState(FriendlyByteBuf buf) {
        // 🔴 先写四元组：客户端那份是【按类型重建】的（格子数随类型变化），
        //    不把形状送过去，两侧的下标就会错位（拖第 9 格改到第 12 格那类事故）。
        buf.writeVarInt(itemIn);
        buf.writeVarInt(fluidIn);
        buf.writeVarInt(itemOut);
        buf.writeVarInt(fluidOut);
        buf.writeVarInt(cells.size());
        for (Cell c : cells) {
            buf.writeBoolean(c.dirty);
            // 🆕 概率三件套 + 催化剂：界面要标"10.00%"与"不消耗"，两侧必须一致。
            buf.writeVarInt(c.chance);
            buf.writeVarInt(c.maxChance);
            buf.writeVarInt(c.tierChanceBoost);
            buf.writeBoolean(c.notConsumable);
            buf.writeBoolean(c.chanceDirty);
            if (c.itemKind) {
                writeItem(buf, c.item);
            } else {
                writeFluid(buf, c.fluid);
            }
        }
    }

    /** 读回（客户端那份 session 用）：先按形状重建，再逐格读。 */
    public void readState(FriendlyByteBuf buf) {
        final int ii = buf.readVarInt();
        final int fi = buf.readVarInt();
        final int io = buf.readVarInt();
        final int fo = buf.readVarInt();
        resize(ii, fi, io, fo);
        final int n = buf.readVarInt();
        for (int i = 0; i < cells.size(); i++) {
            final Cell c = cells.get(i);
            if (i < n) {
                c.dirty = buf.readBoolean();
                c.chance = buf.readVarInt();
                c.maxChance = buf.readVarInt();
                c.tierChanceBoost = buf.readVarInt();
                c.notConsumable = buf.readBoolean();
                c.chanceDirty = buf.readBoolean();
                if (c.itemKind) {
                    c.item = readItem(buf);
                } else {
                    c.fluid = readFluid(buf);
                }
            }
        }
    }

    /**
     * 🔴 2026-10-05（第 6 轮）：物品栈的<b>数量单走一个 VarInt</b>，不再依赖 {@code writeItem}。
     *
     * <h4>为什么必须这么写（javap 实证，不是保险起见）</h4>
     * {@code FriendlyByteBuf.writeItem(stack)} → {@code writeItemStack(stack, false)}，而它的字节码是：
     * <pre>
     *   35: aload_0
     *   36: aload_1
     *   37: invokevirtual  ItemStack.m_41613_()   // getCount()
     *   40: invokevirtual  FriendlyByteBuf.writeByte(I)   ← 🔴 一个 byte！
     * </pre>
     * 读回来那一侧 {@code readItem()} 也是 {@code readByte()} 然后
     * {@code new ItemStack(item, (byte) n)}。
     * ⇒ <b>数量 ≥ 128 时这条通道会把数量截断</b>（1024 → 1024 &amp; 0xFF = 0 ⇒ 客户端那一格直接变成空格）；
     * 而本轮刚好把"一格里能填多大"从 64 放开到 {@code Integer.MAX_VALUE}，
     * 不修这一条的话：<b>服务端存对了、客户端显示错</b> —— 本工程最怕的那类静默不一致。
     *
     * <h4>做法：身份照旧走 writeItem，数量【再补一个 VarInt】</h4>
     * 送出去的栈先把 count 压成 1（避开那个 byte 的锅），随后把真实数量单独写一遍；
     * 读回时补上。两侧在<b>同一个类、同一个 jar</b> 里 ⇒ 协议一起改，没有跨版本兼容面。
     */
    private static void writeItem(FriendlyByteBuf buf, ItemStack stack) {
        final boolean has = stack != null && !stack.isEmpty();
        buf.writeBoolean(has);
        if (has) {
            final ItemStack identity = stack.copy();
            identity.setCount(1);        // 🔴 只借这条通道送"物品 id + NBT"，数量由下一行走
            buf.writeItem(identity);
            buf.writeVarInt(stack.getCount());
        }
    }

    private static ItemStack readItem(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stack = buf.readItem();      // count 恒为 1
        final int count = buf.readVarInt();          // 🔴 真正的数量（可远大于 64／127）
        if (!stack.isEmpty() && count > 0) {
            stack.setCount(count);
        }
        return stack;
    }

    private static void writeFluid(FriendlyByteBuf buf, FluidStack stack) {
        final boolean has = stack != null && !stack.isEmpty();
        buf.writeBoolean(has);
        if (has) {
            stack.writeToBuf(buf);
        }
    }

    private static FluidStack readFluid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? FluidStack.readFromBuf(buf) : FluidStack.empty();
    }

    // ================================================================== 读数（日志/自检）

    /**
     * 一格子的 Content（未动过 ⇒ 原样原文；动过 ⇒ 按改后的值重建）。
     *
     * <p>🆕 2026-10-05（用户点单 B6/B7）：即便"材料"没动，只要<b>概率/递增/催化剂</b>被改过
     * （{@code chanceDirty}），这一条也要重建 —— 否则用户在界面上把概率改成 10%，
     * 保存后配方还是 100%，而日志上一切正常（本工程最怕的那类静默失败）。
     */
    public Content contentFor(Cell c, RecipeCapability<?> cap) {
        if (!c.dirty && !c.chanceDirty && !c.catalystRepair && c.original != null) {
            return c.original;
        }
        if (c.empty() && c.original == null) {
            return null;
        }
        final Object ingredient;
        if (c.original != null && !c.dirty) {
            // 只改了概率/催化剂 ⇒ 材料那一部分仍然用原文（标签、电路号、NBT 全不动）
            ingredient = c.original.content;
        } else {
            if (c.empty()) {
                return null;
            }
            if (c.itemKind) {
                ingredient = itemIngredientOf(c.item);
            } else {
                ingredient = FluidIngredient.of(new FluidStack[]{c.fluid.copy()});
            }
            if (ingredient == null) {
                return null;
            }
        }
        final Content ref = c.original;
        if (c.chanceDirty || c.catalystRepair) {
            return new Content(ingredient, c.chance, c.maxChance, c.tierChanceBoost,
                    ref == null ? null : ref.slotName,
                    ref == null ? null : ref.uiName);
        }
        return new Content(ingredient,
                ref == null ? 10000 : ref.chance,
                ref == null ? 10000 : ref.maxChance,
                ref == null ? 0 : ref.tierChanceBoost,
                ref == null ? null : ref.slotName,
                ref == null ? null : ref.uiName);
    }

    /**
     * 一个具体物品栈 ⇒ 一个 GT 物品原料。
     *
     * <p>⚠️ 这里用的是<b>第二刀运行期验过</b>的那个形状：{@code SizedIngredient} 由
     * {@code Ingredient.of(stack)}（即 vanilla 的 ItemStack Ingredient）再套 count。
     */
    private static Object itemIngredientOf(ItemStack stack) {
        try {
            return com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient
                    .create(Ingredient.of(stack), stack.getCount());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} io_cell_sized_ingredient_failed item={} err={}",
                    PREFIX, stack, t.toString());
            return null;
        }
    }

    /** 一张表里有几个非空格子（日志读数）。 */
    public int count(String which) {
        final boolean inputs = "inputs".equals(which);
        int n = 0;
        for (Cell c : (inputs ? itemsIn : itemsOut)) {
            if (!c.empty()) {
                n++;
            }
        }
        for (Cell c : (inputs ? fluidsIn : fluidsOut)) {
            if (!c.empty()) {
                n++;
            }
        }
        return n;
    }

    public int dirtyCount() {
        int n = 0;
        for (Cell c : cells) {
            if (c.dirty || c.chanceDirty) {
                n++;
            }
        }
        return n;
    }

    /** 有没有任何一个格子动过（没有 ⇒ 保存时根本不必走那条后端链）。 */
    public boolean anyDirty() {
        return dirtyCount() > 0;
    }

    /**
     * 缓冲里有没有"老数据待修复"的格子（A6 迁移用）。
     *
     * <p>与 {@link #anyDirty()} <b>刻意分开</b>：用户什么都没改时 {@code anyDirty()} 必须是 false
     * （既有判据 {@code WS_UNTOUCHED_ON_LOAD} 就认这一条，它保证"打开看一眼再保存不会动配方"），
     * 但那种情况下<b>仍然应该允许</b>把写坏的催化剂修回去。
     */
    public boolean anyCatalystRepair() {
        for (Cell c : cells) {
            if (c.catalystRepair) {
                return true;
            }
        }
        return false;
    }

    /** 待修复的格子数（日志/自检用）。 */
    public int catalystRepairCount() {
        int n = 0;
        for (Cell c : cells) {
            if (c.catalystRepair) {
                n++;
            }
        }
        return n;
    }

    /** 给日志/自检用的一行摘要。 */
    public String summary() {
        return "itemIn=" + count("inputs") + " fluidIn=" + count("inputs") + " out=" + count("outputs")
                + " cells=" + cellCount() + " shape=" + itemIn + "/" + fluidIn + "/" + itemOut + "/" + fluidOut
                + " dirty=" + dirtyCount();
    }

    /** 复制一份（自检要拿"原始 vs 编辑后"两份对比；同样是就地搬，见 {@link #copyFrom}）。 */
    public ShanhaiIoTable copy() {
        final ShanhaiIoTable t = new ShanhaiIoTable();
        t.copyFrom(this);
        return t;
    }

    /** 24 个格子的 dirty 位图（自检/日志用：{@code 1000…} 这种 24 位串）。 */
    public String dirtyMask() {
        final StringBuilder sb = new StringBuilder(cells.size());
        for (Cell c : cells) {
            sb.append(c.dirty ? '1' : '0');
        }
        return sb.toString();
    }

    /** 24 个格子的"空/非空"位图（自检/日志用）。 */
    public String emptyMask() {
        final StringBuilder sb = new StringBuilder(cells.size());
        for (Cell c : cells) {
            sb.append(c.empty() ? '0' : '1');
        }
        return sb.toString();
    }

    /**
     * 把另一份缓冲的 24 个格子整体搬进来（面板"载入一条配方"走这条）。
     *
     * <h4>🔴 必须【就地改】现有那 24 个 Cell，不许 {@code cells.set(i, 新的)}</h4>
     * 本类内部有<b>两套引用</b>指向同一批格子：{@link #cells}（下标序，面板与两侧同步都用它）
     * 与 {@link #itemsIn}/{@link #fluidsIn}/{@link #itemsOut}/{@link #fluidsOut}
     * （按表分组，回写 JSON 用它）。如果把 {@code cells} 里的元素<b>换成新对象</b>，
     * 四个分组列表就还抱着<b>旧的那批</b> ⇒
     * <pre>
     *   拖进格子   → 改的是新对象（cells 里那份）⇒ dirtyCount 看得见
     *   回写 JSON  → 读的是旧对象（分组列表里那份）⇒ 读出来是空的
     * </pre>
     * 表现就是"界面显示拖进去了、点保存却什么都没发生，日志上只有一条 inputs_json_len=22"。
     * <b>2026-10-05 冒烟真机抓到过这一次</b>（{@code WS_GESTURE accepted=true … json_has_probe=false}
     * ＋ 一行 {@code io_cell_edited} 都没打）⇒ 就地改是唯一正确的写法。
     */
    public void copyFrom(ShanhaiIoTable other) {
        if (other == null || other.cells.size() != cells.size()) {
            return;
        }
        for (int i = 0; i < cells.size(); i++) {
            final Cell src = other.cells.get(i);
            final Cell dst = cells.get(i);
            dst.original = src.original;
            dst.item = src.item.isEmpty() ? ItemStack.EMPTY : src.item.copy();
            dst.fluid = src.fluid.isEmpty() ? FluidStack.empty() : src.fluid.copy();
            dst.dirty = src.dirty;
            dst.chance = src.chance;
            dst.maxChance = src.maxChance;
            dst.tierChanceBoost = src.tierChanceBoost;
            dst.notConsumable = src.notConsumable;
            dst.chanceDirty = src.chanceDirty;
        }
    }

    /** 把 24 个格子清空成"全空、未动过"（同样是就地改，理由见 {@link #copyFrom}）。 */
    /** 把 24 个格子清空成"全空、未动过"（同样是就地改，理由见 {@link #copyFrom}）。 */
    public void clearAll() {
        for (Cell c : cells) {
            c.original = null;
            c.item = ItemStack.EMPTY;
            c.fluid = FluidStack.empty();
            c.dirty = false;
            c.chance = 10000;
            c.maxChance = 10000;
            c.tierChanceBoost = 0;
            c.notConsumable = false;
            c.chanceDirty = false;
        }
    }

    /** 该表里有几个物品格子非空。 */
    public int countItem(String which) {
        return countKind("inputs".equals(which) ? itemsIn : itemsOut);
    }

    /** 该表里有几个流体格子非空。 */
    public int countFluid(String which) {
        return countKind("inputs".equals(which) ? fluidsIn : fluidsOut);
    }

    private static int countKind(List<Cell> list) {
        int n = 0;
        for (Cell c : list) {
            if (!c.empty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 自检用：把一条 {@link Content} 按 GT 的形状编成 JSON、再<b>解码回来</b>。
     *
     * <p>为什么必须"解码回来"而不是"看字符串里有没有某个键"：覆盖层下一局做的事就是
     * {@code GT 的 CapabilityMapComponent.read}（同一个 codec）解析我们写出去的那份 JSON。
     * 只有解码回来的对象才对得上"用户下一局看到的东西"。字符串断言会把
     * "codec 省略了默认值"误判成失败（本文件里 C4 的第一版就是这么判错的）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Content decodeBack(Content c) {
        try {
            final JsonElement el = ShanhaiRecipeIoApply.encodeContent(ItemRecipeCapability.CAP, c);
            if (el == null) {
                return null;
            }
            final com.mojang.serialization.DataResult<Content> dr =
                    ((com.mojang.serialization.Codec<Content>) Content.codec(ItemRecipeCapability.CAP))
                            .parse(com.mojang.serialization.JsonOps.INSTANCE, el);
            return dr.result().orElse(null);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} IO_CHANCE_SELFCHECK decode_back_threw: {}", PREFIX, t.toString());
            return null;
        }
    }

    /** NBT 快照（自检里判"没动过的格子原样带回"用：比两个 NBT 相等）。 */    public CompoundTag debugTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("dirty", dirtyCount());
        tag.putString("blocks", ioDigest());
        return tag;
    }

    private String ioDigest() {
        final StringBuilder sb = new StringBuilder();
        for (Cell c : cells) {
            sb.append(c.empty() ? "-" : c.describe()).append('|');
        }
        return sb.toString();
    }

    // ================================================================== 🆕 自检（纯内存）

    /**
     * <b>B6/B7 那条链的机器判据</b>：催化剂 / 概率 / 随电压递增 真的会进 JSON 吗？
     *
     * <h4>为什么必须有它</h4>
     * 用户点单的三件事（「可以设置物品是否作为催化剂」「产出的概率（那些随着电压等级递增
     * 什么的都要可以改）」）全部落在"界面改了 → 写进 Content → 编码成 JSON → 覆盖层下一局重放"
     * 这条链上。<b>这条链错了的表现是"界面上改了、保存后什么都没变，日志全绿"</b>——
     * 本工程最怕的那一类静默失败。⇒ 这里用纯内存的一拍把它变成可判读的数字。
     *
     * <h4>跑法</h4>
     * 由 {@link ShanhaiRecipeEditorWorkspaceCheck#run} 在 {@code SHANHAI_EDITOR=1} 时调用
     * （不需要客户端、不需要玩家、不需要写盘）。
     *
     * <h4>判据（每一条都有正/负对照）</h4>
     * <pre>
     *   C1 概率 50%  ⇒ chanceText()=="50.00%"             （界面上那个黄字）
     *   C2 概率 50%  ⇒ contentFor(...).chance==5000 且 maxChance==10000
     *   C3 递增      ⇒ contentFor(...).tierChanceBoost==1000
     *   C4 落盘 JSON ⇒ 含 "chance":5000 与 "tierChanceBoost":1000（GT codec 出的形状）
     *   C5 催化剂    ⇒ chanceText()=="" 且 contentFor(...) 被判 isNotConsumable
     *   C6 催化剂 JSON ⇒ 含 "chance":0 与 "maxChance":0
     *   C7 负对照：<b>没动过的格子</b> contentFor 必须返回<b>原来那个 Content 对象本身</b>
     *      （=== 引用相等）⇒ 证明"改一格概率"不会顺手把别的格子的语义重写掉
     * </pre>
     */
    public static void selfcheckChanceCatalyst() {
        int pass = 0;
        int fail = 0;
        try {
            final ShanhaiIoTable t = new ShanhaiIoTable(1, 0, 1, 0);
            final Cell in0 = t.cell(0);
            final Cell out0 = t.cell(1);
            if (in0 == null || out0 == null) {
                ShanhaiMod.LOGGER.error("{} IO_CHANCE_SELFCHECK ABORT reason=cells_not_built cells={}",
                        PREFIX, t.cellCount());
                return;
            }
            in0.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT), 3);
            out0.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND), 2);

            // ── C1/C2/C3 概率 50% ＋ 递增 10% ──
            out0.setChanceAndCatalyst(5000, 1000, false);
            final boolean c1 = "50.00%".equals(out0.chanceText());
            final Content cs = t.contentFor(out0, ItemRecipeCapability.CAP);
            final boolean c2 = cs != null && cs.chance == 5000 && cs.maxChance == 10000;
            final boolean c3 = cs != null && cs.tierChanceBoost == 1000;
            final String js = t.json("outputs").toString();
            // 🔴 C4 的判据第一版是错的（记下来免得下次再判错）：它断言 JSON 里必须出现
            //    "maxChance":10000。实测 encoder 出的形状是
            //      {"item":[{"tierChanceBoost":1000,"content":{…},"chance":5000}],"fluid":[]}
            //    —— GT 的 Content.codec 在 maxChance 取默认值（10000）时【不写这个键】。
            //    ⇒ 字符串断言写错了判据（检查器自己的假设错，不是产物错）。
            //    正确判据 = 【解码回来】（这正是覆盖层下一局做的事：GT 的 CapabilityMapComponent.read
            //    拿同一个 codec 解析我们写出去的 JSON）⇒ 三个数必须一个不少地回来。
            final Content rt = decodeBack(cs);
            final boolean c4 = rt != null && rt.chance == 5000 && rt.maxChance == 10000
                    && rt.tierChanceBoost == 1000;
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C1 text={} PASS={}", PREFIX, out0.chanceText(), c1);
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C2 chance={} maxChance={} PASS={}",
                    PREFIX, cs == null ? -1 : cs.chance, cs == null ? -1 : cs.maxChance, c2);
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C3 tierChanceBoost={} PASS={}",
                    PREFIX, cs == null ? -1 : cs.tierChanceBoost, c3);
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C4 raw={} decoded=chance:{} maxChance:{} boost:{} PASS={}",
                    PREFIX, js,
                    rt == null ? "<decode-failed>" : String.valueOf(rt.chance),
                    rt == null ? "-" : String.valueOf(rt.maxChance),
                    rt == null ? "-" : String.valueOf(rt.tierChanceBoost), c4);

            // ── C5/C6 催化剂（不消耗）──
            // 🔴 A6 修正（2026-10-05）：期望值从 (0, 0) 改成 GT 原生的 (0, 10000)。
            out0.setChanceAndCatalyst(0, 0, true);
            final Content cc = t.contentFor(out0, ItemRecipeCapability.CAP);
            final boolean c5 = out0.chanceText().isEmpty() && isNotConsumable(cc) && out0.notConsumable;
            final String js2 = t.json("outputs").toString();
            final Content rt2 = decodeBack(cc);
            final boolean c6 = rt2 != null && rt2.chance == 0
                    && rt2.maxChance == MAX_CHANCE_VALUE && isNotConsumable(rt2);
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C5 catalyst={} chanceText='{}' PASS={}",
                    PREFIX, out0.notConsumable, out0.chanceText(), c5);
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C6 raw={} decoded=chance:{} maxChance:{} (期望 0/{}) PASS={}",
                    PREFIX, js2,
                    rt2 == null ? "<decode-failed>" : String.valueOf(rt2.chance),
                    rt2 == null ? "-" : String.valueOf(rt2.maxChance), MAX_CHANCE_VALUE, c6);

            // ── C8（🆕 A6 的核心判据）：把 GT 画那行字的算式原样复刻，正/负对照一起给 ──
            //   算式出处：javap com.gregtechceu.gtceu.api.recipe.content.Content#drawChance
            //             偏移 41–55：percent = 100f * (float)chance / (float)maxChance
            //   判据：修正后的 (0,10000) 必须是【有限且为 0】（⇒ GT 走"§c不消耗§r"那一支）；
            //         老数据 (0,0)         必须是【NaN】（⇒ 证明这个检查器真看得见用户截图那个病）。
            final float pctFixed = gtPercent(0, MAX_CHANCE_VALUE);
            final float pctBroken = gtPercent(0, 0);
            final boolean c8 = (pctFixed == 0.0f) && !Float.isNaN(pctFixed) && Float.isNaN(pctBroken);
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C8 gt_percent(修正 0/{})={} gt_percent(老 0/0)={} PASS={} "
                            + "（NaN==0 为 false ⇒ 老数据会被 GT 画成黄字 NaN%；修正后是 0 ⇒ 红字「不消耗」）",
                    PREFIX, MAX_CHANCE_VALUE, pctFixed, pctBroken, c8);

            // ── C9（🆕 A6 迁移）：老数据的识别与就地修复 ──
            final Content legacy = new Content(
                    com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient.create(
                            net.minecraft.world.item.crafting.Ingredient.of(
                                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT)), 1),
                    0, 0, 0, null, null);
            final boolean recognized = isMalformedCatalyst(legacy) && isNotConsumable(legacy)
                    && !isMalformedCatalyst(new Content(legacy.content, 0, MAX_CHANCE_VALUE, 0, null, null));
            final GTRecipe legacyRecipe = null;              // 手工装一格，不需要真配方
            final ShanhaiIoTable t3 = new ShanhaiIoTable(1, 0, 0, 0);
            t3.cell(0).original = legacy;
            t3.cell(0).item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT);
            t3.cell(0).dirty = false;
            t3.cell(0).chanceDirty = false;
            // 🔴 这里必须把 fill() 读进来之后的那两个字段也照做（第一版漏了 ⇒ 判据假红）：
            //    fill() 对老数据会写 chance=0 / maxChance=10000，而 new Cell() 的默认是 10000/10000。
            //    只置 catalystRepair 而不动这两个数，等于测了一个生产上不存在的状态。
            t3.cell(0).chance = 0;
            t3.cell(0).maxChance = MAX_CHANCE_VALUE;
            t3.cell(0).catalystRepair = true;                // fill() 在输入侧遇到老数据就会置这一位
            final Content repaired = t3.contentFor(t3.cell(0), ItemRecipeCapability.CAP);
            final boolean c9 = recognized && repaired != null
                    && repaired.chance == 0 && repaired.maxChance == MAX_CHANCE_VALUE;
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C9 老数据被认出={} 修复后=chance:{} maxChance:{} PASS={} "
                            + "（迁移路径 = 打开那条配方点一次「保存这条」；本项只证明读进来那一刻已经修好）",
                    PREFIX, recognized,
                    repaired == null ? "-" : String.valueOf(repaired.chance),
                    repaired == null ? "-" : String.valueOf(repaired.maxChance), c9);
            if (legacyRecipe != null) {
                ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK legacy_recipe_unexpected", PREFIX);
            }

            // ── C7 负对照：没动过的格子必须原样返回（引用相等）──
            final ShanhaiIoTable t2 = new ShanhaiIoTable(1, 0, 1, 0);
            final GTRecipe probe = null;                    // fromRecipe 才需要 recipe，这里手工造
            final Content untouched = new Content(
                    com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient.create(
                            net.minecraft.world.item.crafting.Ingredient.of(
                                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)), 4),
                    10000, 10000, 0, null, null);
            final Cell raw = t2.cell(1);
            raw.original = untouched;
            raw.item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 4);
            raw.dirty = false;
            raw.chanceDirty = false;
            final Content back = t2.contentFor(raw, ItemRecipeCapability.CAP);
            final boolean c7 = back == untouched;
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK C7 untouched_content_identical={} PASS={} "
                            + "(负对照：没动过的格子必须把原来那条 Content 原样带回)",
                    PREFIX, c7, c7);
            // 这一句只是让 javac 不因未使用变量报警（也顺手说明了这条不需要配方对象）
            if (probe != null) {
                ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK probe_unexpected", PREFIX);
            }

            final boolean[] all = {c1, c2, c3, c4, c5, c6, c7, c8, c9};
            final StringBuilder failed = new StringBuilder();
            for (int i = 0; i < all.length; i++) {
                if (all[i]) {
                    pass++;
                } else {
                    fail++;
                    failed.append("C").append(i + 1).append(' ');
                }
            }
            ShanhaiMod.LOGGER.info("{} IO_CHANCE_SELFCHECK_DONE pass={} fail={} failed={}",
                    PREFIX, pass, fail, fail == 0 ? "(none)" : failed.toString().trim());
            if (fail > 0) {
                ShanhaiMod.LOGGER.error("{} IO_CHANCE_SELFCHECK FAILED {} 条判据不过 ⇒ "
                        + "界面上改的概率/催化剂很可能写不进配方（这是静默失败，必须修）", PREFIX, fail);
            }
        } catch (Throwable th) {
            ShanhaiMod.LOGGER.error("{} IO_CHANCE_SELFCHECK_CRASHED (server keeps running): {}",
                    PREFIX, th.toString(), th);
        }
    }

    /**
     * 🔴 2026-10-05（第 6 轮）<b>「一格能填多大」那条链的机器判据</b>。
     *
     * <h4>它判的是用户报的那件事</h4>
     * 用户原话：「他这个不让我输入超过64的数字啊，实际上机器可以接受超过64个的啊」。
     * ⇒ 从"界面输入"到"读回来"整条链上，任何一层把数压回 64 都会让这条自检变红。
     *
     * <h4>判据（四条正对照 ＋ 三条负对照，全部可 grep）</h4>
     * <pre>
     *   IC1 缓冲      setItem(dirt, 99999)            ⇒ shownCount()==99999
     *   IC2 落盘+读回  json → GT 的 Content.codec 解码 → representativeItem() ⇒ 仍是 99999
     *                  （这一拍同时证明"写出去的那份"和"读回来的那份"是同一个数）
     *   IC3 同步      writeState → readState（真 FriendlyByteBuf 往返）⇒ 仍是 99999
     *                  🔴 这一拍是本轮【新发现】的那道闸：FriendlyByteBuf.writeItem 里是
     *                     writeByte(getCount())（javap 实证），≥128 会在客户端被截断
     *   IC4 上限      Integer.MAX_VALUE 能原样存住；且 MAX_ITEM_COUNT == Integer.MAX_VALUE
     *   IC5 负对照    99999 != 64（旧口径会把它压成 64 ⇒ 证明 IC1 不是恒真）
     *   IC6 负对照    旧公式现算一次 = min(99999, maxStackSize)，必须 == 64
     *                  （判据本身"能不能看见旧行为"的证明；这一条挂了说明检查器写错了）
     *   IC7 负对照    setItem(dirt, 0) ⇒ 这一格变【空】（0 的语义是"没有这一条"，不是"要 0 个"）
     * </pre>
     *
     * <p>跑法：由 {@link ShanhaiRecipeEditorWorkspaceCheck#run} 在 {@code SHANHAI_EDITOR=1} 时调用。
     * <b>不需要客户端、不需要玩家、不写盘</b> —— 红线禁止开客户端，所以"能不能一路填到 2147483647"
     * 只能这样验。
     */
    public static void selfcheckItemCountAbove64() {
        int pass = 0;
        int fail = 0;
        try {
            final int want = 99999;                       // 用户截图里敲的那个数
            final ShanhaiIoTable t = new ShanhaiIoTable(1, 0, 1, 0);
            final Cell c0 = t.cell(0);
            if (c0 == null) {
                ShanhaiMod.LOGGER.error("{} IO_ITEMCOUNT_SELFCHECK ABORT reason=cells_not_built", PREFIX);
                return;
            }
            final net.minecraft.world.item.ItemStack probe =
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT);
            final int maxStack = probe.getMaxStackSize();
            final int oldFormula = Math.min(want, maxStack <= 0 ? 64 : maxStack);   // 改动前那一句

            // ── IC1 缓冲 ──
            c0.setItem(probe, want);
            final boolean ic1 = c0.shownCount() == want;

            // ── IC2 落盘（GT 自己的 codec）→ 读回 ──
            final Content written = t.contentFor(c0, ItemRecipeCapability.CAP);
            final Content decoded = decodeBack(written);
            final int readBack = representativeItem(decoded).getCount();
            final boolean ic2 = decoded != null && readBack == want;

            // ── IC3 两侧同步（真 FriendlyByteBuf 往返）──
            final ShanhaiIoTable tx = new ShanhaiIoTable(1, 0, 1, 0);
            tx.cell(0).setItem(probe, want);
            final ShanhaiIoTable rx = new ShanhaiIoTable(1, 0, 1, 0);
            int wireLen = -1;
            int wireCount = -1;
            boolean syncThrew = false;
            try {
                final net.minecraft.network.FriendlyByteBuf buf =
                        new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                tx.writeState(buf);
                wireLen = buf.readableBytes();
                rx.readState(buf);
                wireCount = rx.cell(0) == null ? -1 : rx.cell(0).shownCount();
            } catch (Throwable ts) {
                syncThrew = true;
                ShanhaiMod.LOGGER.error("{} IO_ITEMCOUNT_SELFCHECK IC3 sync_threw: {}", PREFIX, ts.toString());
            }
            final boolean ic3 = !syncThrew && wireCount == want;

            // ── IC4 上限 ──
            final ShanhaiIoTable tMax = new ShanhaiIoTable(1, 0, 1, 0);
            tMax.cell(0).setItem(probe, Integer.MAX_VALUE);
            final boolean ic4 = MAX_ITEM_COUNT == Integer.MAX_VALUE
                    && tMax.cell(0).shownCount() == Integer.MAX_VALUE;

            // ── IC5／IC6 负对照：旧口径必须"看得见" ──
            final boolean ic5 = want != 64;
            final boolean ic6 = oldFormula == 64;

            // ── IC7 负对照：0 = 清空 ──
            final ShanhaiIoTable tZero = new ShanhaiIoTable(1, 0, 1, 0);
            tZero.cell(0).setItem(probe, 7);
            tZero.cell(0).setItem(probe, 0);
            final boolean ic7 = tZero.cell(0).empty();

            final boolean[] all = {ic1, ic2, ic3, ic4, ic5, ic6, ic7};
            final String[] names = {"IC1_buf", "IC2_persist_readback", "IC3_wire_sync", "IC4_ceiling",
                    "IC5_neg_not64", "IC6_neg_old_formula", "IC7_neg_zero_clears"};
            final StringBuilder failed = new StringBuilder();
            for (int i = 0; i < all.length; i++) {
                if (all[i]) {
                    pass++;
                } else {
                    fail++;
                    failed.append(names[i]).append(' ');
                }
            }
            ShanhaiMod.LOGGER.info("{} IO_ITEMCOUNT_SELFCHECK IC1 want={} got={} PASS={} "
                            + "（缓冲层：{}.setItem 不再按 maxStackSize 夹）",
                    PREFIX, want, c0.shownCount(), ic1, "Cell");
            ShanhaiMod.LOGGER.info("{} IO_ITEMCOUNT_SELFCHECK IC2 decoded_count={} PASS={} "
                            + "（落盘+读回：GT 自己的 Content.codec 走一圈）",
                    PREFIX, readBack, ic2);
            ShanhaiMod.LOGGER.info("{} IO_ITEMCOUNT_SELFCHECK IC3 wire_bytes={} count_after_roundtrip={} PASS={} "
                            + "（两侧同步：旧代码这里会变成 {} —— FriendlyByteBuf.writeItem 里是 writeByte(getCount())）",
                    PREFIX, wireLen, wireCount, ic3, want & 0xFF);
            ShanhaiMod.LOGGER.info("{} IO_ITEMCOUNT_SELFCHECK IC4 max_item_count={} stored={} PASS={}",
                    PREFIX, MAX_ITEM_COUNT, tMax.cell(0).shownCount(), ic4);
            ShanhaiMod.LOGGER.info("{} IO_ITEMCOUNT_SELFCHECK IC5 99999!=64 PASS={} / "
                            + "IC6 旧公式 min({},{})={} 期望 64 PASS={} "
                            + "（两条负对照：证明上面这些判据真看得见改动前的行为）",
                    PREFIX, ic5, want, maxStack, oldFormula, ic6);
            ShanhaiMod.LOGGER.info("{} IO_ITEMCOUNT_SELFCHECK IC7 填 0 之后 empty={} PASS={}",
                    PREFIX, tZero.cell(0).empty(), ic7);
            ShanhaiMod.LOGGER.info("{} IO_ITEMCOUNT_SELFCHECK_DONE pass={} fail={} failed={}",
                    PREFIX, pass, fail, fail == 0 ? "(none)" : failed.toString().trim());
            if (fail > 0) {
                ShanhaiMod.LOGGER.error("{} IO_ITEMCOUNT_SELFCHECK FAILED {} 条判据不过 ⇒ "
                        + "「一格能填超过 64」这件事没做成（或做成了但同步会截断），必须修", PREFIX, fail);
            }
        } catch (Throwable th) {
            ShanhaiMod.LOGGER.error("{} IO_ITEMCOUNT_SELFCHECK_CRASHED (server keeps running): {}",
                    PREFIX, th.toString(), th);
        }
    }
}
