package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTarget;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.Snapshot;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.State;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StellarBootEffect;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StellarHudEffect;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StellarHudMode;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StellarHudMotion;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import static com.dishanhai.gt_shanhai.client.gui.starlifter.StellarHudStyle.*;
import static com.dishanhai.gt_shanhai.client.gui.starlifter.StellarTerminalBoot.window;

/** One visible panel and one motion clock. Jade owns placement, scaling, and third-party rows. */
public final class StellarHudPanel {
    private static final int WIDTH = 224, HEADER_RULE = 40, METER_TOP = 61, METER_HEIGHT = 15;
    private static final int SIGNATURE_HEIGHT = 12, FOOTER_ROW_HEIGHT = 10;
    private static final int TREND_HEIGHT = 18, HISTORY = 20;
    private static final int ALERT_HEIGHT = 11;
    private static final float ALPHA_FLOOR = .035f;
    private static final Component EMPTY = Component.empty();
    private static final int FULL_BRIGHT = 0xf000f0;

    private final StellarEngineeringArt art = new StellarEngineeringArt();
    private final StellarTerminalBoot boot = new StellarTerminalBoot();
    private final InterruptibleAnimation visibility = track(0), fold = track(0), change = track(1);
    private final InterruptibleAnimation reserve = track(0), progress = track(0), tone = track(1);
    private final Digits reserveDigits = new Digits(), progressDigits = new Digits();
    private final float[] history = new float[HISTORY];
    private final long[] historyTime = new long[HISTORY];
    private final Vector3f clip = new Vector3f();
    private StarLifterHudCache.Target target, previousTarget;
    private StellarTerminalBoot.Device device;
    private StellarHudReadout.Header header, previousHeader;
    private StellarHudReadout readout;
    private StellarBootReadout bootReadout;
    private Snapshot snapshot, trended;
    private ClientLevel world;
    private BlockPos controller;
    private long incarnation, lastSample, syncedAt, now;
    private int width, writingRows, historySize, historyIndex, previousTone;
    private int bootHeight, detailHeight, coldMs, fastMs, warmMs, cacheMs, resources;
    private boolean detailed, showBrand, meters, trend, departing, detailHeld, jadeIcon, hint, nativeCoreCoordinates;
    private boolean brandMotion, iconsMotion, scanMotion, glowMotion, digitMotion, bootText, bootBrand,
            bootDevice, bootScan, bootMorph, bootMotto, bootSpatial, bootEnabled;
    private float presence, visualAlpha, expanded, detailOpacity, contentMix, colorMix, previousWeight = 1;
    private double phase;
    private Font font;
    private Language language;
    private Component detailKey = EMPTY;
    private String locale;
    private String narration = "";
    private StellarHudStyle.Palette palette;
    private static int resourceGeneration;

    @Mod.EventBusSubscriber(modid = "gt_shanhai", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Resources {
        @SubscribeEvent
        public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                    manager -> resourceGeneration++);
        }
    }

    public int configure(StarLifterHudCache.Target next, boolean showDetails, boolean icon, boolean animated, Component key,
                         StellarHudMode mode, boolean nativeCoordinates) {
        var client = Minecraft.getInstance();
        int nextWidth = Math.clamp(client.getWindow().getGuiScaledWidth() - 32, 110, WIDTH);
        if (world != next.level() || font != client.font || language != Language.getInstance() || resources != resourceGeneration) {
            reset();
            world = next.level();
            font = client.font;
            language = Language.getInstance();
            locale = client.getLanguageManager().getSelected();
            resources = resourceGeneration;
        }
        boolean sameLayout = width == nextWidth && jadeIcon == icon;
        width = nextWidth;
        jadeIcon = icon;
        if (showBrand != ShanhaiUiClientConfig.stellarHudBrandDetails() || !key.equals(detailKey)
                || nativeCoreCoordinates != nativeCoordinates) readout = null;
        nativeCoreCoordinates = nativeCoordinates;
        detailKey = key;
        detailHeld = showDetails || mode == StellarHudMode.ENGINEERING;
        hint = mode != StellarHudMode.ENGINEERING;
        meters = mode != StellarHudMode.MINIMAL && next.role() == StarLifterHudTarget.CORE;
        showBrand = ShanhaiUiClientConfig.stellarHudBrandDetails();
        var requested = StellarHudMotion.current();
        var limit = ShanhaiUiClientConfig.stellarBootQuality();
        boolean enabled = StellarBootEffect.ENABLED.enabled();
        palette = resolve(enabled && limit.ordinal() >= StellarHudMotion.REDUCED.ordinal()
                && limit.ordinal() > requested.ordinal() ? limit : requested, animated && enabled);
        var quality = limit.ordinal() > palette.motion().ordinal() ? limit : palette.motion();
        bootEnabled = quality != StellarHudMotion.DISABLED;
        bootSpatial = quality.ordinal() < StellarHudMotion.REDUCED.ordinal();
        boolean full = quality.ordinal() < StellarHudMotion.PERFORMANCE.ordinal();
        brandMotion = StellarHudEffect.BRAND.enabled() && palette.spatial();
        iconsMotion = StellarHudEffect.ICONS.enabled();
        scanMotion = StellarHudEffect.SCAN.enabled() && palette.liveIcons();
        glowMotion = StellarHudEffect.GLOW.enabled() && palette.rich();
        digitMotion = StellarHudEffect.DIGITS.enabled() && palette.liveIcons();
        bootText = bootSpatial && StellarBootEffect.TEXT.enabled();
        bootBrand = full && brandMotion && StellarBootEffect.BRAND.enabled();
        bootDevice = full && iconsMotion && StellarBootEffect.DEVICE.enabled();
        bootScan = full && scanMotion && StellarBootEffect.SCAN.enabled();
        bootMorph = bootSpatial && StellarBootEffect.MORPH.enabled();
        bootMotto = bootBrand && quality.ordinal() <= StellarHudMotion.HIGH.ordinal() && StellarBootEffect.MOTTO.enabled();
        coldMs = ShanhaiUiClientConfig.stellarBootCold();
        fastMs = ShanhaiUiClientConfig.stellarBootFast();
        warmMs = ShanhaiUiClientConfig.stellarBootWarm();
        cacheMs = ShanhaiUiClientConfig.stellarBootCache();
        if (!bootEnabled) boot.reset();
        if (!sameLayout) bootReadout = null;
        if (bootText && bootReadout == null) bootReadout = new StellarBootReadout(font, width);
        if (!sameLayout) {
            header = null;
            previousHeader = null;
            previousTarget = null;
        }
        return width;
    }

    public int prepare(StarLifterHudCache.Target next, Component name) {
        now = ShBetterUiApi.motion().nowMs();
        if (header == null || !next.equals(target)) {
            boolean fresh = header == null || boot.expired(now, cacheMs);
            float mix = change.sample(now);
            if (!fresh && next.equals(previousTarget) && previousWeight == 1) {
                var outgoing = header;
                header = previousHeader;
                previousHeader = outgoing;
                previousTarget = target;
                change.snapTo(1 - mix);
            } else {
                if (fresh || mix >= .5f) {
                    previousHeader = fresh ? null : header;
                    previousTarget = fresh ? null : target;
                    previousWeight = fresh ? 0 : mix;
                } else previousWeight *= 1 - mix;
                header = StellarHudReadout.header(font, width, name, next.role(), jadeIcon);
                change.snapTo(previousHeader == null ? 1 : 0);
            }
            target = next;
            device = new StellarTerminalBoot.Device(next.pos().asLong(), next.block().getBlock(), next.entity());
            if (fresh) fold.snapTo(0);
            readout = null;
        }
        Snapshot current = StarLifterHudCache.snapshot(next, now);
        update(current);
        bootHeight = bootText ? Math.round(boot.space(now)) : 0;
        if (bootText && !boot.current(device)) {
            var nextPolicy = boot.policyFor(device, owner(current), current.incarnation(), now, cacheMs);
            if (nextPolicy == StellarTerminalBoot.Policy.COLD) bootHeight = coldRail();
            else if (nextPolicy == StellarTerminalBoot.Policy.FAST) bootHeight = Math.max(19, bootHeight);
        }
        detailHeight = Math.round(Math.max(0, fold.sample(now)));
        return baseHeight() + bootHeight + detailHeight;
    }

    public int bootHeight() { return bootHeight; }
    public int detailHeight() { return detailHeight; }

    private int coldRail() { return bootBrand || bootDevice ? 39 : 16; }

    private static long owner(Snapshot value) { return value.controller() == null ? 0 : value.controller().asLong(); }

    public void visible(StarLifterHudCache.Target next, long time, float jadeAlpha) {
        now = time;
        Snapshot current = StarLifterHudCache.observe(next, now);
        if (bootEnabled) {
            if (boot.policyFor(device, owner(current), current.incarnation(), now, cacheMs) == StellarTerminalBoot.Policy.COLD
                    && !boot.current(device)) visibility.snapTo(bootSpatial ? .76f : .58f);
            boot.observe(device, owner(current), current.incarnation(), now, cacheMs,
                    coldMs, fastMs, warmMs, bootText ? coldRail() : 0, bootText ? 19 : 0, !bootSpatial);
            boot.details(detailHeld, now);
        }
        boolean resuming = departing;
        departing = false;
        if (resuming && bootEnabled && visibility.target() != 1 && visibility.running()) visibility.animateTo(1, now);
        else to(visibility, 1, bootSpatial ? warmMs : StellarTerminalBoot.REDUCED_MS, bootEnabled);
        to(change, 1, StellarTerminalBoot.MODULE_MS, bootSpatial);
        update(current);
        animate(jadeAlpha);
    }

    public void lost(long time, float jadeAlpha) {
        StarLifterHudCache.stopObserving();
        now = time;
        departing = true;
        boot.lose(now);
        to(visibility, 0, bootSpatial ? StellarTerminalBoot.EXIT_MS : StellarTerminalBoot.REDUCED_MS, bootEnabled);
        if (readout == null) return;
        foldTo(0, StellarTerminalBoot.EXIT_MS);
        animate(jadeAlpha);
    }

    public void handoff(long time, float jadeAlpha) {
        StarLifterHudCache.stopObserving();
        now = time;
        if (departing || readout == null) return;
        animate(jadeAlpha);
    }

    public void idle(long time) {
        if (target == null) return;
        lost(time, 1);
        if (!bootEnabled || boot.expired(time, cacheMs)) reset();
    }

    private void animate(float jadeAlpha) {
        presence = Math.clamp(visibility.sample(now), 0, 1);
        visualAlpha = presence * (bootEnabled ? jadeAlpha : 1);
        expanded = Math.max(0, fold.sample(now));
        contentMix = change.sample(now);
        colorMix = tone.sample(now);
        detailOpacity = palette.spatial() ? 1 : expanded / Math.max(1, writingRows * LINE);
        if (contentMix >= .999f) {
            previousHeader = null;
            previousTarget = null;
        }
        if (lastSample != 0 && presence > ALPHA_FLOOR && !departing && palette.liveIcons() && iconsMotion) {
            double speed = snapshot.running() ? 1.0 : snapshot.state() == State.FAULT ? 0 : .34;
            phase = (phase + Math.max(0, now - lastSample) * speed / 2600d) % 1;
        }
        lastSample = now;
    }

    private void update(Snapshot current) {
        boolean sameOwner = current.controller() != null && current.controller().equals(controller)
                && incarnation == current.incarnation();
        boolean valid = current.formed();
        if (current.controller() != null && !sameOwner || !valid && current.state() != State.WAITING_FOR_DATA) {
            historySize = historyIndex = 0;
        }
        float reserveFraction = fraction(current.reserve(), current.capacity());
        if (valid && current != trended) {
            history[historyIndex] = reserveFraction;
            historyTime[historyIndex] = now;
            historyIndex = (historyIndex + 1) % HISTORY;
            historySize = Math.min(historySize + 1, HISTORY);
            trended = current;
        }
        if (readout == null || !current.equals(snapshot) || detailed != detailHeld) {
            boolean interpolate = valid && sameOwner && palette.spatial() && current.state() != State.FAULT;
            if (current.controller() != null) {
                controller = current.controller();
                incarnation = current.incarnation();
            }
            to(reserve, reserveFraction, VALUE, interpolate);
            to(progress, fraction(current.progress(), current.duration()), VALUE, interpolate && current.duration() > 0
                    && snapshot != null && current.recipe().equals(snapshot.recipe()) && current.progress() >= snapshot.progress());
            if (snapshot != null && current.state() != snapshot.state()) {
                previousTone = mixedTone();
                tone.snapTo(interpolate ? 0 : 1);
                to(tone, 1, VALUE, interpolate);
            }
            var replacement = new StellarHudReadout(font, width, header, current, locale, showBrand, detailKey);
            reserveDigits.set(replacement.reserveValue, now, interpolate && digitMotion);
            progressDigits.set(replacement.progressValue, now, interpolate && digitMotion);
            if (current.formed() && !current.equals(snapshot)) syncedAt = now;
            readout = replacement;
            snapshot = current;
            detailed = detailHeld;
            narration = readout.narration(detailed, meters, nativeCoreCoordinates);
        }
        trend = meters && historySize > 1 && snapshot.formed() && palette.rich();
        writingRows = readout.details.length;
        int height = detailed ? writingRows * LINE + (trend ? TREND_HEIGHT : 0) + 4 : 0;
        foldTo(height, detailed ? StellarTerminalBoot.DETAILS_MS : COLLAPSE);
    }

    public String narration() { return narration; }

    public boolean hasTarget(StarLifterHudCache.Target value) { return value.equals(target); }

    public boolean tracking() { return target != null; }

    public boolean closing() { return bootEnabled && visualAlpha > ALPHA_FLOOR; }

    public void background(GuiGraphics gui, int w, int h, float opacity) {
        if (visualAlpha <= ALPHA_FLOOR) return;
        art.bind(gui);
        float p = boot.progress(now);
        float layout = boot.layout(now);
        boolean acquisition = bootSpatial && boot.policy() == StellarTerminalBoot.Policy.COLD;
        float open = bootSpatial ? .35f + .65f * window(layout, 0, .27f) : 1;
        float close = departing && bootSpatial ? .9f + .1f * presence : 1;
        float left = w * (1 - open * close) / 2;
        float right = w - left;
        float bottom = Math.max(3, h - (departing && bootSpatial ? (1 - presence) * 4 : 0));
        float fillAlpha = visualAlpha * opacity;
        int border = color(palette.edge(), visualAlpha);
        art.rect(0, 0, w, bottom, color(BACKGROUND, fillAlpha));
        art.rect(1, 1, w - 1, Math.min(39, bottom), color(SURFACE, fillAlpha * .68f));
        art.rect(left, 0, right, 1, border);
        art.rect(left, bottom - 1, right, bottom, border);
        art.rect(left, 1, left + 1, bottom - 1, color(palette.edge(), visualAlpha * open));
        art.rect(right - 1, 1, right, bottom - 1, color(palette.edge(), visualAlpha * open));
        boolean lock = bootSpatial && p < .29f && (acquisition || boot.policy() == StellarTerminalBoot.Policy.FAST);
        float inset = lock && !departing ? 11 * (1 - window(p, 0, .20f)) : 0;
        float locatorAlpha = lock ? .45f + .55f * window(p, 0, .13f) - .16f * window(p, .16f, .29f) : .84f;
        int locator = color(palette.accent(), visualAlpha * locatorAlpha);
        corner(inset, 0, 1, 1, locator);
        corner(w - inset, 0, -1, 1, locator);
        corner(inset, bottom, 1, -1, locator);
        corner(w - inset, bottom, -1, -1, locator);
        if (bootBrand && acquisition && !departing && p >= .15f && p < .42f) {
            float sweep = left + (right - left) * window(p, .15f, .42f);
            art.rect(sweep, 1, Math.min(right, sweep + 3), Math.min(39, bottom), color(palette.accent(), visualAlpha * .12f));
        }
        gui.flush();
    }

    public void draw(GuiGraphics gui, float rawX, float rawY, int height, int reservedRail, int reservedDetails) {
        if (readout == null || visualAlpha <= ALPHA_FLOOR && (departing || !bootSpatial)) return;
        int x = Math.round(rawX), y = Math.round(rawY);
        int animatedRail = Math.min(reservedRail, Math.round(boot.space(now)));
        int animatedDetails = Math.min(reservedDetails, Math.round(expanded));
        int footer = y + height - footerHeight() - (reservedRail - animatedRail) - (reservedDetails - animatedDetails);
        float p = boot.progress(now);
        float layout = boot.layout(now);
        boolean cold = boot.policy() == StellarTerminalBoot.Policy.COLD;
        var stage = boot.stage(now, snapshot.formed());
        boolean usingBrand = bootBrand && (cold || layout > .09f) && layout < 1 && header.icon();
        float morph = bootMorph ? window(layout, .68f, 1) : window(layout, .65f, .79f);
        float info = departing || !bootSpatial ? visualAlpha : Math.max(.90f, visualAlpha);
        float dataAlpha = visualAlpha * (departing ? presence : 1);
        int signal = !snapshot.formed() || snapshot.state() == State.FAULT || snapshot.state() == State.OFFLINE
                ? stateColor(snapshot.state()) : mixedTone();
        int alert = readout.alert != null && footer - y >= contentHeight() ? ALERT_HEIGHT : 0;
        int meterTop = METER_TOP + alert;
        int bodyHeight = (meters ? meterTop + 2 * METER_HEIGHT + 4 : 62 + alert);
        int railY = y + bodyHeight;
        int rail = Math.max(0, Math.min(animatedRail, footer - railY));
        art.bind(gui);
        geometry(x, y, footer, p, layout, cold, usingBrand, morph, signal);
        if (meters) {
            float open = !bootSpatial || departing || !snapshot.formed() ? 1 : .84f + .16f * window(p, .63f, .97f);
            meter(x, y + meterTop, reserve.sample(now), palette.accent(), snapshot.formed(), dataAlpha * open);
            meter(x, y + meterTop + METER_HEIGHT, progress.sample(now), signal, snapshot.duration() > 0, dataAlpha * open);
        }
        if (trend) trend(x, railY + rail, footer);
        bootLines(x, railY, rail, p, stage);
        int detailsY = railY + rail + (trend ? TREND_HEIGHT : 0);
        float detail = boot.detail(now);
        if (bootSpatial && detailed && !departing && detail < 1 && detailsY + 5 < footer) {
            float length = (width - 12) * window(detail, 0, .73f);
            art.rect(x + 6, detailsY + 1, x + 6 + length, detailsY + 2,
                    color(palette.accent(), visualAlpha * (1 - detail) * .85f));
        }
        gui.flush();
        drawHeader(gui, header, x, y, info);
        float brand = usingBrand ? .42f + .58f * window(morph, .45f, 1) : 1;
        text(gui, readout.brand, x + width - 20 - readout.brand.width(), y + 2, palette.accent(), visualAlpha * brand);
        text(gui, readout.state, x + 16, y + 47, signal, info);
        if (alert != 0) text(gui, readout.alert, x + 6, y + 58, signal, info);
        if (meters) {
            drawMeter(gui, x, y + meterTop, readout.reserve, reserveDigits, dataAlpha);
            drawMeter(gui, x, y + meterTop + METER_HEIGHT, readout.progress, progressDigits, dataAlpha);
        }
        drawBootText(gui, x, railY, rail, p, stage);
        if (bootText && detailed && !departing && detail < .94f && detailsY + 12 < footer) {
            float title = 1 - window(detail, .56f, .94f);
            if (detail < .73f) text(gui, bootReadout.subTerminal, x + 6, detailsY + 3, palette.accent(), visualAlpha * title);
            detailsY += Math.round(12 * title);
        }
        drawDetails(gui, x, detailsY, footer);
        text(gui, readout.signature, x + 6, footer + 2, SIGNATURE, visualAlpha);
        if (showDetailsHint()) {
            text(gui, readout.hint, x + 6, footer + SIGNATURE_HEIGHT, palette.muted(), visualAlpha);
        } else if (!nativeCoreCoordinates) {
            text(gui, readout.ownership, x + 6, footer + SIGNATURE_HEIGHT, palette.muted(), visualAlpha);
        }
        gui.flush();
    }

    private void geometry(int x, int y, int footer, float p, float layout, boolean cold, boolean usingBrand, float morph, int signal) {
        float right = x + width - 6;
        float center = x + width / 2f;
        float rule = bootMorph ? .42f + .58f * window(layout, .15f, 1) : 1;
        int ruleColor = color(palette.edge(), visualAlpha);
        art.rect(center - (center - x - 6) * rule, y + HEADER_RULE, center + (right - center) * rule, y + HEADER_RULE + 1, ruleColor);
        art.rect(x + 6, footer, right, footer + 1, color(palette.edge(), visualAlpha * .62f));
        if (glowMotion && bootEnabled && !departing && p >= 1) {
            art.emblem(x + width - 31, y + 30, 1.35f, 1, color(palette.accent(), visualAlpha * .13f));
        }
        float dock = usingBrand ? morph : 1;
        art.emblem(x + width - 10, y + 6, .53f + (1 - dock) * .11f, 1,
                color(palette.accent(), visualAlpha * (.6f + dock * .3f)));
        drawIcon(previousHeader, x, y, 1, previousWeight * (1 - contentMix) * visualAlpha);
        float unlock = bootDevice && (cold || boot.policy() == StellarTerminalBoot.Policy.FAST)
                ? .24f + .76f * window(p, cold ? .53f : .10f, cold ? .81f : .68f) : 1;
        if (usingBrand) {
            int brand = color(palette.accent(), visualAlpha * window(layout, .04f, .17f));
            if (morph <= 0) art.emblem(x + 19, y + 23, 1, window(layout, .09f, .30f), brand);
            else if (bootMorph) art.morph(header.role().icon, x + 19 - 2 * morph, y + 23 + morph, morph,
                    palette.orbitSegments(), color(palette.accent(), visualAlpha * (1 - window(morph, .53f, 1))));
            unlock *= morph;
        }
        float iconAlpha = visualAlpha * (.56f + .44f * contentMix);
        drawIcon(header, x, y, unlock, usingBrand ? iconAlpha * window(morph, .35f, 1) : iconAlpha);
        float scanIn = cold ? .59f : .26f;
        float scanOut = cold ? .82f : .67f;
        if (bootScan && header.icon() && !departing && p > scanIn && p < scanOut
                && (cold || boot.policy() == StellarTerminalBoot.Policy.FAST)) {
            float scan = window(p, scanIn, scanOut);
            art.scan(x + 17, y + 24, scan, snapshot.formed(),
                    color(palette.accent(), visualAlpha * Math.min(1, Math.min(scan * 5, (1 - scan) * 5))));
        }

        var state = displayState();
        float pulse = glowMotion && !departing && (snapshot.running() || state == State.FAULT)
                ? .25f + .15f * (float) Math.sin(now / (state == State.FAULT ? 235d : 340d)) : 0;
        art.diamond(x + 9, y + 51, 4.8f, color(signal, visualAlpha * pulse));
        int marker = color(signal, visualAlpha);
        switch (state) {
            case FAULT -> {
                art.line(x + 6, y + 48, x + 12, y + 54, marker);
                art.line(x + 12, y + 48, x + 6, y + 54, marker);
            }
            case UNFORMED, DISCONNECTED -> {
                art.rect(x + 6, y + 48, x + 8, y + 54, marker);
                art.rect(x + 10, y + 48, x + 12, y + 54, marker);
            }
            case OFFLINE, STANDBY -> art.rect(x + 6, y + 50, x + 12, y + 52, marker);
            default -> art.diamond(x + 9, y + 51, 3, marker);
        }
        if (palette.rich() && !departing && now - syncedAt < VALUE && snapshot.formed()) {
            art.rect(right - 9, y + 50, right, y + 51, color(palette.accent(), visualAlpha * (1 - (now - syncedAt) / (float) VALUE)));
        }
        if (bootSpatial && !departing && snapshot.state() == State.WAITING_FOR_DATA) {
            float dot = right - 10 + (now % 620) / 62f;
            art.rect(right - 11, y + 55, right, y + 56, color(palette.edge(), visualAlpha));
            art.rect(dot - 1, y + 55, dot + 1, y + 56, color(palette.accent(), visualAlpha * .72f));
        }
    }

    private void drawIcon(StellarHudReadout.Header selected, int x, int y, float reveal, float alpha) {
        if (selected == null || !selected.icon() || alpha < ALPHA_FLOOR) return;
        double iconPhase = iconsMotion && palette.liveIcons() ? phase : 0;
        float strength = glowMotion && snapshot.running() ? .48f + .36f * (float) Math.sin(now / 420d) : 0;
        art.icon(selected.role().icon, x + 17, y + 24, reveal, iconPhase, strength,
                color(palette.accent(), alpha), palette.orbitSegments());
    }

    private void drawHeader(GuiGraphics gui, StellarHudReadout.Header value, int x, int y, float alpha) {
        int title = x + (value.icon() ? 35 : 6);
        text(gui, value.section(), x + 6, y + 2, palette.accent(), alpha);
        text(gui, value.name(), title, y + 15, TEXT, alpha);
        text(gui, value.purpose(), title, y + 28, palette.muted(), alpha);
    }

    private void bootLines(int x, int y, int height, float p, StellarTerminalBoot.Stage stage) {
        if (height < 3 || !bootText || stage == StellarTerminalBoot.Stage.ONLINE) return;
        float alpha = bootAlpha(p);
        if (alpha < ALPHA_FLOOR) return;
        float right = x + width - 6;
        float line = y + height - 2;
        art.rect(x + 6, line, right, line + 1, color(palette.edge(), alpha * .8f));
        float extent = (width - 12) * p;
        if (stage == StellarTerminalBoot.Stage.BRAND_REVEAL) {
            art.rect(x + width / 2f - extent, line, x + width / 2f + extent, line + 1,
                    color(palette.accent(), alpha));
        } else {
            art.rect(x + 6, line, x + 6 + extent, line + 1, color(palette.accent(), alpha));
        }
        if (!departing && stage == StellarTerminalBoot.Stage.TELEMETRY_SYNC && snapshot.formed()) {
            float sweep = x + 6 + (width - 17) * window(p, .58f, .81f);
            art.rect(sweep, line - 1, sweep + 5, line + 2, color(DATA, alpha * .72f));
        }
    }

    private float bootAlpha(float p) {
        return visualAlpha * (1 - window(p, bootMorph ? .78f : .91f, 1)) * (departing ? presence : 1);
    }

    private void drawBootText(GuiGraphics gui, int x, int y, int height, float p, StellarTerminalBoot.Stage stage) {
        if (height < 13 || !bootText) return;
        float alpha = bootAlpha(p);
        if (alpha < ALPHA_FLOOR) return;
        int left = x + 6;
        var labels = bootReadout;
        switch (stage) {
            case TARGET_ACQUISITION -> text(gui, labels.target, left, y, palette.accent(), alpha);
            case BRAND_REVEAL -> {
                if (!bootBrand) {
                    text(gui, labels.target, left, y, palette.accent(), alpha);
                    break;
                }
                boolean bilingual = !labels.english.plain().equals(labels.brand.plain());
                if (bilingual) {
                    text(gui, labels.brand, left, y, TEXT, alpha * window(p, .10f, .19f));
                    if (height >= 24) drawWordmark(gui, left, y + 11, p, alpha);
                } else drawWordmark(gui, left, y, p, alpha);
                int line = bilingual ? 22 : 11;
                if (height >= line + 13) text(gui, bootMotto && p >= .27f ? labels.motto : labels.division,
                        left, y + line, palette.muted(), alpha * window(p, .15f, .23f));
            }
            case TERMINAL_INIT -> {
                text(gui, labels.terminal, left, y, TEXT, alpha);
                if (height >= 24) text(gui, p < .48f ? labels.display : labels.profile,
                        left, y + 11, palette.accent(), alpha * window(p, .35f, .40f));
                if (height >= 35) {
                    var channel = p < .52f ? labels.telemetry : snapshot.state() == State.WAITING_FOR_DATA ? labels.channel
                            : snapshot.formed() ? labels.synchronizedData : readout.state;
                    text(gui, channel, left, y + 22, palette.muted(), alpha * window(p, .42f, .49f));
                }
            }
            case DEVICE_IDENTIFICATION -> text(gui, target.role() == StarLifterHudTarget.CORE ? labels.device : labels.module,
                    left, y, palette.accent(), alpha);
            case STRUCTURE_SCAN -> text(gui, snapshot.formed() ? labels.structure : labels.scan, left, y, palette.accent(), alpha);
            case TELEMETRY_CONNECT, TELEMETRY_SYNC, INTERFACE_MORPH -> {
                var status = snapshot.formed() ? target.role() == StarLifterHudTarget.CORE ? labels.synchronizedData : labels.linked
                        : snapshot.state() == State.WAITING_FOR_DATA ? labels.waiting : readout.state;
                text(gui, status, left, y, snapshot.formed() ? palette.accent() : stateColor(snapshot.state()), alpha);
            }
            default -> { }
        }
    }

    private void drawWordmark(GuiGraphics gui, int x, int y, float p, float alpha) {
        var label = bootReadout.english;
        var letters = bootReadout.trackedWordmark;
        float opacity = alpha * window(p, .15f, .23f);
        if (letters.length == 0 || p >= .29f) {
            text(gui, label, x, y, palette.accent(), opacity);
            return;
        }
        float gap = Math.min(1.4f, (width - 12 - label.width()) / (float) Math.max(1, letters.length - 1))
                * (1 - window(p, .15f, .29f));
        float cursor = x;
        for (var letter : letters) {
            text(gui, letter, Math.round(cursor), y, palette.accent(), opacity);
            cursor += letter.width() + gap;
        }
    }

    private void drawDetails(GuiGraphics gui, int x, int first, int footer) {
        for (int i = 0; i < readout.details.length; i++) {
            int rowY = first + i * LINE;
            if (rowY + 9 > footer - 1) break;
            var row = readout.details[i];
            float opening = Mth.clamp((expanded - i * LINE) / 14f, 0, 1);
            float alpha = visualAlpha * (palette.spatial() ? opening : detailOpacity);
            text(gui, row.label(), x + 6, rowY, palette.muted(), alpha);
            if (row.value() != null) text(gui, row.value(), x + width - 6 - row.value().width(), rowY, DATA, alpha);
        }
    }

    private void trend(int x, int first, int footer) {
        if (first + TREND_HEIGHT > footer) return;
        float left = x + 6, top = first, right = x + width - 6;
        int index = (historyIndex + HISTORY - historySize) % HISTORY;
        long end = historyTime[(historyIndex + HISTORY - 1) % HISTORY];
        float span = Math.max(1, end - historyTime[index]);
        art.rect(left, top, right, top + 13, color(SURFACE, visualAlpha * .5f));
        for (int i = 1; i < historySize; i++) {
            int a = (index + i - 1) % HISTORY, b = (index + i) % HISTORY;
            art.line(right - (end - historyTime[a]) / span * (right - left), top + 12 - history[a] * 11,
                    right - (end - historyTime[b]) / span * (right - left), top + 12 - history[b] * 11,
                    color(palette.accent(), visualAlpha * .72f));
        }
    }

    private void meter(int x, int y, float value, int accent, boolean valid, float alpha) {
        int left = x + 6, right = x + width - 6;
        art.rect(left, y + 10, right, y + 12, color(EDGE, alpha * .73f));
        if (valid) art.rect(left, y + 10, left + (right - left) * value, y + 12, color(accent, alpha * .93f));
    }

    private void drawMeter(GuiGraphics gui, int x, int y, StellarHudReadout.Label label, Digits digits, float alpha) {
        text(gui, label, x + 6, y, palette.muted(), alpha);
        digits.draw(gui, x + width - 6, y, alpha);
    }

    private void corner(float x, float y, int dx, int dy, int argb) {
        art.rect(Math.min(x, x + 8 * dx), y - (dy < 0 ? 1 : 0),
                Math.max(x, x + 8 * dx), y + (dy > 0 ? 1 : 0), argb);
        art.rect(x - (dx < 0 ? 1 : 0), Math.min(y, y + 5 * dy),
                x + (dx > 0 ? 1 : 0), Math.max(y, y + 5 * dy), argb);
    }

    private void text(GuiGraphics gui, StellarHudReadout.Label label, int x, int y, int ink, float alpha) {
        if (alpha >= ALPHA_FLOOR) {
            font.drawInBatch(label.glyphs(), x, y, color(ink, alpha), false, gui.pose().last().pose(),
                    gui.bufferSource(), Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }
    }

    private int contentHeight() {
        return (meters ? METER_TOP + 2 * METER_HEIGHT + 4 : 62) + (readout.alert == null ? 0 : ALERT_HEIGHT);
    }
    private boolean showDetailsHint() { return hint && !detailed && target.role() == StarLifterHudTarget.CORE; }
    private int footerHeight() { return SIGNATURE_HEIGHT + (showDetailsHint() || !nativeCoreCoordinates ? FOOTER_ROW_HEIGHT : 0); }
    private int baseHeight() { return contentHeight() + footerHeight(); }

    private int mixedTone() {
        int selected = stateColor(displayState());
        return colorMix >= .999f ? selected : ShBetterUiApi.motion().lerpColor(previousTone, selected, colorMix);
    }

    private State displayState() {
        var state = snapshot.state();
        return target.role() != StarLifterHudTarget.CORE && snapshot.formed() && state != State.FAULT && state != State.OFFLINE
                ? State.PROCESSING : state;
    }

    private static float fraction(long current, long total) {
        return total > 0 ? Mth.clamp((float) ((double) current / total), 0, 1) : 0;
    }

    private static InterruptibleAnimation track(float start) {
        return ShBetterUiApi.motion().animation(Spring.PANEL, start);
    }

    private void to(InterruptibleAnimation track, float value, long duration, boolean animated) {
        if (!animated) track.snapTo(value);
        else if (value != track.target()) {
            track.blendTo(value, now, 0, duration, InterruptibleAnimation.BlendCurve.SMOOTHSTEP);
        }
    }

    private void foldTo(int height, long duration) {
        if (bootSpatial && height == fold.target()) return;
        float current = fold.sample(now);
        if (bootSpatial && fold.running() && (height - current) * fold.velocity() < 0) fold.animateTo(height, now);
        else to(fold, height, duration, bootSpatial);
    }

    public void reset() {
        target = null;
        previousTarget = null;
        device = null;
        world = null;
        header = previousHeader = null;
        readout = null;
        bootReadout = null;
        boot.reset();
        snapshot = trended = null;
        controller = null;
        historySize = historyIndex = 0;
        lastSample = 0;
        departing = false;
        phase = 0;
        visibility.snapTo(0);
        fold.snapTo(0);
        change.snapTo(1);
        tone.snapTo(1);
        colorMix = 1;
        previousWeight = 1;
        narration = "";
        StarLifterHudCache.clear();
    }

    private final class Digits {
        private static final String GLYPHS = "0123456789.%<—";
        private static final String[] TEXT = GLYPHS.chars().mapToObj(Character::toString).toArray(String[]::new);
        private String previous = "—", value = "—";
        private long start = -VALUE;

        void set(String next, long time, boolean animate) {
            if (value.equals(next)) {
                if (!animate) start = time - VALUE;
                return;
            }
            previous = time - start <= VALUE / 2 ? previous : value;
            value = next;
            start = animate ? time : time - VALUE;
        }

        void draw(GuiGraphics gui, int right, int y, float alpha) {
            if (alpha < ALPHA_FLOOR) return;
            float fraction = ease(now - start, 0, VALUE);
            boolean rolling = digitMotion && fraction < .999f;
            if (rolling) {
                var matrix = gui.pose().last().pose();
                matrix.transformPosition(right - 43, y - 1, 0, clip);
                int left = Mth.floor(clip.x), top = Mth.floor(clip.y);
                matrix.transformPosition(right + 1, y + 9, 0, clip);
                gui.enableScissor(left, top, Mth.ceil(clip.x), Mth.ceil(clip.y));
            }
            int columns = rolling ? Math.max(value.length(), previous.length()) : value.length();
            int cell = 7;
            for (int i = 0; i < columns; i++) {
                int x = right - (i + 1) * cell;
                char next = fromRight(value, i), old = fromRight(previous, i);
                if (rolling && next != old) {
                    digit(gui, old, x, y - Math.round(8 * fraction), alpha * (1 - fraction));
                    digit(gui, next, x, y + Math.round(8 * (1 - fraction)), alpha * fraction);
                } else digit(gui, next, x, y, alpha);
            }
            if (rolling) gui.disableScissor();
        }

        private void digit(GuiGraphics gui, char value, int x, int y, float alpha) {
            if (value == ' ' || alpha < ALPHA_FLOOR) return;
            font.drawInBatch(TEXT[GLYPHS.indexOf(value)], x, y, color(DATA, alpha), false, gui.pose().last().pose(),
                    gui.bufferSource(), Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        }

        private char fromRight(String text, int index) {
            return index < text.length() ? text.charAt(text.length() - 1 - index) : ' ';
        }
    }
}
