package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/** 山海：群星终焉使用随演出时钟更新的球面材质。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GuixuCelestialShader {
    private static ShaderInstance shader;

    private GuixuCelestialShader() {}

    @SubscribeEvent
    public static void register(RegisterShadersEvent event) {
        shader = null;
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation(GTDishanhaiMod.MOD_ID, "guixu_celestial"), DefaultVertexFormat.POSITION_TEX_COLOR),
                    loaded -> shader = loaded);
        } catch (IOException | RuntimeException error) {
            GTDishanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] 天体材质加载失败，回退到自转贴图", error);
        }
    }

    static ShaderInstance shader() {
        return shader;
    }

    static boolean prepare(double seconds, float magneticTilt) {
        if (shader == null) return false;
        shader.safeGetUniform("SurfaceSeconds").set((float) seconds);
        shader.safeGetUniform("MagneticAxis").set(-(float) Math.sin(magneticTilt), (float) Math.cos(magneticTilt), 0F);
        return true;
    }
}
