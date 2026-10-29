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

/** 山海：獨立覆蓋板貼圖；位於原生機器表面及 ±0.001 工作覆層之外，避免深度衝突。 */
public final class ShanhaiAeSteamCoverRenderer extends SimpleCoverRenderer {
    public static final ResourceLocation TEXTURE = new ResourceLocation("gt_shanhai", "block/cover/ae_steam_cover");
    public static final AABB SURFACE_BOUNDS = new AABB(-0.002, -0.002, -0.002, 1.002, 1.002, 1.002);

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
