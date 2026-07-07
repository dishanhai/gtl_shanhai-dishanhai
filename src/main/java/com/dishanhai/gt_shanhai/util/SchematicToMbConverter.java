package com.dishanhai.gt_shanhai.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * WorldEdit .schem → GTCEu FactoryBlockPattern .mb 格式转换器
 * 用法: 作为 gradle 的 JavaExec 运行，或在 IDE 中直接执行 main()
 */
public class SchematicToMbConverter {

    public static void main(String[] args) throws Exception {
        String schemPath = args.length > 0 ? args[0] :
            "D:/minecraft/gtl/.minecraft/versions/八周目/.minecraft/versions/GTL八周目2号/.minecraft/versions/GTL八周目2/config/worldedit/schematics/heidon.schem";
        String outputPath = args.length > 1 ? args[1] : "black_hole_containment_from_schem.mb";

        CompoundTag root = NbtIo.readCompressed(new java.io.File(schemPath));
        if (root == null) { System.err.println("Failed to read .schem file"); return; }

        // 兼容两种格式: 有 Schematic 包装 vs 直接根标签
        CompoundTag schem = root.contains("Schematic") ? root.getCompound("Schematic") : root;

        short width  = schem.getShort("Width");   // X
        short height = schem.getShort("Height");  // Y
        short length = schem.getShort("Length");  // Z

        // Parse palette: blockName -> index (inverted)
        CompoundTag paletteTag = schem.getCompound("Palette");
        String[] palette = new String[paletteTag.size()];
        for (String blockName : paletteTag.getAllKeys()) {
            int idx = paletteTag.getInt(blockName);
            palette[idx] = blockName;
        }

        // Read BlockData (varint encoded)
        byte[] rawBlockData = schem.getByteArray("BlockData");
        int[] blockData = decodeVarIntArray(rawBlockData, width * height * length);

        System.out.println("Schematic: " + width + "×" + height + "×" + length);
        System.out.println("Palette (" + palette.length + " blocks):");
        for (int i = 0; i < palette.length; i++) {
            System.out.println("  " + i + " = " + palette[i]);
        }

        // Find controller block
        String controllerBlock = null;
        for (String s : palette) {
            if (s != null && s.contains("black_hole_containment")) {
                controllerBlock = s;
                break;
            }
        }
        System.out.println("Controller: " + controllerBlock);

        // 不裁剪——保留完整尺寸保证控制器位置不变
        StringBuilder mb = new StringBuilder();
        for (int y = 0; y < height; y++) {
            List<String> zSlices = new ArrayList<>();
            for (int z = 0; z < length; z++) {
                StringBuilder slice = new StringBuilder();
                for (int x = 0; x < width; x++) {
                    int idx = (y * length + z) * width + x;
                    String blockName = palette[blockData[idx]];
                    slice.append(mapBlockToChar(blockName, controllerBlock));
                }
                zSlices.add(slice.toString().replaceAll("\\s+$", ""));
            }
            mb.append(String.join(",", zSlices)).append("\n");
        }

        // 直接输出 BHCStructure.java
        StringBuilder java = new StringBuilder();
        java.append("package com.dishanhai.gt_shanhai.common.machine.structure;\n\n");
        java.append("import com.dishanhai.gt_shanhai.api.DShanhaiMBParser;\n");
        java.append("import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;\n");
        java.append("import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;\n");
        java.append("import com.gregtechceu.gtceu.api.pattern.BlockPattern;\n");
        java.append("import com.gregtechceu.gtceu.api.pattern.Predicates;\n");
        java.append("import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;\n");
        java.append("import net.minecraft.world.level.block.Block;\n");
        java.append("import net.minecraftforge.registries.ForgeRegistries;\n");
        java.append("import net.minecraft.resources.ResourceLocation;\n\n");
        java.append("public class BHCStructure {\n");
        java.append("    private static Block b(String id) { return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)); }\n");
        java.append("    private static final Block A=b(\"gtceu:high_power_casing\"),B=b(\"gtlcore:dimensionally_transcendent_casing\"),C=b(\"kubejs:dimensional_bridge_casing\"),D=b(\"kubejs:dimensional_stability_casing\"),E=b(\"gtlcore:dimension_injection_casing\"),F=b(\"kubejs:spacetime_compression_field_generator\"),G=b(\"gtlcore:molecular_casing\"),H=b(\"gtceu:me_extended_export_buffer\"),I=b(\"gtceu:me_dual_hatch_stock_part_machine\"),J=b(\"kubejs:dimension_creation_casing\"),K=b(\"kubejs:create_aggregatione_core\"),L=b(\"gt_shanhai:active_neutron_casing\"),M=b(\"gtlcore:create_casing\");\n");
        java.append("    public static BlockPattern createPattern(MultiblockMachineDefinition d) {\n");
        java.append("        return DShanhaiMBParser.parseRaw(DATA, ch -> switch (ch) {\n");
        java.append("            case 'A' -> Predicates.blocks(A).or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1)).or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1)).or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1)).or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1)).or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));\n");
        java.append("            case 'B' -> Predicates.blocks(B); case 'C' -> Predicates.blocks(C); case 'D' -> Predicates.blocks(D); case 'E' -> Predicates.blocks(E);\n");
        java.append("            case 'F' -> Predicates.blocks(F).or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1));\n");
        java.append("            case 'G' -> Predicates.blocks(G); case 'H' -> Predicates.blocks(H).or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1));\n");
        java.append("            case 'I' -> Predicates.blocks(I).or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1)).or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1));\n");
        java.append("            case 'J' -> Predicates.blocks(J); case 'K' -> Predicates.blocks(K); case 'L' -> Predicates.blocks(L); case 'M' -> Predicates.blocks(M);\n");
        java.append("            case '~' -> Predicates.controller(Predicates.blocks(d.getBlock())); default -> Predicates.any();\n");
        java.append("        }, RelativeDirection.FRONT, RelativeDirection.RIGHT, RelativeDirection.UP);\n");
        java.append("    }\n");
        java.append("    private static final String DATA = \"\"\"\n");
        for (String line : mb.toString().split("\n")) {
            java.append("            ").append(line).append("\n");
        }
        java.append("      \"\"\";\n");
        java.append("}\n");

        Files.writeString(Path.of(outputPath), java.toString());
        System.out.println("\nWritten to: " + outputPath);
        System.out.println("Layers: " + mb.toString().split("\n").length);
    }

    private static char mapBlockToChar(String blockName, String controllerBlock) {
        if (blockName == null || blockName.equals("minecraft:air")) return ' ';
        if (blockName.equals(controllerBlock) || blockName.startsWith(controllerBlock + "[")) return '~';
        // 15 方块 → 每个独立字母
        if (blockName.contains("high_power_casing"))           return 'A';
        if (blockName.contains("dimensionally_transcendent"))  return 'B';
        if (blockName.contains("dimensional_bridge"))          return 'C';
        if (blockName.contains("dimensional_stability"))       return 'D';
        if (blockName.contains("dimension_injection"))         return 'E';
        if (blockName.contains("spacetime_compression"))       return 'F';
        if (blockName.contains("molecular_casing"))            return 'G';
        if (blockName.contains("me_extended_export"))          return 'H';
        if (blockName.contains("me_dual_hatch"))               return 'I';
        if (blockName.contains("dimension_creation"))          return 'J';
        if (blockName.contains("create_aggregation"))          return 'K';
        if (blockName.contains("active_neutron_casing"))       return 'L';
        if (blockName.contains("create_casing"))               return 'M';
        // 兜底
        int lu = blockName.lastIndexOf('_');
        if (lu >= 0 && lu + 1 < blockName.length()) return Character.toUpperCase(blockName.charAt(lu + 1));
        return '?';
    }

    /** Decode Protocol Buffer style varint array */
    private static int[] decodeVarIntArray(byte[] data, int expectedSize) {
        int[] result = new int[expectedSize];
        int pos = 0, idx = 0;
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
}
