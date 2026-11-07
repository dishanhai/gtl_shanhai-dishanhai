package com.dishanhai.gt_shanhai.mixin;

import appeng.core.sync.BasePacket;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BasePacket.class, remap = false)
public interface ShanhaiAe2PacketPayloadAccessor {
    @Accessor("p")
    FriendlyByteBuf gtShanhai$getPayload();
}
