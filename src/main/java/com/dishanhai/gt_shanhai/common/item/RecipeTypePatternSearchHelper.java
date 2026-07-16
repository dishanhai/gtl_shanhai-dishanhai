package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
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

    // ===== 虚拟供料"这一单还剩多少预算"状态 =====
    // 样板 = 下一次单：每个槽位持有一个剩余预算（初始为配置 patternVirtualSupplyBatchParallel），
    // 每次 top-up 按本轮实际补进去的量扣减预算，预算耗尽才算这一单彻底完成，之后不再自动补，
    // 直到样板被取出（clearOrderFulfilled）才重置为满预算、开始下一单。
    // -1 表示尚未初始化（等价于满预算，首次访问时会写入具体值）。
    // 弱引用外层 key（buffer 实例）：方块卸载/破坏后随 GC 自然回收，不需要手动清理。
    private static final Map<MEPatternBufferPartMachineBase, long[]> ORDER_REMAINING_BUDGET_SLOTS =
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

    // ===== 星律样板推断缓存（active/首配兜底共用）=====
    // key: buffer 实例（弱引用）；value: slot -> (样板身份哈希, 缓存时的配方规则版本号, 推断出的 GTRecipe)。
    // 与 peekRecipeCached 的区别：星律路径会写回 NBT（gtShanhai$getPatternRecipe 内部 ensureRecipe 写 TAG_RECIPE_TYPE），
    // 且必须感知 DShanhaiRecipeModifierAPI 的配方修改规则（剥离/替换/删除）运行期变化——规则一变，旧缓存的匹配结果
    // 可能不再是"当前"匹配到的配方，仅按样板身份哈希判断不够，还要比对 getPatternCacheRevision()。
    private static final Map<MEPatternBufferPartMachineBase, it.unimi.dsi.fastutil.ints.Int2ObjectMap<MarkedRecipeCacheEntry>>
            MARKED_RECIPE_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class MarkedRecipeCacheEntry {
        final int stackIdentityHash;
        final long revision;
        final GTRecipe recipe;
        MarkedRecipeCacheEntry(int stackIdentityHash, long revision, GTRecipe recipe) {
            this.stackIdentityHash = stackIdentityHash;
            this.revision = revision;
            this.recipe = recipe;
        }
    }

    /**
     * 星律 active 槽位路径（{@link #collectMarkedPatternRecipesFromMachine}）与首配兜底路径
     * （{@link #collectFirstSparkPatternRecipes}）共用的缓存入口——昂贵的样板反推调用
     * （AE2 样板 NBT 解码 + 配方全量匹配）只在缓存未命中时才触发一次。
     */
    private static GTRecipe getMarkedRecipeCached(MEPatternBufferPartMachineBase buffer, RecipeTypePatternSlotAccess access, int slot) {
        int stackHash = System.identityHashCode(access.gtShanhai$getPatternStack(slot));
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();
        synchronized (MARKED_RECIPE_CACHE) {
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<MarkedRecipeCacheEntry> slotCache = MARKED_RECIPE_CACHE.get(buffer);
            if (slotCache != null) {
                MarkedRecipeCacheEntry entry = slotCache.get(slot);
                if (entry != null && entry.stackIdentityHash == stackHash && entry.revision == revision) {
                    return entry.recipe;
                }
            }
        }
        GTRecipe recipe = access.gtShanhai$getPatternRecipe(slot);
        synchronized (MARKED_RECIPE_CACHE) {
            MARKED_RECIPE_CACHE.computeIfAbsent(buffer, b -> new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>())
                    .put(slot, new MarkedRecipeCacheEntry(stackHash, revision, recipe));
        }
        return recipe;
    }

    /** 清理星律动态槽重建时的首配缓存和虚拟供料预算。 */
    public static void clearPatternState(MEPatternBufferPartMachineBase buffer) {
        if (buffer == null) return;
        synchronized (ORDER_REMAINING_BUDGET_SLOTS) {
            ORDER_REMAINING_BUDGET_SLOTS.remove(buffer);
        }
        synchronized (PATTERN_PEEK_CACHE) {
            PATTERN_PEEK_CACHE.remove(buffer);
        }
        synchronized (MARKED_RECIPE_CACHE) {
            MARKED_RECIPE_CACHE.remove(buffer);
        }
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
            topUpVirtualSupply(buffer, slot, stack, recipe);
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
                || !(patternMachine instanceof MEPatternBufferPartMachineBase buffer)) {
            return;
        }
        if (activeSlots != null && activeSlots.length > 0) {
            com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics.hit("patternSearch.activeSlots",
                    "buffer=" + buffer.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(buffer))
                            + " activeSlots=" + java.util.Arrays.toString(activeSlots));
        }
        if (activeSlots != null) {
            for (int slot : activeSlots) {
                GTRecipe recipe = getMarkedRecipeCached(buffer, access, slot);
                if (recipe == null || !access.gtShanhai$slotAllowsRecipe(slot, recipe)) {
                    com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics.hit("patternSearch.activeSlot.skipped",
                            "slot=" + slot + " recipeNull=" + (recipe == null)
                                    + " allowsRecipe=" + (recipe != null && access.gtShanhai$slotAllowsRecipe(slot, recipe)));
                    continue;
                }
                activatePatternRecipe(capabilityMachine, ownerMachine, recipe, slot);
                result.add(recipe);
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
        if (!(patternMachine instanceof MEPatternBufferPartMachineBase buffer)) return;
        int slotCount = access.gtShanhai$getPatternSlotCount();
        for (int slot = 0; slot < slotCount; slot++) {
            if (containsSlot(activeSlots, slot)) continue; // 真实 AE2 下单已激活，走既有分支，不碰虚拟下单状态
            ItemStack patternStack = access.gtShanhai$getPatternStack(slot);
            if (patternStack.isEmpty()) {
                clearOrderFulfilled(buffer, slot); // 样板被取出：这一单彻底结束，下次放样板算新单
                continue;
            }
            GTRecipe recipe = getMarkedRecipeCached(buffer, access, slot);
            if (recipe != null && access.gtShanhai$slotAllowsRecipe(slot, recipe)) {
                topUpVirtualSupply(buffer, slot, patternStack, recipe);
                activatePatternRecipe(capabilityMachine, ownerMachine, recipe, slot);
                result.add(recipe);
            }
        }
    }

    /**
     * 虚拟供料批量预填：消耗性输入的发配量本身不设上限，按"这一单"的剩余预算（见
     * {@link #ORDER_REMAINING_BUDGET_SLOTS}）和 AE 网络真实库存尽量给足，不受宿主机器并行上限压缩——
     * 这样机器并行低时也能一次拿到足够多轮消耗的量，不会执行一次就停。
     * <p>
     * <b>"不消耗"催化剂由扣料层保护，不在这里限制发配数量</b>：GTLCore/AE2 的样板扣料
     * （{@code InternalSlot.handleItemInternal}/{@code handleFluidInternal}）不识别 GTCEu 的
     * NotConsumable（{@code Content.chance==0}）标记，会把催化剂当消耗品扣。此问题在扣料层（配方层）
     * 解决——见 {@link PatternNotConsumableFilter} 与两个
     * {@code MEPatternBuffer*RecipeTypeFilterMixin}：真扣料阶段把 chance==0 的输入从待扣列表剔除，
     * 催化剂永不被吞。因此这里对催化剂的处理只需保证"在场"：与消耗性输入同批量（achievable）供给，
     * 不额外限制发配数量（避免高并行时 simulate 在场校验因催化剂份数不足而匹配失败），只是 InternalSlot
     * 里已有该催化剂时就不再重复补（扣料层保护它常驻，重复补只会无谓叠加、占用网络库存）；催化剂不参与
     * 消耗性批量的瓶颈计算（否则虚拟催化剂网络库存有限时会把整批 achievable 归零），也不占用"这一单"预算。
     * <p>
     * <b>补料时机</b>：只在"消耗性输入已全部耗尽"时补一轮（见 {@link #hasConsumableStock}）。不能像
     * 早先那样"整槽 itemInventory/fluidInventory 皆空才补"——因为催化剂被扣料层保护而常驻，槽位永远
     * 非空，那样消耗性耗尽后也永远补不上，反而把机器卡死。先对每个消耗性输入 SIMULATE 探测网络真实
     * 可提取量，取瓶颈换算批量，再统一 MODULATE 提取写入 InternalSlot。
     * <p>
     * <b>预算制"这一单"</b>：消耗性批量按剩余预算扣减，预算耗尽才算这一单彻底完成、不再自动补，
     * 避免像 ERR-20260711-006 那样变成停不下来的自动重下单循环。网络暂时没货或本轮未提取到消耗性
     * 货物时不扣减预算，允许下一轮重试。样板被取出（见调用点 {@code clearOrderFulfilled}）时预算
     * 重置，下次放样板算新的一单。
     */
    private static void topUpVirtualSupply(MEPatternBufferPartMachineBase buffer, int slot, ItemStack patternStack,
            GTRecipe recipe) {
        if (patternStack == null || patternStack.isEmpty()) return;
        long remainingBudget = getRemainingBudget(buffer, slot);
        if (remainingBudget <= 0) return; // 这一单预算已耗尽，不再自动补
        Level level = buffer.getLevel();
        if (level == null) return;

        // InternalSlot 是 GTLCore 内部类型（编译期不可见，只能整段走反射），下面全部以 Object 传递。
        Object internalSlot = invokeWithInt(buffer, "getInternalSlot", slot);
        if (internalSlot == null) return;
        if (hasConsumableStock(internalSlot, recipe)) {
            return; // 消耗性输入还没用完，本轮不补（不消耗的催化剂常驻不算库存）
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
        boolean[] notConsumable = new boolean[inputs.length];
        long achievable = remainingBudget;
        for (int i = 0; i < inputs.length; i++) {
            GenericStack in = inputs[i];
            if (in == null || in.amount() <= 0) continue;
            notConsumable[i] = recipe != null && isNotConsumableInput(recipe, in.what());
            if (notConsumable[i]) continue; // 不消耗的催化剂不参与消耗性批量的瓶颈计算
            long available = storage.extract(in.what(), Long.MAX_VALUE, Actionable.SIMULATE, actionSource);
            achievable = Math.min(achievable, available / in.amount());
        }
        if (achievable < 0) achievable = 0;

        if (achievable <= 0) return;
        boolean consumableExtracted = false;
        for (int i = 0; i < inputs.length; i++) {
            GenericStack in = inputs[i];
            if (in == null || in.amount() <= 0) continue;
            // 催化剂已在场则不重复补：扣料层保护它常驻，重复补只会无谓叠加、占用网络库存。
            if (notConsumable[i] && getInternalAmount(internalSlot, in.what()) > 0) continue;
            long want = saturatedMultiply(in.amount(), achievable);
            long extracted = storage.extract(in.what(), want, Actionable.MODULATE, actionSource);
            if (extracted > 0) {
                invokeAdd(internalSlot, in.what(), extracted);
                if (!notConsumable[i]) consumableExtracted = true;
            }
        }
        if (consumableExtracted) {
            decrementBudget(buffer, slot, achievable); // 这一单预算扣减，耗尽前允许下一轮继续补
        }
    }

    /**
     * 该 InternalSlot 里是否还有"消耗性"输入库存（排除被扣料层保护而常驻的 NotConsumable 催化剂）。
     * 直接读 InternalSlot 的 {@code getItemInventory()}/{@code getFluidInventory()}（AEKey→amount，
     * key 有可靠 equals/hashCode），对每个量 &gt;0 的 key 用 {@link #isNotConsumableInput} 判断，
     * 只要有一个非催化剂的输入还有量就返回 true。
     */
    private static boolean hasConsumableStock(Object internalSlot, GTRecipe recipe) {
        if (inventoryHasConsumable(invokeNoArg(internalSlot, "getItemInventory"), recipe)) return true;
        return inventoryHasConsumable(invokeNoArg(internalSlot, "getFluidInventory"), recipe);
    }

    private static boolean inventoryHasConsumable(Object inventoryObj, GTRecipe recipe) {
        if (!(inventoryObj instanceof it.unimi.dsi.fastutil.objects.Object2LongMap<?> map)) return false;
        for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<?> entry : map.object2LongEntrySet()) {
            if (entry.getKey() instanceof AEKey key && entry.getLongValue() > 0
                    && !(recipe != null && isNotConsumableInput(recipe, key))) {
                return true;
            }
        }
        return false;
    }

    /** 读 InternalSlot 里某个 AEKey 当前的库存量（catalyst 常驻判断用），取不到返回 0。 */
    @SuppressWarnings("unchecked")
    private static long getInternalAmount(Object internalSlot, AEKey key) {
        String method = key instanceof appeng.api.stacks.AEItemKey ? "getItemInventory" : "getFluidInventory";
        Object inventoryObj = invokeNoArg(internalSlot, method);
        if (inventoryObj instanceof it.unimi.dsi.fastutil.objects.Object2LongMap<?> map) {
            return ((it.unimi.dsi.fastutil.objects.Object2LongMap<AEKey>) map).getLong(key);
        }
        return 0L;
    }

    /**
     * 判断某个 AE 样板输入在已匹配的 {@code recipe} 里是否对应 {@code Content.chance==0}（GTCEu
     * "不消耗"标记）的输入——这类输入只要求"在场"，不该被当作消耗性物料参与批量估算。
     */
    private static boolean isNotConsumableInput(GTRecipe recipe, AEKey key) {
        if (key instanceof appeng.api.stacks.AEItemKey itemKey) {
            ItemStack stack = itemKey.toStack();
            for (Content content : recipe.getInputContents(
                    com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability.CAP)) {
                if (content.chance != 0) continue;
                if (content.content instanceof net.minecraft.world.item.crafting.Ingredient ingredient
                        && ingredient.test(stack)) {
                    return true;
                }
            }
        } else if (key instanceof appeng.api.stacks.AEFluidKey fluidKey) {
            net.minecraft.world.level.material.Fluid fluid = fluidKey.getFluid();
            for (Content content : recipe.getInputContents(
                    com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability.CAP)) {
                if (content.chance != 0) continue;
                if (content.content instanceof com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient fi
                        && fi.values != null) {
                    for (com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient.Value value : fi.values) {
                        if (value.getFluids().contains(fluid)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static long getRemainingBudget(MEPatternBufferPartMachineBase buffer, int slot) {
        long[] budgets = ORDER_REMAINING_BUDGET_SLOTS.get(buffer);
        if (budgets == null || slot < 0 || slot >= budgets.length || budgets[slot] == -1L) {
            return DShanhaiConfig.COMMON.patternVirtualSupplyBatchParallel.get(); // 尚未初始化，等价满预算
        }
        return budgets[slot];
    }

    private static void decrementBudget(MEPatternBufferPartMachineBase buffer, int slot, long amount) {
        if (slot < 0 || amount <= 0) return;
        synchronized (ORDER_REMAINING_BUDGET_SLOTS) {
            long[] budgets = ORDER_REMAINING_BUDGET_SLOTS.get(buffer);
            if (budgets == null || slot >= budgets.length) {
                long[] grown = new long[Math.max(slot + 1, budgets == null ? 0 : budgets.length)];
                java.util.Arrays.fill(grown, -1L);
                if (budgets != null) {
                    System.arraycopy(budgets, 0, grown, 0, budgets.length);
                }
                budgets = grown;
                ORDER_REMAINING_BUDGET_SLOTS.put(buffer, budgets);
            }
            long current = budgets[slot] == -1L
                    ? DShanhaiConfig.COMMON.patternVirtualSupplyBatchParallel.get()
                    : budgets[slot];
            budgets[slot] = Math.max(0L, current - amount);
        }
    }

    private static void clearOrderFulfilled(MEPatternBufferPartMachineBase buffer, int slot) {
        long[] budgets = ORDER_REMAINING_BUDGET_SLOTS.get(buffer);
        if (budgets != null && slot >= 0 && slot < budgets.length) {
            budgets[slot] = -1L; // 重置为未初始化，下次 top-up 视为满预算的新一单
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
        if (guardFail) {
            com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics.hit("patternSearch.activate.guardFail",
                    "slot=" + slot + " capabilityMachineNull=" + (capabilityMachine == null)
                            + " recipeNull=" + (recipe == null)
                            + " ownerMachine=" + (ownerMachine == null ? "null" : ownerMachine.getClass().getName())
                            + " recipeId=" + (recipe == null ? "?" : recipe.getId()));
            return;
        }
        MEPatternRecipeHandlePart handlePart = MEPatternRecipeHandlePart.of((IMEPatternPartMachine) ownerMachine);
        int handledSlot = handlePart.handleRecipe(recipe, copyRecipeContents(recipe.inputs), true, true);
        if (handledSlot < 0) {
            com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics.hit("patternSearch.activate.handleRecipeRejected",
                    "slot=" + slot + " recipeId=" + recipe.getId() + " ownerMachine=" + ownerMachine.getClass().getName());
            return;
        }
        capabilityMachine.tryAddAndActiveMERhp(handlePart, recipe, handledSlot);
        com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics.hit("patternSearch.activate.success",
                "slot=" + slot + " handledSlot=" + handledSlot + " recipeId=" + recipe.getId());
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
