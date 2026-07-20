package com.dishanhai.gt_shanhai.common.item.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ShanhaiChamberClassifierTest {

    @Test
    void classifiesStableRegistryPathsWithEnergyBeforeInputAndOutput() {
        assertType("luv_input_hatch", ShanhaiChamberClassifier.Type.INPUT, 0xFF3B30);
        assertType("me_output_bus", ShanhaiChamberClassifier.Type.OUTPUT, 0x2F80ED);
        assertType("iv_energy_input_hatch", ShanhaiChamberClassifier.Type.ENERGY, 0xFFD43B);
        assertType("iv_energy_output_hatch", ShanhaiChamberClassifier.Type.ENERGY, 0xFFD43B);
        assertType("iv_substation_output_hatch_64a", ShanhaiChamberClassifier.Type.ENERGY, 0xFFD43B);
        assertType("auto_maintenance_hatch", ShanhaiChamberClassifier.Type.MAINTENANCE, 0xA0A0A0);
        assertType("uev_parallel_hatch", ShanhaiChamberClassifier.Type.PARALLEL, 0xA855F7);
        assertType("hv_muffler_hatch", ShanhaiChamberClassifier.Type.MUFFLER, 0xFF8A2A);
        assertType("optical_data_reception_hatch", ShanhaiChamberClassifier.Type.DATA, 0x22C7D6);
        assertType("rotor_holder", ShanhaiChamberClassifier.Type.OTHER, 0xFFFFFF);
    }

    private static void assertType(String path, ShanhaiChamberClassifier.Type expected, int color) {
        ShanhaiChamberClassifier.Type actual = ShanhaiChamberClassifier.classifyPath(path);
        assertEquals(expected, actual);
        assertEquals(color, actual.color());
    }
}
