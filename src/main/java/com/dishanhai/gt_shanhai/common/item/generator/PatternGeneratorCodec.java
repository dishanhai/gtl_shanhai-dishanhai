package com.dishanhai.gt_shanhai.common.item.generator;

import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 山海生成器共用編解碼。所有集合上限在寫入與讀取兩側驗證。 */
public final class PatternGeneratorCodec {
    public static final int MAX_TYPES = 4096;
    public static final int MAX_CONFIG_CHARS = 65536;
    private PatternGeneratorCodec() {}

    public static void writeState(FriendlyByteBuf buffer, PatternGeneratorState state) {
        requireSize(state.typeCounts().size(), MAX_TYPES);
        requireSize(state.previewPage().size(), PatternGeneratorLayout.capacity(PatternGeneratorLayout.MAX_COLUMNS));
        validateState(state);
        buffer.writeUUID(state.sessionId());
        writeConfiguration(buffer, state.settings());
        buffer.writeVarInt(state.revision());
        buffer.writeVarInt(state.page());
        buffer.writeVarInt(state.count());
        buffer.writeVarInt(state.selected());
        buffer.writeVarInt(state.pendingCount());
        buffer.writeVarInt(state.generated());
        buffer.writeBoolean(state.running());
        buffer.writeBoolean(state.ready());
        buffer.writeVarInt(state.availablePatterns());
        writeText(buffer, state.feedback());
        buffer.writeVarInt(state.typeCounts().size());
        for (var type : state.typeCounts().entrySet()) {
            requireId(type.getKey());
            buffer.writeUtf(type.getKey(), 256);
            buffer.writeVarInt(type.getValue());
        }
        buffer.writeVarInt(state.previewPage().size());
        for (var value : state.previewPage()) writePreview(buffer, value);
        buffer.writeVarInt(state.acknowledgedRequest());
    }

    public static PatternGeneratorState readState(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        CompoundTag settings = readConfiguration(buffer);
        int revision = buffer.readVarInt(), page = buffer.readVarInt(), count = buffer.readVarInt();
        int selected = buffer.readVarInt(), pending = buffer.readVarInt(), generated = buffer.readVarInt();
        boolean running = buffer.readBoolean(), ready = buffer.readBoolean();
        int available = buffer.readVarInt();
        Component feedback = readText(buffer);
        int size = readSize(buffer, MAX_TYPES);
        Map<String, Integer> types = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String type = buffer.readUtf(256); requireId(type);
            int recipes = buffer.readVarInt(); requireSize(recipes, Integer.MAX_VALUE);
            types.put(type, recipes);
        }
        size = readSize(buffer, PatternGeneratorLayout.capacity(PatternGeneratorLayout.MAX_COLUMNS));
        List<PatternGeneratorPreview> previews = new ArrayList<>(size);
        for (int i = 0; i < size; i++) previews.add(readPreview(buffer));
        int acknowledgedRequest = buffer.readVarInt();
        var state = new PatternGeneratorState(id, settings, revision, page, count, selected, pending,
                generated, running, ready, available, feedback, types, previews, acknowledgedRequest);
        validateState(state);
        return state;
    }

    private static void validateState(PatternGeneratorState state) {
        for (int value : new int[]{state.revision(), state.page(), state.count(), state.selected(),
                state.pendingCount(), state.generated(), state.availablePatterns(), state.acknowledgedRequest()})
            requireSize(value, Integer.MAX_VALUE);
        if (state.selected() > state.count()) throw new IllegalArgumentException("Selected count exceeds total");
        state.typeCounts().values().forEach(value -> requireSize(value, Integer.MAX_VALUE));
    }

    public static void writeConfiguration(FriendlyByteBuf buffer, CompoundTag tag) {
        validateConfiguration(tag);
        buffer.writeNbt(tag);
    }

    public static CompoundTag readConfiguration(FriendlyByteBuf buffer) {
        CompoundTag tag = buffer.readNbt();
        validateConfiguration(tag);
        return tag;
    }

    public static void validateConfiguration(CompoundTag tag) {
        if (tag == null || tag.toString().length() > MAX_CONFIG_CHARS)
            throw new ConfigurationTooLargeException();
    }

    public static final class ConfigurationTooLargeException extends IllegalArgumentException {
        public ConfigurationTooLargeException() { super("Pattern generator configuration too large"); }
    }

    public static void writePreview(FriendlyByteBuf buffer, PatternGeneratorPreview value) {
        requireId(value.id());
        buffer.writeUtf(value.id(), 256);
        buffer.writeVarInt(value.circuit());
        buffer.writeLong(value.eut());
        buffer.writeVarInt(value.duration());
        buffer.writeUtf(value.tier(), 32);
        buffer.writeBoolean(value.checked());
        buffer.writeBoolean(value.overflow());
        writeStacks(buffer, value.inputs());
        writeStacks(buffer, value.outputs());
    }

    public static PatternGeneratorPreview readPreview(FriendlyByteBuf buffer) {
        String id = buffer.readUtf(256);
        requireId(id);
        int circuit = buffer.readVarInt();
        long eut = buffer.readLong();
        int duration = buffer.readVarInt();
        String tier = buffer.readUtf(32);
        boolean checked = buffer.readBoolean();
        boolean overflow = buffer.readBoolean();
        return new PatternGeneratorPreview(id, circuit, eut, duration, tier, readStacks(buffer), readStacks(buffer), checked, overflow);
    }

    public static void writeStacks(FriendlyByteBuf buffer, List<GenericStack> stacks) {
        requireSize(stacks.size(), 4096);
        buffer.writeVarInt(stacks.size());
        for (var stack : stacks) {
            if (stack == null) throw new IllegalArgumentException("Null pattern stack");
            buffer.writeNbt(GenericStack.writeTag(stack));
        }
    }

    public static List<GenericStack> readStacks(FriendlyByteBuf buffer) {
        int size = readSize(buffer, 4096);
        var result = new ArrayList<GenericStack>(size);
        for (int i = 0; i < size; i++) {
            var tag = buffer.readNbt();
            var stack = tag == null ? null : GenericStack.readTag(tag);
            if (stack != null) result.add(stack);
        }
        return List.copyOf(result);
    }

    public static void writeText(FriendlyByteBuf buffer, Component component) {
        buffer.writeUtf(Component.Serializer.toJson(component == null ? Component.empty() : component), 32767);
    }

    public static Component readText(FriendlyByteBuf buffer) {
        Component parsed = Component.Serializer.fromJson(buffer.readUtf(32767));
        return parsed == null ? Component.empty() : parsed;
    }

    public static void writeIds(FriendlyByteBuf buffer, List<String> ids) {
        requireSize(ids.size(), PatternGeneratorPresetFiles.MAX_RECIPES);
        ids.forEach(PatternGeneratorCodec::requireId);
        buffer.writeVarInt(ids.size());
        for (String id : ids) buffer.writeUtf(id, 256);
    }

    public static List<String> readIds(FriendlyByteBuf buffer) {
        int size = readSize(buffer, PatternGeneratorPresetFiles.MAX_RECIPES);
        var ids = new LinkedHashSet<String>();
        for (int i = 0; i < size; i++) {
            String id = buffer.readUtf(256);
            requireId(id);
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    public static int readSize(FriendlyByteBuf buffer, int maximum) {
        int size = buffer.readVarInt();
        requireSize(size, maximum);
        return size;
    }
    private static void requireSize(int size, int maximum) {
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Pattern generator packet size out of bounds");
    }
    private static void requireId(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null || parsed.getPath().isEmpty()) throw new IllegalArgumentException("Invalid pattern recipe ID");
    }
    public static void writeBits(FriendlyByteBuf buffer, BitSet bits, int size) {
        if (size < 0 || bits.length() > size) throw new IllegalArgumentException("Pattern generator bitset out of bounds");
        long[] data = bits.toLongArray();
        buffer.writeVarInt(data.length);
        for (long word : data) buffer.writeLong(word);
    }
    public static BitSet readBits(FriendlyByteBuf buffer, int size) {
        if (size < 0) throw new IllegalArgumentException("Negative bitset size");
        int words = readSize(buffer, (int) (((long) size + 63) / 64));
        long[] data = new long[words];
        for (int i = 0; i < words; i++) data[i] = buffer.readLong();
        BitSet bits = BitSet.valueOf(data);
        if (bits.length() > size) throw new IllegalArgumentException("Pattern generator bitset out of bounds");
        return bits;
    }
}

