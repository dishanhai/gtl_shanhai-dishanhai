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

import java.util.ArrayList;
import java.util.List;

/**
 * 星律库存页上的标签拉取，以及按 ME 请求器排列的维持量、批量和提交。
 */
final class PatternBufferStockRequestConfigurator implements IFancyConfigurator {

    private static final int ROWS = 4;
    private static final ResourceTexture ICON = new ResourceTexture("gtceu:textures/gui/list.png");

    private final RecipeTypePatternBufferPartMachine machine;
    private WidgetGroup page;
    private int requestPage;

    PatternBufferStockRequestConfigurator(RecipeTypePatternBufferPartMachine machine) {
        this.machine = machine;
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
        page = new WidgetGroup(0, 0, 176, 176);
        fill(page);
        return page;
    }

    private void fill(WidgetGroup group) {
        group.addWidget(new LabelWidget(4, 2, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_tag.title").getString()));
        group.addWidget(new LabelWidget(110, 2, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_tag.filled",
                machine.stockTagFilledCount()).getString()));
        group.addWidget(new LabelWidget(4, 14, () -> Component.translatable("gui.gtlcore.tag_whitelist").getString()));
        TextFieldWidget white = new TextFieldWidget(4, 24, 168, 12,
                machine::stockTagWhite, machine::setStockTagWhite);
        white.setCurrentString(machine.stockTagWhite());
        group.addWidget(white);
        group.addWidget(new LabelWidget(4, 38, () -> Component.translatable("gui.gtlcore.tag_blacklist").getString()));
        TextFieldWidget black = new TextFieldWidget(4, 48, 168, 12,
                machine::stockTagBlack, machine::setStockTagBlack);
        black.setCurrentString(machine.stockTagBlack());
        group.addWidget(black);
        group.addWidget(new LabelWidget(22, 64, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.label").getString()));
        group.addWidget(new SwitchWidget(4, 64, 14, 12, (click, enabled) -> machine.setStockRequestEnabled(enabled))
                .setTexture(new ColorBorderTexture(-1, 0xFFFF3030), new ColorBorderTexture(-1, 0xFF20B24B))
                .setPressed(machine.stockRequestEnabled())
                .setSupplier(machine::stockRequestEnabled)
                .setHoverTooltips(
                        Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.on"),
                        Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.off")));
        group.addWidget(new LabelWidget(52, 80, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.amount").getString()));
        group.addWidget(new LabelWidget(94, 80, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.batch").getString()));
        group.addWidget(new LabelWidget(132, 80, () -> Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.submit").getString()));

        List<Channel> channels = channels();
        int pages = Math.max(1, (channels.size() + ROWS - 1) / ROWS);
        if (requestPage >= pages) {
            requestPage = pages - 1;
        }
        if (requestPage < 0) {
            requestPage = 0;
        }
        if (channels.isEmpty()) {
            group.addWidget(new LabelWidget(4, 96, () -> Component.translatable(
                    "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.empty").getString()));
        } else {
            int start = requestPage * ROWS;
            for (int row = 0; row < ROWS && start + row < channels.size(); row++) {
                addRow(group, channels.get(start + row), 94 + row * 16);
            }
        }
        group.addWidget(new ButtonWidget(4, 160, 16, 12, new TextTexture("<", -1), click -> changePage(-1)));
        group.addWidget(new LabelWidget(70, 162, () -> (requestPage + 1) + " / " + Math.max(1,
                (channels().size() + ROWS - 1) / ROWS)));
        group.addWidget(new ButtonWidget(156, 160, 16, 12, new TextTexture(">", -1), click -> changePage(1)));
    }

    private void addRow(WidgetGroup group, Channel channel, int y) {
        group.addWidget(new SwitchWidget(4, y, 12, 12,
                (click, enabled) -> machine.setStockRequestRowEnabled(channel.fluid, channel.slot, enabled))
                .setTexture(new ColorBorderTexture(-1, 0xFFFF3030), new ColorBorderTexture(-1, 0xFF20B24B))
                .setPressed(machine.stockRequestRowEnabled(channel.fluid, channel.slot))
                .setSupplier(() -> machine.stockRequestRowEnabled(channel.fluid, channel.slot))
                .setHoverTooltips(channel.key.getDisplayName()));
        group.addWidget(new LabelWidget(18, y + 2, () -> rowLabel(channel))
                .setHoverTooltips(channel.key.getDisplayName()));
        TextFieldWidget amount = new TextFieldWidget(52, y, 40, 12, null,
                text -> machine.setStockRequestDraft(channel.fluid, channel.slot, true, text));
        amount.setCurrentString(machine.stockRequestDraft(channel.fluid, channel.slot, true));
        amount.setHoverTooltips(Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.amount_tip"));
        group.addWidget(amount);
        TextFieldWidget batch = new TextFieldWidget(94, y, 36, 12, null,
                text -> machine.setStockRequestDraft(channel.fluid, channel.slot, false, text));
        batch.setCurrentString(machine.stockRequestDraft(channel.fluid, channel.slot, false));
        batch.setHoverTooltips(Component.translatable(
                "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.batch_tip"));
        group.addWidget(batch);
        group.addWidget(new ButtonWidget(132, y, 40, 12, new TextTexture(
                Component.translatable("gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.submit")
                        .getString(), -1),
                click -> machine.submitStockRequest(channel.fluid, channel.slot)));
    }

    private void changePage(int delta) {
        int pages = Math.max(1, (channels().size() + ROWS - 1) / ROWS);
        int next = requestPage + delta;
        if (next < 0 || next >= pages || page == null) {
            return;
        }
        requestPage = next;
        page.clearAllWidgets();
        fill(page);
    }

    private List<Channel> channels() {
        List<Channel> channels = new ArrayList<>();
        collect(channels, machine.stockItemHandler.getInventory(), false);
        collect(channels, machine.stockFluidHandler.getInventory(), true);
        return channels;
    }

    private static void collect(List<Channel> channels, ExportOnlyAESlot[] slots, boolean fluid) {
        for (int i = 0; i < slots.length; i++) {
            GenericStack config = slots[i].getConfig();
            if (config != null && config.what() != null) {
                channels.add(new Channel(fluid, i, config.what()));
            }
        }
    }

    private static String rowLabel(Channel channel) {
        String kind = Component.translatable(channel.fluid
                ? "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.fluid_row"
                : "gt_shanhai.machine.recipe_type_pattern_buffer.stock_request.item_row").getString();
        return kind + (channel.slot + 1);
    }

    private static final class Channel {
        private final boolean fluid;
        private final int slot;
        private final AEKey key;

        private Channel(boolean fluid, int slot, AEKey key) {
            this.fluid = fluid;
            this.slot = slot;
            this.key = key;
        }
    }
}
