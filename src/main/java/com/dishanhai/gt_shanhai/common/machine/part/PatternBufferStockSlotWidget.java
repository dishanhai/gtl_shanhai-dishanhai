package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.IConfigurableSlot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.gtlcore.gtlcore.client.gui.widget.AEDualConfigSlotWidget;
import org.gtlcore.gtlcore.client.gui.widget.AEDualConfigWidget;

import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.utils.Position;

import java.util.function.IntConsumer;

/**
 * 库存槽左键点已有物品或流体时，选中这一格给拉取配置。
 * 手上拿着东西时仍走原来的放入逻辑，右键仍是清除。
 */
final class PatternBufferStockSlotWidget extends AEDualConfigWidget {

    private static final int ACT_PICK = 41;
    /** 与 AEDualConfigWidget 发给客户端的库存刷新包相同。客户端格子画的是这份副本，不是本地 handler。 */
    private static final int DISPLAY_UPDATE_ID = 1001;

    private final RecipeTypePatternBufferPartMachine machine;
    private final GenericStack[] shownItems = new GenericStack[StellarSuperPatternBufferPartMachine.CONFIG_SIZE];
    private final GenericStack[] shownFluids = new GenericStack[StellarSuperPatternBufferPartMachine.CONFIG_SIZE];

    PatternBufferStockSlotWidget(int x, int y, ExportOnlyAEItemList items, ExportOnlyAEFluidList fluids,
                                 IntConsumer pageSetter, int page, RecipeTypePatternBufferPartMachine machine) {
        super(x, y, items, fluids, pageSetter, page);
        this.machine = machine;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && cursorEmpty()) {
            int index = hitSlot(mouseX, mouseY);
            if (index >= 0 && (hasDisplay(false, index) || hasDisplay(true, index))) {
                boolean fluid = !hasDisplay(false, index);
                machine.noteStockPick(true, fluid, index);
                int picked = index;
                writeClientAction(ACT_PICK, buf -> {
                    buf.writeBoolean(fluid);
                    buf.writeVarInt(picked);
                });
                refreshHighlight();
                return true;
            }
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        refreshHighlight();
        return handled;
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id == ACT_PICK) {
            boolean fluid = buffer.readBoolean();
            int slot = buffer.readVarInt();
            if (slot >= 0 && hasDisplay(fluid, slot)) {
                machine.noteStockPick(true, fluid, slot);
            }
            refreshHighlight();
            return;
        }
        super.handleClientAction(id, buffer);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == DISPLAY_UPDATE_ID) {
            buffer.markReaderIndex();
            readShown(buffer);
            buffer.resetReaderIndex();
        }
        super.readUpdateInfo(id, buffer);
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        dropPickIfMissing();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        dropPickIfMissing();
        refreshHighlight();
    }

    private void dropPickIfMissing() {
        if (!machine.stockPickSet()) {
            return;
        }
        if (hasDisplay(machine.stockPickFluid(), machine.stockPickSlot())) {
            return;
        }
        machine.noteStockPick(false, false, -1);
        refreshHighlight();
    }

    private void refreshHighlight() {
        for (int i = 0; i < widgets.size(); i++) {
            Widget widget = widgets.get(i);
            if (!(widget instanceof AEDualConfigSlotWidget)) {
                continue;
            }
            AEDualConfigSlotWidget slot = (AEDualConfigSlotWidget) widget;
            int index = slotIndex(slot);
            boolean selected = machine.stockPickSet() && index == machine.stockPickSlot()
                    && hasDisplay(machine.stockPickFluid(), index);
            slot.setSelect(selected);
        }
    }

    private int hitSlot(double mouseX, double mouseY) {
        for (int i = 0; i < widgets.size(); i++) {
            Widget widget = widgets.get(i);
            if (!(widget instanceof AEDualConfigSlotWidget)) {
                continue;
            }
            AEDualConfigSlotWidget slot = (AEDualConfigSlotWidget) widget;
            Position pos = slot.getPosition();
            if (!isMouseOver(pos.x, pos.y, 18, 36, mouseX, mouseY)) {
                continue;
            }
            int index = slotIndex(slot);
            if (hasDisplay(false, index) || hasDisplay(true, index)) {
                return index;
            }
        }
        return -1;
    }

    private int slotIndex(AEDualConfigSlotWidget slot) {
        int col = slot.getSelfPositionX() / 18;
        int row = slot.getSelfPositionY() / 38;
        return getSlotIndex(row * 8 + col);
    }

    private void readShown(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        for (int i = 0; i < size; i++) {
            int index = buffer.readVarInt();
            GenericStack config = buffer.readBoolean() ? GenericStack.readBuffer(buffer) : null;
            if (buffer.readBoolean()) {
                GenericStack.readBuffer(buffer);
            }
            int half = shownItems.length;
            if (index >= 0 && index < half) {
                shownItems[index] = config;
            } else if (index >= half && index < half * 2) {
                shownFluids[index - half] = config;
            }
        }
    }

    private boolean hasDisplay(boolean fluid, int slot) {
        if (slot < 0 || slot >= shownItems.length) {
            return false;
        }
        if (isRemote()) {
            GenericStack stack = fluid ? shownFluids[slot] : shownItems[slot];
            return stack != null && stack.what() != null;
        }
        IConfigurableSlot config = fluid ? getFluidConfig(slot) : getItemConfig(slot);
        if (config == null) {
            return false;
        }
        GenericStack stack = config.getConfig();
        return stack != null && stack.what() != null;
    }

    @OnlyIn(Dist.CLIENT)
    private boolean cursorEmpty() {
        if (gui == null || gui.getModularUIContainer() == null) {
            return false;
        }
        ItemStack carried = gui.getModularUIContainer().getCarried();
        return carried.isEmpty();
    }
}
