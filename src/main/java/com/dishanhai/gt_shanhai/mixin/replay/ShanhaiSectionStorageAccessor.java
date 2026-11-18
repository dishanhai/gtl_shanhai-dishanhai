package com.dishanhai.gt_shanhai.mixin.replay;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface ShanhaiSectionStorageAccessor {
    @Accessor("worker") IOWorker shanhai$getWorker();
}
