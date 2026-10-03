package com.dishanhai.gt_shanhai.mixin;

import com.extendedae_plus.ExtendedAEPlus;
import com.extendedae_plus.util.storage.InfinityConstants;
import com.extendedae_plus.util.storage.InfinityStorageManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.math.BigInteger;
import java.util.UUID;

/**
 * EAEP 无限存储盘在首次挂载前必须读取当前世界的 SavedData。
 *
 * <p>旧版 EAEP 在 persist 前还可能遗漏非空盘的 UUID，因此保留修复。</p>
 */
@Mixin(targets = "com.extendedae_plus.api.storage.InfinityBigIntegerCellInventory", remap = false)
public abstract class EaepInfinityCellUuidGuardMixin {

    @Shadow
    @Final
    private ItemStack self;

    @Shadow
    private BigInteger totalAEKey2Amounts;

    @Inject(method = "initData", at = @At("HEAD"), remap = false)
    private void gtShanhai$useCurrentWorldStorage(CallbackInfo ci) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread()) {
            ExtendedAEPlus.STORAGE_INSTANCE = InfinityStorageManager.getInstance(server);
        }
    }

    @Inject(method = "persist", at = @At("HEAD"), remap = false)
    private void gtShanhai$ensureUuidBeforePersist(CallbackInfo ci) {
        BigInteger total = totalAEKey2Amounts;
        if (self == null || self.isEmpty() || total == null || total.signum() <= 0) {
            return;
        }

        CompoundTag tag = self.getOrCreateTag();
        if (tag.hasUUID(InfinityConstants.INFINITY_CELL_UUID)) {
            return;
        }

        tag.putUUID(InfinityConstants.INFINITY_CELL_UUID, UUID.randomUUID());
    }
}
