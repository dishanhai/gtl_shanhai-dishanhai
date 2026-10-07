package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiRecipeIoCapabilityPreservationSourceTest {

    @Test
    void recipeSnapshotAndApplyPreserveRegisteredNonVisualCapabilities() throws Exception {
        String io = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeIoApply.java"));
        String validation = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorValidation.java"));
        String table = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiIoTable.java"));

        assertTrue(io.contains("table.remove(ItemRecipeCapability.CAP)"));
        assertTrue(io.contains("table.remove(FluidRecipeCapability.CAP)"));
        assertTrue(io.contains("table.remove(EURecipeCapability.CAP)"));
        assertTrue(!io.contains("table.clear()"));
        assertTrue(io.contains("table.put(capability, contents)"));
        int directFillStart = table.indexOf("private static void fill(");
        String directFill = directFillStart < 0 ? "" : table.substring(directFillStart);
        assertTrue(directFill.contains("content.getContent() instanceof SizedIngredient sized"));
        assertTrue(directFill.contains("cell.item.setCount(Math.max(1, sized.getAmount()))"));
        assertTrue(table.contains("if (!cell.dirty && cell.original != null) return cell.original;"));
        assertTrue(validation.contains("MAX_CONTENT_ENTRIES = 65536"));
    }
}
