package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SlotCacheManager.class, remap = false)
public interface SlotCacheManagerAccessor {

    @Accessor("circuitCache")
    void gtShanhai$setCircuitCacheRaw(int circuitCache);

    @Accessor("circuitStack")
    void gtShanhai$setCircuitStackRaw(ItemStack circuitStack);
}
