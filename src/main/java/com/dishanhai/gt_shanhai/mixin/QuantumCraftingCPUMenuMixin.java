package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.core.sync.packets.CraftingStatusPacket;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingBlockEntity;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPU;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPUCluster;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPULogic;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingRedispatchMenu;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
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
    @Shadow private CraftingCPUCluster cpu;
    @Shadow @Final private Consumer<AEKey> cpuChangeListener;
    @Shadow public CpuSelectionMode schedulingMode;
    @Shadow public boolean cantStoreItems;

    @Unique
    private QuantumCraftingCPU gtShanhai$quantumCpu;

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
        registerClientAction("gtShanhaiRedispatch", this::gtShanhai$redispatch);
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
    public void gtShanhai$redispatch() {
        if (isClientSide()) {
            sendClientAction("gtShanhaiRedispatch");
            return;
        }
        Player player = getPlayer();
        QuantumCraftingCPU quantumCpu = gtShanhai$resolveExplicitSelectedQuantumCpu();
        if (quantumCpu == null) {
            player.displayClientMessage(Component.literal("§c[量子 CPU] 当前未选择运行中的量子 CPU"), false);
            return;
        }
        QuantumCraftingCPULogic.RedispatchResult result = quantumCpu.craftingLogic.retryRemainingDispatch();
        Component message = switch (result.state()) {
            case NO_JOB -> Component.literal("§e[量子 CPU] 当前没有待重发配任务");
            case GRID_OFFLINE -> Component.literal("§c[量子 CPU] AE 网络离线，无法补取原料");
            case MATERIAL_UNAVAILABLE -> Component.literal(
                    "§c[量子 CPU] 未从网络取到缺失原料；仍缺 " + result.missingKinds() + " 类");
            case RETRIGGERED -> Component.literal("§a[量子 CPU] 原料已在 CPU，已重新触发发配");
            case REFILLED -> Component.literal("§a[量子 CPU] 已补取 " + result.extractedKinds()
                    + "/" + result.missingKinds() + " 类原料，下一 tick 继续发配");
        };
        player.displayClientMessage(message, false);
    }

    @Unique
    private QuantumCraftingCPU gtShanhai$resolveExplicitSelectedQuantumCpu() {
        if ((Object) this instanceof CraftingStatusMenuAccessor accessor) {
            ICraftingCPU selected = accessor.gtShanhai$getSelectedCpuRaw();
            if (selected instanceof QuantumCraftingCPU quantumCpu && quantumCpu.isBusy()) {
                gtShanhai$quantumCpu = quantumCpu;
                return quantumCpu;
            }
            return null;
        }
        return gtShanhai$quantumCpu != null && gtShanhai$quantumCpu.isBusy() ? gtShanhai$quantumCpu : null;
    }

    @Inject(method = "setCPU", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$setQuantumCpu(ICraftingCPU selectedCpu, CallbackInfo ci) {
        if (selectedCpu == null && gtShanhai$tryKeepBusyQuantumCpu()) {
            ci.cancel();
            return;
        }
        if (gtShanhai$quantumCpu != null) {
            gtShanhai$quantumCpu.craftingLogic.removeListener(cpuChangeListener);
        }
        if (selectedCpu instanceof QuantumCraftingCPU) {
            QuantumCraftingCPU quantumCpu = (QuantumCraftingCPU) selectedCpu;
            if (!quantumCpu.isBusy() && gtShanhai$tryKeepBusyQuantumCpu()) {
                ci.cancel();
                return;
            }
            if (!quantumCpu.isBusy()) {
                gtShanhai$quantumCpu = null;
                return;
            }
            if (cpu != null) {
                cpu.craftingLogic.removeListener(cpuChangeListener);
            }
            incrementalUpdateHelper.reset();
            gtShanhai$quantumCpu = quantumCpu;
            gtShanhai$resetQuantumProgressSyncState();
            gtShanhai$syncStatusMenuSelectedCpu(selectedCpu);
            KeyCounter allItems = new KeyCounter();
            gtShanhai$quantumCpu.craftingLogic.getAllItems(allItems);
            int itemKinds = 0;
            for (Object2LongMap.Entry<AEKey> entry : allItems) {
                itemKinds++;
                incrementalUpdateHelper.addChange(entry.getKey());
            }
            gtShanhai$quantumCpu.craftingLogic.addListener(cpuChangeListener);
            QuantumDiagnostics.hit("menu.setQuantumCpu",
                    "busy=" + gtShanhai$quantumCpu.isBusy() + " items=" + itemKinds
                            + " storage=" + gtShanhai$quantumCpu.getAvailableStorage());
            gtShanhai$sendQuantumStatus("setCPU");
            ci.cancel();
            return;
        }
        gtShanhai$quantumCpu = null;
    }

    @Inject(method = "cancelCrafting", at = @At("TAIL"))
    private void gtShanhai$cancelQuantumCrafting(CallbackInfo ci) {
        if (isClientSide()) {
            return;
        }
        QuantumCraftingCPU target = gtShanhai$resolveSelectedQuantumCpu();
        if (target == null) {
            QuantumDiagnostics.hit("menu.cancelQuantum", "target=null cached=" + (gtShanhai$quantumCpu != null));
            return;
        }
        QuantumDiagnostics.hit("menu.cancelQuantum",
                "busy=" + target.isBusy() + " storage=" + target.getAvailableStorage());
        target.cancelJob();
    }

    @Inject(method = "selectCpu", at = @At("TAIL"))
    private void gtShanhai$afterSelectCpu(int serial, CallbackInfo ci) {
        if (isClientSide() || !((Object) this instanceof CraftingStatusMenuAccessor)) {
            return;
        }
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        ICraftingCPU selectedCpu = accessor.gtShanhai$getSelectedCpuRaw();
        if (selectedCpu instanceof QuantumCraftingCPU) {
            gtShanhai$quantumCpu = (QuantumCraftingCPU) selectedCpu;
            QuantumDiagnostics.hit("menu.selectQuantum",
                    "serial=" + serial + " busy=" + gtShanhai$quantumCpu.isBusy());
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
        gtShanhai$selectBusyQuantumCpuForStatusMenu();
        if (isServerSide() && gtShanhai$clearFinishedSelectedQuantumCpu()) {
            return;
        }
        if (isServerSide() && gtShanhai$quantumCpu != null && !gtShanhai$quantumCpu.isBusy()) {
            gtShanhai$clearFinishedQuantumCpu(gtShanhai$quantumCpu);
            return;
        }
        if (isServerSide() && gtShanhai$quantumCpu != null) {
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
    private void gtShanhai$sendQuantumStatus(String reason) {
        gtShanhai$sendQuantumStatus(reason, false);
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
        gtShanhai$rememberQuantumProgressStatus(status);
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
        if (tick - gtShanhai$lastQuantumProgressSyncTick < gtShanhai$QUANTUM_PROGRESS_SYNC_INTERVAL) {
            return false;
        }
        return true;
    }

    @Unique
    private void gtShanhai$rememberQuantumProgressStatus(CraftingStatus status) {
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
    private void gtShanhai$resetQuantumProgressSyncState() {
        gtShanhai$lastQuantumProgressSyncTick = Long.MIN_VALUE;
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
        return new CraftingStatus(full, logic.getElapsedTimeTracker().getElapsedTime(),
                logic.getElapsedTimeTracker().getRemainingItemCount(),
                logic.getElapsedTimeTracker().getStartItemCount(), entries.build());
    }

    @Unique
    private boolean gtShanhai$tryKeepBusyQuantumCpu() {
        if (!((Object) this instanceof CraftingStatusMenuAccessor)) {
            return false;
        }
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        QuantumCraftingCPU busyCpu = gtShanhai$findBusyQuantumCpu(accessor);
        if (busyCpu == null) {
            return false;
        }
        setCPU(busyCpu);
        QuantumDiagnostics.hit("menu.keepBusyQuantum",
                "serial=" + accessor.gtShanhai$getSelectedCpuSerialRaw());
        return true;
    }

    @Unique
    private QuantumCraftingCPU gtShanhai$resolveSelectedQuantumCpu() {
        if ((Object) this instanceof CraftingStatusMenuAccessor) {
            CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
            QuantumCraftingCPU selectedCpu = gtShanhai$resolveStatusMenuQuantumCpu(accessor);
            if (selectedCpu != null) {
                return selectedCpu;
            }
            QuantumCraftingCPU fallback = gtShanhai$findBusyQuantumCpu(accessor);
            if (fallback != null) {
                return fallback;
            }
        }
        if (gtShanhai$quantumCpu != null && gtShanhai$quantumCpu.isBusy()) {
            return gtShanhai$quantumCpu;
        }
        return null;
    }

    @Unique
    private void gtShanhai$selectBusyQuantumCpuForStatusMenu() {
        if (isClientSide() || !((Object) this instanceof CraftingStatusMenuAccessor)) {
            return;
        }
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        ICraftingCPU selectedCpu = accessor.gtShanhai$getSelectedCpuRaw();
        if (selectedCpu instanceof QuantumCraftingCPU && ((QuantumCraftingCPU) selectedCpu).isBusy()) {
            return;
        }
        QuantumCraftingCPU busyCpu = gtShanhai$findBusyQuantumCpu(accessor);
        if (busyCpu != null && busyCpu != selectedCpu) {
            setCPU(busyCpu);
            QuantumDiagnostics.hit("menu.autoSelectBusyQuantum",
                    "serial=" + accessor.gtShanhai$getSelectedCpuSerialRaw());
        }
    }

    @Unique
    private QuantumCraftingCPU gtShanhai$resolveStatusMenuQuantumCpu(CraftingStatusMenuAccessor accessor) {
        int selectedSerial = accessor.gtShanhai$getSelectedCpuSerialRaw();
        for (ICraftingCPU cpu : accessor.gtShanhai$getLastCpuSetRaw()) {
            Integer serial = accessor.gtShanhai$getCpuSerialMapRaw().get(cpu);
            if (serial != null && serial.intValue() == selectedSerial && cpu instanceof QuantumCraftingCPU) {
                QuantumCraftingCPU quantumCpu = (QuantumCraftingCPU) cpu;
                gtShanhai$quantumCpu = quantumCpu;
                return quantumCpu.isBusy() ? quantumCpu : null;
            }
        }
        return null;
    }

    @Unique
    private QuantumCraftingCPU gtShanhai$findBusyQuantumCpu(CraftingStatusMenuAccessor accessor) {
        for (ICraftingCPU cpu : accessor.gtShanhai$getLastCpuSetRaw()) {
            if (cpu instanceof QuantumCraftingCPU) {
                QuantumCraftingCPU quantumCpu = (QuantumCraftingCPU) cpu;
                if (quantumCpu.isBusy()) {
                    gtShanhai$quantumCpu = quantumCpu;
                    accessor.gtShanhai$setSelectedCpuRaw(cpu);
                    accessor.gtShanhai$setSelectedCpuSerialRaw(accessor.gtShanhai$callGetOrAssignCpuSerial(cpu));
                    QuantumDiagnostics.hit("menu.resolveFallbackQuantum",
                            "serial=" + accessor.gtShanhai$getSelectedCpuSerialRaw());
                    return quantumCpu;
                }
            }
        }
        return null;
    }

    @Unique
    private boolean gtShanhai$clearFinishedSelectedQuantumCpu() {
        if (!((Object) this instanceof CraftingStatusMenuAccessor)) {
            return false;
        }
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        ICraftingCPU selectedCpu = accessor.gtShanhai$getSelectedCpuRaw();
        if (!(selectedCpu instanceof QuantumCraftingCPU)) {
            return false;
        }
        QuantumCraftingCPU quantumCpu = (QuantumCraftingCPU) selectedCpu;
        if (quantumCpu.isBusy()) {
            return false;
        }
        QuantumCraftingCPU replacement = gtShanhai$findBusyQuantumCpuExcept(accessor, quantumCpu);
        if (replacement != null) {
            gtShanhai$clearFinishedQuantumCpu(quantumCpu);
            setCPU(replacement);
            return true;
        }
        gtShanhai$clearFinishedQuantumCpu(quantumCpu);
        return true;
    }

    @Unique
    private boolean gtShanhai$hasBusyQuantumCpu(CraftingStatusMenuAccessor accessor) {
        for (ICraftingCPU cpu : accessor.gtShanhai$getLastCpuSetRaw()) {
            if (cpu instanceof QuantumCraftingCPU && cpu.isBusy()) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private QuantumCraftingCPU gtShanhai$findBusyQuantumCpuExcept(CraftingStatusMenuAccessor accessor, QuantumCraftingCPU excluded) {
        for (ICraftingCPU cpu : accessor.gtShanhai$getLastCpuSetRaw()) {
            if (cpu instanceof QuantumCraftingCPU && cpu != excluded && cpu.isBusy()) {
                return (QuantumCraftingCPU) cpu;
            }
        }
        return null;
    }

    @Unique
    private void gtShanhai$clearFinishedQuantumCpu(QuantumCraftingCPU oldCpu) {
        if (oldCpu == null) {
            return;
        }
        oldCpu.craftingLogic.removeListener(cpuChangeListener);
        if (gtShanhai$quantumCpu == oldCpu) {
            gtShanhai$quantumCpu = null;
        }
        gtShanhai$resetQuantumProgressSyncState();
        incrementalUpdateHelper.reset();
        if ((Object) this instanceof CraftingStatusMenuAccessor) {
            CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
            accessor.gtShanhai$setSelectedCpuRaw(null);
            accessor.gtShanhai$setSelectedCpuSerialRaw(-1);
        }
        sendPacketToClient(new CraftingStatusPacket(containerId, CraftingStatus.EMPTY));
        QuantumDiagnostics.hit("menu.clearFinishedQuantum",
                "storage=" + oldCpu.getAvailableStorage());
    }

    @Unique
    private void gtShanhai$syncStatusMenuSelectedCpu(ICraftingCPU selectedCpu) {
        if (!((Object) this instanceof CraftingStatusMenuAccessor)) {
            return;
        }
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        accessor.gtShanhai$setSelectedCpuRaw(selectedCpu);
        accessor.gtShanhai$setSelectedCpuSerialRaw(accessor.gtShanhai$callGetOrAssignCpuSerial(selectedCpu));
    }
}
