package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.math.BigInteger;

/**
 * GTLCore 无限盘 InfinityCellInventory#insert/extract 用 this.lists 的 long 缓存对外提供数量，
 * 但真正的 BigInteger storedMap 不会溢出。超出 long 范围后，任何后续小额变更若只更新 lists，
 * 都会把已经饱和的 Long.MAX_VALUE 当成真实值继续加减，导致 AE 可见库存溢出或被清空；
 * 每次 MODULATE 成功后都必须从 storedMap 重新同步。
 */
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.storage.InfinityCellInventory", remap = false)
public abstract class GTLCoreInfinityCellOverflowGuardMixin {

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Shadow(remap = false)
    @Final
    private KeyCounter lists;

    @Shadow(remap = false)
    private Object2ObjectOpenHashMap<AEKey, BigInteger> storedMap;

    @Inject(method = "insert", at = @At("RETURN"), remap = false)
    private void gtShanhai$resyncOverflowSafeInsert(AEKey what, long amount, Actionable mode,
                                                     IActionSource source, CallbackInfoReturnable<Long> cir) {
        gtShanhai$resyncCounter(what, mode, cir.getReturnValue());
    }

    @Inject(method = "extract", at = @At("RETURN"), remap = false)
    private void gtShanhai$resyncOverflowSafeExtract(AEKey what, long amount, Actionable mode,
                                                      IActionSource source, CallbackInfoReturnable<Long> cir) {
        gtShanhai$resyncCounter(what, mode, cir.getReturnValue());
    }

    @Redirect(method = "insert", at = @At(value = "INVOKE",
            target = "Lappeng/api/stacks/KeyCounter;add(Lappeng/api/stacks/AEKey;J)V"), remap = false)
    private void gtShanhai$replaceOverflowingInsert(KeyCounter counter, AEKey what, long amount) {
        gtShanhai$setFromStoredMap(counter, what, amount);
    }

    @Redirect(method = "extract", at = @At(value = "INVOKE",
            target = "Lappeng/api/stacks/KeyCounter;remove(Lappeng/api/stacks/AEKey;J)V"), remap = false)
    private void gtShanhai$replaceOverflowingExtract(KeyCounter counter, AEKey what, long amount) {
        gtShanhai$setFromStoredMap(counter, what, -amount);
    }

    private void gtShanhai$resyncCounter(AEKey what, Actionable mode, long moved) {
        if (mode != Actionable.MODULATE || moved <= 0L) return;
        if (storedMap == null || lists == null) return;
        BigInteger total = storedMap.get(what);
        if (total == null || total.signum() <= 0) {
            lists.remove(what);
            return;
        }
        long clamped = total.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : total.longValue();
        lists.set(what, clamped);
    }

    private void gtShanhai$setFromStoredMap(KeyCounter counter, AEKey what, long ignoredDelta) {
        if (storedMap == null) return;
        BigInteger total = storedMap.get(what);
        if (total == null || total.signum() <= 0) {
            counter.remove(what);
            return;
        }
        counter.set(what, total.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : total.longValue());
    }
}
