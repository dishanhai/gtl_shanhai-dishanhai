package com.dishanhai.gt_shanhai.client.renderer.item;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class HypercubeModelEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GTDishanhaiMod.MOD_ID + "/hypercube");
    private static final boolean ENABLE_HYPERCUBE_RENDERER = false;
    private static final ResourceLocation HYPERCUBE_ID = new ResourceLocation("kubejs", "hypercube");

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        if (!ENABLE_HYPERCUBE_RENDERER) {
            return;
        }
        int wrapped = 0;
        for (Map.Entry<ResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            ResourceLocation key = entry.getKey();
            if (!HYPERCUBE_ID.getNamespace().equals(key.getNamespace())
                    || !HYPERCUBE_ID.getPath().equals(key.getPath())) {
                continue;
            }
            BakedModel model = entry.getValue();
            if (model != null && !(model instanceof HypercubeBakedModel)) {
                entry.setValue(new HypercubeBakedModel(model));
                wrapped++;
            }
        }
        if (wrapped > 0) {
            LOGGER.info("Wrapped {} model(s) for {}", wrapped, HYPERCUBE_ID);
        }
    }
}
