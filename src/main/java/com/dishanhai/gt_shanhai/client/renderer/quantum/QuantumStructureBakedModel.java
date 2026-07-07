package com.dishanhai.gt_shanhai.client.renderer.quantum;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitBlock;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class QuantumStructureBakedModel extends QuantumConnectedBakedModel {

    public QuantumStructureBakedModel(TextureAtlasSprite face, TextureAtlasSprite sides, TextureAtlasSprite poweredSides) {
        super(RenderType.translucent(), RenderType.cutout(), face, sides, poweredSides);
        setSideEmissive(true);
        setRenderOppositeSide(true);
    }

    @Override
    protected boolean shouldConnect(Block block) {
        if (!(block instanceof QuantumCraftingUnitBlock)) {
            return false;
        }
        return ((QuantumCraftingUnitBlock) block).getQuantumType() == QuantumCraftingUnitTypes.STRUCTURE;
    }

    @Override
    protected boolean shouldBeEmissive(BlockState state) {
        return state.hasProperty(QuantumCraftingUnitBlock.POWERED)
                && state.getValue(QuantumCraftingUnitBlock.POWERED).booleanValue();
    }
}
