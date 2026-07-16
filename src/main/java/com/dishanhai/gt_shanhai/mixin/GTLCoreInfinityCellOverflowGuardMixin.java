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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.math.BigInteger;

/**
 * GTLCore 无限盘 InfinityCellInventory#insert 用 this.lists.add(what, amount) 对 AE 可见量缓存
 * 做纯 long 累加：单个 key 的历史累计存量一旦超过 Long.MAX_VALUE，这次累加就会整数溢出（AE 终端显示/
 * 可提取量表现为归零或负数），但真正的 BigInteger storedMap 从不溢出——两份数据从此永久失步。
 * 对照 loadCellItems() 重新读盘时用的钳制方式（超限统一按 Long.MAX_VALUE 封顶），这里在 insert 的
 * MODULATE 分支成功后，直接用 storedMap 的真实值重新钳制覆盖 lists，取代有溢出风险的增量 add。
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
    private void gtShanhai$resyncOverflowSafeCounter(AEKey what, long amount, Actionable mode, IActionSource source,
                                                      CallbackInfoReturnable<Long> cir) {
        if (mode != Actionable.MODULATE || cir.getReturnValue() <= 0L) return;
        if (storedMap == null || lists == null) return;
        BigInteger total = storedMap.get(what);
        if (total == null) return;
        long clamped = total.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : total.longValue();
        lists.set(what, clamped);
    }
}
