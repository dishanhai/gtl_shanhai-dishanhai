package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Background;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter.*;

final class KerrDebugScreen extends Screen {
    private static final Parameter[][] PAGES = {
            {MASS, SPIN, TILT, AZIMUTH, DISTANCE, INCLINATION, ACCRETION, THICKNESS, TEMPERATURE, EMISSIVITY},
            {TIME_SCALE, EXPOSURE, BLOOM, ACCURACY, STEPS, OUTER_RADIUS}
    };

    private int page;
    private int left, top;
    private final Screen previous;
    private final KerrControls.Optics optics;

    KerrDebugScreen() { this(null, KerrControls.KERR); }

    KerrDebugScreen(Screen previous, KerrControls.Optics optics) {
        super(label("title"));
        this.previous = previous;
        this.optics = optics;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(previous);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        int panelWidth = Math.min(352, width - 12);
        int column = (panelWidth - 6) / 2;
        left = (width - panelWidth) / 2;
        top = Math.max(2, (height - 187) / 2);
        addRenderableWidget(Button.builder(label("physics"), b -> showPage(0))
                .bounds(left, top + 14, column, 20).build()).active = page != 0;
        addRenderableWidget(Button.builder(label("render"), b -> showPage(1))
                .bounds(left + column + 6, top + 14, column, 20).build()).active = page != 1;
        Parameter[] parameters = PAGES[page];
        for (int i = 0; i < parameters.length; i++) {
            addRenderableWidget(new ParameterSlider(left + i % 2 * (column + 6), top + 38 + 21 * (i / 2),
                    column, parameters[i], optics));
        }

        if (page == 0) {
            addRenderableWidget(CycleButton.onOffBuilder(optics.requestedReverse())
                    .create(left, top + 143, column, 20, label("reverse_disk"),
                            (b, reverse) -> optics.setReverseDisk(reverse)));
        } else {
            addRenderableWidget(CycleButton.<Quality>builder(q -> label(q == Quality.COMPATIBILITY && optics == KerrControls.KERR
                            ? "compatibility_analytic" : key(q)))
                    .withValues(Quality.values()).withInitialValue(optics.requestedQuality())
                    .create(left, top + 101, column, 20, label("quality"), (b, q) -> optics.setQuality(q)));
            addRenderableWidget(CycleButton.<Background>builder(b -> label(key(b)))
                    .withValues(Background.values()).withInitialValue(optics.requestedBackground())
                    .create(left + column + 6, top + 101, column, 20, label("background"),
                            (b, background) -> optics.setBackground(background)));
        }
        addRenderableWidget(Button.builder(label("reset"), b -> {
            optics.reset();
            rebuildWidgets();
        }).bounds(left + column + 6, top + 143, column, 20).build());
    }

    private void showPage(int index) {
        page = index;
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(left - 4, top - 3, width - left + 4, top + 189, 0xc5161924);
        graphics.drawCenteredString(font, title, width / 2, top + 1, 0xfff5f7fc);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, BlackHoleLensRenderer.kerrStatus(), width / 2, top + 172, 0xffc6d2e7);
    }

    static Component label(String name) { return Component.translatable("gt_shanhai.kerr." + name); }
    static String key(Enum<?> option) { return option.name().toLowerCase(Locale.ROOT); }

    private static final class ParameterSlider extends AbstractSliderButton {
        private final Parameter parameter;
        private final KerrControls.Optics optics;

        ParameterSlider(int x, int y, int width, Parameter parameter, KerrControls.Optics optics) {
            super(x, y, width, 20, Component.empty(),
                    (optics.requested(parameter) - parameter.min) / (parameter.max - parameter.min));
            this.parameter = parameter;
            this.optics = optics;
            updateMessage();
            if (parameter == DISTANCE || parameter == INCLINATION || parameter == MASS || parameter == STEPS) {
                setTooltip(Tooltip.create(label(key(parameter) + ".hint")));
            }
        }

        private double actual() {
            return parameter.min + value * (parameter.max - parameter.min);
        }

        @Override
        protected void updateMessage() {
            var number = (parameter == DISTANCE || parameter == STEPS) && actual() < 1
                    || parameter == INCLINATION && actual() < 0
                    ? label("automatic") : Component.literal(String.format(Locale.ROOT,
                            parameter == THICKNESS ? "%.3f" : "%.2f", actual()));
            setMessage(Component.translatable("gt_shanhai.kerr.value", label(key(parameter)), number));
        }

        @Override
        protected void applyValue() { optics.set(parameter, actual()); }
    }
}
