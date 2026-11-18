package com.dishanhai.gt_shanhai.mixin.replay;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStorage.class)
public interface ShanhaiEntityStorageAccessor {
    @Accessor("worker") IOWorker shanhai$getWorker();
}
