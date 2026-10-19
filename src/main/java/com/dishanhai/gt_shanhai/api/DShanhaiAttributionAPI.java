package com.dishanhai.gt_shanhai.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海归属标记 API —— 统一给物品/方块物品 tooltip 追加「由 GT-shanhai 添加 / 修改」。
 *
 * <p>规则（按优先级）：
 * <ol>
 *   <li>显式 {@link #markModified(String)} 的物品 → 由 GT-shanhai 修改</li>
 *   <li>显式 {@link #markAdded(String)} 的物品 → 由 GT-shanhai 添加</li>
 *   <li>命名空间为 {@code gt_shanhai} 的物品/方块物品 → 由 GT-shanhai 添加（可关）</li>
 *   <li>本模组 tooltip API 注册过内容的外部物品 → 由 GT-shanhai 修改（可关）</li>
 * </ol>
 *
 * <p>标记文本使用元色板 {@code random}（别名 {@code rand} / {@code 随机}）逐字流动上色。
 * 颜色样式可通过 {@link #setStyle(String)} 切换。
 *
 * <p>KubeJS 用法：
 * <pre>
 * DShanhaiAttributionAPI.markModified('ae2:encoded_pattern');
 * DShanhaiAttributionAPI.markModified(['gtceu:assembly_line', 'gtceu:fusion_reactor']);
 * DShanhaiAttributionAPI.markModified('gtceu:*');   // 整个命名空间
 * DShanhaiAttributionAPI.setStyle('random');       // 随机 / rand 同义
 * </pre>
 */
public final class DShanhaiAttributionAPI {

    /** 本模组 ID */
    public static final String MOD_ID = "gt_shanhai";

    /** 语言键 */
    public static final String LANG_ADDED = "tooltip.gt_shanhai.attribution.added";
    public static final String LANG_MODIFIED = "tooltip.gt_shanhai.attribution.modified";

    /** 语言文件缺失时的兜底文本 */
    public static final String FALLBACK_ADDED = "由 GT-shanhai 添加";
    public static final String FALLBACK_MODIFIED = "由 GT-shanhai 修改";

    /** 默认样式：元色板「随机」 */
    public static final String DEFAULT_STYLE = "random";

    /** 归属模式 */
    private static final String MODE_ADDED = "added";
    private static final String MODE_MODIFIED = "modified";

    private static final Set<String> EXPLICIT_ADDED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EXPLICIT_MODIFIED = ConcurrentHashMap.newKeySet();

    /** 全局开关（关闭后不再自动追加任何归属行） */
    private static volatile boolean enabled = true;
    /** gt_shanhai 命名空间自动标记为「添加」 */
    private static volatile boolean autoNamespaceAdded = true;
    /** 本模组 tooltip API 注册过内容的外部物品自动标记为「修改」 */
    private static volatile boolean autoTooltipModified = true;
    /** 归属行颜色样式 */
    private static volatile String style = DEFAULT_STYLE;

    private static boolean initialized = false;

    private DShanhaiAttributionAPI() {}

    /** 客户端初始化（注册 tooltip 事件） */
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(DShanhaiAttributionAPI.class);
    }

    // =====================
    // 事件
    // =====================

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!enabled) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;

        String mode = resolveMode(stack);
        if (mode == null) return;

        List<Component> tooltip = event.getToolTip();
        if (containsAttribution(tooltip)) return;
        tooltip.add(createTag(mode));
    }

    /** 解析物品应显示的归属模式；null 表示不显示 */
    public static String resolveMode(ItemStack stack) {
        String itemId = itemIdOf(stack);
        if (itemId == null) return null;
        return resolveMode(itemId);
    }

    /** 解析物品 ID 应显示的归属模式；null 表示不显示 */
    public static String resolveMode(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        if (matches(EXPLICIT_MODIFIED, itemId)) return MODE_MODIFIED;
        if (matches(EXPLICIT_ADDED, itemId)) return MODE_ADDED;
        if (autoNamespaceAdded && MOD_ID.equals(namespaceOf(itemId))) return MODE_ADDED;
        if (autoTooltipModified && hasShanhaiTooltip(itemId)) return MODE_MODIFIED;
        return null;
    }

    // =====================
    // 标记注册
    // =====================

    /** 标记为「由 GT-shanhai 添加」，支持 {@code namespace:*} 通配 */
    public static void markAdded(String itemId) {
        if (itemId == null || itemId.isEmpty()) return;
        EXPLICIT_ADDED.add(itemId);
    }

    /** 批量标记为「添加」 */
    public static void markAdded(String[] itemIds) {
        if (itemIds == null) return;
        for (String id : itemIds) markAdded(id);
    }

    /** 标记为「由 GT-shanhai 修改」，支持 {@code namespace:*} 通配 */
    public static void markModified(String itemId) {
        if (itemId == null || itemId.isEmpty()) return;
        EXPLICIT_MODIFIED.add(itemId);
    }

    /** 批量标记为「修改」 */
    public static void markModified(String[] itemIds) {
        if (itemIds == null) return;
        for (String id : itemIds) markModified(id);
    }

    /** 取消「添加」标记 */
    public static void unmarkAdded(String itemId) {
        if (itemId == null) return;
        EXPLICIT_ADDED.remove(itemId);
    }

    /** 取消「修改」标记 */
    public static void unmarkModified(String itemId) {
        if (itemId == null) return;
        EXPLICIT_MODIFIED.remove(itemId);
    }

    /** 清空所有显式标记（不影响自动规则） */
    public static void clear() {
        EXPLICIT_ADDED.clear();
        EXPLICIT_MODIFIED.clear();
    }

    // =====================
    // 文本 / 样式
    // =====================

    /** 「由 GT-shanhai 添加」随机色文本 */
    public static Component addedTag() {
        return createTag(MODE_ADDED);
    }

    /** 「由 GT-shanhai 修改」随机色文本 */
    public static Component modifiedTag() {
        return createTag(MODE_MODIFIED);
    }

    /** 「由 GT-shanhai 添加」纯文本（跟随客户端语言） */
    public static String addedText() {
        return localized(LANG_ADDED, FALLBACK_ADDED);
    }

    /** 「由 GT-shanhai 修改」纯文本（跟随客户端语言） */
    public static String modifiedText() {
        return localized(LANG_MODIFIED, FALLBACK_MODIFIED);
    }

    /** 按样式渲染任意归属文本，默认 random */
    public static Component styledTag(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return ShanhaiTextAPI.styled(text, style);
    }

    /** 当前归属行样式名 */
    public static String getStyle() {
        return style;
    }

    /** 切换归属行样式（random / rand / 随机 均为随机元色板） */
    public static void setStyle(String styleName) {
        style = (styleName == null || styleName.trim().isEmpty()) ? DEFAULT_STYLE : styleName.trim();
    }

    // =====================
    // 开关
    // =====================

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isAutoNamespaceAdded() {
        return autoNamespaceAdded;
    }

    public static void setAutoNamespaceAdded(boolean value) {
        autoNamespaceAdded = value;
    }

    public static boolean isAutoTooltipModified() {
        return autoTooltipModified;
    }

    public static void setAutoTooltipModified(boolean value) {
        autoTooltipModified = value;
    }

    // =====================
    // 内部
    // =====================

    private static Component createTag(String mode) {
        return styledTag(MODE_ADDED.equals(mode) ? addedText() : modifiedText());
    }

    private static boolean containsAttribution(List<Component> tooltip) {
        if (tooltip == null || tooltip.isEmpty()) return false;
        String added = addedText();
        String modified = modifiedText();
        for (Component line : tooltip) {
            if (line == null) continue;
            String plain = line.getString();
            if (added.equals(plain) || modified.equals(plain)) return true;
        }
        return false;
    }

    private static boolean hasShanhaiTooltip(String itemId) {
        if (itemId == null || itemId.isEmpty()) return false;
        try {
            if (DShanhaiItemTooltipAPI.hasTooltip(itemId)) return true;
            if (TooltipEffectRegistry.has(itemId)) return true;
        } catch (Throwable ignored) {
            // tooltip API 尚未就绪时不影响归属判断
        }
        return false;
    }

    private static boolean matches(Set<String> set, String itemId) {
        if (set.isEmpty()) return false;
        if (set.contains(itemId)) return true;
        for (String entry : set) {
            if (entry.endsWith(":*") && itemId.startsWith(entry.substring(0, entry.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static String itemIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }

    private static String namespaceOf(String itemId) {
        int colon = itemId.indexOf(':');
        return colon < 0 ? "minecraft" : itemId.substring(0, colon);
    }

    private static String localized(String key, String fallback) {
        try {
            String text = Component.translatable(key).getString();
            return (text == null || text.isEmpty() || key.equals(text)) ? fallback : text;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
