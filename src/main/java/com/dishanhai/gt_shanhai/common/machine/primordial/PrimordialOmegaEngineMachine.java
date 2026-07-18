package com.dishanhai.gt_shanhai.common.machine.primordial;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.machine.CleanSelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.primordial.IPrimordialOutputMultiplierModule;

import com.gtladd.gtladditions.utils.antichrist.AntichristPosHelper;

import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;

import com.google.common.primitives.Ints;

import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class PrimordialOmegaEngineMachine extends CleanSelectableRecipeTypeSetMachine
        implements IModularMachineHost<PrimordialOmegaEngineMachine> {

    private static final long MAX_PARALLEL = 9223372036854775807L;

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(PrimordialOmegaEngineMachine.class, CleanSelectableRecipeTypeSetMachine.MANAGED_FIELD_HOLDER);

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    private final Set<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> modules = new ReferenceOpenHashSet<>();
    private final OutputMultiplierCache outputMultiplierCache = new OutputMultiplierCache();

    /** volatile 标志，供渲染线程安全读取，避免模块集竞态死锁 */
    public volatile boolean hasModules;

    public PrimordialOmegaEngineMachine(IMachineBlockEntity holder) {
        super(holder, GTRecipeTypes.FURNACE_RECIPES);
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
        if (checkPattern()) {
            setFlipped(getMultiblockState().isNeededFlip());
            onStructureFormed();
            if (getLevel() instanceof ServerLevel serverLevel) {
                MultiblockWorldSavedData.getOrCreate(serverLevel).addMapping(getMultiblockState());
                MultiblockWorldSavedData.getOrCreate(serverLevel).removeAsyncLogic(this);
            }
        }
    }

    // ========== 模块接口 ==========

    @Override
    public Set<IModularMachineModule<PrimordialOmegaEngineMachine, ?>> getModuleSet() {
        return modules;
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
        // 环隐藏由 FOTC 渲染器每帧处理（见 AbstractRingRenderer）
        safeClearModules();
        scanAndConnectModules();
        hasModules = !modules.isEmpty();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        // 环恢复由 FOTC 渲染器每帧处理
        safeClearModules();
        hasModules = false;
        outputMultiplierCache.invalidate();
    }

    /**
     * 覆写 gtlcore 的 safeClearModules，修复并发修改崩溃。
     * 原版在迭代 getModules() 时 removeFromHost 内部修改了同一集合的底层结构，
     * 导致 fastutil ReferenceOpenHashSet 迭代器 wrapped 变 null。
     */
    @Override
    public void safeClearModules() {
        var snapshot = new ArrayList<>(getModules());
        for (var module : snapshot) {
            module.removeFromHost(this);
        }
        getModuleSet().clear();
        outputMultiplierCache.invalidate();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
    }

    // ========== 模块同步（线程安全 volatile 标志） ==========

    @Override
    public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void addModule(M module) {
        if (getModuleSet().add(module)) {
            outputMultiplierCache.invalidate();
        }
        hasModules = true;
    }

    @Override
    public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void removeModule(M module) {
        if (getModuleSet().remove(module)) {
            outputMultiplierCache.invalidate();
        }
        hasModules = !getModuleSet().isEmpty();
    }

    public int getMountedOutputMultiplier() {
        return outputMultiplierCache.get(getOffsetTimer(), modules);
    }

    static final class OutputMultiplierCache {

        private long cachedTick = Long.MIN_VALUE;
        private int cachedValue = 1;
        private boolean valid;

        int get(long tick, Iterable<?> modules) {
            if (valid && cachedTick == tick) {
                return cachedValue;
            }
            int multiplier = 1;
            for (Object module : modules) {
                if (module instanceof IPrimordialOutputMultiplierModule outputModule) {
                    multiplier = Math.max(multiplier, outputModule.getCurrentOutputMultiplier());
                    if (multiplier >= 1000) {
                        break;
                    }
                }
            }
            cachedTick = tick;
            cachedValue = multiplier;
            valid = true;
            return multiplier;
        }

        void invalidate() {
            if (!valid && cachedTick == Long.MIN_VALUE && cachedValue == 1) {
                return;
            }
            cachedTick = Long.MIN_VALUE;
            cachedValue = 1;
            valid = false;
        }
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
     * 临时排障用：暴露配方查找失败原因/锁定状态/已选类型数量，供在游戏内直接观察卡产原因，
     * 而不必逐条猜测（isLock 卡在旧锁定配方 / 选择集为空 / 具体 FAIL_XXX 原因）。
     * 问题定位后应移除。
     */
    private void addRecipeDiagnosisDisplay(List<Component> textList) {
        var logic = getRecipeLogic();
        textList.add(Component.literal("§7[诊断] 已选类型: " + getSelectedRecipeTypeCount()
                + " §7锁定: " + logic.isLock()));
        var locked = logic.getLockRecipe();
        if (locked != null) {
            textList.add(Component.literal("§7[诊断] 锁定配方: " + locked.getId()));
        }
        var status = logic.getRecipeStatus();
        if (status != null && !status.isSuccess() && status.reason() != null) {
            textList.add(Component.literal("§c[诊断] 上次失败: ").append(status.reason()));
        }
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
            addEnergyDisplay(textList);
            addMachineModeDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
            // 显示已安装模块数
            int moduleCount = modules.size();
            if (moduleCount > 0) {
                textList.add(Component.translatable("tooltip.gtlcore.installed_module_count", moduleCount)
                        .withStyle(ChatFormatting.AQUA));
            }
            int outputMultiplier = getMountedOutputMultiplier();
            if (outputMultiplier > 1) {
                textList.add(Component.literal("万象衍生倍率: " + outputMultiplier + "x")
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            addRecipeDiagnosisDisplay(textList);
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_omega_engine.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
