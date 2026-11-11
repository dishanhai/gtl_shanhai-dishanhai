package com.dishanhai.gt_shanhai.client.loading;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.dishanhai.sh_better_ui.client.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopTheme;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiMotion;
import java.util.function.BooleanSupplier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 山海：玩家可從主選單或指令查看兩段載入的慢項；不會開啟任何存檔。 */
public final class ShanhaiLoadingReportScreen extends Screen {
    private final Screen parent;
    private boolean startup;
    private int mode, scroll, contentHeight;
    private final List<Line> lines = new ArrayList<>();
    private ShanhaiLoadingProfiler.ProfileReport report;

    public ShanhaiLoadingReportScreen(Screen parent, boolean startup) {
        super(Component.translatable("gui.gt_shanhai.loading.report.title")); this.parent = parent; this.startup = startup;
    }

    @Override protected void init() {
        int half = Math.max(50, (width - 44) / 2);
        glassButton(tr("report.startup"), button -> select(true), 20, 27, half, 20, () -> startup);
        glassButton(tr("report.world"), button -> select(false), width / 2 + 2, 27, half, 20, () -> !startup);
        String[] modes = {tr("report.elapsed"), tr("report.cpu"), tr("report.allocation"), tr("report.recipes"), tr("report.hotspots"), tr("report.estimates")};
        int itemWidth = Math.max(30, (width - 60) / 6);
        for (int i = 0; i < modes.length; i++) {
            final int selected = i;
            glassButton(modes[i], button -> { mode = selected; scroll = 0; rebuild(); },
                    20 + i * (itemWidth + 4), 51, itemWidth, 20, () -> mode == selected);
        }
        glassButton(tr("report.back"), button -> onClose(), width / 2 + 49, height - 27, 90, 20, () -> false);
        glassButton(tr("report.refresh"), button -> rebuild(), width / 2 - 45, height - 27, 90, 20, () -> false);
        var folder = glassButton(tr("report.folder"), button -> Util.getPlatform().openFile(ShanhaiLoadingProfiler.directory().toFile()),
                width / 2 - 139, height - 27, 90, 20, () -> false);
        folder.active = Files.isDirectory(ShanhaiLoadingProfiler.directory());
        rebuild();
    }

    private Button glassButton(String text, Button.OnPress action, int x, int y, int w, int h, BooleanSupplier selected) {
        return addRenderableWidget(new GlassButton(text, action, x, y, w, h, selected));
    }

    private void select(boolean value) { startup = value; scroll = 0; rebuild(); }

    private void rebuild() {
        report = ShanhaiLoadingProfiler.report(startup);
        lines.clear();
        if (report == null) {
            append(startup ? tr("report.no_startup")
                    : tr("report.no_world"), 0xffb3c4d0);
        } else {
            var metrics = report.metrics();
            append(tr("report.capture_summary", report.capturedAt(), outcome(metrics.outcome())), 0xff829aaa);
            append(tr("report.cpu_summary", (report.processCpuNanos() < 0 ? "N/A" : ms(report.processCpuNanos()) + " ms"),
                    (report.processCpuNanos() < 0 || metrics.elapsedNanos() <= 0 ? "N/A"
                    : String.format(Locale.ROOT, "%.2f / %d", (double) report.processCpuNanos() / metrics.elapsedNanos(), report.processors()))), 0xffb3c4d0);
            append(tr("report.heap_summary", mib(metrics.heapDeltaBytes()), mib(metrics.peakObservedHeapBytes()),
                    report.gcCollections() < 0 ? "N/A" : tr("report.gc_summary", report.gcCollections(), report.gcMillis())), 0xffb3c4d0);
            append(tr("report.caveat"), 0xff829aaa);
            if (mode >= 4) appendHotspots();
            var entries = new ArrayList<>(mode >= 4 ? List.<ShanhaiLoadingMetrics.Entry>of() : mode == 3 ? metrics.slowRecipes() : metrics.entries());
            Comparator<ShanhaiLoadingMetrics.Entry> order = mode == 1 ? Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::cpuNanos)
                    : mode == 2 ? Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::allocatedBytes)
                    : Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::elapsedNanos);
            entries = new ArrayList<>(ShanhaiLoadingMetrics.executionFirst(entries, order));
            if (mode == 0) append(tr("report.execution_first"), 0xff80dfda);
            int rank = 0;
            for (var entry : entries) {
                append("", 0);
                append(++rank + ". " + entry.name(), entry.elapsedNanos() >= 1_000_000_000L ? 0xffe4bd78 : 0xff80dfda);
                append(tr("report.entry_summary", kind(entry.kind()), ms(entry.elapsedNanos()), entry.calls(), entry.failures()), 0xffdbe8ed);
                append(tr("report.resources_summary", entry.cpuSamples() == 0 ? "N/A" : tr("report.sample_value", ms(entry.cpuNanos()) + " ms", entry.cpuSamples()),
                        entry.allocationSamples() == 0 ? "N/A" : tr("report.sample_value", mib(entry.allocatedBytes()), entry.allocationSamples())), 0xffa6b9c6);
            }
            if (entries.isEmpty() && mode < 4) append(tr("report.no_entries"), 0xff829aaa);
        }
        contentHeight = lines.size() * 12;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - Math.max(1, height - 142))));
    }

    private void appendHotspots() {
        var sampling = report.sampling();
        if (sampling == null) { append(tr("report.no_sampling"), 0xff829aaa); return; }
        append(tr("report.jfr") + sampling.status(), 0xffb3c4d0);
        append(mode == 4 ? tr("report.cpu_note") : tr("report.allocation_note"), 0xff829aaa);
        var hotspots = mode == 4 ? sampling.cpuHotspots() : sampling.allocationHotspots();
        int rank = 0;
        for (var entry : hotspots) {
            append("", 0);
            append(++rank + ". " + entry.method(), 0xff80dfda);
            append(mode == 4 ? tr("report.cpu_sample_count", entry.cpuSamples())
                    : tr("report.allocation_sample_count", mib(entry.sampledAllocationBytes()), entry.allocationSamples()), 0xffdbe8ed);
        }
        if (hotspots.isEmpty()) append(tr("report.empty_hotspots"), 0xff829aaa);
    }

    private void append(String text, int color) {
        if (text.isEmpty()) { lines.add(new Line("", color)); return; }
        for (String line : ShanhaiLoadingLayout.wrap(text, Math.max(20, width - 52), font::width)) lines.add(new Line(line, color));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        if (report != null && report != ShanhaiLoadingProfiler.report(startup)) rebuild();
        graphics.flush();
        if (!ShanhaiLoadingBackdrop.draw(width, height, this, 1)) {
            ShanhaiLoadingRenderer.drawBackground(width, height, 0, 1, true);
            ShanhaiLoadingRenderer.warmGlass(false);
            ShopTheme.GLASS.rootPanel(graphics, 10, 3, width - 20, height - 6, 20, 101, width - 40, Math.max(1, height - 139));
            graphics.flush();
            ShanhaiLoadingBackdrop.capture(width, height, this);
        }
        graphics.drawCenteredString(font, title, width / 2, 9, 0xff80dfda);
        String summary = startup ? tr("report.startup") : tr("report.world");
        if (report != null) {
            var metrics = report.metrics();
            summary += "：" + ms(metrics.elapsedNanos()) + " ms";
        }
        graphics.drawString(font, summary, 22, 80, 0xffd6b878, false);
        graphics.hLine(20, width - 20, 96, 0xff264653);
        graphics.enableScissor(20, 102, width - 20, height - 38);
        for (int i = 0; i < lines.size(); i++) {
            int y = 104 + i * 12 - scroll;
            if (y > 90 && y < height - 38) graphics.drawString(font, lines.get(i).text(), 24, y, lines.get(i).color(), false);
        }
        graphics.disableScissor();
        int visible = Math.max(1, height - 142);
        if (contentHeight > visible) {
            int thumb = Math.max(12, visible * visible / contentHeight);
            int y = 102 + (visible - thumb) * scroll / Math.max(1, contentHeight - visible);
            graphics.fill(width - 16, y, width - 13, y + thumb, 0xff80dfda);
        }
        super.render(graphics, mouseX, mouseY, tick);
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - Math.max(1, height - 142)), scroll - (int) (delta * 30)));
        return true;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { ShanhaiLoadingRenderer.releaseBackdrop(); super.removed(); }
    private static String tr(String key, Object... args) { return Component.translatable("gui.gt_shanhai.loading." + key, args).getString(); }
    private static String outcome(String value) {
        return switch (value) {
            case "ready", "cancelled", "replaced", "running" -> tr("outcome." + value);
            default -> value;
        };
    }
    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000d); }
    private static String mib(long bytes) { return String.format(Locale.ROOT, "%.2f MiB", bytes / 1048576d); }
    private static String kind(String value) {
        return switch (value) {
            case "prepare" -> tr("kind.prepare"); case "apply" -> tr("kind.apply"); case "Forge observed" -> tr("kind.forge");
            case "listener elapsed (includes waits)" -> tr("kind.listener");
            case "recipe namespace" -> tr("kind.namespace"); case "recipe" -> tr("kind.recipe"); default -> value;
        };
    }
    private record Line(String text, int color) {}

    private final class GlassButton extends Button {
        private final BooleanSupplier selected;
        private final Spring hover = new Spring(Spring.HOVER);
        private GlassButton(String text, OnPress action, int x, int y, int w, int h, BooleanSupplier selected) {
            super(x, y, w, h, Component.literal(text), action, DEFAULT_NARRATION);
            this.selected = selected;
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
            long now = System.currentTimeMillis();
            float target = active && isHoveredOrFocused() ? 1 : 0;
            if (ShanhaiLoadingPreferences.reduceMotion()) hover.snap(target);
            else { hover.setTarget(target, now); hover.update(now); }
            boolean on = selected.getAsBoolean();
            int edge = on ? GlassPalette.ACCENT : GuiMotion.lerpColor(ShopTheme.GLASS.frameDark, GlassPalette.ACCENT, hover.value());
            int fill = on ? ShopTheme.GLASS.selectBg : GuiMotion.lerpColor(ShopTheme.GLASS.buttonBg, ShopTheme.GLASS.buttonHover, hover.value());
            ShopTheme.GLASS.box(graphics, getX(), getY(), getWidth(), getHeight(), edge, fill);
            renderScrollingString(graphics, font, 2, active ? GlassPalette.TEXT : GlassPalette.TEXT_OFF);
        }
    }
}
