package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import com.gregtechceu.gtceu.api.capability.IDataAccessHatch;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMaintenanceMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.common.machine.trait.InfiniteCWUContainer;
import com.gregtechceu.gtceu.api.GTValues;
import com.gtladd.gtladditions.api.machine.IThreadModifierMachine;
import com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart;

import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.IAutoConfigurationMaintenanceHatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DShanhaiMaintenanceHatchMachine extends MultiblockPartMachine
        implements IMaintenanceBypassPart, IMachineLife,
                   IAutoConfigurationMaintenanceHatch, IMaintenanceMachine,
                   IParallelHatch, IThreadModifierPart, IDataAccessHatch {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            DShanhaiMaintenanceHatchMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);

    private static final float DEFAULT_MIN = 0.2f;
    private static final float DEFAULT_MAX = 1.2f;
    private static final float DURATION_STEP = 0.05f;

    private static final long DEFAULT_MAX_PARALLEL = 256L;
    private static final long PARALLEL_STEP = 1L;

    /** 物质模块 ID → [minDuration, maxDuration]，顺序：初级→高级→终极→创造 */
    private static final Map<String, float[]> MODULE_RANGES = new LinkedHashMap<>();
    /** 物质模块 ID → 最大并行 */
    private static final Map<String, Long> MODULE_MAX_PARALLEL = new LinkedHashMap<>();
    static {
        MODULE_RANGES.put("dishanhai:wzrm",      new float[]{0.50f,     2.0f});   // 入门
        MODULE_RANGES.put("dishanhai:wzjc",      new float[]{0.35f,     5.0f});   // 基础
        MODULE_RANGES.put("dishanhai:wzcz1",     new float[]{0.20f,    10.0f});   // 推演
        MODULE_RANGES.put("dishanhai:wzxc",      new float[]{0.16f,    30.0f});   // 虚像
        MODULE_RANGES.put("dishanhai:wzsb",      new float[]{0.12f,   100.0f});   // 嬗变
        MODULE_RANGES.put("dishanhai:wzax",      new float[]{0.10f,   300.0f});   // 暗星
        MODULE_RANGES.put("dishanhai:wzcz2",     new float[]{0.08f,  1000.0f});   // 重组
        MODULE_RANGES.put("dishanhai:wzqs",      new float[]{0.05f,  2000.0f});   // 虚数
        MODULE_RANGES.put("dishanhai:wzgl",      new float[]{0.03f,  3000.0f});   // 归零
        MODULE_RANGES.put("dishanhai:wzhy",      new float[]{0.025f, 3500.0f});   // 巅峰
        MODULE_RANGES.put("dishanhai:wzsw",      new float[]{0.02f,  4000.0f});   // 升维
        MODULE_RANGES.put("dishanhai:wzcx",      new float[]{0.015f, 5000.0f});   // 超限
        MODULE_RANGES.put("dishanhai:wzdf",      new float[]{0.012f, 5500.0f});   // 混沌
        MODULE_RANGES.put("dishanhai:wzyh",      new float[]{0.01f,  6000.0f});   // 永恒
        MODULE_RANGES.put("dishanhai:wzcz3",     new float[]{0.005f, 8000.0f});   // 物质创造
        MODULE_RANGES.put("dishanhai:reality_anchor_module", new float[]{0.003f, 9000.0f});  // 现实锚点
        MODULE_RANGES.put("dishanhai:create_mk", new float[]{0.001f, 10000.0f});  // 创始

        MODULE_MAX_PARALLEL.put("dishanhai:wzrm",               256L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzjc",              1024L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzcz1",             2048L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzxc",              4096L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzsb",              8192L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzax",             12288L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzcz2",            16384L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzqs",             65536L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzgl",            524288L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzhy",           1048576L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzsw",           2097152L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzcx",          268435456L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzdf",         1073741824L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzyh",         2147483647L);
        MODULE_MAX_PARALLEL.put("dishanhai:wzcz3",     4611686018427387903L);  // ~4.6e18
        MODULE_MAX_PARALLEL.put("dishanhai:reality_anchor_module", 6917529027641081855L);  // ~6.9e18
        MODULE_MAX_PARALLEL.put("dishanhai:create_mk", Long.MAX_VALUE);         // 无限
    }

    private float durationMultiplier = 1.0f;
    @Persisted private float minMultiplier = DEFAULT_MIN;
    @Persisted private float maxMultiplier = DEFAULT_MAX;

    @Persisted private long currentParallel = 1;
    @Persisted private long maxParallel = DEFAULT_MAX_PARALLEL;

    @Persisted private boolean bypassVoltage = true;
    @Persisted private boolean bypassResearch = true;
    @Persisted private boolean bypassChance = true;
    @Persisted private boolean bypassConditions = true;
    @Persisted private boolean bypassTemperature = true;
    @Persisted private int energyTier = 15;

    private static long getTierVoltage(int tier) {
        if (tier == 15) return Long.MAX_VALUE;
        if (tier == 14) return GTValues.V[14];
        if (tier < 0 || tier >= GTValues.V.length) return GTValues.V[14];
        return GTValues.V[tier];
    }

    private static String getTierName(int tier) {
        if (tier == 15) return "MAX+16";
        if (tier == 14) return "MAX";
        if (tier < 0 || tier >= GTValues.VN.length) return "MAX";
        return GTValues.VN[tier];
    }
    @Persisted private boolean threadEnabled = true;

    private long threadCount = 0;  // threadCount long 支持 > int 上限
    private long threadMax = 0;  // 线程上限

    // ====== 产出倍率（创造模块/大反冲解锁）======
    private boolean outputMultiplierEnabled = true;
    private float outputMultiplier = 1.0f;
    private static final float OUTPUT_MIN = 1.0f;
    private static final float OUTPUT_MAX = 5.0f;
    private static final float OUTPUT_STEP = 0.5f;

    @Persisted private final ItemStackTransfer moduleSlot;
    @Persisted private final ItemStackTransfer astralSlot;
    @Persisted private final ItemStackTransfer tearSlot;
    @Persisted private final ItemStackTransfer threadBoostSlot;

    public DShanhaiMaintenanceHatchMachine(IMachineBlockEntity holder) {
        super(holder);
        attachTraits(new InfiniteCWUContainer(this));
        // 无限能源——handleRecipeInner 返回 null 彻底绕过 EU 消耗
        attachTraits(new com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer(this,
                Long.MAX_VALUE, GTValues.V[14], Long.MAX_VALUE,
                GTValues.V[14], Long.MAX_VALUE) {
            @Override public long getInputVoltage() { return getTierVoltage(energyTier); }
            @Override public long getOutputVoltage() { return getTierVoltage(energyTier); }
            @Override public long getEnergyCapacity() { return Long.MAX_VALUE; }
            @Override public long getEnergyStored() { return Long.MAX_VALUE; }
            @Override public long getInputAmperage() { return Long.MAX_VALUE; }
            @Override public long getOutputAmperage() { return Long.MAX_VALUE; }
            @Override
            public java.util.List<Long> handleRecipeInner(com.gregtechceu.gtceu.api.capability.recipe.IO io,
                                                           com.gregtechceu.gtceu.api.recipe.GTRecipe recipe,
                                                           java.util.List<Long> left,
                                                           String slotName, boolean simulate) {
                return null; // 全部 EU 需求已满足，不消耗任何能量
            }
        });
        moduleSlot = new ItemStackTransfer(1) {
            @Override
            public int getSlotLimit(int slot) { return 1; }
        };
        moduleSlot.setFilter(this::isValidModule);
        moduleSlot.setOnContentsChanged(this::updateRangeFromModule);

        astralSlot = new ItemStackTransfer(1) {
            @Override
            public int getSlotLimit(int slot) { return 64; }

            @Override
            public int getStackLimit(int slot, ItemStack stack) {
                // 时间逆转协议只能放1个，星阵可以堆64
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                return "dishanhai:time_reversal_protocol".equals(id) ? 1 : 64;
            }
        };
        astralSlot.setFilter(this::isValidAstral);
        astralSlot.setOnContentsChanged(this::updateThreadCount);

        tearSlot = new ItemStackTransfer(1) {
            @Override
            public int getSlotLimit(int slot) { return 1; }
        };
        tearSlot.setFilter(this::isValidTear);
        tearSlot.setOnContentsChanged(this::onTearChanged);

        threadBoostSlot = new ItemStackTransfer(1) {
            @Override public int getSlotLimit(int slot) { return 64; }
        };
        threadBoostSlot.setFilter(this::isValidThreadBoost);
        threadBoostSlot.setOnContentsChanged(this::onThreadBoostChanged);
    }

    /** 获取线程增强槽 */
    public ItemStackTransfer getThreadBoostSlot() { return threadBoostSlot; }

    /** 获取星阵/世线信标槽 */
    public ItemStackTransfer getAstralSlot() { return astralSlot; }

    /** 获取大反冲解锁槽 */
    public ItemStackTransfer getTearSlot() { return tearSlot; }

    /** 获取当前能源电压等级 */
    public int getEnergyTier() { return energyTier; }

    // ========== 物质模块 ==========

    public ItemStackTransfer getModuleSlot() { return moduleSlot; }

    private boolean isValidModule(ItemStack stack) {
        if (stack.isEmpty()) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (MODULE_RANGES.containsKey(id)) return true;
        // 也支持自定义 NBT 模块
        CompoundTag tag = stack.getTag();
        return tag != null && (tag.contains("MinDuration") || tag.contains("MaxDuration"));
    }

    // ========== 星阵 ==========

    private static final long THREAD_PER_ASTRAL = 16348L * 64L;  // 1,046,272 普通星阵
    private static final long THREAD_PER_COMPRESSED_ASTRAL = 16348L * 98304L;  // 1,607,073,792 (1536倍压缩) 压缩星阵

    private boolean isValidAstral(ItemStack stack) {
        if (stack.isEmpty()) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return "dishanhai:time_reversal_protocol".equals(id)
                || "gtladditions:astral_array".equals(id)
                || "gtladditions:compressed_astral_array".equals(id);
    }

    private void updateThreadCount() {
        // 创造现实修改模块：直接解锁跨线程，无需星阵槽 4294967294线程值 Integer.MAX_VALUE*2
        ItemStack modStack = moduleSlot.getStackInSlot(0);
        if (!modStack.isEmpty() && "dishanhai:create_mk".equals(
                BuiltInRegistries.ITEM.getKey(modStack.getItem()).toString())) {
            long oldMax = threadMax;
            threadMax = 4294967294L;
            // 仅当上限变化（模块新插入）时才拉满，NBT 加载时不覆盖用户设置的值
            if (threadMax != oldMax) {
                threadCount = threadMax;
                THREAD_LOG.info("updateThreadCount: create_mk 模块激活, threadCount={} threadMax={}", threadCount, threadMax);
            } else if (threadCount > threadMax) {
                threadCount = threadMax;
            }
            return;
        }

        ItemStack stack = astralSlot.getStackInSlot(0);
        int count = stack.getCount();
        THREAD_LOG.info("updateThreadCount: isEmpty={} count={} item={}",
                stack.isEmpty(), count,
                stack.isEmpty() ? "空" : BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (stack.isEmpty() || count == 0) {
            threadMax = 0;
            threadCount = 0;
            return;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        long oldMax = threadMax;
        if ("dishanhai:time_reversal_protocol".equals(id)) {
            threadMax = Long.MAX_VALUE;
        } else if ("gtladditions:compressed_astral_array".equals(id)) {
            long result = (long) count * THREAD_PER_COMPRESSED_ASTRAL;
            threadMax = result < 0 ? Long.MAX_VALUE : Math.min(result, Long.MAX_VALUE);
        } else {
            long result = (long) count * THREAD_PER_ASTRAL;
            threadMax = result < 0 ? Long.MAX_VALUE : Math.min(result, Long.MAX_VALUE);
        }
        // 上限变化时自动拉满；上限不变时保留用户手动调整值
        if (threadMax != oldMax) {
            threadCount = threadMax;
        } else {
            if (threadCount > threadMax) threadCount = threadMax;
        }
        THREAD_LOG.info("updateThreadCount: threadCount={} threadMax={}", threadCount, threadMax);
    }

    // ========== 大反冲槽 ==========

    private boolean isValidTear(ItemStack stack) {
        if (stack.isEmpty()) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return "dishanhai:big_tear".equals(id);
    }

    private void onTearChanged() {
        // 大反冲插入/拔出仅影响产出倍率解锁状态，无需额外逻辑
    }

    // ========== 线程增强槽（世线残片/超限器） ==========

    /** 检测是否装有寰宇并行超限器 */
    public boolean hasParallelOverdriver() {
        var stack = threadBoostSlot.getStackInSlot(0);
        if (stack.isEmpty()) return false;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "dishanhai:universal_parallel_overdriver".equals(id.toString());
    }

    private boolean isValidThreadBoost(ItemStack stack) {
        if (stack.isEmpty()) return true;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if ("dishanhai:universal_parallel_overdriver".equals(id)) return true;
        return com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getThreadBoostValue(id) > 0;
    }

    private void onThreadBoostChanged() {
        // 线程槽变动后强制通知主机重新读取线程数
        if (getControllers() != null) {
            for (var controller : getControllers()) {
                if (controller instanceof IThreadModifierMachine tm && tm.getThreadPartMachine() == this) {
                    tm.setThreadPartMachine(null);
                    tm.setThreadPartMachine(this);
                }
            }
        }
    }

    /** 产出倍率是否已解锁（必须大反冲 + 创造模块同时存在） */
    public boolean isOutputUnlocked() {
        ItemStack moduleStack = moduleSlot.getStackInSlot(0);
        ItemStack tearStack = tearSlot.getStackInSlot(0);
        if (moduleStack.isEmpty() || tearStack.isEmpty()) return false;
        String moduleId = BuiltInRegistries.ITEM.getKey(moduleStack.getItem()).toString();
        String tearId = BuiltInRegistries.ITEM.getKey(tearStack.getItem()).toString();
        boolean hasTear = "dishanhai:big_tear".equals(tearId);
        boolean hasModule = "dishanhai:create_mk".equals(moduleId)
                         || "dishanhai:wzcz3".equals(moduleId);
        return hasTear && hasModule;
    }

    /** 配方概率绕过是否已解锁（需星阵槽放入世线信标） */
    public boolean isChanceBypassUnlocked() {
        ItemStack stack = astralSlot.getStackInSlot(0);
        if (stack.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return "dishanhai:time_reversal_protocol".equals(id);
    }

    /** 配方概率绕过是否已解锁且由玩家启用。 */
    public boolean isChanceBypassEnabled() {
        return bypassChance && isChanceBypassUnlocked();
    }

    /** 获取当前产出倍率（解锁且启用时有效，否则返回1.0） */
    public float getOutputMultiplier() {
        if (!isOutputUnlocked() || !outputMultiplierEnabled) return 1.0f;
        return outputMultiplier;
    }

    private void updateRangeFromModule() {
        ItemStack stack = moduleSlot.getStackInSlot(0);
        if (stack.isEmpty()) {
            minMultiplier = DEFAULT_MIN;
            maxMultiplier = DEFAULT_MAX;
            maxParallel = DEFAULT_MAX_PARALLEL;
            currentParallel = 1;  // 无模块时重置为1，不干扰主机并行
            durationMultiplier = Mth.clamp(durationMultiplier, minMultiplier, maxMultiplier);
            return;
        }

        // 先查内置模块表
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        float[] range = MODULE_RANGES.get(id);
        Long moduleMax = MODULE_MAX_PARALLEL.get(id);
        long oldMaxParallel = maxParallel;
        if (range != null) {
            minMultiplier = range[0];
            maxMultiplier = range[1];
            maxParallel = moduleMax != null ? moduleMax : DEFAULT_MAX_PARALLEL;
        } else {
            // 自定义 NBT 模块
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                if (tag.contains("MinDuration")) {
                    minMultiplier = Mth.clamp(tag.getFloat("MinDuration"), 0.01f, 1.0f);
                } else {
                    minMultiplier = DEFAULT_MIN;
                }
                if (tag.contains("MaxDuration")) {
                    maxMultiplier = Mth.clamp(tag.getFloat("MaxDuration"), 1.0f, 100.0f);
                } else {
                    maxMultiplier = DEFAULT_MAX;
                }
                maxParallel = tag.contains("MaxParallel")
                        ? clampLong(tag.getLong("MaxParallel"), 1L, Long.MAX_VALUE)
                        : DEFAULT_MAX_PARALLEL;
            } else {
                minMultiplier = DEFAULT_MIN;
                maxMultiplier = DEFAULT_MAX;
                maxParallel = DEFAULT_MAX_PARALLEL;
            }
        }
        durationMultiplier = Mth.clamp(durationMultiplier, minMultiplier, maxMultiplier);
        // 仅当上限变化时强制拉满；否则保留用户手动调整的值
        if (maxParallel != oldMaxParallel) {
            currentParallel = maxParallel;
        } else {
            if (currentParallel > maxParallel) currentParallel = maxParallel;
            if (currentParallel < 1) currentParallel = 1;
        }
        updateThreadCount(); // 模块变更时重新评估线程上限，防止 create_mk 取出后残留
    }

    // ========== IAutoConfigurationMaintenanceHatch ==========

    @Override
    public float getDurationMultiplier() {
        return durationMultiplier;
    }

    @Override
    public void setDurationMultiplier(float value) {
        durationMultiplier = Mth.clamp(value, minMultiplier, maxMultiplier);
    }

    // ========== IPparallelHatch（可调并行，long 存储，int 接口） ==========

    @Override
    public int getCurrentParallel() {
        if (hasParallelOverdriver()) return Integer.MAX_VALUE;
        long capped = Math.min(currentParallel, maxParallel);
        return capped > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capped;
    }

    /** 获取原始 long 并行值（突破 Integer.MAX_VALUE 限制） */
    public long getRawParallel() {
        if (hasParallelOverdriver()) return Long.MAX_VALUE;
        return Math.min(currentParallel, maxParallel);
    }

    // ========== IThreadModifierPart（跨配方线程，仿 天球分歧引擎） ==========

    private static final org.slf4j.Logger THREAD_LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:thread");

    @Override
    public int getThreadCount() {
        if (!threadEnabled) return 0;
        if (hasParallelOverdriver()) return Integer.MAX_VALUE;
        long multiplier = getThreadBoostFromSlot();
        long base = Math.min(threadCount, threadMax);
        if (multiplier > 1) {
            long result = base * multiplier;
            if (result < 0) result = Long.MAX_VALUE; // 溢出保护
            result = Math.min(result, Long.MAX_VALUE);
            return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
        }
        return base > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) base;
    }

    /** 获取碎片倍率（base × 堆叠数），无碎片返回 1（不乘） */
    private long getThreadBoostFromSlot() {
        ItemStack stack = threadBoostSlot.getStackInSlot(0);
        if (stack.isEmpty()) return 1;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if ("dishanhai:universal_parallel_overdriver".equals(id)) return 1;
        long base = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getThreadBoostValue(id);
        if (base <= 0) return 1;
        return Math.min((long) stack.getCount() * base, Integer.MAX_VALUE);
    }

    /** 获取原始 long 线程值（突破 Integer.MAX_VALUE 限制） */
    public long getRawThreadCount() {
        if (!threadEnabled) return 0;
        if (hasParallelOverdriver()) return Long.MAX_VALUE;
        long multiplier = getThreadBoostFromSlot();
        long base = Math.min(threadCount, threadMax);
        long result = base * multiplier;
        return result < base ? Long.MAX_VALUE : result; // 溢出保护
    }

    @Override
    public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        THREAD_LOG.debug("addedToController: {} isThreadModifier={}",
                controller.getClass().getSimpleName(), controller instanceof IThreadModifierMachine);
        if (controller instanceof IThreadModifierMachine tm) {
            tm.setThreadPartMachine(this);
            THREAD_LOG.debug("已注册为线程修改器! threadCount={}", threadCount);
        }
    }

    @Override
    public void removedFromController(IMultiController controller) {
        super.removedFromController(controller);
        if (controller instanceof IThreadModifierMachine tm && tm.getThreadPartMachine() == this) {
            tm.setThreadPartMachine(null);
            THREAD_LOG.info("已移除线程修改器注册");
        }
    }

    // ========== IMaintenanceBypassPart 覆写（读取 UI 开关状态） ==========

    @Override public boolean isVoltageBypassEnabled() { return bypassVoltage; }
    @Override public boolean isConditionBypassEnabled() { return bypassConditions; }
    @Override public long getBypassVoltage() { return getTierVoltage(energyTier); }
    public boolean isTemperatureBypassEnabled() { return bypassTemperature; }

    // ========== IDataAccessHatch（创造数据访问——绕过配方研究需求） ==========

    @Override public boolean isCreative() { return bypassResearch; }

    @Override
    public boolean isRecipeAvailable(GTRecipe recipe, java.util.Collection<IDataAccessHatch> seen) {
        return true;  // 创造模式，永远可用
    }

    // ========== IMaintenanceMachine（接管主机维护系统，消除 1.25x） ==========

    @Override public boolean isFullAuto() { return true; }
    @Override public void setTaped(boolean taped) {}
    @Override public boolean isTaped() { return false; }
    @Override public byte startProblems() { return IMaintenanceMachine.NO_PROBLEMS; }
    @Override public byte getMaintenanceProblems() { return IMaintenanceMachine.NO_PROBLEMS; }
    @Override public void setMaintenanceProblems(byte problems) {}
    @Override public int getTimeActive() { return 0; }
    @Override public void setTimeActive(int time) {}
    private int modLogSkip = 0;
    @Override public GTRecipe modifyRecipe(GTRecipe recipe) {
        if (bypassVoltage) {
            boolean hadEU = recipe.inputs.containsKey(EURecipeCapability.CAP);
            recipe.inputs.remove(EURecipeCapability.CAP);
            recipe.tickInputs.remove(EURecipeCapability.CAP);
            recipe.tickInputs.remove(CWURecipeCapability.CAP);
            if (hadEU && ++modLogSkip % 5 == 0) {
                THREAD_LOG.info("modifyRecipe 已清EU! bypassVoltage={}", bypassVoltage);
            }
        }
        if (bypassTemperature) {
            // 抹掉配方温度需求，绕过所有机器的温度检查
            recipe.data.remove("ebf_temp");
            recipe.data.remove("blastFurnaceTemp");
        }
        if (isChanceBypassEnabled()) {
            // 产出概率强制 100%（必定产出），输入概率强制 0%（永不消耗）
            recipe.outputs.values().forEach(list -> {
                if (list != null) for (var c : list) if (c != null && c.chance != c.maxChance) c.chance = c.maxChance;
            });
            recipe.tickOutputs.values().forEach(list -> {
                if (list != null) for (var c : list) if (c != null && c.chance != c.maxChance) c.chance = c.maxChance;
            });
            recipe.inputs.values().forEach(list -> {
                if (list != null) for (var c : list) if (c != null && c.chance != 0) c.chance = 0;
            });
            recipe.tickInputs.values().forEach(list -> {
                if (list != null) for (var c : list) if (c != null && c.chance != 0) c.chance = 0;
            });
        }
        return recipe;
    }

    // ========== NBT ==========

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putFloat("DurationMultiplier", durationMultiplier);
        tag.putLong("CurrentParallel", currentParallel);
        tag.putLong("MaxParallel", maxParallel);
        tag.putInt("EnergyTier", energyTier);
        tag.putBoolean("BypassVoltage", bypassVoltage);
        tag.putBoolean("BypassConditions", bypassConditions);
        tag.putBoolean("BypassResearch", bypassResearch);
        tag.putBoolean("BypassChance", bypassChance);
        tag.putBoolean("BypassTemperature", bypassTemperature);
        tag.putBoolean("ThreadEnabled", threadEnabled);
        tag.putLong("ThreadCount", threadCount);
        tag.putLong("ThreadMax", threadMax);
        tag.putBoolean("OutputMultiplierEnabled", outputMultiplierEnabled);
        tag.putFloat("OutputMultiplier", outputMultiplier);
        tag.put("ModuleItem", moduleSlot.serializeNBT());
        tag.put("AstralItem", astralSlot.serializeNBT());
        tag.put("TearItem", tearSlot.serializeNBT());
        tag.put("ThreadBoostItem", threadBoostSlot.serializeNBT());
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains("DurationMultiplier")) {
            durationMultiplier = tag.getFloat("DurationMultiplier");
        }
        if (tag.contains("CurrentParallel")) {
            currentParallel = tag.getLong("CurrentParallel");
        }
        if (tag.contains("MaxParallel")) {
            maxParallel = tag.getLong("MaxParallel");
        }
        if (tag.contains("EnergyTier")) {
            energyTier = tag.getInt("EnergyTier");
        }
        if (tag.contains("BypassVoltage")) {
            bypassVoltage = tag.getBoolean("BypassVoltage");
        }
        if (tag.contains("BypassConditions")) {
            bypassConditions = tag.getBoolean("BypassConditions");
        }
        if (tag.contains("BypassResearch")) {
            bypassResearch = tag.getBoolean("BypassResearch");
        }
        if (tag.contains("BypassChance")) {
            bypassChance = tag.getBoolean("BypassChance");
        }
        if (tag.contains("BypassTemperature")) {
            bypassTemperature = tag.getBoolean("BypassTemperature");
        }
        if (tag.contains("ThreadEnabled")) {
            threadEnabled = tag.getBoolean("ThreadEnabled");
        }
        if (tag.contains("ThreadCount")) {
            threadCount = tag.getLong("ThreadCount");
        }
        if (tag.contains("ThreadMax")) {
            threadMax = tag.getLong("ThreadMax");
        }
        if (tag.contains("OutputMultiplierEnabled")) {
            outputMultiplierEnabled = tag.getBoolean("OutputMultiplierEnabled");
        }
        if (tag.contains("OutputMultiplier")) {
            outputMultiplier = tag.getFloat("OutputMultiplier");
        }
        if (tag.contains("ModuleItem")) {
            moduleSlot.deserializeNBT(tag.getCompound("ModuleItem"));
        }
        if (tag.contains("AstralItem")) {
            astralSlot.deserializeNBT(tag.getCompound("AstralItem"));
        }
        if (tag.contains("TearItem")) {
            tearSlot.deserializeNBT(tag.getCompound("TearItem"));
        }
        if (tag.contains("ThreadBoostItem")) {
            threadBoostSlot.deserializeNBT(tag.getCompound("ThreadBoostItem"));
        }
        updateRangeFromModule();
        updateThreadCount();
    }

    // ========== UI（原版 gtlcore 风格） ==========

    @Override
    public Widget createUIWidget() {
        var group = new WidgetGroup(0, 0, 220, 210);

        // 上方文字区
        var scrollGroup = new DraggableScrollableWidgetGroup(4, 4, 212, 160);
        scrollGroup.setBackground(GuiTextures.DISPLAY);

        var textPanel = new ComponentPanelWidget(4, 5, this::buildText);
        textPanel.setMaxWidthLimit(196);
        textPanel.clickHandler((cmd, cd) -> {
            if (cd.isRemote) return;
            // 并行/线程用大跨度（值范围可到数十亿），其余用小跨度
            boolean isBig = "psub".equals(cmd) || "padd".equals(cmd)
                    || "tsub".equals(cmd) || "tadd".equals(cmd);
            long steps;
            if (cd.isCtrlClick && cd.isShiftClick) steps = isBig ? 100_000_000L : 10_000L;
            else if (cd.isCtrlClick) steps = isBig ? 1_000_000L : 1_000L;
            else if (cd.isShiftClick) steps = isBig ? 100_000L : 100L;
            else steps = 1L;
            switch (cmd) {
                case "sub":
                    setDurationMultiplier(durationMultiplier - DURATION_STEP * (int) steps);
                    break;
                case "add":
                    setDurationMultiplier(durationMultiplier + DURATION_STEP * (int) steps);
                    break;
                case "psub":
                    if (cd.button == 1) steps = currentParallel; // 右键：以当前值为步进
                    currentParallel = clampLong(currentParallel - steps, 1, maxParallel);
                    break;
                case "padd":
                    if (cd.button == 1) steps = currentParallel; // 右键：以当前值为步进
                    currentParallel = clampLong(currentParallel + steps, 1, maxParallel);
                    break;
                case "tsub":
                    threadCount = Math.max(0, threadCount - steps);
                    break;
                case "tadd":
                    threadCount = Math.min(threadMax, threadCount + steps);
                    break;
                case "thread_toggle":
                    threadEnabled = !threadEnabled;
                    break;
                case "bypass_voltage":
                    bypassVoltage = !bypassVoltage;
                    break;
                case "bypass_cond":
                    bypassConditions = !bypassConditions;
                    break;
                case "bypass_research":
                    bypassResearch = !bypassResearch;
                    break;
                case "bypass_chance":
                    if (isChanceBypassUnlocked()) bypassChance = !bypassChance;
                    break;
                case "bypass_temp":
                    bypassTemperature = !bypassTemperature;
                    break;
                case "volt_up":
                    if (energyTier < 15) energyTier++;
                    break;
                case "volt_down":
                    if (energyTier > 0) energyTier--;
                    break;
                case "omul_sub":
                    if (isOutputUnlocked()) {
                        outputMultiplier = Math.max(OUTPUT_MIN, outputMultiplier - OUTPUT_STEP);
                    }
                    break;
                case "omul_add":
                    if (isOutputUnlocked()) {
                        outputMultiplier = Math.min(OUTPUT_MAX, outputMultiplier + OUTPUT_STEP);
                    }
                    break;
                case "omul_toggle":
                    outputMultiplierEnabled = !outputMultiplierEnabled;
                    break;
            }
        });
        scrollGroup.addWidget(textPanel);
        group.addWidget(scrollGroup);

        // 物质模块槽
        var moduleSlotWidget = new SlotWidget(moduleSlot, 0, 24, 170);
        moduleSlotWidget.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
        moduleSlotWidget.setHoverTooltips(
                Component.literal("§6§l物质模块"),
                Component.literal("§7可插入山海物质模块系列"),
                Component.literal("§7控制耗时倍率+并行上限"));
        group.addWidget(moduleSlotWidget);

        // 大反冲槽
        var tearSlotWidget = new SlotWidget(tearSlot, 0, 68, 170);
        tearSlotWidget.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
        tearSlotWidget.setHoverTooltips(
                Component.literal("§d§l逆向坍缩·大反冲"),
                Component.literal("§7放入后解锁配方产出倍率"),
                Component.literal("§7与其他解锁物独立运作"));
        group.addWidget(tearSlotWidget);

        // 星阵槽
        var astralSlotWidget = new SlotWidget(astralSlot, 0, 112, 170);
        astralSlotWidget.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
        astralSlotWidget.setHoverTooltips(
                Component.literal("§5§l线程解锁"),
                Component.literal("§7世线信标×1 → 解锁全线程"),
                Component.literal("§7普通星阵 → 每格=" + String.format("%,d", THREAD_PER_ASTRAL) + "线程"),
                Component.literal("§d压缩星阵 → 每格=" + String.format("%,d", THREAD_PER_COMPRESSED_ASTRAL) + "线程"));
        group.addWidget(astralSlotWidget);

        // 线程增强槽
        var boostSlotWidget = new SlotWidget(threadBoostSlot, 0, 156, 170);
        boostSlotWidget.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
        boostSlotWidget.setHoverTooltips(
                Component.literal("§e§l线程增强"),
                Component.literal("§7世线残片 — 倍率叠加至线程数"),
                Component.literal("§7§6寰宇并行超限器 — 每个配方独立 MAX 并行"),
                Component.literal("§7§6超限器同时锁定线程为 Long.MAX_VALUE"));
        group.addWidget(boostSlotWidget);

        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    private boolean hasCreateMkModule() {
        ItemStack stack = moduleSlot.getStackInSlot(0);
        return !stack.isEmpty() && "dishanhai:create_mk".equals(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private void buildText(List<Component> list) {
        // ====== 耗时倍率 ======
        Component desc;
        if (durationMultiplier == 1.0f) {
            desc = Component.translatable("gtceu.maintenance.configurable_duration.unchanged_description");
        } else {
            desc = Component.translatable("gtceu.maintenance.configurable_duration.changed_description",
                    String.format("%.2f", durationMultiplier));
        }
        list.add(Component.literal("§6§l→ 耗时倍率 §r" + String.format("%.2f", durationMultiplier))
                .withStyle(Style.EMPTY.withHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, desc))));
        list.add(Component.literal(
                String.format("§7  [%.2f~%.2f] §8步进:C+S=%.0f C=%.0f S=%.0f §7←越小越快",
                        minMultiplier, maxMultiplier,
                        DURATION_STEP * 100, DURATION_STEP * 10, DURATION_STEP)));
        var durMod = Component.translatable("gtceu.maintenance.configurable_duration.modify");
        durMod.append(" ");
        durMod.append(ComponentPanelWidget.withButton(Component.literal("[-]"), "sub"));
        durMod.append(" ");
        durMod.append(ComponentPanelWidget.withButton(Component.literal("[+]"), "add"));
        list.add(durMod);

        // ====== 并行数量 ======
        list.add(Component.literal(""));
        list.add(Component.literal("§6§l→ 并行数量 §r" + currentParallel));
        list.add(Component.literal(
                String.format("§7  [1 ~ %s]  §8C+S=1亿 C=100w S=10w", formatBigNum(maxParallel))));
        var parMod = Component.literal("§r调整 ");
        parMod.append(ComponentPanelWidget.withButton(Component.literal("[-]"), "psub"));
        parMod.append(" ");
        parMod.append(ComponentPanelWidget.withButton(Component.literal("[+]"), "padd"));
        list.add(parMod);

        // ====== 跨配方线程 ======
        list.add(Component.literal(""));
        String threadInfo;
        long displayThreadMax = 0;
        ItemStack astralStack = astralSlot.getStackInSlot(0);
        String astralId = astralStack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(astralStack.getItem()).toString();
        boolean isTimeProto = "dishanhai:time_reversal_protocol".equals(astralId);
        boolean isCompressed = "gtladditions:compressed_astral_array".equals(astralId);
        boolean isRegular = "gtladditions:astral_array".equals(astralId);

        if (hasParallelOverdriver()) {
            // 超限器直接旁路"解锁物"门槛（getRawThreadCount 已经这么做了），这里的范围提示
            // 之前没跟上，会显示"[0~0] 未放入解锁物"跟上面已经拉满的线程数自相矛盾。
            threadInfo = "§7§6寰宇并行超限器 → 线程已解锁至 MAX";
            displayThreadMax = Long.MAX_VALUE;
        } else if (hasCreateMkModule()) {
            threadInfo = "§d◇ 创始现实修改模块 → 线程范围 0~4,294,967,294";
            displayThreadMax = 4294967294L;
        } else if (isTimeProto) {
            threadInfo = "§d◇ 世线信标 → 全线程已解锁";
            displayThreadMax = Long.MAX_VALUE;
        } else if (isCompressed || isRegular) {
            long perItem = isCompressed ? THREAD_PER_COMPRESSED_ASTRAL : THREAD_PER_ASTRAL;
            String label = isCompressed ? "压缩星阵" : "星阵";
            long realMax = (long) astralStack.getCount() * perItem;
            if (realMax < 0) realMax = Long.MAX_VALUE;
            long multiplier = getThreadBoostFromSlot();
            if (multiplier > 1) {
                long total = realMax * multiplier;
                if (total < 0) total = Long.MAX_VALUE;
                threadInfo = String.format("§7☆%s×%s §7×%,d=%,d线程",
                        label, astralStack.getCount(), multiplier, total);
                displayThreadMax = Math.min(total, Long.MAX_VALUE);
            } else {
                threadInfo = String.format("§7☆%s×%s §7=%,d线程",
                        label, astralStack.getCount(), realMax);
                displayThreadMax = threadMax;
            }
        } else if (threadMax > 0) {
            threadInfo = String.format("§7★线程上限 %,d", threadMax);
            displayThreadMax = threadMax;
        } else {
            threadInfo = "§8○ 未放入解锁物，线程不可用";
            displayThreadMax = 0;
        }
        list.add(Component.literal("§5§l→ 跨配方线程 §r" + String.format("%,d", getRawThreadCount())));
        list.add(Component.literal("§7  [0 ~ " + String.format("%,d", displayThreadMax) + "]"));

        // 线程增强乘率
        ItemStack boostStack = threadBoostSlot.getStackInSlot(0);
        if (!boostStack.isEmpty() && !"dishanhai:universal_parallel_overdriver".equals(
                BuiltInRegistries.ITEM.getKey(boostStack.getItem()).toString())) {
            long mul = getThreadBoostFromSlot();
            if (mul > 1) {
                list.add(Component.literal("§e◇ 线程增强乘率: §f×" + String.format("%,d", mul)));
            }
        }

        list.add(Component.literal("  " + threadInfo));
        var threadMod = Component.literal("§r调整 ");
        threadMod.append(ComponentPanelWidget.withButton(Component.literal("[-]"), "tsub"));
        threadMod.append(" ");
        threadMod.append(ComponentPanelWidget.withButton(Component.literal("[+]"), "tadd"));
        list.add(threadMod);
        list.add(Component.literal(" §5Ω 跨配方线程: " + (threadEnabled ? "§a● 已激活" : "§8○ 已关闭")));
        var threadToggle = Component.literal("§r  ");
        threadToggle.append(ComponentPanelWidget.withButton(
                Component.literal(threadEnabled ? "§c[关闭]" : "§a[开启]"), "thread_toggle"));
        list.add(threadToggle);

        // 线程增强槽物品
        if (!boostStack.isEmpty()) {
            String boostId = BuiltInRegistries.ITEM.getKey(boostStack.getItem()).toString();
            if ("dishanhai:universal_parallel_overdriver".equals(boostId)) {
                list.add(Component.literal(" §d◆ 寰宇并行超限器 → 并行/线程 MAX"));
            } else {
                list.add(Component.literal(" §7  └ 世线残片×" + boostStack.getCount()
                        + " (基础倍率×" + String.format("%,d",
                        com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase
                                .getThreadBoostValue(boostId)) + ")"));
            }
        }

        // ====== 能源电压 ======
        list.add(Component.literal(""));
        long volt = getTierVoltage(energyTier);
        String voltStr = volt >= Long.MAX_VALUE ? "9,223,372,036,854,775,807" : String.format("%,d", volt);
        list.add(Component.literal("§e§l→ 能源电压 §r§f" + getTierName(energyTier)
                + " §7(" + voltStr + "EU/t)"));
        var eV = Component.literal("§r调整 ");
        eV.append(ComponentPanelWidget.withButton(Component.literal("§c[<]"), "volt_down"));
        eV.append(" ");
        eV.append(ComponentPanelWidget.withButton(Component.literal("§a[>]"), "volt_up"));
        list.add(eV);

        // ====== 产出倍率（创造模块/大反冲解锁） ======
        list.add(Component.literal(""));
        if (isOutputUnlocked()) {
            list.add(Component.literal("§d§l→ 配方产出倍率 §r§d×" + String.format("%.1f", outputMultiplier)));
            list.add(Component.literal(String.format("§7  [%.1f ~ %.1f]  §d§o创造模块/大反冲已解锁", OUTPUT_MIN, OUTPUT_MAX)));
            var oMod = Component.literal("§r调整 ");
            oMod.append(ComponentPanelWidget.withButton(Component.literal("[-]"), "omul_sub"));
            oMod.append(" ");
            oMod.append(ComponentPanelWidget.withButton(Component.literal("[+]"), "omul_add"));
            list.add(oMod);
            var oToggle = Component.literal("§r  ");
            oToggle.append(ComponentPanelWidget.withButton(
                    Component.literal(outputMultiplierEnabled ? "§c[关闭]" : "§a[开启]"), "omul_toggle"));
            list.add(oToggle);
        } else {
            list.add(Component.literal("§8§l→ 配方产出倍率 §7[锁定]"));
            list.add(Component.literal("§8  §o需同时插入创造模块+大反冲解锁"));
        }

        // ====== 绕过开关 ======
        list.add(Component.literal(""));
        list.add(Component.literal("§c§l→ 绕过开关"));
        // 电压
        var vLine = Component.literal(" §7电压/算力: " + (bypassVoltage ? "§a●开" : "§8○关") + "  ");
        vLine.append(ComponentPanelWidget.withButton(
                Component.literal(bypassVoltage ? "§c[关]" : "§a[开]"), "bypass_voltage"));
        list.add(vLine);
        // 环境条件
        var cLine = Component.literal(" §7环境条件: " + (bypassConditions ? "§a●开" : "§8○关") + "  ");
        cLine.append(ComponentPanelWidget.withButton(
                Component.literal(bypassConditions ? "§c[关]" : "§a[开]"), "bypass_cond"));
        list.add(cLine);
        // 研究
        var rLine = Component.literal(" §7研究需求: " + (bypassResearch ? "§a●开" : "§8○关") + "  ");
        rLine.append(ComponentPanelWidget.withButton(
                Component.literal(bypassResearch ? "§c[关]" : "§a[开]"), "bypass_research"));
        list.add(rLine);
        // 线圈温度
        var tempLine = Component.literal(" §7线圈温度: " + (bypassTemperature ? "§a●开" : "§8○关") + "  ");
        tempLine.append(ComponentPanelWidget.withButton(
                Component.literal(bypassTemperature ? "§c[关]" : "§a[开]"), "bypass_temp"));
        list.add(tempLine);
        // 配方概率
        if (isChanceBypassUnlocked()) {
            var chLine = Component.literal(" §7配方概率: " + (bypassChance ? "§a●开" : "§8○关") + "  ");
            chLine.append(ComponentPanelWidget.withButton(
                    Component.literal(bypassChance ? "§c[关]" : "§a[开]"), "bypass_chance"));
            list.add(chLine);
        } else {
            list.add(Component.literal(" §8§l→ 配方概率 §7[需世线信标]"));
        }

        // ====== 物质模块速查表 ======
        list.add(Component.literal(""));
        list.add(Component.literal("§6§l可插入的物质模块："));
        for (var entry : MODULE_RANGES.entrySet()) {
            var item = BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(entry.getKey()));
            String name = item.getDefaultInstance().getHoverName().getString();
            float[] r = entry.getValue();
            long par = MODULE_MAX_PARALLEL.getOrDefault(entry.getKey(), DEFAULT_MAX_PARALLEL);
            list.add(Component.literal(
                    String.format(" §7%s §a[耗时%.2f~%.2f] §b[并行%s]",
                            name, r[0], r[1], formatBigNum(par))));
        }
        ItemStack inSlot = moduleSlot.getStackInSlot(0);
        if (inSlot.isEmpty()) {
            list.add(Component.literal(" §8§o← 右侧槽位未放入模块"));
        } else {
            list.add(Component.literal(" §b✓ 已放入: " + inSlot.getHoverName().getString()));
        }
        list.add(Component.literal(""));
        list.add(Component.literal("§8§o或任意带 MinDuration/MaxDuration/MaxParallel NBT 物品"));
    }

    private static String formatBigNum(long n) {
        if (n == Long.MAX_VALUE) return "∞";
        if (n >= 1_000_000_000_000_000_000L) return String.format("%.1fE", n / 1_000_000_000_000_000_000.0);
        if (n >= 1_000_000_000_000_000L) return String.format("%.1fP", n / 1_000_000_000_000_000.0);
        if (n >= 1_000_000_000_000L) return String.format("%.1fT", n / 1_000_000_000_000.0);
        if (n >= 1_000_000_000) return String.format("%.1fG", n / 1_000_000_000.0);
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private static long clampLong(long value, long min, long max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() { return MANAGED_FIELD_HOLDER; }
}
