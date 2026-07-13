package com.dishanhai.gt_shanhai.api.machine;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 多配方类型选择集配方逻辑（薄层）。
 * <p>
 * <b>唯一职责</b>：把 GTLAdd 原生"单一 {@code getRecipeType()} 搜索"扩展为"遍历玩家选中的多个
 * 配方类型搜索"。除此之外的一切——并行计算、扣料、出货、链式接续，以及<b>样板总成发配槽的发现
 * 与执行</b>——全部沿用 GTLAdd/GTLCore 原生逻辑。
 * <p>
 * 样板总成（星律/超级/库存 等）是被动的：只暴露玩家 AE 下单推进来的发配槽 + 存对应原料，怎么用
 * 是主机配方逻辑的事。GTLCore 的 {@code GTRecipeLookupMixin} 会在 {@code getLookup().getRecipeIterator/find}
 * 时自动把发配槽（已激活/已缓存）的内容并入搜索——所以只要老老实实按选中类型走原生查找，样板配方
 * 就会被原生发现、执行、缓存、消费一次、随料耗尽退出激活而停止，全程无需本层插手。
 * <p>
 * <b>只缓存"配方集合"（哪些配方是候选），绝不缓存数量/并行/扣料</b>：数量类结果每轮 calculateParallels
 * 实算、buildFinal* 每轮重验扣料，杜绝"缓存上一次配方数量"造成的扣料错配、串产、卡产（历史教训
 * ERR-20260711 系列）。集合缓存只为省掉每配方周期对大型 AE 输入列表的重复物化搜索（火花实测热点
 * ExportOnlyAEItemList.getContents），短窗口 + 选择集变更即失效。
 */
public class SelectableRecipeTypeSetRecipeLogic extends GTLAddMultipleWirelessRecipesLogic {

    // 配方集合短缓存：只缓存"哪些配方是候选"，绝不缓存数量/并行/扣料。命中可省掉每配方周期
    // （~10t 一次的 onRecipeFinish 接续）对大型 AE 输入列表的 getContents() 物化重搜。
    // 三道保险确保缓存绝不"控死"配方：
    //   1) 短窗口自愈——超时（LOOKUP_CACHE_TICKS）必重搜，任何陈旧最多存活 1 秒；
    //   2) 空结果不缓存——机器空转/缺料时永远实时搜索，料一到立刻开工，杜绝"缓存空集守死不启动"；
    //   3) 选择集变更即时失效（invalidateLookupSetCache）+ 锁定态直接绕过。
    // 数量/扣料/模块门控仍每周期实时算（见 calculateParallels / buildFinalWirelessRecipe / 模块 checkModuleCondition），
    // 故缓存最坏只造成"网络新出现的可用配方延迟 ≤ 1 秒才被拾取"，不可能卡产/重复/串产/控死。
    private static final long LOOKUP_CACHE_TICKS = 20L;
    private long cachedLookupTick = Long.MIN_VALUE;
    private Set<GTRecipe> cachedLookupRecipes;

    public SelectableRecipeTypeSetRecipeLogic(SelectableRecipeTypeSetMachine machine) {
        super(machine);
    }

    public SelectableRecipeTypeSetRecipeLogic(SelectableRecipeTypeSetMachine machine,
                                              Predicate<IRecipeLogicMachine> beforeWorking) {
        super(machine, beforeWorking);
    }

    @Override
    public SelectableRecipeTypeSetMachine getMachine() {
        return (SelectableRecipeTypeSetMachine) super.getMachine();
    }

    /**
     * 选择集变更回调：立即失效配方集合缓存（否则被取消选择的类型在缓存窗口内仍可能被执行——控死风险）。
     * 不打断正在运行的配方（多配方逻辑 setup 前已真实扣输入、finish 才出货，interruptRecipe 会让已扣输入蒸发），
     * 新选择在下一轮 lookup 立即生效。锁定配方的清理由机器侧 refreshRecipeTypeSelection 负责。
     */
    public void onRecipeTypeSelectionChanged() {
        invalidateLookupSetCache();
    }

    /** 立即清空配方集合缓存。选择集变更、模块等级变化等"候选集合可能变化"的时机必须调用。 */
    protected void invalidateLookupSetCache() {
        cachedLookupTick = Long.MIN_VALUE;
        cachedLookupRecipes = null;
    }

    @Override
    public boolean checkMatchedRecipeAvailable(GTRecipe recipe) {
        if (recipe == null || recipe.recipeType == null || !isRecipeTypeSelected(recipe.recipeType)) {
            return false;
        }
        return super.checkMatchedRecipeAvailable(recipe);
    }

    @Override
    protected boolean checkRecipe(GTRecipe recipe) {
        return recipe != null && recipe.recipeType != null
                && isRecipeTypeSelected(recipe.recipeType)
                && super.checkRecipe(recipe);
    }

    /**
     * 唯一必要的覆写：把原生"单一 getRecipeType() 搜索"扩展为"遍历玩家选中的多个配方类型"。
     * 逐类型走原生 {@code getLookup().getRecipeIterator} —— GTLCore 的 GTRecipeLookupMixin 会在此处
     * 自动把样板总成已激活/已缓存的发配槽内容并入搜索，样板配方的发现与执行全程原生，本类不插手。
     * <p>
     * 结果集走短缓存（三道保险见字段注释），只缓存"候选是谁"、不缓存数量：锁定态绕过、空结果不缓存、
     * 超窗自愈；再叠加 {@link #calculateParallels()} 的"算不出可运行配方就立即失效缓存"——保证机器绝不会
     * 守着陈旧候选集空转，缓存最坏只让新配方晚 ≤1 秒被拾取。
     */
    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        SelectableRecipeTypeSetMachine machine = getMachine();
        if (!machine.hasSelectedRecipeTypes()) {
            return Collections.emptySet();
        }
        if (isLock()) {
            return lookupLockedRecipe();
        }
        long tick = machine.getOffsetTimer();
        // 保险①短窗口自愈 + 保险②空结果不缓存（cachedLookupRecipes 非空才可能命中）
        if (cachedLookupRecipes != null
                && tick - cachedLookupTick >= 0 && tick - cachedLookupTick < LOOKUP_CACHE_TICKS) {
            return cachedLookupRecipes;
        }
        Set<GTRecipe> recipes = searchSelectedRecipeTypes(machine);
        if (recipes.isEmpty()) {
            // 空结果不写缓存：机器空转/缺料时永远实时搜索，料一到立刻开工，杜绝"缓存空集守死不启动"。
            invalidateLookupSetCache();
            return recipes;
        }
        cachedLookupTick = tick;
        cachedLookupRecipes = recipes;
        return recipes;
    }

    private Set<GTRecipe> searchSelectedRecipeTypes(SelectableRecipeTypeSetMachine machine) {
        Set<GTRecipe> recipes = new ObjectOpenHashSet<>();
        for (GTRecipeType type : machine.getRecipeTypes()) {
            if (type == null) {
                continue;
            }
            Iterator<GTRecipe> iterator = type.getLookup().getRecipeIterator(machine, this::checkRecipe);
            if (iterator == null) {
                continue;
            }
            while (iterator.hasNext()) {
                GTRecipe recipe = iterator.next();
                if (recipe != null) {
                    recipes.add(recipe);
                }
            }
        }
        return recipes;
    }

    private Set<GTRecipe> lookupLockedRecipe() {
        GTRecipe locked = getLockRecipe();
        if (locked == null) {
            locked = findFirstSelectedRecipe();
            setLockRecipe(locked);
        } else if (locked.recipeType == null || !isRecipeTypeSelected(locked.recipeType)) {
            setLockRecipe(null);
            locked = findFirstSelectedRecipe();
            setLockRecipe(locked);
        } else if (!checkRecipe(locked)) {
            return Collections.emptySet();
        }
        return locked == null ? Collections.emptySet() : Collections.singleton(locked);
    }

    private GTRecipe findFirstSelectedRecipe() {
        for (GTRecipeType type : getMachine().getRecipeTypes()) {
            if (type == null) {
                continue;
            }
            GTRecipe recipe = type.getLookup().find(getMachine(), this::checkRecipe);
            if (recipe != null) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * 沿用与父类同构的并行分配，但<b>去掉一切缓存</b>：每轮基于当前实时 lookup 结果与机器并行上限实算。
     * 保留本覆写（而非退回父类）是因为 {@link #getTotalParallelLimit()} 用的是消费机器自身可覆写的
     * {@code getRecipeLogicMaxParallel()}——代理执行者等有专属并行公式，父类版本读不到。
     */
    @Override
    @Nullable
    protected ParallelData calculateParallels() {
        Set<GTRecipe> recipes = lookupRecipeIterator();
        if (recipes.isEmpty()) {
            invalidateLookupSetCache();
            return null;
        }
        long totalParallel = getTotalParallelLimit();
        if (totalParallel <= 0L) {
            invalidateLookupSetCache();
            return null;
        }
        long remaining = totalParallel;
        long[] parallels = new long[recipes.size()];
        int index = 0;
        ObjectArrayList<GTRecipe> recipeList = new ObjectArrayList<>(recipes.size());
        LongArrayList remainingWants = new LongArrayList(recipes.size());
        IntArrayList remainingIndices = new IntArrayList(recipes.size());
        for (GTRecipe recipe : recipes) {
            long max = getMaxParallel(recipe, totalParallel);
            if (max <= 0) {
                continue;
            }
            recipeList.add(recipe);
            long allocated = Math.min(max, totalParallel / recipes.size());
            parallels[index] = allocated;
            long want = max - allocated;
            if (want > 0) {
                remainingWants.add(want);
                remainingIndices.add(index);
            }
            remaining -= allocated;
            index++;
        }
        if (recipeList.isEmpty()) {
            // 实测回归：材料不足时若不失效 SET 缓存，会让机器在整个 20-tick 窗口内反复对同一批
            // （可能很大的）候选 GTRecipe 逐个 getMaxParallel() 校验，比"偶尔全量重搜、多数情况下
            // 直接命中空集快速返回"更贵——曾尝试去掉这里的失效，代理执行者延迟从 307μs 涨到 761μs，
            // 已还原。缓存里的候选这轮全部并行为 0（料耗尽等）→ 机器要闲置：立即失效缓存，
            // 下轮重新实时搜索，避免守着已耗尽的陈旧候选集空转（控死保险）。
            invalidateLookupSetCache();
            return null;
        }
        return RecipeCalculationHelper.INSTANCE.getFinalParallelData(
                remaining,
                parallels,
                (LongList) remainingWants,
                (IntList) remainingIndices,
                (ObjectList<GTRecipe>) recipeList);
    }

    protected long getTotalParallelLimit() {
        return saturatedMultiply(getMachine().getRecipeLogicMaxParallel(), getLogicThreadMultiplier());
    }

    protected long getLogicThreadMultiplier() {
        return Math.max(1L, (long) getMultipleThreads());
    }

    protected static long saturatedMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private boolean isRecipeTypeSelected(GTRecipeType type) {
        return type != null && getMachine().isRecipeTypeSelected(type);
    }
}
