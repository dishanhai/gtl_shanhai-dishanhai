package com.dishanhai.gt_shanhai.mixin;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 gtladditions 配方卡物品槽数量溢出。
 *
 * <p>gtladditions 的 {@code GTLytSlotGrid$GTLytSlot.render} 里，物品数量用
 * {@code String.valueOf(long)} 直接画原始数字，六位数以上会溢出槽位边框
 * （如 114514）。而流体数量走 gtlcore 的 {@link NumberUtils#formatLong(long)}
 * 自动 K/M/G 缩写（如 1.05KB），不溢出。</p>
 *
 * <p>本 mixin 把物品数量那次 {@code String.valueOf(long)} 重定向到
 * {@code NumberUtils.formatLong}，让物品与流体走同一缩写引擎，视觉统一：
 * 16 → 16，64 → 64，114514 → 114.51K。</p>
 *
 * <p>render() 内只有唯一一处 {@code String.valueOf(J)}（即物品数量），
 * 故 ordinal 缺省即精确命中，不影响流体路径。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mixin(targets = "com.gtladd.gtladditions.api.gui.GTLytSlotGrid$GTLytSlot", remap = false)
public class GTLytSlotItemCountFormatMixin {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Ljava/lang/String;valueOf(J)Ljava/lang/String;")
    )
    private String gtShanhai$formatItemCount(long amount) {
        return NumberUtils.formatLong(amount);
    }
}
