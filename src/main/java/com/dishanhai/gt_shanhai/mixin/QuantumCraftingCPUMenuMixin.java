package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.sync.packets.CraftingStatusPacket;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.AEBaseMenu;
import appeng.menu.guisync.GuiSync;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.dishanhai.gt_shanhai.common.ae2.GraphCraftingRedispatch;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingBlockEntity;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPU;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPUCluster;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPULogic;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingRedispatchMenu;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

@Mixin(value = CraftingCPUMenu.class, remap = false)
public abstract class QuantumCraftingCPUMenuMixin extends AEBaseMenu implements QuantumCraftingRedispatchMenu {

    @Shadow @Final private IncrementalUpdateHelper incrementalUpdateHelper;
    @Shadow @Final private IGrid grid;
    @Shadow @Final private Consumer<AEKey> cpuChangeListener;
    @Shadow private CraftingCPUCluster cpu;
    @Shadow public CpuSelectionMode schedulingMode;
    @Shadow public boolean cantStoreItems;

    @Unique
    private QuantumCraftingCPU gtShanhai$quantumCpu;

    @Unique
    private static final int gtShanhai$NO_REDISPATCH_CPU = Integer.MIN_VALUE;

    @Unique
    @GuiSync(110)
    private int gtShanhai$redispatchSerial = gtShanhai$NO_REDISPATCH_CPU;

    @Unique
    private static final long gtShanhai$QUANTUM_PROGRESS_SYNC_INTERVAL = 5L;

    @Unique
    private long gtShanhai$lastQuantumProgressSyncTick = Long.MIN_VALUE;

    public QuantumCraftingCPUMenuMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Shadow
    protected abstract void setCPU(ICraftingCPU cpu);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gtShanhai$initQuantumCpu(MenuType<?> menuType, int id, Inventory inventory, Object host, CallbackInfo ci) {
        registerClientAction("gtShanhaiRedispatch", Integer.class, this::gtShanhai$handleRedispatch);
        if ((Object) this instanceof CraftingStatusMenuAccessor) {
            registerClientAction("gtShanhaiCancelSelectedCpu", Integer.class, this::gtShanhai$cancelSelectedCpu);
        }
        if (host instanceof QuantumCraftingBlockEntity) {
            QuantumCraftingCPUCluster cluster = ((QuantumCraftingBlockEntity) host).getCluster();
            if (cluster == null) return;
            List<QuantumCraftingCPU> active = cluster.getActiveCPUs();
            if (active.isEmpty()) {
                setCPU(cluster.getRemainingCapacityCPU());
            } else {
                setCPU(active.get(0));
            }
        }
    }

    @Override
    @Unique
    public boolean gtShanhai$canRedispatch() {
        return gtShanhai$redispatchSerial != gtShanhai$NO_REDISPATCH_CPU
                && gtShanhai$redispatchSerial == gtShanhai$currentRedispatchSerial();
    }

    @Override
    @Unique
    public void gtShanhai$redispatch() {
        if (isClientSide() && gtShanhai$canRedispatch()) {
            sendClientAction("gtShanhaiRedispatch", gtShanhai$redispatchSerial);
        }
    }

    @Unique
    private int gtShanhai$currentRedispatchSerial() {
        return (Object) this instanceof CraftingStatusMenuAccessor accessor
                ? accessor.gtShanhai$getSelectedCpuSerialRaw() : -1;
    }

    @Unique
    private GraphCraftingRedispatch gtShanhai$resolveNativeGraphCpu() {
        ICraftingCPU selected = (Object) this instanceof CraftingStatusMenuAccessor accessor
                ? accessor.gtShanhai$getSelectedCpuRaw() : cpu;
        if (!(selected instanceof CraftingCPUCluster cluster) || grid == null || cluster.getGrid() != grid
                || cluster.isDestroyed() || !cluster.isActive() || !cluster.isBusy()) {
            return null;
        }
        return GraphCraftingRedispatch.forNativeCpu(cluster);
    }

    @Unique
    private void gtShanhai$handleRedispatch(int serial) {
        if (isClientSide()) return;
        QuantumCraftingCPU quantumCpu = gtShanhai$resolveExplicitSelectedQuantumCpu();
        GraphCraftingRedispatch graph = gtShanhai$resolveNativeGraphCpu();
        if (serial == gtShanhai$NO_REDISPATCH_CPU || serial != gtShanhai$currentRedispatchSerial()
                || (Object) this instanceof CraftingStatusMenuAccessor && serial < 0
                || (quantumCpu == null || !quantumCpu.isActive()) && graph == null) {
            getPlayer().displayClientMessage(Component.translatable("gt_shanhai.quantum_cpu.redispatch.no_selection")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        if (graph != null) {
            getPlayer().displayClientMessage(graph.gtShanhai$requestRedispatch()
                    ? Component.translatable("gt_shanhai.quantum_cpu.redispatch.graph_queued")
                    : Component.translatable("gt_shanhai.quantum_cpu.redispatch.graph_unavailable"), false);
            return;
        }
        QuantumCraftingCPULogic.RedispatchResult result = quantumCpu.craftingLogic.retryRemainingDispatch();
        Component message = switch (result.state()) {
            case GRAPH_QUEUED -> Component.translatable("gt_shanhai.quantum_cpu.redispatch.graph_queued");
            case GRAPH_UNAVAILABLE -> Component.translatable("gt_shanhai.quantum_cpu.redispatch.graph_unavailable");
            case NO_JOB -> Component.translatable("gt_shanhai.quantum_cpu.redispatch.no_job")
                    .withStyle(ChatFormatting.YELLOW);
            case GRID_OFFLINE -> Component.translatable("gt_shanhai.quantum_cpu.redispatch.grid_offline")
                    .withStyle(ChatFormatting.RED);
            case MATERIAL_UNAVAILABLE -> Component.translatable("gt_shanhai.quantum_cpu.redispatch.material_unavailable",
                    result.missingKinds()).withStyle(ChatFormatting.RED);
            case RETRIGGERED -> Component.translatable("gt_shanhai.quantum_cpu.redispatch.retriggered")
                    .withStyle(ChatFormatting.GREEN);
            case REFILLED -> Component.translatable("gt_shanhai.quantum_cpu.redispatch.refilled",
                    result.extractedKinds(), result.missingKinds()).withStyle(ChatFormatting.GREEN);
        };
        getPlayer().displayClientMessage(message, false);
    }

    @Unique
    private QuantumCraftingCPU gtShanhai$resolveExplicitSelectedQuantumCpu() {
        QuantumCraftingCPU quantumCpu = gtShanhai$quantumCpu;
        if (quantumCpu == null || !quantumCpu.isBusy() || !quantumCpu.getCluster().belongsToGrid(grid)) {
            return null;
        }
        if ((Object) this instanceof CraftingStatusMenuAccessor accessor
                && accessor.gtShanhai$getSelectedCpuRaw() != quantumCpu) {
            return null;
        }
        return quantumCpu;
    }

    @WrapMethod(method = "setCPU")
    private void gtShanhai$setQuantumCpu(ICraftingCPU selectedCpu, Operation<Void> original) {
        boolean hadQuantum = gtShanhai$quantumCpu != null;
        if (hadQuantum) {
            gtShanhai$quantumCpu.craftingLogic.removeListener(cpuChangeListener);
            gtShanhai$quantumCpu = null;
            gtShanhai$lastQuantumProgressSyncTick = Long.MIN_VALUE;
            incrementalUpdateHelper.reset();
        }
        if (!(selectedCpu instanceof QuantumCraftingCPU quantumCpu)) {
            original.call(selectedCpu);
            if (hadQuantum && selectedCpu == null && isServerSide()) {
                sendPacketToClient(new CraftingStatusPacket(containerId, CraftingStatus.EMPTY));
            }
            return;
        }

        // AE2 and GTLCore each own a CPU reference and listener in the wrapped method.
        original.call((ICraftingCPU) null);
        if (!quantumCpu.isBusy() || !quantumCpu.getCluster().belongsToGrid(grid)) {
            if (isServerSide()) {
                sendPacketToClient(new CraftingStatusPacket(containerId, CraftingStatus.EMPTY));
            }
            return;
        }
        incrementalUpdateHelper.reset();
        gtShanhai$quantumCpu = quantumCpu;
        gtShanhai$markAllQuantumItemsChanged();
        quantumCpu.craftingLogic.addListener(cpuChangeListener);
        gtShanhai$sendQuantumStatus("setCPU", true);
    }

    @WrapMethod(method = "cancelCrafting")
    private void gtShanhai$cancelQuantumCrafting(Operation<Void> original) {
        if ((Object) this instanceof CraftingStatusMenuAccessor accessor) {
            if (isClientSide()) {
                sendClientAction("gtShanhaiCancelSelectedCpu", accessor.gtShanhai$getSelectedCpuSerialRaw());
            }
            return;
        }
        if (isServerSide() && gtShanhai$quantumCpu != null) {
            QuantumCraftingCPU target = gtShanhai$resolveExplicitSelectedQuantumCpu();
            if (target != null) {
                target.cancelJob();
            }
            return;
        }
        original.call();
    }

    @Unique
    private void gtShanhai$cancelSelectedCpu(int serial) {
        if (isClientSide() || serial < 0 || grid == null) {
            return;
        }
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        if (serial != accessor.gtShanhai$getSelectedCpuSerialRaw()) {
            return;
        }
        ICraftingCPU selected = accessor.gtShanhai$getSelectedCpuRaw();
        if (selected != null && selected.isBusy() && grid.getCraftingService().getCpus().contains(selected)) {
            selected.cancelJob();
        }
    }

    // removed(Player) = AbstractContainerMenu.m_6877_
    @Inject(method = "m_6877_", at = @At("TAIL"), remap = false, require = 0)
    private void gtShanhai$removed(Player player, CallbackInfo ci) {
        if (gtShanhai$quantumCpu != null) {
            gtShanhai$quantumCpu.craftingLogic.removeListener(cpuChangeListener);
        }
    }

    // 本项目无 refmap，对 MC 继承方法必须用 SRG 名 + remap=false（同 WobbleFontMixin）。
    // broadcastChanges = AbstractContainerMenu.m_38946_
    @Inject(method = "m_38946_", at = @At("HEAD"), remap = false, require = 0)
    private void gtShanhai$broadcastQuantumChanges(CallbackInfo ci) {
        if (isServerSide()) {
            int serial = gtShanhai$currentRedispatchSerial();
            QuantumCraftingCPU selected = gtShanhai$resolveExplicitSelectedQuantumCpu();
            boolean available = (selected != null && selected.isActive()) || gtShanhai$resolveNativeGraphCpu() != null;
            gtShanhai$redispatchSerial = available ? serial : gtShanhai$NO_REDISPATCH_CPU;
        }
        if (isServerSide() && gtShanhai$quantumCpu != null) {
            if (gtShanhai$resolveExplicitSelectedQuantumCpu() == null) {
                setCPU(null);
                return;
            }
            schedulingMode = gtShanhai$quantumCpu.getSelectionMode();
            cantStoreItems = gtShanhai$quantumCpu.craftingLogic.isCantStoreItems();
            if (incrementalUpdateHelper.hasChanges()) {
                gtShanhai$sendQuantumStatus("broadcast", false);
            } else if (gtShanhai$shouldSyncQuantumProgress()) {
                gtShanhai$markAllQuantumItemsChanged();
                gtShanhai$sendQuantumStatus("progress", true);
            }
        }
    }

    @Unique
    private void gtShanhai$sendQuantumStatus(String reason, boolean forceProgressOnly) {
        boolean hasChanges = incrementalUpdateHelper.hasChanges();
        if (!isServerSide() || gtShanhai$quantumCpu == null || (!hasChanges && !forceProgressOnly)) {
            return;
        }
        CraftingStatus status = gtShanhai$createStatus(incrementalUpdateHelper, gtShanhai$quantumCpu.craftingLogic);
        if (hasChanges) {
            incrementalUpdateHelper.commitChanges();
        }
        gtShanhai$rememberQuantumProgressStatus();
        QuantumDiagnostics.hit("menu.sendQuantumStatus",
                "reason=" + reason
                        + " entries=" + status.getEntries().size()
                        + " remaining=" + status.getRemainingItemCount()
                        + " start=" + status.getStartItemCount()
                        + " busy=" + gtShanhai$quantumCpu.isBusy());
        sendPacketToClient(new CraftingStatusPacket(containerId, status));
    }

    @Unique
    private boolean gtShanhai$shouldSyncQuantumProgress() {
        if (gtShanhai$quantumCpu == null || !gtShanhai$quantumCpu.isBusy()) {
            return false;
        }
        Level level = gtShanhai$quantumCpu.getLevel();
        if (level == null) {
            return false;
        }
        long tick = level.getGameTime();
        return gtShanhai$lastQuantumProgressSyncTick == Long.MIN_VALUE
                || tick - gtShanhai$lastQuantumProgressSyncTick >= gtShanhai$QUANTUM_PROGRESS_SYNC_INTERVAL;
    }

    @Unique
    private void gtShanhai$rememberQuantumProgressStatus() {
        Level level = gtShanhai$quantumCpu.getLevel();
        gtShanhai$lastQuantumProgressSyncTick = level == null ? gtShanhai$lastQuantumProgressSyncTick : level.getGameTime();
    }

    @Unique
    private void gtShanhai$markAllQuantumItemsChanged() {
        if (gtShanhai$quantumCpu == null) {
            return;
        }
        KeyCounter allItems = new KeyCounter();
        gtShanhai$quantumCpu.craftingLogic.getAllItems(allItems);
        for (Object2LongMap.Entry<AEKey> entry : allItems) {
            incrementalUpdateHelper.addChange(entry.getKey());
        }
    }

    @Unique
    private static CraftingStatus gtShanhai$createStatus(IncrementalUpdateHelper changes, QuantumCraftingCPULogic logic) {
        boolean full = changes.isFullUpdate();
        ImmutableList.Builder<CraftingStatusEntry> entries = ImmutableList.builder();
        Iterator<AEKey> iterator = changes.iterator();
        while (iterator.hasNext()) {
            AEKey what = iterator.next();
            long storedCount = logic.getStored(what);
            long activeCount = logic.getWaitingFor(what);
            long pendingCount = logic.getPendingOutputs(what);
            AEKey sentStack = what;
            if (!full && changes.getSerial(what) != null) {
                sentStack = null;
            }
            CraftingStatusEntry entry = new CraftingStatusEntry(changes.getOrAssignSerial(what), sentStack,
                    storedCount, activeCount, pendingCount);
            entries.add(entry);
            if (entry.isDeleted()) {
                changes.removeSerial(what);
            }
        }
        return new CraftingStatus(full, logic.getElapsedTime(), logic.getRemainingItemCount(),
                logic.getStartItemCount(), entries.build());
    }

}
