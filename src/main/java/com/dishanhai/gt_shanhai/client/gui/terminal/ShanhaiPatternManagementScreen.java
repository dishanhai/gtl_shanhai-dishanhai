package com.dishanhai.gt_shanhai.client.gui.terminal;

import appeng.client.gui.me.patternaccess.PatternSlot;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.BackgroundPanel;
import appeng.client.gui.widgets.ToolboxPanel;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.menu.SlotSemantics;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternRemoteConfigPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternRemoteConfigPacket.Operation;
import com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal;
import de.mari_023.ae2wtlib.wut.CycleTerminalButton;
import de.mari_023.ae2wtlib.wut.IUniversalTerminalCapable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ShanhaiPatternManagementScreen
        extends GuiExPatternTerminal<ShanhaiPatternManagementMenu>
        implements IUniversalTerminalCapable {

    private static final int STOCK_BUTTON_X = 198;
    private final List<StockButtonTarget> stockButtons = new ArrayList<>();

    public ShanhaiPatternManagementScreen(ShanhaiPatternManagementMenu menu, Inventory inventory,
            Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        if (menu.isWUT()) {
            addToLeftToolbar(new CycleTerminalButton(button -> cycleTerminal()));
        }
        this.widgets.add("upgrades", new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE), menu.getTerminalHost()));
        if (menu.getToolbox().isPresent()) {
            this.widgets.add("toolbox", new ToolboxPanel(style, menu.getToolbox().getName()));
        }
        this.widgets.add("singularityBackground", new BackgroundPanel(style.getImage("singularityBackground")));
    }

    @Override
    public void drawFG(GuiGraphics graphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(graphics, offsetX, offsetY, mouseX, mouseY);
        stockButtons.clear();
        Set<Long> drawn = new HashSet<>();
        ItemStack icon = new ItemStack(Items.HOPPER);
        for (Slot slot : getMenu().slots) {
            if (!(slot instanceof PatternSlot patternSlot)) continue;
            long serverId = patternSlot.getMachineInv().getServerId();
            if (!getMenu().isStellarContainer(serverId) || !drawn.add(serverId)) continue;
            int y = patternSlot.y;
            graphics.fill(STOCK_BUTTON_X, y, STOCK_BUTTON_X + 16, y + 16, 0xFF355C7D);
            graphics.renderItem(icon, STOCK_BUTTON_X, y);
            stockButtons.add(new StockButtonTarget(serverId, STOCK_BUTTON_X, y));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int localX = (int) mouseX - leftPos;
            int localY = (int) mouseY - topPos;
            for (StockButtonTarget target : stockButtons) {
                if (target.contains(localX, localY)) {
                    ShanhaiNetwork.CHANNEL.sendToServer(new ShanhaiPatternRemoteConfigPacket(
                            getMenu().containerId, target.serverId(), Operation.OPEN_STOCK_INPUT, -1));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType) {
        if (slot instanceof PatternSlot patternSlot && mouseButton == 2) {
            long serverId = patternSlot.getMachineInv().getServerId();
            if (getMenu().isStellarContainer(serverId)) {
                ShanhaiNetwork.CHANNEL.sendToServer(new ShanhaiPatternRemoteConfigPacket(
                        getMenu().containerId, serverId, Operation.OPEN_SLOT_CATALYST,
                        patternSlot.getSlotIndex()));
                return;
            }
        }
        super.slotClicked(slot, slotId, mouseButton, clickType);
    }

    @Override
    public void storeState() {
    }

    private record StockButtonTarget(long serverId, int x, int y) {
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        }
    }
}
