package com.dishanhai.gt_shanhai.common.machine.preview;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：只標記控制器旁20個分子機械方塊；兩側及背面同材質不得變倉口。 */
class ShanhaiNyarlathotepHatchLayoutTest {
    private String map(String original) throws Exception {
        assertTrue(Files.exists(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/preview/ShanhaiNyarlathotepHatchLayout.java")),
                "Missing controller-local hatch layout");
        return (String) Class.forName("com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepHatchLayout")
                .getMethod("withLocalHatches", String.class).invoke(null, original);
    }

    @Test void onlyControllerAdjacentMolecularCasingsBecomeHatches() throws Exception {
        String original = Files.readString(Path.of("src/main/resources/assets/gt_shanhai/multiblock/nyarlathoteps_tentacle.mb"));
        String[] before = original.lines().toArray(String[]::new);
        String[] after = map(original).lines().toArray(String[]::new);
        assertEquals(before.length, after.length);
        int hatches = 0, untouchedA = 0, untouchedO = 0;
        for (int z = 0; z < before.length; z++) {
            String[] oldRows = before[z].split(",", -1), newRows = after[z].split(",", -1);
            assertEquals(oldRows.length, newRows.length);
            for (int y = 0; y < oldRows.length; y++) {
                assertEquals(oldRows[y].length(), newRows[y].length());
                for (int x = 0; x < oldRows[y].length(); x++) {
                    char old = oldRows[y].charAt(x), mapped = newRows[y].charAt(x);
                    if (old == 'A' && z == 48 && Math.abs(x - 82) <= 2 && Math.abs(y - 24) <= 2) {
                        assertEquals('b', mapped, "Controller-local molecular casing must accept hatch");
                        hatches++;
                    } else {
                        assertEquals(old, mapped, "Do not change remote casing or source structure");
                        if (old == 'A') untouchedA++;
                        if (old == 'O') untouchedO++;
                    }
                }
            }
        }
        assertEquals(20, hatches);
        assertEquals(42, untouchedA);
        assertEquals(4952, untouchedO);
    }

    @Test void registrationUsesLocalLayoutInsteadOfOpeningAllRemoteCasings() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/preview/ShanhaiNyarlathotepPreviewStructure.java"));
        assertTrue(source.contains("ShanhaiNyarlathotepHatchLayout.withLocalHatches"));
        assertFalse(source.contains("symbol == 'O'"));
        assertTrue(source.contains("case 'b':"));
    }
}
