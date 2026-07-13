package com.dishanhai.gt_shanhai.common.machine.primordial;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.minecraft.world.level.block.entity.BlockEntity;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.machine.CleanSelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;

import com.gtladd.gtladditions.api.machine.IThreadModifierMachine;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import com.gtladd.gtladditions.utils.antichrist.AntichristPosHelper;

import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.world.item.Items;

import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 原始终焉引擎模块基类
 * 所有模块继承此类，自动扫描并连接到主机。
 * 支持独立运行模式（配置项 workWithoutHost）。
 */
public abstract class PrimordialOmegaEngineModuleBase extends CleanSelectableRecipeTypeSetMachine
        implements IModularMachineModule<PrimordialOmegaEngineMachine, PrimordialOmegaEngineModuleBase>,
                   IThreadModifierMachine {

    private BlockPos hostPosition;
    private PrimordialOmegaEngineMachine host;
    private boolean hostConnected;
    private TickableSubscription hostScanSubs;
    private final java.util.List<MEPatternBufferPartMachineBase> hostPatternBuffers = new java.util.ArrayList<>();
    private int lastHostPatternBufferCount = -1;
    private String lastModuleConditionError;
    private GTRecipeType[] cachedRecipeTypesRef;
    private Set<String> cachedRecipeTypeNames = Collections.emptySet();
    private long cachedThreadBoostTick = Long.MIN_VALUE;
    private long cachedThreadBoost;
    private long cachedExtraMountTick = Long.MIN_VALUE;
    private int cachedExtraMountMask;
    private long cachedCanWorkTick = Long.MIN_VALUE;
    private boolean cachedCanWork;
    private long lastHostPatternSyncTick = Long.MIN_VALUE;
    private Item cachedModuleItem = Items.AIR;
    private int cachedModuleCount = -1;
    private String cachedModuleItemId;
    private int cachedModuleLevel;
    private String cachedModuleDisplayName;
    private static final String KEY_MODULE_SLOT = "sh_module";
    private static final String KEY_THREAD_SLOT = "sh_thread";
    private static final String KEY_EXTRA_MOUNT_SLOT = "sh_extra_mount";
    private static final String KEY_CHILD_STORAGES = "sh_child_storages";

    // ========== 全局线程倍率物品注册表 ==========
    // 存储字符串 ID 避免 startup 阶段 ForgeRegistries 不可用
    private static final Map<String, Long> GLOBAL_THREAD_BOOST_IDS = new java.util.LinkedHashMap<>();
    private static final Map<Item, Long> GLOBAL_THREAD_BOOST_CACHE = new java.util.HashMap<>();
    private static final String DARK_ENERGY_MULTIPLIER_ID = "dishanhai:dark_energy_multiplier";
    private static final String ANNIHILATION_CORE_ID = "dishanhai:annihilation_core";
    private static final String HYPERSTABLE_BLACK_HOLE_SEED_ID = "dishanhai:bhd_hyper_seed";
    private static final int ANNIHILATION_SAFE_MODULE_LEVEL = 15;
    private static final int EXTRA_MOUNT_DARK_ENERGY = 1;
    private static final int EXTRA_MOUNT_ANNIHILATION = 2;
    private static final int EXTRA_MOUNT_BLACK_HOLE_SEED = 4;

    /** KubeJS 可调用：注册线程倍率物品 */
    public static void registerThreadBoostItem(String itemId, long boost) {
        GLOBAL_THREAD_BOOST_IDS.put(itemId, boost);
    }

    /** 提供给外部类查询线程倍率 */
    public static long getThreadBoostValue(String itemId) {
        Long v = GLOBAL_THREAD_BOOST_IDS.get(itemId);
        return v == null ? 0 : v;
    }

    /** 惰性解析 item → 倍率（运行时可查到） */
    private static long getThreadBoostForItem(Item item) {
        if (item == null || item == net.minecraft.world.item.Items.AIR) return 0L;
        Long cached = GLOBAL_THREAD_BOOST_CACHE.get(item);
        if (cached != null) return cached;
        String regId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item).toString();
        Long boost = GLOBAL_THREAD_BOOST_IDS.get(regId);
        if (boost != null) {
            GLOBAL_THREAD_BOOST_CACHE.put(item, boost);
            return boost;
        }
        return 0L;
    }

    /** 获取线程倍率（已乘以堆叠数量） */
    public long getThreadBoost() {
        long tick = getOffsetTimer();
        if (cachedThreadBoostTick == tick) {
            return cachedThreadBoost;
        }
        cachedThreadBoostTick = tick;
        var stack = threadBoostSlot.storage.getStackInSlot(0);
        if (!stack.isEmpty()) {
            long base = getThreadBoostForItem(stack.getItem());
            // 饱和乘法：寰宇并行超限器的注册倍率就是 Long.MAX_VALUE，堆叠数量>1 时
            // base*count 会 long 溢出成负数，导致下游并行直接归零，这里钳到 Long.MAX_VALUE。
            cachedThreadBoost = base > 0 && base <= Long.MAX_VALUE / stack.getCount()
                    ? base * stack.getCount()
                    : (base > 0 ? Long.MAX_VALUE : 0L);
            return cachedThreadBoost;
        }
        cachedThreadBoost = 0L;
        return cachedThreadBoost;
    }

    // ========== 通用线程倍率槽 ==========
    protected final NotifiableItemStackHandler threadBoostSlot;
    protected final NotifiableItemStackHandler moduleSlot;
    protected final NotifiableItemStackHandler extraMountSlots;

    public PrimordialOmegaEngineModuleBase(IMachineBlockEntity holder, Object... args) {
        super(holder, null, args);
        threadBoostSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        threadBoostSlot.setFilter(stack -> {
            if (stack == null || stack.isEmpty()) return true;
            return getThreadBoostForItem(stack.getItem()) > 0;
        });
        moduleSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        moduleSlot.setFilter(stack -> {
            if (stack == null || stack.isEmpty()) return true;
            return isValidModule(stack);
        });
        extraMountSlots = new NotifiableItemStackHandler(this, 3, IO.NONE, IO.BOTH);
    }

    private static boolean isValidModule(ItemStack stack) {
        String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        return id.startsWith("dishanhai:wz") || "dishanhai:create_mk".equals(id)
                || "dishanhai:reality_anchor_module".equals(id);
    }

    // ====== 物质模块等级表（对应 KubeJS 注册顺序 17 个） ======
    private static final java.util.Map<String, Integer> MODULE_LEVELS = new java.util.LinkedHashMap<>();
    static {
        String[] ids = {
            "dishanhai:wzrm",                // 1  入门
            "dishanhai:wzjc",                // 2  基础
            "dishanhai:wzcz1",               // 3  物质推演
            "dishanhai:wzxc",                // 4  虚像
            "dishanhai:wzsb",                // 5  嬗变
            "dishanhai:wzax",                // 6  暗星
            "dishanhai:wzcz2",               // 7  物质重组
            "dishanhai:wzqs",                // 8  虚数跃迁
            "dishanhai:wzgl",                // 9  归零
            "dishanhai:wzhy",                // 10 巅峰
            "dishanhai:wzsw",                // 11 升维
            "dishanhai:wzcx",                // 12 超限
            "dishanhai:wzdf",                // 13 混沌
            "dishanhai:wzyh",                // 14 永恒
            "dishanhai:wzcz3",               // 15 物质创造
            "dishanhai:reality_anchor_module", // 16 现实锚点
            "dishanhai:create_mk",            // 17 创始现实修改
        };
        for (int i = 0; i < ids.length; i++) MODULE_LEVELS.put(ids[i], i + 1);
    }

    /** 获取模块槽中物品的完整 ID，空则返回 null */
    public String getModuleItemId() {
        refreshModuleSlotCache();
        return cachedModuleItemId;
    }

    /** 获取当前搭载的物质模块显示名（直接从物品原名读取），无模块时返回 null */
    public String getModuleDisplayName() {
        refreshModuleSlotCache();
        return cachedModuleDisplayName;
    }

    /** 按模块 ID 查等级（1~17），非模块 ID 返回 0 */
    public static int getModuleLevelById(String id) {
        return id != null ? MODULE_LEVELS.getOrDefault(id, 0) : 0;
    }

    /** 获取物质模块搭配的详细诊断信息（模块不足时输出具体差距） */
    public String getModuleConditionDiagnosis(String moduleId, int requiredLevel) {
        int requiredLv = getModuleLevelById(moduleId);
        String reqName = getDisplayNameForModuleId(moduleId);
        String slotId = getModuleItemId();
        int slotLv = getModuleLevel();

        if (slotId == null) {
            return Component.literal(
                String.format("§c✗ 需搭载 %s§c(Lv.%d) 或以上，当前未搭载任何模块", reqName, requiredLv))
                .getString();
        }
        if (slotLv < requiredLv) {
            String curName = getModuleDisplayName();
            return Component.literal(
                String.format("§c✗ 需搭载 %s§c(Lv.%d) 或以上，当前: %s§c(Lv.%d)",
                    reqName, requiredLv, curName != null ? curName : "未知", slotLv))
                .getString();
        }
        // 等级够了但数量不够
        int multiplier = slotLv - requiredLv + 1;
        int count = getModuleCount() * multiplier;
        if (count < requiredLevel) {
            return Component.literal(
                String.format("§c✗ 模块数量不足: %s§c 需 ×%d(等效)，当前 ×%d",
                    reqName, requiredLevel, count))
                .getString();
        }
        return null; // 满足条件
    }

    /** 按模块 ID 获取物品显示名 */
    private static String getDisplayNameForModuleId(String moduleId) {
        try {
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    new net.minecraft.resources.ResourceLocation(moduleId));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return item.getDefaultInstance().getHoverName().getString();
            }
        } catch (Exception ignored) {}
        return moduleId;
    }

    /** 获取当前搭载的物质模块等级（1~16），无模块时返回 0 */
    public int getModuleLevel() {
        refreshModuleSlotCache();
        return cachedModuleLevel;
    }

    /** 获取当前物质模块槽中的物品堆叠数量 */
    public int getModuleCount() {
        refreshModuleSlotCache();
        return cachedModuleCount;
    }

    private void refreshModuleSlotCache() {
        var stack = moduleSlot.storage.getStackInSlot(0);
        Item item = stack.isEmpty() ? Items.AIR : stack.getItem();
        int count = stack.isEmpty() ? 0 : stack.getCount();
        if (item == cachedModuleItem && count == cachedModuleCount) {
            return;
        }
        cachedModuleItem = item;
        cachedModuleCount = count;
        if (item == Items.AIR || !isValidModule(stack)) {
            cachedModuleItemId = null;
            cachedModuleLevel = 0;
            cachedModuleDisplayName = null;
            return;
        }
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        cachedModuleItemId = key == null ? null : key.toString();
        cachedModuleLevel = getModuleLevelById(cachedModuleItemId);
        cachedModuleDisplayName = stack.getHoverName().getString();
    }

    /** 是否允许脱离主机独立运行 */
    public boolean canWorkWithoutHost() {
        return DShanhaiConfig.COMMON.modulesWorkWithoutHost.get();
    }

    // ========== 模块接口 ==========

    @Override
    public BlockPos getHostPosition() {
        return hostPosition;
    }

    @Override
    public void setHostPosition(BlockPos pos) {
        this.hostPosition = pos;
    }

    @Override
    public PrimordialOmegaEngineMachine getHost() {
        return host;
    }

    @Override
    public void setHost(PrimordialOmegaEngineMachine host) {
        this.host = host;
    }

    @Override
    public Class<PrimordialOmegaEngineMachine> getHostType() {
        return PrimordialOmegaEngineMachine.class;
    }

    @Override
    public BlockPos[] getHostScanPositions() {
        return AntichristPosHelper.INSTANCE.calculatePossibleHostPositions(getPos(), getFrontFacing());
    }

    // ========== 生命周期 ==========

    private TickableSubscription hostPatternSyncSubs;

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        if (!tryConnectToHost()) {
            hostScanSubs = subscribeServerTick(hostScanSubs, () -> {
                if (getOffsetTimer() % 20 == 0 && tryConnectToHost()) {
                    hostConnected = true;
                    if (hostScanSubs != null) {
                        hostScanSubs.unsubscribe();
                        hostScanSubs = null;
                    }
                }
            });
        } else {
            hostConnected = true;
        }
        hostPatternSyncSubs = subscribeServerTick(hostPatternSyncSubs, () -> {
            if (getOffsetTimer() % 100 == 0 && host != null) {
                syncHostPatternHandlers(host, false);
            }
        });
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (host != null) {
            host.removeModule(this);
            host = null;
            hostConnected = false;
        }
        cachedCanWorkTick = Long.MIN_VALUE;
        lastHostPatternSyncTick = Long.MIN_VALUE;
        hostPatternBuffers.clear();
        lastHostPatternBufferCount = -1;
        if (hostScanSubs != null) {
            hostScanSubs.unsubscribe();
            hostScanSubs = null;
        }
        if (hostPatternSyncSubs != null) {
            hostPatternSyncSubs.unsubscribe();
            hostPatternSyncSubs = null;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
    }

    private boolean tryConnectToHost() {
        if (getLevel() == null) return false;
        BlockPos[] positions = AntichristPosHelper.INSTANCE.calculatePossibleHostPositions(getPos(), getFrontFacing());
        for (BlockPos pos : positions) {
            BlockEntity be = getLevel().getBlockEntity(pos);
            if (be instanceof IMachineBlockEntity machineBE) {
                MetaMachine machine = machineBE.getMetaMachine();
                if (machine instanceof PrimordialOmegaEngineMachine engine && engine.isFormed()) {
                    engine.addModule(this);
                    setHost(engine);
                    setHostPosition(pos);
                    cachedCanWorkTick = Long.MIN_VALUE;
                    onConnected(engine);
                    syncHostPatternHandlers(engine, true);
                    return true;
                }
            }
        }
        return false;
    }

    /** 同步主机样板总成列表；不要把样板 handler 注入模块能力，避免触发样板隔离。 */
    private void syncHostPatternHandlers(PrimordialOmegaEngineMachine host, boolean force) {
        if (host == null || !host.isFormed()) return;
        long tick = getOffsetTimer();
        if (!force && lastHostPatternSyncTick == tick) return;
        lastHostPatternSyncTick = tick;

        java.util.List<IMEPatternPartMachine> buffers = new java.util.ArrayList<>();
        for (IMultiPart part : host.getParts()) {
            if (part instanceof IMEPatternPartMachine patternPart) {
                buffers.add(patternPart);
            }
        }

        if (!force && lastHostPatternBufferCount == buffers.size()) {
            return;
        }

        hostPatternBuffers.clear();
        lastHostPatternBufferCount = buffers.size();

        for (IMEPatternPartMachine buffer : buffers) {
            if (buffer instanceof MEPatternBufferPartMachineBase patternBuffer) {
                hostPatternBuffers.add(patternBuffer);
            }
        }

        getRecipeLogic().markLastRecipeDirty();
        getRecipeLogic().updateTickSubscription();
    }

    public List<MEPatternBufferPartMachineBase> getHostPatternBuffers() {
        return hostPatternBuffers;
    }

    public Set<String> getRecipeTypeNameSet() {
        GTRecipeType[] recipeTypes = getDefinition().getRecipeTypes();
        if (recipeTypes == cachedRecipeTypesRef) {
            return cachedRecipeTypeNames;
        }
        cachedRecipeTypesRef = recipeTypes;
        if (recipeTypes == null || recipeTypes.length == 0) {
            cachedRecipeTypeNames = Collections.emptySet();
            return cachedRecipeTypeNames;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (GTRecipeType type : recipeTypes) {
            if (type != null && type.registryName != null) {
                names.add(type.registryName.toString());
            }
        }
        cachedRecipeTypeNames = names.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(names);
        return cachedRecipeTypeNames;
    }

    public boolean isHostConnected() {
        return hasLiveHost();
    }

    public void setModuleConditionError(String msg) { this.lastModuleConditionError = msg; }
    public String getModuleConditionError() { return lastModuleConditionError; }

    /** 模块是否可以运行（连接了主机，或启用了独立模式） */
    protected boolean canModuleWork() {
        long tick = getOffsetTimer();
        if (cachedCanWorkTick == tick) {
            return cachedCanWork;
        }
        cachedCanWorkTick = tick;
        if (hasLiveHost()) {
            if (tick % 20 == 0) {
                syncHostPatternHandlers(host, false);
            }
            cachedCanWork = true;
            return cachedCanWork;
        }
        if (isFormed() && tick % 20 == 0 && tryConnectToHost()) {
            hostConnected = true;
            cachedCanWork = true;
            return cachedCanWork;
        }
        cachedCanWork = canWorkWithoutHost();
        return cachedCanWork;
    }

    private boolean hasLiveHost() {
        if (host != null && host.isFormed()) {
            if (!host.getModuleSet().contains(this)) {
                host.addModule(this);
            }
            hostConnected = true;
            return true;
        }
        if (hostConnected) {
            hostConnected = false;
            cachedCanWorkTick = Long.MIN_VALUE;
        }
        return false;
    }

    // ========== 工作控制 ==========

    /**
     * 保持 tick 订阅常驻。
     * 本系列模块的输入料来自 AE 网络（库存总线），AE 发料不触发机器自身 inventory 的
     * onContentsChanged，导致 RecipeLogic 空闲取消订阅后无法被唤醒、永久待机。
     * 返回 true 可让 RecipeLogic 每 5 tick 主动轮询配方，避免该问题。
     */
    @Override
    public boolean keepSubscribing() {
        return true;
    }

    @Override
    public boolean onWorking() {
        if (!canModuleWork()) {
            return false;
        }
        return super.onWorking();
    }

    @Override
    public int getAdditionalThread() {
        long boost = getThreadBoost();
        return boost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) boost;
    }

    @Override
    public long getRecipeLogicMaxParallel() {
        return Math.max(1L, getCurrentParallel());
    }

    public long getRecipeLogicThreadMultiplier() {
        long boost = getThreadBoost();
        return boost > 0L ? boost : 1L;
    }


    /** 检测线程倍率槽是否装有寰宇并行超限器 */
    public boolean hasParallelOverdriver() {
        var stack = threadBoostSlot.storage.getStackInSlot(0);
        if (stack.isEmpty()) return false;
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "dishanhai:universal_parallel_overdriver".equals(id.toString());
    }

    /** 检测额外挂载槽是否装有暗能量倍增器；多个槽位或堆叠只生效一次。 */
    public boolean hasDarkEnergyMultiplierMounted() {
        return hasExtraMountItem(DARK_ENERGY_MULTIPLIER_ID);
    }

    /** 检测额外挂载槽是否装有湮灭核心；多个槽位或堆叠只生效一次。 */
    public boolean hasAnnihilationCoreMounted() {
        return hasExtraMountItem(ANNIHILATION_CORE_ID);
    }

    /** 检测额外挂载槽是否装有超稳态黑洞种子。 */
    public boolean hasHyperstableBlackHoleSeedMounted() {
        return hasExtraMountItem(HYPERSTABLE_BLACK_HOLE_SEED_ID);
    }

    private boolean hasExtraMountItem(String itemId) {
        int expectedMask;
        if (DARK_ENERGY_MULTIPLIER_ID.equals(itemId)) {
            expectedMask = EXTRA_MOUNT_DARK_ENERGY;
        } else if (ANNIHILATION_CORE_ID.equals(itemId)) {
            expectedMask = EXTRA_MOUNT_ANNIHILATION;
        } else if (HYPERSTABLE_BLACK_HOLE_SEED_ID.equals(itemId)) {
            expectedMask = EXTRA_MOUNT_BLACK_HOLE_SEED;
        } else {
            return false;
        }
        return (getExtraMountMask() & expectedMask) != 0;
    }

    private int getExtraMountMask() {
        long tick = getOffsetTimer();
        if (cachedExtraMountTick == tick) {
            return cachedExtraMountMask;
        }
        cachedExtraMountTick = tick;
        int mask = 0;
        for (int i = 0; i < extraMountSlots.storage.getSlots(); i++) {
            var stack = extraMountSlots.storage.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) continue;
            String value = id.toString();
            if (DARK_ENERGY_MULTIPLIER_ID.equals(value)) {
                mask |= EXTRA_MOUNT_DARK_ENERGY;
            } else if (ANNIHILATION_CORE_ID.equals(value)) {
                mask |= EXTRA_MOUNT_ANNIHILATION;
            } else if (HYPERSTABLE_BLACK_HOLE_SEED_ID.equals(value)) {
                mask |= EXTRA_MOUNT_BLACK_HOLE_SEED;
            }
        }
        cachedExtraMountMask = mask;
        return cachedExtraMountMask;
    }

    /** 原初模块额外挂载提供的配方 EU 系数。 */
    public double getExtraMountEuMultiplier() {
        return hasDarkEnergyMultiplierMounted() ? 0.5D : 1.0D;
    }

    /** 湮灭核心提供 90% 配方时长压缩。 */
    public double getExtraMountDurationMultiplier() {
        return hasAnnihilationCoreMounted() ? 0.1D : 1.0D;
    }

    /** 湮灭核心产物湮灭风险；高级物质模块挂载后归零。 */
    public double getAnnihilationOutputLossChance() {
        if (!hasAnnihilationCoreMounted()) return 0.0D;
        return isAnnihilationRiskSuppressed() ? 0.0D : 0.01D;
    }

    public boolean isAnnihilationRiskSuppressed() {
        return getModuleLevel() >= ANNIHILATION_SAFE_MODULE_LEVEL;
    }


    // ========== NBT 持久化 ==========
    // 直接读写字段，不依赖 getPersistedStorages()（子类覆写时会遗漏基类槽）

    @Override
    public void saveCustomPersistedData(net.minecraft.nbt.CompoundTag tag, boolean forSyncing) {
        super.saveCustomPersistedData(tag, forSyncing);
        if (forSyncing) return;
        tag.put(KEY_MODULE_SLOT, moduleSlot.storage.serializeNBT());
        tag.put(KEY_THREAD_SLOT, threadBoostSlot.storage.serializeNBT());
        tag.put(KEY_EXTRA_MOUNT_SLOT, extraMountSlots.storage.serializeNBT());

        var storages = getPersistedStorages();
        if (storages != null && storages.length > 0) {
            var list = new net.minecraft.nbt.ListTag();
            for (var storage : storages) {
                if (storage == null || isBaseStorage(storage)) {
                    continue;
                }
                list.add(storage.storage.serializeNBT());
            }
            if (!list.isEmpty()) {
                tag.put(KEY_CHILD_STORAGES, list);
            }
        }
    }

    @Override
    public void loadCustomPersistedData(net.minecraft.nbt.CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(KEY_MODULE_SLOT)) {
            moduleSlot.storage.deserializeNBT(tag.getCompound(KEY_MODULE_SLOT));
        }
        if (tag.contains(KEY_THREAD_SLOT)) {
            threadBoostSlot.storage.deserializeNBT(tag.getCompound(KEY_THREAD_SLOT));
        }
        if (tag.contains(KEY_EXTRA_MOUNT_SLOT)) {
            extraMountSlots.storage.deserializeNBT(tag.getCompound(KEY_EXTRA_MOUNT_SLOT));
        }
        if (tag.contains(KEY_CHILD_STORAGES)) {
            var list = tag.getList(KEY_CHILD_STORAGES, net.minecraft.nbt.Tag.TAG_COMPOUND);
            var storages = getPersistedStorages();
            int index = 0;
            if (storages != null) {
                for (var storage : storages) {
                    if (storage == null || isBaseStorage(storage)) {
                        continue;
                    }
                    if (index >= list.size()) {
                        break;
                    }
                    storage.storage.deserializeNBT(list.getCompound(index++));
                }
            }
        }
    }

    /** @deprecated 子类覆写保留兼容，实际持久化已改为直接读写字段 */
    @Deprecated
    protected com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler[] getPersistedStorages() {
        return new com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler[0];
    }

    private boolean isBaseStorage(NotifiableItemStackHandler storage) {
        return storage == moduleSlot || storage == threadBoostSlot || storage == extraMountSlots;
    }

    @Override
    protected void attachCleanSideTabs(TabsWidget tabsWidget) {
        tabsWidget.attachSubTab(new ExtraMountPageProvider());
    }

    @Override
    protected void onRecipeTypeSelectionChanged(RecipeLogic logic) {
        super.onRecipeTypeSelectionChanged(logic);
        if (logic instanceof PrimordialModuleRecipeLogic moduleLogic) {
            moduleLogic.onRecipeTypeSelectionChanged();
        }
    }

    // ========== UI & 线程倍率槽显示 ==========

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group && showsModuleAndThreadSlots()) {
            var size = group.getSize();
            // 物质模块槽（右上方）
            var modSlot = new SlotWidget(
                moduleSlot.storage, 0,
                size.width - 30, size.height - 68,
                true, true
            );
            modSlot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            modSlot.setHoverTooltips(
                    Component.literal("§6§l物质模块"),
                    Component.literal("§7放入物质模块系列"),
                    Component.literal("§7用于标识模块等级并参与配方条件判定"),
                    Component.literal("§7并非模块并行槽位注意区分"));
            group.addWidget(modSlot);
            // 线程倍率槽（右下）
            var thrSlot = new SlotWidget(
                threadBoostSlot.storage, 0,
                size.width - 30, size.height - 48,
                true, true
            );
            thrSlot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            thrSlot.setHoverTooltips(
                    Component.literal("§d§l线程倍率槽"),
                    Component.literal("§7放入已注册的线程倍率物品"),
                    Component.literal("§7提升本模块的跨配方线程数"),
                    Component.literal("§7§6寰宇并行超限器 §7可启用超限模式"));
            group.addWidget(thrSlot);
        }
        return widget;
    }

    /** 物质模块槽 / 线程倍率槽是否在 UI 中显示。部分模块（如零点能发生器，并行由编程电路
     *  独立决定）对这两个槽位完全无感知，子类可覆写为 false 隐藏，避免误导玩家放物品。 */
    protected boolean showsModuleAndThreadSlots() {
        return true;
    }

    private class ExtraMountPageProvider implements IFancyUIProvider {

        @Override
        public Component getTitle() {
            return Component.literal("§b额外挂载");
        }

        @Override
        public IGuiTexture getTabIcon() {
            return new ItemStackTexture(Items.HOPPER);
        }

        @Override
        public Widget createMainPage(FancyMachineUIWidget widget) {
            var group = new WidgetGroup(0, 0, 126, 78);
            group.setBackground(GuiTextures.BACKGROUND_INVERSE);
            group.addWidget(new LabelWidget(8, 8, () -> "额外挂载槽"));
            for (int i = 0; i < 3; i++) {
                var slot = new SlotWidget(
                        extraMountSlots.storage,
                        i,
                        8 + i * 34,
                        30,
                        true,
                        true);
                slot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
                slot.setHoverTooltips(
                        Component.literal("§b§l额外挂载槽 " + (i + 1)),
                        Component.literal("§7用于原初模块额外挂载物品"),
                        Component.literal("§7暗能量倍增器: §b配方 EU 消耗 -50%"),
                        Component.literal("§7湮灭核心: §c配方耗时 -90%, 产物湮灭风险 1%"),
                        Component.literal("§7超稳态黑洞种子: §d输出端可写时吞噬溢出产物"));
                group.addWidget(slot);
            }
            group.addWidget(new LabelWidget(8, 58, () -> "暗能量 / 湮灭 / 黑洞种子"));
            return group;
        }
    }

    protected void configureParallelModuleSlot(SlotWidget slot) {
        slot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
        slot.setHoverTooltips(
                Component.literal("§6§l模块并行槽"),
                Component.literal("§7放入山海物质模块系列"),
                Component.literal("§7决定此原初模块的并行上限"),
                Component.literal("§7等级越高并行越高，现实锚点/创始现实修改提供超高并行"));
    }

    // ========== 显示 ==========

    /** 添加主机连接/独立模式状态显示，子类可调用 */
    protected void addHostStatusDisplay(List<Component> textList) {
        if (!hostConnected) {
            if (canWorkWithoutHost()) {
                textList.add(DShanhaiTextUtil.createAuroraText(
                        Component.translatable("gt_shanhai.misc.module_independent_mode").getString()));
            } else {
                textList.add(Component.translatable("gt_shanhai.machine.primordial_omega_engine.no_host")
                        .withStyle(ChatFormatting.RED));
            }
        }
        // 物质模块搭载信息（所有子类的 addDisplayText 都调用了此方法，确保可见）
        if (showsModuleAndThreadSlots()) {
            addModuleSlotDisplay(textList);
        }
    }

    /** 获取当前模块并行值（子类覆写返回模块等级对应的并行数，默认无限） */
    public long getCurrentParallel() {
        return Long.MAX_VALUE;
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        long parallel = getCurrentParallel();
        boolean isInfinite = parallel >= Long.MAX_VALUE / 2;
        var parallelText = isInfinite
            ? DShanhaiTextUtil.createUltimateRainbow("无限")
            : Component.literal(String.format("%,d", parallel)).withStyle(ChatFormatting.GOLD);

        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(parallelText)
                .append(Component.literal("个配方")));
        long boost = getThreadBoost();
        var threadText = boost > 0
            ? DShanhaiTextUtil.createNeonText(String.format("%,d", boost))
            : Component.literal("1").withStyle(ChatFormatting.GRAY);
        textList.add(Component.literal("")
                .append(Component.literal("跨配方线程: "))
                .append(threadText));
        if (boost > 0) {
            textList.add(Component.literal("")
                    .append(DShanhaiTextUtil.createElectricText("线程倍率: "))
                    .append(DShanhaiTextUtil.createAuroraText("×" + boost)));
        }
        if (hasParallelOverdriver()) {
            textList.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("◆ 超限模式 · 已激活"))
                    .append(Component.literal(" §7每个配方独立 Long.MAX_VALUE 并行")));
        }
        if (hasDarkEnergyMultiplierMounted()) {
            textList.add(Component.literal("§b◆ 暗能量倍增器 · 配方 EU 消耗 -50%"));
        }
        if (hasAnnihilationCoreMounted()) {
            textList.add(Component.literal(isAnnihilationRiskSuppressed()
                    ? "§c◆ 湮灭核心 · 配方耗时 -90% · 高级模块稳定"
                    : "§c◆ 湮灭核心 · 配方耗时 -90% · 产物湮灭风险 1%"));
        }
        if (hasHyperstableBlackHoleSeedMounted()) {
            textList.add(Component.literal("§d◆ 超稳态黑洞种子 · 输出端可写时吞噬溢出产物"));
        }
        if (lastModuleConditionError != null) {
            textList.add(Component.literal(lastModuleConditionError));
        }
    }

    /** 获取超级并行补偿倍率（来自配置文件，优先级高于枢纽/引力波倍率） */
    public long getSuperParallelCompensation(long rawParallel) {
        double mul = DShanhaiConfig.COMMON.superParallelMultiplier.get();
        if (mul <= 1.0) return rawParallel;
        long multiplied = (long) ((double) rawParallel * mul);
        return multiplied < 0 ? Long.MAX_VALUE : Math.min(multiplied, Long.MAX_VALUE);
    }

    /** 供子类 addParallelDisplay 调用来显示线程倍率信息 */
    protected void addThreadBoostDisplay(List<Component> textList) {
        long boost = getThreadBoost();
        textList.add(Component.literal("")
                .append(DShanhaiTextUtil.createElectricText("跨配方线程: "))
                .append(boost > 0
                        ? DShanhaiTextUtil.createNeonText(String.format("%,d", boost))
                        : Component.literal("1").withStyle(ChatFormatting.GRAY)));
        if (boost > 0) {
            textList.add(Component.literal("")
                    .append(DShanhaiTextUtil.createElectricText("线程倍率: "))
                    .append(DShanhaiTextUtil.createMagicText("×" + boost)));
        }
        if (hasParallelOverdriver()) {
            textList.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("◆ 超限模式 · 已激活"))
                    .append(Component.literal(" §7每个配方独立 Long.MAX_VALUE 并行")));
        }
        if (hasDarkEnergyMultiplierMounted()) {
            textList.add(Component.literal("§b◆ 暗能量倍增器 · 配方 EU 消耗 -50%"));
        }
        if (hasAnnihilationCoreMounted()) {
            textList.add(Component.literal(isAnnihilationRiskSuppressed()
                    ? "§c◆ 湮灭核心 · 配方耗时 -90% · 高级模块稳定"
                    : "§c◆ 湮灭核心 · 配方耗时 -90% · 产物湮灭风险 1%"));
        }
        if (hasHyperstableBlackHoleSeedMounted()) {
            textList.add(Component.literal("§d◆ 超稳态黑洞种子 · 输出端可写时吞噬溢出产物"));
        }
    }

    /** 添加物质模块搭载信息 */
    protected void addModuleSlotDisplay(List<Component> textList) {
        String modName = getModuleDisplayName();
        int level = getModuleLevel();
        if (modName == null) {
            textList.add(Component.literal("")
                    .append(Component.literal("§7物质模块: §8未搭载")));
            return;
        }
        int count = getModuleCount();
        String countStr = count > 1 ? " §7×" + count : "";
        textList.add(Component.literal("")
                .append(Component.literal("§6§l物质模块: "))
                .append(DShanhaiTextUtil.createGoldenText(modName))
                .append(Component.literal(" §7Lv." + level + countStr)));
    }

    /** 解析本模块所属的无线能源电网 UUID：优先用主机 UUID（整个原初引擎结构共用同一电网），
     *  没连接主机时退回自己的 UUID。必须和 onWorking() 写入 WirelessEnergyManager 用的
     *  解析逻辑保持一致，否则读到的就不是自己写进去的那份电网。 */
    protected UUID resolveWirelessGridUuid() {
        var host = getHost();
        return host != null && host.getUuid() != null ? host.getUuid() : getUuid();
    }

    /**
     * 覆写继承自 GTLAdd 基类的原生 addEnergyDisplay()：原版逻辑只认 getWirelessNetworkEnergyHandler()
     * 和 long 类型的 energyContainer 电压，原初系机器两者都没有实现，只会一直显示写死的
     * getMaxVoltage()=Long.MAX_VALUE 换算出来的"最大功率(MAX+16)"，是个永远不变的假数字。
     * 原初系真正的经济体系是 com.hepdd.gtmthings 的 WirelessEnergyManager（UUID 记账的
     * BigInteger 全局电网，见 onWorking()），这里直接读同一份电网余额显示，数值真实会变化。
     */
    @Override
    protected void addEnergyDisplay(List<Component> textList) {
        UUID uuid = resolveWirelessGridUuid();
        if (uuid == null) return;
        try {
            Class<?> mgr = Class.forName("com.hepdd.gtmthings.api.misc.WirelessEnergyManager");
            Object total = mgr.getMethod("getUserEU", UUID.class).invoke(null, uuid);
            if (total instanceof java.math.BigInteger bigTotal) {
                textList.add(Component.literal("")
                        .append(DShanhaiTextUtil.createElectricText("电网能源总量: "))
                        .append(Component.literal(com.gtladd.gtladditions.utils.CommonUtils.formatBigIntegerFixed(bigTotal) + " EU")
                                .withStyle(ChatFormatting.AQUA)));
            }
        } catch (Exception ignored) {
            // gtmthings 未加载或反射失败：安静跳过，不显示这行，不影响其余信息
        }
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addHostStatusDisplay(textList);
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
            if (lastModuleConditionError != null) {
                textList.add(Component.literal(lastModuleConditionError));
            }
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_omega_module.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
