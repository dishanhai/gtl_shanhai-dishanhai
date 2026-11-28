package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.sh_better_ui.kernel.api.layout.SmoothVerticalScroll;
import com.dishanhai.sh_better_ui.kernel.api.layout.VerticalScroll;
import com.dishanhai.sh_better_ui.kernel.api.text.TextReadabilityReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterBootTimeline.*;
import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterBootTimeline.Result.*;
import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminal.phase;

/** Client presentation of the boot schedule and the existing terminal snapshot. */
final class StarLifterBootLog {
    private static final String TEXT = StarLifterTerminal.TEXT + "boot.log.";
    private static final CompoundTag EMPTY = new CompoundTag();
    private static final int BACK = 0xff080b15;
    private static final int LEFT = 5, TOP = 29, WIDTH = 165, HEIGHT = 88;
    private static final int ROW = 19, VIEW = 57, BODY = WIDTH - 16;
    private static final int GRAPHEME_INTERVAL_MS = 25, TYPED_ROW_HOLD_MS = 140;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final int INK = readable(0xb8c4dc), CYAN = readable(0x83e0f0), ORBIT_INK = readable(0x8bbadf);
    private static final int VIOLET = readable(0xc4a5f2), GOLD = readable(0xffd574), DIM = readable(0x9399b9);
    private static final String[] PHASE_KEYS = {"wake", "kernel", "check", "orbit", "stellar", "resonance", "imaging", "ready"};

    private enum Module {
        BOOT, KERNEL, NETWORK, CHECK, TOPOLOGY, MATRIX, COMPUTE, ORBIT, SYNC, STELLAR, SIM,
        RESONANCE, RENDER, RECIPE, DIAG, CONTROL, SYSTEM;

        final String label = "[" + name() + "]";

        int ink() {
            return switch (this) {
                case MATRIX, ORBIT, TOPOLOGY -> ORBIT_INK;
                case COMPUTE, SIM, RESONANCE -> VIOLET;
                case STELLAR -> GOLD;
                default -> CYAN;
            };
        }
    }

    private enum Probe { NONE, SNAPSHOT, STRUCTURE, PARTS, ENERGY, CARGO, RING, LINK, TARGET, PREVIEW, RECIPE, CONTROL, DIAG, SYSTEM }
    private record Event(int at, Module module, String key, boolean typed, Probe probe, int ring) {
        Event(int at, Module module, String key) { this(at, module, key, false, Probe.NONE, -1); }
        Event(int at, Module module, String key, Probe probe) { this(at, module, key, false, probe, -1); }
        Event(int at, Module module, String key, boolean typed) { this(at, module, key, typed, Probe.NONE, -1); }
        Event(int ring) { this(ringStart(ring), Module.ORBIT, "ring", false, Probe.RING, ring); }
    }

    private static final Event[] EVENTS = {
            new Event(45, Module.BOOT, "wake", true),
            new Event(KERNEL, Module.KERNEL, "context"),
            new Event(390, Module.KERNEL, "reference", true),
            new Event(515, Module.NETWORK, "snapshot", Probe.SNAPSHOT),
            new Event(CHECK, Module.CHECK, "structure", Probe.STRUCTURE),
            new Event(685, Module.CHECK, "parts", Probe.PARTS),
            new Event(760, Module.CHECK, "energy", Probe.ENERGY),
            new Event(830, Module.CHECK, "cargo", Probe.CARGO),
            new Event(ORBIT, Module.TOPOLOGY, "topology"), new Event(0),
            new Event(945, Module.MATRIX, "transform", true), new Event(1),
            new Event(1115, Module.COMPUTE, "projection"), new Event(2), new Event(3),
            new Event(1640, Module.SYNC, "reference_sync"),
            new Event(STELLAR, Module.STELLAR, "star_frame", true),
            new Event(1870, Module.STELLAR, "star_radius"),
            new Event(1960, Module.SIM, "field", true),
            new Event(2170, Module.SIM, "plasma"),
            new Event(2320, Module.SIM, "transport"),
            new Event(RESONANCE, Module.RESONANCE, "binding", Probe.LINK),
            new Event(2700, Module.NETWORK, "target", Probe.TARGET),
            new Event(IMAGING, Module.RENDER, "meshes", Probe.PREVIEW),
            new Event(3260, Module.RENDER, "material"),
            new Event(3390, Module.RECIPE, "recipe", Probe.RECIPE),
            new Event(3520, Module.CONTROL, "permit", Probe.CONTROL),
            new Event(3650, Module.DIAG, "diagnostics", Probe.DIAG),
            new Event(HANDOFF, Module.SYSTEM, "interface", Probe.SYSTEM)
    };

    private static final class Row {
        String body, fitted, visible;
        int[] boundaries;
        int shown, visibleWidth, resultSince, resultWidth;
        Result result;

        void show(Font font, String body, boolean typed) {
            if (body.equals(this.body)) return;
            this.body = body;
            fitted = fit(font, body, BODY - (typed ? 6 : 0));
            boundaries = typed ? graphemeEnds(fitted) : null;
            visible = fitted;
            visibleWidth = font.width(visible);
            shown = -1;
        }

        boolean type(Font font, int age) {
            if (boundaries == null) return false;
            int glyphs = visibleGraphemes(age, boundaries.length - 1);
            if (shown != glyphs) {
                // The fitted string is immutable for a typed event. Only its visible prefix changes.
                visible = fitted.substring(0, boundaries[glyphs]);
                visibleWidth = font.width(visible);
                shown = glyphs;
            }
            return glyphs < boundaries.length - 1;
        }

        boolean holdForTyping(int age) {
            return boundaries != null && age < (boundaries.length - 1) * GRAPHEME_INTERVAL_MS + TYPED_ROW_HOLD_MS;
        }
    }

    private final Row[] rows = new Row[EVENTS.length];
    private final float[] angles = new float[4];
    private final boolean[] angleReady = new boolean[4];
    private final VerticalScroll scroll = new VerticalScroll();
    private final SmoothVerticalScroll scrolling = new SmoothVerticalScroll(scroll);
    private int count, elapsed, followRow;
    private boolean follow = true;
    private boolean previewReady, previewOn, sampledPreview, summaryPrepared;
    private final String[] phases = new String[PHASE_KEYS.length];
    private String header, online, quickSync, quickRender, summary, resonanceLabel;
    private Result resonanceResult;
    private CompoundTag previousStatus, previousStructure, previousLink, previousRecipe;
    private MachineState state = new MachineState(false, false, false, false, false, false, false, "");

    void start() {
        count = elapsed = followRow = 0;
        follow = true;
        previewReady = sampledPreview = summaryPrepared = false;
        previousStatus = previousStructure = previousLink = previousRecipe = null;
        Arrays.fill(rows, null);
        Arrays.fill(angleReady, false);
        scroll.resize(VIEW, 0);
        scrolling.synchronize();
        for (int i = 0; i < phases.length; i++) phases[i] = label("phase." + PHASE_KEYS[i]);
        header = label("header");
        online = label("online");
        quickSync = label("snapshot");
        quickRender = label("observation");
        resonanceLabel = label("phase.resonance");
    }

    void sample(StarLifterTerminalSync sync, int ms, boolean previewOn, boolean drawn, double ticks, long now, boolean full) {
        elapsed = full ? cueMs(ms) : ms;
        this.previewOn = previewOn;
        previewReady = previewOn && drawn;
        boolean ready = sync.ready();
        var status = ready ? sync.group("status") : EMPTY;
        var structure = ready ? sync.group("structure") : EMPTY;
        var link = ready ? sync.group("link") : EMPTY;
        var recipe = ready ? sync.group("recipe") : EMPTY;
        boolean depleted = ready && sync.group("reserve").getBoolean("depleted");
        boolean refresh = status != previousStatus || structure != previousStructure || link != previousLink
                || depleted != state.depleted() || recipe != previousRecipe || sampledPreview != previewReady;
        if (refresh) {
            String operation = status.getString("state");
            state = new MachineState(ready, status.getBoolean("formed"), structure.getBoolean("unloaded"),
                    status.getBoolean("work"), operation.equals("working") && sync.recipe() != null,
                    depleted, operation.equals("waiting") || operation.equals("suspend"),
                    link.getString("state"));
            previousStatus = status;
            previousStructure = structure;
            previousLink = link;
            previousRecipe = recipe;
            sampledPreview = previewReady;
            summaryPrepared = false;
        }
        if (!summaryPrepared) {
            summary = label("summary." + state.online());
            resonanceResult = state.resonance();
            summaryPrepared = true;
        }
        if (!full) return;

        for (int i = 0; i < angles.length; i++) {
            if (!angleReady[i] && previewReady && elapsed >= ringEnd(i)) {
                var orbit = StarLifterGeometry.RINGS.get(i);
                angles[i] = orbit.spatialPeriod() == 0 ? orbit.rimDegrees(ticks) : orbit.spatialDegrees(ticks);
                angleReady[i] = true;
            }
        }
        Font font = Minecraft.getInstance().font;
        int previousCount = count;
        while (count < EVENTS.length && EVENTS[count].at <= elapsed) count++;
        if (count != previousCount) scroll.resize(VIEW, count * ROW);
        int firstTypingRow = count;
        for (int i = 0; i < count; i++) {
            var event = EVENTS[i];
            var row = rows[i];
            if (row == null) {
                rows[i] = row = new Row();
                row.show(font, body(event), event.typed);
            } else if ((event.probe == Probe.RING && row.result != SYNCED && angleReady[event.ring])
                    || (refresh && event.probe != Probe.NONE && event.probe != Probe.RING)) {
                row.show(font, body(event), event.typed);
            }
            if (follow && event.typed && firstTypingRow == count && row.holdForTyping(playbackMs(elapsed - event.at))) {
                firstTypingRow = i;
            }
            int feedbackAt = event.probe == Probe.RING ? event.at + 68 : event.at + 108;
            Result next = event.probe == Probe.NONE || elapsed < feedbackAt ? null : result(event);
            if (row.result != next) {
                row.result = next;
                row.resultSince = elapsed;
                row.resultWidth = next == null ? 0 : font.width(next.label);
            }
        }
        if (follow) {
            int topRow = Math.min((int) scroll.maxOffset() / ROW, firstTypingRow);
            if (topRow != followRow) {
                followRow = topRow;
                scrolling.scrollTo(topRow * ROW, now, false);
            }
        }
    }

    private Result result(Event event) {
        return switch (event.probe) {
            case SNAPSHOT -> state.ready() ? SYNCED : PENDING;
            case STRUCTURE -> state.structure();
            case PARTS, ENERGY, CARGO -> !state.ready() ? PENDING : state.formed() ? SYNCED : SKIPPED;
            case RING -> angleReady[event.ring] ? SYNCED : !previewOn ? SKIPPED
                    : !previewReady || elapsed >= ringEnd(event.ring) ? DEFERRED : COMPUTING;
            case LINK, TARGET -> state.resonance();
            case PREVIEW -> previewReady ? READY : previewOn ? PENDING : SKIPPED;
            case RECIPE -> state.recipe();
            case CONTROL -> !state.ready() ? PENDING : !state.formed() ? SKIPPED : state.work() ? READY : STANDBY;
            case DIAG -> !state.ready() ? PENDING : previousStructure.contains("pos") ? READY : SKIPPED;
            case SYSTEM -> state.ready() ? READY : PENDING;
            case NONE -> null;
        };
    }

    private String body(Event event) {
        String key = event.key;
        if (event.probe == Probe.RING && angleReady[event.ring]) return label("angle", String.format(Locale.ROOT, "%.1f", angles[event.ring]));
        if (key.equals("star_radius")) return label(key, Math.round(StarLifterGeometry.STAR_RADIUS * StarLifterGeometry.DEFAULT_RADIUS));
        if (!state.ready()) return label(key);
        if (event.probe == Probe.PARTS && state.formed() && previousStructure.contains("partCount"))
            return label("parts_value", previousStructure.getInt("partCount"));
        if ((event.probe == Probe.ENERGY || event.probe == Probe.CARGO) && state.formed()) {
            int[] parts = previousStructure.getIntArray("parts");
            if (parts.length >= 6) return event.probe == Probe.ENERGY ? label("energy_value", parts[4], parts[5])
                    : label("cargo_value", parts[0] + parts[1], parts[2] + parts[3]);
        }
        if (event.probe == Probe.CONTROL) return label(state.work() ? "permit_on" : "permit_off");
        if (event.probe == Probe.DIAG && previousStructure.contains("pos")) {
            return label("fault", BlockPos.of(previousStructure.getLong("pos")).toShortString());
        }
        if (event.probe == Probe.STRUCTURE && state.formed()) return label("formed");
        if (event.probe == Probe.RECIPE) {
            return switch (state.recipe()) {
                case ACTIVE -> label("recipe_active");
                case STANDBY -> label("recipe_idle");
                case WARNING -> label("recipe_wait");
                default -> label("recipe");
            };
        }
        if (event.probe == Probe.LINK || event.probe == Probe.TARGET) {
            String link = state.link();
            return label(switch (link) {
                case "online", "working" -> "node_ready";
                case "offline", "unavailable" -> "node_offline";
                case "unloaded" -> "node_unloaded";
                case "unbound" -> "node_unbound";
                default -> key;
            });
        }
        return label(key);
    }

    void draw(GuiGraphics g, int ox, int oy, float alpha, long now) {
        float appear = phase(elapsed, 70, 365);
        float retract = phase(elapsed, 3650, 4090);
        int right = LEFT + Math.round(WIDTH * appear * (1 - .82f * retract));
        if (right <= LEFT + 2 || alpha <= .02f) return;
        Font font = Minecraft.getInstance().font;
        scrolling.advance(now, false);
        int end = TOP + HEIGHT;
        g.fill(LEFT, TOP, right, end, alpha(BACK, alpha));
        g.renderOutline(LEFT, TOP, right - LEFT, HEIGHT, alpha(0x425576, alpha));
        g.enableScissor(ox + LEFT + 2, oy + TOP + 2, ox + right - 1, oy + end - 2);
        try {
            draw(g, font, phases[stage(elapsed).ordinal()], LEFT + 5, TOP + 4, CYAN, alpha);
            g.fill(LEFT + 5, TOP + 15, LEFT + WIDTH - 5, TOP + 16, alpha(0x304264, alpha));
            int logY = TOP + 17;
            g.enableScissor(ox + LEFT + 2, oy + logY, ox + right - 1, oy + logY + VIEW);
            try {
                int offset = (int) Math.round(scroll.offset());
                for (int i = offset / ROW; i < Math.min(count, (offset + VIEW + ROW - 1) / ROW); i++) {
                    drawRow(g, font, i, LEFT + 5, logY + ROW * i - offset, right, alpha);
                }
                g.flush();
            } finally {
                g.disableScissor();
            }
            int thumb = scroll.thumbTop(VIEW);
            if (scroll.maxOffset() > 0) g.fill(LEFT + WIDTH - 4, logY + thumb, LEFT + WIDTH - 3,
                    logY + thumb + scroll.thumbHeight(VIEW), alpha(0x6489ac, alpha));
            draw(g, font, header, LEFT + 5, end - 11, DIM, alpha);
            if (!state.ready() && elapsed >= CHECK && elapsed % 620 < 385) {
                draw(g, font, "_", LEFT + 7 + font.width(header), end - 11, DIM, alpha);
            }
            g.flush();
        } finally {
            g.disableScissor();
        }
    }

    private void drawRow(GuiGraphics g, Font font, int index, int x, int y, int right, float alpha) {
        var row = rows[index];
        var event = EVENTS[index];
        int age = elapsed - event.at;
        String module = event.probe == Probe.RING ? RING_LABELS[event.ring] : event.module.label;
        float amount = phase(age, 0, 55) * alpha;
        draw(g, font, module, x, y, event.module.ink(), amount);
        if (row.result != null && right - LEFT >= 122) {
            float result = phase(elapsed - row.resultSince, 0, 100) * alpha;
            draw(g, font, row.result.label, right - 7 - row.resultWidth, y, row.result.ink, result);
        }
        boolean typing = row.type(font, playbackMs(age));
        draw(g, font, row.visible, x + 3, y + 9, INK, amount);
        if (typing) draw(g, font, "_", x + 3 + row.visibleWidth, y + 9, CYAN, amount);
    }

    private static final String[] RING_LABELS = {"[ORBIT-01]", "[ORBIT-02]", "[ORBIT-03]", "[ORBIT-04]"};

    void drawOrbit(GuiGraphics g, int x, int y, int width, float alpha) {
        float visible = phase(elapsed, 905, 1080) * (1 - phase(elapsed, 1680, 1880)) * alpha;
        if (visible <= .02f) return;
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < angles.length; i++) {
            int rowY = y + 9 * i;
            draw(g, font, RING_LABELS[i], x, rowY, ORBIT_INK, visible);
            int track = Math.max(10, width - 72);
            int trackX = x + width - track;
            g.fill(trackX, rowY + 4, trackX + track, rowY + 5, alpha(0x344461, visible));
            if (angleReady[i]) {
                g.fill(trackX, rowY + 4, trackX + track, rowY + 5, alpha(SYNCED.ink, visible));
            } else if (previewOn && elapsed >= ringStart(i)) {
                int cursor = trackX + (elapsed - ringStart(i)) / 14 % (track - 4);
                g.fill(cursor, rowY + 4, cursor + 4, rowY + 5, alpha(CYAN, visible));
            }
        }
    }

    void drawResonance(GuiGraphics g, int x, int y, int width, float alpha) {
        float visible = phase(elapsed, RESONANCE, 2670) * (1 - phase(elapsed, 3010, 3180)) * alpha;
        if (visible <= .02f) return;
        Font font = Minecraft.getInstance().font;
        int ink = resonanceResult == READY ? VIOLET : DIM;
        g.fill(x, y, x + width, y + 26, alpha(BACK, visible));
        draw(g, font, resonanceLabel, x + 4, y + 3, ink, visible);
        drawResult(g, font, resonanceResult, x + width - 4, y + 14, visible * phase(elapsed, 2700, 2850));
        int end = resonanceResult == READY ? x + Math.round(width * phase(elapsed, 2680, 3000)) : x + width;
        g.fill(x, y + 25, end, y + 26, alpha(ink, visible));
    }

    void drawSummary(GuiGraphics g, float alpha, boolean quick, float progress) {
        float reveal = quick ? phase(progress, .08f, .30f) * (1 - phase(progress, .79f, 1)) : phase(elapsed, 3740, 3870);
        alpha *= reveal;
        if (alpha <= .02f) return;
        Font font = Minecraft.getInstance().font;
        int x = quick ? 6 : 159, y = quick ? 49 : 78;
        g.fill(x - 2, y - 3, x + (quick ? 237 : 106), y + (quick ? 32 : 19), alpha(BACK, alpha));
        if (quick) {
            draw(g, font, "[SYNC]", x + 2, y, CYAN, alpha);
            draw(g, font, quickSync, x + 57, y, INK, alpha);
            if (progress >= .28f) drawResult(g, font, state.ready() ? SYNCED : PENDING, x + 232, y, alpha);
            draw(g, font, "[RENDER]", x + 2, y + 9, CYAN, alpha);
            draw(g, font, quickRender, x + 57, y + 9, INK, alpha);
            if (progress >= .53f) drawResult(g, font, previewReady ? READY : previewOn ? PENDING : SKIPPED, x + 232, y + 9, alpha);
            y += 19;
        }
        draw(g, font, online, x + 2, y, CYAN, alpha);
        if (!quick) {
            int color = switch (state.online()) {
                case "incomplete" -> FAILED.ink;
                case "active" -> ACTIVE.ink;
                case "depleted", "suspended" -> WARNING.ink;
                default -> DIM;
            };
            draw(g, font, summary, x + 2, y + 9, color, alpha);
            if ((state.online().equals("awaiting") || !state.ready()) && elapsed % 620 < 385) {
                draw(g, font, "_", x + 3 + font.width(summary), y + 9, color, alpha);
            }
        }
    }

    private static void drawResult(GuiGraphics g, Font font, Result result, int right, int y, float alpha) {
        draw(g, font, result.label, right - font.width(result.label), y, result.ink, alpha);
    }

    boolean scroll(double x, double y, double delta, long now) {
        if (elapsed < 365 || elapsed >= IMAGING || x < LEFT || x >= LEFT + WIDTH || y < TOP || y >= TOP + HEIGHT
                || scroll.maxOffset() == 0) return false;
        follow = false;
        scrolling.scrollBy(-delta * ROW, now, false);
        return true;
    }

    private static String label(String key, Object... args) { return Component.translatable(TEXT + key, args).getString(); }

    private static int readable(int rgb) {
        double luminance = TextReadabilityReference.srgbLuminance(BACK);
        return TextReadabilityReference.readableFill(0xff000000 | rgb, luminance, luminance, 4.5) & 0xffffff;
    }

    private static int alpha(int color, float opacity) { return (Math.round(255 * opacity) << 24) | (color & 0xffffff); }

    private static void draw(GuiGraphics g, Font font, String text, int x, int y, int ink, float opacity) {
        if (opacity > .034f) g.drawString(font, text, x, y, alpha(ink, opacity), false);
    }

    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        int end = 0;
        for (int boundary : graphemeEnds(text)) {
            if (font.width(text.substring(0, boundary)) + font.width("…") > width) break;
            end = boundary;
        }
        return text.substring(0, end) + "…";
    }

    static int[] graphemeEnds(String text) {
        var matches = GRAPHEME.matcher(text);
        int[] boundaries = new int[text.length() + 1];
        int size = 1;
        while (matches.find()) boundaries[size++] = matches.end();
        return Arrays.copyOf(boundaries, size);
    }

    static int visibleGraphemes(int ageMs, int count) {
        return Math.clamp(ageMs / GRAPHEME_INTERVAL_MS, 0, count);
    }
}
