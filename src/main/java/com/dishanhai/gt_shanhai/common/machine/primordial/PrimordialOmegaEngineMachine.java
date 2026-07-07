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
        getModuleSet().add(module);
        hasModules = true;
    }

    @Override
    public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void removeModule(M module) {
        getModuleSet().remove(module);
        hasModules = !getModuleSet().isEmpty();
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
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_omega_engine.name")
                .withStyle(ChatFormatting.GOLD));
    }
}
