package com.dishanhai.gt_shanhai.common.machine.misc;

import appeng.me.cluster.MBCalculator;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingBlockEntity;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPUCluster;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitBlock;
import com.dishanhai.gt_shanhai.common.block.DShanhaiAE2Blocks;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class QuantumComputerMachine extends MetaMachine
        implements IInteractedMachine, IFancyUIMachine {

    private long totalStorage;
    private long availableStorage;
    private int coprocessors;
    private int activeCpus;
    private String quantumStructureSize = "";
    private QuantumCraftingCPUCluster attachedCluster;
    private TickableSubscription refreshTickSub;
    private int refreshDelay;
    private boolean refreshingQuantumCluster;

    public QuantumComputerMachine(IMachineBlockEntity holder, Object... args) {
        super(holder);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        scheduleQuantumRefresh(1);
    }

    @Override
    public void onUnload() {
        super.onUnload();
        unsubscribeQuantumRefresh();
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return true;
    }

    public boolean isAttachedToQuantumCluster() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return attachedCluster != null;
        detectQuantumCluster();
        return attachedCluster != null;
    }

    @Override
    public Widget createUIWidget() {
        detectQuantumCluster();
        WidgetGroup root = new WidgetGroup(0, 0, 176, 80);
        root.setBackground(GuiTextures.BACKGROUND_INVERSE);
        root.addWidget(new LabelWidget(4, 4, "§d§l量子计算机Plus"));
        int y = 18;
        if (!isAttachedToQuantumCluster()) {
            root.addWidget(new LabelWidget(4, y, "§c未连接已成型的量子 CPU 池"));
            root.addWidget(new LabelWidget(4, y + 12, "§7外层为量子结构玻璃"));
            root.addWidget(new LabelWidget(4, y + 24, "§7核心/存储/处理器放在内部"));
        } else {
            root.addWidget(new LabelWidget(4, y, "§d量子结构: §f" + quantumStructureSize));
            root.addWidget(new LabelWidget(4, y + 12, Component.literal(
                    "§b量子小 CPU: §f" + activeCpus + " §7运行中 / §f" + coprocessors + " §7线程")));
            root.addWidget(new LabelWidget(4, y + 24, Component.literal(
                    "§a可用存储: §f" + formatLong(availableStorage) + " §7/ §f" + formatLong(totalStorage))));
        }
        return root;
    }

    public void addDisplayText(List<Component> textList) {
        if (!isAttachedToQuantumCluster()) {
            clearQuantumStats();
            return;
        }
        detectQuantumCluster();
        textList.add(Component.literal("§d量子结构: §f" + quantumStructureSize));
        textList.add(Component.literal("§b量子小 CPU: §f" + activeCpus + " §7运行中 / §f" + coprocessors + " §7线程"));
        textList.add(Component.literal("§a可用存储: §f" + formatLong(availableStorage) + " §7/ §f" + formatLong(totalStorage)));
    }

    public static MachineDefinition register() {
        MachineDefinition def = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "quantum_computer",
                MachineDefinition::createDefinition,
                QuantumComputerMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity)
            .rotationState(RotationState.NON_Y_AXIS)
            .renderer(() -> new WorkableCasingMachineRenderer(
                new ResourceLocation(MOD_ID, "block/crafting/quantum_structure"),
                new ResourceLocation(MOD_ID, "block/crafting/quantum_core")))
            .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(Component.literal("§d§l量子计算机Plus"));
            tips.add(Component.literal(""));
            tips.add(Component.literal("§aAE2 量子合成 CPU 池"));
            tips.add(Component.literal("§b材质参考 AdvancedAE，运行期不依赖 AdvancedAE"));
            tips.add(Component.literal("§a量子外壳本身可连接 AE 线缆传递频道"));
            tips.add(Component.literal("Long.MAX_VALUE存储量(9.22E18)"));
            tips.add(Component.literal("§e量子 CPU 池结构: §f最大 7x7x7，外壳包裹内部部件"));
            tips.add(Component.literal("§e外层结构玻璃，核心、存储、处理单元放在内部任意位置"));
            tips.add(Component.literal("§e单个量子并行处理单元: §f4096 线程"));
            tips.add(Component.literal("§e多个任务可拆分为多个小 CPU 并发执行"));
        });
        return def;
    }

    private void detectQuantumCluster() {
        QuantumCraftingCPUCluster cluster = attachedCluster;
        if (!isValidAttachedCluster(cluster)) {
            cluster = findAttachedQuantumCluster();
        }
        if (cluster == null) {
            clearQuantumStats();
            return;
        }
        attachedCluster = cluster;
        updateQuantumStats(cluster);
    }

    private void refreshQuantumCluster() {
        Level level = getLevel();
        if (!(level instanceof ServerLevel)) return;
        if (refreshingQuantumCluster || MBCalculator.isModificationInProgress()) return;
        refreshingQuantumCluster = true;
        try {
            BlockPos origin = getPos();
            for (Direction direction : Direction.values()) {
                BlockPos pos = origin.relative(direction);
                if (!level.isLoaded(pos)) continue;
                if (!(level.getBlockState(pos).getBlock() instanceof QuantumCraftingUnitBlock)) continue;
                BlockEntity rawBlockEntity = level.getBlockEntity(pos);
                if (rawBlockEntity instanceof QuantumCraftingBlockEntity blockEntity) {
                    blockEntity.updateMultiBlock(origin);
                    return;
                }
            }
        } finally {
            refreshingQuantumCluster = false;
        }
    }

    private void scheduleQuantumRefresh(int delay) {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;
        refreshDelay = Math.max(refreshDelay, delay);
        if (refreshTickSub == null) {
            refreshTickSub = subscribeServerTick(refreshTickSub, this::quantumRefreshTick);
        }
    }

    private void quantumRefreshTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) {
            unsubscribeQuantumRefresh();
            clearQuantumStats();
            return;
        }
        if (refreshDelay > 0) {
            refreshDelay--;
            return;
        }
        if (refreshingQuantumCluster || MBCalculator.isModificationInProgress()) return;
        refreshQuantumCluster();
        detectQuantumCluster();
        refreshDelay = 20;
    }

    private void unsubscribeQuantumRefresh() {
        if (refreshTickSub != null) {
            refreshTickSub.unsubscribe();
            refreshTickSub = null;
        }
        refreshDelay = 0;
    }

    private void clearQuantumStats() {
        totalStorage = 0;
        availableStorage = 0;
        coprocessors = 0;
        activeCpus = 0;
        quantumStructureSize = "";
    }

    private QuantumCraftingCPUCluster findAttachedQuantumCluster() {
        Level level = getLevel();
        if (level == null) return null;
        BlockPos origin = getPos();
        for (Direction direction : Direction.values()) {
            BlockPos pos = origin.relative(direction);
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockState(pos).getBlock() instanceof QuantumCraftingUnitBlock)) continue;
            BlockEntity rawBlockEntity = level.getBlockEntity(pos);
            if (!(rawBlockEntity instanceof QuantumCraftingBlockEntity)) continue;
            QuantumCraftingBlockEntity blockEntity = (QuantumCraftingBlockEntity) rawBlockEntity;
            if (blockEntity.getUnitBlock().getQuantumType() != com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes.STRUCTURE) {
                continue;
            }
            QuantumCraftingCPUCluster cluster = blockEntity.getCluster();
            if (isValidAttachedCluster(cluster)) {
                return cluster;
            }
        }
        return null;
    }

    private boolean isValidAttachedCluster(QuantumCraftingCPUCluster cluster) {
        if (cluster == null || cluster.isDestroyed() || cluster.getCoreBlockEntity() == null) return false;
        BlockPos pos = getPos();
        BlockPos min = cluster.getBoundsMin();
        BlockPos max = cluster.getBoundsMax();
        boolean xFace = (pos.getX() == min.getX() - 1 || pos.getX() == max.getX() + 1)
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        boolean yFace = (pos.getY() == min.getY() - 1 || pos.getY() == max.getY() + 1)
                && pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        boolean zFace = (pos.getZ() == min.getZ() - 1 || pos.getZ() == max.getZ() + 1)
                && pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY();
        return xFace || yFace || zFace;
    }

    private void updateQuantumStats(QuantumCraftingCPUCluster cluster) {
        totalStorage = cluster.getTotalStorage();
        availableStorage = cluster.getAvailableStorage();
        coprocessors = cluster.getCoProcessors();
        activeCpus = cluster.getActiveCpuCount();
        quantumStructureSize = cluster.getSizeX() + "x" + cluster.getSizeY() + "x" + cluster.getSizeZ();
    }

    private String formatLong(long value) {
        if (value == Long.MAX_VALUE) {
            return "∞";
        }
        return Long.toString(value);
    }
}
