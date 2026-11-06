package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.dishanhai.gt_shanhai.common.machine.part.CachedPatternSlotWidget;
import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import com.dishanhai.sh_better_ui.client.gui.scaled.ButtonFeedback;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiMotion;
import com.dishanhai.sh_better_ui.client.gui.scaled.PageTransition;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.custom.PlayerInventoryWidget;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.UUID;
import java.util.Objects;

import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudLibraryLayout.*;
import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudUiContract.*;

/** 山海：原件管理的玻璃介面；權限、租約與庫操作仍由機器 Session 決定。 */
final class StellarCloudLibraryUi {
    private static final String PREFIX = "gt_shanhai.cloud.";
    private static final GlassColors COLORS = GlassColors.SMOKE;

    static ModularUI create(StellarCloudLibraryMachine machine, Player player, ItemStackTransfer inbox,
                            ItemStackTransfer output, StellarCloudLibraryMachine.Session session) {
        var bridge = new StellarCloudUiSync.Library(session, player, inbox);
        var controller = bridge.controller;
        var selector = new TypeSelector(TYPE.x(), TYPE.y(), TYPE.width(), TYPE.height(), controller);
        WidgetGroup root = new WidgetGroup(0, 0, WIDTH, HEIGHT) {
            private boolean menuGesture;

            @Override @OnlyIn(Dist.CLIENT)
            public Widget getHoverElement(double x, double y) {
                return selector.isExpanded() ? selector : super.getHoverElement(x, y);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public boolean mouseClicked(double x, double y, int button) {
                if (selector.isExpanded() || selector.isMouseOverElement(x, y)) {
                    menuGesture = true;
                    selector.mouseClicked(x, y, button);
                    return true;
                }
                return super.mouseClicked(x, y, button);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
                if (menuGesture || selector.isExpanded()) {
                    selector.mouseDragged(x, y, button, dx, dy);
                    return true;
                }
                return super.mouseDragged(x, y, button, dx, dy);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public boolean mouseReleased(double x, double y, int button) {
                if (menuGesture || selector.isExpanded()) {
                    menuGesture = false;
                    selector.mouseReleased(x, y, button);
                    return true;
                }
                return super.mouseReleased(x, y, button);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public boolean mouseWheelMove(double x, double y, double delta) {
                return selector.isExpanded() ? selector.mouseWheelMove(x, y, delta) : super.mouseWheelMove(x, y, delta);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public boolean keyPressed(int key, int scanCode, int modifiers) {
                if (selector.isExpanded()) {
                    if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) selector.setShow(false);
                    return true;
                }
                return super.keyPressed(key, scanCode, modifiers);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public boolean charTyped(char character, int modifiers) {
                return selector.isExpanded() || super.charTyped(character, modifiers);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                if (selector.isExpanded()) selector.drawInForeground(graphics, mouseX, mouseY, partialTicks);
                else super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
            }
        };
        root.setBackground(GlassPanelTexture.smokePanel());
        root.addWidget(bridge);
        for (Rect area : new Rect[]{FILTER, CATALOG, TRANSFER, STATUS, INVENTORY}) {
            var panel = new WidgetGroup(area.x(), area.y(), area.width(), area.height());
            panel.setBackground(surface());
            root.addWidget(panel);
        }

        text(root, 12, 10, 186, 12, COLORS.TEXT_HEAD, () -> tr("title"));
        clientText(root, 12, 24, 186, 10, () -> COLORS.TEXT_MUTED,
                () -> tr("ui.catalog", controller.state().total()), false);
        text(root, 206, 10, 98, 12, COLORS.TEXT_MUTED, () -> tr(machine.isFormed()
                ? "structure.formed" : "structure.invalid"));
        text(root, 206, 24, 98, 10, COLORS.ACCENT, () -> tr(machine.isOnline()
                ? "ui.network_online" : "ui.network_offline"));

        text(root, 12, 45, 26, 12, COLORS.TEXT_MUTED, () -> tr("search"));
        root.addWidget(searchField(controller));
        for (int index = 0; index < PAGE_SIZE; index++) root.addWidget(cell(machine, session, controller, index));
        clientText(root, CATALOG.x() + 6, CATALOG.y() + 30, CATALOG.width() - 12, 32,
                () -> COLORS.TEXT_MUTED, () -> !controller.state().synchronizedReady() ? tr("ui.waiting")
                        : controller.state().visibleRows().isEmpty() ? tr("ui.empty") : Component.empty(), true);
        button(root, PREVIOUS, "previous", controller::previousPage, () -> pageControl(controller, false));
        clientText(root, 60, 165, 86, 12, () -> COLORS.TEXT_MUTED,
                () -> tr("page", controller.state().page() + 1, controller.state().pages()), false);
        button(root, NEXT, "next", controller::nextPage, () -> pageControl(controller, true));

        text(root, 214, 44, 82, 12, COLORS.TEXT_HEAD, () -> tr("ui.transfer"));
        text(root, 212, 59, 42, 10, COLORS.TEXT_MUTED, () -> tr("ui.input"));
        text(root, 260, 59, 38, 10, COLORS.TEXT_MUTED, () -> tr("ui.output"));
        root.addWidget(new TransferSlot(inbox, INPUT_SLOT.x(), INPUT_SLOT.y(), true, session::inboxAccessible).setBackground(surface())
                .setHoverTooltips(tr("ui.input_hint")));
        root.addWidget(new TransferSlot(output, OUTPUT_SLOT.x(), OUTPUT_SLOT.y(), false, session::outputAccessible).setBackground(surface())
                .setHoverTooltips(tr("ui.output_hint")));
        button(root, UPLOAD, "upload", controller::uploadOriginal, () -> controller.control(ActionKind.UPLOAD));
        button(root, WITHDRAW, "withdraw", controller::withdrawSelected, () -> controller.control(ActionKind.WITHDRAW));

        var details = foldout(root, DETAIL, "details", () -> controller.localState().detailsExpanded(), controller::toggleDetails);
        text(details, 2, 2, 80, 18, COLORS.TEXT, session::selectedName);
        text(details, 2, 26, 80, 12, COLORS.ACCENT, session::selectedState);
        text(details, 2, 46, 80, 18, COLORS.TEXT_MUTED, session::selectedType);
        // Session 同步業務結果與選取拒絕；只在 pending 時覆蓋，避免舊回執永久遮住新訊息。
        var message = StellarCloudUiWidgets.text(root, STATUS.x() + 6, STATUS.y() + 4,
                STATUS.width() - 12, 26, COLORS.TEXT, session::message);
        var feedback = clientText(root, STATUS.x() + 6, STATUS.y() + 4, STATUS.width() - 12, 26,
                () -> controller.pendingRequestId().isPresent() ? COLORS.ACCENT : COLORS.TEXT,
                () -> Component.translatable(!controller.state().synchronizedReady()
                        ? WAITING : !controller.state().authorized() ? PREFIX + "status.denied" : controller.feedbackKey()), true);
        root.addWidget(new Widget(0, 0, 0, 0) {
            @Override public void updateScreen() {
                Widget visible = Feedback.visible(controller, message, feedback);
                message.setVisible(visible == message);
                feedback.setVisible(visible == feedback);
            }
        });

        var inventory = new PlayerInventoryWidget() {
            @Override @OnlyIn(Dist.CLIENT)
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0 && isShiftDown()) {
                    int slot = StellarCloudLibraryLayout.inventorySlot(mouseX - getPosition().x, mouseY - getPosition().y);
                    if (slot >= 0
                            && machine.accepts(player.getInventory().getItem(slot))) {
                        controller.quickUpload(slot);
                        return true;
                    }
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
                int slot = StellarCloudLibraryLayout.inventorySlot(mouseX - getPosition().x, mouseY - getPosition().y);
                if (isShiftDown() && slot >= 0 && machine.accepts(player.getInventory().getItem(slot))) {
                    var control = controller.control(ActionKind.QUICK_UPLOAD);
                    if (!control.enabled() && gui != null) gui.getModularUIGui().setHoverTooltip(
                            java.util.List.of(Component.translatable(control.reasonKey())), ItemStack.EMPTY, null, null);
                }
            }
        };
        inventory.setPlayer(player);
        inventory.setSelfPosition(new Position(INVENTORY.x(), INVENTORY.y()));
        inventory.setSlotBackground(surface());
        root.addWidget(inventory);

        var access = foldout(root, ACCESS, "access", () -> controller.localState().permissionsExpanded(), controller::togglePermissions);
        button(access, new Rect(2, 2, 100, 18), "member_select", controller::nextMember,
                () -> !controller.state().synchronizedReady() ? new ControlState(false, WAITING)
                        : controller.pendingRequestId().isPresent() ? controller.control(ActionKind.GRANT)
                        : new ControlState(controller.state().owner() && controller.state().authorized(), PREFIX + "status.denied"));
        text(access, 2, 24, 98, 14, COLORS.TEXT, session::memberText);
        text(access, 2, 44, 98, 14, COLORS.TEXT_MUTED, () -> tr("ui.access_hint"));
        button(access, new Rect(2, 64, 46, 18), "grant", () -> controller.changeAccess(true), () -> controller.control(ActionKind.GRANT));
        button(access, new Rect(54, 64, 46, 18), "revoke", () -> controller.changeAccess(false), () -> controller.control(ActionKind.REVOKE));
        // 最後加入，展開時在樣板槽之上接收點擊。
        selector.setHoverTooltips(tr("ui.type_hint"));
        root.addWidget(selector);
        return new ModularUI(WIDTH, HEIGHT, machine, player).widget(root);
    }

    /** 山海：文字與控件測試共用同一個實際可見回饋選擇接點。 */
    static final class Feedback {
        static <T> T visible(LibraryController controller, T serverMessage, T actionFeedback) {
            boolean override = !controller.state().synchronizedReady() || !controller.state().authorized()
                    || controller.pendingRequestId().isPresent();
            return override ? actionFeedback : serverMessage;
        }
    }

    /** 山海：原生點擊、拖放與 shift-click 共用此閘門；只在服務端查閱即時授權。 */
    private static final class TransferSlot extends SlotWidget {
        private final BooleanSupplier authorized;

        private TransferSlot(ItemStackTransfer transfer, int x, int y, boolean canPut, BooleanSupplier authorized) {
            super(transfer, 0, x, y, true, canPut);
            this.authorized = authorized;
        }

        @Override public boolean canPutStack(ItemStack stack) {
            return (isRemote() || authorized.getAsBoolean()) && super.canPutStack(stack);
        }

        @Override public boolean canTakeStack(Player player) {
            return (isRemote() || authorized.getAsBoolean()) && super.canTakeStack(player);
        }
    }

    private static WidgetGroup foldout(WidgetGroup root, Rect area, String key,
                                       BooleanSupplier open, Runnable toggle) {
        Rect viewport = StellarCloudLibraryLayout.viewport(area);
        var content = StellarCloudUiWidgets.viewport(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        // 只收起繪製與命中；保持 active，服務端仍同步文字及停用原因。
        content.setVisible(false);
        root.addWidget(new ImageWidget(area.x(), area.y(), area.width(), area.height(),
                (graphics, mouseX, mouseY, x, y, width, height) -> {
                    if (open.getAsBoolean()) surface().draw(graphics, mouseX, mouseY, x, y, width, height);
                }));
        root.addWidget(content);
        var header = new WidgetGroup(area.x() + 4, area.y() + 2, area.width() - 8, 18);
        var expand = button(header, new Rect(0, 0, area.width() - 8, 18), "ui." + key + "_expand",
                toggle, () -> new ControlState(true, ""));
        var collapse = button(header, new Rect(0, 0, area.width() - 8, 18), "ui." + key + "_collapse",
                toggle, () -> new ControlState(true, ""));
        collapse.setVisible(false);
        root.addWidget(header);
        root.addWidget(new Widget(0, 0, 0, 0) {
            @Override public void updateScreen() {
                boolean expanded = open.getAsBoolean();
                content.setVisible(expanded);
                expand.setVisible(!expanded);
                collapse.setVisible(expanded);
            }
        });
        return content;
    }

    private static WidgetGroup cell(StellarCloudLibraryMachine machine, StellarCloudLibraryMachine.Session session,
                                    LibraryController controller, int index) {
        Rect area = StellarCloudLibraryLayout.cell(index);
        ItemStackTransfer preview = new ItemStackTransfer(1);
        preview.setStackInSlot(0, session.rowPreview(index));
        WidgetGroup cell = new WidgetGroup(area.x(), area.y(), area.width(), area.height()) {
            @Override public void detectAndSendChanges() {
                ItemStack stack = session.rowPreview(index);
                if (!ItemStack.matches(stack, preview.getStackInSlot(0))) preview.setStackInSlot(0, stack);
                super.detectAndSendChanges();
            }
        };
        var slot = new CachedPatternSlotWidget(preview, 0, 1, 1, machine::getLevel);
        slot.setCanTakeItems(false).setCanPutItems(false).setDrawHoverOverlay(false).setDrawHoverTips(false);
        slot.setBackgroundTexture(IGuiTexture.EMPTY);
        slot.setOccupiedTexture(IGuiTexture.EMPTY);
        cell.addWidget(slot);
        cell.addWidget(new CellTooltip(area.width(), area.height(), () -> session.rowTooltip(index),
                () -> session.rowStack(index), () -> controller.quickWithdrawControl(index)));
        cell.addWidget(new CellQuickAction(area.width(), area.height(), index, session, controller));
        return cell;
    }

    private static final class CellQuickAction extends Widget {
        private final int index;
        private final StellarCloudLibraryMachine.Session session;
        private final LibraryController controller;
        private final ButtonFeedback feedback = new ButtonFeedback();
        private UUID identity;

        CellQuickAction(int width, int height, int index, StellarCloudLibraryMachine.Session session,
                        LibraryController controller) {
            super(0, 0, width, height);
            this.index = index;
            this.session = session;
            this.controller = controller;
        }

        @Override public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            identity = session.rowIdentity(index);
            buffer.writeBoolean(identity != null);
            if (identity != null) buffer.writeUUID(identity);
        }

        @Override public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            identity = buffer.readBoolean() ? buffer.readUUID() : null;
        }

        @Override public void detectAndSendChanges() {
            super.detectAndSendChanges();
            UUID current = session.rowIdentity(index);
            if (!Objects.equals(identity, current)) {
                identity = current;
                writeUpdateInfo(1, buffer -> {
                    buffer.writeBoolean(current != null);
                    if (current != null) buffer.writeUUID(current);
                });
            }
        }

        @Override public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == 1) identity = buffer.readBoolean() ? buffer.readUUID() : null;
            else super.readUpdateInfo(id, buffer);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseClicked(double x, double y, int button) {
            if (!isMouseOverElement(x, y)) return false;
            if (button != 0) return true;
            if (identity == null) {
                if (!isShiftDown()) writeClientAction(2, buffer -> {});
                return true;
            }
            // 顯示槽身份與快照一致才使用 controller；列表變動期間寧可等待，也不改取另一張。
            var rows = controller.state().visibleRows();
            if (index >= rows.size() || !identity.equals(rows.get(index).id())) return true;
            boolean enabled = !isShiftDown() || controller.quickWithdrawControl(index).enabled();
            feedback.press(0, getPosition().x, getPosition().y, getSize().width, getSize().height,
                    enabled, PageTransition.nowMs());
            if (isShiftDown()) controller.quickWithdraw(index);
            else controller.select(index);
            return true;
        }

        @Override public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == 2) session.clearSelection(index);
            else super.handleClientAction(id, buffer);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseReleased(double x, double y, int button) {
            if (button == 0 && feedback.release(PageTransition.nowMs())) return true;
            return isMouseOverElement(x, y);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            long now = PageTransition.nowMs();
            feedback.pointer(mouseX, mouseY, now);
            feedback.tick(now);
            boolean selected = identity != null && identity.equals(controller.state().selection().id());
            boolean enabled = controller.state().synchronizedReady() && identity != null
                    && (!isShiftDown() || controller.quickWithdrawControl(index).enabled());
            float hover = feedback.hover(0, enabled && isMouseOverElement(mouseX, mouseY), now);
            var visual = feedback.visual(0, 0, enabled, now, false);
            int x = getPosition().x, y = getPosition().y, width = getSize().width, height = getSize().height;
            // 透明覆層位於預覽之上；懸停／按下只改覆層，不能遮住產物與數量。
            if (hover > 0 || visual.press() > 0) graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                    (COLORS.ACCENT & 0x00FFFFFF) | Math.round(255 * (visual.press() * 0.18f + hover * 0.08f)) << 24);
            graphics.renderOutline(x, y, width, height, selected ? COLORS.ACCENT
                    : enabled ? GuiMotion.lerpColor(COLORS.ROW_EDGE, COLORS.ACCENT, hover) : COLORS.DISPLAY_EDGE);
        }
    }

    private static Component tr(String suffix, Object... args) { return Component.translatable(PREFIX + suffix, args); }

    static Component recipeTypeName(String id) {
        if (id.isEmpty()) return tr("all_types");
        ResourceLocation type = ResourceLocation.tryParse(id);
        if (type == null) return Component.literal(id);
        String namespace = type.getNamespace(), path = type.getPath();
        return Component.translatableWithFallback(namespace + "." + path, "%s",
                Component.translatableWithFallback(namespace + ".recipe_type." + path, "%s",
                        Component.translatableWithFallback("gtceu." + path, "%s",
                                Component.translatableWithFallback("gtceu.recipe_type." + path, id))));
    }

    /** 以 ID 同步選項，名稱留給客戶端翻譯，避免不同語言或同名選項改變篩選結果。 */
    private static final class TypeSelector extends SelectorWidget {
        private final LibraryController controller;
        private final ButtonFeedback feedback = new ButtonFeedback();

        TypeSelector(int x, int y, int width, int height, LibraryController controller) {
            super(x, y, width, height, java.util.List.of(), COLORS.TEXT);
            this.controller = controller;
            setMaxCount(6);
            setButtonBackground(GlassPanelTexture.smokeButtonOverlay());
            setBackground(GlassPanelTexture.smokePanel());
            popUp.setUseScissor(true);
        }

        boolean isExpanded() { return isShow; }

        @Override public void updateScreen() {
            super.updateScreen();
            var state = controller.state();
            if (!candidates.equals(state.availableTypes())) setCandidates(state.availableTypes());
            if (!Objects.equals(currentValue, state.type())) setValue(state.type());
        }

        private boolean inside(Widget widget, double mouseX, double mouseY) {
            return StellarCloudUiWidgets.inside(widget.getPosition().x, widget.getPosition().y,
                    widget.getSize().width, widget.getSize().height, mouseX, mouseY);
        }

        @Override public boolean isMouseOverElement(double mouseX, double mouseY) {
            return inside(this, mouseX, mouseY) || isShow && inside(popUp, mouseX, mouseY);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            boolean wasOpen = isShow;
            if (wasOpen && !isMouseOverElement(mouseX, mouseY)) {
                setShow(false);
                return true;
            }
            if (wasOpen && inside(popUp, mouseX, mouseY)) {
                if (button == 0) popUp.mouseClicked(mouseX, mouseY, button);
                return true;
            }
            if (inside(this, mouseX, mouseY)) {
                if (button == 0) {
                    boolean enabled = controller.state().synchronizedReady();
                    feedback.press(0, getPosition().x, getPosition().y, getSize().width, getSize().height,
                            enabled, PageTransition.nowMs());
                    if (enabled) setShow(!isShow);
                }
                return true;
            }
            return wasOpen;
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseReleased(double x, double y, int button) {
            if (button == 0) feedback.release(PageTransition.nowMs());
            return super.mouseReleased(x, y, button) || isMouseOverElement(x, y);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            long now = PageTransition.nowMs();
            feedback.pointer(mouseX, mouseY, now);
            feedback.tick(now);
            boolean enabled = controller.state().synchronizedReady();
            float hover = feedback.hover(0, enabled && inside(this, mouseX, mouseY), now);
            var visual = feedback.visual(0, 0, enabled, now, false);
            textTexture.setColor(enabled ? COLORS.TEXT : COLORS.TEXT_OFF);
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            int x = getPosition().x, y = getPosition().y, width = getSize().width, height = getSize().height;
            if (visual.press() > 0) graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                    (COLORS.ACCENT & 0x00FFFFFF) | Math.round(visual.press() * 32) << 24);
            graphics.renderOutline(x, y, width, height, !enabled ? COLORS.DISPLAY_EDGE
                    : isShow || !controller.state().type().isEmpty() ? COLORS.ACCENT
                    : GuiMotion.lerpColor(COLORS.BUTTON_EDGE, COLORS.ACCENT, hover));
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            setHoverTooltips(Component.translatable(controller.state().synchronizedReady() ? PREFIX + "ui.type_hint" : WAITING));
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseWheelMove(double mouseX, double mouseY, double delta) {
            if (isShow) {
                if (inside(popUp, mouseX, mouseY)) super.mouseWheelMove(mouseX, mouseY, delta);
                return true;
            }
            return super.mouseWheelMove(mouseX, mouseY, delta);
        }

        @Override public SelectorWidget setValue(String value) {
            super.setValue(value);
            textTexture.updateText(recipeTypeName(value).getString());
            return this;
        }

        @Override protected void computeLayout() {
            int height = Math.min(maxCount, candidates.size()) * 15;
            popUp.clearAllWidgets();
            selectables.clear();
            popUp.setSize(new Size(getSize().width, height));
            popUp.setSelfPosition(new Position(0, getSize().height));
            popUp.setYScrollBarWidth(candidates.size() > maxCount ? 4 : 0);
            int width = candidates.size() > maxCount ? getSize().width - 4 : getSize().width;
            int y = 0;
            for (String candidate : candidates) {
                var select = new SelectableWidgetGroup(0, y, width, 15);
                select.setHoverTooltips(recipeTypeName(candidate));
                var label = new TextTexture("", COLORS.TEXT).setWidth(width).setType(TextTexture.TextType.ROLL);
                String[] rendered = {""};
                select.addWidget(new ImageWidget(0, 0, width, 15, (g, mx, my, x, yy, w, h) -> {
                    boolean selected = candidate.equals(controller.state().type());
                    if (selected || Widget.isMouseOver((int) x, (int) yy, w, h, mx, my)) {
                        g.fill((int) x, (int) yy, (int) x + w, (int) yy + h, COLORS.ROW_FILL_HOVER);
                        if (selected) g.renderOutline((int) x, (int) yy, w, h, COLORS.ACCENT);
                    }
                    String name = recipeTypeName(candidate).getString();
                    if (!rendered[0].equals(name)) { rendered[0] = name; label.updateText(name); }
                    label.draw(g, mx, my, x, yy, w, h);
                }));
                select.setSelectedTexture(IGuiTexture.EMPTY);
                select.setOnSelected(ignored -> {
                    controller.selectType(candidate);
                    setShow(false);
                });
                popUp.addWidget(select);
                selectables.add(select);
                y += 15;
            }
            popUp.setScrollYOffset(0);
        }
    }

    private static IGuiTexture surface() {
        return StellarCloudUiWidgets.surface();
    }

    private static TextFieldWidget searchField(LibraryController controller) {
        var field = new TextFieldWidget(SEARCH.x(), SEARCH.y(), SEARCH.width(), SEARCH.height(), null, null) {
            private boolean applyingSnapshot;

            @Override @OnlyIn(Dist.CLIENT)
            protected void onTextChanged(String value) {
                currentString = value;
                if (!applyingSnapshot) controller.changeSearch(value);
            }

            @Override @OnlyIn(Dist.CLIENT)
            public void updateScreen() {
                super.updateScreen();
                // 不用較早回來的快照覆蓋仍在輸入的搜尋字串。
                if (controller.state().synchronizedReady() && !textField.isFocused()
                        && !getCurrentString().equals(controller.state().search())) {
                    applyingSnapshot = true;
                    try { setCurrentString(controller.state().search()); }
                    finally { applyingSnapshot = false; }
                }
            }
        };
        field.setMaxStringLength(80).setBordered(false).setTextColor(COLORS.TEXT).setBackground(surface());
        field.setHoverTooltips(tr("ui.search_hint"));
        return field;
    }

    private static ControlState pageControl(LibraryController controller, boolean next) {
        var state = controller.state();
        if (!state.synchronizedReady()) return new ControlState(false, WAITING);
        return new ControlState(next ? state.page() + 1 < state.pages() : state.page() > 0,
                PREFIX + (next ? "ui.last_page" : "ui.first_page"));
    }

    private static ButtonWidget button(WidgetGroup root, Rect area, String key, Runnable action,
                                        Supplier<ControlState> control) {
        return StellarCloudUiWidgets.intent(root, area.x(), area.y(), area.width(), area.height(),
                PREFIX + key, action, control);
    }

    /** 僅渲染 controller 的客戶端快照／回執；固定邊界，完整長文字保留在 tooltip。 */
    private static Widget clientText(WidgetGroup root, int x, int y, int width, int height, IntSupplier color,
                                      Supplier<Component> value, boolean wrap) {
        var widget = new Widget(x, y, width, height) {
            private final TextTexture label = new TextTexture("").setWidth(width)
                    .setType(TextTexture.TextType.ROLL).setDropShadow(false);

            @Override @OnlyIn(Dist.CLIENT)
            public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                int xx = getPosition().x, yy = getPosition().y;
                var matrix = graphics.pose().last().pose();
                graphics.enableScissor((int) Math.floor(xx * matrix.m00() + matrix.m30()),
                        (int) Math.floor(yy * matrix.m11() + matrix.m31()),
                        (int) Math.ceil((xx + width) * matrix.m00() + matrix.m30()),
                        (int) Math.ceil((yy + height) * matrix.m11() + matrix.m31()));
                try {
                    if (wrap) {
                        var font = Minecraft.getInstance().font;
                        var lines = font.split(value.get(), width);
                        int count = Math.min(lines.size(), height / font.lineHeight);
                        for (int line = 0; line < count; line++)
                            graphics.drawString(font, lines.get(line), xx, yy + line * font.lineHeight, color.getAsInt(), false);
                    } else {
                        label.updateText(value.get().getString());
                        label.setColor(color.getAsInt());
                        label.draw(graphics, mouseX, mouseY, xx, yy, width, height);
                    }
                } finally { graphics.disableScissor(); }
            }

            @Override @OnlyIn(Dist.CLIENT)
            public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                setHoverTooltips(value.get());
                super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
            }
        };
        root.addWidget(widget);
        return widget;
    }

    private static void text(WidgetGroup root, int x, int y, int width, int height, int color,
                             Supplier<Component> value) {
        StellarCloudUiWidgets.text(root, x, y, width, height, color, value);
    }

    /** 使用既有 ComponentPanel 同步完整配方 tooltip；覆蓋全格但不攔截點擊。 */
    private static final class CellTooltip extends ComponentPanelWidget {
        private final int width, height;
        private final Supplier<ItemStack> originalSupplier;
        private final Supplier<ControlState> quickControl;
        private ItemStack original = ItemStack.EMPTY;

        CellTooltip(int width, int height, Supplier<java.util.List<Component>> lines, Supplier<ItemStack> originalSupplier,
                    Supplier<ControlState> quickControl) {
            super(0, 0, text -> text.addAll(lines.get()));
            this.width = width; this.height = height;
            this.originalSupplier = originalSupplier;
            this.quickControl = quickControl;
            setMaxWidthLimit(240);
            setSize(new Size(width, height));
        }

        @Override public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            original = originalSupplier.get().copy();
            buffer.writeNbt(original.save(new net.minecraft.nbt.CompoundTag()));
        }

        @Override public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            var tag = buffer.readNbt();
            original = tag == null ? ItemStack.EMPTY : ItemStack.of(tag);
        }

        @Override public void detectAndSendChanges() {
            super.detectAndSendChanges();
            ItemStack current = originalSupplier.get();
            if (!ItemStack.matches(original, current)) {
                original = current.copy();
                writeUpdateInfo(2, buffer -> buffer.writeNbt(original.save(new net.minecraft.nbt.CompoundTag())));
            }
        }

        @Override public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == 2) {
                var tag = buffer.readNbt();
                original = tag == null ? ItemStack.EMPTY : ItemStack.of(tag);
            } else super.readUpdateInfo(id, buffer);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void updateComponentTextSize() { if (width > 0) setSize(new Size(width, height)); }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {}

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            if ((!original.isEmpty() || !lastText.isEmpty()) && isMouseOverElement(mouseX, mouseY) && gui != null) {
                var lines = new java.util.ArrayList<Component>();
                if (!original.isEmpty()) {
                    var client = Minecraft.getInstance();
                    var flag = client.options.advancedItemTooltips
                            ? net.minecraft.world.item.TooltipFlag.Default.ADVANCED
                            : net.minecraft.world.item.TooltipFlag.Default.NORMAL;
                    lines.addAll(original.getTooltipLines(client.player, flag));
                }
                lines.addAll(lastText);
                if (isShiftDown() && !quickControl.get().enabled())
                    lines.add(Component.translatable(quickControl.get().reasonKey()));
                gui.getModularUIGui().setHoverTooltip(lines, original, null,
                        original.isEmpty() ? null : original.getTooltipImage().orElse(null));
            }
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }
    }

    private StellarCloudLibraryUi() {}
}
