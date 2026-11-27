package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInstallation;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class StarLifterBlockModels {
    private static final ModelProperty<Boolean> HIDDEN = new ModelProperty<>();
    private static final ModelData HIDDEN_MODEL = ModelData.builder().with(HIDDEN, true).build();

    private StarLifterBlockModels() {}

    @SubscribeEvent
    public static void bake(ModelEvent.ModifyBakingResult event) {
        var shells = DShanhaiBlocks.STAR_LIFTER_SHELL.values().stream()
                .map(entry -> entry.getId().getPath()).collect(Collectors.toSet());
        event.getModels().replaceAll((id, model) -> GTDishanhaiMod.MOD_ID.equals(id.getNamespace())
                && shells.contains(id.getPath()) ? new ShellModel(model) : model);
    }

    public static void acceptSnapshot(ResourceLocation dimension, List<StarLifterInstallation> installations) {
        var previous = StarLifterWorld.acceptSnapshot(dimension, installations);
        var game = Minecraft.getInstance();
        if (game.level == null || !dimension.equals(game.level.dimension().location())) return;
        if (previous == null) previous = List.of();
        refreshRemoved(previous, installations, game);
        refreshRemoved(installations, previous, game);
    }

    private static void refreshRemoved(List<StarLifterInstallation> candidates,
                                       List<StarLifterInstallation> other, Minecraft game) {
        for (var installation : candidates) {
            if (!installation.isController() || other.stream().anyMatch(current ->
                    current.name().equals(installation.name()) && current.center().equals(installation.center())
                            && current.heading() == installation.heading())) continue;
            installation.forEachShellSection(section -> game.levelRenderer.setSectionDirty(
                    SectionPos.x(section), SectionPos.y(section), SectionPos.z(section)));
        }
    }

    private static final class ShellModel extends BakedModelWrapper<BakedModel> {
        private ShellModel(BakedModel model) {
            super(model);
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData previous) {
            var level = Minecraft.getInstance().level;
            if (level != null && (!(world instanceof Level) || world == level) && StarLifterWorld.isReplaced(level, pos)) {
                return HIDDEN_MODEL;
            }
            return super.getModelData(world, pos, state, previous);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                        ModelData data, RenderType layer) {
            return data.has(HIDDEN) ? List.of() : super.getQuads(state, side, random, data, layer);
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
            return data.has(HIDDEN) ? ChunkRenderTypeSet.none() : super.getRenderTypes(state, random, data);
        }
    }
}
