package com.dishanhai.gt_shanhai.client.gui.terminal;

import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.BackgroundPanel;
import appeng.client.gui.widgets.ToolboxPanel;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.menu.SlotSemantics;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import com.dishanhai.gt_shanhai.common.item.terminal.StellarTerminalDetail;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternRemoteConfigPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiPatternRemoteConfigPacket.Operation;
import com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal;
import de.mari_023.ae2wtlib.wut.CycleTerminalButton;
import de.mari_023.ae2wtlib.wut.IUniversalTerminalCapable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import appeng.client.gui.me.patternaccess.PatternSlot;

public final class ShanhaiPatternManagementScreen
        extends GuiExPatternTerminal<ShanhaiPatternManagementMenu>
        implements IUniversalTerminalCapable {

    private static final int HEADER_BUTTON_X = 166;
    private static final int HEADER_BUTTON_Y = 55;
    private static final int ROW_HEIGHT = 18;
    private static final int BUTTON_WIDTH = 14;
    private static final int BUTTON_HEIGHT = 12;
    private static final int BUTTON_BORDER = 0xFF39425E;
    private static final int BUTTON_BG = 0xFFB7D8F0;
    private static final int BUTTON_TEXT = 0xFF2C5778;
    private static final int PANEL_WIDTH = 112;
    private static final int ROW_STRIDE = 12;
    private static final int PANEL_BG = 0xF0121824;
    private static final int PANEL_BORDER = 0xFF8FB7D4;
    private static final int PANEL_SELECTED = 0xFF3E6F93;
    private static final int PANEL_TEXT = 0xFFE7F2FA;
    private static final int DETAIL_WIDTH = 120;
    private static final int DETAIL_GAP = 4;
    private static final int DETAIL_TOOLTIP_WIDTH = 220;
    private static final int DETAIL_LABEL = 0xFF9BB4CC;
    private static final int DETAIL_WARN = 0xFFE0A15A;
    private static final int DETAIL_OFFLINE = 0xFFE07A7A;
    private static final int DETAIL_LINK = 0xFF7EC8E3;

    private static final Operation[] CONTROL_ACTIONS = {
            Operation.OPEN_FULL_PATTERN,
            Operation.OPEN_STOCK_INPUT,
            Operation.OPEN_OUTPUT_MULTIPLIER,
            Operation.OPEN_STRONG_PATTERN_MATCH,
            Operation.OPEN_WILDCARD,
            Operation.OPEN_SHARED_INPUTS,
            Operation.OPEN_PATTERN_BEHAVIOR,
            Operation.OPEN_REFUND,
            Operation.OPEN_STUCK_REPORT
    };
    private static final String[] CONTROL_LABELS = {
            "gui.gt_shanhai.stellar_remote.pattern",
            "gui.gt_shanhai.stellar_remote.stock",
            "gui.gt_shanhai.stellar_remote.multiplier",
            "gui.gt_shanhai.stellar_remote.strong_match",
            "gui.gt_shanhai.stellar_remote.wildcard",
            "gui.gt_shanhai.stellar_remote.shared",
            "gui.gt_shanhai.stellar_remote.behavior",
            "gui.gt_shanhai.stellar_remote.refund",
            "gui.gt_shanhai.stellar_remote.detail.open_stuck_report"
    };

    private static final Field EAEP_OPEN_UI_BUTTONS_FIELD = findNamedField("openUIButtons");
    private static final Field ROWS_FIELD = findNamedField("rows");
    private static final Field VISIBLE_ROWS_FIELD = findNamedField("visibleRows");
    private static final Field SCROLLBAR_FIELD = findNamedField("scrollbar");
    private static final Map<String, Method> NO_ARG_METHODS = new HashMap<>();

    private final List<StellarHeaderTarget> headerTargets = new ArrayList<>();
    private final List<DetailHit> detailHits = new ArrayList<>();
    private final Set<Integer> stellarHeaderRows = new HashSet<>();
    private boolean controlOpen;
    private List<Long> controlServerIds = List.of();
    private long selectedServerId;
    private int controlX;
    private int controlY;

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
        rebuildHeaderTargets();
        hideStellarHeaderButtons();
        for (StellarHeaderTarget target : headerTargets) {
            drawHeaderButton(graphics, target.x(), target.y());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (controlOpen) {
            graphics.pose().pushPose();
            graphics.pose().translate(leftPos, topPos, 400.0F);
            drawDetailPanel(graphics);
            drawControlPanel(graphics);
            graphics.pose().popPose();
        }
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        for (StellarHeaderTarget target : headerTargets) {
            if (!target.contains(localX, localY)) continue;
            graphics.renderTooltip(font, Component.translatable("gui.gt_shanhai.stellar_remote.button"),
                    mouseX, mouseY);
            break;
        }
        if (controlOpen) {
            DetailHit hit = detailHitAt(localX, localY);
            if (hit != null && hit.tooltip() != null) {
                List<FormattedCharSequence> wrapped = font.split(hit.tooltip(), DETAIL_TOOLTIP_WIDTH);
                if (!wrapped.isEmpty()) {
                    graphics.renderTooltip(font, wrapped, mouseX, mouseY);
                }
            }
        }
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (controlContains(mouseX - leftPos, mouseY - topPos)) {
            return;
        }
        super.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            rebuildHeaderTargets();
            int localX = (int) mouseX - leftPos;
            int localY = (int) mouseY - topPos;
            if (controlOpen && handleControlClick(localX, localY)) {
                return true;
            }
            for (StellarHeaderTarget target : headerTargets) {
                if (!target.contains(localX, localY)) continue;
                if (controlOpen && controlServerIds.equals(target.serverIds())) {
                    controlOpen = false;
                } else {
                    openControl(target);
                }
                return true;
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

    private void openControl(StellarHeaderTarget target) {
        controlOpen = true;
        controlServerIds = target.serverIds();
        selectedServerId = target.serverIds().get(0);
        placeControlPanel(target);
    }

    private boolean controlContains(int localX, int localY) {
        return actionContains(localX, localY) || detailContains(localX, localY);
    }

    private boolean actionContains(int localX, int localY) {
        return controlOpen && localX >= controlX && localY >= controlY
                && localX < controlX + PANEL_WIDTH && localY < controlY + controlHeight();
    }

    private boolean detailContains(int localX, int localY) {
        if (!controlOpen) return false;
        int height = detailHeight(selectedDetail());
        int x = detailX();
        int y = detailY(height);
        return localX >= x && localY >= y && localX < x + DETAIL_WIDTH && localY < y + height;
    }

    private boolean handleControlClick(int localX, int localY) {
        if (detailContains(localX, localY)) {
            Operation action = detailOperationAt(localX, localY);
            if (action != null) {
                ShanhaiNetwork.CHANNEL.sendToServer(new ShanhaiPatternRemoteConfigPacket(
                        getMenu().containerId, selectedServerId, action, 0));
            }
            return true;
        }
        if (!actionContains(localX, localY)) {
            controlOpen = false;
            return true;
        }
        int row = (localY - controlY - 3) / ROW_STRIDE;
        int providerRows = controlServerIds.size() > 1 ? controlServerIds.size() : 0;
        if (row >= 0 && row < providerRows) {
            selectedServerId = controlServerIds.get(row);
            return true;
        }
        int actionIndex = row - providerRows - (providerRows > 0 ? 1 : 0);
        if (actionIndex >= 0 && actionIndex < CONTROL_ACTIONS.length) {
            long serverId = selectedServerId;
            controlOpen = false;
            ShanhaiNetwork.CHANNEL.sendToServer(new ShanhaiPatternRemoteConfigPacket(
                    getMenu().containerId, serverId, CONTROL_ACTIONS[actionIndex], -1));
        }
        return true;
    }

    private void rebuildHeaderTargets() {
        headerTargets.clear();
        stellarHeaderRows.clear();
        Object rowsValue = readField(this, ROWS_FIELD);
        if (!(rowsValue instanceof List<?> rows)) return;
        int scroll = currentScroll();
        int visible = visibleRows();
        for (int index = 0; index < rows.size(); index++) {
            Object row = rows.get(index);
            if (row == null || !row.getClass().getSimpleName().equals("GroupHeaderRow")) continue;
            List<Long> serverIds = new ArrayList<>();
            for (int next = index + 1; next < rows.size(); next++) {
                Object candidate = rows.get(next);
                if (candidate == null) continue;
                if (candidate.getClass().getSimpleName().equals("GroupHeaderRow")) break;
                if (!candidate.getClass().getSimpleName().equals("SlotsRow")) continue;
                long serverId = serverIdOf(candidate);
                if (!getMenu().isStellarContainer(serverId) || serverIds.contains(serverId)) continue;
                serverIds.add(serverId);
            }
            if (serverIds.isEmpty()) continue;
            stellarHeaderRows.add(index);
            if (index < scroll || index >= scroll + visible) continue;
            int visibleIndex = index - scroll;
            headerTargets.add(new StellarHeaderTarget(List.copyOf(serverIds),
                    HEADER_BUTTON_X, HEADER_BUTTON_Y + visibleIndex * ROW_HEIGHT));
        }
        if (!controlOpen) return;
        StellarHeaderTarget anchor = null;
        for (StellarHeaderTarget target : headerTargets) {
            if (target.serverIds().equals(controlServerIds)) {
                anchor = target;
                break;
            }
        }
        if (anchor == null || !anchor.serverIds().contains(selectedServerId)) {
            controlOpen = false;
            return;
        }
        placeControlPanel(anchor);
    }

    private void placeControlPanel(StellarHeaderTarget target) {
        controlX = target.x() + BUTTON_WIDTH - PANEL_WIDTH;
        if (controlX < 4) controlX = 4;
        int detailScreenX = leftPos + controlX - DETAIL_GAP - DETAIL_WIDTH;
        if (detailScreenX < 2) {
            controlX += 2 - detailScreenX;
        }
        controlY = target.y() + BUTTON_HEIGHT + 1;
        int height = controlHeight();
        if (controlY + height > this.imageHeight - 2) {
            controlY = Math.max(2, target.y() - height - 1);
        }
    }

    private StellarTerminalDetail selectedDetail() {
        return getMenu().stellarDetail(selectedServerId);
    }

    private int detailX() {
        return controlX - DETAIL_GAP - DETAIL_WIDTH;
    }

    private int detailY(int height) {
        int y = controlY;
        if (topPos + y + height > this.height - 2) {
            y -= topPos + y + height - (this.height - 2);
        }
        if (topPos + y < 2) y = 2 - topPos;
        return y;
    }

    private int detailHeight(StellarTerminalDetail detail) {
        return 6 + detailLines(detail).size() * ROW_STRIDE;
    }

    private static boolean showHost(StellarTerminalDetail detail) {
        String host = detail.host().getString();
        return !host.isEmpty() && !host.equals(detail.name().getString());
    }

    private void drawDetailPanel(GuiGraphics graphics) {
        StellarTerminalDetail detail = selectedDetail();
        List<DetailLine> lines = detailLines(detail);
        int height = 6 + lines.size() * ROW_STRIDE;
        int x = detailX();
        int y = detailY(height);
        graphics.fill(x - 1, y - 1, x + DETAIL_WIDTH + 1, y + height + 1, PANEL_BORDER);
        graphics.fill(x, y, x + DETAIL_WIDTH, y + height, PANEL_BG);
        detailHits.clear();
        int textY = y + 3;
        int textX = x + 4;
        for (DetailLine line : lines) {
            Component shown = trimDetail(line.text());
            graphics.drawString(font, shown, textX, textY, line.color(), false);
            if (line.action() != null) {
                int underline = Math.min(font.width(shown), DETAIL_WIDTH - 8);
                graphics.fill(textX, textY + 9, textX + underline, textY + 10, line.color());
            }
            if (line.tooltip() != null || line.action() != null) {
                detailHits.add(new DetailHit(textX, textY, DETAIL_WIDTH - 8, ROW_STRIDE,
                        line.tooltip(), line.teleport()));
            }
            textY += ROW_STRIDE;
        }
    }

    private List<DetailLine> detailLines(StellarTerminalDetail detail) {
        List<DetailLine> lines = new ArrayList<>();
        if (detail == null) {
            lines.add(new DetailLine(Component.translatable("gui.gt_shanhai.stellar_remote.detail.pending"),
                    DETAIL_LABEL, null, false));
            return lines;
        }
        lines.add(new DetailLine(detail.name(), PANEL_TEXT, null, false));
        if (showHost(detail)) {
            lines.add(new DetailLine(detail.host(), DETAIL_LABEL, null, false));
        }
        lines.add(new DetailLine(Component.translatable(
                "gui.gt_shanhai.stellar_remote.detail.pos", detail.x(), detail.y(), detail.z()),
                DETAIL_LINK, Component.translatable("gui.gt_shanhai.stellar_remote.detail.teleport"), true));
        if (detail.proxyCount() <= 0) {
            lines.add(new DetailLine(Component.translatable("gui.gt_shanhai.stellar_remote.detail.proxies_none"),
                    DETAIL_LABEL, null, false));
        } else {
            Component proxies = Component.translatable(
                    "gui.gt_shanhai.stellar_remote.detail.proxies", detail.proxyCount());
            Component proxyTip = detail.proxyLines().isEmpty()
                    ? proxies
                    : Component.literal(detail.proxyLines());
            lines.add(new DetailLine(proxies, PANEL_TEXT, proxyTip, false));
        }
        if (detail.hostCount() > 0) {
            lines.add(new DetailLine(Component.translatable(
                    "gui.gt_shanhai.stellar_remote.detail.host_count", detail.hostCount()),
                    DETAIL_LABEL, null, false));
            Component types = detail.hostRecipeTypes().isEmpty()
                    ? Component.translatable("gui.gt_shanhai.stellar_remote.detail.host_types_none")
                    : Component.translatable("gui.gt_shanhai.stellar_remote.detail.host_types", detail.hostRecipeTypes());
            lines.add(new DetailLine(types, PANEL_TEXT, types, false));
            Component status = hostStatusLine(detail);
            lines.add(new DetailLine(status, detail.hostStatus().isEmpty() ? DETAIL_LABEL : DETAIL_WARN, status, false));
            Component hostPos = Component.translatable("gui.gt_shanhai.stellar_remote.detail.host_pos",
                    detail.hostX(), detail.hostY(), detail.hostZ());
            lines.add(new DetailLine(hostPos, DETAIL_LABEL, hostPos, false));
        }
        lines.add(new DetailLine(Component.translatable(
                "gui.gt_shanhai.stellar_remote.detail.patterns", detail.filledPatterns(), detail.patternCapacity()),
                PANEL_TEXT, null, false));
        lines.add(new DetailLine(Component.translatable(
                "gui.gt_shanhai.stellar_remote.detail.wildcard", detail.wildcardMothers(), detail.wildcardExpanded()),
                PANEL_TEXT, null, false));
        lines.add(new DetailLine(detail.multiplierEnabled()
                ? Component.translatable("gui.gt_shanhai.stellar_remote.detail.multiplier", detail.multiplier())
                : Component.translatable("gui.gt_shanhai.stellar_remote.detail.multiplier_off"),
                PANEL_TEXT, null, false));
        lines.add(new DetailLine(detail.circuit() < 0
                ? Component.translatable("gui.gt_shanhai.stellar_remote.detail.circuit_none")
                : Component.translatable("gui.gt_shanhai.stellar_remote.detail.circuit", detail.circuit()),
                PANEL_TEXT, null, false));
        if (detail.warnings() > 0) {
            lines.add(new DetailLine(Component.translatable(
                    "gui.gt_shanhai.stellar_remote.detail.warnings", detail.warnings()),
                    DETAIL_WARN, null, false));
            Component reportLink = Component.translatable("gui.gt_shanhai.stellar_remote.detail.open_stuck_report");
            lines.add(new DetailLine(reportLink, DETAIL_LINK, reportLink, Operation.OPEN_STUCK_REPORT));
        }
        if (detail.stuckBroadcast() != null && !detail.stuckBroadcast().isEmpty()) {
            String[] parts = detail.stuckBroadcast().split("\n", -1);
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty()) continue;
                Component stuck = Component.literal(parts[index]);
                lines.add(new DetailLine(stuck, DETAIL_WARN,
                        Component.literal(parts[index].replace(" | ", "\n")), false));
            }
        }
        lines.add(new DetailLine(Component.translatable(detail.online()
                ? "gui.gt_shanhai.stellar_remote.detail.online"
                : "gui.gt_shanhai.stellar_remote.detail.offline"),
                detail.online() ? DETAIL_LABEL : DETAIL_OFFLINE, null, false));
        return lines;
    }

    private static Component hostStatusLine(StellarTerminalDetail detail) {
        if (!detail.hostStatus().isEmpty()) {
            return Component.translatable("gui.gt_shanhai.stellar_remote.detail.host_status", detail.hostStatus());
        }
        if (!detail.hostFormed()) {
            return Component.translatable("gui.gt_shanhai.stellar_remote.detail.host_unformed");
        }
        return Component.translatable("gui.gt_shanhai.stellar_remote.detail.host_running");
    }

    private Operation detailOperationAt(int localX, int localY) {
        List<DetailLine> lines = detailLines(selectedDetail());
        int y = detailY(6 + lines.size() * ROW_STRIDE) + 3;
        int x = detailX();
        for (DetailLine line : lines) {
            if (line.action() != null && localX >= x && localX < x + DETAIL_WIDTH
                    && localY >= y && localY < y + ROW_STRIDE) {
                return line.action();
            }
            y += ROW_STRIDE;
        }
        return null;
    }

    private DetailHit detailHitAt(int localX, int localY) {
        for (DetailHit hit : detailHits) {
            if (localX >= hit.x() && localY >= hit.y()
                    && localX < hit.x() + hit.width() && localY < hit.y() + hit.height()) {
                return hit;
            }
        }
        return null;
    }

    private Component trimDetail(Component text) {
        int width = DETAIL_WIDTH - 8;
        if (font.width(text) <= width) return text;
        String raw = text.getString();
        String trimmed = font.plainSubstrByWidth(raw, width - font.width("…")) + "…";
        return Component.literal(trimmed);
    }

    private int controlHeight() {
        int providers = controlServerIds.size() > 1 ? controlServerIds.size() * ROW_STRIDE + ROW_STRIDE : 0;
        return 6 + providers + CONTROL_ACTIONS.length * ROW_STRIDE;
    }

    private void drawControlPanel(GuiGraphics graphics) {
        int height = controlHeight();
        graphics.fill(controlX - 1, controlY - 1, controlX + PANEL_WIDTH + 1, controlY + height + 1, PANEL_BORDER);
        graphics.fill(controlX, controlY, controlX + PANEL_WIDTH, controlY + height, PANEL_BG);
        int y = controlY + 3;
        if (controlServerIds.size() > 1) {
            for (int index = 0; index < controlServerIds.size(); index++) {
                long serverId = controlServerIds.get(index);
                if (serverId == selectedServerId) {
                    graphics.fill(controlX + 2, y - 1, controlX + PANEL_WIDTH - 2, y + 10, PANEL_SELECTED);
                }
                graphics.drawString(font, Component.translatable(
                        "gui.gt_shanhai.stellar_remote.provider", index + 1),
                        controlX + 4, y, PANEL_TEXT, false);
                y += ROW_STRIDE;
            }
            y += ROW_STRIDE;
        }
        for (String label : CONTROL_LABELS) {
            graphics.drawString(font, Component.translatable(label), controlX + 4, y, PANEL_TEXT, false);
            y += ROW_STRIDE;
        }
    }

    private void drawHeaderButton(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + BUTTON_WIDTH + 1, y + BUTTON_HEIGHT + 1, BUTTON_BORDER);
        graphics.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, BUTTON_BG);
        graphics.drawString(font, Component.translatable("gui.gt_shanhai.stellar_remote.mark"),
                x + 3, y + 2, BUTTON_TEXT, false);
    }

    private void hideStellarHeaderButtons() {
        Object value = readField(this, EAEP_OPEN_UI_BUTTONS_FIELD);
        if (!(value instanceof Map<?, ?> buttons)) return;
        for (Map.Entry<?, ?> entry : buttons.entrySet()) {
            if (!(entry.getKey() instanceof Integer row) || !stellarHeaderRows.contains(row)) continue;
            if (entry.getValue() instanceof AbstractWidget widget) {
                widget.visible = false;
                widget.active = false;
            }
        }
    }

    private int currentScroll() {
        Object scrollbar = readField(this, SCROLLBAR_FIELD);
        Object value = invokeNoArg(scrollbar, "getCurrentScroll");
        return value instanceof Integer scroll ? scroll : 0;
    }

    private int visibleRows() {
        Object value = readField(this, VISIBLE_ROWS_FIELD);
        return value instanceof Integer rows ? rows : 0;
    }

    private static long serverIdOf(Object slotsRow) {
        Object container = invokeNoArg(slotsRow, "container");
        Object id = invokeNoArg(container, "getServerId");
        return id instanceof Long value ? value : Long.MIN_VALUE;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + name;
        Method method = NO_ARG_METHODS.get(key);
        if (!NO_ARG_METHODS.containsKey(key)) {
            method = findNoArg(target.getClass(), name);
            NO_ARG_METHODS.put(key, method);
        }
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Method findNoArg(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object readField(Object target, Field field) {
        if (field == null || target == null) return null;
        try {
            return field.get(target);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static Field findNamedField(String name) {
        Field partial = null;
        Class<?> type = GuiExPatternTerminal.class;
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    field.setAccessible(true);
                    return field;
                }
                if (partial == null && field.getName().contains(name)) {
                    field.setAccessible(true);
                    partial = field;
                }
            }
            type = type.getSuperclass();
        }
        return partial;
    }

    private record DetailLine(Component text, int color, Component tooltip, Operation action) {
        private DetailLine(Component text, int color, Component tooltip, boolean teleport) {
            this(text, color, tooltip, teleport ? Operation.TELEPORT : null);
        }

        private boolean teleport() { return action == Operation.TELEPORT; }
    }

    private record DetailHit(int x, int y, int width, int height, Component tooltip, boolean teleport) {}

    private record StellarHeaderTarget(List<Long> serverIds, int x, int y) {
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + BUTTON_WIDTH
                    && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
        }
    }
}
