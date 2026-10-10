package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.common.data.GTItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Clipboard object consumed by {@code DShanhaiRecipeEngine}.
 * Fluids are {@code id amount}. Items are {@code Nx id}, and a count of 1 drops the prefix.
 * Input chance 0 splits into {@code notConsumable} and {@code notConsumableFluid}.
 * {@code EUt} is the recipe value itself.
 */
public final class ShanhaiRecipeJsExport {

    private ShanhaiRecipeJsExport() {}

    public static String format(ShanhaiIoTable table, String recipeId, String recipeTypeId,
                                int duration, long eut, int blastTemp) {
        List<String> itemInputs = new ArrayList<>();
        List<String> inputFluids = new ArrayList<>();
        List<String> notConsumable = new ArrayList<>();
        List<String> notConsumableFluid = new ArrayList<>();
        List<String> itemOutputs = new ArrayList<>();
        List<String> outputFluids = new ArrayList<>();
        int circuit = 0;
        boolean sawCircuit = false;
        if (table != null) {
            for (int i = 0; i < table.cellCount(); i++) {
                ShanhaiIoTable.Cell cell = table.cell(i);
                if (cell == null || cell.empty()) continue;
                ShanhaiIoTable.Kind kind = table.kindOf(i);
                boolean inputSide = kind == ShanhaiIoTable.Kind.ITEM_IN
                        || kind == ShanhaiIoTable.Kind.FLUID_IN;
                if (inputSide && cell.itemKind) {
                    int configuration = circuitOf(cell.item);
                    if (configuration >= 0) {
                        if (!sawCircuit) circuit = configuration;
                        sawCircuit = true;
                        continue;
                    }
                }
                String token = cell.itemKind ? itemToken(cell) : fluidToken(cell);
                if (token == null) continue;
                if (inputSide && cell.chance <= 0) {
                    (cell.itemKind ? notConsumable : notConsumableFluid).add(token);
                } else if (kind == ShanhaiIoTable.Kind.ITEM_IN) {
                    itemInputs.add(token);
                } else if (kind == ShanhaiIoTable.Kind.FLUID_IN) {
                    inputFluids.add(token);
                } else if (kind == ShanhaiIoTable.Kind.ITEM_OUT) {
                    itemOutputs.add(token);
                } else {
                    outputFluids.add(token);
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append("{id:'").append(leaf(recipeId)).append('\'');
        builder.append(",defaultEnabled:true");
        builder.append(",type:'").append(typePath(recipeTypeId)).append('\'');
        builder.append(",circuit:").append(circuit);
        builder.append(",notConsumable:").append(array(notConsumable));
        builder.append(",notConsumableFluid:").append(array(notConsumableFluid));
        builder.append(",itemInputs:").append(array(itemInputs));
        builder.append(",inputFluids:").append(array(inputFluids));
        builder.append(",itemOutputs:").append(array(itemOutputs));
        builder.append(",outputFluids:").append(array(outputFluids));
        if (blastTemp >= 0) builder.append(",blastFurnaceTemp:").append(blastTemp);
        builder.append(",duration:").append(Math.max(1, duration));
        builder.append(",EUt:").append(eut);
        builder.append('}');
        return builder.toString();
    }

    static String leaf(String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeId == null ? "" : recipeId.trim());
        if (id == null) return "";
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Condition key written beside the datapack recipe, {@code namespace:leaf}. */
    public static String enabledRecipeId(ResourceLocation exportId) {
        if (exportId == null) return "";
        return exportId.getNamespace() + ":" + leaf(exportId.toString());
    }

    private static String typePath(String recipeTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeTypeId == null ? "" : recipeTypeId.trim());
        if (id == null) return recipeTypeId == null ? "" : recipeTypeId.trim();
        return id.getPath();
    }

    private static String array(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(", ");
            builder.append('\'').append(values.get(i).replace("'", "")).append('\'');
        }
        return builder.append(']').toString();
    }

    private static String itemToken(ShanhaiIoTable.Cell cell) {
        int count = cell.shownCount();
        if (cell.matchTag != null && !cell.matchTag.isEmpty()) {
            String tag = "#" + cell.matchTag;
            return count == 1 ? tag : count + "x " + tag;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(cell.item.getItem());
        if (id == null) return null;
        if (count == 1) return id.toString();
        return count + "x " + id;
    }

    private static String fluidToken(ShanhaiIoTable.Cell cell) {
        if (cell.matchTag != null && !cell.matchTag.isEmpty()) {
            return "#" + cell.matchTag + " " + cell.shownCount();
        }
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(cell.fluid.getFluid());
        if (id == null) return null;
        return id + " " + cell.shownCount();
    }

    /** Programmed circuit configuration, or -1 when this stack is not one. Does not write NBT. */
    private static int circuitOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !GTItems.INTEGRATED_CIRCUIT.isIn(stack)) return -1;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("Configuration")) return 0;
        return tag.getInt("Configuration");
    }
}
