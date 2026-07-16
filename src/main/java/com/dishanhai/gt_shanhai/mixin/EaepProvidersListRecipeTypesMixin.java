package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.compat.eaep.EaepProviderRecipeTypeBridge;
import com.dishanhai.gt_shanhai.common.compat.eaep.EaepProviderRecipeTypesPacketAccess;
import com.extendedae_plus.network.provider.ProvidersListS2CPacket;

import net.minecraft.network.FriendlyByteBuf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = ProvidersListS2CPacket.class, remap = false)
public class EaepProvidersListRecipeTypesMixin implements EaepProviderRecipeTypesPacketAccess {

    @Unique
    private List<List<String>> gtShanhai$providerRecipeTypeIds = List.of();

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtShanhai$initRecipeTypes(List<Long> ids, List<String> names, List<Integer> emptySlots,
            CallbackInfo ci) {
        this.gtShanhai$providerRecipeTypeIds = List.of();
    }

    @Inject(method = "encode", at = @At("TAIL"), remap = false)
    private static void gtShanhai$encodeRecipeTypes(ProvidersListS2CPacket msg, FriendlyByteBuf buf,
            CallbackInfo ci) {
        List<List<String>> providerTypes =
                ((EaepProviderRecipeTypesPacketAccess) msg).gtShanhai$getProviderRecipeTypeIds();
        buf.writeVarInt(providerTypes.size());
        for (List<String> types : providerTypes) {
            buf.writeVarInt(types.size());
            for (String type : types) {
                buf.writeUtf(type, 128);
            }
        }
    }

    @Inject(method = "decode", at = @At("RETURN"), remap = false)
    private static void gtShanhai$decodeRecipeTypes(FriendlyByteBuf buf,
            CallbackInfoReturnable<ProvidersListS2CPacket> cir) {
        if (buf.readableBytes() <= 0) {
            return;
        }
        int providerCount = buf.readVarInt();
        List<List<String>> providerTypes = new ArrayList<>(providerCount);
        for (int i = 0; i < providerCount; i++) {
            int typeCount = buf.readVarInt();
            List<String> types = new ArrayList<>(typeCount);
            for (int j = 0; j < typeCount; j++) {
                types.add(buf.readUtf(128));
            }
            providerTypes.add(types);
        }
        ((EaepProviderRecipeTypesPacketAccess) cir.getReturnValue()).gtShanhai$setProviderRecipeTypeIds(providerTypes);
    }

    @Inject(method = "handleClient", at = @At("HEAD"), remap = false)
    private static void gtShanhai$pushRecipeTypes(ProvidersListS2CPacket msg, CallbackInfo ci) {
        EaepProviderRecipeTypeBridge.setIncomingProviderRecipeTypes(
                ((EaepProviderRecipeTypesPacketAccess) msg).gtShanhai$getProviderRecipeTypeIds());
    }

    @Inject(method = "handleClient", at = @At("RETURN"), remap = false)
    private static void gtShanhai$clearRecipeTypes(ProvidersListS2CPacket msg, CallbackInfo ci) {
        EaepProviderRecipeTypeBridge.clearIncomingProviderRecipeTypes();
    }

    @Override
    public List<List<String>> gtShanhai$getProviderRecipeTypeIds() {
        return this.gtShanhai$providerRecipeTypeIds;
    }

    @Override
    public void gtShanhai$setProviderRecipeTypeIds(List<List<String>> providerRecipeTypeIds) {
        this.gtShanhai$providerRecipeTypeIds = providerRecipeTypeIds == null ? List.of() : providerRecipeTypeIds;
    }
}
