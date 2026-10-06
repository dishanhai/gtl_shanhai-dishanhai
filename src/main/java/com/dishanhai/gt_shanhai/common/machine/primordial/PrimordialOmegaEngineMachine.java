package com.dishanhai.gt_shanhai.common.machine.primordial;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.machine.CleanSelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.output.IOutputMultiplierSource;
import com.dishanhai.gt_shanhai.api.machine.primordial.IPrimordialOutputMultiplierModule;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.network.SHideRingPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import com.gtladd.gtladditions.utils.antichrist.AntichristPosHelper;

import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;

import com.google.common.primitives.Ints;

import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraftforge.registries.ForgeRegistries;

public class PrimordialOmegaEngineMachine extends CleanSelectableRecipeTypeSetMachine
        implements IModularMachineHost<PrimordialOmegaEngineMachine>, IOutputMultiplierSource {

    private static final long MAX_PARALLEL = 9223372036854775807L;

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(PrimordialOmegaEngineMachine.class, CleanSelectableRecipeTypeSetMachine.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    private final Set<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> modules = new ReferenceOpenHashSet<>();
    private final OutputMultiplierCache outputMultiplierCache = new OutputMultiplierCache();

    /**
     * modules 的不可变快照（copy-on-write）：只在模块增删时于锁内重建一次。
     * getModuleSet() 曾经每次调用都「synchronized + 全量复制 65 元素集合」，而它被
     * 每模块每 tick（hasLiveHost）和每候选配方（ModuleLevelCondition）调用——65 模块下
     * 每 tick 数千次元素拷贝 + 同一把锁的争用。快照化后读路径零锁零分配。
     */
    private volatile Set<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> moduleSnapshot =
            Collections.emptySet();

    /** 每 tick 一次的模块等级聚合（下标 = 模块等级，值 = 该等级堆叠总数，饱和加）。 */
    private volatile long moduleLevelCountsTick = Long.MIN_VALUE;
    private volatile long[] moduleLevelCounts = new long[MODULE_LEVEL_ARRAY_SIZE];
    private static final int MODULE_LEVEL_ARRAY_SIZE = 32;

    /** volatile 标志，供渲染线程安全读取，避免模块集竞态死锁 */
    public volatile boolean hasModules;

    /**
     * 球体渲染风格（{@link PrimordialSphereStyle} 的 ordinal）。
     * TESR 只跑在客户端，所以必须 @DescSynced 才能让玩家在 GUI 里的切换实时反映到渲染。
     */
    @Persisted
    @DescSynced
    private int sphereStyle = PrimordialSphereStyle.UNIVERSE.ordinal();

    public static final int STAR_PANEL_MIN_MODULE_LEVEL = 15;
    public static final int STAR_RADIUS_MIN = 13;
    public static final int STAR_RADIUS_MAX = 43;
    public static final int STAR_MODE_FOLLOW_LEVEL = 0;
    public static final int STAR_MODE_MANUAL = 1;
    public static final int STAR_OVERRIDE_UNSET = -1;
    public static final int STAR_PALETTE_SPECTRAL = 0;
    public static final int STAR_PALETTE_RAINBOW = 1;
    public static final int STAR_RAINBOW_PERIOD_OFF = 0;
    public static final int[] STAR_RAINBOW_PERIODS = {0, 20, 60, 200, 600, 1800, 6000, 12000};

    @Persisted
    @DescSynced
    private int starRenderMode = STAR_MODE_FOLLOW_LEVEL;

    @Persisted
    @DescSynced
    private int starPalette = STAR_PALETTE_SPECTRAL;

    @Persisted
    @DescSynced
    private int starRainbowPeriodTicks = STAR_RAINBOW_PERIOD_OFF;

    @Persisted
    @DescSynced
    private int starRadiusOverride = STAR_OVERRIDE_UNSET;

    @Persisted
    @DescSynced
    private int starHueOverride = STAR_OVERRIDE_UNSET;

    @Persisted
    @DescSynced
    private boolean starAlwaysWorking;

    @Persisted
    private final NotifiableItemStackHandler matterBonusSlot;

    @Persisted
    @DescSynced
    private int matterBonusLevel;

    public PrimordialOmegaEngineMachine(IMachineBlockEntity holder) {
        super(holder, GTRecipeTypes.FURNACE_RECIPES);
        matterBonusSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.NONE) {
            @Override
            public void onContentsChanged() {
                super.onContentsChanged();
                if (!isRemote()) {
                    refreshMatterBonusLevel();
                }
            }
        };
        matterBonusSlot.setFilter(PrimordialOmegaEngineMachine::isMatterModuleStack);
    }

    // ========== 配方逻辑 ==========

    @Override
    public PrimordialOmegaEngineRecipeLogic createRecipeLogic(Object... args) {
        return new PrimordialOmegaEngineRecipeLogic(this);
    }

    @Override
    public int getMaxParallel() {
        return Ints.saturatedCast(MAX_PARALLEL);
    }

    @Override
    public int getAdditionalThread() {
        return Integer.MAX_VALUE;
    }

    // keepSubscribing() 已上移至 SelectableRecipeTypeSetMachine 基类统一覆写

    // ========== 能量 ==========

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;
    }

    // ========== 结构检测（187层巨构，同步检测避免死锁） ==========

    @Override
    public void asyncCheckPattern(long period) {
        if (!getMultiblockState().hasError() && isFormed()) return;
        if ((getHolder().getOffset() + period) % 4 != 0) return;
        if (!checkPatternWithTryLock()) return;
        if (getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                getPatternLock().lock();
                try {
                    if (isFormed()) return;
                    setFlipped(getMultiblockState().isNeededFlip());
                    onStructureFormed();
                    MultiblockWorldSavedData worldData =
                            MultiblockWorldSavedData.getOrCreate(serverLevel);
                    worldData.addMapping(getMultiblockState());
                    worldData.removeAsyncLogic(this);
                } finally {
                    getPatternLock().unlock();
                }
            });
        }
    }

    // ========== 模块接口 ==========

    @Override
    public Set<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> getModuleSet() {
        return moduleSnapshot;
    }

    /** 必须在 synchronized (outputMultiplierCache) 内调用。 */
    private void rebuildModuleSnapshot() {
        moduleSnapshot = modules.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new ReferenceOpenHashSet<>(modules));
        moduleLevelCountsTick = Long.MIN_VALUE;
    }

    /**
     * 主机级等效模块数量：requiredLv 及以上等级的全部模块按等级差换算后的饱和总和。
     * 等级聚合每 tick 最多重建一次，替代「每模块 × 每候选配方」各自遍历全部模块的旧路径。
     */
    public long getEquivalentModuleCountForLevel(int requiredLv) {
        if (requiredLv <= 0) return Long.MAX_VALUE;
        long[] counts = moduleLevelCountsSnapshot();
        long total = 0L;
        for (int lv = requiredLv; lv < counts.length; lv++) {
            long stackCount = counts[lv];
            if (stackCount <= 0L) continue;
            int cappedCount = stackCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) stackCount;
            long equivalent = com.dishanhai.gt_shanhai.api.ModuleLevelCondition
                    .calculateEquivalentCount(lv, cappedCount, requiredLv);
            if (equivalent == Long.MAX_VALUE || total > Long.MAX_VALUE - equivalent) {
                return Long.MAX_VALUE;
            }
            total += equivalent;
        }
        return total;
    }

    private long[] moduleLevelCountsSnapshot() {
        long tick = getOffsetTimer();
        if (moduleLevelCountsTick == tick) {
            return moduleLevelCounts;
        }
        long[] counts = new long[MODULE_LEVEL_ARRAY_SIZE];
        for (IModularMachineModule<PrimordialOmegaEngineMachine, ?> module : moduleSnapshot) {
            if (!(module instanceof PrimordialOmegaEngineModuleBase mb)) continue;
            String slotId = mb.getModuleItemId();
            if (slotId == null) continue;
            int lv = PrimordialOmegaEngineModuleBase.getModuleLevelById(slotId);
            if (lv <= 0 || lv >= counts.length) continue;
            int stackCount = mb.getModuleCount();
            if (stackCount <= 0) continue;
            long next = counts[lv] + stackCount;
            counts[lv] = next < counts[lv] ? Long.MAX_VALUE : next;
        }
        moduleLevelCounts = counts;
        moduleLevelCountsTick = tick;
        return counts;
    }

    @Override
    public BlockPos[] getModuleScanPositions() {
        return AntichristPosHelper.INSTANCE.calculateModulePositions(getPos(), getFrontFacing());
    }

    @Override
    public boolean isValidModule(MetaMachine machine) {
        if (machine instanceof IModularMachineModule<?, ?> module) {
            return module.getHostType().isAssignableFrom(PrimordialOmegaEngineMachine.class);
        }
        return false;
    }

    // ========== 生命周期 ==========

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        syncRingVisibility(true);
        safeClearModules();
        scanAndConnectModules();
        synchronized (outputMultiplierCache) {
            hasModules = !modules.isEmpty();
        }
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        syncRingVisibility(false);
        safeClearModules();
        synchronized (outputMultiplierCache) {
            hasModules = false;
            outputMultiplierCache.invalidate();
        }
    }

    @Override
    public void onMachineRemoved() {
        syncRingVisibility(false);
        super.onMachineRemoved();
        clearInventory(matterBonusSlot.storage);
    }

    private void syncRingVisibility(boolean hide) {
        if (getLevel() instanceof ServerLevel serverLevel) {
            ShanhaiNetwork.sendHideRingToClients(serverLevel,
                    new SHideRingPacket(getPos(), getFrontFacing(), hide));
        }
    }

    /**
     * 覆写 gtlcore 的 safeClearModules，修复并发修改崩溃。
     * 原版在迭代 getModules() 时 removeFromHost 内部修改了同一集合的底层结构，
     * 导致 fastutil ReferenceOpenHashSet 迭代器 wrapped 变 null。
     */
    @Override
    public void safeClearModules() {
        List<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> snapshot;
        synchronized (outputMultiplierCache) {
            snapshot = new ArrayList<>(modules);
        }
        for (var module : snapshot) {
            module.removeFromHost(this);
        }
        synchronized (outputMultiplierCache) {
            modules.clear();
            rebuildModuleSnapshot();
            outputMultiplierCache.invalidate();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
        if (!isRemote()) {
            refreshMatterBonusLevel();
        }
    }

    // ========== 模块同步（线程安全 volatile 标志） ==========

    @Override
    public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void addModule(M module) {
        synchronized (outputMultiplierCache) {
            if (modules.add(module)) {
                rebuildModuleSnapshot();
                outputMultiplierCache.invalidate();
            }
            hasModules = true;
        }
    }

    @Override
    public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void removeModule(M module) {
        synchronized (outputMultiplierCache) {
            if (modules.remove(module)) {
                rebuildModuleSnapshot();
                outputMultiplierCache.invalidate();
            }
            hasModules = !modules.isEmpty();
        }
    }

    public int getMountedOutputMultiplier() {
        // 传不可变快照：缓存未命中时可在锁外安全遍历，get() 不再需要锁内复制一份集合。
        return outputMultiplierCache.get(getOffsetTimer(), moduleSnapshot);
    }

    public void invalidateMountedOutputMultiplier() {
        synchronized (outputMultiplierCache) {
            outputMultiplierCache.invalidate();
        }
    }

    @Override
    public Object getOutputMultiplierSourceKey() {
        return getPos();
    }

    @Override
    public long getOutputMultiplierContribution() {
        return getMountedOutputMultiplier();
    }

    static final class OutputMultiplierCache {

        private long cachedTick = Long.MIN_VALUE;
        private int cachedValue = 1;
        private boolean valid;
        private long generation;

        int get(long tick, Iterable<?> modules) {
            // 入参必须是不可变快照——锁外遍历安全，命中与未命中路径都零复制零分配。
            while (true) {
                long observedGeneration;
                synchronized (this) {
                    if (valid && cachedTick == tick) {
                        return cachedValue;
                    }
                    observedGeneration = generation;
                }
                int multiplier = calculateMultiplier(modules);
                synchronized (this) {
                    if (generation != observedGeneration) {
                        continue;
                    }
                    cachedTick = tick;
                    cachedValue = multiplier;
                    valid = true;
                    return multiplier;
                }
            }
        }

        private static int calculateMultiplier(Iterable<?> modules) {
            int multiplier = 1;
            for (Object module : modules) {
                if (module instanceof IPrimordialOutputMultiplierModule outputModule) {
                    int moduleMultiplier = Math.max(1,
                            Math.min(1000, outputModule.getCurrentOutputMultiplier()));
                    multiplier = Math.max(multiplier, moduleMultiplier);
                    if (multiplier >= 1000) {
                        break;
                    }
                }
            }
            return multiplier;
        }

        synchronized void invalidate() {
            generation++;
            cachedTick = Long.MIN_VALUE;
            cachedValue = 1;
            valid = false;
        }
    }

    // ========== 球体渲染风格 ==========

    public PrimordialSphereStyle getSphereStyle() {
        return PrimordialSphereStyle.byIndex(sphereStyle);
    }

    public void setSphereStyle(PrimordialSphereStyle style) {
        if (style == null || sphereStyle == style.ordinal()) return;
        sphereStyle = style.ordinal();
        notifyBlockUpdate();
    }

    public int moduleSlotBonus() {
        if (!isRemote()) {
            refreshMatterBonusLevel();
        }
        return matterBonusLevel;
    }

    public boolean canControlStarRender() {
        return moduleSlotBonus() >= STAR_PANEL_MIN_MODULE_LEVEL;
    }

    public boolean isStarRenderManual() {
        return starRenderMode == STAR_MODE_MANUAL;
    }

    public int getStarRadiusOverride() {
        return starRadiusOverride;
    }

    public int getStarHueOverride() {
        return starHueOverride;
    }

    public int getStarPalette() {
        return starPalette;
    }

    public int getStarRainbowPeriodTicks() {
        return starRainbowPeriodTicks;
    }

    public boolean isStarAlwaysWorking() {
        return starAlwaysWorking;
    }

    public void setStarAlwaysWorking(boolean value) {
        if (starAlwaysWorking == value) return;
        starAlwaysWorking = value;
        notifyBlockUpdate();
    }

    public void toggleStarPalette() {
        if (!canControlStarRender() || isStarRenderManual()) return;
        starPalette = starPalette == STAR_PALETTE_SPECTRAL
                ? STAR_PALETTE_RAINBOW : STAR_PALETTE_SPECTRAL;
        notifyBlockUpdate();
    }

    public void cycleRainbowPeriod() {
        if (!canControlStarRender() || isStarRenderManual()
                || starPalette != STAR_PALETTE_RAINBOW) {
            return;
        }
        int index = 0;
        for (int i = STAR_RAINBOW_PERIODS.length - 1; i >= 0; i--) {
            if (STAR_RAINBOW_PERIODS[i] <= starRainbowPeriodTicks) {
                index = i;
                break;
            }
        }
        starRainbowPeriodTicks = STAR_RAINBOW_PERIODS[(index + 1) % STAR_RAINBOW_PERIODS.length];
        notifyBlockUpdate();
    }

    public void toggleStarRenderMode() {
        if (!canControlStarRender()) return;
        if (isStarRenderManual()) {
            starRenderMode = STAR_MODE_FOLLOW_LEVEL;
        } else {
            starRenderMode = STAR_MODE_MANUAL;
            if (starRadiusOverride < STAR_RADIUS_MIN) {
                starRadiusOverride = estimateLevelRadius();
            }
            if (starHueOverride < 0) {
                starHueOverride = estimateLevelHue();
            }
        }
        notifyBlockUpdate();
    }

    public void stepStarRadius(int delta) {
        if (!canControlStarRender()) return;
        int base = starRadiusOverride < STAR_RADIUS_MIN ? estimateLevelRadius() : starRadiusOverride;
        int next = Math.max(STAR_RADIUS_MIN, Math.min(base + delta, STAR_RADIUS_MAX));
        if (next == starRadiusOverride) return;
        starRadiusOverride = next;
        notifyBlockUpdate();
    }

    public void stepStarHue(int delta) {
        if (!canControlStarRender()) return;
        int base = starHueOverride < 0 ? estimateLevelHue() : starHueOverride;
        int next = Math.floorMod(base + delta, 360);
        if (next == starHueOverride) return;
        starHueOverride = next;
        notifyBlockUpdate();
    }

    private int estimateLevelRadius() {
        int level = Math.max(0, Math.min(moduleSlotBonus(), 17));
        return Math.max(STAR_RADIUS_MIN,
                Math.min(STAR_RADIUS_MAX,
                        Math.round(13.0F + (35.1F - 13.0F) * level / 17.0F)));
    }

    private int estimateLevelHue() {
        int level = Math.max(0, Math.min(moduleSlotBonus(), 17));
        return Math.round(300.0F * level / 17.0F);
    }

    private static boolean isMatterModuleStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null
                && PrimordialOmegaEngineModuleBase.getModuleLevelById(key.toString()) > 0;
    }

    private int computeMatterBonusLevel() {
        ItemStack stack = matterBonusSlot.storage.getStackInSlot(0);
        if (stack.getCount() != 64) return 0;
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? 0
                : PrimordialOmegaEngineModuleBase.getModuleLevelById(key.toString());
    }

    private void refreshMatterBonusLevel() {
        int next = computeMatterBonusLevel();
        if (next == matterBonusLevel) return;
        matterBonusLevel = next;
        notifyBlockUpdate();
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();
            SlotWidget bonusSlot = new SlotWidget(
                    matterBonusSlot.storage, 0, size.width - 30, size.height - 68, true, true);
            bonusSlot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            bonusSlot.setHoverTooltips(
                    Component.literal("§d§l物质模块专属槽"),
                    Component.literal("§7只收山海物质模块"),
                    Component.literal("§7必须放满 §b64§7 个同种模块才生效"),
                    Component.literal("§7生效后：中子星半径与颜色随等级变化"));
            group.addWidget(bonusSlot);
        }
        return widget;
    }

    @Override
    protected void attachCleanConfigurators(ConfiguratorPanel panel) {
        super.attachCleanConfigurators(panel);
        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                GuiTextures.BUTTON_SWITCH_VIEW.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D),
                GuiTextures.BUTTON_SWITCH_VIEW.getSubTexture(0.0D, 0.5D, 1.0D, 0.5D),
                () -> getSphereStyle() == PrimordialSphereStyle.NEUTRON_STAR,
                (clickData, pressed) -> setSphereStyle(pressed.booleanValue()
                        ? PrimordialSphereStyle.NEUTRON_STAR
                        : PrimordialSphereStyle.UNIVERSE))
                .setTooltipsSupplier(PrimordialOmegaEngineMachine::sphereStyleTooltips));
        panel.attachConfigurators(new StarRenderConfigurator(this));
        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                GuiTextures.BUTTON_WORKING_ENABLE.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D)
                        .setColor(0xFF5A5A5A),
                GuiTextures.BUTTON_WORKING_ENABLE.getSubTexture(0.0D, 0.0D, 1.0D, 0.5D),
                this::isStarAlwaysWorking,
                (clickData, pressed) -> setStarAlwaysWorking(Boolean.TRUE.equals(pressed)))
                .setTooltipsSupplier(PrimordialOmegaEngineMachine::starAlwaysWorkingTooltips));
    }

    private static List<Component> starAlwaysWorkingTooltips(boolean pressed) {
        List<Component> tooltips = new ArrayList<>(3);
        tooltips.add(Component.literal("始终渲染为工作状态：")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(pressed ? "开" : "关（默认）")
                        .withStyle(pressed ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY)));
        tooltips.add(Component.literal("打开后：中子星光束常亮、轨道环常转").withStyle(ChatFormatting.GRAY));
        tooltips.add(Component.literal("这是机器状态，所有人都会看到并随存档保存").withStyle(ChatFormatting.GOLD));
        return tooltips;
    }

    /**
     * tooltip 只在客户端求值，所以这里读客户端本地的 COMMON 配置是安全的。
     * 全局覆盖生效时必须明说：这颗钮改的仍是所有人都看得到的机器状态，但<b>你自己</b>的画面不会变。
     * 否则它在覆盖者眼里就是一颗按了没反应的假开关。
     */
    private static List<Component> sphereStyleTooltips(boolean pressed) {
        List<Component> tooltips = new ArrayList<>(3);
        tooltips.add(Component.translatable("gt_shanhai.gui.sphere_style")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.translatable(pressed
                        ? "gt_shanhai.gui.sphere_style.neutron_star"
                        : "gt_shanhai.gui.sphere_style.universe")));
        tooltips.add(Component.translatable("gt_shanhai.gui.sphere_style.info"));
        if (isSphereStyleOverridden()) {
            tooltips.add(Component.translatable("gt_shanhai.gui.sphere_style.overridden")
                    .withStyle(ChatFormatting.GOLD));
        }
        return tooltips;
    }

    private static boolean isSphereStyleOverridden() {
        return DShanhaiConfig.COMMON_SPEC.isLoaded()
                && DShanhaiConfig.COMMON.primordialSphereStyle.get()
                        != DShanhaiConfig.ConfigValues.SphereStyleOverride.FOLLOW_MACHINE;
    }

    // ========== 显示 ==========

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        var inf = DShanhaiTextUtil.createUltimateRainbow("无限");
        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(inf)
                .append(Component.literal("个配方")));
        textList.add(Component.translatable("gtladditions.multiblock.threads", inf));
        // 超限器状态检测
        if (hasOverdriverInstalled()) {
            textList.add(Component.literal("")
                    .append(DShanhaiTextUtil.createUltimateRainbow("◆ 超限模式 · 已激活")));
        }
    }

    public boolean hasOverdriverInstalled() {
        for (IMultiPart part : getParts()) {
            if (part instanceof com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine mh
                    && mh.hasParallelOverdriver()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 覆写继承自 GTLAdd 基类的原生 addEnergyDisplay()：宿主 getMaxVoltage() 同样写死
     * Long.MAX_VALUE，原生逻辑只会显示永远不变的"最大功率(MAX+16)"假数字。真实经济体系是
     * com.hepdd.gtmthings 的 WirelessEnergyManager（UUID 记账的 BigInteger 全局电网，
     * 见 PrimordialOmegaEngineModuleBase.onWorking()），这里直接读同一份电网余额显示。
     */
    @Override
    protected void addEnergyDisplay(List<Component> textList) {
        UUID uuid = getUuid();
        if (uuid == null) return;
        try {
            Class<?> mgr = Class.forName("com.hepdd.gtmthings.api.misc.WirelessEnergyManager");
            Object total = mgr.getMethod("getUserEU", UUID.class).invoke(null, uuid);
            if (total instanceof java.math.BigInteger bigTotal) {
                textList.add(Component.literal("")
                        .append(DShanhaiTextUtil.createElectricText("⚡️ 电网能源总量: "))
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
            addEnergyDisplay(textList);
            addMachineModeDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
            // 显示已安装模块数（读不可变快照，免锁）
            int moduleCount = moduleSnapshot.size();
            if (moduleCount > 0) {
                textList.add(Component.translatable("tooltip.gtlcore.installed_module_count", moduleCount)
                        .withStyle(ChatFormatting.AQUA));
            }
            int outputMultiplier = getMountedOutputMultiplier();
            if (outputMultiplier > 1) {
                textList.add(Component.literal("万象衍生倍率: " + outputMultiplier + "x")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_omega_engine.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
