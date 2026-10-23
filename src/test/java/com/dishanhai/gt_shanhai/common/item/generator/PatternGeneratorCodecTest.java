package com.dishanhai.gt_shanhai.common.item.generator;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PatternGeneratorCodecTest {
    @Test void stateAndPreviewRoundTripAndSnapshotsDoNotLeakMutability() {
        CompoundTag settings = new CompoundTag();
        settings.putInt("transferCount", 3);
        var previews = new ArrayList<PatternGeneratorPreview>();
        previews.add(new PatternGeneratorPreview("gtceu:lathe/a", 1, 32, 20, "LV",
                List.of(), List.of(), true, false));
        var state = new PatternGeneratorState(UUID.randomUUID(), settings, 4, 0, 1, 1, 0, 0,
                false, true, 2, Component.literal("ready"), Map.of("gtceu:lathe", 1), previews, 37);
        settings.putInt("transferCount", 9);
        previews.clear();
        state.settings().putInt("transferCount", 10);
        assertEquals(3, state.settings().getInt("transferCount"));
        assertEquals(1, state.previewPage().size());
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PatternGeneratorCodec.writeState(buf, state);
            assertEquals(state, PatternGeneratorCodec.readState(buf));
            assertEquals(37, state.acknowledgedRequest());
        } finally { buf.release(); }
    }

    @Test void bitsAndIdsRoundTripAndRejectOversizeInBothDirections() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BitSet bits = new BitSet(); bits.set(65);
            PatternGeneratorCodec.writeBits(buf, bits, 66);
            assertEquals(bits, PatternGeneratorCodec.readBits(buf, 66));
            assertThrows(IllegalArgumentException.class, () -> PatternGeneratorCodec.writeBits(buf, bits, 65));
            buf.clear(); buf.writeVarInt(2); buf.writeLong(0); buf.writeLong(2);
            assertThrows(IllegalArgumentException.class, () -> PatternGeneratorCodec.readBits(buf, 65));
            buf.clear();
            PatternGeneratorCodec.writeIds(buf, List.of("gtceu:a", "gtceu:b"));
            assertEquals(List.of("gtceu:a", "gtceu:b"), PatternGeneratorCodec.readIds(buf));
            assertThrows(IllegalArgumentException.class,
                    () -> PatternGeneratorCodec.writeIds(buf, Collections.nCopies(PatternGeneratorPresetFiles.MAX_RECIPES + 1, "gtceu:a")));
            assertThrows(IllegalArgumentException.class, () -> PatternGeneratorCodec.writeIds(buf, List.of("Invalid ID")));
            buf.clear(); buf.writeVarInt(PatternGeneratorPresetFiles.MAX_RECIPES + 1);
            assertThrows(IllegalArgumentException.class, () -> PatternGeneratorCodec.readIds(buf));
        } finally { buf.release(); }
    }

    @Test void stackAndPreviewListsHaveBoundedWritesAndReads() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> PatternGeneratorCodec.writeStacks(buf, Collections.nCopies(4097, null)));
            buf.writeVarInt(4097);
            assertThrows(IllegalArgumentException.class, () -> PatternGeneratorCodec.readStacks(buf));
            assertThrows(IllegalArgumentException.class,
                    () -> PatternGeneratorCodec.writePreview(buf, new PatternGeneratorPreview(
                            "bad id", 0, 0, 0, "", List.of(), List.of(), false, false)));
        } finally { buf.release(); }
    }

    @Test void snapshotAlsoCopiesNestedFeedbackComponents() {
        MutableComponent feedback = Component.literal("root").append(Component.literal("leaf"));
        var state = new PatternGeneratorState(UUID.randomUUID(), new CompoundTag(), 0, 0, 0, 0, 0, 0,
                false, true, 0, feedback, Map.of(), List.of());
        ((MutableComponent) feedback.getSiblings().get(0)).append("changed");
        assertEquals("rootleaf", state.feedback().getString());
        ((MutableComponent) state.feedback().getSiblings().get(0)).append("changed");
        assertEquals("rootleaf", state.feedback().getString());
    }

    @Test void cumulativeConfigurationLimitRejectsBeforeWritingAndDoesNotMutateNbt() {
        CompoundTag candidate = new CompoundTag();
        for (int i = 0; i < 4; i++) candidate.putString("slot" + i, "x".repeat(20000));
        CompoundTag original = candidate.copy();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(PatternGeneratorCodec.ConfigurationTooLargeException.class,
                    () -> PatternGeneratorCodec.validateConfiguration(candidate));
            assertThrows(PatternGeneratorCodec.ConfigurationTooLargeException.class,
                    () -> PatternGeneratorCodec.writeConfiguration(buffer, candidate));
            assertEquals(0, buffer.writerIndex());
            assertEquals(original, candidate);
            buffer.writeNbt(candidate);
            assertThrows(PatternGeneratorCodec.ConfigurationTooLargeException.class,
                    () -> PatternGeneratorCodec.readConfiguration(buffer));
            assertEquals(original, candidate);
        } finally { buffer.release(); }
    }

    @Test void acknowledgementsAreNonnegativeAndOldConstructorDefaultsToZero() {
        var state = new PatternGeneratorState(UUID.randomUUID(), new CompoundTag(), 0, 0, 0, 0, 0, 0,
                false, true, 0, Component.empty(), Map.of(), List.of());
        assertEquals(0, state.acknowledgedRequest());
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorState(
                state.sessionId(), state.settings(), 0, 0, 0, 0, 0, 0, false, true, 0,
                Component.empty(), Map.of(), List.of(), -1));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PatternGeneratorCodec.writeState(buffer, state);
            buffer.writerIndex(buffer.writerIndex() - 1);
            buffer.writeVarInt(-1);
            assertThrows(IllegalArgumentException.class, () -> PatternGeneratorCodec.readState(buffer));
        } finally { buffer.release(); }
    }
}

