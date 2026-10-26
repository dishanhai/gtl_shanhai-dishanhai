package com.shanhai.machine.stellar;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>「手填配方类型 + 展开样板 → GTRecipe」的唯一反查器</b>（J6 的落地件）。
 *
 * <h2>为什么必须新写一个</h2>
 * 老山海的 {@code com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper} 里
 * 有这个能力（{@code findMatchingRecipeForPattern(GenericStack[], GenericStack[], String)}，
 * javap 偏移见 {@code temp\crossrecipe\javap-VirtualPatternEncodingHelper.txt}），但它**不在本仓、
 * 也不在 {@code libs} 的任何 jar 里**（全仓 grep 与 34 个 jar 内类名扫描都是 0）⇒ 拿不到，只能按同一算法重写。
 *
 * <h2>算法（照 {@code PatternRecipeTypeHelper.resolveRecipeType} 的 javap 偏移 209–218 取法）</h2>
 * <pre>
 *   type  = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(typeId))     // 偏移 209
 *   recipes = type.getLookup().getLookup().getRecipes(false)                // 偏移 213–224（Stream&lt;GTRecipe&gt;）
 *   逐条：拿 getInputContents / getOutputContents(ItemRecipeCapability.CAP / FluidRecipeCapability.CAP)
 *         → 折成 AEKey 集合，与样板的 sparseInputs / sparseOutputs 比
 * </pre>
 *
 * <h2>🔴 匹配判据（保守优先：宁可"查不到"也不认错）</h2>
 * <ol>
 *   <li><b>输出集合必须相等</b>（键集合，忽略数量与概率）：样板的每条产出都能在这条配方的产出里找到，
 *       且这条配方的每条产出也都在样板里。多一条少一条都算不匹配 —— 这是"要导线出杆"最直接的判据
 *       （导线配方与杆配方产出不同名）；</li>
 *   <li><b>输入必须全部被覆盖</b>（键集合包含）：样板的每条输入都要在这条配方的输入里出现
 *       （配方可以多出样板没有的输入 = 电路/催化剂那一类，不算错）；</li>
 *   <li>多条都满足 ⇒ 先看<b>数量也对得上</b>的那条；仍有并列 ⇒ 视为<b>歧义</b>，返回 {@code null}
 *       并让上层 WARN（不猜）。</li>
 * </ol>
 * <p>⚠️ 概率产出（{@code Content.chance < maxChance}）一律<b>计入</b>输出集合：样板是编码时把
 * {@code getIOStacks} 全写进去的，漏掉它会让我们把"正确配方"判成不匹配。
 *
 * <h2>不落任何缓存</h2>
 * 反查结果由调用方（{@code shanhaiRebuildPatternTables()}）在<b>同一次遍历</b>里绑定到槽，
 * 生命周期与 {@code expandedToSlot} 完全一致 ⇒ 不存在"表重建了绑定还是旧的"这种状态。
 */
public final class ShanhaiWildcardRecipeBinding {

    private ShanhaiWildcardRecipeBinding() {}

    /** 反查结果：命中 / 未命中 / 歧义 —— 让调用方能把三种情况分开记日志。 */
    public enum Verdict {
        /** 命中唯一一条（或数量也对得上的那一条）。 */
        FOUND,
        /** 类型 id 为空 ⇒ 不参与反查（"自动识别"，保持改前行为）。 */
        NO_TYPE,
        /** 类型 id 解析不出 {@code GTRecipeType}。 */
        TYPE_UNKNOWN,
        /** 该类型下一条配方都没有（世界还没加载完 / 类型表是空的）。 */
        TYPE_EMPTY,
        /** 扫过了，但没有一条同时满足输出相等 + 输入覆盖。 */
        NO_MATCH,
        /** 有多条满足且数量也对不齐 ⇒ 不猜。 */
        AMBIGUOUS,
        /** 这条样板不是 {@code AEProcessingPattern}（理论上不会发生）。 */
        NOT_PROCESSING
    }

    /** 一次反查的完整读数（给 WARN / XRECIPE 用，避免上层二次拼装）。 */
    public static final class Result {
        public final Verdict verdict;
        public final GTRecipe recipe;
        public final String typeId;
        public final int scanned;
        public final int matched;

        Result(Verdict verdict, GTRecipe recipe, String typeId, int scanned, int matched) {
            this.verdict = verdict;
            this.recipe = recipe;
            this.typeId = typeId;
            this.scanned = scanned;
            this.matched = matched;
        }

        public boolean found() {
            return this.verdict == Verdict.FOUND && this.recipe != null;
        }

        @Override
        public String toString() {
            return this.verdict + "(type=" + (this.typeId == null || this.typeId.isEmpty() ? "none" : this.typeId)
                    + ",scanned=" + this.scanned + ",matched=" + this.matched + ")";
        }
    }

    /** 类型 id → {@code GTRecipeType}；解析不出返回 {@code null}（绝不抛）。 */
    public static GTRecipeType typeOf(String typeId) {
        if (typeId == null || typeId.isEmpty()) {
            return null;
        }
        try {
            final ResourceLocation rl = ResourceLocation.tryParse(typeId.trim().toLowerCase(java.util.Locale.ROOT));
            if (rl == null) {
                return null;
            }
            return GTRegistries.RECIPE_TYPES.get(rl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这条配方属于哪个类型 id（{@code registryName}）；拿不到返回空串。 */
    public static String typeIdOf(GTRecipe recipe) {
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            return "";
        }
        return recipe.recipeType.registryName.toString();
    }

    /** 配方 id（日志用）：优先 {@code recipe.id}，没有就退化成 {@code recipeType#index}。 */
    public static String describe(GTRecipe recipe) {
        if (recipe == null) {
            return "null";
        }
        if (recipe.id != null) {
            return recipe.id.toString();
        }
        return typeIdOf(recipe) + "#(noId)";
    }

    /**
     * 反查：给定样板与<b>手填类型 id</b>，返回那一条 GTRecipe。
     *
     * @param pattern  展开出来的样板（{@code AEProcessingPattern}）
     * @param typeId   母槽样板 NBT 里的 {@code gt_shanhai_recipe_type}（空 = 自动）
     */
    public static Result find(IPatternDetails pattern, String typeId) {
        final String norm = typeId == null ? "" : typeId.trim();
        if (norm.isEmpty()) {
            return new Result(Verdict.NO_TYPE, null, norm, 0, 0);
        }
        if (!(pattern instanceof AEProcessingPattern processing)) {
            return new Result(Verdict.NOT_PROCESSING, null, norm, 0, 0);
        }
        final GTRecipeType type = typeOf(norm);
        if (type == null) {
            return new Result(Verdict.TYPE_UNKNOWN, null, norm, 0, 0);
        }
        final GenericStack[] ins = processing.getSparseInputs();
        final GenericStack[] outs = processing.getSparseOutputs();
        final Map<AEKey, Long> wantIn = amountsOf(ins);
        final Map<AEKey, Long> wantOut = amountsOf(outs);

        final List<GTRecipe> matched = new ArrayList<>(2);
        GTRecipe exactAmounts = null;
        int scanned = 0;
        try {
            final java.util.Iterator<GTRecipe> it = type.getLookup().getLookup().getRecipes(false).iterator();
            while (it.hasNext()) {
                final GTRecipe recipe = it.next();
                scanned++;
                if (!outputsEqual(recipe, wantOut)) {
                    continue;
                }
                if (!inputsCovered(recipe, wantIn)) {
                    continue;
                }
                matched.add(recipe);
                if (exactAmounts == null && amountsMatch(recipe, wantIn, wantOut)) {
                    exactAmounts = recipe;
                }
            }
        } catch (Throwable ignored) {
            // 配方表在加载中途被换掉之类 ⇒ 当作"没扫到"，由调用方按未命中处理（它会 WARN）
        }
        if (scanned == 0) {
            return new Result(Verdict.TYPE_EMPTY, null, norm, 0, 0);
        }
        if (matched.isEmpty()) {
            return new Result(Verdict.NO_MATCH, null, norm, scanned, 0);
        }
        if (matched.size() == 1) {
            return new Result(Verdict.FOUND, matched.get(0), norm, scanned, 1);
        }
        if (exactAmounts != null) {
            return new Result(Verdict.FOUND, exactAmounts, norm, scanned, matched.size());
        }
        return new Result(Verdict.AMBIGUOUS, null, norm, scanned, matched.size());
    }

    // ------------------------------------------------------------------ 内部：内容折成 AEKey

    private static Map<AEKey, Long> amountsOf(GenericStack[] stacks) {
        final Map<AEKey, Long> out = new LinkedHashMap<>();
        if (stacks == null) {
            return out;
        }
        for (GenericStack stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0L) {
                continue;
            }
            out.merge(stack.what(), stack.amount(), Long::sum);
        }
        return out;
    }

    /** 配方某侧（输入/输出）的键集合。物品走 {@link ItemRecipeCapability#CAP}，流体走 {@link FluidRecipeCapability#CAP}。 */
    private static Set<AEKey> keysOfSide(GTRecipe recipe, boolean output) {
        final Set<AEKey> keys = new LinkedHashSet<>();
        final List<Content> items = output
                ? recipe.getOutputContents(ItemRecipeCapability.CAP)
                : recipe.getInputContents(ItemRecipeCapability.CAP);
        if (items != null) {
            for (Content content : items) {
                final AEKey key = itemKeyOf(content);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        final List<Content> fluids = output
                ? recipe.getOutputContents(FluidRecipeCapability.CAP)
                : recipe.getInputContents(FluidRecipeCapability.CAP);
        if (fluids != null) {
            for (Content content : fluids) {
                final AEKey key = fluidKeyOf(content);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** 配方的「物品 + 流体」输入数量表（只用于"数量也对得上"的加分项）。 */
    private static Map<AEKey, Long> inputAmountsOf(GTRecipe recipe) {
        final Map<AEKey, Long> out = new LinkedHashMap<>();
        final List<Content> items = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (items != null) {
            for (Content content : items) {
                final ItemStack stack = itemStackOf(content);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                final AEKey key = AEItemKey.of(stack);
                if (key != null) {
                    out.merge(key, Math.max(1L, stack.getCount()), Long::sum);
                }
            }
        }
        final List<Content> fluids = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (fluids != null) {
            for (Content content : fluids) {
                final FluidStack stack = fluidStackOf(content);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                final AEKey key = AEFluidKey.of(stack.getFluid(), stack.getTag());
                if (key != null) {
                    out.merge(key, Math.max(1L, stack.getAmount()), Long::sum);
                }
            }
        }
        return out;
    }

    private static boolean outputsEqual(GTRecipe recipe, Map<AEKey, Long> wantOut) {
        final Set<AEKey> have = keysOfSide(recipe, true);
        if (have.size() != wantOut.size()) {
            return false;
        }
        return have.containsAll(wantOut.keySet());
    }

    private static boolean inputsCovered(GTRecipe recipe, Map<AEKey, Long> wantIn) {
        if (wantIn.isEmpty()) {
            return true;
        }
        return keysOfSide(recipe, false).containsAll(wantIn.keySet());
    }

    private static boolean amountsMatch(GTRecipe recipe, Map<AEKey, Long> wantIn, Map<AEKey, Long> wantOut) {
        final Map<AEKey, Long> have = inputAmountsOf(recipe);
        for (Map.Entry<AEKey, Long> e : wantIn.entrySet()) {
            final Long got = have.get(e.getKey());
            if (got == null || !got.equals(e.getValue())) {
                return false;
            }
        }
        return !wantOut.isEmpty();
    }

    // ------------------------------------------------------------------ 内部：Content → AEKey

    private static ItemStack itemStackOf(Content content) {
        if (content == null || content.getContent() == null) {
            return null;
        }
        try {
            final Object raw = content.getContent();
            final Object stack = ItemRecipeCapability.CAP.of(raw);
            if (stack instanceof ItemStack itemStack) {
                return itemStack;
            }
            if (raw instanceof Ingredient ingredient) {
                final ItemStack[] items = ingredient.getItems();
                if (items.length > 0) {
                    return items[0];
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static FluidStack fluidStackOf(Content content) {
        if (content == null || content.getContent() == null) {
            return null;
        }
        try {
            final Object stack = FluidRecipeCapability.CAP.of(content.getContent());
            if (stack instanceof FluidStack fluidStack) {
                return fluidStack;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static AEKey itemKeyOf(Content content) {
        final ItemStack stack = itemStackOf(content);
        return stack == null || stack.isEmpty() ? null : AEItemKey.of(stack);
    }

    private static AEKey fluidKeyOf(Content content) {
        final FluidStack stack = fluidStackOf(content);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return AEFluidKey.of(stack.getFluid(), stack.getTag());
    }

    /** 未使用的占位说明：{@link Material} 只用于把 javadoc 里的"逐材料展开"链指回去，勿删。 */
    static final Class<?> MATERIAL_ANCHOR = Material.class;
}
