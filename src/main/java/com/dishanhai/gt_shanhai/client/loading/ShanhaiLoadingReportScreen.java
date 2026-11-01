package com.dishanhai.gt_shanhai.client.loading;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
        super(Component.literal("山海載入效能分析")); this.parent = parent; this.startup = startup;
    }

    @Override protected void init() {
        int half = Math.max(50, (width - 44) / 2);
        addRenderableWidget(Button.builder(Component.literal("啟動初始化"), button -> select(true)).bounds(20, 27, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("進入世界"), button -> select(false)).bounds(width / 2 + 2, 27, half, 20).build());
        String[] modes = {"耗時", "CPU", "配置", "慢配方", "熱點", "配置估計"};
        int itemWidth = Math.max(30, (width - 60) / 6);
        for (int i = 0; i < modes.length; i++) {
            final int selected = i;
            addRenderableWidget(Button.builder(Component.literal(modes[i]), button -> { mode = selected; scroll = 0; rebuild(); })
                    .bounds(20 + i * (itemWidth + 4), 51, itemWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose()).bounds(width / 2 + 49, height - 27, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("重新整理"), button -> rebuild()).bounds(width / 2 - 45, height - 27, 90, 20).build());
        var folder = addRenderableWidget(Button.builder(Component.literal("開啟報告目錄"), button -> Util.getPlatform().openFile(ShanhaiLoadingProfiler.directory().toFile()))
                .bounds(width / 2 - 139, height - 27, 90, 20).build());
        folder.active = Files.isDirectory(ShanhaiLoadingProfiler.directory());
        rebuild();
    }

    private void select(boolean value) { startup = value; scroll = 0; rebuild(); }

    private void rebuild() {
        report = ShanhaiLoadingProfiler.report(startup);
        lines.clear();
        if (report == null) {
            append(startup ? "尚無啟動分析。分析器啟用後，完成下一次遊戲初始化便會產生報告。"
                    : "尚無世界分析。請自行選擇存檔或伺服器，完成載入後再查看。", 0xffb3c4d0);
        } else {
            var metrics = report.metrics();
            append("擷取：" + report.capturedAt() + "；狀態：" + metrics.outcome(), 0xff829aaa);
            append("程序 CPU " + (report.processCpuNanos() < 0 ? "N/A" : ms(report.processCpuNanos()) + " ms")
                    + "；平均使用核心數 " + (report.processCpuNanos() < 0 || metrics.elapsedNanos() <= 0 ? "N/A"
                    : String.format(Locale.ROOT, "%.2f / %d", (double) report.processCpuNanos() / metrics.elapsedNanos(), report.processors())), 0xffb3c4d0);
            append("堆淨變化 " + mib(metrics.heapDeltaBytes()) + "；已觀察堆峰值 " + mib(metrics.peakObservedHeapBytes())
                    + "；GC " + (report.gcCollections() < 0 ? "N/A" : report.gcCollections() + " 次 / " + report.gcMillis() + " ms"), 0xffb3c4d0);
            append("並行與巢狀工作不可相加當作總耗時。含等待／Forge 觀測項不是 CPU 執行時間；堆數值含其他執行緒與 GC。", 0xff829aaa);
            if (mode >= 4) appendHotspots();
            var entries = new ArrayList<>(mode >= 4 ? List.<ShanhaiLoadingMetrics.Entry>of() : mode == 3 ? metrics.slowRecipes() : metrics.entries());
            Comparator<ShanhaiLoadingMetrics.Entry> order = mode == 1 ? Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::cpuNanos)
                    : mode == 2 ? Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::allocatedBytes)
                    : Comparator.comparingLong(ShanhaiLoadingMetrics.Entry::elapsedNanos);
            entries.sort(order.reversed());
            int rank = 0;
            for (var entry : entries) {
                append("", 0);
                append(++rank + ". " + entry.name(), entry.elapsedNanos() >= 1_000_000_000L ? 0xffe4bd78 : 0xff80dfda);
                append("[" + kind(entry.kind()) + "] " + ms(entry.elapsedNanos()) + " ms；" + entry.calls() + " 次；失敗 " + entry.failures(), 0xffdbe8ed);
                append("CPU " + (entry.cpuSamples() == 0 ? "N/A" : ms(entry.cpuNanos()) + " ms（" + entry.cpuSamples() + " 筆）")
                        + "；配置量 " + (entry.allocationSamples() == 0 ? "N/A" : mib(entry.allocatedBytes()) + "（" + entry.allocationSamples() + " 筆）"), 0xffa6b9c6);
            }
            if (entries.isEmpty() && mode < 4) append("此分類沒有可用樣本。逐筆配方僅量耗時，CPU／配置量記在外層載入工作。", 0xff829aaa);
        }
        contentHeight = lines.size() * 12;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - Math.max(1, height - 142))));
    }

    private void appendHotspots() {
        var sampling = report.sampling();
        if (sampling == null) { append("此報告沒有抽樣資料。", 0xff829aaa); return; }
        append("JFR：" + sampling.status(), 0xffb3c4d0);
        append(mode == 4 ? "CPU 熱點依抽樣次數排序，不是精確 CPU 時間。" : "配置量為 JFR 抽樣估計，不是精確配置總量。", 0xff829aaa);
        var hotspots = mode == 4 ? sampling.cpuHotspots() : sampling.allocationHotspots();
        int rank = 0;
        for (var entry : hotspots) {
            append("", 0);
            append(++rank + ". " + entry.method(), 0xff80dfda);
            append(mode == 4 ? entry.cpuSamples() + " 個 CPU 樣本"
                    : "估計配置 " + mib(entry.sampledAllocationBytes()) + "；" + entry.allocationSamples() + " 個樣本", 0xffdbe8ed);
        }
        if (hotspots.isEmpty()) append("此分類尚無樣本；載入很快、抽樣停用或 JVM 不支援時可能為空。", 0xff829aaa);
    }

    private void append(String text, int color) {
        if (text.isEmpty()) { lines.add(new Line("", color)); return; }
        for (String line : ShanhaiLoadingLayout.wrap(text, Math.max(20, width - 52), font::width)) lines.add(new Line(line, color));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        if (report != null && report != ShanhaiLoadingProfiler.report(startup)) rebuild();
        graphics.fill(0, 0, width, height, 0xff09131f);
        graphics.drawCenteredString(font, title, width / 2, 9, 0xff80dfda);
        String summary = startup ? "啟動初始化" : "進入世界";
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
    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000d); }
    private static String mib(long bytes) { return String.format(Locale.ROOT, "%.2f MiB", bytes / 1048576d); }
    private static String kind(String value) {
        return switch (value) {
            case "prepare" -> "準備執行"; case "apply" -> "套用執行"; case "Forge observed" -> "Forge 觀測耗時";
            case "listener elapsed (includes waits)" -> "載入器耗時（含等待）";
            case "recipe namespace" -> "配方命名空間"; case "recipe" -> "單筆配方"; default -> value;
        };
    }
    private record Line(String text, int color) {}
}
