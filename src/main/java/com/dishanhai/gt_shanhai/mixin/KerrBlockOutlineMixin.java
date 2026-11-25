package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensRenderer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LevelRenderer.class)
abstract class KerrBlockOutlineMixin {
    @ModifyVariable(method = {"renderHitOutline", "m_109637_"}, at = @At("HEAD"),
            argsOnly = true, ordinal = 0, remap = false)
    private VertexConsumer shanhai$foregroundSelection(VertexConsumer vanilla) {
        return BlackHoleLensRenderer.foregroundOutline(vanilla);
    }
}
