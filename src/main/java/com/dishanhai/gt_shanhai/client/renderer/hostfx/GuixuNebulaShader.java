package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 山海：星雲動態材質；由原版 shader 重載生命週期管理，不建立粒子或貼圖更新執行緒。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GuixuNebulaShader {
    public static final VertexFormat FORMAT = new VertexFormat(ImmutableMap.of(
            "Position", DefaultVertexFormat.ELEMENT_POSITION, "UV0", DefaultVertexFormat.ELEMENT_UV0,
            "Color", DefaultVertexFormat.ELEMENT_COLOR, "Normal", DefaultVertexFormat.ELEMENT_NORMAL,
            "Padding", DefaultVertexFormat.ELEMENT_PADDING));
    private static ShaderInstance shader;

    private GuixuNebulaShader() {}

    @SubscribeEvent
    public static void register(RegisterShadersEvent event) {
        shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "guixu_nebula"), FORMAT),
                    loaded -> shader = loaded);
        } catch (java.io.IOException | RuntimeException error) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] 星雲 shader 載入失敗，回落原生 UV 流動", error);
        }
    }

    public static ShaderInstance shader() {
        return shader != null ? shader : GameRenderer.getPositionTexColorShader();
    }

    /** 每台主機在自己的批次提交前設定，降級／凍結時仍使用主機動畫時鐘。 */
    public static boolean prepare(double seconds, boolean degraded) {
        if (shader == null) return false;
        shader.safeGetUniform("NebulaPhase").set(GuixuNebulaField.materialPhase(seconds));
        shader.safeGetUniform("NebulaDetail").set(degraded ? 0F : 1F);
        return true;
    }
}
