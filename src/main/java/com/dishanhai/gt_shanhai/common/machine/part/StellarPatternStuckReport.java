package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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

    /** 診斷在來源就分欄，顯示時不能再從分號串起來的摘要猜測欄位。 */
    public record Diagnosis(String hostStatus, String minting, String parallel) {
        public static final Diagnosis EMPTY = new Diagnosis("", "", "");
        public Diagnosis {
            hostStatus = hostStatus == null ? "" : hostStatus;
            minting = minting == null ? "" : minting;
            parallel = parallel == null ? "" : parallel;
        }
    }

    public record Entry(int slot, String slotLabel, String reason, String inputs, String outputs,
                        List<String> hostRecipeTypes, String patternRecipeTypeId, String patternRecipeId, Cause cause,
                        Diagnosis diagnosis, StellarPatternOrderInfo order) {
        public Entry(int slot, String slotLabel, String reason, String inputs, String outputs,
                     List<String> hostRecipeTypes, String patternRecipeTypeId, String patternRecipeId, Cause cause, Diagnosis diagnosis) {
            this(slot, slotLabel, reason, inputs, outputs, hostRecipeTypes, patternRecipeTypeId, patternRecipeId, cause,
                    diagnosis, StellarPatternOrderInfo.UNKNOWN);
        }
        public Entry(int slot, String slotLabel, String reason, String inputs, String outputs,
                     List<String> hostRecipeTypes, String patternRecipeTypeId, String patternRecipeId) {
            this(slot, slotLabel, reason, inputs, outputs, hostRecipeTypes, patternRecipeTypeId, patternRecipeId,
                    Cause.UNCONSUMED, Diagnosis.EMPTY);
        }

        public Entry(int slot, String slotLabel, String reason, String inputs, String outputs,
                     List<String> hostRecipeTypes, String patternRecipeTypeId, String patternRecipeId, Cause cause) {
            this(slot, slotLabel, reason, inputs, outputs, hostRecipeTypes, patternRecipeTypeId, patternRecipeId,
                    cause, Diagnosis.EMPTY);
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
            diagnosis = diagnosis == null ? Diagnosis.EMPTY : diagnosis;
            order = order == null ? StellarPatternOrderInfo.UNKNOWN : order;
        }

        public String displaySlot() {
            return slotLabel.isBlank() ? Integer.toString(slot + 1) : slotLabel;
        }

        public Entry withOrder(StellarPatternOrderInfo order) {
            return new Entry(slot, slotLabel, reason, inputs, outputs, hostRecipeTypes, patternRecipeTypeId, patternRecipeId, cause, diagnosis, order);
        }

        public net.minecraft.nbt.CompoundTag save() {
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putInt("slot", slot); StellarPatternHistoryText.put(tag, "label", slotLabel);
            StellarPatternHistoryText.put(tag, "reason", reason); StellarPatternHistoryText.put(tag, "inputs", inputs);
            StellarPatternHistoryText.put(tag, "outputs", outputs); StellarPatternHistoryText.put(tag, "type", patternRecipeTypeId);
            StellarPatternHistoryText.put(tag, "recipe", patternRecipeId); tag.putString("cause", cause.name());
            var types = new net.minecraft.nbt.ListTag();
            hostRecipeTypes.forEach(type -> types.add(net.minecraft.nbt.StringTag.valueOf(type))); tag.put("hosts", types);
            StellarPatternHistoryText.put(tag, "status", diagnosis.hostStatus()); StellarPatternHistoryText.put(tag, "mint", diagnosis.minting());
            StellarPatternHistoryText.put(tag, "parallel", diagnosis.parallel());
            tag.put("order", order.save()); return tag;
        }

        public static Entry load(net.minecraft.nbt.CompoundTag tag) {
            Cause cause;
            try { cause = Cause.valueOf(tag.getString("cause")); } catch (IllegalArgumentException ignored) { cause = Cause.UNCONSUMED; }
            var types = tag.getList("hosts", net.minecraft.nbt.Tag.TAG_STRING);
            var hosts = new java.util.ArrayList<String>();
            for (int i = 0; i < types.size(); i++) hosts.add(types.getString(i));
            return new Entry(tag.getInt("slot"), StellarPatternHistoryText.get(tag, "label"), StellarPatternHistoryText.get(tag, "reason"),
                    StellarPatternHistoryText.get(tag, "inputs"), StellarPatternHistoryText.get(tag, "outputs"), hosts,
                    StellarPatternHistoryText.get(tag, "type"), StellarPatternHistoryText.get(tag, "recipe"), cause,
                    new Diagnosis(StellarPatternHistoryText.get(tag, "status"), StellarPatternHistoryText.get(tag, "mint"), StellarPatternHistoryText.get(tag, "parallel")),
                    StellarPatternOrderInfo.load(tag.getCompound("order")));
        }
    }

    public static ShanhaiTerminalReport build(ResourceLocation dimension, BlockPos pos, Collection<Entry> entries) {
        return build(ShanhaiTerminalReport.nextId(), dimension, pos, entries);
    }

    public static ShanhaiTerminalReport build(long id, ResourceLocation dimension, BlockPos pos, Collection<Entry> entries) {
        int[] counts = new int[5]; counts[0] = entries.size();
        for (Entry entry : entries) counts[1 + entry.order().state().ordinal()]++;
        ShanhaiTerminalReport.Builder report = new ShanhaiTerminalReport.Builder(
                id, ShanhaiTerminalReport.Type.STELLAR_STUCK, pos, counts);
        String target = dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        Component coordinates = action(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                "/shanhai stellar_tp " + target, "teleport_hint");
        report.summary(action(Component.translatable(PREFIX + "target_dimension", dimension.toString(), coordinates),
                "/shanhai stellar_tp " + target, "teleport_hint"));
        for (Entry entry : entries.stream().sorted(Comparator.comparingInt((Entry e) -> e.order().state().ordinal())
                .thenComparingInt(e -> e.cause().ordinal())
                .thenComparingInt(Entry::slot)).toList()) {
            Component name = Component.translatable(PREFIX + "slot_summary", entry.displaySlot(),
                    entry.cause().summary());
            name = action(name, "/shanhai stellar_slot " + target + " " + entry.slot(), "slot_open_hint");
            name = name.copy().append("\n").append(action(Component.translatable(PREFIX + "open_main"),
                    "/shanhai stellar_slot " + target + " " + entry.slot() + " main", "main_open_hint"))
                    .append("  ").append(action(Component.translatable(PREFIX + "open_slot_settings"),
                            "/shanhai stellar_slot " + target + " " + entry.slot(), "slot_open_hint"));
            if (!entry.order().id().isBlank()) name = name.copy().append("\n").append(
                    Component.translatable(PREFIX + "order_id_short", entry.order().id()).withStyle(style -> style.withUnderlined(false)));
            MutableComponent details = Component.empty();
            section(details, "location", Component.translatable(PREFIX + "detail_location",
                    entry.displaySlot(), dimension.toString(), coordinates), 0x78C8F0);
            section(details, "reason", Component.literal(entry.reason()), entry.cause().color());
            section(details, "order", orderDetails(entry.order()), orderColor(entry));
            if (!entry.diagnosis().hostStatus().isBlank())
                section(details, "host", Component.literal(entry.diagnosis().hostStatus()), 0x78C8F0);
            if (!entry.diagnosis().minting().isBlank())
                section(details, "mint", Component.literal(entry.diagnosis().minting()), 0xF0C878);
            if (!entry.diagnosis().parallel().isBlank())
                section(details, "parallel", Component.literal(entry.diagnosis().parallel()), 0x78C8F0);
            section(details, "recipe", Component.translatable(PREFIX + "detail_recipe",
                    entry.patternRecipeId(), recipeTypeName(entry.patternRecipeTypeId()), recipeTypeNames(entry.hostRecipeTypes())), 0x78C8F0);
            section(details, "materials", Component.translatable(PREFIX + "detail_materials", entry.inputs(), entry.outputs()), 0x78C8F0);
            Component category = Component.translatable(PREFIX + "order_state_" + entry.order().state().name().toLowerCase(java.util.Locale.ROOT))
                    .append(" · ").append(entry.cause().label());
            report.add(pos, "stellar_" + entry.order().state().name() + "_" + entry.cause().name(), category, orderColor(entry),
                    name, List.of(), details);
        }
        return report.build();
    }

    private static int orderColor(Entry entry) {
        return switch (entry.order().state()) {
            case COMPLETED -> 0x78E8A0;
            case CANCELLED -> 0xF0C878;
            case RUNNING, UNTRACKED -> entry.cause().color();
        };
    }

    private static Component orderDetails(StellarPatternOrderInfo order) {
        if (order.state() == StellarPatternOrderInfo.State.UNTRACKED) return Component.translatable(PREFIX + "order_untracked");
        return Component.translatable(PREFIX + "detail_order", order.id(),
                Component.translatable(PREFIX + "order_state_" + order.state().name().toLowerCase(java.util.Locale.ROOT)),
                order.requester(), order.cpu(), order.output(), order.outputId(), order.requested(), order.remaining());
    }

    private static void section(MutableComponent details, String key, Component content, int color) {
        if (!details.getSiblings().isEmpty()) details.append("\n\n");
        details.append(Component.translatable(PREFIX + "section_" + key).withStyle(style -> style.withBold(true).withColor(color)))
                .append("\n").append(content);
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
