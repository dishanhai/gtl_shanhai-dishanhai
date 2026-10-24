package com.dishanhai.gt_shanhai.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Supplier;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.ShanhaiStructureHighlightPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ShanhaiStructureHighlightClient {

    private static final Supplier<Boolean> BLINK = () -> ((System.currentTimeMillis() / 500) & 1) != 0;
    private static boolean initialized;
    private static Constructor<?> colorConstructor;
    private static Method highlightMethod;

    /** 每個客戶端 tick 最多登記的方框數；大量坐標分批登記，避免一次塞滿造成卡頓。 */
    private static final int BATCH_PER_TICK = 96;
    private record Pending(ResourceKey<Level> dimension, long expiresAt, Iterator<ShanhaiStructureHighlightPacket.Marker> markers) {}
    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();

    private ShanhaiStructureHighlightClient() {}

    /** 排入佇列、之後每 tick 分批標出（主線程）；到期時間以排入時刻為準。 */
    public static void highlightAsync(ResourceKey<Level> dimension, long expiresAt,
                                      Iterable<ShanhaiStructureHighlightPacket.Marker> markers) {
        QUEUE.add(new Pending(dimension, expiresAt, markers.iterator()));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUE.isEmpty()) return;
        Pending pending = QUEUE.peek();
        java.util.List<ShanhaiStructureHighlightPacket.Marker> batch = new java.util.ArrayList<>(BATCH_PER_TICK);
        while (batch.size() < BATCH_PER_TICK && pending.markers().hasNext()) batch.add(pending.markers().next());
        highlight(pending.dimension(), pending.expiresAt(), batch);
        if (!pending.markers().hasNext()) QUEUE.poll();
    }

    public static void highlight(ShanhaiStructureHighlightPacket packet) {
        highlight(packet.dimension(), packet.expiresAt(), packet.markers());
    }

    /** 客户端界面直接调用（报告界面的「显示坐标方框」），不经过封包与 256 个的封包上限。 */
    public static void highlight(ResourceKey<Level> dimension, long expiresAt,
                                 Iterable<ShanhaiStructureHighlightPacket.Marker> markers) {
        initializeBridge();
        if (colorConstructor == null || highlightMethod == null) return;
        try {
            for (ShanhaiStructureHighlightPacket.Marker marker : markers) {
                int color = marker.color();
                Object colorData = colorConstructor.newInstance(
                        ((color >> 16) & 0xFF) / 255.0f,
                        ((color >> 8) & 0xFF) / 255.0f,
                        (color & 0xFF) / 255.0f);
                highlightMethod.invoke(null, marker.pos(), null, dimension, expiresAt,
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
