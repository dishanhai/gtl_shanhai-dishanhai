package com.dishanhai.gt_shanhai.api.gui;

import com.dishanhai.gt_shanhai.common.machine.circuit.ShanhaiAdvancedCircuitSelection;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.network.chat.Component;

/**
 * 电路设置页：点选切换，全选覆盖 0–32。
 */
public final class ShanhaiAdvancedCircuitConfigurator {

    private ShanhaiAdvancedCircuitConfigurator() {}

    public static Widget create(ItemStackTransfer circuitSlot) {
        WidgetGroup group = new WidgetGroup(0, 0, 174, 100);
        ButtonWidget[] buttons = new ButtonWidget[ShanhaiAdvancedCircuitSelection.CIRCUIT_COUNT];
        IGuiTexture[] selectedLook = new IGuiTexture[ShanhaiAdvancedCircuitSelection.CIRCUIT_COUNT];
        IGuiTexture[] idleLook = new IGuiTexture[ShanhaiAdvancedCircuitSelection.CIRCUIT_COUNT];
        RefreshWidget refresh = new RefreshWidget(circuitSlot, buttons, selectedLook, idleLook);
        group.addWidget(new ButtonWidget(4, 2, 40, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(
                        Component.translatable("gt_shanhai.gui.advanced_circuit.select_all").getString(), -1)
                        .setDropShadow(true)),
                click -> {
                    if (click.isRemote) {
                        refresh.preview(ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS);
                    } else {
                        ShanhaiAdvancedCircuitSelection.selectAll(circuitSlot);
                    }
                }).setHoverTooltips("一次选中 0 到 32，共 33 个电路"));
        group.addWidget(new ButtonWidget(46, 2, 40, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(
                        Component.translatable("gt_shanhai.gui.advanced_circuit.clear").getString(), -1)
                        .setDropShadow(true)),
                click -> {
                    if (click.isRemote) {
                        refresh.preview(0L);
                    } else {
                        ShanhaiAdvancedCircuitSelection.clear(circuitSlot);
                    }
                }).setHoverTooltips("清空已选电路"));
        group.addWidget(new LabelWidget(92, 5, () ->
                Long.bitCount(refresh.displayedMask()) + "/33"));

        int index = 0;
        for (int row = 0; row <= 2; row++) {
            for (int column = 0; column <= 8; column++) {
                addCircuitButton(group, buttons, selectedLook, idleLook, refresh, circuitSlot, index,
                        6 + 18 * column, 20 + 18 * row);
                index++;
            }
        }
        for (int column = 0; column <= 5; column++) {
            addCircuitButton(group, buttons, selectedLook, idleLook, refresh, circuitSlot, index,
                    6 + 18 * column, 74);
            index++;
        }
        refresh.bind(ShanhaiAdvancedCircuitSelection.currentMask(circuitSlot.getStackInSlot(0)));
        group.addWidget(refresh);
        return group;
    }

    private static void addCircuitButton(WidgetGroup group, ButtonWidget[] buttons, IGuiTexture[] selectedLook,
                                         IGuiTexture[] idleLook, RefreshWidget refresh, ItemStackTransfer circuitSlot,
                                         int configuration, int x, int y) {
        IGuiTexture icon = new ItemStackTexture(IntCircuitBehaviour.stack(configuration)).scale(0.888f);
        // 边框和暗色必须画在电路图标上面，画在下面会被图标完全挡住。
        selectedLook[configuration] = new GuiTextureGroup(GuiTextures.SLOT, icon,
                new ColorRectTexture(0x6637FF6E),
                new ColorBorderTexture(2, 0xFF39FF6A));
        idleLook[configuration] = new GuiTextureGroup(GuiTextures.SLOT, icon,
                new ColorRectTexture(0xC0101018),
                new ColorBorderTexture(1, 0xFF8A3A3A));
        ButtonWidget button = new ButtonWidget(x, y, 18, 18, idleLook[configuration], click -> {
            if (click.isRemote) {
                refresh.preview(ShanhaiAdvancedCircuitSelection.toggleBit(refresh.displayedMask(), configuration));
            } else {
                ShanhaiAdvancedCircuitSelection.toggle(circuitSlot, configuration);
            }
        });
        buttons[configuration] = button;
        group.addWidget(button);
    }

    private static final class RefreshWidget extends WidgetGroup {
        private final ItemStackTransfer circuitSlot;
        private final ButtonWidget[] buttons;
        private final IGuiTexture[] selected;
        private final IGuiTexture[] idle;
        private long displayed = 0L;
        private long lastSynced = Long.MIN_VALUE;
        private long painted = Long.MIN_VALUE;

        private RefreshWidget(ItemStackTransfer circuitSlot, ButtonWidget[] buttons,
                              IGuiTexture[] selected, IGuiTexture[] idle) {
            super(0, 0, 0, 0);
            this.circuitSlot = circuitSlot;
            this.buttons = buttons;
            this.selected = selected;
            this.idle = idle;
        }

        private long displayedMask() {
            return this.displayed;
        }

        private void bind(long mask) {
            this.displayed = mask;
            this.lastSynced = mask;
            show(mask);
        }

        private void preview(long mask) {
            this.displayed = mask & ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS;
            show(this.displayed);
        }

        @Override
        public void updateScreen() {
            if (!LDLib.isRemote()) return;
            long synced = ShanhaiAdvancedCircuitSelection.currentMask(circuitSlot.getStackInSlot(0));
            if (synced != this.lastSynced) {
                this.lastSynced = synced;
                this.displayed = synced;
                show(synced);
            }
        }

        private void show(long mask) {
            if (mask == this.painted) return;
            this.painted = mask;
            for (int configuration = 0; configuration < this.buttons.length; configuration++) {
                if (this.buttons[configuration] == null) continue;
                boolean on = ShanhaiAdvancedCircuitSelection.isBitSet(mask, configuration);
                this.buttons[configuration].setButtonTexture(on ? this.selected[configuration] : this.idle[configuration]);
                this.buttons[configuration].setHoverTooltips(on
                        ? "电路 " + configuration + " · 开启"
                        : "电路 " + configuration + " · 关闭");
            }
        }
    }
}
