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

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.Collections;
import java.util.LinkedHashSet;
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

    /**
     * 客户端上次看到的数量。GTLCore 的 TerminalInventoryChanges.between 读的是
     * previousAvailableStacks，而且它的结果会盖过我们自己的差异集。
     * 这里若换成空计数器，整格取空的 key 两边都找不到，终端就一直显示。
     * 也不能直接指到网络活缓存，否则下一轮 removeAll 会改掉网络库存。
     */
    @Unique
    private final KeyCounter gtShanhai$broadcastBaseline = new KeyCounter();

    @Unique
    private KeyCounter gtShanhai$sentSnapshot = this.gtShanhai$broadcastBaseline;

    @Unique
    private boolean gtShanhai$sentSnapshotReady = false;

    /** revision 没变时缓存应与基线一致，这一轮不要用可能陈旧的缓存覆盖基线。 */
    @Unique
    private boolean gtShanhai$keepBroadcastBaseline = false;

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
                if (!this.gtShanhai$sentSnapshotReady || this.updateHelper.isFullUpdate()) {
                    // 第一次（或客户端要求全量）必须走原版：空 previous.removeAll 会把现有 key
                    // 全部标成变更，配合 fullUpdate 下发完整库存。此时还没有可对比的已发送快照。
                    this.gtShanhai$useRevisionOptimization = false;
                    this.gtShanhai$pendingResyncRevision = revision;
                    this.gtShanhai$currentAvailableStacks = cachedInventory;
                    return cachedInventory;
                }
                if (revision == this.gtShanhai$lastInventoryRevision) {
                    this.gtShanhai$useRevisionOptimization = true;
                    this.gtShanhai$keepBroadcastBaseline = true;
                    this.gtShanhai$currentAvailableStacks = cachedInventory;
                    this.gtShanhai$currentInventoryRevision = revision;
                    this.gtShanhai$currentChangedKeys = Collections.emptySet();
                    return cachedInventory;
                }
                this.gtShanhai$keepBroadcastBaseline = false;
                // 不走 lastChangedKeys 单槽信箱。revision 跳号或空发布会把「数量变成 0」的删除丢掉，
                // 玩家一次取完整格后终端仍显示该物品。用上次下发的快照和当前缓存做差，归零也算变更。
                this.gtShanhai$useRevisionOptimization = true;
                this.gtShanhai$currentAvailableStacks = cachedInventory;
                this.gtShanhai$currentInventoryRevision = revision;
                this.gtShanhai$currentChangedKeys = gtShanhai$diffSnapshot(this.gtShanhai$sentSnapshot, cachedInventory);
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
            if (this.gtShanhai$keepBroadcastBaseline) {
                gtShanhai$pointPreviousAtBaseline();
            } else {
                gtShanhai$captureBaseline(this.gtShanhai$currentAvailableStacks);
            }
        } else if (this.gtShanhai$pendingResyncRevision != Long.MIN_VALUE) {
            // 原版全量 diff 已把客户端对齐。记下这份快照，之后的增量才能发现整格取空。
            this.gtShanhai$lastInventoryRevision = this.gtShanhai$pendingResyncRevision;
            gtShanhai$captureBaseline(this.gtShanhai$currentAvailableStacks);
        }
        this.gtShanhai$pendingResyncRevision = Long.MIN_VALUE;
        this.gtShanhai$useRevisionOptimization = false;
        this.gtShanhai$keepBroadcastBaseline = false;
        this.gtShanhai$currentAvailableStacks = null;
        this.gtShanhai$currentChangedKeys = Collections.emptySet();
    }

    @Unique
    private void gtShanhai$pointPreviousAtBaseline() {
        this.previousAvailableStacks = this.gtShanhai$broadcastBaseline;
        this.gtShanhai$sentSnapshot = this.gtShanhai$broadcastBaseline;
        this.gtShanhai$sentSnapshotReady = true;
    }

    /** 用这一轮真正拿去对比的库存覆盖基线。只保留数量大于 0 的 key。 */
    @Unique
    private void gtShanhai$captureBaseline(KeyCounter current) {
        this.gtShanhai$broadcastBaseline.clear();
        this.gtShanhai$broadcastBaseline.removeEmptySubmaps();
        if (current != null) {
            for (Object2LongMap.Entry<AEKey> entry : current) {
                long amount = entry.getLongValue();
                if (amount > 0L) {
                    this.gtShanhai$broadcastBaseline.set(entry.getKey(), amount);
                }
            }
        }
        gtShanhai$pointPreviousAtBaseline();
    }

    /** 数量变化、新出现、整格归零都要进变更集。归零的 key 不在当前缓存里，必须从旧快照侧找。 */
    @Unique
    private static Set<AEKey> gtShanhai$diffSnapshot(KeyCounter sent, KeyCounter current) {
        LinkedHashSet<AEKey> changes = new LinkedHashSet<>();
        if (sent != null) {
            for (Object2LongMap.Entry<AEKey> entry : sent) {
                AEKey key = entry.getKey();
                long now = current == null ? 0L : current.get(key);
                if (now != entry.getLongValue()) changes.add(key);
            }
        }
        if (current != null) {
            for (Object2LongMap.Entry<AEKey> entry : current) {
                AEKey key = entry.getKey();
                long before = sent == null ? 0L : sent.get(key);
                if (entry.getLongValue() != before) changes.add(key);
            }
        }
        return changes;
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
