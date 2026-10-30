package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 終端完整報告：聊天欄只放摘要，完整條目經封包送到客戶端，由聊天連結開啟的介面顯示。
 * 類別與名稱各自去重成表，條目只存索引，封包因此保持小。
 */
public record ShanhaiTerminalReport(long id, Type type, BlockPos target, int[] counts, Component summary,
                                    List<Category> categories, List<Component> names,
                                    List<List<Item>> icons, List<Component> details,
                                    List<Line> lines, int omitted) {

    public enum Type { SCAN, MODULES, BUILD, CRAFT, STELLAR_STUCK }

    /** 聊天連結指令前綴；客戶端攔截點擊後開啟報告介面，指令本身不會送到伺服器。 */
    public static final String CHAT_COMMAND = "/gtshanhai_terminal_report ";

    /** {@code label} 是客戶端實際顯示的類別名（可翻譯 Component），{@code color} 為 RGB。 */
    public record Category(Component label, int color) {}

    public record Line(BlockPos pos, int category, int name) {}

    /** 單份報告的條目上限；超出的只計數、由介面明示，不靜默丟棄。 */
    public static final int MAX_LINES = 20000;
    private static final int MAX_TABLE = 256;
    /** 單個名稱最多帶幾個圖示（一個位置可放的仓室候選上限）。 */
    private static final int MAX_ICONS = 512;
    private static final AtomicLong NEXT_ID = new AtomicLong(System.currentTimeMillis());

    public static long nextId() {
        return NEXT_ID.incrementAndGet();
    }

    public ShanhaiTerminalReport {
        target = target.immutable();
        counts = counts.clone();
        summary = summary == null ? Component.empty() : summary;
        categories = List.copyOf(categories);
        names = List.copyOf(names);
        icons = icons.stream().map(List::copyOf).toList();
        details = List.copyOf(details);
        lines = List.copyOf(lines);
    }

    @Override
    public int[] counts() {
        return counts.clone();
    }

    /** 伺服器端組裝器：同類別／同名稱自動去重。 */
    public static final class Builder {
        private final long id = nextId();
        private final Type type;
        private final BlockPos target;
        private final int[] counts;
        private final List<Category> categories = new ArrayList<>();
        private final Map<String, Integer> categoryIndex = new HashMap<>();
        private final List<Component> names = new ArrayList<>();
        private final List<List<Item>> icons = new ArrayList<>();
        private final List<Component> details = new ArrayList<>();
        private final Map<String, Integer> nameIndex = new HashMap<>();
        private final List<Line> lines = new ArrayList<>();
        private Component summary = Component.empty();
        private int omitted;

        public Builder(Type type, BlockPos target, int... counts) {
            this.type = type;
            this.target = target;
            this.counts = counts;
        }

        /** 結果摘要（施工報告用：成功／失敗訊息，已帶顏色）。 */
        public Builder summary(Component summary) {
            this.summary = summary;
            return this;
        }

        public long id() {
            return id;
        }

        public int size() {
            return lines.size() + omitted;
        }

        public void add(BlockPos pos, String categoryKey, Component categoryLabel, int color, Component name) {
            add(pos, categoryKey, categoryLabel, color, name, List.of());
        }

        /** {@code items} 是該名稱涵蓋的具體方塊／仓室，客戶端逐個畫成帶圖示的卡片。 */
        public void add(BlockPos pos, String categoryKey, Component categoryLabel, int color, Component name,
                        List<ItemStack> items) {
            add(pos, categoryKey, categoryLabel, color, name, items, Component.empty());
        }

        /** {@code detail} 是名稱下方的補充說明（合成報告的缺料清單等），空 Component 表示沒有。 */
        public void add(BlockPos pos, String categoryKey, Component categoryLabel, int color, Component name,
                        List<ItemStack> items, Component detail) {
            if (lines.size() >= MAX_LINES) {
                omitted++;
                return;
            }
            int category = categoryIndex.computeIfAbsent(categoryKey, key -> {
                categories.add(new Category(categoryLabel, color & 0xFFFFFF));
                return categories.size() - 1;
            });
            int nameId = nameIndex.computeIfAbsent(name.getString() + '|' + name.hashCode(), key -> {
                names.add(name);
                icons.add(items.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::getItem).distinct()
                        .limit(MAX_ICONS).toList());
                details.add(detail);
                return names.size() - 1;
            });
            lines.add(new Line(pos, category, nameId));
        }

        public ShanhaiTerminalReport build() {
            return new ShanhaiTerminalReport(id, type, target, counts, summary, categories, names, icons, details, lines, omitted);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(id);
        buf.writeEnum(type);
        buf.writeBlockPos(target);
        buf.writeVarIntArray(counts);
        buf.writeComponent(summary);
        buf.writeVarInt(categories.size());
        for (Category category : categories) {
            buf.writeComponent(category.label());
            buf.writeInt(category.color());
        }
        buf.writeVarInt(names.size());
        for (Component name : names) buf.writeComponent(name);
        for (List<Item> items : icons) {
            buf.writeVarInt(items.size());
            for (Item item : items) buf.writeId(BuiltInRegistries.ITEM, item);
        }
        for (Component detail : details) buf.writeComponent(detail);
        buf.writeVarInt(omitted);
        buf.writeVarInt(lines.size());
        for (Line line : lines) {
            buf.writeLong(line.pos().asLong());
            buf.writeVarInt(line.category());
            buf.writeVarInt(line.name());
        }
    }

    public static ShanhaiTerminalReport decode(FriendlyByteBuf buf) {
        long id = buf.readLong();
        Type type = buf.readEnum(Type.class);
        BlockPos target = buf.readBlockPos();
        int[] counts = buf.readVarIntArray(16);
        Component summary = buf.readComponent();
        int categoryCount = checked(buf.readVarInt(), MAX_TABLE);
        List<Category> categories = new ArrayList<>(categoryCount);
        for (int i = 0; i < categoryCount; i++) categories.add(new Category(buf.readComponent(), buf.readInt()));
        int nameCount = checked(buf.readVarInt(), MAX_LINES);
        List<Component> names = new ArrayList<>(nameCount);
        for (int i = 0; i < nameCount; i++) names.add(buf.readComponent());
        List<List<Item>> icons = new ArrayList<>(nameCount);
        for (int i = 0; i < nameCount; i++) {
            int iconCount = checked(buf.readVarInt(), MAX_ICONS);
            List<Item> items = new ArrayList<>(iconCount);
            for (int j = 0; j < iconCount; j++) items.add(buf.readById(BuiltInRegistries.ITEM));
            icons.add(items);
        }
        List<Component> details = new ArrayList<>(nameCount);
        for (int i = 0; i < nameCount; i++) details.add(buf.readComponent());
        int omitted = Math.max(0, buf.readVarInt());
        int lineCount = checked(buf.readVarInt(), MAX_LINES);
        List<Line> lines = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            BlockPos pos = BlockPos.of(buf.readLong());
            int category = checked(buf.readVarInt(), categoryCount - 1);
            int name = checked(buf.readVarInt(), nameCount - 1);
            lines.add(new Line(pos, category, name));
        }
        return new ShanhaiTerminalReport(id, type, target, counts, summary, categories, names, icons, details, lines, omitted);
    }

    private static int checked(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("山海终端报告数据越界: " + value);
        return value;
    }
}
