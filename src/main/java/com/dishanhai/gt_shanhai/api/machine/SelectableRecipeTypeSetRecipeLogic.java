package com.dishanhai.gt_shanhai.api.machine;

import com.dishanhai.gt_shanhai.common.item.PatternSlotScopedRecipe;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSearchHelper;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 多配方类型选择集配方逻辑（薄层）。
 * <p>
 * <b>主要职责</b>：把 GTLAdd 原生"单一 {@code getRecipeType()} 搜索"扩展为"遍历玩家选中的多个
 * 配方类型搜索"，并补齐本覆写绕过父类 RETURN Mixin 后缺失的星律槽位候选合并。
 * <p>
 * GTLCore 原生查找仍负责普通候选；星律槽位副本由山海显式追加。副本存在时移除同一真实配方的
 * 未分槽候选，避免相同配方 ID 的多张 AE 主产物样板重新争抢第一个槽；副本解析失败时保留原始候选回退。
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
    //   1) 短窗口自愈——超时必重搜；默认 1 秒，稳定的原初系逻辑可单独覆写；
    //   2) 空结果不缓存——机器空转/缺料时永远实时搜索，料一到立刻开工，杜绝"缓存空集守死不启动"；
    //   3) 选择集变更即时失效（invalidateLookupSetCache）+ 锁定态直接绕过。
    // 数量/扣料仍每周期实时算；模块门控由模块物品/数量变化即时失效（见 calculateParallels /
    // buildFinalWirelessRecipe / 模块 refreshModuleConditionContext），
    // 故缓存最坏只造成"网络新出现的可用配方延迟至对应逻辑的缓存窗口后才被拾取"，
    // 不可能卡产/重复/串产/控死。
    private static final long DEFAULT_LOOKUP_CACHE_TICKS = 20L;
    private long cachedLookupTick = Long.MIN_VALUE;
    private Set<GTRecipe> cachedLookupRecipes;

    // 按配方类型拆分的子缓存 + 稳态自适应退避：解决"选中多个配方类型时，每次真正重搜（整集缓存过期/
    // 结果变化）都要对每个选中类型各调一次昂贵的 type.getLookup().getRecipeIterator()"的开销。
    // 每个类型独立记录自己的候选集与过期窗口——未变化的类型不必跟着"某一个类型的候选变了"陪绑重搜。
    // 窗口本身会退避：连续 STABLE_HITS_TO_GROW 次重搜结果都和上次完全一致（同一批 GTRecipe），
    // 说明这个类型的候选面处于稳态，窗口翻倍（封顶 MAX_LOOKUP_CACHE_TICKS）；结果一变或本次为空
    // 立刻打回基础窗口，绝不让"退避到很久之后才重搜"发生在候选面真的在变化的类型上。
    // 与整集缓存的关系：只是整集缓存 miss 后、逐类型重搜阶段的加速层，不改变整集缓存本身的任何
    // 失效触发点（选择集变更 invalidateLookupSetCache 会连同这里一起清空；calculateParallels 因
    // 材料不足触发的失效同样会清空——完整保留"材料不足时必须能快速重搜命中空集"这条已验证过的
    // 保险，退避只发生在"持续稳定产出"的路径上，不会重现 307μs→761μs 那次回归）。
    private static final long MAX_LOOKUP_CACHE_TICKS = 200L;
    private static final int STABLE_HITS_TO_GROW = 2;

    private static final class TypeCacheEntry {
        long cachedTick = Long.MIN_VALUE;
        long cacheWindow = DEFAULT_LOOKUP_CACHE_TICKS;
        int stableHits = 0;
        Set<GTRecipe> recipes = Collections.emptySet();
    }

    private final Map<GTRecipeType, TypeCacheEntry> perTypeLookupCache = new HashMap<>();
    private long cachedMarkedRecipeTick = Long.MIN_VALUE;
    private Set<GTRecipe> cachedMarkedRecipes = Collections.emptySet();

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
        perTypeLookupCache.clear();
        cachedMarkedRecipeTick = Long.MIN_VALUE;
        cachedMarkedRecipes = Collections.emptySet();
    }

    /**
     * 非空候选集合的缓存时窗。普通选择集机器保持 20 tick；稳定运行的原初系逻辑可单独延长，
     * 不影响代理执行者等动态目标机器。
     */
    protected long getLookupCacheTicks() {
        return DEFAULT_LOOKUP_CACHE_TICKS;
    }

    protected long getMaxLookupCacheTicks() {
        return MAX_LOOKUP_CACHE_TICKS;
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
     * 守着陈旧候选集空转。具体时窗由 {@link #getLookupCacheTicks()} 决定。
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
        long cacheTicks = getLookupCacheTicks();
        // 保险①短窗口自愈 + 保险②空结果不缓存（cachedLookupRecipes 非空才可能命中）
        if (cachedLookupRecipes != null
                && cacheTicks > 0L
                && tick - cachedLookupTick >= 0 && tick - cachedLookupTick < cacheTicks) {
            return mergeMarkedPatternRecipes(machine, cachedLookupRecipes);
        }
        Set<GTRecipe> recipes = searchSelectedRecipeTypes(machine);
        Set<GTRecipe> merged = mergeMarkedPatternRecipes(machine, recipes);
        if (merged.isEmpty()) {
            // 空结果不写缓存：机器空转/缺料时永远实时搜索，料一到立刻开工，杜绝"缓存空集守死不启动"。
            invalidateLookupSetCache();
            return merged;
        }
        if (recipes.isEmpty()) {
            // 次要输出样板可能让未作用域基础候选被第一输出指纹拒绝，但 active scoped 槽仍是有效候选。
            // 不缓存空基础集；当前轮直接返回已合并的槽位候选，下一轮继续实时确认 active 状态。
            invalidateLookupSetCache();
            return merged;
        }
        cachedLookupTick = tick;
        cachedLookupRecipes = recipes;
        return merged;
    }

    private Set<GTRecipe> mergeMarkedPatternRecipes(SelectableRecipeTypeSetMachine machine, Set<GTRecipe> base) {
        Set<GTRecipe> marked = collectActiveMarkedPatternRecipesCached(machine);
        if (marked.isEmpty()) {
            return base;
        }
        LinkedHashSet<GTRecipe> merged = new LinkedHashSet<>();
        if (base != null) {
            for (GTRecipe candidate : base) {
                boolean shadowed = false;
                for (GTRecipe scoped : marked) {
                    if (PatternSlotScopedRecipe.represents(scoped, candidate)) {
                        shadowed = true;
                        break;
                    }
                }
                if (!shadowed) {
                    merged.add(candidate);
                }
            }
        }
        merged.addAll(marked);
        return merged;
    }

    private Set<GTRecipe> collectActiveMarkedPatternRecipesCached(SelectableRecipeTypeSetMachine machine) {
        long tick = machine.getOffsetTimer();
        if (tick == cachedMarkedRecipeTick) {
            return cachedMarkedRecipes;
        }
        Set<GTRecipe> marked = RecipeTypePatternSearchHelper.collectActiveMarkedPatternRecipes(machine);
        cachedMarkedRecipeTick = tick;
        cachedMarkedRecipes = marked == null ? Collections.emptySet() : marked;
        return cachedMarkedRecipes;
    }

    private Set<GTRecipe> searchSelectedRecipeTypes(SelectableRecipeTypeSetMachine machine) {
        long tick = machine.getOffsetTimer();
        Set<GTRecipe> recipes = new ObjectOpenHashSet<>();
        for (GTRecipeType type : machine.getRecipeTypes()) {
            if (type == null) {
                continue;
            }
            recipes.addAll(searchOneRecipeTypeCached(type, machine, tick));
        }
        return recipes;
    }

    /**
     * 单个配方类型的子缓存查找 + 稳态自适应退避，详见字段注释。命中直接返回上次候选集，
     * 不命中才真正调用一次 {@code type.getLookup().getRecipeIterator()}。
     */
    private Set<GTRecipe> searchOneRecipeTypeCached(GTRecipeType type, SelectableRecipeTypeSetMachine machine, long tick) {
        TypeCacheEntry entry = perTypeLookupCache.get(type);
        if (entry != null
                && !entry.recipes.isEmpty()
                && tick - entry.cachedTick >= 0 && tick - entry.cachedTick < entry.cacheWindow) {
            return entry.recipes;
        }
        Set<GTRecipe> found = new ObjectOpenHashSet<>();
        Iterator<GTRecipe> iterator = type.getLookup().getRecipeIterator(machine, this::checkRecipe);
        if (iterator != null) {
            while (iterator.hasNext()) {
                GTRecipe recipe = iterator.next();
                if (recipe != null) {
                    found.add(recipe);
                }
            }
        }
        if ("gtceu:nightmare_crafting".equals(type.registryName == null ? null : type.registryName.toString())) {
            boolean isCapMachine = machine instanceof org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
            int mePartCount = -1;
            if (isCapMachine) {
                mePartCount = ((org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine) machine)
                        .getMEPatternRecipeHandleParts().size();
            }
            com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics.hit("selectableSet.nightmareCraftingLookup",
                    "machine=" + machine.getClass().getName() + " isCapMachine=" + isCapMachine
                            + " mePatternHandlePartCount=" + mePartCount + " foundCount=" + found.size());
        }
        if (entry == null) {
            entry = new TypeCacheEntry();
            perTypeLookupCache.put(type, entry);
        }
        if (found.isEmpty()) {
            // 空结果不缓存/不退避：和整集缓存同款保险，类型暂无候选时保持基础窗口，料一到立刻重搜。
            entry.cachedTick = Long.MIN_VALUE;
            entry.cacheWindow = DEFAULT_LOOKUP_CACHE_TICKS;
            entry.stableHits = 0;
            entry.recipes = Collections.emptySet();
            return entry.recipes;
        }
        if (found.equals(entry.recipes)) {
            entry.stableHits++;
            if (entry.stableHits >= STABLE_HITS_TO_GROW) {
                entry.cacheWindow = Math.min(getMaxLookupCacheTicks(), entry.cacheWindow * 2);
                entry.stableHits = 0;
            }
        } else {
            // 候选集变了：说明这个类型的可选面并不稳定，打回基础窗口重新起算退避。
            entry.stableHits = 0;
            entry.cacheWindow = DEFAULT_LOOKUP_CACHE_TICKS;
        }
        entry.cachedTick = tick;
        entry.recipes = found;
        return entry.recipes;
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
