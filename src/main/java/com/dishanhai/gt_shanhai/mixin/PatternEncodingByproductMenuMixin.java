package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.storage.ITerminalHost;
import appeng.core.definitions.AEItems;
import appeng.helpers.IPatternTerminalMenuHost;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;

import com.dishanhai.gt_shanhai.common.ae2.ByproductOrderMenu;
import com.dishanhai.gt_shanhai.common.ae2.PatternByproductOrder;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadSelectionMenu;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadService;
import org.gtlcore.gtlcore.integration.ae2.wireless.WirelessAePackets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 样板终端的副产物标识，和一条独立的带标识上传。
 * 不改 GTLCore 的 encode() 与 gTLCore$quickUploadPattern 本体。
 * 只在对方开始普通快速上传时清掉我们自己的待选目标，避免选目标时串线。
 */
@Mixin(value = PatternEncodingTermMenu.class, priority = 1100, remap = false)
public abstract class PatternEncodingByproductMenuMixin extends MEStorageMenu implements ByproductOrderMenu {

    @Shadow(remap = false)
    @Final
    private RestrictedInputSlot blankPatternSlot;

    @Shadow(remap = false)
    @Final
    private RestrictedInputSlot encodedPatternSlot;

    @Shadow(remap = false)
    protected abstract ItemStack encodePattern();

    @Unique
    private List<PatternQuickUploadService.Target> gtShanhai$pendingTargets = List.of();

    @Unique
    private ItemStack gtShanhai$pendingPattern = ItemStack.EMPTY;

    @Unique
    private boolean gtShanhai$markByproductOnEncode;

    protected PatternEncodingByproductMenuMixin(MenuType<?> menuType, int id, Inventory ip, ITerminalHost host) {
        super(menuType, id, ip, host);
    }

    @Inject(method = "<init>(Lnet/minecraft/world/inventory/MenuType;ILnet/minecraft/world/entity/player/Inventory;Lappeng/helpers/IPatternTerminalMenuHost;Z)V",
            at = @At("TAIL"), remap = false)
    private void gtShanhai$registerByproductActions(MenuType<?> menuType, int id, Inventory ip,
            IPatternTerminalMenuHost host, boolean bindInventory, CallbackInfo ci) {
        registerClientAction("gtShanhaiToggleByproductOrder", this::gtShanhai$toggleByproductOrder);
        registerClientAction("gtShanhaiUploadByproductPattern", this::gtShanhai$uploadByproductPattern);
        registerClientAction("gtShanhaiSetMarkByproductOnEncode", Boolean.class,
                this::gtShanhai$setMarkByproductOnEncode);
    }

    @Inject(method = "encodeProcessingPattern", at = @At("RETURN"), remap = false, require = 0)
    private void gtShanhai$markEncodedByproduct(CallbackInfoReturnable<ItemStack> cir) {
        if (!this.gtShanhai$markByproductOnEncode || !gtShanhai$fromEncodeButton()) return;
        ItemStack encoded = cir.getReturnValue();
        if (encoded == null || encoded.isEmpty()) return;
        PatternByproductOrder.setMarked(encoded, true);
    }

    @Unique
    private static boolean gtShanhai$fromEncodeButton() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String method = frame.getMethodName();
            if (method == null) continue;
            if (method.contains("quickUpload") || method.contains("QuickUpload")
                    || method.contains("prepareMarkedPattern") || method.contains("uploadByproduct")) {
                return false;
            }
            if ("encode".equals(method)) return true;
        }
        return false;
    }

    @Inject(method = "gTLCore$quickUploadPattern", at = @At("HEAD"), remap = false, require = 0)
    private void gtShanhai$dropPendingByproductUpload(CallbackInfo ci) {
        this.gtShanhai$pendingTargets = List.of();
        this.gtShanhai$pendingPattern = ItemStack.EMPTY;
    }

    @Inject(method = "gTLCore$selectQuickUploadTarget", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void gtShanhai$selectByproductUploadTarget(int index, CallbackInfo ci) {
        if (this.gtShanhai$pendingTargets.isEmpty()) return;
        ci.cancel();
        if (isClientSide()) return;
        if (!(getPlayer() instanceof ServerPlayer player)) {
            gtShanhai$clearPendingUpload();
            return;
        }
        if (index < 0 || index >= this.gtShanhai$pendingTargets.size()) {
            gtShanhai$clearPendingUpload();
            return;
        }
        PatternQuickUploadService.Target target = this.gtShanhai$pendingTargets.get(index);
        ItemStack pattern = this.gtShanhai$pendingPattern;
        gtShanhai$clearPendingUpload();
        gtShanhai$insertMarkedPattern(player, pattern, target);
    }

    @Override
    public void gtShanhai$setMarkByproductOnEncode(Boolean enabled) {
        if (isClientSide()) {
            sendClientAction("gtShanhaiSetMarkByproductOnEncode", enabled);
            return;
        }
        this.gtShanhai$markByproductOnEncode = Boolean.TRUE.equals(enabled);
    }

    @Override
    public void gtShanhai$toggleByproductOrder() {
        if (isClientSide()) {
            sendClientAction("gtShanhaiToggleByproductOrder");
            return;
        }
        if (!(getPlayer() instanceof ServerPlayer player)) return;
        ItemStack stack = this.encodedPatternSlot.getItem();
        if (!PatternDetailsHelper.isEncodedPattern(stack)) {
            player.displayClientMessage(Component.translatable(
                    "gt_shanhai.message.pattern_byproduct_order.need_encoded"), true);
            return;
        }
        boolean marked = !PatternByproductOrder.isMarked(stack);
        PatternByproductOrder.setMarked(stack, marked);
        this.encodedPatternSlot.set(stack);
        player.displayClientMessage(Component.translatable(marked
                ? "gt_shanhai.message.pattern_byproduct_order.on"
                : "gt_shanhai.message.pattern_byproduct_order.off"), true);
    }

    @Override
    public void gtShanhai$uploadByproductPattern() {
        if (isClientSide()) {
            sendClientAction("gtShanhaiUploadByproductPattern");
            return;
        }
        if (!(getPlayer() instanceof ServerPlayer player)) return;
        gtShanhai$clearPendingUpload();
        ItemStack pattern = gtShanhai$prepareMarkedPattern(player);
        if (pattern.isEmpty()) return;
        PatternQuickUploadService.SearchResult result = PatternQuickUploadService.findTargets(
                player, getNetworkNode(), pattern);
        if (result.failureMessage() != null) {
            player.displayClientMessage(result.failureMessage(), true);
            return;
        }
        switch (result.match().status()) {
            case UNIQUE -> gtShanhai$insertMarkedPattern(player, pattern, result.match().uniqueCandidate());
            case AMBIGUOUS -> {
                this.gtShanhai$pendingPattern = pattern;
                this.gtShanhai$pendingTargets = List.copyOf(result.match().candidates());
                WirelessAePackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new WirelessAePackets.OpenPatternQuickUploadSelectionPacket(
                                pattern, gtShanhai$selectionEntries(this.gtShanhai$pendingTargets)));
            }
            default -> player.displayClientMessage(Component.translatable(
                    "message.gtlcore.pattern_quick_upload_no_target"), true);
        }
    }

    @Unique
    private ItemStack gtShanhai$prepareMarkedPattern(ServerPlayer player) {
        ItemStack output = this.encodedPatternSlot.getItem();
        if (PatternDetailsHelper.isEncodedPattern(output)) {
            ItemStack copy = output.copy();
            PatternByproductOrder.setMarked(copy, true);
            return copy;
        }
        if (!output.isEmpty() && !AEItems.BLANK_PATTERN.isSameAs(output)) {
            player.displayClientMessage(Component.translatable(
                    "message.gtlcore.pattern_quick_upload_output_blocked"), true);
            return ItemStack.EMPTY;
        }
        if (output.isEmpty() && !AEItems.BLANK_PATTERN.isSameAs(this.blankPatternSlot.getItem())) {
            player.displayClientMessage(Component.translatable(
                    "message.gtlcore.pattern_quick_upload_missing_blank"), true);
            return ItemStack.EMPTY;
        }
        ItemStack encoded = encodePattern();
        if (encoded == null || encoded.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "message.gtlcore.pattern_quick_upload_invalid_pattern"), true);
            return ItemStack.EMPTY;
        }
        PatternByproductOrder.setMarked(encoded, true);
        return encoded;
    }

    @Unique
    private void gtShanhai$insertMarkedPattern(ServerPlayer player, ItemStack pattern,
            PatternQuickUploadService.Target target) {
        if (pattern.isEmpty() || target == null) return;
        PatternByproductOrder.setMarked(pattern, true);
        PatternQuickUploadService.UploadResult upload = PatternQuickUploadService.insertIntoTargetSlotResult(
                player, pattern, target);
        if (upload.status() == PatternQuickUploadService.UploadStatus.INSERTED) {
            gtShanhai$consumePatternSource();
            player.displayClientMessage(Component.translatable(
                    "gt_shanhai.message.pattern_byproduct_order.uploaded", target.targetName()), true);
            return;
        }
        if (upload.status() == PatternQuickUploadService.UploadStatus.DUPLICATE) {
            WirelessAePackets.sendPatternQuickUploadDuplicate(player, target.targetName());
            return;
        }
        player.displayClientMessage(Component.translatable(
                "message.gtlcore.pattern_quick_upload_insert_failed"), true);
    }

    @Unique
    private void gtShanhai$consumePatternSource() {
        if (!this.encodedPatternSlot.getItem().isEmpty()) {
            this.encodedPatternSlot.set(ItemStack.EMPTY);
            return;
        }
        ItemStack blank = this.blankPatternSlot.getItem();
        if (blank.isEmpty()) return;
        blank.shrink(1);
        if (blank.getCount() <= 0) {
            this.blankPatternSlot.set(ItemStack.EMPTY);
        } else {
            this.blankPatternSlot.set(blank);
        }
    }

    @Unique
    private void gtShanhai$clearPendingUpload() {
        this.gtShanhai$pendingTargets = List.of();
        this.gtShanhai$pendingPattern = ItemStack.EMPTY;
    }

    @Unique
    private static List<PatternQuickUploadSelectionMenu.Entry> gtShanhai$selectionEntries(
            List<PatternQuickUploadService.Target> targets) {
        List<PatternQuickUploadSelectionMenu.Entry> entries = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            PatternQuickUploadService.Target target = targets.get(i);
            entries.add(new PatternQuickUploadSelectionMenu.Entry(
                    target.levelKey(),
                    target.bufferPos(),
                    target.targetName(),
                    target.recipeTypeId(),
                    target.recipeTypeName(),
                    target.showsSinglePosition()));
        }
        return entries;
    }
}
