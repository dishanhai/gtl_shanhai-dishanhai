package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.api.whitehole.StandardSpacetime;
import com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleEngine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleConsumer;

/** Controls the local white hole preview. Host mode selection is managed by the machine. */
final class WhiteHoleDebugScreen extends Screen {
    private static final List<Integer> SPECTRA = List.of(0, 1, 2, 3, 4, 5);
    private WhiteHolePreset selectedPreset = WhiteHolePreset.KERR;
    private Component actionMessage = Component.empty();
    private int page;
    private int left, top, panelWidth;

    WhiteHoleDebugScreen() {
        super(label("title"));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        var preview = WhiteHoleRenderState.preview();
        panelWidth = Math.min(360, width - 12);
        int column = (panelWidth - 6) / 2;
        left = (width - panelWidth) / 2;
        int second = left + column + 6;
        top = Math.max(3, (height - 173) / 2);
        int tab = (panelWidth - 6) / 3;
        String[] pages = {"playback", "physics", "display"};
        for (int i = 0; i < pages.length; i++) {
            int target = i;
            addRenderableWidget(Button.builder(label(pages[i]), b -> {
                page = target;
                rebuildWidgets();
            }).bounds(left + i * (tab + 3), top + 15, tab, 20).build()).active = page != i;
        }
        switch (page) {
            case 0 -> playback(preview, second, column);
            case 1 -> physics(preview, second, column);
            case 2 -> display(preview, second, column);
            default -> throw new IllegalStateException("unknown page");
        }
    }

    private void playback(WhiteHoleRenderState.Session preview, int second, int column) {
        addRenderableWidget(CycleButton.onOffBuilder(WhiteHoleRenderState.previewEnabled())
                .create(left, top + 38, column, 20, label("preview"),
                        (button, enabled) -> WhiteHoleRenderState.setPreviewEnabled(enabled)));
        addRenderableWidget(CycleButton.onOffBuilder(preview.engine.isPaused())
                .create(second, top + 38, column, 20, label("paused"),
                        (button, value) -> preview.engine.setPaused(value)));
        addRenderableWidget(Button.builder(label("step"), button -> {
            preview.engine.setPaused(true);
            preview.engine.stepOnce();
            rebuildWidgets();
        }).bounds(left, top + 59, column, 20).build());
        addRenderableWidget(Button.builder(label("burst"), button -> {
            if (preview.engine.triggerBurst(1)) {
                actionMessage = Component.empty();
            } else if (preview.engine.snapshot().bursts().size() >= WhiteHoleEngine.MAX_ACTIVE_BURSTS) {
                actionMessage = Component.translatable("gt_shanhai.white_hole.burst_full", WhiteHoleEngine.MAX_ACTIVE_BURSTS);
            } else {
                actionMessage = label("burst_exhausted");
            }
        }).bounds(second, top + 59, column, 20).build());
        addRenderableWidget(new ValueSlider(left, top + 80, column, "rate", .125, 4, preview.rate, preview::setRate));
        addRenderableWidget(Button.builder(label("seed_random"), button ->
                preview.reseed(ThreadLocalRandom.current().nextLong()))
                .bounds(second, top + 80, column, 20).build());
        addRenderableWidget(CycleButton.<WhiteHolePreset>builder(preset -> label(preset.key()))
                .withValues(WhiteHolePreset.values()).withInitialValue(selectedPreset)
                .create(left, top + 101, column, 20, label("presets"), (button, preset) -> {
                    selectedPreset = preset;
                    preset.settings(preview.engine.parameters().seed()).apply(preview);
                }));
        addRenderableWidget(Button.builder(label("reset_time"), button -> {
            boolean paused = preview.engine.isPaused();
            preview.engine.reset();
            preview.engine.setPaused(paused);
            preview.engine.setTimeScale(preview.rate);
            actionMessage = Component.empty();
        }).bounds(second, top + 101, column, 20).build());
        addRenderableWidget(Button.builder(label("save"), button -> presetFile(preview, true))
                .bounds(left, top + 122, column, 20).build());
        addRenderableWidget(Button.builder(label("load"), button -> presetFile(preview, false))
                .bounds(second, top + 122, column, 20).build());
    }

    private void physics(WhiteHoleRenderState.Session preview, int second, int column) {
        var p = preview.engine.parameters();
        double reference = WhiteHoleRenderState.PREVIEW_REFERENCE_MASS;
        double nominalPower = com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleParameters.NOMINAL_LUMINOSITY_WATTS;
        addRenderableWidget(new ValueSlider(left, top + 38, column, "mass", .15, 6, p.referenceMassKg() / reference,
                value -> {
                    var now = preview.engine.parameters();
                    preview.engine.setParameters(now.withMass(value * reference, now.minimumMassFraction()));
                }));
        var spin = addRenderableWidget(new ValueSlider(second, top + 38, column, "spin", -.998, .998,
                p.dimensionlessSpin(), value ->
                        preview.engine.setParameters(preview.engine.parameters().withDimensionlessSpin(value))));
        spin.active = p.spacetime() == StandardSpacetime.KERR || p.spacetime() == StandardSpacetime.QUANTUM_BOUNCE;
        addRenderableWidget(new ValueSlider(left, top + 59, column, "velocity", .01, .95, p.outflowBeta(), value -> {
            var now = preview.engine.parameters();
            preview.engine.setParameters(now.withEmission(now.radiation(), now.luminosityWatts(),
                    now.restMassFlowKgPerSecond(), value, now.plasmaTemperatureKelvin()));
        }));
        addRenderableWidget(new ValueSlider(second, top + 59, column, "power", 0, 4, p.luminosityWatts() / nominalPower,
                value -> {
                    var now = preview.engine.parameters();
                    preview.engine.setParameters(now.withEmission(now.radiation(), value * nominalPower,
                            now.restMassFlowKgPerSecond(), now.outflowBeta(), now.plasmaTemperatureKelvin()));
                }));
        addRenderableWidget(new ValueSlider(left, top + 80, column, "temperature", 3000, 100000,
                p.plasmaTemperatureKelvin(), value -> {
                    var now = preview.engine.parameters();
                    preview.engine.setParameters(now.withEmission(now.radiation(), now.luminosityWatts(),
                            now.restMassFlowKgPerSecond(), now.outflowBeta(), value));
                }));
        addRenderableWidget(new ValueSlider(second, top + 80, column, "mass_flow", 0, 10,
                p.restMassFlowKgPerSecond() / 4e17, value -> {
                    var now = preview.engine.parameters();
                    preview.engine.setParameters(now.withEmission(now.radiation(), now.luminosityWatts(),
                            value * 4e17, now.outflowBeta(), now.plasmaTemperatureKelvin()));
                }));
        addRenderableWidget(new ValueSlider(left, top + 101, column, "observer_beta", -.85f, .85f,
                preview.observerBeta, value -> preview.observerBeta = (float) value));
    }

    private void display(WhiteHoleRenderState.Session preview, int second, int column) {
        addRenderableWidget(new ValueSlider(second, top + 38, column, "jets", 0, 2, preview.jetGain,
                value -> preview.jetGain = (float) value));
        addRenderableWidget(new ValueSlider(left, top + 59, column, "disk_density", .05f, 4, preview.diskDensity,
                value -> preview.diskDensity = (float) value));
        addRenderableWidget(new ValueSlider(second, top + 59, column, "disk_thickness", .25, 3,
                preview.diskThickness, value -> preview.diskThickness = (float) value));
        addRenderableWidget(new ValueSlider(left, top + 38, column, "core", 0, 2, preview.coreGain,
                value -> preview.coreGain = (float) value));
        addRenderableWidget(CycleButton.<Integer>builder(i -> label("spectrum." + i))
                .withValues(SPECTRA).withInitialValue(preview.spectrum)
                .create(left, top + 80, column, 20, label("spectrum"), (button, i) -> preview.spectrum = i));
        addRenderableWidget(Button.builder(label("optics"), button -> minecraft.setScreen(new KerrDebugScreen(this, KerrControls.WHITE)))
                .bounds(second, top + 80, column, 20).build());
        addRenderableWidget(CycleButton.onOffBuilder(preview.automaticExposure)
                .create(left, top + 101, column, 20, label("auto_exposure"),
                        (button, on) -> preview.automaticExposure = on));
        addRenderableWidget(CycleButton.<Integer>builder(mode -> label("diagnostic." + mode))
                .withValues(0, 1, 2).withInitialValue(preview.debugMode)
                .create(second, top + 101, column, 20, label("diagnostic"), (button, mode) -> preview.debugMode = mode));
    }

    private void presetFile(WhiteHoleRenderState.Session preview, boolean save) {
        Path path = FMLPaths.CONFIGDIR.get().resolve("gt_shanhai/white_hole_preview.json");
        try {
            if (save) WhiteHolePreset.Settings.capture(preview).save(path);
            else WhiteHolePreset.Settings.load(path).apply(preview);
            actionMessage = label(save ? "saved" : "loaded");
        } catch (IOException | IllegalArgumentException | NullPointerException | com.google.gson.JsonParseException e) {
            actionMessage = label(save ? "save_failed" : "load_failed");
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(left - 4, top - 3, left + panelWidth + 4, top + 176, 0xc5161924);
        graphics.drawCenteredString(font, title, width / 2, top + 1, 0xfff5f7fc);
        super.render(graphics, mouseX, mouseY, partialTick);
        var snapshot = WhiteHoleRenderState.preview().engine.snapshot();
        var status = Component.translatable("gt_shanhai.white_hole.status",
                String.format(Locale.ROOT, "%.2f", snapshot.simulationTimeSeconds()), snapshot.seed());
        graphics.drawCenteredString(font, actionMessage, width / 2, top + 146, 0xffc6d2e7);
        graphics.drawCenteredString(font, status, width / 2, top + 160, 0xffc6d2e7);
    }

    private static Component label(String key) { return Component.translatable("gt_shanhai.white_hole." + key); }

    private static final class ValueSlider extends AbstractSliderButton {
        private final String key;
        private final double minimum;
        private final double span;
        private final DoubleConsumer setter;

        ValueSlider(int x, int y, int width, String key, double minimum, double maximum, double initial,
                    DoubleConsumer setter) {
            super(x, y, width, 20, Component.empty(), Math.clamp((initial - minimum) / (maximum - minimum), 0, 1));
            this.key = key;
            this.minimum = minimum;
            this.span = maximum - minimum;
            this.setter = setter;
            updateMessage();
        }

        private double actual() { return minimum + span * value; }

        @Override protected void updateMessage() {
            setMessage(Component.translatable("gt_shanhai.kerr.value", label(key),
                    String.format(Locale.ROOT, "%.2f", actual())));
        }

        @Override protected void applyValue() { setter.accept(actual()); }
    }
}
