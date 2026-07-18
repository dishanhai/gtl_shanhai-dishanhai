package com.dishanhai.gt_shanhai.mixin;

import com.extendedae_plus.util.storage.InfinityConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.math.BigInteger;
import java.util.UUID;

/**
 * EAEP 非空无限元件在 persist 前必须先拥有 UUID。
 *
 * <p>EAEP 的原始 persist() 会把 UUID 直接传给 InfinityStorageManager；旧物或
 * 临时 ItemStack 若已有内存库存但没有 UUID，会把 null 作为 SavedData 的 map key，
 * 随后在世界保存时因 CompoundTag.putUUID(null) 崩溃。</p>
 */
@Mixin(targets = "com.extendedae_plus.api.storage.InfinityBigIntegerCellInventory", remap = false)
public abstract class EaepInfinityCellUuidGuardMixin {

    @Shadow
    @Final
    private ItemStack self;

    @Shadow
    public abstract boolean hasUUID();

    @Shadow
    public abstract BigInteger getTotalAEKey2Amounts();

    @Inject(method = "persist", at = @At("HEAD"), remap = false)
    private void gtShanhai$ensureUuidBeforePersist(CallbackInfo ci) {
        BigInteger total = getTotalAEKey2Amounts();
        if (self == null || self.isEmpty() || hasUUID() || total == null || total.signum() <= 0) {
            return;
        }

        CompoundTag tag = self.getOrCreateTag();
        if (!tag.hasUUID(InfinityConstants.INFINITY_CELL_UUID)) {
            tag.putUUID(InfinityConstants.INFINITY_CELL_UUID, UUID.randomUUID());
        }
    }
}
