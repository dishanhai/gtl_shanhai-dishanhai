package com.dishanhai.gt_shanhai.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class SchematicToMbResourceConverter {
    private static final char[] CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[]{};:<>/?".toCharArray();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: <input.schem> <output.mb> [report.txt]");
        }

        CompoundTag root = NbtIo.readCompressed(Path.of(args[0]).toFile());
        if (root == null) throw new IllegalStateException("Failed to read " + args[0]);
        CompoundTag schem = root.contains("Schematic") ? root.getCompound("Schematic") : root;

        int width = schem.getShort("Width");
        int height = schem.getShort("Height");
        int length = schem.getShort("Length");
        String[] palette = readPalette(schem.getCompound("Palette"));
        int[] blocks = decodeVarIntArray(schem.getByteArray("BlockData"), width * height * length);

        int minX = width, minY = height, minZ = length;
        int maxX = -1, maxY = -1, maxZ = -1;
        Map<String, Integer> counts = new TreeMap<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    String block = palette[blocks[index(x, y, z, width, length)]];
                    if ("minecraft:air".equals(block)) continue;
                    counts.put(block, counts.getOrDefault(block, 0) + 1);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (maxX < 0) throw new IllegalStateException("Schematic has no non-air blocks");

        Map<String, Character> mapping = buildMapping(counts);
        StringBuilder mb = new StringBuilder();
        for (int y = minY; y <= maxY; y++) {
            List<String> rows = new ArrayList<>();
            for (int z = minZ; z <= maxZ; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = minX; x <= maxX; x++) {
                    String block = palette[blocks[index(x, y, z, width, length)]];
                    row.append("minecraft:air".equals(block) ? ' ' : mapping.get(block));
                }
                rows.add(trimRight(row.toString()));
            }
            mb.append(String.join(",", rows)).append('\n');
        }

        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, mb.toString());

        String report = buildReport(width, height, length, minX, minY, minZ, maxX, maxY, maxZ, counts, mapping);
        System.out.print(report);
        if (args.length >= 3) {
            Path reportPath = Path.of(args[2]);
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, report);
        }
    }

    private static String[] readPalette(CompoundTag paletteTag) {
        String[] palette = new String[paletteTag.size()];
        for (String blockName : paletteTag.getAllKeys()) {
            palette[paletteTag.getInt(blockName)] = stripState(blockName);
        }
        return palette;
    }

    private static Map<String, Character> buildMapping(Map<String, Integer> counts) {
        Map<String, Character> mapping = new LinkedHashMap<>();
        mapping.put("minecraft:oak_log", '~');
        mapping.put("gtlcore:dimensionally_transcendent_casing", 'H');

        List<String> blocks = new ArrayList<>(counts.keySet());
        blocks.sort(Comparator.naturalOrder());
        int charIndex = 0;
        for (String block : blocks) {
            if (mapping.containsKey(block)) continue;
            while (charIndex < CHARS.length && mapping.containsValue(CHARS[charIndex])) charIndex++;
            if (charIndex >= CHARS.length) throw new IllegalStateException("Too many block types");
            mapping.put(block, CHARS[charIndex++]);
        }
        return mapping;
    }

    private static String stripState(String blockName) {
        int stateStart = blockName.indexOf('[');
        return stateStart >= 0 ? blockName.substring(0, stateStart) : blockName;
    }

    private static String trimRight(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') end--;
        return value.substring(0, end);
    }

    private static int index(int x, int y, int z, int width, int length) {
        return (y * length + z) * width + x;
    }

    private static int[] decodeVarIntArray(byte[] data, int expectedSize) {
        int[] result = new int[expectedSize];
        int pos = 0;
        int idx = 0;
        while (pos < data.length && idx < expectedSize) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < data.length);
            result[idx++] = value;
        }
        return result;
    }

    private static String buildReport(int width, int height, int length,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Map<String, Integer> counts, Map<String, Character> mapping) {
        StringBuilder out = new StringBuilder();
        out.append("Schematic: ").append(width).append('x').append(height).append('x').append(length).append('\n');
        out.append("Cropped: ").append(maxX - minX + 1).append('x').append(maxY - minY + 1).append('x').append(maxZ - minZ + 1)
                .append(" min=(").append(minX).append(',').append(minY).append(',').append(minZ).append(')')
                .append(" max=(").append(maxX).append(',').append(maxY).append(',').append(maxZ).append(')').append('\n');
        out.append("Blocks:\n");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.append("  ").append(mapping.get(entry.getKey())).append(" = ").append(entry.getKey())
                    .append(" x").append(entry.getValue()).append('\n');
        }
        return out.toString();
    }
}
