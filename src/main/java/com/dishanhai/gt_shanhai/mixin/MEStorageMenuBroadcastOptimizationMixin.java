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

    protected MEStorageMenuBroadcastOptimizationMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Redirect(method = "m_38946_", at = @At(value = "INVOKE", target = "Lappeng/api/storage/MEStorage;getAvailableStacks()Lappeng/api/stacks/KeyCounter;"), remap = false)
    private KeyCounter gtShanhai$useCachedInventory(MEStorage storage) {
        IStorageService service = gtShanhai$getStorageService();
        if (service instanceof IStorageServiceRevisionAccess revisionAccess) {
            KeyCounter cachedInventory = service.getCachedInventory();
            if (cachedInventory != null) {
                this.gtShanhai$useRevisionOptimization = true;
                this.gtShanhai$currentAvailableStacks = cachedInventory;
                this.gtShanhai$currentInventoryRevision = revisionAccess.gtShanhai$getInventoryRevision();
                if (this.updateHelper.isFullUpdate()) {
                    this.gtShanhai$currentChangedKeys = cachedInventory.keySet();
                } else if (this.gtShanhai$currentInventoryRevision != this.gtShanhai$lastInventoryRevision) {
                    this.gtShanhai$currentChangedKeys = revisionAccess.gtShanhai$getLastChangedKeys();
                } else {
                    this.gtShanhai$currentChangedKeys = Collections.emptySet();
                }
                return cachedInventory;
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
        }
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
