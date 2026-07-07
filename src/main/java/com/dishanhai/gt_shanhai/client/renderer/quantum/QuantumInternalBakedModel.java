package com.dishanhai.gt_shanhai.client.renderer.quantum;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitBlock;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

public class QuantumInternalBakedModel extends QuantumConnectedBakedModel {

    public QuantumInternalBakedModel(TextureAtlasSprite face, TextureAtlasSprite side, TextureAtlasSprite poweredSides,
            Map<Direction, TextureAtlasSprite> faceAnimations) {
        super(RenderType.cutout(), face, side, poweredSides);
        setSideEmissive(true);
        setFaceAnimation(faceAnimations, true);
    }

    @Override
    protected boolean shouldConnect(Block block) {
        if (!(block instanceof QuantumCraftingUnitBlock)) {
            return false;
        }
        return ((QuantumCraftingUnitBlock) block).getQuantumType() != QuantumCraftingUnitTypes.STRUCTURE;
    }

    @Override
    protected boolean shouldBeEmissive(BlockState state) {
        return state.hasProperty(QuantumCraftingUnitBlock.POWERED)
                && state.getValue(QuantumCraftingUnitBlock.POWERED).booleanValue();
    }
}
