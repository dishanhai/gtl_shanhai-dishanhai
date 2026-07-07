package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiTempBypass;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CompoundTag.getInt 级拦截。
 * 绕过激活时对 ebf_temp / blastFurnaceTemp 返回 0。
 */
@Mixin(CompoundTag.class)
public class CompoundTagBypassMixin {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:temp");

    @Inject(method = "getInt", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$bypassTemp(String key, CallbackInfoReturnable<Integer> cir) {
        if (!DShanhaiTempBypass.isActive()) return;
        if (key.contains("temp") || key.contains("Temp") || key.contains("ebf") || key.contains("blast")) {
            LOG.info("[TempBypass] intercepted key={}", key);
        }
        if ("ebf_temp".equals(key) || "blastFurnaceTemp".equals(key)) {
            cir.setReturnValue(0);
        }
    }
}
