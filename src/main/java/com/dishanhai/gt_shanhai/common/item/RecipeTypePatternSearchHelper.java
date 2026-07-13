package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.crafting.pattern.AEProcessingPattern;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferProxyPartMachine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class RecipeTypePatternSearchHelper {

    private RecipeTypePatternSearchHelper() {
    }

    // ===== 反射缓存 =====
    // 每个 Class 只走一次类层级搜索，后续 O(1) 命中。
    // Optional.empty() 表示"确认不存在该方法/字段"，避免重复扫描。
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Optional<Method>>> METHOD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Optional<Field>>> FIELD_CACHE =
            new ConcurrentHashMap<>();
    // findHandlerMachine 需要尝试 4 个混淆名，缓存每个 Class 哪个名字有效
    private static final ConcurrentHashMap<Class<?>, String> HANDLER_MACHINE_METHOD_CACHE =
            new ConcurrentHashMap<>();
    private static final String[] HANDLER_MACHINE_CANDIDATES =
            {"getMachine", "mo293getMachine", "mo292getMachine", "mo291getMachine"};

    // ===== 虚拟供料"一次性下单"状态 =====
    // 样板 = 下一次单：放进槽位触发一次抽料，抽到了就标记这单完成，之后不管缓冲区
    // 被吃空多少次都不再自动补，直到样板被取出（clearOrderFulfilled）才允许下一单。
    // 弱引用外层 key（buffer 实例）：方块卸载/破坏后随 GC 自然回收，不需要手动清理。
    private static final Map<MEPatternBufferPartMachineBase, boolean[]> ORDER_FULFILLED_SLOTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    // ===== 样板推断缓存 =====
    // key: buffer 实例（弱引用，随方块卸载自然回收）；value: slot -> (样板 ItemStack 身份哈希, 推断出的 GTRecipe)。
    // 槽位内容不变时 ItemStackHandler#getStackInSlot 返回同一引用，内容变化才会替换引用，
    // 用身份哈希判断即可安全复用，避免每次刷新都重新走 AE2 样板 NBT 解码 + 配方全量匹配
    // （同类问题见 PrimordialModuleRecipeLogic.peekRecipeCached，此处是它未覆盖到的另一条调用路径）。
    private static final Map<MEPatternBufferPartMachineBase, it.unimi.dsi.fastutil.ints.Int2ObjectMap<PatternPeekCacheEntry>>
            PATTERN_PEEK_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class PatternPeekCacheEntry {
        final int stackIdentityHash;
        final GTRecipe recipe;
        PatternPeekCacheEntry(int stackIdentityHash, GTRecipe recipe) {
            this.stackIdentityHash = stackIdentityHash;
            this.recipe = recipe;
        }
    }

    private static GTRecipe peekRecipeCached(MEPatternBufferPartMachineBase buffer, int slot, ItemStack stack, Level level) {
        int stackHash = System.identityHashCode(stack);
        synchronized (PATTERN_PEEK_CACHE) {
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<PatternPeekCacheEntry> slotCache = PATTERN_PEEK_CACHE.get(buffer);
            if (slotCache != null) {
                PatternPeekCacheEntry entry = slotCache.get(slot);
                if (entry != null && entry.stackIdentityHash == stackHash) {
                    return entry.recipe;
                }
            }
        }
        GTRecipe recipe = PatternRecipeTypeHelper.peekRecipe(stack, level);
        synchronized (PATTERN_PEEK_CACHE) {
            PATTERN_PEEK_CACHE.computeIfAbsent(buffer, b -> new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>())
                    .put(slot, new PatternPeekCacheEntry(stackHash, recipe));
        }
        return recipe;
    }

    public static Iterator<GTRecipe> searchRecipe(IRecipeLogicMachine machine) {
        if (machine == null) return Collections.emptyIterator();

        GTRecipeType forcedType = ProgrammableHatchPartMachine.getSelectedRecipeTypeFor(machine);
        GTRecipeType baseType = forcedType != null ? forcedType : machine.getRecipeType();
        Iterator<GTRecipe> baseIterator = searchType(baseType, machine);
        ProgrammableHatchPartMachine.logRecipeTypeRedirect(machine, forcedType, machine.getRecipeType());
        if (forcedType != null) {
            return baseIterator;
        }

        Set<GTRecipe> virtualRecipes = collectMarkedPatternRecipes(machine);
        if (virtualRecipes.isEmpty()) {
            return baseIterator;
        }
        return new AppendingRecipeIterator(baseIterator, virtualRecipes.iterator());
    }

    public static Set<GTRecipe> collectMarkedPatternRecipes(IRecipeLogicMachine machine) {
        LinkedHashSet<GTRecipe> result = new LinkedHashSet<>();
        if (!(machine instanceof IRecipeCapabilityMachine capabilityMachine)) return result;

        List<MEPatternRecipeHandlePart> parts = capabilityMachine.getMEPatternRecipeHandleParts();
        if (parts != null && !parts.isEmpty()) {
            for (MEPatternRecipeHandlePart part : parts) {
                if (part == null) continue;
                Object[] handlers = part.getMERecipeHandlers();
                if (handlers == null) continue;
                for (Object handler : handlers) {
                    collectMarkedPatternRecipesFromHandler(capabilityMachine, handler, result);
                }
            }
        }
        collectMarkedPatternRecipesFromParts(machine, capabilityMachine, result);
        return applyRecipeTypeSwitch(machine, result);
    }

    /**
     * 原生 GTCEu/GTLCore 机器(不实现 IRecipeCapabilityMachine)的虚拟候选收集。
     * 只走 IMultiController#getParts() 从结构仓室扫星律样板总成,不依赖 handler 激活路径。
     * capabilityMachine 传 null:activatePatternRecipe 的 guard 会安全跳过供料注册,
     * 原生机器的供料由 mixin 侧手动 handleRecipeInput 完成,不需要 tryAddAndActiveMERhp。
     */
    public static Set<GTRecipe> collectNativeVirtualRecipes(IRecipeLogicMachine machine) {
        LinkedHashSet<GTRecipe> result = new LinkedHashSet<>();
        if (!(machine instanceof IMultiController controller)) return result;
        for (IMultiPart part : controller.getParts()) {
            if (part == null) continue;
            Object patternMachine = resolvePatternBuffer(part);
            if (patternMachine == null) continue;
            collectMarkedPatternRecipesFromMachine(null, part, patternMachine,
                    readActiveSlots(patternMachine), result);
        }
        return applyRecipeTypeSwitch(machine, result);
    }

    public static Set<GTRecipe> collectMarkedPatternRecipesFromBuffer(IRecipeLogicMachine machine, Object buffer) {
        LinkedHashSet<GTRecipe> result = new LinkedHashSet<>();
        IRecipeCapabilityMachine capabilityMachine = machine instanceof IRecipeCapabilityMachine
                ? (IRecipeCapabilityMachine) machine
                : null;
        collectMarkedPatternRecipesFromMachine(capabilityMachine, buffer, buffer, readActiveSlots(buffer), result);
        return applyRecipeTypeSwitch(machine, result);
    }

    public static Set<GTRecipeType> collectDeclaredRecipeTypes(IRecipeLogicMachine machine) {
        LinkedHashSet<GTRecipeType> result = new LinkedHashSet<>();
        if (!(machine instanceof IRecipeCapabilityMachine capabilityMachine)) return result;

        List<MEPatternRecipeHandlePart> parts = capabilityMachine.getMEPatternRecipeHandleParts();
        if (parts == null || parts.isEmpty()) return result;

        for (MEPatternRecipeHandlePart part : parts) {
            if (part == null) continue;
            Object[] handlers = part.getMERecipeHandlers();
            if (handlers == null) continue;
            for (Object handler : handlers) {
                collectDeclaredRecipeTypesFromHandler(handler, result);
            }
        }
        return result;
    }

    private static void collectMarkedPatternRecipesFromHandler(IRecipeCapabilityMachine capabilityMachine, Object handler,
            Set<GTRecipe> result) {
        Object ownerMachine = findHandlerMachine(handler);
        collectMarkedPatternRecipesFromMachine(capabilityMachine, ownerMachine, resolvePatternBuffer(ownerMachine),
                readActiveSlots(handler), result);
    }

    private static void collectMarkedPatternRecipesFromParts(IRecipeLogicMachine machine,
            IRecipeCapabilityMachine capabilityMachine, Set<GTRecipe> result) {
        if (!(machine instanceof IMultiController controller)) return;
        for (IMultiPart part : controller.getParts()) {
            if (part == null) continue;
            Object patternMachine = resolvePatternBuffer(part);
            Object slotSource = patternMachine == null ? part : patternMachine;
            collectMarkedPatternRecipesFromMachine(capabilityMachine, part, patternMachine,
                    readActiveSlots(slotSource), result);
            collectPlainPatternRecipesFromPart(machine, capabilityMachine, part, result);
        }
    }

    /**
     * 通用样板总成（GTLCore/GTLAdd 超级样板总成等，不实现 RecipeTypePatternSlotAccess 的样板）首配路径：
     * 这类样板既进不了上面的星律扫描（不是 RecipeTypePatternSlotAccess），其 getCachedGTRecipe 也是
     * "跑过一次才有内容"的懒缓存——此前只有 PrimordialModuleRecipeLogic.addPlainPatternRecipes 给"模块"
     * 补过这条路径；原始终焉引擎主机（PrimordialOmegaEngineRecipeLogic 直接继承本类的 lookupRecipeIterator，
     * 没有任何 merge 逻辑）和代理执行者完全没有等价能力，挂超级样板总成的配方永远进不了候选池
  d（2026-07-11 补齐，ERR-20260711-003 同批问题）。直接只读解码样板物品本身，按本机当前选中的
     * 配方类型过滤，激活链与星律路径一致；AE2 网络没有对应物料时 activatePatternRecipe 会自然跳过。
     */
    private static void collectPlainPatternRecipesFromPart(IRecipeLogicMachine machine,
            IRecipeCapabilityMachine capabilityMachine, Object part, Set<GTRecipe> result) {
        if (part instanceof RecipeTypePatternSlotAccess) return; // 星律走专属路径，不重复处理
        if (!(part instanceof MEPatternBufferPartMachine buffer)) return;
        var level = buffer.getLevel();
        var inventory = buffer.getPatternInventory();
        if (level == null || inventory == null) return;
        int slotCount = inventory.getSlots();
        for (int slot = 0; slot < slotCount; slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) {
                clearOrderFulfilled(buffer, slot); // 样板被取出：这一单彻底结束，下次放样板算新单
                continue;
            }
            GTRecipe recipe = peekRecipeCached(buffer, slot, stack, level);
            if (recipe == null || !isSelectedOnMachine(machine, recipe.recipeType)) continue;
            topUpVirtualSupply(buffer, slot, stack);
            activatePatternRecipe(capabilityMachine, buffer, recipe, slot);
            result.add(recipe);
        }
    }

    private static boolean isSelectedOnMachine(IRecipeLogicMachine machine, GTRecipeType type) {
        if (type == null) return false;
        if (machine instanceof SelectableRecipeTypeSetMachine selectable) {
            return selectable.isRecipeTypeSelected(type);
        }
        return true;
    }

    private static void collectMarkedPatternRecipesFromMachine(IRecipeCapabilityMachine capabilityMachine,
            Object ownerMachine, Object patternMachine, int[] activeSlots, Set<GTRecipe> result) {
        if (!(patternMachine instanceof RecipeTypePatternSlotAccess access)
                || !(patternMachine instanceof MEPatternBufferPartMachineBase)) {
            return;
        }
        if (activeSlots != null) {
            for (int slot : activeSlots) {
                GTRecipe recipe = access.gtShanhai$getPatternRecipe(slot);
                if (recipe != null && access.gtShanhai$slotAllowsRecipe(slot, recipe)) {
                    activatePatternRecipe(capabilityMachine, ownerMachine, recipe, slot);
                    result.add(recipe);
                }
            }
        }
        collectFirstSparkPatternRecipes(capabilityMachine, ownerMachine, patternMachine, access, activeSlots, result);
    }

    /**
     * 首配路径兜底：GTLCore 原生 InternalSlot.isActive() 要求 AE2 真的把一次合成任务推送进该槽位后才为 true，
     * getActiveSlots() 才会包含它——星律样板总成从未被真实 AE2 合成请求命中过时，这个条件永远不满足，
     * 上面的 activeSlots 分支永远拿不到候选（鸡生蛋，与 ERR-20260710-001 同一类问题）。此前只在
     * PrimordialModuleRecipeLogic.addPlainPatternRecipes 里给"非星律"样板总成补过首配路径，星律样板总成
     * 自身这条路径从未修（2026-07-11 补齐）。直接只读解码样板物品本身、按槽位规则校验后走同一激活链，
     * AE2 网络没有对应物料时 activatePatternRecipe 内部 handleRecipe 会返回负数自然跳过，不会绕过真实供料校验。
     */
    private static void collectFirstSparkPatternRecipes(IRecipeCapabilityMachine capabilityMachine,
            Object ownerMachine, Object patternMachine, RecipeTypePatternSlotAccess access,
            int[] activeSlots, Set<GTRecipe> result) {
        if (!(patternMachine instanceof MEPatternBufferPartMachine buffer)) return;
        var inventory = buffer.getPatternInventory();
        if (inventory == null) return;
        int slotCount = inventory.getSlots();
        for (int slot = 0; slot < slotCount; slot++) {
            if (containsSlot(activeSlots, slot)) continue; // 真实 AE2 下单已激活，走既有分支，不碰虚拟下单状态
            if (inventory.getStackInSlot(slot).isEmpty()) {
                clearOrderFulfilled(buffer, slot); // 样板被取出：这一单彻底结束，下次放样板算新单
                continue;
            }
            GTRecipe recipe = access.gtShanhai$getPatternRecipe(slot);
            if (recipe != null && access.gtShanhai$slotAllowsRecipe(slot, recipe)) {
                topUpVirtualSupply(buffer, slot, inventory.getStackInSlot(slot));
                activatePatternRecipe(capabilityMachine, ownerMachine, recipe, slot);
                result.add(recipe);
            }
        }
    }

    /**
     * 虚拟供料批量预填：GTLCore 原生 InternalSlot 只认"单次 AE 下单送进来多少"，对着无限并行主机
     * 天然只够跑 1 份。这里换一种更安全的思路：不碰并行估算/扣料逻辑本身，只把
     * InternalSlot 这个"料仓"从无线 ME 网络按批量真实预填满——预填之后，GTLCore 原生的
     * IParallelLogic 并行估算和 MEPatternRecipeHandlePart 扣料就是在真实数量上工作，不需要
     * gt_shanhai 自己重新实现一套并行计算或提交逻辑。
     * <p>
     * 只在槽位当前真实库存已耗尽（itemInventory/fluidInventory 皆空）时才补一批，避免每次
     * lookup 都重复提取、无限增长；先对每个输入做 SIMULATE 探测网络真实可提取量，取瓶颈输入
     * 换算出的批量上限（不超过配置 patternVirtualSupplyBatchParallel，即"这一单"的目标总量，
     * 与消费机器自身的并行上限无关——机器并行低时就多跑几轮/几 tick 消耗完这一整批，机器并行
     * 高时一轮就能吃完，批量本身不因此被压缩），再统一按该批量做真实 MODULATE 提取——避免某个
     * 输入库存充裕、另一个稀缺时，充裕的那份被无谓提走锁死在缓冲区里。虚拟物品只在这批真实
     * MODULATE 提取时才被消耗（写入 InternalSlot 供配方扣料），用完即从网络中消失，不会额外
     * 滞留或被重复计数。
     * <p>
     * <b>一次性下单</b>：本方法只在这个槽位这一次"放样板"里真正成功提取过一次才算下单完成
     * （见 {@link #ORDER_FULFILLED_SLOTS}），完成后不再重复触发，即使 InternalSlot 之后被
     * 正常消费清空——这是刻意行为：批量已经按"这一单"的目标总量一次性给足，不需要也不应该
     * 再驱动无限期反复重新下单（那样等于给样板装了个停不下来的自动合成循环，不是真实机器的
     * 正常运转方式）。网络暂时没货时不标记完成，允许下一轮重试，直到真正抽到过一次为止。
     */
    private static void topUpVirtualSupply(MEPatternBufferPartMachineBase buffer, int slot, ItemStack patternStack) {
        if (patternStack == null || patternStack.isEmpty()) return;
        if (isOrderFulfilled(buffer, slot)) return; // 这一单已经下过料，不再自动重下
        Level level = buffer.getLevel();
        if (level == null) return;

        // InternalSlot 是 GTLCore 内部类型（编译期不可见，只能整段走反射），下面全部以 Object 传递。
        Object internalSlot = invokeWithInt(buffer, "getInternalSlot", slot);
        if (internalSlot == null) return;
        if (!isEmptyMap(invokeNoArg(internalSlot, "getItemStackInputMap"))
                || !isEmptyMap(invokeNoArg(internalSlot, "getFluidStackInputMap"))) {
            return; // 槽位还有真实库存没消费完，本轮不再补
        }

        IGrid grid = buffer.getGrid();
        if (grid == null) return;
        IPatternDetails details;
        try {
            details = PatternDetailsHelper.decodePattern(patternStack, level);
        } catch (RuntimeException ignored) {
            return;
        }
        if (!(details instanceof AEProcessingPattern pattern)) return;
        GenericStack[] inputs = pattern.getSparseInputs();
        if (inputs == null || inputs.length == 0) return;

        Object actionSourceObj = readField(buffer, "actionSource");
        if (!(actionSourceObj instanceof IActionSource actionSource)) return;

        MEStorage storage = grid.getStorageService().getInventory();
        long achievable = DShanhaiConfig.COMMON.patternVirtualSupplyBatchParallel.get();
        for (GenericStack in : inputs) {
            if (in == null || in.amount() <= 0) continue;
            long available = storage.extract(in.what(), Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
            achievable = Math.min(achievable, available / in.amount());
            if (achievable <= 0) return;
        }
        if (achievable <= 0) return;

        boolean extractedAny = false;
        for (GenericStack in : inputs) {
            if (in == null || in.amount() <= 0) continue;
            long want = saturatedMultiply(in.amount(), achievable);
            long extracted = storage.extract(in.what(), want, Actionable.MODULATE, actionSource);
            if (extracted > 0) {
                invokeAdd(internalSlot, in.what(), extracted);
                extractedAny = true;
            }
        }
        if (extractedAny) {
            markOrderFulfilled(buffer, slot); // 真正下过料了，这一单到此为止，不再自动重下
        }
    }

    private static boolean isEmptyMap(Object mapObj) {
        return !(mapObj instanceof Map<?, ?> map) || map.isEmpty();
    }

    private static boolean isOrderFulfilled(MEPatternBufferPartMachineBase buffer, int slot) {
        boolean[] flags = ORDER_FULFILLED_SLOTS.get(buffer);
        return flags != null && slot >= 0 && slot < flags.length && flags[slot];
    }

    private static void markOrderFulfilled(MEPatternBufferPartMachineBase buffer, int slot) {
        if (slot < 0) return;
        synchronized (ORDER_FULFILLED_SLOTS) {
            boolean[] flags = ORDER_FULFILLED_SLOTS.get(buffer);
            if (flags == null || slot >= flags.length) {
                boolean[] grown = new boolean[Math.max(slot + 1, flags == null ? 0 : flags.length)];
                if (flags != null) {
                    System.arraycopy(flags, 0, grown, 0, flags.length);
                }
                flags = grown;
                ORDER_FULFILLED_SLOTS.put(buffer, flags);
            }
            flags[slot] = true;
        }
    }

    private static void clearOrderFulfilled(MEPatternBufferPartMachineBase buffer, int slot) {
        boolean[] flags = ORDER_FULFILLED_SLOTS.get(buffer);
        if (flags != null && slot >= 0 && slot < flags.length) {
            flags[slot] = false;
        }
    }

    private static long saturatedMultiply(long a, long b) {
        if (a <= 0 || b <= 0) return 0L;
        if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
        return a * b;
    }

    /**
     * 带缓存的单 int 参数方法调用。与 {@link #invokeNoArg} 同款缓存策略，key 额外带 "(int)" 后缀
     * 避免跟无参方法同名时撞缓存。用于 {@code MEPatternBufferPartMachineBase#getInternalSlot(int)}
     * 这类 protected 且带参的访问器。
     */
    private static Object invokeWithInt(Object target, String methodName, int arg) {
        if (target == null) return null;
        Class<?> startClass = target.getClass();
        ConcurrentHashMap<String, Optional<Method>> classCache =
                METHOD_CACHE.computeIfAbsent(startClass, k -> new ConcurrentHashMap<>());
        String cacheKey = methodName + "(int)";
        Optional<Method> cached = classCache.get(cacheKey);
        if (cached != null) {
            if (!cached.isPresent()) return null;
            try {
                return cached.get().invoke(target, arg);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        Class<?> type = startClass;
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(methodName, int.class);
                method.setAccessible(true);
                classCache.put(cacheKey, Optional.of(method));
                return method.invoke(target, arg);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                classCache.put(cacheKey, Optional.empty());
                return null;
            }
        }
        classCache.put(cacheKey, Optional.empty());
        return null;
    }

    /**
     * 带缓存的 {@code add(AEKey, long)} 调用。同款缓存策略，key 固定 "add(AEKey,long)"。
     * 用于把真实从无线 ME 网络提取出的物料写回 InternalSlot 的内部计数（不产生任何新增物品，
     * 调用前必须已经用 {@link MEStorage#extract} 的 {@code Actionable.MODULATE} 真实提取过等量）。
     */
    private static void invokeAdd(Object target, AEKey key, long amount) {
        if (target == null) return;
        Class<?> startClass = target.getClass();
        ConcurrentHashMap<String, Optional<Method>> classCache =
                METHOD_CACHE.computeIfAbsent(startClass, k -> new ConcurrentHashMap<>());
        String cacheKey = "add(AEKey,long)";
        Optional<Method> cached = classCache.get(cacheKey);
        if (cached != null) {
            if (!cached.isPresent()) return;
            try {
                cached.get().invoke(target, key, amount);
            } catch (ReflectiveOperationException ignored) {
                // ignored
            }
            return;
        }
        Class<?> type = startClass;
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod("add", AEKey.class, long.class);
                method.setAccessible(true);
                classCache.put(cacheKey, Optional.of(method));
                method.invoke(target, key, amount);
                return;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                classCache.put(cacheKey, Optional.empty());
                return;
            }
        }
        classCache.put(cacheKey, Optional.empty());
    }

    private static boolean containsSlot(int[] slots, int slot) {
        if (slots == null) return false;
        for (int s : slots) {
            if (s == slot) return true;
        }
        return false;
    }

    private static Object resolvePatternBuffer(Object ownerMachine) {
        if (ownerMachine instanceof RecipeTypePatternSlotAccess) return ownerMachine;
        if (ownerMachine instanceof MEPatternBufferProxyPartMachine proxy) {
            return proxy.getBuffer();
        }
        return null;
    }

    private static void activatePatternRecipe(IRecipeCapabilityMachine capabilityMachine, Object ownerMachine,
            GTRecipe recipe, int slot) {
        boolean guardFail = capabilityMachine == null || recipe == null || !(ownerMachine instanceof IMEPatternPartMachine);
        if (guardFail) return;
        MEPatternRecipeHandlePart handlePart = MEPatternRecipeHandlePart.of((IMEPatternPartMachine) ownerMachine);
        int handledSlot = handlePart.handleRecipe(recipe, copyRecipeContents(recipe.inputs), true, true);
        if (handledSlot < 0) return;
        capabilityMachine.tryAddAndActiveMERhp(handlePart, recipe, handledSlot);
    }

    /**
     * 供外部逻辑（原始终焉引擎模块的通用样板路径等）按配方激活样板供料：
     * 注册样板总成的 handle part 到宿主机器并绑定配方槽位，与星律虚拟路径同一激活链。
     */
    public static void activatePatternRecipeFor(IRecipeCapabilityMachine capabilityMachine, Object ownerMachine,
            GTRecipe recipe, int slot) {
        activatePatternRecipe(capabilityMachine, ownerMachine, recipe, slot);
    }

    private static Reference2ObjectMap<RecipeCapability<?>, List<Object>> copyRecipeContents(
            Map<RecipeCapability<?>, List<Content>> contents) {
        Reference2ObjectMap<RecipeCapability<?>, List<Object>> copied = new Reference2ObjectOpenHashMap<>();
        if (contents == null || contents.isEmpty()) return copied;
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : contents.entrySet()) {
            List<Object> values = new ObjectArrayList<>();
            List<Content> contentList = entry.getValue();
            if (contentList != null) {
                for (Content content : contentList) {
                    if (content != null) {
                        values.add(content.getContent());
                    }
                }
            }
            copied.put(entry.getKey(), values);
        }
        return copied;
    }

    private static Set<GTRecipe> applyRecipeTypeSwitch(IRecipeLogicMachine machine, Set<GTRecipe> recipes) {
        if (machine == null || recipes == null || recipes.isEmpty()) return recipes;

        DShanhaiConfig.ConfigValues.RecipeTypePatternSwitchMode mode =
                DShanhaiConfig.COMMON.recipeTypePatternSwitchMode.get();

        // 严格模式：必须有山海可编程仓随主机成型并成功把宿主切到样板类型才放行；
        // 无可编程仓或宿主无法切换时，虚拟样板不参与执行（保留旧语义，要求样板类型唯一）。
        if (mode == DShanhaiConfig.ConfigValues.RecipeTypePatternSwitchMode.PROGRAMMABLE_HATCH_REQUIRED) {
            GTRecipeType target = getUniqueRecipeType(recipes);
            if (target == null) return Collections.emptySet();
            return ProgrammableHatchPartMachine.switchRecipeTypeFor(machine, target, true)
                    ? recipes
                    : Collections.emptySet();
        }

        // 纯虚拟注入（默认 VIRTUAL_ACTIVE_TYPE）：不校验宿主是否原生支持该配方类型，
        // 也不切换宿主 activeRecipeType。样板配方已作为完整 GTRecipe 通过 MEPatternRecipeHandlePart
        // 挂载并入执行队列，执行不依赖宿主原生类型，故跨配方类型样板可虚拟直跑，且支持多类型混装。
        return recipes;
    }

    private static GTRecipeType getUniqueRecipeType(Set<GTRecipe> recipes) {
        GTRecipeType target = null;
        for (GTRecipe recipe : recipes) {
            if (recipe == null || recipe.recipeType == null) continue;
            if (target == null) {
                target = recipe.recipeType;
                continue;
            }
            if (!recipeTypeEquals(target, recipe.recipeType)) {
                return null;
            }
        }
        return target;
    }

    private static void collectDeclaredRecipeTypesFromHandler(Object handler, Set<GTRecipeType> result) {
        Object machine = resolvePatternBuffer(findHandlerMachine(handler));
        if (!(machine instanceof RecipeTypePatternSlotAccess access)
                || !(machine instanceof MEPatternBufferPartMachineBase)) {
            return;
        }

        int[] activeSlots = readActiveSlots(handler);
        if (activeSlots == null || activeSlots.length == 0) return;
        for (int slot : activeSlots) {
            GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(access.gtShanhai$getPatternRecipeTypeId(slot));
            if (type != null) {
                result.add(type);
            }
        }
    }

    private static Object findHandlerMachine(Object handler) {
        Object current = unwrapProxyHandler(handler);
        if (current == null) return null;
        // 缓存命中：直接调已知有效的方法名
        String cached = HANDLER_MACHINE_METHOD_CACHE.get(current.getClass());
        if (cached != null) {
            return invokeNoArg(current, cached);
        }
        // 首次：尝试候选列表，记录哪个成功
        for (String name : HANDLER_MACHINE_CANDIDATES) {
            Object machine = invokeNoArg(current, name);
            if (machine != null) {
                HANDLER_MACHINE_METHOD_CACHE.put(current.getClass(), name);
                return machine;
            }
        }
        return null;
    }

    private static int[] readActiveSlots(Object handler) {
        Object activeSlots = invokeNoArg(handler, "getActiveSlots");
        return activeSlots instanceof int[] ? (int[]) activeSlots : new int[0];
    }

    private static Object unwrapProxyHandler(Object handler) {
        Object current = handler;
        for (int i = 0; i < 4; i++) {
            Object next = readField(current, "handler");
            if (next == null || next == current) return current;
            current = next;
        }
        return current;
    }

    /**
     * 带缓存的字段读取。第一次走类层级搜索并缓存 Field；
     * 后续调用直接取缓存，Optional.empty() 表示已确认不存在。
     */
    private static Object readField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> startClass = target.getClass();
        ConcurrentHashMap<String, Optional<Field>> classCache =
                FIELD_CACHE.computeIfAbsent(startClass, k -> new ConcurrentHashMap<>());
        Optional<Field> cached = classCache.get(fieldName);
        if (cached != null) {
            if (!cached.isPresent()) return null;
            try {
                return cached.get().get(target);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        // 首次：走类层级搜索
        Class<?> type = startClass;
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                classCache.put(fieldName, Optional.of(field));
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                classCache.put(fieldName, Optional.empty());
                return null;
            }
        }
        classCache.put(fieldName, Optional.empty());
        return null;
    }

    /**
     * 带缓存的无参方法调用。第一次走类层级搜索并缓存 Method；
     * 后续调用直接取缓存，Optional.empty() 表示已确认不存在。
     */
    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        Class<?> startClass = target.getClass();
        ConcurrentHashMap<String, Optional<Method>> classCache =
                METHOD_CACHE.computeIfAbsent(startClass, k -> new ConcurrentHashMap<>());
        Optional<Method> cached = classCache.get(methodName);
        if (cached != null) {
            if (!cached.isPresent()) return null;
            try {
                return cached.get().invoke(target);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        // 首次：走类层级搜索
        Class<?> type = startClass;
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                classCache.put(methodName, Optional.of(method));
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException ignored) {
                classCache.put(methodName, Optional.empty());
                return null;
            }
        }
        classCache.put(methodName, Optional.empty());
        return null;
    }

    private static Iterator<GTRecipe> searchType(GTRecipeType type, IRecipeLogicMachine machine) {
        if (type == null || machine == null) return Collections.emptyIterator();
        Iterator<GTRecipe> iterator = type.searchRecipe(machine);
        return iterator == null ? Collections.emptyIterator() : iterator;
    }

    private static boolean recipeTypeEquals(GTRecipeType left, GTRecipeType right) {
        return left == right || (left != null && right != null && left.registryName != null
                && left.registryName.equals(right.registryName));
    }

    private static final class AppendingRecipeIterator implements Iterator<GTRecipe> {

        private final Iterator<GTRecipe> extraRecipes;
        private Iterator<GTRecipe> current;

        private AppendingRecipeIterator(Iterator<GTRecipe> baseIterator, Iterator<GTRecipe> extraRecipes) {
            this.current = baseIterator == null ? Collections.emptyIterator() : baseIterator;
            this.extraRecipes = extraRecipes == null ? Collections.emptyIterator() : extraRecipes;
        }

        @Override
        public boolean hasNext() {
            if (current.hasNext()) {
                return true;
            }
            return extraRecipes.hasNext();
        }

        @Override
        public GTRecipe next() {
            if (current.hasNext()) {
                return current.next();
            }
            if (extraRecipes.hasNext()) {
                return extraRecipes.next();
            }
            throw new NoSuchElementException();
        }
    }
}
