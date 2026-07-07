package com.dishanhai.gt_shanhai.common.misc;

import com.dishanhai.gt_shanhai.api.DShanhaiStyleRegistry;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.ShanhaiTextAPI;
import com.dishanhai.gt_shanhai.api.TextFormatParser;
import com.dishanhai.gt_shanhai.api.TooltipEffectLine;
import com.dishanhai.gt_shanhai.api.TooltipEffectRegistry;
import com.dishanhai.gt_shanhai.api.TooltipEffectRegistry.TooltipEffectConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通用 tooltip 逐字揭示 + wobble 处理器。
 * 读取 {@link TooltipEffectRegistry} 中注册的配置，
 * KubeJS 通过 {@link com.dishanhai.gt_shanhai.api.TooltipEffectAPI} 注册物品。
 */
public class TooltipEffectHandler {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai.tooltip");

    private static final Map<UUID, Long> PRESS_TIME = new HashMap<>();
    /** 被 DShanhaiItemTooltipAPI 标记为暂时禁用的物品（shift 未按住时） */
    public static final java.util.Set<String> SUPPRESSED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(new TooltipEffectHandler());
    }

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;

        String itemId = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        TooltipEffectConfig cfg = TooltipEffectRegistry.get(itemId);
        if (cfg == null) return;

        Player player = event.getEntity();
        if (player == null) return;

        // 被 DShanhaiItemTooltipAPI 暂时禁用（shift 未满足条件时）
        if (SUPPRESSED.contains(itemId)) return;

        List<TooltipEffectLine> lines = cfg.lines;
        if (lines == null || lines.isEmpty()) return;

        var tooltip = event.getToolTip();
        UUID uuid = player.getUUID();

        // ===== 计算总长度（清理后文本）& 总时间 =====
        int totalChars = 0;
        for (TooltipEffectLine l : lines) {
            totalChars += l.text.replaceAll("\\{[^}]+\\}|\\{/\\}", "").trim().length();
        }
        int msPerChar = Math.max(20, (int) (cfg.revealSpeedMs / (float) totalChars));

        boolean keyHeld = cfg.shiftOnly ? net.minecraft.client.gui.screens.Screen.hasShiftDown()
                                        : net.minecraft.client.gui.screens.Screen.hasAltDown();

        if (keyHeld) {
            long now = System.currentTimeMillis();
            PRESS_TIME.putIfAbsent(uuid, now);
            long elapsed = now - PRESS_TIME.get(uuid);
            int revealed = (int) (elapsed / msPerChar);

            // 注册 CENTER_TARGETS（用清理后文本，剥离 {style} 标记）
            DShanhaiTextUtil.CENTER_TARGETS.get().clear();
            for (TooltipEffectLine l : lines) {
                String clean = l.text.replaceAll("\\{[^}]+\\}|\\{/\\}", "").trim();
                DShanhaiTextUtil.CENTER_TARGETS.get().add(clean);
            }

            if (revealed >= totalChars) {
                DShanhaiTextUtil.WOBBLE_OFFSETS.set(new float[]{1.2f});
                // WOBBLE_FILTER: 只匹配有效果标记的行
                DShanhaiTextUtil.WOBBLE_FILTER.set(s -> DShanhaiTextUtil.WOBBLE_STYLE_MAP.get().containsKey(s));
                // WOBBLE_STYLE_MAP: 只存有效果标记的行
                DShanhaiTextUtil.WOBBLE_STYLE_MAP.get().clear();
                for (TooltipEffectLine l : lines) {
                    if (hasEffectMarkers(l.wobbleStyle)) {
                        String clean = l.text.replaceAll("\\{[^}]+\\}|\\{/\\}", "").trim();
                        DShanhaiTextUtil.WOBBLE_STYLE_MAP.get().put(clean, l.wobbleStyle);
                    }
                }
                        tooltip.add(Component.literal(""));
                for (TooltipEffectLine l : lines) {
                    String clean = l.text.replaceAll("\\{[^}]+\\}|\\{/\\}", "").trim();
                    if (hasEffectMarkers(l.wobbleStyle)) {
                        if (l.text.contains("{/}")) {
                            // 行内 {style} 标记：逐段染色 Component + 逐字效果映射
                            var segs = parseSegments(l.text);
                            StringBuilder effBuf = new StringBuilder();
                            var textComp = net.minecraft.network.chat.Component.literal("");
                            for (Segment seg : segs) {
                                String segText = seg.text.trim();
                                if (segText.isEmpty()) continue;
                                StringBuilder palName = new StringBuilder();
                                char effChar = '_';
                                for (char sc : seg.styleName.toCharArray()) {
                                    if ("%~*@!#".indexOf(sc) >= 0) effChar = sc;
                                    else palName.append(sc);
                                }
                                int[] pal = DShanhaiStyleRegistry.getRGB(palName.toString());
                                if (pal == null) pal = DShanhaiStyleRegistry.getRGB("ultimate");
                                for (int ci = 0; ci < segText.length(); ci++) {
                                    int col = DShanhaiTextUtil.getDynamicColor(pal, ci, segText.length(), false);
                                    textComp.append(net.minecraft.network.chat.Component.literal(String.valueOf(segText.charAt(ci)))
                                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.network.chat.TextColor.fromRgb(col))));
                                    effBuf.append(effChar);
                                }
                            }
                            DShanhaiTextUtil.PER_CHAR_EFFECTS.get().put(clean, effBuf.toString());
                            tooltip.add(textComp);
                        } else {
                            // 效果行：纯文本，Section 6 处理偏移+颜色
                            tooltip.add(Component.literal(clean));
                        }
                    } else {
                        // 普通行：逐字染色 Component，Section 3 居中+原版渲染
                        tooltip.add(DShanhaiTextUtil.createRevealText(clean, clean.length(), l.revealStyle, 0));
                    }
                }
                        tooltip.add(Component.literal(""));
            } else {
                // === 展开中（全局混淆：仅最后 obfuscateCount 字混淆，逐行分布） ===
                        tooltip.add(Component.literal(""));
                int remaining = revealed;
                int obfuscationGlobalStart = Math.max(0, revealed - cfg.obfuscateCount);
                for (TooltipEffectLine l : lines) {
                    String clean = l.text.replaceAll("\\{[^}]+\\}|\\{/\\}", "").trim();
                    int show = Math.min(remaining, clean.length());
                    int lineGlobalStart = revealed - remaining;
                    int overlapStart = Math.max(lineGlobalStart, obfuscationGlobalStart);
                    int overlapEnd = Math.min(lineGlobalStart + show, revealed);
                    int localObfuscated = Math.max(0, overlapEnd - overlapStart);
                    // obfuscateCompletedLines: 已完成的行保持末尾混淆
                    if (cfg.obfuscateCompletedLines && show >= clean.length()) {
                        localObfuscated = clean.length();
                    }
                        tooltip.add(DShanhaiTextUtil.createRevealText(clean, show, l.revealStyle, localObfuscated));
                    remaining = Math.max(0, remaining - clean.length());
                }
                        tooltip.add(Component.literal(""));
            }

            // 计算容器宽度
            calcContainerWidth(tooltip);
        } else {
            PRESS_TIME.remove(uuid);
            DShanhaiTextUtil.CENTER_TARGETS.get().clear();
            DShanhaiTextUtil.CENTER_CONTAINER_WIDTH.set(0);
            DShanhaiTextUtil.WOBBLE_OFFSETS.set(null);
            DShanhaiTextUtil.WOBBLE_FILTER.set(null);
            DShanhaiTextUtil.WOBBLE_STYLE.set(null);
            DShanhaiTextUtil.WOBBLE_STYLE_MAP.get().clear();
            DShanhaiTextUtil.PER_CHAR_EFFECTS.get().clear();
            String hint = cfg.hintText;
            if (cfg.shiftOnly) hint = hint.replace("ALT", "SHIFT");
            tooltip.add(Component.literal(hint));
        }
    }

    private static class Segment {
        final String styleName;
        final String text;
        Segment(String s, String t) { styleName = s; text = t; }
    }

    private static java.util.List<Segment> parseSegments(String text) {
        java.util.List<Segment> segs = new java.util.ArrayList<>();
        int i = 0;
        String curStyle = null;
        StringBuilder buf = new StringBuilder();
        while (i < text.length()) {
            if (text.charAt(i) == '{') {
                int end = text.indexOf('}', i);
                if (end < 0) break;
                String tag = text.substring(i + 1, end);
                if (tag.equals("/")) {
                    if (buf.length() > 0 && curStyle != null) {
                        segs.add(new Segment(curStyle, buf.toString()));
                        buf = new StringBuilder();
                    }
                    curStyle = null;
                } else {
                    if (buf.length() > 0 && curStyle != null) {
                        segs.add(new Segment(curStyle, buf.toString()));
                        buf = new StringBuilder();
                    }
                    curStyle = tag;
                }
                i = end + 1;
            } else {
                buf.append(text.charAt(i));
                i++;
            }
        }
        if (buf.length() > 0 && curStyle != null) {
            segs.add(new Segment(curStyle, buf.toString()));
        }
        return segs;
    }

    private static boolean hasEffectMarkers(String style) {
        return style != null && (style.contains("%") || style.contains("~") || style.contains("*") || style.contains("@") || style.contains("!"));
    }

    private static void calcContainerWidth(List<Component> tooltip) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.font != null) {
                int maxW = 0;
                for (Component c : tooltip) {
                    String t = c.getString();
                    if (t != null) maxW = Math.max(maxW, mc.font.width(t));
                }
                DShanhaiTextUtil.CENTER_CONTAINER_WIDTH.set(maxW);
            }
        } catch (Exception ignored) {}
    }
}
