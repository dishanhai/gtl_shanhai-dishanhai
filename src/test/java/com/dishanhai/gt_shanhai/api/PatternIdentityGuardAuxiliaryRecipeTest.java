package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternIdentityGuardAuxiliaryRecipeTest {

    private static final Path GUARD = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "api", "PatternIdentityGuard.java");

    @Test
    void auxiliaryIoRecipeBypassesPatternIdentityBeforeSlotInspection() throws IOException {
        String source = Files.readString(GUARD);
        int bypass = source.indexOf("PatternRecipeExecutionGuard.isAuxiliaryIORecipe(recipe)");
        int slotInspection = source.indexOf("handlerMachine instanceof RecipeTypePatternBufferPartMachine");

        assertTrue(bypass >= 0, "dummy auxiliary recipes must bypass pattern identity matching");
        assertTrue(bypass < slotInspection, "the bypass must run before reading the slot pattern identity");
    }
}
