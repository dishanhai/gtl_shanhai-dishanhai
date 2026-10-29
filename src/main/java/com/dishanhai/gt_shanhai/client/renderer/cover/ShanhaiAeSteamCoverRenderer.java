package com.dishanhai.gt_shanhai.client.renderer.cover;

import com.gregtechceu.gtceu.api.cover.CoverBehavior;
import com.gregtechceu.gtceu.client.renderer.cover.SimpleCoverRenderer;
import com.gregtechceu.gtceu.client.util.StaticFaceBakery;
import com.lowdragmc.lowdraglib.client.model.ModelFactory;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** 山海：覆蓋板在機殼之外、原生 overlay 之內，保留主機圖案與工作狀態顯示。 */
public final class ShanhaiAeSteamCoverRenderer extends SimpleCoverRenderer {
    public static final ResourceLocation TEXTURE = new ResourceLocation("gt_shanhai", "block/cover/ae_steam_cover");
    // 蒸汽倉 front overlay 為 0.001/16 格，比加工機的 0.001 更近；取其內側的獨立層。
    private static final double SURFACE_OFFSET = 1.0 / 32768.0;
    public static final AABB SURFACE_BOUNDS = new AABB(-SURFACE_OFFSET, -SURFACE_OFFSET, -SURFACE_OFFSET,
            1 + SURFACE_OFFSET, 1 + SURFACE_OFFSET, 1 + SURFACE_OFFSET);

    public ShanhaiAeSteamCoverRenderer() {
        super(TEXTURE);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void renderCover(List<BakedQuad> quads, Direction side, RandomSource rand,
                            CoverBehavior cover, Direction modelFacing, BlockPos pos,
                            BlockAndTintGetter level, ModelState modelState) {
        if (side == cover.attachedSide && modelFacing != null) {
            quads.add(StaticFaceBakery.bakeFace(SURFACE_BOUNDS, modelFacing,
                    ModelFactory.getBlockSprite(TEXTURE), modelState, -1, 0, true, true));
        }
    }
}
