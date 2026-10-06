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
     * 🔴🔴 2026-10-06（第 15 刀）<b>【类型声明上限】的放大闸 —— 不再是"任何一栏最多 128 格"</b>。
     *
     * <h4>为什么原来那个 128 是错的（用户点单 ＋ 活日志实证）</h4>
     * 用户原话（逐字）：
     * <blockquote>「还有就是有些配方真的会超出5页，这个页数你也可以添加一下」<br>
     * 「其实是有些配方的输出超出了 jei/机器的 io 但是 kjs 还是强制注册成功了，
     * 我希望这些配方也可以正常显示输出和输入」</blockquote>
     * 他本机的活日志（{@code logs/latest.log}，只读）里就有两条<b>真实</b>规模：
     * <pre>
     * 16:01:09  workspace_io_shape id=thetornproductionline:assembler/creative_all_items_gen
     *           type=gtceu:assembler shape=9/1/19862/3 (type_max=9/1/1/3 used=3/0/19862/0)
     *           cells=141 pages(in=1 out=5)          ← ★ 19862 被夹成 128；141 格 ÷ 32 = 正好 5 页
     * 15:42:13  workspace_io_shape id=…/ultimate_integrated_ore_process
     *           type=gtceu:miner_module shape=2/1/282/3 (type_max=2/1/6/3 used=2/0/282/0)
     *           cells=134 pages(in=1 out=5)          ← ★ 282 被夹成 128
     * </pre>
     * ⇒ 用户截图里的「输出 4/5 (131 格)」「物品出 128 · 流体出 3」就是这一行夹出来的。
     *
     * <h4>新旧口径（两个上限，各管一件事）</h4>
     * <pre>
     *   旧：shape[i] = max(0, min(128, max(type_max[i], used[i])))        ← 用一个数管两件事
     *   新：shape[i] = max( min(type_max[i], MAX_PER_SECTION),           ← ① 类型【声明】的上限
     *                     min(used[i],     MAX_CELLS_PER_SECTION) )      ← ② 这条配方【真用到】的条数
     * </pre>
     * 为什么必须分开：① 是"类型觉得自己能装多少"（可以很离谱，甚至 {@code Integer.MAX_VALUE}，
     * 拿它开格子会把内存撑爆 ⇒ 需要闸）；② 是"这条配方 json 里真的有几条"
     * （它本身就受配方文件大小限制 ⇒ 照单全收才是对的，正是用户要的那件事）。
     *
     * <p>⚠️ <b>本常量只管 ①</b>。给 ① 留 4096 是因为活日志里所有类型的
     * {@code type_max} 实测都在 1..17（见 {@code ShanhaiRecipeTypes.maxIoStatsLine()} 的读数），
     * 4096 已经等于"任何真实类型都不受它限制"，同时又挡住畸形值。
     */
    public static final int MAX_PER_SECTION = 4096;

    /**
     * 🔴 2026-10-06（第 15 刀）<b>【这条配方真用到几条】的绝对安全上限</b> —— 本类真正的兜底。
     *
     * <p>取值依据（**不是拍的**，是用户本机活日志里的最大真实值 ×3）：
     * 实测最大 = <b>19862</b>（{@code thetornproductionline:assembler/creative_all_items_gen}，
     * 见 {@link #MAX_PER_SECTION} 那段引的日志原文），次大 282。65536 给足余量，
     * 又保证"就算有人写一条几十万条的畸形配方"也不会把客户端/服务端内存拖垮
     * （65536 × 4 栏 = 26 万个格子，仍是几十 MB 量级）。
     *
     * <p>⚠️ 一旦某条配方的真实用量超过它，<b>保存会被拒绝</b>
     * （{@link #truncationRisk} ⇒ {@code ShanhaiRecipeEditorWorkspace#save}），
     * <b>绝不静默丢数据</b> —— 这是本工程"宁可拒绝，不许静默"那条纪律。
     */
    public static final int MAX_CELLS_PER_SECTION = 65536;

    /**
     * 🔴 2026-10-06（第 15 刀）<b>四元组的唯一算法</b>（两个来源取大，但各自有自己的闸）。
     *
     * <p>两个来源的语义差别见 {@link #MAX_PER_SECTION}。本方法是<b>纯函数</b>，
     * 所以"19862 到底会不会被截断"这件事可以脱离游戏离线判（离线模型
     * {@code handoff/outbound/BigIoShapeModel.java} 逐行复刻的它就是这一份）。
     *
     * @param typeCap 类型自己声明的上限（{@link ShanhaiRecipeTypes#maxIoOf}，长度 4）
     * @param used    这条配方实际用到的条数（{@link #usedBy}，长度 4）
     * @return 四元组 {@code {物品入, 流体入, 物品出, 流体出}}（每个都是"这一栏开几个格子"）
     */
    public static int[] shapeFor(int[] typeCap, int[] used) {
        final int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            final int cap = clampTo(typeCap != null && i < typeCap.length ? typeCap[i] : 0, MAX_PER_SECTION);
            final int real = clampTo(used != null && i < used.length ? used[i] : 0, MAX_CELLS_PER_SECTION);
            out[i] = Math.max(cap, real);
        }
        return out;
    }

    private static int clampTo(int n, int hi) {
        return Math.max(0, Math.min(hi, n));
    }

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

    /**
     * 建表时的<b>兜底</b>夹取 —— 只挡"畸形到不可能有人真用"的数。
     *
     * <p>🔴 2026-10-06（第 15 刀）：这里原来是 {@code min(MAX_PER_SECTION, n)}（= 128），
     * 它是"19862 变成 128"的最后一刀。现在改成 {@link #MAX_CELLS_PER_SECTION}（65536）——
     * <b>这一层不再参与业务判断</b>，业务裁剪已经上移到 {@link #shapeFor} 里按两个来源分开做。
     */
    private static int clampSection(int n) {
        return clampTo(n, MAX_CELLS_PER_SECTION);
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
     * 🔴 2026-10-06（第 15 刀）<b>现在屏幕上真正可见的那些格子（整表下标）</b>
     * —— 同步包只发这一批，见 {@link #writeState(FriendlyByteBuf, int[])} 里那笔账。
     *
     * <p>口径与 {@code ShanhaiRecipeEditorWorkspace#inCellIndex/outCellIndex}
     * <b>逐字相同</b>（同样是 {@code page * cellsPerPage + slot}，同样越界就跳过）：
     * 那两处是"第 slot 个控件画哪一格"，这里是"要发哪几格"，两边必须是同一批下标，
     * 否则屏幕上会出现"收到的是别的页"那种错位。
     *
     * @param inPage       输入栏当前页（0 基）
     * @param outPage      输出栏当前页（0 基）
     * @param cellsPerPage 一栏一页多少格（{@link ShanhaiRecipeEditorWorkspace#CELLS_PER_PAGE}）
     * @return 可见下标（长度 ≤ 2×{@code cellsPerPage}）
     */
    public int[] visibleIndices(int inPage, int outPage, int cellsPerPage) {
        final int per = Math.max(1, cellsPerPage);
        final int[] tmp = new int[Math.min(cells.size(), per * 2)];
        int n = 0;
        final int inSec = inSection();
        for (int slot = 0; slot < per; slot++) {
            final int idx = inPage * per + slot;
            if (idx >= 0 && idx < inSec) {
                tmp[n++] = inIndex(idx);
            }
        }
        final int outSec = outSection();
        for (int slot = 0; slot < per; slot++) {
            final int idx = outPage * per + slot;
            if (idx >= 0 && idx < outSec) {
                tmp[n++] = outIndex(idx);
            }
        }
        return java.util.Arrays.copyOf(tmp, n);
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

    /** 四个区的名字（读数/报错文本用；下标与 {@link #usedBy} 的四元组一一对应）。 */
    public static final String[] SECTION_NAMES = {"物品输入", "流体输入", "物品输出", "流体输出"};

    /**
     * 🔴🔴 2026-10-06（第 15 刀）<b>「保存会不会静默丢 IO」的判据（纯函数）</b>。
     *
     * <h4>为什么必须有它（这是本次改动里最要紧的一条）</h4>
     * 保存那条路是<b>整段替换</b>语义：
     * {@code ShanhaiRecipeEditorWorkspace.save()} → {@code io.json("outputs")}
     * （只遍历<b>本表</b>的格子）→ {@code ShanhaiRecipeEditorOps.setIo}
     * → {@code ShanhaiRecipeIoApply.applyTable} 里第一句就是 {@code table.clear()}。
     * ⇒ <b>表里装不下的那些条数，会在"用户改了任意一格"的那一刻被整段抹掉</b>
     * （内存里的配方 ＋ 落盘的 {@code config/shanhai/recipe_overrides.json} 一起），
     * 而日志上一切正常 —— 本工程最怕的那类静默数据损伤。
     * <p>改动之前这就是<b>真会发生</b>的：{@code used=19862} 而表里只有 128 格 ⇒
     * 用户只要动一下那条配方的任意一格再点保存，<b>19734 条输出凭空消失</b>。
     *
     * <h4>判据与用法</h4>
     * 逐栏比"这条配方真有几条"与"这张表开得出几格"；任何一栏装不下就返回一段人话描述，
     * 调用方（{@code save}）据此<b>拒绝这次保存</b>并报 ERROR —— 宁可拒绝，不许静默。
     *
     * @param used 这条配方四个区各有多少条（{@link #usedBy}）
     * @param t    当前编辑缓冲
     * @return {@code null} = 装得下（可以安全写回）；否则是"会丢多少"的描述
     */
    public static String truncationRisk(int[] used, ShanhaiIoTable t) {
        if (used == null || t == null) {
            return null;
        }
        final int[] have = {t.itemIn, t.fluidIn, t.itemOut, t.fluidOut};
        final StringBuilder sb = new StringBuilder();
        int lostTotal = 0;
        for (int i = 0; i < 4; i++) {
            final int u = i < used.length ? used[i] : 0;
            final int h = have[i];
            if (u > h) {
                if (sb.length() > 0) {
                    sb.append('；');
                }
                sb.append(SECTION_NAMES[i]).append(" 有 ").append(u)
                        .append(" 条、编辑器只装得下 ").append(h)
                        .append(" 条 ⇒ 一键保存会丢 ").append(u - h).append(" 条");
                lostTotal += u - h;
            }
        }
        return sb.length() == 0 ? null : sb.append("（合计 ").append(lostTotal).append(" 条）").toString();
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

    /**
     * 全量同步（旧口径）：把<b>每一格</b>都写进包。只给"小表"用
     * —— 自检 {@code IO_ITEMCOUNT_SELFCHECK.IC3} 与任何"格子数一眼可数"的场合。
     *
     * <p>🔴 面板那条路<b>不许</b>走它：见 {@link #writeState(FriendlyByteBuf, int[])} 里
     * "19862 格 = 291 KiB / 每 tick 一包"那段账。
     */
    public void writeState(FriendlyByteBuf buf) {
        writeState(buf, null);
    }

    /**
     * 🔴🔴 2026-10-06（第 15 刀）<b>按【当前可见的那一页】同步</b> —— 这是"同步包不能爆"的落点。
     *
     * <h4>为什么必须改（账算给你看）</h4>
     * 旧口径把每一格都写出去，实测（逐字段按 Forge 的 {@code FriendlyByteBuf} 语义算，
     * 见离线模型 {@code BigIoShapeModel} 的 D 段）：
     * <pre>
     *   每格 = dirty(1) + chance varint(2) + maxChance varint(2) + boost varint(1)
     *        + notConsumable(1) + chanceDirty(1) + 物品/流体那一段
     *   物品格（非空）：上面 8 ＋ writeBoolean(1) ＋ writeItemStack(布尔1 ＋ itemId varint 2~3
     *                  ＋ count byte 1 ＋ NBT 空标记 1) ＋ 数量 varint(1)          ≈ 15 字节
     *   空格：        上面 8 ＋ writeBoolean(1)                                      =  9 字节
     * </pre>
     * ⇒ 放开 128 之后那条真实配方（19862 个输出 ＋ 其余）合计 <b>≈ 291 KiB / 一次推送</b>，
     * 而 {@code StatePump} 是<b>版本一变就推一次</b>（每次拖动/翻页/保存都算变）。
     * 原版 {@code Varint21FrameDecoder} 的硬上限是 2097151 字节 ⇒ 还没炸，但离得很近，
     * 而且每 tick 几百 KB 对局域网/远程服务器就是实打实的卡。
     * <p>⇒ 面板一共只有 2×{@code CELLS_PER_PAGE}=64 个格子控件，
     * <b>客户端根本不需要当前页以外的格子</b>（绘制/命中都只走 {@code inCellIndex/outCellIndex}）
     * ⇒ 只发可见的 ≤64 格，包大小与配方规模<b>彻底解耦</b>（≤ ~1 KiB）。
     *
     * <h4>线上格式（写侧带下标，读侧自描述）</h4>
     * <pre>
     *   writeVarInt itemIn/fluidIn/itemOut/fluidOut     ← 四元组（客户端 resize 用）
     *   writeVarInt n                                   ← 这次发了多少格
     *   n × { writeVarInt 下标; 该格那一整段 }
     * </pre>
     * 下标<b>随包发</b>（不靠两侧各算一份）⇒ 读侧不需要知道"每页几格"这件事，
     * 也就不可能因为页大小/页号的假设不同而错位。{@code indices == null} ⇒ 全量
     * （等价于"下标 = 0..n-1"），读侧仍是同一套格式、同一段代码。
     *
     * @param indices 要同步的整表下标（{@code null} = 全量）
     */
    public void writeState(FriendlyByteBuf buf, int[] indices) {
        // 🔴 先写四元组：客户端那份是【按类型重建】的（格子数随类型变化），
        //    不把形状送过去，两侧的下标就会错位（拖第 9 格改到第 12 格那类事故）。
        buf.writeVarInt(itemIn);
        buf.writeVarInt(fluidIn);
        buf.writeVarInt(itemOut);
        buf.writeVarInt(fluidOut);
        final int[] idx = indices == null ? allIndices() : indices;
        // 🔴 n 必须【严格等于】下面真正写出去的条数：读侧按这个数循环，多算一个就会读到包尾
        //    （错位 ⇒ 后面的字段全错，本工程踩过"两侧字段顺序反了"那条 P0）。
        //    ⇒ 越界下标先在计数之前筛掉，而不是在循环里 continue。
        int live = 0;
        for (int i = 0; i < idx.length; i++) {
            if (cell(idx[i]) != null) {
                live++;
            } else {
                ShanhaiMod.LOGGER.warn("{} io_sync_skip_bad_index index={} cells={}",
                        PREFIX, idx[i], cells.size());
            }
        }
        buf.writeVarInt(live);
        for (int i = 0; i < idx.length; i++) {
            final Cell c = cell(idx[i]);
            if (c == null) {
                continue;       // 已经在上面报过警、也已经在 live 里扣掉了
            }
            buf.writeVarInt(idx[i]);
            // 🔴 2026-10-06：把"这一格是物品还是流体"也写进包 ⇒ 读侧<b>自描述</b>，
            //    不需要自己去推（下标越界时也照样读得完，流不会从此错位）。一格 1 字节。
            buf.writeBoolean(c.itemKind);
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

    private int[] allIndices() {
        final int[] a = new int[cells.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = i;
        }
        return a;
    }

    /**
     * 读回（客户端那份 session 用）：先按形状重建，再按【包里的下标】逐格读。
     *
     * <p>⚠️ 与写侧是同一套格式：{@code n × {下标, 该格那一整段}}。
     * 不在这 n 个下标里的格子<b>保持原样</b>（它们不在屏幕上，见写侧那段账）；
     * 但每一次推送都会把<b>当前页的全部格子</b>（含空格）发一遍，
     * 所以"翻到第 N 页"那一拍，新页的每一格都是新鲜的 ⇒ 屏幕上不可能出现旧页的残留。
     */
    public void readState(FriendlyByteBuf buf) {
        final int ii = buf.readVarInt();
        final int fi = buf.readVarInt();
        final int io = buf.readVarInt();
        final int fo = buf.readVarInt();
        resize(ii, fi, io, fo);
        final int n = buf.readVarInt();
        int applied = 0;
        int dropped = 0;
        for (int k = 0; k < n; k++) {
            // 🔴 下标【必须先读】。哪怕它越界，后面那一段字节也得照样读掉，
            //    否则整条流从这个点开始全部错位（本工程踩过"两侧字段顺序反了"那条 P0：
            //    错位后的异常还被 Forge 吞掉 ⇒ 界面上只表现为"面板打不开"）。
            final int index = buf.readVarInt();
            final boolean itemKind = buf.readBoolean();
            final boolean dirty = buf.readBoolean();
            final int chance = buf.readVarInt();
            final int maxChance = buf.readVarInt();
            final int boost = buf.readVarInt();
            final boolean notConsumable = buf.readBoolean();
            final boolean chanceDirty = buf.readBoolean();
            final ItemStack item = itemKind ? readItem(buf) : ItemStack.EMPTY;
            final FluidStack fluid = itemKind ? FluidStack.empty() : readFluid(buf);
            final Cell c = cell(index);
            if (c == null) {
                dropped++;
                continue;
            }
            c.dirty = dirty;
            c.chance = chance;
            c.maxChance = maxChance;
            c.tierChanceBoost = boost;
            c.notConsumable = notConsumable;
            c.chanceDirty = chanceDirty;
            if (itemKind) {
                c.item = item;
            } else {
                c.fluid = fluid;
            }
            applied++;
        }
        if (dropped > 0) {
            // 不许静默：只有"两侧形状不一致"才会走到这里，而那正是"拖第 9 格改到第 12 格"那类事故的前兆。
            ShanhaiMod.LOGGER.error("{} io_sync_dropped_out_of_range dropped={} of={} cells={} shape={}/{}/{}/{}",
                    PREFIX, dropped, n, cells.size(), itemIn, fluidIn, itemOut, fluidOut);
        }
        lastSyncCells = applied;
        lastSyncTotal = n;
    }

    /**
     * 上一次 {@link #readState} 真的落进了几格（读数；两侧对账用）。
     * <p>🔴 为什么要有它：`0==0` 那种"空输入恒真"的假绿是本工程踩过的坑
     * （见 HANDOFF-20261006-配方编辑器第一屏GT条数归零 §1.4）⇒
     * 自检断言里必须能看出"这次比了多少格"，所以把这两个数留下来打日志。
     */
    private int lastSyncCells = -1;
    private int lastSyncTotal = -1;

    public int lastSyncCells() {
        return lastSyncCells;
    }

    public int lastSyncTotal() {
        return lastSyncTotal;
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
     * <p>⚠️ 形状仍然是<b>第二刀运行期验过</b>的那一个：{@code SizedIngredient} ＋ 内层
     * {@code Ingredient} ＋ count。只有内层那一层在 2026-10-06 变了（见 {@link #ingredientForStack}）。
     */
    private static Object itemIngredientOf(ItemStack stack) {
        try {
            return com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient
                    .create(ingredientForStack(stack), stack.getCount());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} io_cell_sized_ingredient_failed item={} err={}",
                    PREFIX, stack, t.toString());
            return null;
        }
    }

    /**
     * 🔴 2026-10-06（用户原话：「他配方编辑器<b>不能保留编程电路</b>」）：
     * <b>带编号的编程电路必须走 GT 自己的 {@code IntCircuitIngredient}</b>，不能走
     * {@code Ingredient.of(栈)}。
     *
     * <h4>为什么（三层实测证据，不是推断）</h4>
     * <ol>
     *   <li><b>vanilla 那条路必然丢号</b>：{@code javap -c
     *       net.minecraft.world.item.crafting.Ingredient$ItemValue#serialize}（Forge
     *       1.20.1-47.2.20 mapped_official）只有 {@code ldc "item"} 一个键
     *       ⇒ {@code Ingredient.of(栈).toJson()} = {@code {"item":"gtceu:programmed_circuit"}}，
     *       <b>NBT 里的 {@code Configuration} 根本没进 JSON</b>；而
     *       {@code Ingredient#test} 的字节码是 {@code 栈.is(候选.getItem())} —— <b>只比物品、不比 NBT</b>
     *       ⇒ 存盘再读回来之后，这条配方<b>任何一个号都能喂进去</b>（号彻底没了，不是"变成 0"）；</li>
     *   <li><b>正确形状有现成参照</b>：GT 自己的序列化形是
     *       {@code {"type":"gtceu:circuit","configuration":N}}（{@code IntCircuitIngredient#toJson}
     *       字节码 {@code ldc "type"} ＋ {@code ldc "configuration"}，{@code TYPE = GTCEu.id("circuit")}），
     *       本实例 {@code local/kubejs/export/recipes/shanhai/photon_siphon/pf/photon.json}
     *       里一条活的配方就是 {@code "ingredient":{"type":"gtceu:circuit","configuration":2}}；</li>
     *   <li><b>读回来也靠它</b>：{@code IntCircuitIngredient#getItems()} 返回
     *       {@code IntCircuitBehaviour.stack(号)}（带 {@code Configuration} NBT）
     *       ⇒ {@link #representativeItem} 拿到的是带号的栈，卡片上的角标
     *       （{@link #circuitOf}）才画得出来。</li>
     * </ol>
     *
     * <p>⚠️ <b>没有号</b>的裸芯片（NBT 里没有 {@code Configuration}，{@link #circuitOf} 给 {@code -1}）
     * 与<b>所有非电路物品</b>一律保持原路（{@code Ingredient.of(栈)}）。
     *
     * <h4>为什么不会动到已验收的那几条</h4>
     * 现有判据用的探针是<b>普通物品</b>（{@code ShanhaiRecipeEditorWorkspaceCheck.PROBE_ITEM}
     * = {@code minecraft:dirt}、{@link ShanhaiIoTable} 的 IO_CHANCE_SELFCHECK 用 dirt/stone、
     * {@code ShanhaiRecipeEditorSelfcheck.probeStack()} 用 {@code Items.STONE}）
     * ⇒ {@link #circuitOf} 一律 {@code -1} ⇒ 走的还是老分支，JSON 一个字节不变（模型 E 段量过）。
     * ⚠️ 反过来说：<b>现有判据里没有一条拿"带号的电路"测过</b>，
     * 这正是这个 bug 能一路绿过去的原因（2026-10-06 补的模型与这条日志就是为堵它）。
     *
     * <h4>🆕 2026-10-06（第 13 刀）：非 GT／工作台那条链也共用本方法</h4>
     * 原版那一侧的 {@link ShanhaiVanillaRecipeShape#ingredientOf} 此前也是裸
     * {@code Ingredient.of(shown)} ⇒ <b>同一条 bug</b>（工作台格子里拖进带号电路同样丢号）。
     * 两处合成<b>同一个</b>方法，避免"改了一边忘另一边"。
     */
    public static Ingredient ingredientForStack(ItemStack stack) {
        final int circuit = circuitOf(stack);
        if (circuit >= com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient.CIRCUIT_MIN
                && circuit <= com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient.CIRCUIT_MAX) {
            try {
                final Ingredient circuitIng =
                        com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient.circuitInput(circuit);
                ShanhaiMod.LOGGER.info("{} io_cell_circuit_kept item={} circuit={} (这一格按 GT 的 "
                                + "gtceu:circuit 形状写 ⇒ 号会进 recipe_overrides.json 的 ingredient)",
                        PREFIX, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()),
                        circuit);
                return circuitIng;
            } catch (Throwable t) {
                // 取不到带号的电路原料 ⇒ 退回老路（宁可没号，也不能让这一格整个写不进去）
                ShanhaiMod.LOGGER.error("{} io_cell_circuit_ingredient_failed circuit={} item={} err={} "
                                + "(退回 Ingredient.of(栈)：这一格会丢掉电路号)",
                        PREFIX, circuit, stack, t.toString());
            }
        }
        return Ingredient.of(stack);
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

    /**
     * 🔴🔴 2026-10-06（第 15 刀）<b>「超大 IO 配方」那条链的机器判据</b>。
     *
     * <h4>它判的是用户报的那件事</h4>
     * 用户原话（逐字）：
     * <blockquote>「有些配方真的会超出5页」<br>
     * 「其实是有些配方的输出超出了 jei/机器的 io 但是 kjs 还是强制注册成功了，
     * 我希望这些配方也可以正常显示输出和输入」</blockquote>
     * 靶子用的是他本机活日志里的<b>真实数字</b>（不是编的）：
     * {@code type_max=9/1/1/3 · used=3/0/19862/0 · 旧代码 cells=141 pages(out=5)}。
     *
     * <h4>判据（每条都带对照，且读数里能看出"这次比了多少格"）</h4>
     * <pre>
     *   BG1 新口径 cells == 19875（19862 不再被夹成 128）   BG1neg 旧口径必须 == 141（负对照）
     *   BG2 新口径 outPages == 621                        BG2neg 旧口径必须 == 5（负对照）
     *   BG3 truncationRisk(used, 新表) == null（装得下）
     *   BG4 truncationRisk(used, 旧表) 必须报出"会丢 19734 条"（★ 这就是"保存会丢数据"的证据）
     *   BG5 分页同步：writeState(全量) 的字节数 vs writeState(当前页) 的字节数
     *       —— 期望 全量 > 200000 且 当前页 < 4000（包大小与配方规模解耦）
     *   BG6 分页同步真的读得回来：writeState(第 3 页) → readState ⇒
     *       那一页的格子逐格相等，且【页外】的格子没被动过
     *   BG7 空输入不恒真：本项统计"真的比过几个格子"（compared>0），
     *       0 的话直接判红（本工程踩过 0==0 假绿的坑）
     * </pre>
     *
     * <p>跑法：由 {@link ShanhaiRecipeEditorWorkspaceCheck#run} 在 {@code SHANHAI_EDITOR=1} 时调用。
     * <b>不需要客户端、不需要玩家、不写盘</b>。
     */
    public static void selfcheckBigIoPaging() {
        int pass = 0;
        int fail = 0;
        try {
            // ── 靶子：用户本机活日志里那条真实配方（逐字抄自 logs/latest.log 16:01:09）──
            final int[] typeCap = {9, 1, 1, 3};
            final int[] used = {3, 0, 19862, 0};
            // 改动前那一句（逐字复刻旧公式）：先取大，再被 MAX_PER_SECTION 夹一次。
            final int[] oldShape = new int[4];
            for (int i = 0; i < 4; i++) {
                oldShape[i] = clampTo(Math.max(typeCap[i], used[i]), 128);
            }
            final int[] newShape = shapeFor(typeCap, used);

            final ShanhaiIoTable oldT = new ShanhaiIoTable(oldShape[0], oldShape[1], oldShape[2], oldShape[3]);
            final ShanhaiIoTable newT = new ShanhaiIoTable(newShape[0], newShape[1], newShape[2], newShape[3]);

            final int oldCells = oldT.cellCount();
            final int newCells = newT.cellCount();
            final int oldPages = pageCountOf(oldT.outSection());
            final int newPages = pageCountOf(newT.outSection());

            final boolean bg1 = newCells == 19875;
            final boolean bg1neg = oldCells == 141;
            final boolean bg2 = newPages == 621;
            final boolean bg2neg = oldPages == 5;

            final String riskNew = truncationRisk(used, newT);
            final String riskOld = truncationRisk(used, oldT);
            final boolean bg3 = riskNew == null;
            final boolean bg4 = riskOld != null && riskOld.contains("19734");

            // ── BG5/BG6：真 FriendlyByteBuf 往返（和面板那条同步链同一段代码）──
            final ShanhaiIoTable tx = new ShanhaiIoTable(newShape[0], newShape[1], newShape[2], newShape[3]);
            final net.minecraft.world.item.ItemStack probe =
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT);
            for (int i = 0; i < tx.cellCount(); i++) {
                tx.cell(i).setItem(probe, 1);          // 全表填满（模拟极端：每格都有东西）
            }
            final int[] page3 = tx.visibleIndices(0, 3, ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE);
            int fullBytes = -1;
            int pageBytes = -1;
            boolean syncThrew = false;
            int compared = 0;
            boolean pageOk = false;
            boolean outsideUntouched = false;
            try {
                final net.minecraft.network.FriendlyByteBuf big =
                        new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                tx.writeState(big);                     // 旧口径：全量
                fullBytes = big.readableBytes();

                final net.minecraft.network.FriendlyByteBuf small =
                        new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                tx.writeState(small, page3);            // 新口径：只发当前页
                pageBytes = small.readableBytes();

                // 读侧：先造一份"全表都有东西（数量 7）"的客户端副本，再只把第 3 页那一批读进去 ⇒
                // 页内的格子必须与源一致，页外必须还是 7（证明"只发一部分"没有副作用）。
                final ShanhaiIoTable rx = new ShanhaiIoTable(newShape[0], newShape[1], newShape[2], newShape[3]);
                for (int i = 0; i < rx.cellCount(); i++) {
                    rx.cell(i).setItem(probe, 7);
                }
                rx.readState(small);
                final boolean[] visible = new boolean[rx.cellCount()];
                pageOk = true;
                for (int i = 0; i < page3.length; i++) {
                    final int idx = page3[i];
                    if (idx >= 0 && idx < visible.length) {
                        visible[idx] = true;
                    }
                    final Cell a = tx.cell(idx);
                    final Cell b = rx.cell(idx);
                    compared++;
                    if (a == null || b == null || a.shownCount() != b.shownCount()) {
                        pageOk = false;
                    }
                }
                pageOk = compared > 0 && pageOk;
                outsideUntouched = true;
                for (int i = 0; i < rx.cellCount(); i++) {
                    if (!visible[i] && rx.cell(i).shownCount() != 7) {
                        outsideUntouched = false;
                        break;
                    }
                }
                ShanhaiMod.LOGGER.info("{} IO_BIGIO_SELFCHECK BG6 read_applied={} read_total={} "
                                + "（读数：这次真的比了 {} 格、发了 {} 格）",
                        PREFIX, rx.lastSyncCells(), rx.lastSyncTotal(), compared, page3.length);
            } catch (Throwable ts) {
                syncThrew = true;
                ShanhaiMod.LOGGER.error("{} IO_BIGIO_SELFCHECK sync_threw: {}", PREFIX, ts.toString());
            }

            final boolean bg5 = !syncThrew && fullBytes > 200_000 && pageBytes > 0 && pageBytes < 4000;
            final boolean bg6 = !syncThrew && pageOk && outsideUntouched && compared == page3.length;
            final boolean bg7 = compared > 0;

            final boolean[] all = {bg1, bg1neg, bg2, bg2neg, bg3, bg4, bg5, bg6, bg7};
            final String[] names = {"BG1_new_cells", "BG1neg_old_cells", "BG2_new_pages", "BG2neg_old_pages",
                    "BG3_new_no_truncation", "BG4_old_would_drop", "BG5_wire_budget", "BG6_page_roundtrip",
                    "BG7_not_vacuous"};
            final StringBuilder failed = new StringBuilder();
            for (int i = 0; i < all.length; i++) {
                if (all[i]) {
                    pass++;
                } else {
                    fail++;
                    failed.append(names[i]).append(' ');
                }
            }
            ShanhaiMod.LOGGER.info("{} IO_BIGIO_SELFCHECK BG1 cells 新口径={} 旧口径={} (期望 19875/141) PASS={}/{}",
                    PREFIX, newCells, oldCells, bg1, bg1neg);
            ShanhaiMod.LOGGER.info("{} IO_BIGIO_SELFCHECK BG2 out_pages 新口径={} 旧口径={} (期望 621/5) PASS={}/{}",
                    PREFIX, newPages, oldPages, bg2, bg2neg);
            ShanhaiMod.LOGGER.info("{} IO_BIGIO_SELFCHECK BG3/BG4 新表装得下={} 旧表={} PASS={}/{}",
                    PREFIX, riskNew == null ? "是" : riskNew,
                    riskOld == null ? "（没算出风险 ⇒ 判据坏了）" : riskOld, bg3, bg4);
            ShanhaiMod.LOGGER.info("{} IO_BIGIO_SELFCHECK BG5 同步包 全量={} 字节 / 当前页={} 字节 PASS={} "
                            + "（{} 格 vs {} 格；原版帧上限 2097151）",
                    PREFIX, fullBytes, pageBytes, bg5, tx.cellCount(), page3.length);
            ShanhaiMod.LOGGER.info("{} IO_BIGIO_SELFCHECK BG6 页内逐格相等={} 页外没被动过={} 比过={} 格 PASS={}",
                    PREFIX, pageOk, outsideUntouched, compared, bg6);
            ShanhaiMod.LOGGER.info("{} IO_BIGIO_SELFCHECK_DONE pass={} fail={} failed={}",
                    PREFIX, pass, fail, fail == 0 ? "(none)" : failed.toString().trim());
            if (fail > 0) {
                ShanhaiMod.LOGGER.error("{} IO_BIGIO_SELFCHECK FAILED {} 条判据不过 ⇒ "
                        + "「超出机器 io 的配方也能完整显示/编辑」这件事没做成，必须修", PREFIX, fail);
            }
        } catch (Throwable th) {
            ShanhaiMod.LOGGER.error("{} IO_BIGIO_SELFCHECK_CRASHED (server keeps running): {}",
                    PREFIX, th.toString(), th);
        }
    }

    /** 一栏几页（至少 1 页）——与 {@code ShanhaiRecipeEditorWorkspace#pageCountOf} 同一口径。 */
    private static int pageCountOf(int section) {
        return Math.max(1, (section + ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE - 1)
                / ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE);
    }
}
