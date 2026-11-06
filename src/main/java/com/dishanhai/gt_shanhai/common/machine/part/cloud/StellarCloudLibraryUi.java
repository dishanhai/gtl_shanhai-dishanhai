package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.dishanhai.gt_shanhai.common.machine.part.CachedPatternSlotWidget;
import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
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
import java.util.function.Consumer;
import java.util.UUID;
import java.util.Objects;

import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudLibraryLayout.*;

/** 山海：原件管理的玻璃介面；權限、租約與庫操作仍由機器 Session 決定。 */
final class StellarCloudLibraryUi {
    private static final String PREFIX = "gt_shanhai.cloud.";
    private static final GlassColors COLORS = GlassColors.SMOKE;

    static ModularUI create(StellarCloudLibraryMachine machine, Player player, ItemStackTransfer inbox,
                            ItemStackTransfer output, StellarCloudLibraryMachine.Session session) {
        var selector = new TypeSelector(130, 42, 64, 18);
        WidgetGroup root = new WidgetGroup(0, 0, WIDTH, HEIGHT) {
            @Override @OnlyIn(Dist.CLIENT)
            public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                if (selector.isExpanded()) selector.drawInForeground(graphics, mouseX, mouseY, partialTicks);
                else super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
            }
        };
        root.setBackground(GlassPanelTexture.smokePanel());
        // 山海：第⑤步可直接使用此橋的 controller；既有原件槽與 tooltip 保留各自同步。
        root.addWidget(new StellarCloudUiSync.Library(session, player, inbox));
        Foldouts foldouts = new Foldouts();
        for (Rect area : new Rect[]{FILTER, CATALOG, TRANSFER, DETAIL, STATUS, INVENTORY, ACCESS}) {
            var panel = new WidgetGroup(area.x(), area.y(), area.width(), area.height());
            panel.setBackground(surface());
            root.addWidget(panel);
        }

        text(root, 12, 10, 186, 12, COLORS.TEXT_HEAD, () -> tr("title"));
        text(root, 12, 24, 186, 10, COLORS.TEXT_MUTED, () -> tr("ui.catalog", session.total()));
        text(root, 206, 10, 98, 12, COLORS.TEXT_MUTED, () -> tr(machine.isFormed()
                ? "structure.formed" : "structure.invalid"));
        text(root, 206, 24, 98, 10, COLORS.ACCENT, () -> tr(machine.isOnline()
                ? "ui.network_online" : "ui.network_offline"));

        text(root, 12, 45, 26, 12, COLORS.TEXT_MUTED, () -> tr("search"));
        root.addWidget(field(40, 44, 84, () -> session.search, session::changeSearch, 80, "ui.search_hint"));
        for (int index = 0; index < PAGE_SIZE; index++) root.addWidget(cell(machine, session, index));
        text(root, CATALOG.x() + 6, CATALOG.y() + 36, CATALOG.width() - 12, 14, COLORS.TEXT_MUTED,
                () -> session.rows().isEmpty() ? tr("ui.empty") : Component.empty());
        button(root, PAGER.x(), PAGER.y(), 44, PAGER.height(), "previous", session::previousPage,
                () -> session.page > 0 ? "" : PREFIX + "ui.first_page", session::showMessage);
        text(root, 60, 165, 86, 12, COLORS.TEXT_MUTED, () -> tr("page", session.page + 1, session.pages()));
        button(root, 154, PAGER.y(), 44, PAGER.height(), "next", session::nextPage,
                () -> session.page + 1 < session.pages() ? "" : PREFIX + "ui.last_page", session::showMessage);

        text(root, 214, 44, 82, 12, COLORS.TEXT_HEAD, () -> tr("ui.transfer"));
        text(root, 212, 59, 42, 10, COLORS.TEXT_MUTED, () -> tr("ui.input"));
        text(root, 260, 59, 38, 10, COLORS.TEXT_MUTED, () -> tr("ui.output"));
        root.addWidget(new TransferSlot(inbox, 224, 72, true, session::inboxAccessible).setBackground(surface())
                .setHoverTooltips(tr("ui.input_hint")));
        root.addWidget(new TransferSlot(output, 270, 72, false, session::outputAccessible).setBackground(surface())
                .setHoverTooltips(tr("ui.output_hint")));
        button(root, 212, 98, 42, 22, "upload", session::uploadOriginal, session::uploadDisabledReason, session::showMessage);
        button(root, 260, 98, 38, 22, "withdraw", session::withdrawSelected, session::withdrawDisabledReason, session::showMessage);

        var details = foldout(root, DETAIL, "details", foldouts::detailsOpen, foldouts::toggleDetails);
        text(details, 2, 2, 80, 14, COLORS.TEXT, session::selectedName);
        text(details, 2, 22, 80, 12, COLORS.ACCENT, session::selectedState);
        text(details, 2, 42, 80, 14, COLORS.TEXT_MUTED, session::selectedType);
        text(root, STATUS.x() + 6, STATUS.y() + 4, STATUS.width() - 12, 26,
                COLORS.TEXT, session::message);

        var inventory = new PlayerInventoryWidget() {
            @Override @OnlyIn(Dist.CLIENT)
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0 && isShiftDown()) {
                    int x = (int) mouseX - getPosition().x - 5;
                    int y = (int) mouseY - getPosition().y - 5;
                    int col = x / 18;
                    int slot = y >= 58 && y < 76 ? col : y >= 0 && y < 54 ? (y / 18 + 1) * 9 + col : -1;
                    if (x >= 0 && col < 9 && slot >= 0 && slot < 36
                            && machine.accepts(player.getInventory().getItem(slot))) {
                        ItemStack expected = player.getInventory().getItem(slot).copy();
                        writeClientAction(91, buffer -> { buffer.writeVarInt(slot); buffer.writeItem(expected); });
                        return true;
                    }
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }

            @Override public void handleClientAction(int id, FriendlyByteBuf buffer) {
                if (id == 91) session.quickUpload(buffer.readVarInt(), buffer.readItem());
                else super.handleClientAction(id, buffer);
            }
        };
        inventory.setPlayer(player);
        inventory.setSelfPosition(new Position(INVENTORY.x(), INVENTORY.y()));
        inventory.setSlotBackground(surface());
        root.addWidget(inventory);

        var access = foldout(root, ACCESS, "access", foldouts::accessOpen, foldouts::toggleAccess);
        button(access, 2, 2, 100, 18, "member_select", session::nextMember, session::memberDisabledReason, session::showMessage);
        text(access, 2, 24, 98, 14, COLORS.TEXT, session::memberText);
        text(access, 2, 44, 98, 14, COLORS.TEXT_MUTED, () -> tr("ui.access_hint"));
        button(access, 2, 64, 46, 18, "grant", () -> session.access(true), session::accessDisabledReason, session::showMessage);
        button(access, 54, 64, 46, 18, "revoke", () -> session.access(false), session::accessDisabledReason, session::showMessage);
        // 最後加入，展開時在樣板槽之上接收點擊。
        selector.setCandidatesSupplier(session::availableTypes).setSupplier(() -> session.type)
                .setOnChanged(session::selectType);
        selector.setHoverTooltips(tr("ui.type_hint"));
        root.addWidget(selector);
        return new ModularUI(WIDTH, HEIGHT, machine, player).widget(root);
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
        root.addWidget(content);
        var header = new SwitchWidget(area.x() + 4, area.y() + 2, area.width() - 8, 18, (click, pressed) -> {
            if (click.isRemote) {
                toggle.run();
                content.setVisible(open.getAsBoolean());
            }
        }) {
            @Override @OnlyIn(Dist.CLIENT)
            public boolean mouseClicked(double x, double y, int button) {
                if (!isMouseOverElement(x, y)) return false;
                return button != 0 || super.mouseClicked(x, y, button);
            }
        };
        header.setTexture(foldoutFace(key + "_expand", area.width() - 12, false),
                foldoutFace(key + "_collapse", area.width() - 12, true));
        header.setHoverTooltips(tr("ui.fold_hint"));
        root.addWidget(header);
        return content;
    }

    private static IGuiTexture foldoutFace(String key, int width, boolean selected) {
        IGuiTexture label = new TextTexture(PREFIX + "ui." + key, COLORS.TEXT_HEAD).setWidth(width)
                .setType(TextTexture.TextType.ROLL).setDropShadow(false);
        return selected ? new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(), label,
                new ColorBorderTexture(1, COLORS.ACCENT))
                : new GuiTextureGroup(GlassPanelTexture.smokeButtonOverlay(), label);
    }

    private static WidgetGroup cell(StellarCloudLibraryMachine machine, StellarCloudLibraryMachine.Session session, int index) {
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
        cell.addWidget(new SwitchWidget(0, 0, area.width(), area.height(), null)
                .setTexture(GlassPanelTexture.smokeButtonOverlay(), new GuiTextureGroup(
                        GlassPanelTexture.smokeButtonOverlay(), new ColorBorderTexture(1, COLORS.ACCENT)))
                .setSupplier(() -> session.rowSelected(index)));
        var slot = new CachedPatternSlotWidget(preview, 0, 1, 1, machine::getLevel);
        slot.setCanTakeItems(false).setCanPutItems(false).setDrawHoverOverlay(false).setDrawHoverTips(false);
        slot.setBackgroundTexture(IGuiTexture.EMPTY);
        slot.setOccupiedTexture(IGuiTexture.EMPTY);
        cell.addWidget(slot);
        cell.addWidget(new CellTooltip(area.width(), area.height(), () -> session.rowTooltip(index),
                () -> session.rowStack(index)));
        cell.addWidget(new CellQuickAction(area.width(), area.height(), index, session));
        return cell;
    }

    private static final class CellQuickAction extends Widget {
        private final int index;
        private final StellarCloudLibraryMachine.Session session;
        private UUID identity;

        CellQuickAction(int width, int height, int index, StellarCloudLibraryMachine.Session session) {
            super(0, 0, width, height);
            this.index = index;
            this.session = session;
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
            UUID expected = identity;
            boolean quick = isShiftDown();
            writeClientAction(1, buffer -> { buffer.writeUUID(expected); buffer.writeBoolean(quick); });
            return true;
        }

        @Override public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == 1) {
                UUID expected = buffer.readUUID();
                if (buffer.readBoolean()) session.quickWithdraw(index, expected);
                else session.select(index, expected);
            }
            else if (id == 2) session.clearSelection(index);
            else super.handleClientAction(id, buffer);
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
        TypeSelector(int x, int y, int width, int height) {
            super(x, y, width, height, java.util.List.of(), COLORS.TEXT);
            setMaxCount(6);
            setButtonBackground(GlassPanelTexture.smokeButtonOverlay());
            setBackground(GlassPanelTexture.smokePanel());
            popUp.setUseScissor(true);
        }

        boolean isExpanded() { return isShow; }

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
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            // 包含空白與外點關閉；此事件不能繼續交給下層樣板或背包。
            return wasOpen || handled;
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
            if (candidates.size() > maxCount) popUp.setYScrollBarWidth(4);
            int width = candidates.size() > maxCount ? getSize().width - 4 : getSize().width;
            int y = 0;
            for (String candidate : candidates) {
                var select = new SelectableWidgetGroup(0, y, width, 15);
                select.setHoverTooltips(recipeTypeName(candidate));
                var label = new TextTexture("", COLORS.TEXT).setWidth(width).setType(TextTexture.TextType.ROLL);
                String[] rendered = {""};
                select.addWidget(new ImageWidget(0, 0, width, 15, (g, mx, my, x, yy, w, h) -> {
                    String name = recipeTypeName(candidate).getString();
                    if (!rendered[0].equals(name)) { rendered[0] = name; label.updateText(name); }
                    label.draw(g, mx, my, x, yy, w, h);
                }));
                select.setSelectedTexture(-1, -1);
                select.setOnSelected(ignored -> {
                    setValue(candidate);
                    writeClientAction(2, buffer -> buffer.writeUtf(candidate));
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

    private static TextFieldWidget field(int x, int y, int width, Supplier<String> value,
                                         java.util.function.Consumer<String> changed, int limit, String hint) {
        var field = new TextFieldWidget(x, y, width, 14, value, changed);
        field.setMaxStringLength(limit).setBordered(false).setTextColor(COLORS.TEXT).setBackground(surface());
        field.setHoverTooltips(tr(hint));
        return field;
    }

    private static ButtonWidget button(WidgetGroup root, int x, int y, int width, int height, String key,
                                        Runnable action, Supplier<String> disabledReason, Consumer<String> rejected) {
        return StellarCloudUiWidgets.action(root, x, y, width, height, PREFIX + key, action, disabledReason, rejected);
    }

    private static void text(WidgetGroup root, int x, int y, int width, int height, int color,
                             Supplier<Component> value) {
        StellarCloudUiWidgets.text(root, x, y, width, height, color, value);
    }

    /** 使用既有 ComponentPanel 同步完整配方 tooltip；覆蓋全格但不攔截點擊。 */
    private static final class CellTooltip extends ComponentPanelWidget {
        private final int width, height;
        private final Supplier<ItemStack> originalSupplier;
        private ItemStack original = ItemStack.EMPTY;

        CellTooltip(int width, int height, Supplier<java.util.List<Component>> lines, Supplier<ItemStack> originalSupplier) {
            super(0, 0, text -> text.addAll(lines.get()));
            this.width = width; this.height = height;
            this.originalSupplier = originalSupplier;
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
                gui.getModularUIGui().setHoverTooltip(lines, original, null,
                        original.isEmpty() ? null : original.getTooltipImage().orElse(null));
            }
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }
    }

    private StellarCloudLibraryUi() {}
}
