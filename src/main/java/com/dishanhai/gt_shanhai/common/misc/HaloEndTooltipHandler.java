package com.dishanhai.gt_shanhai.common.misc;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import com.dishanhai.gt_shanhai.api.DShanhaiStyleRegistry;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 终末之环 (halo_end) 逐字展开 Tooltip。
 *
 * 按住 Alt 时文字从左到右逐字出现，
 * 最新 N 字为 §k 混淆，已稳定的字用 body_silver 渐变色，
 * 全部展开后切换为 ultimateRainbow + body_cream 动态渐变。
 */
public class HaloEndTooltipHandler {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai.halo");
    private static final ResourceLocation TARGET = new ResourceLocation("dishanhai", "halo_end");
    private static final String LINE1 = "如果这就是一切的尽头，那么——";
    private static final String LINE2 = "这一宿命就让我画上止符。";
    private static final int TOTAL = LINE1.length() + LINE2.length();
    /** 总展开时间 ~2.5 秒，按文本长度自动分配每字速度 */
    private static final int MS_PER_CHAR = Math.max(20, 4000 / TOTAL);
    private static final int OBFUSCATED_TAIL = 2;

    private static final Map<UUID, Long> PRESS_TIME = new HashMap<>();

    private static boolean initialized = false;

    /** 手动注册到 Forge 事件总线 */
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(new HaloEndTooltipHandler());
    }

    /**
     * 在 tooltip 即将渲染时（所有 handler 执行完毕后）测量最大行宽。
     * 此时 tooltip 组件列表已完整。
     */
    @SubscribeEvent
    public void onRenderTooltipPre(net.minecraftforge.client.event.RenderTooltipEvent.Pre event) {
        if (!ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem()).equals(TARGET)) return;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.font != null) {
                int maxW = 0;
                for (var comp : event.getComponents()) {
                    int w = comp.getWidth(mc.font);
                    if (w > maxW) maxW = w;
                }
                if (maxW > 0) DShanhaiTextUtil.CENTER_CONTAINER_WIDTH.set(maxW);
                LOG.info("[Width] RenderTooltipPre maxW={}", maxW);
            }
        } catch (Exception ignored) {}
    }

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!ForgeRegistries.ITEMS.getKey(stack.getItem()).equals(TARGET)) return;

        Player player = event.getEntity();
        if (player == null) return;

        var tooltip = event.getToolTip();
        UUID uuid = player.getUUID();

        if (net.minecraft.client.gui.screens.Screen.hasAltDown()) {
            long now = System.currentTimeMillis();
            PRESS_TIME.putIfAbsent(uuid, now);
            long elapsed = now - PRESS_TIME.get(uuid);
            int revealed = (int)(elapsed / MS_PER_CHAR);

            // 设置居中目标行 + 全程启用 wobble（展开阶段即飘动）
            DShanhaiTextUtil.CENTER_TARGETS.get().clear();
            DShanhaiTextUtil.CENTER_TARGETS.get().add(LINE1);
            DShanhaiTextUtil.CENTER_TARGETS.get().add(LINE2);
            DShanhaiTextUtil.WOBBLE_OFFSETS.set(new float[1]);
            DShanhaiTextUtil.WOBBLE_FILTER.set(s -> s.startsWith("如果这就是") || s.startsWith("这一宿命"));

            if (revealed >= TOTAL) {
                tooltip.add(Component.literal(""));
                // &$style-格式让 mixin 独立解析每行动态色板
                tooltip.add(Component.literal("&$ultimate-" + LINE1));
                tooltip.add(Component.literal("&$body_cream-" + LINE2));
            } else {
                tooltip.add(Component.literal(""));
                int line1Show = Math.min(revealed, LINE1.length());
                int obfuscationStart = Math.max(0, revealed - OBFUSCATED_TAIL);
                int line1Overlap = Math.max(0, Math.min(line1Show, revealed) - Math.max(0, obfuscationStart));
                tooltip.add(buildLine(LINE1, line1Show, line1Overlap));
                int rem = Math.max(0, revealed - LINE1.length());
                int line2Show = Math.min(rem, LINE2.length());
                int line2Start = LINE1.length();
                int line2End = line2Start + line2Show;
                int line2Overlap = Math.max(0, Math.min(line2End, revealed) - Math.max(line2Start, obfuscationStart));
                tooltip.add(buildLine(LINE2, line2Show, line2Overlap));
            }

        } else {
            PRESS_TIME.remove(uuid);
            DShanhaiTextUtil.CENTER_TARGETS.get().clear();
            DShanhaiTextUtil.CENTER_CONTAINER_WIDTH.set(0);
            DShanhaiTextUtil.WOBBLE_OFFSETS.set(null);
            DShanhaiTextUtil.WOBBLE_FILTER.set(null);
            DShanhaiTextUtil.WOBBLE_STYLE.set(null);
            tooltip.add(Component.literal("§7§o按住 §eALT §7查看终末真相"));
        }
    }

    private static Component buildLine(String text, int revealed, int obfuscatedTail) {
        return DShanhaiTextUtil.createRevealText(text, revealed, "body_silver", obfuscatedTail);
    }
}
