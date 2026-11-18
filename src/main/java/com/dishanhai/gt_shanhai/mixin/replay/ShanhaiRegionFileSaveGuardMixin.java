package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.common.time.replay.ReplaySaveGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;

@Mixin(RegionFileStorage.class)
public abstract class ShanhaiRegionFileSaveGuardMixin {
    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("HEAD"), require = 1)
    private void shanhai$guardRegionWrite(ChunkPos pos, CompoundTag data, CallbackInfo ci) throws IOException {
        if (ReplaySaveGuard.rejectRegionWrite()) throw new IOException("Region write rejected during replay: " + pos);
    }
}
