package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterTerminalPreview;
import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterTerminalSky;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterEntrance;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import com.gregtechceu.gtceu.api.gui.fancy.FancyMachineUIWidget;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.List;

import static com.dishanhai.sh_better_ui.client.api.ShBetterUiApi.motion;
import static com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation.BlendCurve.LINEAR;

final class StarLifterTerminal extends WidgetGroup {
    static final int WIDTH = 276, HEIGHT = 140;
    static final String TEXT = "gt_shanhai.star_lifter.terminal.";

    StarLifterTerminal(StarLifterControllerMachine machine, Frame frame) {
        super(0, 0, WIDTH, HEIGHT);
        var sync = new StarLifterTerminalSync(machine);
        addWidget(sync);
        if (machine.isRemote()) addClientView(machine, sync, frame);
    }

    @OnlyIn(Dist.CLIENT)
    private void addClientView(StarLifterControllerMachine machine, StarLifterTerminalSync sync, Frame frame) {
        addWidget(new StarLifterTerminalView(machine, sync, frame));
    }

    /** Retains the original GT player inventory, part menus, and machine configurators. */
    static final class Frame extends FancyMachineUIWidget {
        private static boolean firstOpen = true;
        private final StarLifterControllerMachine machine;
        private InterruptibleAnimation inventoryMotion, entrance;
        private int inventorySpace;
        private float inventoryExtent;
        private boolean inventoryExpanded = true, slotsAvailable = true;
        private StarLifterEntrance entranceMode = StarLifterEntrance.OFF;
        private boolean pendingEntrance;
        private boolean entrancePrimed;
        private boolean finishingEntrance;
        private long entranceStarted = -1, entranceInterrupted;
        private net.minecraft.client.resources.sounds.SoundInstance entranceSound;
        private StarLifterTerminalView view;
        private List<SlotWidget> inventorySlots = List.of();
        private Position inventoryOrigin;

        Frame(StarLifterControllerMachine machine) {
            super(machine, WIDTH + 8, HEIGHT + 94);
            this.machine = machine;
            setBackground(IGuiTexture.EMPTY);
            getSideTabsWidget().setSelfPosition(new Position(-26, 0));
        }

        @Override
        public void initWidget() {
            super.initWidget();
            if (isRemote()) registerClientClose();
        }

        @OnlyIn(Dist.CLIENT)
        private void registerClientClose() {
            StarLifterTerminalDeparture.clear();
            inventoryMotion = motion().animation(Spring.PANEL, 0);
            inventoryExpanded = ShanhaiUiClientConfig.starLifterInventoryExpanded();
            if (!inventoryExpanded && getPlayerInventory() != null) inventoryMotion.snapTo(getPlayerInventory().getSize().height);
            entrance = motion().animation(Spring.PANEL, 1);
            entranceMode = StarLifterEntrance.current();
            if (entranceMode == StarLifterEntrance.FIRST && !firstOpen) entranceMode = StarLifterEntrance.QUICK;
            pendingEntrance = entranceMode != StarLifterEntrance.OFF && !StarLifterTerminalView.reducedMotion();
            firstOpen = false;
            getGui().registerCloseListener(() -> {
                boolean fullscreen = StarLifterFullscreen.isActive(getGui().getModularUIGui());
                StarLifterFullscreen.closeFor(getGui().getModularUIGui());
                boolean wasStarting = pendingEntrance || entrance.running();
                stopEntranceSound();
                entrance.close();
                inventoryMotion.close();
                if (view != null) view.closeMotion();
                if (wasStarting || fullscreen || StarLifterTerminalView.reducedMotion() || view == null
                        || getCurrentPage() != getMainPage() || !view.beginDeparture()) {
                    StarLifterTerminalPreview.closeFrame();
                }
            });
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = getPosition().x, y = getPosition().y;
            int width = getSize().width, height = getSize().height;
            boolean main = getCurrentPage() == getMainPage();
            if (main && pendingEntrance && !entrancePrimed) {
                // Let the native screen and its overlays lay out before the client tick starts the show.
                entrance.snapTo(0);
                entrancePrimed = true;
            }
            float opening = entrance(motion().nowMs());
            if (main) {
                try (var profile = StarLifterTerminalPreview.profile("LayoutTransition")) {
                    layoutInventory();
                }
                drawShell(graphics, x, y, width, height, opening);
            } else {
                graphics.fill(x, y, x + width, y + height, 0xff151526);
            }
            var inventory = getPlayerInventory();
            boolean clipInventory = main && inventory != null && inventory.isVisible()
                    && (inventorySpace > 0 || opening < 1);
            boolean revealChrome = main && opening < 1 && entranceMode != StarLifterEntrance.MINIMAL;
            var title = getTitleBar();
            var tabs = getSideTabsWidget();
            var options = getConfiguratorPanel();
            boolean drawTitle = revealChrome && title.isVisible();
            boolean drawTabs = revealChrome && tabs.isVisible();
            boolean drawOptions = revealChrome && options.isVisible();
            if (drawTitle) title.setVisible(false);
            if (drawTabs) tabs.setVisible(false);
            if (drawOptions) options.setVisible(false);
            if (clipInventory) inventory.setVisible(false);
            try {
                super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            } finally {
                if (clipInventory) inventory.setVisible(true);
                if (drawTitle) title.setVisible(true);
                if (drawTabs) tabs.setVisible(true);
                if (drawOptions) options.setVisible(true);
            }
            float chrome = phase(opening, .24f, .67f);
            if (drawTitle) reveal(graphics, title, fullEntrance() ? phase(opening, .065f, .22f) : chrome, mouseX, mouseY, partialTicks);
            if (drawTabs) reveal(graphics, tabs, chrome, mouseX, mouseY, partialTicks);
            if (drawOptions) reveal(graphics, options, chrome, mouseX, mouseY, partialTicks);
            if (clipInventory) {
                // GT's configurator SVGs share the GUI vertex buffer. Submit them outside the slot clip.
                graphics.flush();
                int top = y + inventoryOrigin.y + inventorySpace;
                int edge = Mth.lerpInt(phase(opening, fullEntrance() ? .87f : .54f, 1), top, y + height);
                graphics.enableScissor(x, top, x + width, edge);
                try {
                    inventory.drawInBackground(graphics, -1, -1, partialTicks);
                } finally {
                    graphics.flush();
                    graphics.disableScissor();
                }
            }
        }

        @OnlyIn(Dist.CLIENT)
        private void drawShell(GuiGraphics g, int x, int y, int width, int height, float opening) {
            if (opening >= 1 || entranceMode == StarLifterEntrance.MINIMAL) {
                StarLifterTerminalSky.draw(g, x, y, width, height, opening);
                g.renderOutline(x, y, width, height, 0xff3a4263);
                return;
            }
            int cx = x + width / 2, cy = y + 74;
            float horizontal = phase(opening, fullEntrance() ? .02f : .08f, fullEntrance() ? .16f : .52f);
            float vertical = phase(opening, fullEntrance() ? .035f : .13f, fullEntrance() ? .34f : .64f);
            int left = Mth.lerpInt(horizontal, cx - 12, x);
            int right = Mth.lerpInt(horizontal, cx + 12, x + width);
            int top = Mth.lerpInt(vertical, cy - 12, y);
            int bottom = Mth.lerpInt(vertical, cy + 12, y + height);
            g.enableScissor(left, top, right, bottom);
            try {
                StarLifterTerminalSky.draw(g, x, y, width, height, phase(opening, .04f, .36f));
                g.flush();
            } finally {
                g.disableScissor();
            }
            int alpha = Math.round(220 * phase(opening, .06f, .35f));
            g.renderOutline(left, top, right - left, bottom - top, alpha << 24 | 0x597594);
        }

        @OnlyIn(Dist.CLIENT)
        private static void reveal(GuiGraphics g, Widget group, float amount, int mx, int my, float partial) {
            if (amount <= 0) return;
            var pos = group.getPosition();
            var size = group.getSize();
            g.flush();
            g.enableScissor(pos.x, pos.y, pos.x + Math.round(size.width * amount), pos.y + size.height);
            try {
                group.drawInBackground(g, mx, my, partial);
                g.flush();
            } finally {
                g.disableScissor();
            }
        }

        int inventorySpace() { return inventorySpace; }

        boolean inventoryExpanded() { return inventoryExpanded; }
        float inventoryExtent() { return inventoryExtent; }
        float inventoryTarget() { return inventoryMotion.target(); }
        int inventoryHeight() { return getPlayerInventory() == null ? 0 : getPlayerInventory().getSize().height; }
        boolean fullEntrance() { return entranceMode == StarLifterEntrance.FIRST || entranceMode == StarLifterEntrance.FULL; }
        boolean quickEntrance() { return entranceMode == StarLifterEntrance.QUICK; }
        boolean minimalEntrance() { return entranceMode == StarLifterEntrance.MINIMAL; }
        boolean acceptsInventoryInput() { return entrance == null || !pendingEntrance && !entrance.running(); }
        void attach(StarLifterTerminalView view) { this.view = view; }
        float entrance(long now) { return entrance == null ? 1 : entrance.sample(now, StarLifterTerminalView.reducedMotion()); }

        int bootElapsed(long now) {
            if (entranceStarted < 0) return -1;
            return (int) Math.clamp((finishingEntrance ? entranceInterrupted : now) - entranceStarted, 0, entranceMode.durationMs);
        }

        boolean playingBoot() { return entranceStarted >= 0 && !finishingEntrance; }

        float bootOpacity(long now) {
            if (entranceStarted < 0) return 0;
            return finishingEntrance ? 1 - phase(now - entranceInterrupted, 0, 115) : 1;
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void updateScreen() {
            super.updateScreen();
            if (entrance == null) return;
            if (StarLifterTerminalView.reducedMotion() || getCurrentPage() != getMainPage()) {
                cancelEntrance();
            } else if (pendingEntrance && entrancePrimed) {
                startEntrance(entranceMode);
            }
        }

        @OnlyIn(Dist.CLIENT)
        void startEntrance(boolean full) {
            entranceMode = full ? StarLifterEntrance.FULL : StarLifterEntrance.QUICK;
            finishingEntrance = false;
            pendingEntrance = entrancePrimed = true;
        }

        @OnlyIn(Dist.CLIENT)
        private void startEntrance(StarLifterEntrance mode) {
            if (entrance == null) return;
            pendingEntrance = false;
            if (StarLifterTerminalView.reducedMotion()) {
                entrance.snapTo(1);
                return;
            }
            entranceMode = mode;
            finishingEntrance = false;
            long now = motion().nowMs();
            entranceStarted = now;
            entrance.snapTo(0);
            entrance.blendTo(1, now, 0, mode.durationMs, LINEAR);
            stopEntranceSound();
            if (fullEntrance()) {
                entranceSound = StarLifterTerminalSounds.BOOT.instance();
                Minecraft.getInstance().getSoundManager().play(entranceSound);
            }
            if (view != null) view.startBoot(now, fullEntrance());
        }

        @OnlyIn(Dist.CLIENT)
        private void stopEntranceSound() {
            if (entranceSound == null) return;
            Minecraft.getInstance().getSoundManager().stop(entranceSound);
            entranceSound = null;
        }

        @OnlyIn(Dist.CLIENT)
        boolean skipEntrance() {
            if (entrance == null || !pendingEntrance && !entrance.running() || finishingEntrance) return false;
            pendingEntrance = false;
            finishingEntrance = true;
            entranceInterrupted = motion().nowMs();
            stopEntranceSound();
            entrance.blendTo(1, entranceInterrupted, 0, 115, LINEAR);
            return true;
        }

        @OnlyIn(Dist.CLIENT)
        void cancelEntrance() {
            if (pendingEntrance || entrance.running() || playingBoot() && bootElapsed(motion().nowMs()) < entranceMode.durationMs) {
                pendingEntrance = false;
                entranceStarted = -1;
                entrance.snapTo(1);
                stopEntranceSound();
                if (view != null) view.cancelBootCamera();
            }
        }

        @OnlyIn(Dist.CLIENT)
        void toggleInventory() {
            setInventoryExpanded(!inventoryExpanded, true);
        }

        @OnlyIn(Dist.CLIENT)
        boolean setInventoryExpanded(boolean expanded, boolean remember) {
            if (inventoryMotion == null || inventoryOrigin == null) return false;
            if (inventoryExpanded == expanded) return true;
            if (!checkInventoryIdle()) return false;
            inventoryExpanded = expanded;
            if (remember) ShanhaiUiClientConfig.setStarLifterInventoryExpanded(expanded);
            setSlotsAvailable(false);
            inventoryMotion.animateTo(inventoryExpanded ? 0 : getPlayerInventory().getSize().height, motion().nowMs());
            return true;
        }

        @OnlyIn(Dist.CLIENT)
        boolean canHideInventory() {
            var screen = getGui().getModularUIGui();
            return !screen.getQuickCrafting() && screen.getDraggingElement() == null && screen.getMenu().getCarried().isEmpty();
        }

        @OnlyIn(Dist.CLIENT)
        boolean checkInventoryIdle() {
            if (canHideInventory()) return true;
            Minecraft.getInstance().player.displayClientMessage(Component.translatable(TEXT + "inventory.busy"), true);
            return false;
        }

        @OnlyIn(Dist.CLIENT)
        private void layoutInventory() {
            var inventory = getPlayerInventory();
            if (inventoryOrigin == null || inventoryMotion == null || inventory == null) return;
            inventoryExtent = Mth.clamp(inventoryMotion.sample(motion().nowMs(), StarLifterTerminalView.reducedMotion()),
                    0, inventory.getSize().height);
            int space = Math.round(inventoryExtent);
            if (space != inventorySpace) {
                inventorySpace = space;
                inventory.setSelfPosition(new Position(inventoryOrigin.x, inventoryOrigin.y + space));
            }
            boolean visible = space < inventory.getSize().height;
            boolean interactable = visible && !inventoryMotion.running() && acceptsInventoryInput();
            inventory.setVisible(visible).setActive(interactable);
            setSlotsAvailable(interactable);
        }

        @OnlyIn(Dist.CLIENT)
        private void setSlotsAvailable(boolean available) {
            if (available == slotsAvailable) return;
            slotsAvailable = available;
            for (var slot : inventorySlots) slot.setActive(available);
        }

        @Override
        public void detectAndSendChanges() {
            var menu = getGui();
            if (menu.entityPlayer instanceof ServerPlayer player && player.containerMenu == menu.getModularUIContainer()
                    && !machine.canUseTerminal(player)) {
                player.closeContainer();
                return;
            }
            super.detectAndSendChanges();
        }

        @Override
        protected void setupFancyUI(IFancyUIProvider provider, boolean showInventory) {
            super.setupFancyUI(provider, showInventory);
            if (isRemote()) styleChrome();
        }

        @OnlyIn(Dist.CLIENT)
        private void styleChrome() {
            var panel = GlassPanelTexture.smokePanel();
            for (var section : getTitleBar().getWidgetsByType(WidgetGroup.class)) section.setBackground(panel);
            for (var image : getTitleBar().getWidgetsByType(ImageWidget.class)) {
                if (image.getImage() instanceof TextTexture title) {
                    title.updateText(getCurrentHomePage().getTitle().getString());
                    title.setColor(0xffeeefff).setDropShadow(false);
                }
            }
            for (var button : getTitleBar().getWidgetsByType(ButtonWidget.class)) {
                if (button.getBackgroundTexture() instanceof TextTexture label) label.setColor(0xffc7cdec);
            }
            if (getPlayerInventory() != null) {
                var skin = new StarLifterInventorySkin();
                getPlayerInventory().setBackground(skin.panel());
                getPlayerInventory().setSlotBackground(skin.slot());
                inventorySlots = getPlayerInventory().getWidgetsByType(SlotWidget.class);
                for (var slot : inventorySlots) slot.setDrawHoverOverlay(false);
                inventoryOrigin = getPlayerInventory().getSelfPosition();
                inventorySpace = 0;
                setSlotsAvailable(true);
            }
            StarLifterTerminalChrome.apply(getSideTabsWidget(), getConfiguratorPanel());
        }
    }

    static float phase(float time, float start, float end) {
        float t = Mth.clamp((time - start) / (end - start), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
