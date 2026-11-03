package com.dishanhai.gt_shanhai.common.machine.preview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 山海：13×12×15雙瓣共鳴口；五層前艙主機居中，全部34格分子機械方塊接受倉口。 */
public final class ShanhaiNyarlathotepEchoLayout {
    private static final String RESOURCE = "/assets/gt_shanhai/multiblock/nyarlathoteps_echo.mb";
    private ShanhaiNyarlathotepEchoLayout() {}

    /** 介面與外框皆為分子機械方塊，均可替換為本機支援的倉室。 */
    public static boolean allowsHatch(char symbol) {
        return symbol == 'C' || symbol == 'M';
    }

    /** FactoryBlockPattern 的每個切片由上至下排列，空白皆要求空氣。 */
    public static String[][] aisles() {
        try (var input = ShanhaiNyarlathotepEchoLayout.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing Echo structure: " + RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isEmpty()).map(line -> line.split(",", -1)).toArray(String[][]::new);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read Echo structure: " + RESOURCE, error);
        }
    }

    /** 建築檔的鑽石標記轉為實際主機／倉口；active方塊允許兩種狀態，避免運行時失效。 */
    public static String blockId(char symbol) {
        return switch (symbol) {
            case 'C', 'M' -> "gtlcore:molecular_casing";
            case 'A' -> "gtlcore:sps_casing";
            case 'B' -> "gtladditions:extreme_density_casing";
            case 'R' -> "kubejs:uruium_coil_block";
            case 'G' -> "gtceu:fusion_glass";
            case 'D' -> "kubejs:containment_field_generator";
            case 'T' -> "kubejs:spacetime_assembly_line_unit";
            case 'F' -> "kubejs:restraint_device";
            case 'O' -> "kubejs:hollow_casing";
            case 'H' -> "gtlcore:hyper_core";
            default -> throw new IllegalArgumentException("Unknown Echo structure symbol: " + symbol);
        };
    }
}
