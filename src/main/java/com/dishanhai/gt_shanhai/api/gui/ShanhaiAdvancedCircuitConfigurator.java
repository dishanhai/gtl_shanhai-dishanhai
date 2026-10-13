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

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * 电路设置页。格子和数量只认服务器推下来的掩码。
 * 重开界面时客户端电路槽经常还是空的，读槽会把已开启画成关闭，数量却仍是服务器上的旧数字。
 */
public final class ShanhaiAdvancedCircuitConfigurator {

    private ShanhaiAdvancedCircuitConfigurator() {}

    public static Widget create(ItemStackTransfer circuitSlot) {
        WidgetGroup group = new WidgetGroup(0, 0, 174, 100);
        ButtonWidget[] buttons = new ButtonWidget[ShanhaiAdvancedCircuitSelection.CIRCUIT_COUNT];
        IGuiTexture[] selectedLook = new IGuiTexture[ShanhaiAdvancedCircuitSelection.CIRCUIT_COUNT];
        IGuiTexture[] idleLook = new IGuiTexture[ShanhaiAdvancedCircuitSelection.CIRCUIT_COUNT];
        MaskSync sync = new MaskSync(circuitSlot, buttons, selectedLook, idleLook);
        group.addWidget(new ButtonWidget(4, 2, 40, 14,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(
                        Component.translatable("gt_shanhai.gui.advanced_circuit.select_all").getString(), -1)
                        .setDropShadow(true)),
                click -> {
                    if (click.isRemote) {
                        sync.preview(ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS);
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
                        sync.preview(0L);
                    } else {
                        ShanhaiAdvancedCircuitSelection.clear(circuitSlot);
                    }
                }).setHoverTooltips("清空已选电路"));
        LabelWidget count = new LabelWidget(92, 5, () -> Long.bitCount(sync.mask()) + "/33");
        count.setClientSideWidget();
        sync.attach(count);
        group.addWidget(count);

        int index = 0;
        for (int row = 0; row <= 2; row++) {
            for (int column = 0; column <= 8; column++) {
                addCircuitButton(group, buttons, selectedLook, idleLook, sync, circuitSlot, index,
                        6 + 18 * column, 20 + 18 * row);
                index++;
            }
        }
        for (int column = 0; column <= 5; column++) {
            addCircuitButton(group, buttons, selectedLook, idleLook, sync, circuitSlot, index,
                    6 + 18 * column, 74);
            index++;
        }
        group.addWidget(sync);
        return group;
    }

    private static void addCircuitButton(WidgetGroup group, ButtonWidget[] buttons, IGuiTexture[] selectedLook,
                                         IGuiTexture[] idleLook, MaskSync sync, ItemStackTransfer circuitSlot,
                                         int configuration, int x, int y) {
        // 开和关各用一份图标。边框和暗色必须画在图标上面。
        selectedLook[configuration] = new GuiTextureGroup(GuiTextures.SLOT, circuitIcon(configuration),
                new ColorRectTexture(0x6637FF6E),
                new ColorBorderTexture(2, 0xFF39FF6A));
        idleLook[configuration] = new GuiTextureGroup(GuiTextures.SLOT, circuitIcon(configuration),
                new ColorRectTexture(0xC0101018),
                new ColorBorderTexture(1, 0xFF8A3A3A));
        ButtonWidget button = new ButtonWidget(x, y, 18, 18, idleLook[configuration], click -> {
            if (click.isRemote) {
                sync.preview(ShanhaiAdvancedCircuitSelection.toggleBit(sync.mask(), configuration));
            } else {
                ShanhaiAdvancedCircuitSelection.toggle(circuitSlot, configuration);
            }
        });
        buttons[configuration] = button;
        group.addWidget(button);
    }

    private static IGuiTexture circuitIcon(int configuration) {
        return new ItemStackTexture(IntCircuitBehaviour.stack(configuration)).scale(0.888f);
    }

    /**
     * 服务器在打开界面和槽位变化时写下掩码。客户端只按这份掩码画格子和数量。
     */
    private static final class MaskSync extends Widget {
        private final ItemStackTransfer circuitSlot;
        private final ButtonWidget[] buttons;
        private final IGuiTexture[] selected;
        private final IGuiTexture[] idle;
        private LabelWidget count;
        private long mask;
        private long sent = Long.MIN_VALUE;
        private long painted = Long.MIN_VALUE;

        private MaskSync(ItemStackTransfer circuitSlot, ButtonWidget[] buttons,
                         IGuiTexture[] selected, IGuiTexture[] idle) {
            super(0, 0, 0, 0);
            this.circuitSlot = circuitSlot;
            this.buttons = buttons;
            this.selected = selected;
            this.idle = idle;
        }

        private long mask() {
            return this.mask;
        }

        private void attach(LabelWidget count) {
            this.count = count;
        }

        private void preview(long next) {
            show(next);
        }

        @Override
        public void writeInitialData(FriendlyByteBuf buffer) {
            long now = ShanhaiAdvancedCircuitSelection.currentMask(this.circuitSlot.getStackInSlot(0));
            this.sent = now;
            buffer.writeLong(now);
        }

        @Override
        public void readInitialData(FriendlyByteBuf buffer) {
            show(buffer.readLong());
        }

        @Override
        public void detectAndSendChanges() {
            long now = ShanhaiAdvancedCircuitSelection.currentMask(this.circuitSlot.getStackInSlot(0));
            if (now == this.sent) return;
            this.sent = now;
            writeUpdateInfo(1, buffer -> buffer.writeLong(now));
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == 1) {
                show(buffer.readLong());
                return;
            }
            super.readUpdateInfo(id, buffer);
        }

        @Override
        public void updateScreen() {
            super.updateScreen();
            // 槽位同步若把客戶端堆清掉，只按已推送的掩碼補回去，不再反過來改格子。
            if (ShanhaiAdvancedCircuitSelection.currentMask(this.circuitSlot.getStackInSlot(0)) != this.mask) {
                ShanhaiAdvancedCircuitSelection.mirror(this.circuitSlot, this.mask);
            }
        }

        private void show(long next) {
            next &= ShanhaiAdvancedCircuitSelection.ALL_CIRCUITS;
            this.mask = next;
            if (next != this.painted) {
                this.painted = next;
                for (int configuration = 0; configuration < this.buttons.length; configuration++) {
                    if (this.buttons[configuration] == null) continue;
                    boolean on = ShanhaiAdvancedCircuitSelection.isBitSet(next, configuration);
                    this.buttons[configuration].setButtonTexture(on ? this.selected[configuration] : this.idle[configuration]);
                    this.buttons[configuration].setHoverTooltips(on
                            ? "电路 " + configuration + " · 开启"
                            : "电路 " + configuration + " · 关闭");
                }
            }
            ShanhaiAdvancedCircuitSelection.mirror(this.circuitSlot, next);
            if (LDLib.isRemote() && this.count != null) {
                this.count.updateScreen();
            }
        }
    }
}
