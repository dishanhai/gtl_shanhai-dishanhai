package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** 山海：卡死告警的完整資料，與管理終端的短廣播文字分開保存。 */
public final class StellarPatternStuckReport {
    private static final String PREFIX = "gui.gt_shanhai.ultimate_terminal.report.";

    private StellarPatternStuckReport() {}

    /** 來源為實際主機狀態，不能從已翻譯的長診斷字串猜測等級。 */
    public enum Cause {
        STRONG_MATCH("strong_match", "error", 0xF07878),
        COIN_MINT_LOCKED("coin_mint_locked", "error", 0xF07878),
        NO_HOST("no_host", "error", 0xF07878),
        HOST_FAILURE("host_failure", "error", 0xF07878),
        UNCONSUMED("unconsumed", "warning", 0xF0C878),
        PAUSED("paused", "info", 0x78C8F0);

        private final String key, level;
        private final int color;

        Cause(String key, String level, int color) {
            this.key = key;
            this.level = level;
            this.color = color;
        }

        public int color() { return color; }
        public Component summary() { return Component.translatable(PREFIX + "reason_" + key); }
        public Component label() {
            return Component.translatable(PREFIX + "severity_" + level).append(" · ")
                    .append(summary());
        }
    }

    public record Entry(int slot, String slotLabel, String reason, String inputs, String outputs,
                        List<String> hostRecipeTypes, String patternRecipeTypeId, String patternRecipeId, Cause cause) {
        public Entry(int slot, String slotLabel, String reason, String inputs, String outputs,
                     List<String> hostRecipeTypes, String patternRecipeTypeId, String patternRecipeId) {
            this(slot, slotLabel, reason, inputs, outputs, hostRecipeTypes, patternRecipeTypeId, patternRecipeId,
                    Cause.UNCONSUMED);
        }

        public Entry {
            slotLabel = slotLabel == null ? "" : slotLabel;
            reason = value(reason);
            inputs = value(inputs);
            outputs = value(outputs);
            hostRecipeTypes = hostRecipeTypes == null ? List.of() : List.copyOf(hostRecipeTypes);
            patternRecipeTypeId = value(patternRecipeTypeId);
            patternRecipeId = value(patternRecipeId);
            cause = cause == null ? Cause.UNCONSUMED : cause;
        }

        public String displaySlot() {
            return slotLabel.isBlank() ? Integer.toString(slot + 1) : slotLabel;
        }
    }

    public static ShanhaiTerminalReport build(ResourceLocation dimension, BlockPos pos, Collection<Entry> entries) {
        ShanhaiTerminalReport.Builder report = new ShanhaiTerminalReport.Builder(
                ShanhaiTerminalReport.Type.STELLAR_STUCK, pos, entries.size());
        String target = dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        Component coordinates = action(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                "/shanhai stellar_tp " + target, "teleport_hint");
        report.summary(action(Component.translatable(PREFIX + "target_dimension", dimension.toString(), coordinates),
                "/shanhai stellar_tp " + target, "teleport_hint"));
        for (Entry entry : entries.stream().sorted(Comparator.comparingInt((Entry e) -> e.cause().ordinal())
                .thenComparingInt(Entry::slot)).toList()) {
            Component name = Component.translatable(PREFIX + "slot_summary", entry.displaySlot(),
                    entry.cause().summary());
            name = action(name, "/shanhai stellar_slot " + target + " " + entry.slot(), "slot_open_hint");
            Component details = Component.translatable(PREFIX + "detail_stellar",
                    entry.displaySlot(), dimension.toString(), coordinates, entry.reason(),
                    entry.patternRecipeId(), recipeTypeName(entry.patternRecipeTypeId()), entry.inputs(), entry.outputs(),
                    recipeTypeNames(entry.hostRecipeTypes()));
            report.add(pos, "stellar_" + entry.cause().name(), entry.cause().label(), entry.cause().color(),
                    name, List.of(), details);
        }
        return report.build();
    }

    private static Component action(Component text, String command, String hint) {
        return text.copy().withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(PREFIX + hint))));
    }

    /** 保留翻譯元件，讓客戶端依目前語言解析，伺服器不先轉成英文或翻譯鍵。 */
    public static Component recipeTypeName(String id) {
        ResourceLocation type = ResourceLocation.tryParse(id);
        if (type == null) return Component.literal(value(id));
        String path = type.getPath();
        String key = "coin_forge".equals(path) ? PREFIX + "recipe_type_name.coin_forge" : "gtceu." + path;
        return Component.translatableWithFallback(key, path).append(" (" + path + ")")
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal(id))));
    }

    private static Component recipeTypeNames(List<String> ids) {
        var names = Component.empty();
        for (String id : ids) {
            if (!names.getSiblings().isEmpty()) names.append(", ");
            names.append(recipeTypeName(id));
        }
        return ids.isEmpty() ? Component.literal("<none>") : names;
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "<none>" : text;
    }
}
