package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.MEStorage;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.common.IncrementalUpdateHelper;
import appeng.menu.me.common.MEStorageMenu;

import com.dishanhai.gt_shanhai.api.ae2.IStorageServiceRevisionAccess;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Set;

@Mixin(value = MEStorageMenu.class, remap = false)
public abstract class MEStorageMenuBroadcastOptimizationMixin extends AEBaseMenu {

    @Shadow @Nullable protected MEStorage storage;
    @Shadow @Nullable private IGridNode networkNode;
    @Shadow @Final private ITerminalHost host;
    @Shadow @Final private IncrementalUpdateHelper updateHelper;
    @Shadow private KeyCounter previousAvailableStacks;

    @Unique
    private long gtShanhai$lastInventoryRevision = Long.MIN_VALUE;

    @Unique
    private long gtShanhai$currentInventoryRevision = Long.MIN_VALUE;

    @Unique
    private boolean gtShanhai$useRevisionOptimization = false;

    @Unique
    private KeyCounter gtShanhai$currentAvailableStacks;

    @Unique
    private Set<AEKey> gtShanhai$currentChangedKeys = Collections.emptySet();

    /** revision 跳号回退原版全量 diff 时，待本轮 diff 完成后重新对齐的目标 revision */
    @Unique
    private long gtShanhai$pendingResyncRevision = Long.MIN_VALUE;

    protected MEStorageMenuBroadcastOptimizationMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Redirect(method = "m_38946_", at = @At(value = "INVOKE", target = "Lappeng/api/storage/MEStorage;getAvailableStacks()Lappeng/api/stacks/KeyCounter;"), remap = false)
    private KeyCounter gtShanhai$useCachedInventory(MEStorage storage) {
        IStorageService service = gtShanhai$getStorageService();
        // storage 身份断言：ME 储存箱等 host 接电网但 getInventory() 是单颗元件而非网络存储，
        // 用网络级缓存顶替会让单元件 GUI 显示整个网络的内容（含第三方终端变体同险）
        if (service instanceof IStorageServiceRevisionAccess revisionAccess
                && storage == service.getInventory()) {
            KeyCounter cachedInventory = service.getCachedInventory();
            if (cachedInventory != null) {
                long revision = revisionAccess.gtShanhai$getInventoryRevision();
                if (this.updateHelper.isFullUpdate()) {
                    this.gtShanhai$useRevisionOptimization = true;
                    this.gtShanhai$currentAvailableStacks = cachedInventory;
                    this.gtShanhai$currentInventoryRevision = revision;
                    this.gtShanhai$currentChangedKeys = cachedInventory.keySet();
                    return cachedInventory;
                }
                if (revision == this.gtShanhai$lastInventoryRevision) {
                    this.gtShanhai$useRevisionOptimization = true;
                    this.gtShanhai$currentAvailableStacks = cachedInventory;
                    this.gtShanhai$currentInventoryRevision = revision;
                    this.gtShanhai$currentChangedKeys = Collections.emptySet();
                    return cachedInventory;
                }
                if (revision - this.gtShanhai$lastInventoryRevision == 1) {
                    this.gtShanhai$useRevisionOptimization = true;
                    this.gtShanhai$currentAvailableStacks = cachedInventory;
                    this.gtShanhai$currentInventoryRevision = revision;
                    this.gtShanhai$currentChangedKeys = revisionAccess.gtShanhai$getLastChangedKeys();
                    return cachedInventory;
                }
                // revision 跳号：lastChangedKeys 是单槽信箱，一 tick 多次重扫时中间变更集已被
                // 覆写丢弃，只应用最后一份会让终端数量静默失同步。本轮退回原版全量 diff 重新
                // 对齐，diff 完成后在 TAIL 把 lastInventoryRevision 推进到当前值恢复增量模式。
                this.gtShanhai$pendingResyncRevision = revision;
            }
        }

        this.gtShanhai$useRevisionOptimization = false;
        this.gtShanhai$currentAvailableStacks = storage.getAvailableStacks();
        this.gtShanhai$currentChangedKeys = Collections.emptySet();
        return this.gtShanhai$currentAvailableStacks;
    }

    @Redirect(method = "m_38946_", at = @At(value = "INVOKE", target = "Lappeng/api/stacks/KeyCounter;removeAll(Lappeng/api/stacks/KeyCounter;)V"), remap = false)
    private void gtShanhai$skipFullDiffWhenRevisionUnchanged(KeyCounter previous, KeyCounter available) {
        if (!this.gtShanhai$useRevisionOptimization) {
            previous.removeAll(available);
        }
    }

    @Redirect(method = "m_38946_", at = @At(value = "INVOKE", target = "Lappeng/api/stacks/KeyCounter;removeZeros()V"), remap = false)
    private void gtShanhai$skipZeroSweepWhenRevisionUnchanged(KeyCounter counter) {
        if (!this.gtShanhai$useRevisionOptimization) {
            counter.removeZeros();
        }
    }

    @Redirect(method = "m_38946_", at = @At(value = "INVOKE", target = "Lappeng/api/stacks/KeyCounter;keySet()Ljava/util/Set;"), remap = false)
    private Set<AEKey> gtShanhai$useChangedKeySet(KeyCounter counter) {
        if (!this.gtShanhai$useRevisionOptimization) {
            return counter.keySet();
        }
        return this.gtShanhai$currentChangedKeys;
    }

    @Inject(method = "m_38946_", at = @At("TAIL"), remap = false)
    private void gtShanhai$rememberRevisionAfterBroadcast(CallbackInfo ci) {
        if (!isServerSide()) {
            return;
        }
        if (this.gtShanhai$useRevisionOptimization) {
            this.gtShanhai$lastInventoryRevision = this.gtShanhai$currentInventoryRevision;
            this.previousAvailableStacks = new KeyCounter();
        } else if (this.gtShanhai$pendingResyncRevision != Long.MIN_VALUE) {
            // 跳号回退的原版全量 diff 已把客户端完整对齐，可安全推进 revision 恢复增量模式
            this.gtShanhai$lastInventoryRevision = this.gtShanhai$pendingResyncRevision;
        }
        this.gtShanhai$pendingResyncRevision = Long.MIN_VALUE;
        this.gtShanhai$useRevisionOptimization = false;
        this.gtShanhai$currentAvailableStacks = null;
        this.gtShanhai$currentChangedKeys = Collections.emptySet();
    }

    @Unique
    @Nullable
    private IStorageService gtShanhai$getStorageService() {
        IGridNode node = this.networkNode;
        if (node == null && this.host instanceof IActionHost actionHost) {
            node = actionHost.getActionableNode();
        }
        if (node == null) return null;
        var grid = node.getGrid();
        if (grid == null) return null;
        return grid.getStorageService();
    }
}
