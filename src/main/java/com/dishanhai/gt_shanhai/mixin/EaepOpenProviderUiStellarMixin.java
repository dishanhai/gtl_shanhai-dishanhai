package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStellarRemoteUIFactory;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.extendedae_plus.network.provider.OpenProviderUiC2SPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OpenProviderUiC2SPacket.class, remap = false)
public abstract class EaepOpenProviderUiStellarMixin {

    @Shadow
    @Final
    private long posLong;

    @Shadow
    @Final
    private ResourceLocation dimId;

    @Inject(method = "lambda$handle$0", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$openStellarPatternUi(NetworkEvent.Context context,
            OpenProviderUiC2SPacket packet, CallbackInfo callback) {
        ServerPlayer player = context.getSender();
        if (player == null || !(player.containerMenu instanceof ShanhaiPatternManagementMenu menu)) return;

        EaepOpenProviderUiStellarMixin access = (EaepOpenProviderUiStellarMixin) (Object) packet;
        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, access.dimId);
        ServerLevel level = player.server.getLevel(levelKey);
        if (level == null) return;

        BlockPos pos = BlockPos.of(access.posLong);
        RecipeTypePatternBufferPartMachine stellar = menu.resolveStellarContainer(level, pos);
        if (stellar == null) return;

        context.setPacketHandled(true);
        ShanhaiStellarRemoteUIFactory.INSTANCE.openPattern(player, stellar, menu.getWirelessHost());
        callback.cancel();
    }
}
