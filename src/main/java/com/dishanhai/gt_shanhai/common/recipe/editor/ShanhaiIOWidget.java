package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/** One graphical item or fluid cell. Drops are recorded, never consumed. */
public class ShanhaiIOWidget extends Widget implements IGhostIngredientTarget {

    public static final int FRAME_ITEM = 0xFF8B8B8B;
    public static final int FRAME_FLUID = 0xFF3A7BD5;
    public static final int CELL = 20;

    private ShanhaiIoTable table;
    private final IntSupplier indexSupplier;
    private final Runnable changed;
    private Runnable before = () -> {};
    private BooleanSupplier selected = () -> false;
    private DoubleSupplier accent = () -> 1d;
    private Runnable onSelect = () -> {};

    public ShanhaiIOWidget(ShanhaiIoTable table, IntSupplier indexSupplier,
                           int x, int y, Runnable changed) {
        super(x, y, CELL, CELL);
        this.table = table;
        this.indexSupplier = indexSupplier;
        this.changed = changed;
    }

    public void setSelection(BooleanSupplier selected, Runnable onSelect) {
        this.selected = selected == null ? () -> false : selected;
        this.onSelect = onSelect == null ? () -> {} : onSelect;
    }

    public void setAccent(DoubleSupplier accent) {
        this.accent = accent == null ? () -> 1d : accent;
    }

    public void setTable(ShanhaiIoTable table) {
        this.table = table;
    }

    public void setBeforeChange(Runnable before) {
        this.before = before == null ? () -> {} : before;
    }

    private ShanhaiIoTable.Cell cell() {
        return table == null ? null : table.cell(indexSupplier.getAsInt());
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        int discoveredIndex = indexSupplier.getAsInt();
        ShanhaiIoTable.Cell current = cell();
        if (current == null) return List.of();
        Object value = normalize(ingredient, current.itemKind);
        if (value == null) return List.of();
        return List.of(new Target() {
            @Override
            public Rect2i getArea() {
                return new Rect2i(getPositionX(), getPositionY(), CELL, CELL);
            }

            @Override
            public void accept(Object accepted) {
                applyClientDraft(accepted, discoveredIndex, current);
            }
        });
    }

    private void applyClientDraft(Object accepted, int discoveredIndex,
                                  ShanhaiIoTable.Cell discoveredCell) {
        ShanhaiIoTable.Cell target = table == null ? null : table.cell(discoveredIndex);
        if (target == null || target != discoveredCell) return;
        Object normalized = normalize(accepted, target.itemKind);
        if (!(normalized instanceof ItemStack) && !(normalized instanceof FluidStack)) return;
        before.run();
        if (normalized instanceof ItemStack stack) {
            target.setItem(stack, stack.getCount());
            keepPlacedCircuit(discoveredIndex, target);
        } else {
            target.setFluid((FluidStack) normalized);
        }
        changed.run();
    }

    /**
     * Writes the shop picker's confirmed list. The clicked cell is the first slot of its
     * own kind; the other kind starts at the first cell on the same side. Extra entries
     * grow that side instead of spilling into the opposite side.
     */
    private void applyBatch(int origin, List<ItemStack> items, List<FluidStack> fluids) {
        if (table == null) return;
        int itemCount = items == null ? 0 : items.size();
        int fluidCount = fluids == null ? 0 : fluids.size();
        if (itemCount == 0 && fluidCount == 0) return;
        ShanhaiIoTable.Cell originCell = table.cell(origin);
        if (originCell == null) return;
        before.run();
        boolean inputSide = origin < table.inSection();
        int itemLocal = originCell.itemKind
                ? (inputSide ? origin : origin - table.inSection()) : 0;
        int fluidLocal = originCell.itemKind ? 0
                : (inputSide ? origin - table.itemIn() : origin - (table.inSection() + table.itemOut()));
        int itemSize = inputSide ? table.itemIn() : table.itemOut();
        int fluidSize = inputSide ? table.fluidIn() : table.fluidOut();
        int nextItems = itemCount == 0 ? itemSize : Math.max(itemSize, itemLocal + itemCount);
        int nextFluids = fluidCount == 0 ? fluidSize : Math.max(fluidSize, fluidLocal + fluidCount);
        if (inputSide) table.resize(nextItems, nextFluids, table.itemOut(), table.fluidOut());
        else table.resize(table.itemIn(), table.fluidIn(), nextItems, nextFluids);
        int itemBase = inputSide ? 0 : table.inSection();
        int fluidBase = inputSide ? table.itemIn() : table.inSection() + table.itemOut();
        for (int i = 0; i < itemCount; i++) {
            ShanhaiIoTable.Cell cell = table.cell(itemBase + itemLocal + i);
            ItemStack stack = items.get(i);
            if (cell == null || !cell.itemKind || stack == null || stack.isEmpty()) continue;
            cell.setItem(stack, Math.max(1, stack.getCount()));
            keepPlacedCircuit(itemBase + itemLocal + i, cell);
        }
        for (int i = 0; i < fluidCount; i++) {
            ShanhaiIoTable.Cell cell = table.cell(fluidBase + fluidLocal + i);
            FluidStack stack = fluids.get(i);
            if (cell == null || cell.itemKind || stack == null || stack.isEmpty()) continue;
            cell.setFluid(stack);
        }
        changed.run();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOverElement(mouseX, mouseY)) return false;
        if (button == 1) {
            ShanhaiIoTable.Cell current = cell();
            if (current == null) return true;
            if (!current.empty()) {
                before.run();
                current.clear();
                current.chance = 10000;
                current.maxChance = 10000;
                current.tierChanceBoost = 0;
                changed.run();
            }
            return true;
        }
        if (button == 2) {
            ShanhaiIoTable.Cell current = cell();
            if (current == null) return true;
            int index = indexSupplier.getAsInt();
            ShanhaiRecipeStackPickerBridge.open(!current.itemKind,
                    (items, fluids) -> applyBatch(index, items, fluids));
            return true;
        }
        if (button == 0) {
            onSelect.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        ShanhaiIoTable.Cell current = cell();
        if (current == null) return;
        int x = getPositionX();
        int y = getPositionY();
        float strength = selected.getAsBoolean() ? (float) accent.getAsDouble() : 0f;
        if (strength > 0.02f) {
            int alpha = Math.min(255, Math.round(255f * strength));
            int inset = Math.round((1f - strength) * 3f);
            graphics.fill(x - 1 + inset, y - 1 + inset, x + CELL + 1 - inset, y + CELL + 1 - inset,
                    (alpha << 24) | 0x0069E8FF);
        }
        graphics.fill(x, y, x + CELL, y + CELL, current.itemKind ? FRAME_ITEM : FRAME_FLUID);
        graphics.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF272A35);
        if (current.itemKind && !current.item.isEmpty()) {
            new ItemStackTexture(current.item).draw(graphics, mouseX, mouseY, x + 2, y + 2, 16, 16);
            graphics.renderItemDecorations(Minecraft.getInstance().font, current.item, x + 2, y + 2);
        } else if (!current.itemKind && !current.fluid.isEmpty()) {
            drawFluid(graphics, current.fluid, x + 2, y + 2);
            smallTextRight(graphics, compactCount(current.shownCount()), x + CELL - 2, y + CELL - 1);
        }
        if (notConsumed(current)) {
            drawNotConsumed(graphics, x + 2, y + 2);
        } else if (current.chance != 10000) {
            smallText(graphics, (current.chance * 100 / Math.max(1, current.maxChance)) + "%", x + 2, y + CELL - 1);
        }
        if (current.matchTag != null && !current.matchTag.isEmpty()) {
            graphics.drawString(Minecraft.getInstance().font, "T", x + CELL - 6, y + 1, 0xFF69E8FF, false);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ShanhaiIoTable.Cell current = cell();
        if (current == null || current.empty()) {
            setHoverTooltips(List.of(Component.literal("灰框物品 / 蓝框流体，JEI 拖入，中键打开选取器")));
        } else if (current.itemKind) {
            List<Component> tips = new ArrayList<>();
            tips.add(current.item.getHoverName());
            tips.add(Component.literal("数量 " + current.item.getCount()));
            String detail = ShanhaiIoTable.stackDetail(current.item);
            if (!detail.isEmpty()) tips.add(Component.literal(detail));
            tips.addAll(matchTips(current));
            tips.add(chanceTip(current));
            tips.add(Component.literal("右键取消选取"));
            tips.add(Component.literal("中键打开选取器"));
            setHoverTooltips(tips);
        } else {
            List<Component> tips = new ArrayList<>();
            tips.add(current.fluid.getDisplayName());
            tips.add(Component.literal("数量 " + current.fluid.getAmount() + " mB"));
            tips.addAll(matchTips(current));
            tips.add(chanceTip(current));
            tips.add(Component.literal("右键取消选取"));
            tips.add(Component.literal("中键打开选取器"));
            setHoverTooltips(tips);
        }
    }

    private static List<Component> matchTips(ShanhaiIoTable.Cell current) {
        List<Component> tips = new ArrayList<>();
        if (current.matchTag != null && !current.matchTag.isEmpty()) {
            tips.add(Component.literal("标签 #" + current.matchTag).withStyle(ChatFormatting.AQUA));
        }
        List<String> tags = ShanhaiIoTable.tagsOf(current);
        int shown = Math.min(6, tags.size());
        for (int i = 0; i < shown; i++) {
            tips.add(Component.literal("#" + tags.get(i)).withStyle(ChatFormatting.GRAY));
        }
        if (tags.size() > shown) {
            tips.add(Component.literal("…共 " + tags.size() + " 个标签").withStyle(ChatFormatting.DARK_GRAY));
        }
        return tips;
    }

    /** A configured programmed circuit placed on an input defaults to not consumed. */
    private void keepPlacedCircuit(int index, ShanhaiIoTable.Cell cell) {
        if (table == null || cell == null || ShanhaiIoTable.circuitConfiguration(cell.item) < 0) return;
        if (table.kindOf(index) != ShanhaiIoTable.Kind.ITEM_IN) return;
        if (cell.chance == 10000 && cell.maxChance == 10000 && cell.tierChanceBoost == 0) {
            cell.chance = 0;
        }
    }

    private boolean notConsumed(ShanhaiIoTable.Cell current) {
        if (current == null || current.chance != 0 || table == null) return false;
        int index = indexSupplier.getAsInt();
        if (index < 0) return false;
        ShanhaiIoTable.Kind kind = table.kindOf(index);
        return kind == ShanhaiIoTable.Kind.ITEM_IN || kind == ShanhaiIoTable.Kind.FLUID_IN;
    }

    private Component chanceTip(ShanhaiIoTable.Cell current) {
        if (notConsumed(current)) return Component.literal("不消耗").withStyle(ChatFormatting.RED);
        return Component.literal("概率 " + current.chance + "/" + current.maxChance);
    }

    /** Red 不 at half size, top-right of the icon. Same scale GTCEu uses for chance marks. */
    private static void drawNotConsumed(GuiGraphics graphics, int iconX, int iconY) {
        var font = Minecraft.getInstance().font;
        String mark = "不";
        graphics.pose().pushPose();
        graphics.pose().translate(iconX + 16, iconY, 400f);
        graphics.pose().scale(0.5f, 0.5f, 1f);
        graphics.drawString(font, mark, -font.width(mark), 0, 0xFFFF0000, true);
        graphics.pose().popPose();
    }

    static Object normalize(Object ingredient, boolean itemKind) {
        if (ingredient instanceof ITypedIngredient<?> typed) {
            try {
                ItemStack stack = typed.getItemStack().orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) ingredient = stack;
                else ingredient = typed.getIngredient();
            } catch (Throwable ignored) {
                ingredient = typed.getIngredient();
            }
        }
        if (itemKind) {
            if (ingredient instanceof ItemStack stack && !stack.isEmpty()) return stack;
            if (ingredient instanceof Ingredient recipeIngredient) {
                ItemStack[] stacks = recipeIngredient.getItems();
                return stacks.length == 0 ? null : stacks[0];
            }
            return null;
        }
        if (ingredient instanceof FluidStack fluid && !fluid.isEmpty()) return fluid;
        Object converted = com.lowdragmc.lowdraglib.gui.widget.PhantomFluidWidget
                .checkJEIIngredient(ingredient);
        return converted instanceof FluidStack fluid && !fluid.isEmpty() ? fluid : null;
    }

    private static void drawFluid(GuiGraphics graphics, FluidStack stack, int x, int y) {
        ResourceLocation still = null;
        int tint = 0xFFFFFFFF;
        try {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(stack.getFluid());
            still = ext.getStillTexture();
            tint = ext.getTintColor();
        } catch (Throwable ignored) {
            // Fall back to a tinted block.
        }
        if (still == null) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000 | (tint & 0x00FFFFFF));
            return;
        }
        try {
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(((tint >> 16) & 0xFF) / 255f,
                    ((tint >> 8) & 0xFF) / 255f, (tint & 0xFF) / 255f, 1f);
            graphics.blit(x, y, 0, 16, 16, sprite);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        } catch (Throwable ignored) {
            graphics.fill(x, y, x + 16, y + 16, 0xC0000000 | (tint & 0x00FFFFFF));
        }
    }

    private static void smallText(GuiGraphics graphics, String value, int x, int y) {
        if (value == null || value.isEmpty()) return;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y - 5, 300);
        graphics.pose().scale(.5f, .5f, 1f);
        graphics.drawString(Minecraft.getInstance().font, value, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static void smallTextRight(GuiGraphics graphics, String value, int right, int bottom) {
        if (value == null || value.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        graphics.pose().translate(right, bottom, 300);
        graphics.pose().scale(.5f, .5f, 1f);
        graphics.drawString(font, value, -font.width(value), -9, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private static String compactCount(int count) {
        int value = Math.max(0, count);
        if (value < 1000) return Integer.toString(value);
        if (value < 1_000_000) return (value / 1000) + "k";
        return (value / 1_000_000) + "m";
    }
}
