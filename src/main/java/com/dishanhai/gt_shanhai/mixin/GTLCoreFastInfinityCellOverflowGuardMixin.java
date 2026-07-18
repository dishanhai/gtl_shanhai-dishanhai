package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import org.gtlcore.gtlcore.utils.datastructure.Int128;
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
 * FastInfinityCellInventory 用 Int128 保存真实数量，但 lists 仍是 long 缓存。
 * 大批量插入或提取后必须从 storedMap 重建当前 key 的饱和值，避免 AE 终端看到溢出值。
 */
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.storage.FastInfinityCellInventory", remap = false)
public abstract class GTLCoreFastInfinityCellOverflowGuardMixin {

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Shadow @Final
    private KeyCounter lists;

    @Shadow
    private Object2ObjectOpenHashMap<AEKey, Int128> storedMap;

    @Inject(method = "insert", at = @At("RETURN"), remap = false)
    private void gtShanhai$resyncAfterInsert(AEKey what, long amount, Actionable mode,
                                              IActionSource source, CallbackInfoReturnable<Long> cir) {
        gtShanhai$resyncCounter(what, mode, cir.getReturnValue());
    }

    @Inject(method = "extract", at = @At("RETURN"), remap = false)
    private void gtShanhai$resyncAfterExtract(AEKey what, long amount, Actionable mode,
                                               IActionSource source, CallbackInfoReturnable<Long> cir) {
        gtShanhai$resyncCounter(what, mode, cir.getReturnValue());
    }

    @Redirect(method = "insert", at = @At(value = "INVOKE",
            target = "Lappeng/api/stacks/KeyCounter;set(Lappeng/api/stacks/AEKey;J)V"), remap = false)
    private void gtShanhai$replaceOverflowingInsert(KeyCounter counter, AEKey what, long ignoredAmount) {
        gtShanhai$setFromStoredMap(counter, what);
    }

    @Redirect(method = "loadCellItems", at = @At(value = "INVOKE",
            target = "Lappeng/api/stacks/KeyCounter;add(Lappeng/api/stacks/AEKey;J)V"), remap = false)
    private void gtShanhai$replaceTruncatedLoadedAmount(KeyCounter counter, AEKey what, long ignoredAmount) {
        gtShanhai$setFromStoredMap(counter, what);
    }

    @Redirect(method = "extract", at = @At(value = "INVOKE",
            target = "Lappeng/api/stacks/KeyCounter;set(Lappeng/api/stacks/AEKey;J)V"), remap = false)
    private void gtShanhai$replaceOverflowingExtract(KeyCounter counter, AEKey what, long ignoredAmount) {
        gtShanhai$setFromStoredMap(counter, what);
    }

    private void gtShanhai$resyncCounter(AEKey what, Actionable mode, long moved) {
        if (mode != Actionable.MODULATE || moved <= 0L || lists == null || storedMap == null) return;
        Int128 total = storedMap.get(what);
        if (total == null || !total.isPositive()) {
            lists.remove(what);
            return;
        }
        BigInteger value = total.toBigInteger();
        lists.set(what, value.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : value.longValue());
    }

    private void gtShanhai$setFromStoredMap(KeyCounter counter, AEKey what) {
        if (storedMap == null) return;
        Int128 total = storedMap.get(what);
        if (total == null || !total.isPositive()) {
            counter.remove(what);
            return;
        }
        BigInteger value = total.toBigInteger();
        counter.set(what, value.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : value.longValue());
    }
}
