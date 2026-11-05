package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EnhancedCoreCloudLibraryTextTest {
    @Test
    void largeChineseEmojiAndNullRoundTripWithoutSilentUtfLoss() throws Exception {
        String text = ("山海雲端配方🙂\u0000".repeat(4096));
        var chunks = EnhancedCoreCloudLibraryText.chunks(text, 8192);
        assertEquals(text, String.join("", chunks));
        for (String chunk : chunks) {
            assertTrue(EnhancedCoreCloudLibraryText.modifiedUtfBytes(chunk) <= 8192);
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            var bytes = new ByteArrayOutputStream(); new DataOutputStream(bytes).writeUTF(chunk);
            assertEquals(chunk, new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())).readUTF());
        }
        String capped = EnhancedCoreCloudLibraryText.cap(text, 65535);
        assertFalse(capped.isEmpty()); assertTrue(EnhancedCoreCloudLibraryText.modifiedUtfBytes(capped) <= 65535);
    }

    @Test
    void jsonEscapingIsBudgetedAfterSerializationAndRemainsWholeJson() {
        String text = ("\"\\\n\r\t\u0000🙂漢".repeat(10000));
        java.util.function.Function<String, String> json = value -> "{\"text\":\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t").replace("\u0000", "\\u0000") + "\"}";
        String bounded = EnhancedCoreCloudLibraryText.json(text, 65535, json);
        assertTrue(bounded.startsWith("{\"text\":\"")); assertTrue(bounded.endsWith("\"}"));
        assertTrue(EnhancedCoreCloudLibraryText.modifiedUtfBytes(bounded) <= 65535);
        assertFalse(bounded.contains("\u0000"));
        String restored = JsonParser.parseString(bounded).getAsJsonObject().get("text").getAsString();
        assertTrue(text.startsWith(restored)); assertFalse(restored.isEmpty());
        assertFalse(Character.isHighSurrogate(restored.charAt(restored.length() - 1)));
    }

    @Test
    void fullIndexFindsNamesBeyondDisplayCapAndAcrossChunkBoundaries() {
        String names = "甲".repeat(10000) + "尾端🙂精確名字";
        var index = EnhancedCoreCloudLibraryText.chunks(names, 4096);
        assertFalse(EnhancedCoreCloudLibraryText.cap(names, 200).contains("精確名字"));
        String keys = EnhancedCoreCloudLibraryText.terminalKeys("", true, "input", index, List.of());
        assertTrue(keys.contains("尾端🙂精確名字"));
        assertTrue(EnhancedCoreCloudLibraryText.terminalKeys("", true, "input", List.of("ab", "Cd"), List.of()).contains("bcd"));
        assertFalse(keys.contains("不存在"));
    }

    @Test
    void actualTerminalSideMatcherSearchesFullCustomNamesInTheCorrectSideWithEmptyBitsets() throws Exception {
        var input = EnhancedCoreCloudLibraryText.chunks("甲".repeat(10000) + "自訂輸入NBT🙂", 4096);
        var output = EnhancedCoreCloudLibraryText.chunks("乙".repeat(10000) + "Custom OUTPUT NBT🙂", 4096);
        String inputKeys = EnhancedCoreCloudLibraryText.terminalKeys("minecraft:stone\n", true, "input", input, output);
        String outputKeys = EnhancedCoreCloudLibraryText.terminalKeys("minecraft:stone\n", true, "output", input, output);
        Class<?> side = Class.forName("com.gtl.enhancedcore.common.cloud.CloudLibraryService$TerminalSide");
        var constructor = side.getDeclaredConstructor(String.class, int[].class, int[].class); constructor.setAccessible(true);
        var matches = side.getDeclaredMethod("matches", String.class, BitSet.class, BitSet.class); matches.setAccessible(true);
        Object inputSide = constructor.newInstance(inputKeys, new int[0], new int[0]);
        Object outputSide = constructor.newInstance(outputKeys, new int[0], new int[0]);
        assertEquals(true, matches.invoke(inputSide, "自訂輸入NBT🙂".toLowerCase(Locale.ROOT), new BitSet(), new BitSet()));
        assertEquals(false, matches.invoke(inputSide, "custom output nbt🙂", new BitSet(), new BitSet()));
        assertEquals(true, matches.invoke(outputSide, "custom output nbt🙂", new BitSet(), new BitSet()));
        assertEquals(false, matches.invoke(outputSide, "自訂輸入NBT🙂".toLowerCase(Locale.ROOT), new BitSet(), new BitSet()));
        assertEquals("minecraft:stone\n", EnhancedCoreCloudLibraryText.terminalKeys("minecraft:stone\n", false, "input", input, output));
    }
}
