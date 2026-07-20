package com.dishanhai.gt_shanhai.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.ShanhaiStructureHighlightPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ShanhaiStructureHighlightClient {

    private static final Supplier<Boolean> BLINK = () -> ((System.currentTimeMillis() / 500) & 1) != 0;
    private static boolean initialized;
    private static Constructor<?> colorConstructor;
    private static Method highlightMethod;

    private ShanhaiStructureHighlightClient() {}

    public static void highlight(ShanhaiStructureHighlightPacket packet) {
        initializeBridge();
        if (colorConstructor == null || highlightMethod == null) return;
        try {
            for (ShanhaiStructureHighlightPacket.Marker marker : packet.markers()) {
                int color = marker.color();
                Object colorData = colorConstructor.newInstance(
                        ((color >> 16) & 0xFF) / 255.0f,
                        ((color >> 8) & 0xFF) / 255.0f,
                        (color & 0xFF) / 255.0f);
                highlightMethod.invoke(null, marker.pos(), null, packet.dimension(), packet.expiresAt(),
                        new AABB(marker.pos()), colorData, BLINK);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            colorConstructor = null;
            highlightMethod = null;
            GTDishanhaiMod.LOGGER.warn("[山海终端] 彩色结构高亮调用失败，保留聊天标识", e);
        }
    }

    private static synchronized void initializeBridge() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> colorType = Class.forName("com.glodblock.github.glodium.client.render.ColorData");
            Class<?> handlerType = Class.forName(
                    "com.glodblock.github.glodium.client.render.highlight.HighlightHandler");
            colorConstructor = colorType.getConstructor(float.class, float.class, float.class);
            highlightMethod = handlerType.getMethod("highlight", BlockPos.class, Direction.class,
                    ResourceKey.class, long.class, AABB.class, colorType, Supplier.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            colorConstructor = null;
            highlightMethod = null;
            GTDishanhaiMod.LOGGER.warn("[山海终端] Glodium 彩色高亮桥不可用，保留聊天标识", e);
        }
    }
}
