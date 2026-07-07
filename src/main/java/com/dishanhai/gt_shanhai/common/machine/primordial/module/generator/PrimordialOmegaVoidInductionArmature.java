package com.dishanhai.gt_shanhai.common.machine.primordial.module.generator;
import com.dishanhai.gt_shanhai.common.machine.primordial.*;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import org.gtlcore.gtlcore.utils.MachineIO;

import java.math.BigInteger;
import java.util.UUID;
import java.util.List;

/**
 * 原始真空零点能发生器
 * 从真空量子涨落中提取零点能的蒸汽供能模块
 */
public class PrimordialOmegaVoidInductionArmature extends PrimordialOmegaEngineModuleBase {

    private static final long EU_PER_THEORETICAL_PARALLEL = 1048576L;
    private static final long ZERO_POINT_RECIPE_DURATION = 200L;
    private static final ResourceLocation PROGRAMMED_CIRCUIT_ID =
            new ResourceLocation("gtceu", "programmed_circuit");

    // 实际并行：1号=2^0=1, 2号=2^1=2, N号=2^(N-1)，16号以上 INT_MAX
    private int circuitNumber;
    private boolean circuitFound;
    private TickableSubscription circuitScanSubs;
    private final NotifiableItemStackHandler circuitSlot;

    public PrimordialOmegaVoidInductionArmature(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
        circuitSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        circuitSlot.setFilter(stack -> stack == null || stack.isEmpty()
                || isProgrammedCircuit(stack));
    }

    @Override
    public PrimordialOmegaVoidInductionArmatureLogic createRecipeLogic(Object... args) {
        return new PrimordialOmegaVoidInductionArmatureLogic(this);
    }

    @Override
    public PrimordialOmegaVoidInductionArmatureLogic getRecipeLogic() {
        return (PrimordialOmegaVoidInductionArmatureLogic) recipeLogic;
    }

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;  // 解除 ULV 配方限制
    }

    private void scanCircuits() {
        var directCircuit = circuitSlot.storage.getStackInSlot(0);
        if (isProgrammedCircuit(directCircuit)) {
            int directNumber = IntCircuitBehaviour.getCircuitConfiguration(directCircuit);
            if (directNumber > 0) {
                circuitNumber = directNumber;
                circuitFound = true;
                return;
            }
        }
        for (int i = 20; i >= 1; i--) {
            try {
                if (MachineIO.notConsumableCircuit(this, i)) {
                    circuitNumber = i;
                    circuitFound = true;
                    return;
                }
            } catch (ClassCastException e) {
                // gtlcore 1.2.2.9+ 改 API 导致样板总成 handler cast 失败，跳过本次扫描
                circuitNumber = 0;
                circuitFound = false;
                return;
            }
        }
        circuitNumber = 0;
        circuitFound = false;
    }

    private static boolean isProgrammedCircuit(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = ForgeRegistries.ITEMS.getValue(PROGRAMMED_CIRCUIT_ID);
        return item != null && item != Items.AIR && stack.is(item);
    }

    @Override
    protected NotifiableItemStackHandler[] getPersistedStorages() {
        return new NotifiableItemStackHandler[]{ circuitSlot };
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();
            var slot = new SlotWidget(circuitSlot.storage, 0, size.width - 52, size.height - 30, true, true);
            slot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            slot.setHoverTooltips(
                    Component.literal("§e§l编程电路槽"),
                    Component.literal("§7放入 GT 编程电路决定真空零点能并行"),
                    Component.literal("§7优先级高于结构仓室内的电路"));
            group.addWidget(slot);
        }
        return widget;
    }

    private void scanCircuitsSafe() {
        try { scanCircuits(); } catch (Exception ignored) {}
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        refreshTier();  // 重新计算电压等级，避免 ULV
        scanCircuitsSafe();
        circuitScanSubs = subscribeServerTick(circuitScanSubs, () -> {
            try {
                if (getOffsetTimer() % 3 == 0) scanCircuits();
            } catch (Exception ignored) {}
        });
    }

    @Override
    public int getTier() {
        return 9;  // GTValues.MAX ≈ UHV+，解除配方等级限制
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (circuitScanSubs != null) {
            circuitScanSubs.unsubscribe();
            circuitScanSubs = null;
        }
    }

    @Override
    public boolean onWorking() {
        boolean working = super.onWorking();
        if (!working || !circuitFound) return working;
        var host = getHost();
        UUID targetUuid = host != null && host.getUuid() != null ? host.getUuid() : getUuid();
        if (targetUuid == null) return working;

        try {
            BigInteger eu = getGeneratedEuPerTick();
            Class<?> mgr = Class.forName("com.hepdd.gtmthings.api.misc.WirelessEnergyManager");
            mgr.getMethod("addEUToGlobalEnergyMap", UUID.class, BigInteger.class, MetaMachine.class)
                .invoke(null, targetUuid, eu, this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return working;
    }

    /** 当前电路号（由 StartupUpdate 从编程电路自动读取） */
    public int getCircuitNumber() {
        return circuitNumber;
    }

    public boolean hasTargetWirelessGrid() {
        var host = getHost();
        return (host != null && host.getUuid() != null) || getUuid() != null;
    }

    public boolean canStartGeneratingRecipe() {
        // 编程电路决定并行；无电路时，暗能量倍增器可作为 1 号电路兜底
        boolean circuitOrFallback = circuitFound || hasDarkEnergyMultiplierMounted();
        return isFormed() && hasProxies() && canModuleWork() && circuitOrFallback;
    }

    /** 理论并行值（BigInteger），仅用于显示 */
    public BigInteger getTheoreticalParallel() {
        if (circuitNumber <= 0) return BigInteger.ZERO;
        int exponent = 128 * (1 << (circuitNumber - 1));
        return BigInteger.ONE.shiftLeft(exponent);
    }

    public BigInteger getGeneratedEuPerTick() {
        return getTheoreticalParallel().multiply(BigInteger.valueOf(EU_PER_THEORETICAL_PARALLEL));
    }

    public BigInteger getGeneratedEuPerCycle() {
        return getGeneratedEuPerTick().multiply(BigInteger.valueOf(ZERO_POINT_RECIPE_DURATION));
    }

    @Override
    public int getMaxParallel() {
        if (!circuitFound) {
            // 无编程电路：暗能量倍增器兜底 → 按 1 号电路计算
            return hasDarkEnergyMultiplierMounted() ? 1 : 0;
        }
        return getCircuitParallel();
    }

    private int getCircuitParallel() {
        if (circuitNumber <= 0) return 0;
        if (circuitNumber >= 16) return Integer.MAX_VALUE;
        return 1 << (circuitNumber - 1);
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        int parallel = getMaxParallel();
        boolean infinite = parallel >= Integer.MAX_VALUE;
        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(infinite
                        ? com.dishanhai.gt_shanhai.api.DShanhaiTextUtil.createUltimateRainbow("无限")
                        : Component.literal(String.format("%,d", parallel)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("个配方")));
        int threads = getAdditionalThread();
        if (threads > 0) {
            textList.add(Component.translatable("gtladditions.multiblock.threads",
                    Component.literal(String.format("%,d", threads)).withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addHostStatusDisplay(textList);
            if (!canModuleWork()) {
                textList.add(Component.translatable("gt_shanhai.machine.primordial_void_induction_armature.name")
                        .withStyle(ChatFormatting.GOLD));
                return;
            }
            if (!circuitFound) {
                if (hasDarkEnergyMultiplierMounted()) {
                    textList.add(Component.literal("§e暗能量倍增器兜底模式（等效 1 号电路）"));
                } else {
                    textList.add(Component.literal("§c需要插入编程电路或暗能量倍增器以产电"));
                }
            } else {
                textList.add(Component.translatable("gt_shanhai.machine.primordial_void_induction_armature.mode")
                        .withStyle(ChatFormatting.GREEN));
            }
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_void_induction_armature.name")
                .withStyle(ChatFormatting.GOLD));
    }
}

