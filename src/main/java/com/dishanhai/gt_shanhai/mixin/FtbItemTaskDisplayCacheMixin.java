package com.dishanhai.gt_shanhai.mixin;

import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = ItemTask.class, remap = false)
public class FtbItemTaskDisplayCacheMixin {

    @Unique
    private List<ItemStack> shanhai$validDisplayItems;

    @Inject(method = "getValidDisplayItems", at = @At("HEAD"), cancellable = true)
    private void shanhai$getCachedValidDisplayItems(CallbackInfoReturnable<List<ItemStack>> cir) {
        if (this.shanhai$validDisplayItems != null) {
            cir.setReturnValue(this.shanhai$validDisplayItems);
        }
    }

    @Inject(method = "getValidDisplayItems", at = @At("RETURN"))
    private void shanhai$storeCachedValidDisplayItems(CallbackInfoReturnable<List<ItemStack>> cir) {
        if (this.shanhai$validDisplayItems == null) {
            this.shanhai$validDisplayItems = cir.getReturnValue();
        }
    }

    @Inject(method = "readData", at = @At("RETURN"))
    private void shanhai$clearCacheAfterReadData(CompoundTag nbt, CallbackInfo ci) {
        this.shanhai$validDisplayItems = null;
    }

    @Inject(method = "readNetData", at = @At("RETURN"))
    private void shanhai$clearCacheAfterReadNetData(FriendlyByteBuf buffer, CallbackInfo ci) {
        this.shanhai$validDisplayItems = null;
    }

    @Inject(method = "setStackAndCount", at = @At("RETURN"))
    private void shanhai$clearCacheAfterStackChange(ItemStack stack, int count, CallbackInfoReturnable<ItemTask> cir) {
        this.shanhai$validDisplayItems = null;
    }
}
