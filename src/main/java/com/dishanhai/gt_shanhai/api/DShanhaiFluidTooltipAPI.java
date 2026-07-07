package com.dishanhai.gt_shanhai.api;

import dev.latvian.mods.kubejs.KubeJS;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DShanhaiFluidTooltipAPI {

    private static final Map<String, List<String>> ENTRIES = new ConcurrentHashMap<>();

    /**
     * 注册流体 tooltip 行（每行可用 {style} 行内标记）。
     * <pre>
     * DShanhaiFluidTooltipAPI.register("dishanhai:primal_chaos", [
     *   "第一行描述",
     *   "{aurora}第二行高亮{/}和普通混合",
     *   "第三行"
     * ]);
     * </pre>
     */
    public static void register(String fluidId, String[] lines) {
        ENTRIES.put(fluidId, new ArrayList<>(Arrays.asList(lines)));
        KubeJS.LOGGER.info("[山海流体] 注册: {} → {} 行", fluidId, lines != null ? lines.length : 0);
    }

    /** 注册单行 */
    public static void registerLine(String fluidId, String line) {
        ENTRIES.computeIfAbsent(fluidId, k -> new ArrayList<>()).add(line);
        KubeJS.LOGGER.info("[山海流体] registerLine: {}", fluidId);
    }

    public static void remove(String fluidId) {
        ENTRIES.remove(fluidId);
        KubeJS.LOGGER.info("[山海流体] remove: {}", fluidId);
    }

    public static void clear() {
        ENTRIES.clear();
        KubeJS.LOGGER.info("[山海流体] clear");
    }

    /** 获取某流体的注册行（KubeJS 调用用） */
    public static String[] getEntryLines(String fluidId) {
        List<String> lines = ENTRIES.get(fluidId);
        return lines != null ? lines.toArray(new String[0]) : null;
    }

    /** 获取所有已注册流体 ID */
    public static String[] getRegisteredIds() {
        return ENTRIES.keySet().toArray(new String[0]);
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BucketItem bucket)) return;

        String fluidId = bucket.getFluid().builtInRegistryHolder().key().location().toString();
        List<String> lines = ENTRIES.get(fluidId);
        if (lines == null || lines.isEmpty()) return;

        KubeJS.LOGGER.info("[山海流体] ItemTooltipEvent 触发: {}", fluidId);
        List<Component> tooltip = event.getToolTip();
        for (String line : lines) {
            try {
                tooltip.add(ShanhaiTextAPI.inline(line));
            } catch (Exception e) {
                KubeJS.LOGGER.warn("[山海流体] inline 渲染失败: {}", e.toString());
                tooltip.add(Component.literal("§7" + line));
            }
        }
    }
}
