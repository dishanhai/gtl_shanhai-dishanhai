package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.cells.ISaveProvider;
import com.dishanhai.gt_shanhai.api.ae2.AeStorageAmountMath;
import com.extendedae_plus.api.storage.InfinityBigIntegerCellInventory;
import com.extendedae_plus.util.storage.InfinityConstants;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
    @Final
    private ISaveProvider container;

    @Shadow
    private int totalAEKeyType;

    @Shadow
    private BigInteger totalAEKey2Amounts;

    @Shadow
    private boolean isPersisted;

    @Shadow
    private Object2ObjectMap<AEKey, BigInteger> getCellStoredMap() {
        throw new AssertionError();
    }

    @Shadow
    public abstract void persist();

    @Unique
    private BigInteger gtShanhai$storedBeforeExtract;

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

    @Inject(method = "extract", at = @At("HEAD"), remap = false)
    private void gtShanhai$captureStoredAmount(AEKey what, long amount, Actionable mode, IActionSource source,
                                               CallbackInfoReturnable<Long> cir) {
        gtShanhai$storedBeforeExtract = mode == Actionable.MODULATE && amount > 0L
                ? getCellStoredMap().getOrDefault(what, BigInteger.ZERO)
                : null;
    }

    @Redirect(method = "insert", at = @At(value = "INVOKE",
            target = "Lcom/extendedae_plus/api/storage/InfinityBigIntegerCellInventory;saveChanges()V"),
            remap = false)
    private void gtShanhai$saveIncrementalInsert(InfinityBigIntegerCellInventory inventory, AEKey what, long amount,
                                                 Actionable mode, IActionSource source) {
        gtShanhai$markChanged(AeStorageAmountMath.afterBigIntegerInsert(totalAEKey2Amounts, amount));
    }

    @Redirect(method = "extract", at = @At(value = "INVOKE",
            target = "Lcom/extendedae_plus/api/storage/InfinityBigIntegerCellInventory;saveChanges()V"),
            remap = false)
    private void gtShanhai$saveIncrementalExtract(InfinityBigIntegerCellInventory inventory, AEKey what, long amount,
                                                  Actionable mode, IActionSource source) {
        BigInteger storedBefore = gtShanhai$storedBeforeExtract;
        gtShanhai$storedBeforeExtract = null;
        gtShanhai$markChanged(AeStorageAmountMath.afterBigIntegerExtract(
                totalAEKey2Amounts, storedBefore, amount));
    }

    @Unique
    private void gtShanhai$markChanged(BigInteger newTotal) {
        Object2ObjectMap<AEKey, BigInteger> stored = getCellStoredMap();
        totalAEKeyType = stored == null ? 0 : stored.size();
        totalAEKey2Amounts = newTotal;
        isPersisted = false;
        if (container != null) {
            container.saveChanges();
        } else {
            persist();
        }
    }
}
