package com.dishanhai.gt_shanhai.mixin.replay;

import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentEntitySectionManager.class)
public interface ShanhaiEntityManagerStorageAccessor {
    @Accessor("permanentStorage") EntityPersistentStorage<?> shanhai$getPermanentStorage();
}
