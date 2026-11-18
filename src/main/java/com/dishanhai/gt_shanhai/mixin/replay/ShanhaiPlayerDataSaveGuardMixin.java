package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.common.time.replay.ReplaySaveGuard;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerDataStorage.class)
public abstract class ShanhaiPlayerDataSaveGuardMixin {
    @Inject(method = "save(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void shanhai$guardPlayerSave(Player player, CallbackInfo ci) {
        if (ReplaySaveGuard.rejectSave()) ci.cancel();
    }
}
