package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.KeyCounter;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = KeyCounter.class, remap = false)
public interface KeyCounterListsAccessor {

    @Accessor("lists")
    Reference2ObjectMap<Object, Object> gtShanhai$getListsRaw();
}
