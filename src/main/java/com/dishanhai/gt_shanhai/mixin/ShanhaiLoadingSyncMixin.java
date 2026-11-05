package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：僅採用目前連線在主執行緒套用的配方包，晚到舊封包不能污染新會話。 */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ShanhaiLoadingSyncMixin {
    @Group(name = "shanhaiSyncRecipes", min = 1, max = 1)
    @WrapOperation(method = {"handleUpdateRecipes", "m_6327_"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/RecipeManager;replaceRecipes(Ljava/lang/Iterable;)V", remap = false),
            require = 0, remap = false)
    private void shanhai$syncProgress(RecipeManager manager, Iterable<Recipe<?>> recipes, Operation<Void> original,
                                      ClientboundUpdateRecipesPacket packet) {
        original.call(manager, ShanhaiLoadingClient.trackSyncedRecipes(
                (ClientPacketListener) (Object) this, recipes, packet.getRecipes().size()));
    }

    @Group(name = "shanhaiSyncRecipes", min = 1, max = 1)
    @WrapOperation(method = {"handleUpdateRecipes", "m_6327_"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/RecipeManager;m_44024_(Ljava/lang/Iterable;)V", remap = false),
            require = 0, remap = false)
    private void shanhai$syncProgressSrg(RecipeManager manager, Iterable<Recipe<?>> recipes, Operation<Void> original,
                                         ClientboundUpdateRecipesPacket packet) {
        original.call(manager, ShanhaiLoadingClient.trackSyncedRecipes(
                (ClientPacketListener) (Object) this, recipes, packet.getRecipes().size()));
    }

    @Inject(method = {"handleUpdateRecipes", "m_6327_"}, at = @At("RETURN"), remap = false)
    private void shanhai$syncEnd(ClientboundUpdateRecipesPacket packet, CallbackInfo ci) {
        ShanhaiLoadingClient.syncRecipes((ClientPacketListener) (Object) this, packet.getRecipes().size(), true);
    }
}
