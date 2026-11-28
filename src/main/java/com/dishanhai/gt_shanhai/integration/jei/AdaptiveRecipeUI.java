package com.dishanhai.gt_shanhai.integration.jei;

// Author: dishanhai

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ui.GTRecipeTypeUI;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.ProgressWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** Fills the two I/O regions declared by an RTUI, keeping each ingredient registered with JEI. */
public final class AdaptiveRecipeUI {
    static final String TEXT = "gt_shanhai.adaptive_recipe_ui.";
    private static final String CARD = "shanhai_adaptive_recipe";
    private static final String INPUTS = "adaptive_inputs";
    private static final String OUTPUTS = "adaptive_outputs";
    private static final int SLOT_SIZE = 18;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final int HEADING_HEIGHT = 11;

    private AdaptiveRecipeUI() {}

    public static void populate(GTRecipe recipe, Table<IO, RecipeCapability<?>, List<Content>> contents,
                                WidgetGroup card) {
        Widget inputs = child(card, INPUTS);
        Widget outputs = child(card, OUTPUTS);
        if (inputs != null || outputs != null) card.setId(CARD);
        if (inputs != null) populatePane(recipe, contents, card, inputs, IO.IN);
        if (outputs != null) populatePane(recipe, contents, card, outputs, IO.OUT);
    }

    static boolean hasAdaptiveCard(Widget widget) {
        return widget instanceof WidgetGroup root && child(root, CARD) instanceof WidgetGroup;
    }

    static void expand(WidgetGroup root, GTRecipe recipe, int width, int height, int headerHeight) {
        var card = (WidgetGroup) child(root, CARD);
        var inputs = child(card, INPUTS);
        var outputs = child(card, OUTPUTS);
        var progress = child(card, "progress");
        int oldWidth = root.getSizeWidth();
        int contentGrowth = height - root.getSizeHeight() - headerHeight;
        int contentHeight = card.getSizeHeight() + contentGrowth;
        int margin = 10;
        int inputWidth = Math.min(130, Math.max(62, width / 5));
        int gap = progress == null ? 12 : progress.getSizeWidth() + 16;
        int outputsX = inputs == null ? margin : margin + inputWidth + gap;
        int paneHeight = contentHeight - 2 * margin;
        root.setSelfPosition(0, 0);
        root.setSize(width, height);
        card.setSelfPosition(0, headerHeight);
        card.setSize(width, contentHeight);
        for (Widget footer : root.widgets) {
            if (footer == card) continue;
            if (footer.getSelfPositionX() >= oldWidth / 2) footer.addSelfPosition(width - oldWidth, 0);
            footer.addSelfPosition(0, contentGrowth + headerHeight);
        }
        if (progress != null) {
            int progressX = inputs == null || outputs == null ? width - margin - progress.getSizeWidth() : margin + inputWidth + 8;
            int firstSlotCenterY = margin + HEADING_HEIGHT + SLOT_SIZE / 2;
            progress.setSelfPosition(progressX, firstSlotCenterY - progress.getSizeHeight() / 2);
        }
        var contents = HashBasedTable.<IO, RecipeCapability<?>, List<Content>>create();
        if (inputs != null) {
            card.removeWidget(inputs);
            int availableWidth = outputs == null ? width - 2 * margin - gap : inputWidth;
            addPane(recipe, contents, card, new Widget(margin, margin, availableWidth, paneHeight).setId(INPUTS), IO.IN);
        }
        if (outputs != null) {
            card.removeWidget(outputs);
            int availableWidth = width - margin - outputsX - (inputs == null && progress != null ? gap : 0);
            addPane(recipe, contents, card, new Widget(outputsX, margin, availableWidth, paneHeight).setId(OUTPUTS), IO.OUT);
        }
    }

    private static Widget child(WidgetGroup group, String id) {
        for (Widget child : group.widgets) {
            if (id.equals(child.getId())) return child;
        }
        return null;
    }

    private static void populatePane(GTRecipe recipe, Table<IO, RecipeCapability<?>, List<Content>> contents,
                                     WidgetGroup card, Widget anchor, IO io) {
        card.removeWidget(anchor);
        addPane(recipe, contents, card, anchor, io);
    }

    private static void addPane(GTRecipe recipe, Table<IO, RecipeCapability<?>, List<Content>> contents,
                                WidgetGroup card, Widget anchor, IO io) {
        var items = combinedContents(recipe, io, ItemRecipeCapability.CAP);
        var fluids = combinedContents(recipe, io, FluidRecipeCapability.CAP);
        boolean research = io == IO.IN && recipe.recipeType.isHasResearchSlot() && ConfigHolder.INSTANCE.machines.enableResearch
                && recipe.conditions.stream().anyMatch(ResearchCondition.class::isInstance);
        int itemSlots = items.size() + (research ? 1 : 0);
        if (itemSlots == 0 && fluids.isEmpty()) return;

        int columns = Math.max(1, (anchor.getSizeWidth() - SCROLLBAR_WIDTH) / SLOT_SIZE);
        int itemHeight = sectionHeight(itemSlots, columns);
        int fluidHeight = sectionHeight(fluids.size(), columns);
        int available = anchor.getSizeHeight();
        int itemViewport = Math.min(itemHeight, available);
        if (itemHeight > 0 && fluidHeight > 0 && itemHeight + fluidHeight > available) {
            int rows = (available - 2 * HEADING_HEIGHT) / SLOT_SIZE;
            int itemRows = Math.max(1, Math.min(rows - 1, Math.ceilDiv(rows * 2, 3)));
            int preferred = HEADING_HEIGHT + itemRows * SLOT_SIZE;
            itemViewport = Math.min(itemHeight, Math.max(preferred, available - fluidHeight));
        }
        int fluidViewport = Math.min(fluidHeight, available - itemViewport);
        var pane = new WidgetGroup(anchor.getSelfPosition().x, anchor.getSelfPosition().y,
                anchor.getSizeWidth(), itemViewport + fluidViewport);
        pane.setId(anchor.getId());
        card.addWidget(pane);

        String direction = io == IO.IN ? "_in" : "_out";
        addSection(recipe, contents, pane, io, ItemRecipeCapability.CAP, items, itemSlots, columns, 0, itemViewport,
                "item" + direction, 0xfff2d79c);
        addSection(recipe, contents, pane, io, FluidRecipeCapability.CAP, fluids, fluids.size(), columns,
                itemViewport, fluidViewport, "fluid" + direction, 0xffa5eafa);
    }

    static List<Content> combinedContents(GTRecipe recipe, IO io, RecipeCapability<?> capability) {
        var normal = io == IO.IN ? recipe.getInputContents(capability) : recipe.getOutputContents(capability);
        var recurring = (io == IO.IN ? recipe.tickInputs : recipe.tickOutputs).getOrDefault(capability, List.of());
        if (recurring.isEmpty()) return normal;
        var merged = new ArrayList<Content>(normal.size() + recurring.size());
        merged.addAll(normal);
        merged.addAll(recurring);
        return merged;
    }

    static int sectionHeight(int count, int columns) {
        return count == 0 ? 0 : HEADING_HEIGHT + Math.ceilDiv(count, columns) * SLOT_SIZE;
    }

    private static void addSection(GTRecipe recipe, Table<IO, RecipeCapability<?>, List<Content>> contents,
                                   WidgetGroup pane, IO io, RecipeCapability<?> capability, List<Content> entries,
                                   int totalSlots, int columns, int y, int height, String heading, int color) {
        if (totalSlots == 0) return;
        contents.put(io, capability, entries);
        var storage = capability.createXEIContainer(capability.createXEIContainerContents(entries, recipe, io));
        var holder = new GTRecipeTypeUI.RecipeHolder(ProgressWidget.JEIProgress,
                ImmutableTable.of(io, capability, storage), recipe.data, recipe.conditions, false, false);
        pane.addWidget(new LabelWidget(0, y, Component.translatable(TEXT + heading))
                .setTextColor(color).setDropShadow(true));
        int bodyHeight = ((height - HEADING_HEIGHT) / SLOT_SIZE) * SLOT_SIZE;
        int bodyY = y + HEADING_HEIGHT;
        WidgetGroup body;
        int overflow = sectionHeight(totalSlots, columns) - HEADING_HEIGHT - bodyHeight;
        if (overflow > 0) {
            var scroll = new ScrollViewport(bodyY, pane.getSizeWidth(), bodyHeight, overflow);
            scroll.setYScrollBarWidth(SCROLLBAR_WIDTH)
                    .setYBarStyle(new ColorRectTexture(0x70445a70), new ColorRectTexture(0xff86a9c2));
            body = scroll;
        } else {
            body = new WidgetGroup(0, bodyY, pane.getSizeWidth(), bodyHeight);
        }
        body.setId("adaptive_" + heading);
        pane.addWidget(body);
        for (int start = 0; start < totalSlots; start += columns) {
            int slots = Math.min(columns, totalSlots - start);
            var row = new WidgetGroup(0, (start / columns) * SLOT_SIZE, slots * SLOT_SIZE, SLOT_SIZE);
            for (int column = 0; column < slots; column++) {
                int index = start + column;
                Widget slot = capability == ItemRecipeCapability.CAP ? itemSlot(body) : fluidSlot(body);
                slot.setId(capability.slotName(io, index));
                slot.setSelfPosition(column * SLOT_SIZE, 0);
                capability.applyWidgetInfo(slot, index, true, io, holder, recipe.recipeType, recipe, null, storage);
                row.addWidget(slot);
            }
            body.addWidget(row);
        }
    }

    private static SlotWidget itemSlot(WidgetGroup pane) {
        return new ItemSlot(pane);
    }

    private static TankWidget fluidSlot(WidgetGroup pane) {
        var slot = new TankWidget() {
            @Override
            public boolean isMouseOverElement(double x, double y) {
                return inside(pane, x, y) && super.isMouseOverElement(x, y);
            }
        };
        slot.initTemplate();
        slot.setFillDirection(ProgressTexture.FillDirection.ALWAYS_FULL);
        slot.setBackground(GuiTextures.FLUID_SLOT);
        return slot;
    }

    private static boolean inside(WidgetGroup pane, double x, double y) {
        return Widget.isMouseOver(pane.getPositionX(), pane.getPositionY(), pane.getSizeWidth(), pane.getSizeHeight(), x, y);
    }

    public static boolean usesCompactCount(Widget widget) {
        return widget instanceof ItemSlot;
    }

    private static final class ItemSlot extends SlotWidget {
        private final WidgetGroup pane;

        ItemSlot(WidgetGroup pane) {
            this.pane = pane;
            initTemplate();
            setBackgroundTexture(GuiTextures.SLOT);
        }

        @Override
        public boolean isMouseOverElement(double x, double y) {
            return inside(pane, x, y) && super.isMouseOverElement(x, y);
        }
    }

    static final class ScrollViewport extends DraggableScrollableWidgetGroup {
        private final int overflow;

        ScrollViewport(int y, int width, int height, int overflow) {
            super(0, y, width, height);
            this.overflow = overflow;
        }

        @Override
        public boolean mouseWheelMove(double x, double y, double delta) {
            if (delta == 0 || !inside(this, x, y)) return false;
            int step = delta > 0 ? -SLOT_SIZE : SLOT_SIZE;
            setScrollYOffset(Mth.clamp(getScrollYOffset() + step, 0, overflow));
            return true;
        }

        @Override
        public boolean mouseReleased(double x, double y, int button) {
            boolean handled = super.mouseReleased(x, y, button);
            if (handled) setScrollYOffset((getScrollYOffset() + SLOT_SIZE / 2) / SLOT_SIZE * SLOT_SIZE);
            return handled;
        }
    }
}
