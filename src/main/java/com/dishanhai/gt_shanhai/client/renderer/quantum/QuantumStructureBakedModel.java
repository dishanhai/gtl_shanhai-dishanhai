package com.dishanhai.gt_shanhai.client.renderer.quantum;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitBlock;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class QuantumStructureBakedModel extends QuantumConnectedBakedModel {

    public QuantumStructureBakedModel(TextureAtlasSprite face, TextureAtlasSprite sides, TextureAtlasSprite poweredSides) {
        // 面用半透明、边用硬切边框，两种渲染层——真正的黑块/空隙根因是模型压根没装上
        // （见 QuantumModelRegistration 的说明），跟这里声明几种 RenderType 无关，维持原设计。
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
