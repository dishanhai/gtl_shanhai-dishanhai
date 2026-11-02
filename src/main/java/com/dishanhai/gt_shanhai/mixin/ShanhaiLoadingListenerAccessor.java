package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 山海：辨識 Forge 包裝的原載入器，避免 23 個不同項目被合併成一筆。 */
@Mixin(targets = "net.minecraftforge.event.AddReloadListenerEvent$WrappedStateAwareListener", remap = false)
public interface ShanhaiLoadingListenerAccessor {
    @Accessor(value = "wrapped", remap = false)
    PreparableReloadListener shanhai$wrapped();
}
