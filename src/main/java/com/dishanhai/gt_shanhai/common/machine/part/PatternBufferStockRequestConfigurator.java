package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.network.chat.Component;

/**
 * 星律库存页上的标签拉取。选中的格子在库存槽下方改维持量、批量和提交。
 */
final class PatternBufferStockRequestConfigurator implements IFancyConfigurator {

    private static final ResourceTexture ICON = new ResourceTexture("gtceu:textures/gui/list.png");

    private final RecipeTypePatternBufferPartMachine machine;
    private WidgetGroup pickPanel;

    PatternBufferStockRequestConfigurator(RecipeTypePatternBufferPartMachine machine) {
        this.machine = machine;
        machine.bindStockRequestUi(this);
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_tag.title");
    }

    @Override
    public IGuiTexture getIcon() {
        return ICON;
    }

    @Override
    public Widget createConfigurator() {
        WidgetGroup page = new WidgetGroup(0, 0, 176, 80);
        page.addWidget(new LabelWidget(4, 2, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_tag.title").getString()));
        page.addWidget(new LabelWidget(110, 2, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_tag.filled",
                machine.stockTagFilledCount()).getString()));
        page.addWidget(new LabelWidget(4, 14, () -> Component.translatable("gui.gtlcore.tag_whitelist").getString()));
        TextFieldWidget white = new TextFieldWidget(4, 24, 168, 12,
                machine::stockTagWhite, machine::setStockTagWhite);
        white.setCurrentString(machine.stockTagWhite());
        page.addWidget(white);
        page.addWidget(new LabelWidget(4, 38, () -> Component.translatable("gui.gtlcore.tag_blacklist").getString()));
        TextFieldWidget black = new TextFieldWidget(4, 48, 168, 12,
                machine::stockTagBlack, machine::setStockTagBlack);
        black.setCurrentString(machine.stockTagBlack());
        page.addWidget(black);
        page.addWidget(new LabelWidget(22, 64, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.label").getString()));
        page.addWidget(new SwitchWidget(4, 64, 14, 12, (click, enabled) -> machine.setStockRequestEnabled(enabled))
                .setTexture(new ColorBorderTexture(-1, 0xFFFF3030), new ColorBorderTexture(-1, 0xFF20B24B))
                .setPressed(machine.stockRequestEnabled())
                .setSupplier(machine::stockRequestEnabled)
                .setHoverTooltips(
                        Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.on"),
                        Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.off")));
        return page;
    }

    Widget createPickPanel(int x, int y) {
        pickPanel = new WidgetGroup(x, y, 176, 34);
        fillPickPanel(pickPanel);
        return pickPanel;
    }

    void rebuildPickPanel() {
        if (pickPanel == null) {
            return;
        }
        pickPanel.clearAllWidgets();
        fillPickPanel(pickPanel);
    }

    private void fillPickPanel(WidgetGroup group) {
        if (!machine.stockPickSet()) {
            group.addWidget(new LabelWidget(4, 2, () -> Component.translatable(
                    "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.empty").getString()));
            return;
        }
        boolean fluid = machine.stockPickFluid();
        int slot = machine.stockPickSlot();
        group.addWidget(new SwitchWidget(4, 0, 12, 12,
                (click, enabled) -> machine.setStockRequestRowEnabled(fluid, slot, enabled))
                .setTexture(new ColorBorderTexture(-1, 0xFFFF3030), new ColorBorderTexture(-1, 0xFF20B24B))
                .setPressed(machine.stockRequestRowEnabled(fluid, slot))
                .setSupplier(() -> machine.stockRequestRowEnabled(fluid, slot))
                .setHoverTooltips(selectedName()));
        group.addWidget(new LabelWidget(20, 2, this::selectedLabel).setHoverTooltips(selectedName()));
        group.addWidget(new LabelWidget(4, 18, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.amount").getString()));
        TextFieldWidget amount = new TextFieldWidget(28, 16, 36, 12, null,
                text -> machine.setStockRequestDraft(fluid, slot, true, text));
        amount.setCurrentString(machine.stockRequestDraft(fluid, slot, true));
        amount.setHoverTooltips(Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.amount_tip"));
        group.addWidget(amount);
        group.addWidget(new LabelWidget(68, 18, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.batch").getString()));
        TextFieldWidget batch = new TextFieldWidget(92, 16, 32, 12, null,
                text -> machine.setStockRequestDraft(fluid, slot, false, text));
        batch.setCurrentString(machine.stockRequestDraft(fluid, slot, false));
        batch.setHoverTooltips(Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.batch_tip"));
        group.addWidget(batch);
        group.addWidget(new ButtonWidget(128, 16, 44, 12, new TextTexture(
                Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.submit")
                        .getString(), -1),
                click -> machine.submitStockRequest(fluid, slot)));
    }

    private String selectedLabel() {
        return Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.selected",
                selectedName().getString()).getString();
    }

    private Component selectedName() {
        AEKey key = selectedKey();
        if (key == null) {
            return Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.empty");
        }
        return key.getDisplayName();
    }

    private AEKey selectedKey() {
        if (!machine.stockPickSet()) {
            return null;
        }
        ExportOnlyAESlot[] slots = machine.stockPickFluid()
                ? machine.stockFluidHandler.getInventory()
                : machine.stockItemHandler.getInventory();
        int slot = machine.stockPickSlot();
        if (slot < 0 || slot >= slots.length) {
            return null;
        }
        GenericStack config = slots[slot].getConfig();
        if (config == null) {
            return null;
        }
        return config.what();
    }
}
