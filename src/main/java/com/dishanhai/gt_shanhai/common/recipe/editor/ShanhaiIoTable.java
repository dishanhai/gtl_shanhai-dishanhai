package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Recipe-editor IO buffer.
 *
 * <p>The table deliberately separates the declared type capacity from the
 * amount actually present in a recipe. This is what lets the editor display
 * KubeJS recipes whose IO count exceeds the machine's normal slot count without
 * silently truncating them.</p>
 */
public final class ShanhaiIoTable {

    public static final int ITEM_IN = 9;
    public static final int FLUID_IN = 3;
    public static final int ITEM_OUT = 9;
    public static final int FLUID_OUT = 3;
    public static final int CELLS = ITEM_IN + FLUID_IN + ITEM_OUT + FLUID_OUT;

    /** Upper bound for a recipe type's declared slot capacity. */
    public static final int MAX_PER_SECTION = 4096;
    /** Hard memory guard for the actual number of entries in one section. */
    public static final int MAX_CELLS_PER_SECTION = 65536;
    /** GT's SizedIngredient stores an int, not an item stack size. */
    public static final int MAX_ITEM_COUNT = Integer.MAX_VALUE;

    public enum Kind {
        ITEM_IN, FLUID_IN, ITEM_OUT, FLUID_OUT
    }

    public static final class Cell {
        public final boolean itemKind;
        public Content original;
        public ItemStack item = ItemStack.EMPTY;
        public FluidStack fluid = FluidStack.empty();
        public boolean dirty;
        public int chance = 10000;
        public int maxChance = 10000;
        public int tierChanceBoost;

        public Cell(boolean itemKind) {
            this.itemKind = itemKind;
        }

        public boolean empty() {
            return itemKind ? item.isEmpty() : fluid.isEmpty();
        }

        public int shownCount() {
            if (itemKind) return item.isEmpty() ? 0 : item.getCount();
            return fluid.isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, fluid.getAmount());
        }

        public void setItem(ItemStack stack, int count) {
            if (stack == null || stack.isEmpty() || count <= 0) {
                item = ItemStack.EMPTY;
            } else {
                item = stack.copy();
                item.setCount(Math.max(1, Math.min(MAX_ITEM_COUNT, count)));
            }
            dirty = true;
        }

        public void setFluid(FluidStack stack) {
            fluid = stack == null || stack.isEmpty() ? FluidStack.empty() : stack.copy();
            dirty = true;
        }

        public void clear() {
            item = ItemStack.EMPTY;
            fluid = FluidStack.empty();
            dirty = true;
        }

        public Cell copy() {
            Cell copy = new Cell(itemKind);
            copy.original = original;
            copy.item = item.copy();
            copy.fluid = fluid.isEmpty() ? FluidStack.empty() : fluid.copy();
            copy.dirty = dirty;
            copy.chance = chance;
            copy.maxChance = maxChance;
            copy.tierChanceBoost = tierChanceBoost;
            return copy;
        }
    }

    private final List<Cell> cells = new ArrayList<>();
    private int itemIn;
    private int fluidIn;
    private int itemOut;
    private int fluidOut;

    public ShanhaiIoTable() {
        this(ITEM_IN, FLUID_IN, ITEM_OUT, FLUID_OUT);
    }

    public ShanhaiIoTable(int itemIn, int fluidIn, int itemOut, int fluidOut) {
        this.itemIn = clampSection(itemIn);
        this.fluidIn = clampSection(fluidIn);
        this.itemOut = clampSection(itemOut);
        this.fluidOut = clampSection(fluidOut);
        rebuild();
    }

    private static int clampSection(int value) {
        return Math.max(0, Math.min(MAX_CELLS_PER_SECTION, value));
    }

    private void rebuild() {
        cells.clear();
        addCells(itemIn, true);
        addCells(fluidIn, false);
        addCells(itemOut, true);
        addCells(fluidOut, false);
    }

    private void addCells(int count, boolean itemKind) {
        for (int i = 0; i < count; i++) cells.add(new Cell(itemKind));
    }

    /**
     * Returns {item input, fluid input, item output, fluid output}.
     */
    public static int[] shapeFor(int[] typeCapacity, int[] used) {
        int[] shape = new int[4];
        for (int i = 0; i < shape.length; i++) {
            int declared = typeCapacity != null && i < typeCapacity.length ? typeCapacity[i] : 0;
            int actual = used != null && i < used.length ? used[i] : 0;
            shape[i] = Math.max(
                    Math.min(MAX_PER_SECTION, Math.max(0, declared)),
                    Math.min(MAX_CELLS_PER_SECTION, Math.max(0, actual)));
        }
        return shape;
    }

    public int itemIn() { return itemIn; }
    public int fluidIn() { return fluidIn; }
    public int itemOut() { return itemOut; }
    public int fluidOut() { return fluidOut; }
    public int inSection() { return itemIn + fluidIn; }
    public int outSection() { return itemOut + fluidOut; }
    public int cellCount() { return cells.size(); }
    public Cell cell(int index) {
        return index < 0 || index >= cells.size() ? null : cells.get(index);
    }
    public List<Cell> all() { return List.copyOf(cells); }

    public Kind kindOf(int index) {
        if (index < itemIn) return Kind.ITEM_IN;
        if (index < itemIn + fluidIn) return Kind.FLUID_IN;
        if (index < inSection() + itemOut) return Kind.ITEM_OUT;
        return Kind.FLUID_OUT;
    }

    public int inIndex(int sectionIndex) { return sectionIndex; }
    public int outIndex(int sectionIndex) { return inSection() + sectionIndex; }

    /**
     * Returns exactly the cells shown on the current input/output pages.
     * The editor and sync packet both use this method to avoid page drift.
     */
    public int[] visibleIndices(int inputPage, int outputPage, int cellsPerPage) {
        int per = Math.max(1, cellsPerPage);
        int[] result = new int[Math.min(cellCount(), per * 2)];
        int size = 0;
        for (int slot = 0; slot < per; slot++) {
            int sectionIndex = inputPage * per + slot;
            if (sectionIndex >= 0 && sectionIndex < inSection()) result[size++] = inIndex(sectionIndex);
        }
        for (int slot = 0; slot < per; slot++) {
            int sectionIndex = outputPage * per + slot;
            if (sectionIndex >= 0 && sectionIndex < outSection()) result[size++] = outIndex(sectionIndex);
        }
        return Arrays.copyOf(result, size);
    }

    public void resize(int newItemIn, int newFluidIn, int newItemOut, int newFluidOut) {
        int ni = clampSection(newItemIn);
        int nf = clampSection(newFluidIn);
        int no = clampSection(newItemOut);
        int nfo = clampSection(newFluidOut);
        if (ni == itemIn && nf == fluidIn && no == itemOut && nfo == fluidOut) return;
        List<Cell> old = new ArrayList<>(cells);
        int oldItemIn = itemIn;
        int oldFluidIn = fluidIn;
        int oldItemOut = itemOut;
        int oldFluidOut = fluidOut;
        itemIn = ni;
        fluidIn = nf;
        itemOut = no;
        fluidOut = nfo;
        rebuild();
        carry(old, oldItemIn, 0, itemIn, 0);
        carry(old, oldFluidIn, oldItemIn, fluidIn, itemIn);
        carry(old, oldItemOut, oldItemIn + oldFluidIn, itemOut, itemIn + fluidIn);
        carry(old, oldFluidOut, oldItemIn + oldFluidIn + oldItemOut,
                fluidOut, itemIn + fluidIn + itemOut);
    }

    private void carry(List<Cell> old, int oldCount, int oldOffset, int newCount, int newOffset) {
        int count = Math.min(oldCount, newCount);
        for (int i = 0; i < count; i++) {
            Cell source = old.get(oldOffset + i);
            Cell target = cells.get(newOffset + i);
            cells.set(newOffset + i, source.copy());
            if (target.itemKind != source.itemKind) {
                cells.get(newOffset + i).clear();
            }
        }
    }

    public static int[] usedBy(GTRecipe recipe) {
        if (recipe == null) return new int[] {0, 0, 0, 0};
        return new int[] {
                contents(recipe.inputs, ItemRecipeCapability.CAP).size(),
                contents(recipe.inputs, FluidRecipeCapability.CAP).size(),
                contents(recipe.outputs, ItemRecipeCapability.CAP).size(),
                contents(recipe.outputs, FluidRecipeCapability.CAP).size()
        };
    }

    private static List<Content> contents(
            Map<RecipeCapability<?>, List<Content>> table, RecipeCapability<?> capability) {
        if (table == null) return List.of();
        List<Content> value = table.get(capability);
        return value == null ? List.of() : value;
    }

    /**
     * Non-null means a save would drop entries because the current buffer cannot hold them.
     */
    public static String truncationRisk(int[] used, ShanhaiIoTable table) {
        if (used == null || table == null) return null;
        int[] capacity = {table.itemIn, table.fluidIn, table.itemOut, table.fluidOut};
        String[] names = {"item-input", "fluid-input", "item-output", "fluid-output"};
        for (int i = 0; i < 4; i++) {
            int actual = i < used.length ? Math.max(0, used[i]) : 0;
            if (actual > capacity[i]) {
                return names[i] + " requires " + actual + " cells but table has " + capacity[i];
            }
        }
        return null;
    }

    public static ShanhaiIoTable fromRecipe(GTRecipe recipe) {
        int[] used = usedBy(recipe);
        ShanhaiIoTable table = new ShanhaiIoTable(
                Math.max(ITEM_IN, used[0]),
                Math.max(FLUID_IN, used[1]),
                Math.max(ITEM_OUT, used[2]),
                Math.max(FLUID_OUT, used[3]));
        if (recipe == null) return table;
        fill(table, recipe.inputs, ItemRecipeCapability.CAP, true, 0);
        fill(table, recipe.inputs, FluidRecipeCapability.CAP, false, table.itemIn);
        fill(table, recipe.outputs, ItemRecipeCapability.CAP, true, table.inSection());
        fill(table, recipe.outputs, FluidRecipeCapability.CAP, false, table.inSection() + table.itemOut);
        return table;
    }

    public static ShanhaiIoTable fromRecipeWithShape(GTRecipe recipe, int[] shape) {
        int[] safe = shape == null || shape.length < 4 ? new int[] {ITEM_IN, FLUID_IN, ITEM_OUT, FLUID_OUT} : shape;
        ShanhaiIoTable table = new ShanhaiIoTable(safe[0], safe[1], safe[2], safe[3]);
        if (recipe == null) return table;
        fill(table, recipe.inputs, ItemRecipeCapability.CAP, true, 0);
        fill(table, recipe.inputs, FluidRecipeCapability.CAP, false, table.itemIn);
        fill(table, recipe.outputs, ItemRecipeCapability.CAP, true, table.inSection());
        fill(table, recipe.outputs, FluidRecipeCapability.CAP, false, table.inSection() + table.itemOut);
        return table;
    }

    /**
     * Builds the graphical buffer from the same GTCEu codec JSON sent by the
     * editor detail packet. This keeps the client slot view in sync with the
     * server snapshot without inventing a second wire format.
     */
    public static ShanhaiIoTable fromJson(JsonObject inputs, JsonObject outputs) {
        int itemIn = arraySize(inputs, "item");
        int fluidIn = arraySize(inputs, "fluid");
        int itemOut = arraySize(outputs, "item");
        int fluidOut = arraySize(outputs, "fluid");
        ShanhaiIoTable table = new ShanhaiIoTable(
                Math.max(ITEM_IN, itemIn),
                Math.max(FLUID_IN, fluidIn),
                Math.max(ITEM_OUT, itemOut),
                Math.max(FLUID_OUT, fluidOut));
        fillJson(table, inputs, "item", ItemRecipeCapability.CAP, true, 0);
        fillJson(table, inputs, "fluid", FluidRecipeCapability.CAP, false, table.itemIn);
        fillJson(table, outputs, "item", ItemRecipeCapability.CAP, true, table.inSection());
        fillJson(table, outputs, "fluid", FluidRecipeCapability.CAP, false,
                table.inSection() + table.itemOut);
        return table;
    }

    private static int arraySize(JsonObject value, String key) {
        if (value == null) return 0;
        JsonElement element = value.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray().size() : 0;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void fillJson(ShanhaiIoTable table, JsonObject source, String key,
                                 RecipeCapability capability, boolean itemKind, int offset) {
        if (source == null) return;
        JsonElement element = source.get(key);
        if (element == null || !element.isJsonArray()) return;
        JsonArray values = element.getAsJsonArray();
        for (int i = 0; i < values.size() && offset + i < table.cells.size(); i++) {
            Content content = ((com.mojang.serialization.Codec<Content>) Content.codec(capability))
                    .parse(JsonOps.INSTANCE, values.get(i)).result().orElse(null);
            if (content == null) continue;
            Cell cell = table.cells.get(offset + i);
            cell.original = content;
            cell.chance = content.chance;
            cell.maxChance = content.maxChance;
            cell.tierChanceBoost = content.tierChanceBoost;
            if (itemKind) {
                Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
                if (ingredient != null) {
                    ItemStack[] stacks = ingredient.getItems();
                    if (stacks.length > 0) {
                        cell.item = stacks[0].copy();
                        if (content.getContent() instanceof SizedIngredient sized) {
                            cell.item.setCount(Math.max(1, sized.getAmount()));
                        }
                    }
                }
            } else {
                FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
                if (ingredient != null) {
                    FluidStack[] stacks = ingredient.getStacks();
                    if (stacks.length > 0) cell.fluid = stacks[0].copy();
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void fill(ShanhaiIoTable table, Map<RecipeCapability<?>, List<Content>> source,
                             RecipeCapability capability, boolean itemKind, int offset) {
        List<Content> values = source == null ? null : source.get(capability);
        if (values == null) return;
        for (int i = 0; i < values.size() && offset + i < table.cells.size(); i++) {
            Content content = values.get(i);
            Cell cell = table.cells.get(offset + i);
            cell.original = content;
            cell.chance = content.chance;
            cell.maxChance = content.maxChance;
            cell.tierChanceBoost = content.tierChanceBoost;
            if (itemKind) {
                Ingredient ingredient = ItemRecipeCapability.CAP.of(content.getContent());
                if (ingredient != null) {
                    ItemStack[] stacks = ingredient.getItems();
                    if (stacks.length > 0) cell.item = stacks[0].copy();
                }
            } else {
                FluidIngredient ingredient = FluidRecipeCapability.CAP.of(content.getContent());
                if (ingredient != null) {
                    FluidStack[] stacks = ingredient.getStacks();
                    if (stacks.length > 0) cell.fluid = stacks[0].copy();
                }
            }
        }
    }

    /**
     * Encodes one section using GTCEu's own Content codec.
     * Untouched cells retain their original Content object, including chance,
     * catalyst and tag information; only dirty cells are rebuilt.
     */
    public com.google.gson.JsonObject json(String which) {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        com.google.gson.JsonArray fluids = new com.google.gson.JsonArray();
        int start = "outputs".equals(which) ? inSection() : 0;
        int end = "outputs".equals(which) ? cells.size() : inSection();
        for (int i = start; i < end; i++) {
            Cell cell = cells.get(i);
            RecipeCapability<?> capability = cell.itemKind
                    ? ItemRecipeCapability.CAP : FluidRecipeCapability.CAP;
            Content content = contentFor(cell, capability);
            if (content == null) continue;
            @SuppressWarnings({"unchecked", "rawtypes"})
            com.mojang.serialization.Codec<Content> codec = Content.codec((RecipeCapability) capability);
            codec.encodeStart(JsonOps.INSTANCE, content).result().ifPresent(
                    value -> (cell.itemKind ? items : fluids).add(value));
        }
        if (items.size() > 0) result.add("item", items);
        if (fluids.size() > 0) result.add("fluid", fluids);
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Content contentFor(Cell cell, RecipeCapability<?> capability) {
        if (cell == null || capability == null || cell.empty()) return null;
        if (!cell.dirty && cell.original != null) return cell.original;
        Object value;
        if (cell.itemKind) {
            Ingredient ingredient = Ingredient.of(cell.item.copy());
            value = SizedIngredient.create(ingredient, Math.max(1, cell.item.getCount()));
        } else {
            value = FluidIngredient.of(cell.fluid.copy());
        }
        return new Content(value, cell.chance, cell.maxChance, cell.tierChanceBoost, null, null);
    }

    public void writeState(FriendlyByteBuf buffer, int[] indices) {
        int[] safe = indices == null ? new int[0] : indices;
        buffer.writeVarInt(safe.length);
        for (int index : safe) {
            Cell cell = cell(index);
            buffer.writeVarInt(index);
            buffer.writeBoolean(cell != null && cell.itemKind);
            buffer.writeBoolean(cell != null && !cell.empty());
            if (cell == null || cell.empty()) continue;
            buffer.writeVarInt(cell.shownCount());
            if (cell.itemKind) {
                buffer.writeVarInt(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(cell.item.getItem()));
            } else {
                buffer.writeVarInt(net.minecraft.core.registries.BuiltInRegistries.FLUID.getId(cell.fluid.getFluid()));
            }
        }
    }
}
