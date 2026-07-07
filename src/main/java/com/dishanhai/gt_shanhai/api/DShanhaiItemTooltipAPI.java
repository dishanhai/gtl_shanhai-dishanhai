package com.dishanhai.gt_shanhai.api;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import dev.latvian.mods.kubejs.KubeJS;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DShanhaiItemTooltipAPI {

    private static boolean initialized = false;

    private static class KeyTooltipEntry {
        final String keyMode;
        final List<String> lines;
        final String hintText;
        final List<String> hintLines;

        KeyTooltipEntry(String keyMode, String[] lines, String hintText) {
            this(keyMode, lines, hintText != null && !hintText.isEmpty() ? new String[]{hintText} : null);
        }

        KeyTooltipEntry(String keyMode, String[] lines, String[] hintLines) {
            this.keyMode = keyMode;
            this.lines = new ArrayList<>(Arrays.asList(lines != null ? lines : new String[0]));
            this.hintText = hintLines != null && hintLines.length > 0 ? hintLines[0] : null;
            this.hintLines = new ArrayList<>(Arrays.asList(hintLines != null ? hintLines : new String[0]));
        }
    }

    private static final Map<String, List<String>> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, List<KeyTooltipEntry>> KEY_ENTRIES = new ConcurrentHashMap<>();
    /** 标记哪些物品需要按住 SHIFT 才显示 */
    private static final Map<String, Boolean> SHIFT_ONLY = new ConcurrentHashMap<>();
    private static final Map<String, String> KEY_MODE = new ConcurrentHashMap<>();
    private static final Map<String, String> HINT_LINES = new ConcurrentHashMap<>();

    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(DShanhaiItemTooltipAPI.class);
        KubeJS.LOGGER.info("[山海物品] DShanhaiItemTooltipAPI tooltip 事件已注册");
    }

    // ====== 简单注册（inline 渲染） ======

    /**
     * 注册物品 tooltip 行（每行可用 {style} 行内标记）。
     * <pre>
     * DShanhaiItemTooltipAPI.register("dishanhai:wl_board_ulv", [
     *   "{golden}品名{/} {gray}ULV{/}",
     *   "{bodySilver}诗意描述{/}"
     * ]);
     * </pre>
     */
    public static void register(String itemId, String[] lines) {
        register(itemId, lines, false);
    }

    /**
     * 注册物品 tooltip，可选 shiftOnly。
     * shiftOnly=true 时，未按住 SHIFT 时不显示，按住后才显示。
     */
    public static void register(String itemId, String[] lines, boolean shiftOnly) {
        ENTRIES.put(itemId, new ArrayList<>(Arrays.asList(lines)));
        KEY_ENTRIES.remove(itemId);
        SHIFT_ONLY.put(itemId, shiftOnly);
        KEY_MODE.put(itemId, shiftOnly ? "shift" : "none");
        KubeJS.LOGGER.info("[山海物品] 注册 tooltip: {} → {} 行 shiftOnly={}",
                itemId, lines != null ? lines.length : 0, shiftOnly);
    }

    public static void registerAlt(String itemId, String[] lines, String hintText) {
        registerKey(itemId, "alt", lines, hintText);
    }

    public static void registerShift(String itemId, String[] lines, String hintText) {
        registerKey(itemId, "shift", lines, hintText);
    }

    public static void registerCtrl(String itemId, String[] lines, String hintText) {
        registerKey(itemId, "ctrl", lines, hintText);
    }

    public static void registerAltLines(String itemId, String[] lines, String[] hintLines) {
        registerKeyLines(itemId, "alt", lines, hintLines);
    }

    public static void registerAltLines(String itemId, String[] lines, String hintText) {
        registerKey(itemId, "alt", lines, hintText);
    }

    public static void registerShiftLines(String itemId, String[] lines, String[] hintLines) {
        registerKeyLines(itemId, "shift", lines, hintLines);
    }

    public static void registerShiftLines(String itemId, String[] lines, String hintText) {
        registerKey(itemId, "shift", lines, hintText);
    }

    public static void registerCtrlLines(String itemId, String[] lines, String[] hintLines) {
        registerKeyLines(itemId, "ctrl", lines, hintLines);
    }

    public static void registerCtrlLines(String itemId, String[] lines, String hintText) {
        registerKey(itemId, "ctrl", lines, hintText);
    }

    public static void registerKeyLines(String itemId, String key, String[] lines, String[] hintLines) {
        if (itemId == null || itemId.isEmpty()) return;
        String keyMode = normalizeKey(key);
        ENTRIES.remove(itemId);
        replaceAllKeyEntries(itemId, new KeyTooltipEntry(keyMode, lines, hintLines));
        SHIFT_ONLY.put(itemId, "shift".equals(keyMode));
        KEY_MODE.put(itemId, keyMode);
        if (hintLines != null && hintLines.length > 0 && hintLines[0] != null && !hintLines[0].isEmpty()) {
            HINT_LINES.put(itemId, hintLines[0]);
        } else {
            HINT_LINES.remove(itemId);
        }
        KubeJS.LOGGER.info("[山海物品] 注册 key tooltip lines: {} → {} 行 key={}",
                itemId, lines != null ? lines.length : 0, keyMode);
    }

    public static void registerAltMany(String[] itemIds, String[] lines, String hintText) {
        registerKeyMany(itemIds, "alt", lines, hintText);
    }

    public static void registerAltLines(String[] itemIds, String[] lines, String[] hintLines) {
        registerKeyLinesMany(itemIds, "alt", lines, hintLines);
    }

    public static void registerAltLines(String[] itemIds, String[] lines, String hintText) {
        registerKeyMany(itemIds, "alt", lines, hintText);
    }

    public static void registerShiftMany(String[] itemIds, String[] lines, String hintText) {
        registerKeyMany(itemIds, "shift", lines, hintText);
    }

    public static void registerShiftLines(String[] itemIds, String[] lines, String[] hintLines) {
        registerKeyLinesMany(itemIds, "shift", lines, hintLines);
    }

    public static void registerShiftLines(String[] itemIds, String[] lines, String hintText) {
        registerKeyMany(itemIds, "shift", lines, hintText);
    }

    public static void registerCtrlMany(String[] itemIds, String[] lines, String hintText) {
        registerKeyMany(itemIds, "ctrl", lines, hintText);
    }

    public static void registerCtrlLines(String[] itemIds, String[] lines, String[] hintLines) {
        registerKeyLinesMany(itemIds, "ctrl", lines, hintLines);
    }

    public static void registerCtrlLines(String[] itemIds, String[] lines, String hintText) {
        registerKeyMany(itemIds, "ctrl", lines, hintText);
    }

    public static void registerKeyMany(String[] itemIds, String key, String[] lines, String hintText) {
        if (itemIds == null) return;
        for (String itemId : itemIds) {
            registerKey(itemId, key, lines, hintText);
        }
    }

    public static void registerKeyLinesMany(String[] itemIds, String key, String[] lines, String[] hintLines) {
        if (itemIds == null) return;
        for (String itemId : itemIds) {
            registerKeyLines(itemId, key, lines, hintLines);
        }
    }

    public static void registerKey(String itemId, String key, String[] lines, String hintText) {
        if (itemId == null || itemId.isEmpty()) return;
        String keyMode = normalizeKey(key);
        ENTRIES.remove(itemId);
        replaceAllKeyEntries(itemId, new KeyTooltipEntry(keyMode, lines, hintText));
        SHIFT_ONLY.put(itemId, "shift".equals(keyMode));
        KEY_MODE.put(itemId, keyMode);
        if (hintText != null && !hintText.isEmpty()) {
            HINT_LINES.put(itemId, hintText);
        } else {
            HINT_LINES.remove(itemId);
        }
        KubeJS.LOGGER.info("[山海物品] 注册 key tooltip: {} → {} 行 key={}",
                itemId, lines != null ? lines.length : 0, keyMode);
    }

    private static void replaceAllKeyEntries(String itemId, KeyTooltipEntry nextEntry) {
        List<KeyTooltipEntry> next = new ArrayList<>();
        next.add(nextEntry);
        KEY_ENTRIES.put(itemId, next);
    }

    private static void replaceKeyEntry(String itemId, KeyTooltipEntry nextEntry) {
        KEY_ENTRIES.compute(itemId, (id, entries) -> {
            List<KeyTooltipEntry> next = new ArrayList<>();
            if (entries != null) {
                for (KeyTooltipEntry entry : entries) {
                    if (!entry.keyMode.equals(nextEntry.keyMode)) {
                        next.add(entry);
                    }
                }
            }
            next.add(nextEntry);
            return next;
        });
    }

    private static String normalizeKey(String key) {
        if (key == null) return "none";
        String normalized = key.trim().toLowerCase();
        if (normalized.equals("alt") || normalized.equals("shift") || normalized.equals("ctrl") || normalized.equals("control")) {
            return normalized.equals("control") ? "ctrl" : normalized;
        }
        return "none";
    }

    private static boolean isKeyDown(String keyMode) {
        if ("alt".equals(keyMode)) return Screen.hasAltDown();
        if ("shift".equals(keyMode)) return Screen.hasShiftDown();
        if ("ctrl".equals(keyMode)) return Screen.hasControlDown();
        return true;
    }

    private static String defaultHint(String keyMode) {
        if ("alt".equals(keyMode)) return "§8§o按住 §7ALT §8查看提示";
        if ("shift".equals(keyMode)) return "§8§o按住 §7SHIFT §8查看提示";
        if ("ctrl".equals(keyMode)) return "§8§o按住 §7CTRL §8查看提示";
        return "";
    }

    private static void addInline(List<Component> tooltip, String itemId, String line) {
        if (line == null) {
            return;
        }
        if (line.isEmpty()) {
            tooltip.add(Component.empty());
            return;
        }
        try {
            tooltip.add(ShanhaiTextAPI.inline(autoSpaceInlineText(line)));
        } catch (Exception e) {
            KubeJS.LOGGER.warn("[山海物品] inline 渲染失败: {} - {}", itemId, e.toString());
            tooltip.add(Component.literal("§7" + autoSpaceInlineText(line)));
        }
    }

    private static String autoSpaceInlineText(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder result = new StringBuilder(text.length() + 8);
        int previousVisibleType = 0;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '{') {
                int close = text.indexOf('}', index);
                if (close > index) {
                    result.append(text, index, close + 1);
                    index = close + 1;
                    continue;
                }
            }
            if (c == '§' && index + 1 < text.length()) {
                result.append(c).append(text.charAt(index + 1));
                index += 2;
                continue;
            }
            int currentType = visibleCharType(c);
            if (needsAutoSpace(previousVisibleType, currentType, result)) {
                result.append(' ');
            }
            result.append(c);
            if (currentType != 0) previousVisibleType = currentType;
            index++;
        }
        return result.toString();
    }

    private static boolean needsAutoSpace(int previousType, int currentType, StringBuilder result) {
        if (previousType == 0 || currentType == 0 || previousType == currentType) return false;
        if (result.length() == 0 || Character.isWhitespace(result.charAt(result.length() - 1))) return false;
        return (previousType == 1 && currentType == 2) || (previousType == 2 && currentType == 1);
    }

    private static int visibleCharType(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
            return 1;
        }
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            return 2;
        }
        return 0;
    }

    public static void registerLine(String itemId, String line) {
        ENTRIES.computeIfAbsent(itemId, k -> new ArrayList<>()).add(line);
        KubeJS.LOGGER.info("[山海物品] registerLine: {}", itemId);
    }

    // ====== 全参数注册（wobble 特效，等同 TooltipEffectAPI 参数模式） ======

    /**
     * 全参数注册：简单线条 + shiftOnly。
     * 等同 TooltipEffectAPI.register(itemId, lines, revealSpeedMs, obfuscateCount, wobbleStyles)
     * 但额外支持 shiftOnly。
     */
    public static void register(String itemId, String[] lines, boolean shiftOnly,
                                 long revealSpeedMs, int obfuscateCount, String[] wobbleStyles) {
        register(itemId, lines, shiftOnly, revealSpeedMs, obfuscateCount, wobbleStyles,
                "§7§o按住 §eALT §7查看空想寄语");
    }

    /**
     * 全参数注册 + 自定义提示文本。
     */
    public static void register(String itemId, String[] lines, boolean shiftOnly,
                                 long revealSpeedMs, int obfuscateCount, String[] wobbleStyles,
                                 String hintText) {
        register(itemId, lines, shiftOnly, revealSpeedMs, obfuscateCount, wobbleStyles, hintText, null);
    }

    /**
     * 全参数注册 + 提示文本 + 混淆配置。
     * 匹配 TooltipEffectAPI.register(itemId, lines, speed, count, styles, hint, obfu) 的完整签名，
     * 额外在前面加了 boolean shiftOnly。
     */
    public static void register(String itemId, String[] lines, boolean shiftOnly,
                                 long revealSpeedMs, int obfuscateCount, String[] wobbleStyles,
                                 String hintText, String obfuConfig) {
        SHIFT_ONLY.put(itemId, shiftOnly);
        // 直接创建 TooltipEffectConfig 并传入 shiftOnly（不经 TooltipEffectAPI，其不含 shiftOnly 参数）
        java.util.List<TooltipEffectLine> list = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String ws = (i < wobbleStyles.length) ? wobbleStyles[i] : "ultimate";
            String rs = ws;
            int di = ws.indexOf('$');
            if (di >= 0) rs = ws.substring(di + 1);
            for (String ec : new String[]{"%", "~", "*", "@", "!", "#"}) {
                while (rs.startsWith(ec)) rs = rs.substring(1);
            }
            list.add(new TooltipEffectLine(lines[i], rs.isEmpty() ? "body_silver" : rs, ws));
        }
        boolean obfu = obfuConfig != null && obfuConfig.equalsIgnoreCase("obfu:true");
        TooltipEffectRegistry.register(itemId,
                new TooltipEffectRegistry.TooltipEffectConfig(itemId, revealSpeedMs, obfuscateCount, list, hintText, obfu, shiftOnly));
        KubeJS.LOGGER.info("[山海物品] 注册 wobble tooltip: {} → {} 行 shiftOnly={}", itemId, lines.length, shiftOnly);
    }

    // ====== 管理 ======

    public static void remove(String itemId) {
        ENTRIES.remove(itemId);
        KEY_ENTRIES.remove(itemId);
        SHIFT_ONLY.remove(itemId);
        KEY_MODE.remove(itemId);
        HINT_LINES.remove(itemId);
        TooltipEffectRegistry.remove(itemId);
        KubeJS.LOGGER.info("[山海物品] remove: {}", itemId);
    }

    public static void clear() {
        ENTRIES.clear();
        KEY_ENTRIES.clear();
        SHIFT_ONLY.clear();
        KEY_MODE.clear();
        HINT_LINES.clear();
        KubeJS.LOGGER.info("[山海物品] clear");
    }

    public static String[] getEntryLines(String itemId) {
        List<String> lines = ENTRIES.get(itemId);
        return lines != null ? lines.toArray(new String[0]) : null;
    }

    public static String[] getRegisteredIds() {
        return ENTRIES.keySet().toArray(new String[0]);
    }

    // ====== 事件处理 ======

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;

        ResourceLocation registryName = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (registryName == null) return;
        String itemId = registryName.toString();

        boolean shiftOnly = SHIFT_ONLY.getOrDefault(itemId, false);
        String keyMode = KEY_MODE.getOrDefault(itemId, shiftOnly ? "shift" : "none");
        boolean keyDown = isKeyDown(keyMode);
        List<Component> tooltip = event.getToolTip();
        List<KeyTooltipEntry> keyEntries = KEY_ENTRIES.get(itemId);
        addPatternRecipeTypeTooltip(stack, tooltip, event.getEntity() == null ? null : event.getEntity().level());

        // ====== wobble 特效物品 ======
        boolean hasWobble = TooltipEffectRegistry.has(itemId);
        if (hasWobble) {
            if (!keyDown) {
                com.dishanhai.gt_shanhai.common.misc.TooltipEffectHandler.SUPPRESSED.add(itemId);
                var cfg = TooltipEffectRegistry.get(itemId);
                String hint = HINT_LINES.getOrDefault(itemId, cfg.hintText != null ? cfg.hintText : defaultHint(keyMode));
                addInline(event.getToolTip(), itemId, hint);
            } else {
                // shift 已按住 → 允许 wobble 渲染
                com.dishanhai.gt_shanhai.common.misc.TooltipEffectHandler.SUPPRESSED.remove(itemId);
            }
            // 有 ENTRIES 或 KEY_ENTRIES 数据时兼容显示 inline 文本（如 create_mk 同时有简单注册和 wobble 注册）
            if (!ENTRIES.containsKey(itemId) && (keyEntries == null || keyEntries.isEmpty())) return;
        }

        if (keyEntries != null && !keyEntries.isEmpty()) {
            boolean matched = false;
            for (KeyTooltipEntry entry : keyEntries) {
                if (isKeyDown(entry.keyMode)) {
                    matched = true;
                    for (String line : entry.lines) addInline(tooltip, itemId, line);
                }
            }
            if (!matched) {
                for (KeyTooltipEntry entry : keyEntries) {
                    if (!entry.hintLines.isEmpty()) {
                        for (String hintLine : entry.hintLines) {
                            addInline(tooltip, itemId, hintLine);
                        }
                    } else {
                        String hint = entry.hintText != null && !entry.hintText.isEmpty() ? entry.hintText : defaultHint(entry.keyMode);
                        if (!hint.isEmpty()) addInline(tooltip, itemId, hint);
                    }
                }
            }
            return;
        }

        // ====== 简单 inline 物品 ======
        List<String> lines = ENTRIES.get(itemId);
        if (lines == null || lines.isEmpty()) return;

        if (!keyDown) {
            String hint = HINT_LINES.getOrDefault(itemId, defaultHint(keyMode));
            if (!hint.isEmpty()) addInline(tooltip, itemId, hint);
            return;
        }

        for (String line : lines) {
            addInline(tooltip, itemId, line);
        }
    }

    private static void addPatternRecipeTypeTooltip(ItemStack stack, List<Component> tooltip, Level level) {
        String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(stack);
        if (recipeTypeId.isEmpty() && level != null) {
            recipeTypeId = PatternRecipeTypeHelper.ensureRecipeTypeId(stack, level);
        }
        if (recipeTypeId.isEmpty()) return;
        tooltip.add(Component.literal("§b配方类型：§f" + recipeTypeDisplayName(recipeTypeId))
                .append(Component.literal(" §8(" + recipeTypeId + ")")));
    }

    private static String recipeTypeDisplayName(String recipeTypeId) {
        GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(recipeTypeId);
        if (type == null || type.registryName == null) return recipeTypeId;
        for (String key : recipeTypeLanguageKeys(type)) {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) {
                return translated;
            }
        }
        return recipeTypeId;
    }

    private static String[] recipeTypeLanguageKeys(GTRecipeType type) {
        String namespace = type.registryName.getNamespace();
        String path = type.registryName.getPath();
        return new String[] {
                type.registryName.toLanguageKey(),
                "gtceu." + path,
                "gtceu.recipe_type." + path,
                "recipe_type." + path,
                "gtceu.recipe_type." + namespace + "." + path
        };
    }
}
