package com.dishanhai.gt_shanhai.jei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;

/**
 * 配方收藏排除规则。
 *
 * <p>只写物品 ID 时，该物品不论 NBT 都排除。写成 {@code 物品ID{SNBT}} 时，
 * 物品 ID 相同且物品 NBT 包含这些键值才排除。字符串值 {@code "*"} 表示该键存在且非空。
 * 也接受 {@code Item.of('物品ID', '{SNBT}')}。</p>
 */
public final class JeiBookmarkExclusions {

    public static final List<String> DEFAULTS = List.of(
            "gtceu:data_module",
            "gtceu:data_orb{assembly_line_research:{research_id:\"*\"}}",
            "gtceu:data_stick{assembly_line_research:{research_id:\"*\"}}");

    private JeiBookmarkExclusions() {}

    public record Rule(ResourceLocation itemId, CompoundTag nbt) {}

    public static List<Rule> parse(List<? extends String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<Rule> rules = new ArrayList<>();
        for (String line : lines) {
            Rule rule = parseLine(line);
            if (rule != null) rules.add(rule);
        }
        return rules;
    }

    public static Rule parseLine(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty() || line.charAt(0) == '#') return null;
        try {
            if (line.startsWith("Item.of(") && line.endsWith(")")) {
                return parseItemOf(line);
            }
            int brace = line.indexOf('{');
            if (brace < 0) {
                ResourceLocation id = ResourceLocation.tryParse(line);
                return id == null ? null : new Rule(id, null);
            }
            ResourceLocation id = ResourceLocation.tryParse(line.substring(0, brace).trim());
            if (id == null) return null;
            return new Rule(id, TagParser.parseTag(line.substring(brace)));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean matches(ResourceLocation itemId, CompoundTag itemTag, List<Rule> rules) {
        if (itemId == null || rules == null || rules.isEmpty()) return false;
        for (Rule rule : rules) {
            if (rule == null || !itemId.equals(rule.itemId())) continue;
            if (rule.nbt() == null || nbtContains(itemTag, rule.nbt())) return true;
        }
        return false;
    }

    private static Rule parseItemOf(String line) throws Exception {
        String body = line.substring("Item.of(".length(), line.length() - 1).trim();
        if (body.length() < 2) return null;
        char quote = body.charAt(0);
        if (quote != '\'' && quote != '"') return null;
        int idEnd = body.indexOf(quote, 1);
        if (idEnd <= 1) return null;
        ResourceLocation id = ResourceLocation.tryParse(body.substring(1, idEnd).trim());
        if (id == null) return null;
        String rest = body.substring(idEnd + 1).trim();
        if (rest.isEmpty()) return new Rule(id, null);
        if (rest.charAt(0) != ',') return null;
        rest = rest.substring(1).trim();
        if (rest.isEmpty()) return new Rule(id, null);
        char nbtQuote = rest.charAt(0);
        String snbt = rest;
        if ((nbtQuote == '\'' || nbtQuote == '"') && rest.charAt(rest.length() - 1) == nbtQuote) {
            snbt = rest.substring(1, rest.length() - 1);
        }
        return new Rule(id, TagParser.parseTag(snbt));
    }

    /** 配置里的复合标签按「包含」匹配：多出来的键不影响，写出的键必须相等。 */
    private static boolean nbtContains(CompoundTag actual, CompoundTag pattern) {
        if (pattern == null || pattern.isEmpty()) return actual != null;
        if (actual == null) return false;
        return valueContains(actual, pattern);
    }

    private static boolean valueContains(Tag actual, Tag pattern) {
        if (pattern instanceof StringTag text && "*".equals(text.getAsString())) {
            return actual instanceof StringTag value && !value.getAsString().isEmpty();
        }
        if (pattern instanceof CompoundTag patternCompound) {
            if (!(actual instanceof CompoundTag actualCompound)) return false;
            for (String key : patternCompound.getAllKeys()) {
                if (!actualCompound.contains(key)) return false;
                Tag child = actualCompound.get(key);
                Tag expected = patternCompound.get(key);
                if (child == null || expected == null || !valueContains(child, expected)) return false;
            }
            return true;
        }
        return pattern.equals(actual);
    }
}
