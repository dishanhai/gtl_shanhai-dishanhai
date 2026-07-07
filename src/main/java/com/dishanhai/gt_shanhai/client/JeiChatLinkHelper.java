package com.dishanhai.gt_shanhai.client;

import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JeiChatLinkHelper {
    public static final String COMMAND = "/gtshanhai_jei_search ";
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]\\r\\n]{1,80})\\]\\s+([a-z0-9_.-]+:[a-z0-9_./-]+)");

    private JeiChatLinkHelper() {}

    public static Component makeLinks(Component message) {
        if (message == null) return null;

        String text = message.getString();
        Matcher matcher = LINK_PATTERN.matcher(text);
        if (!matcher.find()) return message;

        MutableComponent result = Component.empty();
        int lastEnd = 0;
        do {
            if (matcher.start() > lastEnd) {
                result.append(Component.literal(text.substring(lastEnd, matcher.start())));
            }

            String name = matcher.group(1);
            String id = matcher.group(2);
            String searchText = normalizeSearchText(id);
            result.append(Component.literal("[" + name + "]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withInsertion(id)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, COMMAND + searchText))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("点击在 JEI 搜索: " + searchText).withStyle(ChatFormatting.YELLOW)))))
                    .append(Component.literal(" " + id));
            lastEnd = matcher.end();
        } while (matcher.find());

        if (lastEnd < text.length()) {
            result.append(Component.literal(text.substring(lastEnd)));
        }
        return result;
    }

    public static boolean runSearchCommand(String command) {
        if (command == null || !command.startsWith(COMMAND)) return false;

        String searchText = normalizeSearchText(command.substring(COMMAND.length()).trim());
        if (searchText.isEmpty()) return true;

        IJeiRuntime runtime = ShanhaiJEIPlugin.getRuntime();
        if (runtime == null) {
            showMessage(Component.literal("[山海JEI] JEI 还未就绪，无法打开搜索").withStyle(ChatFormatting.RED));
            return true;
        }

        runtime.getIngredientFilter().setFilterText(searchText);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ChatScreen) {
            mc.setScreen(null);
        }
        showMessage(Component.literal("[山海JEI] 已搜索: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(searchText).withStyle(ChatFormatting.YELLOW)));
        return true;
    }

    static String normalizeSearchText(String searchText) {
        if (searchText == null) return "";

        String trimmed = searchText.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.startsWith("*") || trimmed.startsWith("#")) return trimmed;
        return ResourceLocation.tryParse(trimmed) == null ? trimmed : "*" + trimmed;
    }

    private static void showMessage(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(message, true);
    }
}
