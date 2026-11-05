package com.dishanhai.gt_shanhai.common.machine.part.ui;

import com.dishanhai.gt_shanhai.api.machine.IRecipeThreadDisplay;
import com.dishanhai.gt_shanhai.common.machine.part.CachedPatternSlotWidget;
import com.dishanhai.sh_better_ui.client.glass.GlassPalette;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopTheme;
import com.gregtechceu.gtceu.api.gui.widget.NumberInputWidget;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TankWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.custom.PlayerInventoryWidget;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 山海：服务端只创建槽位和同步控件；玻璃/GL 入口只由客户端绘制回调访问。 */
public class RecipeThreadPanelWidget extends WidgetGroup {
    private static final Map<Widget, Boolean> STELLAR_STYLED_CONTROLS = new WeakHashMap<>();
    private final IMultiPart source;
    private final BooleanSupplier enabled;
    private final LongSupplier capacity;
    private boolean glassSlotsConfigured;

    public RecipeThreadPanelWidget(int x, int y, int width, int height, IMultiPart source,
                                  ItemStackTransfer slot, BooleanSupplier enabled, Consumer<Boolean> setEnabled,
                                  LongSupplier capacity, boolean sharedSlot) {
        super(x, y, width, height);
        this.source = source;
        this.enabled = enabled;
        this.capacity = capacity;
        var text = new ComponentPanelWidget(12, 10, this::buildText);
        text.setMaxWidthLimit(width - 56);
        text.clickHandler((command, click) -> {
            if (!click.isRemote && "recipe_threads_toggle".equals(command)) setEnabled.accept(!enabled.getAsBoolean());
        });
        addWidget(text);
        var item = new SlotWidget(slot, 0, width - 34, 37, true, true);
        item.setHoverTooltips(Component.translatable("gt_shanhai.recipe_threads.slot.tooltip"),
                Component.translatable(sharedSlot ? "gt_shanhai.recipe_threads.slot.shared" : "gt_shanhai.recipe_threads.slot.retained"));
        addWidget(item);
    }

    private void buildText(List<Component> lines) {
        lines.add(Component.translatable("gt_shanhai.recipe_threads.ui.title"));
        lines.add(Component.translatable("gt_shanhai.recipe_threads.ui.capacity", number(capacity.getAsLong())));
        lines.add(Component.translatable("gt_shanhai.recipe_threads.ui.state",
                Component.translatable(enabled.getAsBoolean() ? "gt_shanhai.recipe_threads.enabled" : "gt_shanhai.recipe_threads.disabled")));
        long running = 0, waiting = 0, reserved = 0, parallel = 0;
        var owners = source.getControllers();
        for (var owner : owners) {
            if (owner instanceof IRecipeLogicMachine machine && machine.getRecipeLogic() instanceof IRecipeThreadDisplay display
                    && display.isRecipeThreadDisplayAvailable()) {
                var snapshot = display.getRecipeThreadDisplay(0, 0);
                running = saturatedAdd(running, snapshot.getInt("running"));
                waiting = saturatedAdd(waiting, snapshot.getInt("waiting"));
                reserved = saturatedAdd(reserved, snapshot.getLong("reserved"));
                parallel = saturatedAdd(parallel, snapshot.getLong("parallelCapacity"));
            }
        }
        lines.add(Component.translatable("gt_shanhai.recipe_threads.ui.owners", owners.size()));
        lines.add(Component.translatable("gt_shanhai.recipe_threads.ui.activity", number(running), number(waiting)));
        lines.add(Component.translatable("gt_shanhai.recipe_threads.ui.parallel", number(reserved), number(parallel)));
        lines.add(ComponentPanelWidget.withButton(Component.translatable(enabled.getAsBoolean()
                ? "gt_shanhai.recipe_threads.action.disable" : "gt_shanhai.recipe_threads.action.enable"), "recipe_threads_toggle"));
    }

    private static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }
    private static long saturatedAdd(long a, long b) {
        return b <= 0L ? a : a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!glassSlotsConfigured) {
            applyGlassSlots(this);
            glassSlotsConfigured = true;
        }
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
    }

    public static WidgetGroup glassRoot(int width, int height) {
        return new WidgetGroup(0, 0, width, height) {
            private boolean glassSlotsConfigured;

            @Override
            @OnlyIn(Dist.CLIENT)
            public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                if (!glassSlotsConfigured) {
                    applyGlassSlots(this);
                    glassSlotsConfigured = true;
                }
                Renderer.glass(graphics, getPosition().x, getPosition().y, getSize().width, getSize().height);
                super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            }
        };
    }

    /** 遠端詳情頁也會在 initWidget／選取庫存後建立控件。 */
    public static WidgetGroup stellarRoot(int width, int height) {
        return new WidgetGroup(0, 0, width, height) {
            @Override
            @OnlyIn(Dist.CLIENT)
            public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                styleStellarControls(this);
                super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            }
        };
    }

    /** 山海：只换槽底材质，保留原槽、绑定库存及全部点击行为。 */
    @OnlyIn(Dist.CLIENT)
    public static void applyGlassSlots(WidgetGroup root) {
        for (var inventory : root.getWidgetsByType(PlayerInventoryWidget.class)) {
            inventory.setBackground((IGuiTexture) null);
            inventory.setSlotBackground(Renderer.SLOT);
        }
        for (var slot : root.getWidgetsByType(SlotWidget.class)) {
            if (!(slot instanceof CachedPatternSlotWidget) && !hasPatternOverlay(slot)) {
                slot.setBackgroundTexture(Renderer.SLOT);
            }
        }
        for (var field : root.getWidgetsByType(TextFieldWidget.class)) {
            field.setBordered(false).setBackground(GlassPanelTexture.display());
        }
        for (var number : root.getWidgetsByType(NumberInputWidget.class)) {
            styleNumberInput(number);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void applyStellarGlassSlots(WidgetGroup root) {
        for (var inventory : root.getWidgetsByType(PlayerInventoryWidget.class)) {
            inventory.setBackground((IGuiTexture) null);
            inventory.setSlotBackground(StellarGlassTexture.slot());
        }
        for (var slot : root.getWidgetsByType(SlotWidget.class)) {
            if (!(slot instanceof CachedPatternSlotWidget) && !hasPatternOverlay(slot)) {
                slot.setBackgroundTexture(StellarGlassTexture.slot());
            }
        }
        for (var field : root.getWidgetsByType(TextFieldWidget.class)) {
            field.setBordered(false).setBackground(StellarGlassTexture.display());
        }
        for (var number : root.getWidgetsByType(NumberInputWidget.class)) {
            styleStellarNumberInput(number);
        }
    }

    private static boolean hasPatternOverlay(SlotWidget slot) {
        return slot.getBackgroundTexture() instanceof GuiTextureGroup group
                && group.textures.length > 1 && group.textures[1] == GuiTextures.PATTERN_OVERLAY;
    }

    /** 持續掃描動態控件；材質替換冪等，亦涵蓋 ImageWidget 每 tick 更新的供應器。 */
    @OnlyIn(Dist.CLIENT)
    public static void styleStellarControls(WidgetGroup root) {
        boolean neutral = StellarGlassTexture.neutral();
        for (Widget widget : root.getWidgetsByType(Widget.class)) {
            widget.setBackground(StellarGlassTexture.adapt(widget.getBackgroundTexture()));
            Boolean previous = STELLAR_STYLED_CONTROLS.put(widget, neutral);
            if (previous != null && previous == neutral) continue;
            if (previous == null) {
                if (widget instanceof SwitchWidget toggle) StellarGlassTexture.styleSwitch(toggle);
            }
            if (widget instanceof LabelWidget label) {
                label.setTextColor(StellarGlassTexture.textColor()).setDropShadow(false);
            }
            if (widget instanceof TextFieldWidget field) {
                field.setTextColor(StellarGlassTexture.textColor());
                field.setBordered(false).setBackground(StellarGlassTexture.display());
            }
        }
    }

    /** 原數值控件保留按鍵回調、快捷增量及文字同步，只替換原生按鍵與輸入底色。 */
    public static void styleNumberInput(NumberInputWidget<?> input) {
        for (var button : input.getWidgetsByType(ButtonWidget.class)) {
            if (button.getBackgroundTexture() instanceof GuiTextureGroup current && current.textures.length > 1) {
                button.setButtonTexture(new GuiTextureGroup(GlassPanelTexture.button(), current.textures[1]));
            }
        }
        for (var field : input.getWidgetsByType(TextFieldWidget.class)) {
            field.setBordered(false).setBackground(GlassPanelTexture.display());
        }
    }

    public static void styleStellarNumberInput(NumberInputWidget<?> input) {
        for (var button : input.getWidgetsByType(ButtonWidget.class)) {
            if (button.getBackgroundTexture() instanceof GuiTextureGroup current && current.textures.length > 1) {
                button.setButtonTexture(new GuiTextureGroup(StellarGlassTexture.button(), current.textures[1]));
            }
        }
        for (var field : input.getWidgetsByType(TextFieldWidget.class)) {
            field.setBordered(false).setBackground(StellarGlassTexture.display());
        }
    }

    /** GTLCore 在打開樣板時才建立催化劑內層控件，因此每次 toggle 後套用。 */
    public static void styleCatalystControls(WidgetGroup manager) {
        for (int index = 1; index <= 2 && index < manager.widgets.size(); index++) {
            if (manager.widgets.get(index) instanceof WidgetGroup section && section.widgets.size() > 1
                    && section.widgets.get(1) instanceof WidgetGroup container) {
                container.setBackground(StellarGlassTexture.page());
            }
        }
        for (var slot : manager.getWidgetsByType(SlotWidget.class)) {
            slot.setBackgroundTexture(StellarGlassTexture.slot());
        }
        for (var tank : manager.getWidgetsByType(TankWidget.class)) {
            tank.setBackground(StellarGlassTexture.slot());
        }
        for (var number : manager.getWidgetsByType(NumberInputWidget.class)) {
            styleStellarNumberInput(number);
        }
        if (LDLib.isRemote()) styleStellarControls(manager);
    }

    @OnlyIn(Dist.CLIENT)
    public static void drawGlass(GuiGraphics graphics, int x, int y, int width, int height) {
        Renderer.glass(graphics, x, y, width, height);
    }

    @OnlyIn(Dist.CLIENT)
    private static final class Renderer {
        private static final IGuiTexture SLOT = (g, mx, my, x, y, width, height) -> {
            boolean hovered = mx >= x && mx < x + width && my >= y && my < y + height;
            ShopTheme.GLASS.box(g, (int) x, (int) y, width, height,
                    hovered ? ShopTheme.GLASS.frame : ShopTheme.GLASS.cellEdge,
                    hovered ? ShopTheme.GLASS.selectBg : ShopTheme.GLASS.barBg);
        };
        private static void glass(GuiGraphics g, int x, int y, int width, int height) {
            // 完全复用商店 GLASS 根板：同一套阴影、模糊、半透明底和方形细边。
            ShopTheme.GLASS.rootShadow(g, x, y, width, height);
            ShopTheme.GLASS.backdrop(g, x, y, width, height);
            ShopTheme.GLASS.box(g, x, y, width, height, GlassPalette.ROOT_EDGE,
                    ShopTheme.GLASS.panelBg);
        }

    }
}
