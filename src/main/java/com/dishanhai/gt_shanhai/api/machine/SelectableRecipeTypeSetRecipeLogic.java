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
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

public class SelectableRecipeTypeSetRecipeLogic extends GTLAddMultipleWirelessRecipesLogic {

    // 非锁定跨配方轮询缓存：主机（如原初终焉引擎）每 tick 对所有选中配方类型全量 find() 极耗时，
    // 空转时更是每 tick 白查。20-tick（1秒）缓存查找结果，配合脏标记主动失效。
    private static final long LOOKUP_CACHE_TICKS = 20L;
    private long cachedLookupTick = Long.MIN_VALUE;
    private Set<GTRecipe> cachedLookupRecipes;
    private int cachedLookupRecipesHash = 0; // 配方集合指纹，用于快速对比
    
    // calculateParallels 缓存：避免每 tick 重算并行数分配
    private ParallelData cachedParallelData;
    private Set<GTRecipe> cachedParallelRecipes;
    private long cachedParallelLimit = -1L;
    private int cachedParallelRecipesHash = 0; // 配方集合指纹
    
    // getGTRecipe 智能轮询缓存：优先查询上次成功的配方类型
    private GTRecipeType lastSuccessfulRecipeType = null;
    
    // getGTRecipe 返回值缓存：避免每 tick 重新轮询（配方执行阶段缓存）
    private static final long RECIPE_RESULT_CACHE_TICKS = 10L; // 0.5秒缓存（比 lookup 短，快速响应输入变化）
    private GTRecipe cachedGTRecipe = null;
    private boolean cachedGTRecipeValid = false;
    private long cachedGTRecipeTick = Long.MIN_VALUE;

    public SelectableRecipeTypeSetRecipeLogic(SelectableRecipeTypeSetMachine machine) {
        super(machine);
    }

    public SelectableRecipeTypeSetRecipeLogic(SelectableRecipeTypeSetMachine machine,
                                              Predicate<IRecipeLogicMachine> beforeWorking) {
        super(machine, beforeWorking);
    }

    protected void invalidateLookupCache() {
        cachedLookupTick = Long.MIN_VALUE;
        cachedLookupRecipes = null;
        cachedLookupRecipesHash = 0;
        cachedParallelData = null;
        cachedParallelRecipes = null;
        cachedParallelLimit = -1L;
        cachedParallelRecipesHash = 0;
        lastSuccessfulRecipeType = null; // 清空配方类型缓存
        cachedGTRecipe = null; // 清空配方结果缓存
        cachedGTRecipeValid = false;
        cachedGTRecipeTick = Long.MIN_VALUE;
    }

    @Override
    public SelectableRecipeTypeSetMachine getMachine() {
        return (SelectableRecipeTypeSetMachine) super.getMachine();
    }

    @Override
    public boolean checkMatchedRecipeAvailable(GTRecipe recipe) {
        if (recipe == null || recipe.recipeType == null) {
            return false;
        }
        if (!isRecipeTypeSelected(recipe.recipeType)) {
            return false;
        }
        return super.checkMatchedRecipeAvailable(recipe);
    }

    @Override
    public void handleRecipeWorking() {
        super.handleRecipeWorking();
        // 配方执行阶段结束，清空配方结果缓存（物品可能已消耗/生产，下次需重新查找）
        cachedGTRecipe = null;
        cachedGTRecipeValid = false;
        cachedGTRecipeTick = Long.MIN_VALUE;
    }

    public void onRecipeTypeSelectionChanged() {
        invalidateLookupCache();
        interruptRecipe();
    }

    @Override
    protected boolean checkRecipe(GTRecipe recipe) {
        return recipe != null && recipe.recipeType != null
                && isRecipeTypeSelected(recipe.recipeType)
                && super.checkRecipe(recipe);
    }

    @Override
    protected GTRecipe getGTRecipe() {
        if (!getMachine().hasSelectedRecipeTypes()) {
            return null;
        }
        
        // 超快路径：返回值缓存（配方执行阶段，物品输入未变时直接返回上次结果）
        long tick = getMachine().getOffsetTimer();
        if (cachedGTRecipeValid && tick - cachedGTRecipeTick >= 0
                && tick - cachedGTRecipeTick < RECIPE_RESULT_CACHE_TICKS) {
            return cachedGTRecipe;
        }
        
        GTRecipe recipe = super.getGTRecipe();
        if (recipe != null) {
            if (recipe.recipeType != null) {
                lastSuccessfulRecipeType = recipe.recipeType;
            }
            cachedGTRecipe = recipe;
            cachedGTRecipeValid = true;
            cachedGTRecipeTick = tick;
            return recipe;
        }
        
        // 快速路径：优先查询上次成功的配方类型（缓存命中率高时可避免全量轮询）
        if (lastSuccessfulRecipeType != null 
                && getMachine().isRecipeTypeSelected(lastSuccessfulRecipeType)) {
            getMachine().setForcedSearchRecipeType(lastSuccessfulRecipeType);
            try {
                recipe = super.getGTRecipe();
            } finally {
                getMachine().setForcedSearchRecipeType(null);
            }
            if (recipe != null) {
                cachedGTRecipe = recipe;
                cachedGTRecipeValid = true;
                cachedGTRecipeTick = tick;
                return recipe;
            }
        }
        
        // 慢速路径：全量轮询所有配方类型
        for (GTRecipeType type : getMachine().getSelectedRecipeTypes()) {
            if (type == null || type == lastSuccessfulRecipeType) {
                continue; // 跳过 null 和已经查询过的类型
            }
            getMachine().setForcedSearchRecipeType(type);
            try {
                recipe = super.getGTRecipe();
            } finally {
                getMachine().setForcedSearchRecipeType(null);
            }
            if (recipe != null) {
                lastSuccessfulRecipeType = type; // 更新缓存
                cachedGTRecipe = recipe;
                cachedGTRecipeValid = true;
                cachedGTRecipeTick = tick;
                return recipe;
            }
        }
        
        // 未找到配方，缓存 null 结果（避免下次 tick 重复轮询）
        cachedGTRecipe = null;
        cachedGTRecipeValid = true;
        cachedGTRecipeTick = tick;
        return null;
    }

    @Override
    @Nullable
    protected ParallelData calculateParallels() {
        Set<GTRecipe> recipes = lookupRecipeIterator();
        if (recipes.isEmpty()) {
            return null;
        }
        long totalParallel = getTotalParallelLimit();
        if (totalParallel <= 0L) {
            return null;
        }
        
        // 快速路径：用指纹快速判断配方集合是否相同（O(1) vs O(N)）
        int recipesHash = System.identityHashCode(recipes);
        if (cachedParallelData != null 
                && recipesHash == cachedParallelRecipesHash
                && totalParallel == cachedParallelLimit) {
            // 指纹相同但对象不同，双重检查（防止哈希冲突）
            if (recipes == cachedParallelRecipes || recipes.equals(cachedParallelRecipes)) {
                return cachedParallelData;
            }
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
            return null;
        }
        ParallelData result = RecipeCalculationHelper.INSTANCE.getFinalParallelData(
                remaining,
                parallels,
                (LongList) remainingWants,
                (IntList) remainingIndices,
                (ObjectList<GTRecipe>) recipeList);
        
        // 缓存结果（保留原集合引用，避免浅拷贝开销）
        cachedParallelData = result;
        cachedParallelRecipes = recipes;
        cachedParallelRecipesHash = recipesHash;
        cachedParallelLimit = totalParallel;
        return result;
    }

    @Override
    protected Set<GTRecipe> lookupRecipeIterator() {
        if (!getMachine().hasSelectedRecipeTypes()) {
            return Collections.emptySet();
        }
        if (isLock()) {
            return lookupLockedRecipe();
        }

        // 非锁定：20-tick（1秒）缓存全量轮询结果，缓解主机每 tick 全配方类型 find() 的开销。
        // 并行数/输出匹配仍由 calculateParallels→getMaxParallel 每 tick 重算，不影响数量正确性。
        long tick = getMachine().getOffsetTimer();
        if (cachedLookupRecipes != null && tick - cachedLookupTick >= 0
                && tick - cachedLookupTick < LOOKUP_CACHE_TICKS) {
            return cachedLookupRecipes;
        }

        Set<GTRecipe> recipes = new LinkedHashSet<>();
        for (GTRecipeType type : getMachine().getRecipeTypes()) {
            if (type == null) {
                continue;
            }
            GTRecipe recipe = type.getLookup().find(getMachine(), this::checkRecipe);
            if (recipe != null) {
                recipes.add(recipe);
            }
        }
        // 追加内部样板标识的虚拟配方类型：代理覆写了本方法，父类 GTLAddMultipleRecipesLogic
        // 的追加注入（GTLAddRecipesLogicMixins）不会触发，必须在此显式收集标识样板配方。
        // 样板配方已由 helper 内部按槽位 slotAllowsRecipe 校验，无需再过 isRecipeTypeSelected 选中过滤。
        Set<GTRecipe> markedRecipes = com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper
                .collectMarkedPatternRecipes(getMachine());
        if (!markedRecipes.isEmpty()) {
            recipes.addAll(markedRecipes);
        }
        cachedLookupTick = tick;
        cachedLookupRecipes = recipes;
        cachedLookupRecipesHash = System.identityHashCode(recipes);
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

    private boolean isRecipeTypeSelected(GTRecipeType type) {
        if (type == null) {
            return false;
        }
        return getMachine().isRecipeTypeSelected(type);
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
}
