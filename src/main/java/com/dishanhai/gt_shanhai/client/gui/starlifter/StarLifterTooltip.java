package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterProfile;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterMotion;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.dishanhai.sh_better_ui.client.lyrics.common.style.GlyphColors;
import com.dishanhai.sh_better_ui.client.lyrics.render.GradientPainter;
import com.dishanhai.sh_better_ui.theme.token.ThemeTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import static com.dishanhai.gt_shanhai.client.gui.starlifter.StarLifterDossier.*;

@Mod.EventBusSubscriber(modid = "gt_shanhai", value = Dist.CLIENT)
public final class StarLifterTooltip {
    private static final StarLifterTooltip TOOLTIP = new StarLifterTooltip();
    private static final ResourceLocation ITEM = new ResourceLocation("gt_shanhai", "star_lifter");
    private static final int MARGIN = 5, MAX_WIDTH = 344, MIN_TEXT_ALPHA = 5;
    private static final long ENTER_MS = 155, EXIT_MS = 105, FOLD_MS = 210;
    private static final int LIGHT = 0xF000F0;

    private final StarLifterDossierArt art = new StarLifterDossierArt();
    private final InterruptibleAnimation opacity = track();
    private final InterruptibleAnimation height = track();
    private final InterruptibleAnimation[] weights = {track(), track(), track()};
    private final float[] levels = new float[3];
    private final int[] pages = new int[3];
    private final long[] revealed = new long[3];
    private StarLifterDossier dossier;
    private Screen owner;
    private boolean shown, foreign, left, departing;
    private int state, x, y, mouseX, mouseY, quality, border;
    private int hoverX, hoverY;
    private boolean contrast;
    private long epoch, lastSeen;

    private static InterruptibleAnimation track() {
        return ShBetterUiApi.motion().animation(Spring.PANEL, 0);
    }

    private static void to(InterruptibleAnimation value, float target, long now, long duration, boolean immediate) {
        if (immediate) value.snapTo(target);
        else if (target != value.target()) {
            value.blendTo(target, now, 0, duration, InterruptibleAnimation.BlendCurve.SMOOTHSTEP);
        }
    }

    @SubscribeEvent
    public static void beginFrame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || TOOLTIP.owner == null) return;
        TOOLTIP.shown = false;
        TOOLTIP.foreign = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tooltip(RenderTooltipEvent.Pre event) {
        var tooltip = TOOLTIP;
        var stack = event.getItemStack();
        if (!matches(stack)) {
            if (tooltip.owner != null) tooltip.foreign = true;
            return;
        }
        var screen = Minecraft.getInstance().screen;
        if (screen == null || event.getScreenWidth() < 72 || event.getScreenHeight() < 104 || !tooltip.art.ready()) return;
        event.setCanceled(true);
        try (var profile = StarLifterProfile.begin("StarLifterTooltip", true)) {
            tooltip.show(event, screen);
        }
    }

    private static boolean matches(ItemStack stack) {
        return !stack.isEmpty() && ITEM.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterScreen(ScreenEvent.Render.Post event) {
        var tooltip = TOOLTIP;
        if (tooltip.owner != event.getScreen() || tooltip.shown) return;
        if (tooltip.foreign || tooltip.quality == 0) {
            tooltip.reset();
            return;
        }
        long now = ShBetterUiApi.motion().nowMs();
        tooltip.departing = true;
        to(tooltip.opacity, 0, now, EXIT_MS, false);
        if (tooltip.opacity.sample(now) < .018f) {
            tooltip.reset();
        } else {
            tooltip.draw(event.getGuiGraphics(), now);
        }
    }

    @SubscribeEvent
    public static void screenClosing(ScreenEvent.Closing event) {
        if (TOOLTIP.owner == event.getScreen()) TOOLTIP.reset();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void pageKeys(ScreenEvent.KeyPressed.Pre event) {
        var tooltip = TOOLTIP;
        int key = event.getKeyCode();
        if (key != GLFW.GLFW_KEY_PAGE_UP && key != GLFW.GLFW_KEY_PAGE_DOWN) return;
        int mode = state(Screen.hasShiftDown(), Screen.hasControlDown());
        if (!tooltip.canPage(event.getScreen(), mode)) return;
        tooltip.turnPage(mode, key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void pageWheel(ScreenEvent.MouseScrolled.Pre event) {
        var tooltip = TOOLTIP;
        double delta = event.getScrollDelta();
        if (delta == 0 || !Screen.hasShiftDown() || tooltip.foreign || !tooltip.canPage(event.getScreen(), ENGINEERING)
                || (int) event.getMouseX() != tooltip.hoverX || (int) event.getMouseY() != tooltip.hoverY) return;
        if (tooltip.turnPage(ENGINEERING, delta > 0 ? -1 : 1)) event.setCanceled(true);
    }

    private boolean canPage(Screen screen, int mode) {
        return owner == screen && !departing && ShBetterUiApi.motion().nowMs() - lastSeen <= 170
                && dossier.pages[mode].length > 1;
    }

    private boolean turnPage(int mode, int direction) {
        int previous = pages[mode];
        pages[mode] = Mth.clamp(previous + direction, 0, dossier.pages[mode].length - 1);
        return pages[mode] != previous;
    }

    static int state(boolean shift, boolean ctrl) {
        return shift ? ENGINEERING : ctrl ? PROJECT : OVERVIEW;
    }

    @Mod.EventBusSubscriber(modid = "gt_shanhai", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Resources {
        @SubscribeEvent
        public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(TOOLTIP.art);
            event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> {
                TOOLTIP.dossier = null;
                TOOLTIP.reset();
            });
        }
    }

    private void show(RenderTooltipEvent.Pre event, Screen screen) {
        var mc = Minecraft.getInstance();
        long now = ShBetterUiApi.motion().nowMs();
        int viewWidth = event.getScreenWidth(), viewHeight = event.getScreenHeight();
        int width = Math.min(MAX_WIDTH, viewWidth - 2 * MARGIN);
        int usableHeight = viewHeight - 2 * MARGIN - 3;
        String locale = mc.getLanguageManager().getSelected();
        boolean layoutChanged = dossier == null || dossier.font != event.getFont() || dossier.width != width
                || dossier.viewportHeight != usableHeight || dossier.language != Language.getInstance()
                || !dossier.locale.equals(locale);
        if (layoutChanged) dossier = new StarLifterDossier(event.getFont(), width, usableHeight, locale);

        var scope = ShBetterUiApi.themes().global();
        var preferences = UiPreferences.configured().withTheme(scope);
        quality = preferences.reducedMotion() ? 0 : switch (StarLifterMotion.current()) {
            case FULL -> scope.preferences().lowPerformance() ? 1 : 2;
            case STANDARD -> 1;
            case MINIMAL, REDUCED -> 0;
        };
        contrast = preferences.highContrast();
        border = mix(0x59436E, scope.resolve(ThemeTokens.ACCENT).argb(), contrast ? .58f : .25f);
        int target = state(Screen.hasShiftDown(), Screen.hasControlDown());
        boolean fresh = owner != screen;
        if (fresh || layoutChanged) {
            reset();
            owner = screen;
            epoch = now;
            state = target;
            revealed[target] = now;
            weights[target].snapTo(1);
        }
        if (state != target) {
            // The outgoing page keeps its layout until its opacity track reaches zero.
            if (weights[target].sample(now) <= .001f) revealed[target] = now;
            state = target;
        }

        boolean still = quality == 0;
        int duration = still ? 0 : (int) FOLD_MS;
        for (int i = 0; i < weights.length; i++) {
            to(weights[i], i == state ? 1 : 0, now, duration, still);
        }
        to(height, page(state).height(), now, duration, still || fresh || layoutChanged);
        to(opacity, 1, now, ENTER_MS, still);
        int nextX = event.getX(), nextY = event.getY();
        if (fresh || layoutChanged || Math.abs(nextX - mouseX) + Math.abs(nextY - mouseY) > 1) {
            mouseX = nextX;
            mouseY = nextY;
            left = nextX + 14 + width > viewWidth - MARGIN && nextX - 14 >= viewWidth - nextX - 14;
        }
        x = Mth.clamp(left ? mouseX - 14 - width : mouseX + 14, MARGIN, viewWidth - MARGIN - width);
        y = Mth.clamp(mouseY - 11, MARGIN, Math.max(MARGIN, viewHeight - MARGIN - Math.round(height.sample(now))));
        // The host may offset the tooltip position. Compare scroll input with its actual cursor instead.
        var window = mc.getWindow();
        hoverX = (int) (mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth());
        hoverY = (int) (mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
        departing = false;
        shown = true;
        lastSeen = now;
        draw(event.getGraphics(), now);
    }

    private Page page(int mode) {
        return dossier.pages[mode][pages[mode]];
    }

    private void draw(GuiGraphics gui, long now) {
        float alpha = opacity.sample(now);
        if (alpha < .02f) return;
        for (int i = 0; i < levels.length; i++) levels[i] = weights[i].sample(now);
        int boundsHeight = Math.round(height.sample(now));
        int dy = quality == 0 ? 0 : Math.round((1 - alpha) * 3);
        double time = quality == 0 ? 0 : (now - epoch) * .001;
        gui.enableScissor(x, y + dy, x + dossier.width, y + dy + boundsHeight);
        gui.pose().pushPose();
        gui.pose().translate(x, y + dy, 400);
        try {
            try (var svg = StarLifterProfile.begin("StarLifterTooltip.SVG", true)) {
                art.begin(gui);
                art.frame(dossier.width, boundsHeight, alpha, time, quality, levels[PROJECT], border, contrast);
                for (int i = 0; i < levels.length; i++) {
                    float layer = alpha * Math.max(0, 2 * levels[i] - 1);
                    if (layer > .02f) decorations(i, boundsHeight, now, layer);
                }
                int vertices = art.end();
                StarLifterProfile.draw(svg, vertices == 0 ? 0 : 1, vertices, vertices * 24);
            }

            drawTitle(gui, alpha, time);
            for (int i = 0; i < levels.length; i++) {
                float layer = alpha * Math.max(0, 2 * levels[i] - 1);
                if (layer < .02f) continue;
                Page page = page(i);
                for (Span span : page.spans()) drawSpan(gui, span, layer * staged(i, span.y(), now), boundsHeight - 5);
                for (Span span : page.footer()) drawSpan(gui, span, layer, boundsHeight - 5);
            }
            gui.flush();
        } finally {
            gui.pose().popPose();
            gui.disableScissor();
        }
    }

    private void decorations(int mode, int height, long now, float layer) {
        Page page = page(mode);
        for (Span span : page.spans()) {
            if (span.y() + 9 > height - 5) break;
            float alpha = layer * staged(mode, span.y(), now);
            if (span.icon() != 0) art.sprite(span.icon(), span.x(), span.y(), 9, 9, span.color(), .86f * alpha);
            if (span.heading()) {
                float fraction = quality == 0 ? 1 : Mth.clamp((now - revealed[mode] - (span.y() - HEADER) * .24f) / 125f, 0, 1);
                int length = dossier.width - 2 * PAD;
                art.sprite(RULE, PAD, span.y() + 11, length * fraction, 1,
                        mode == ENGINEERING ? BLUE : GOLD, .53f * alpha);
                if (mode != OVERVIEW && fraction > 0 && fraction < 1) {
                    art.dot(PAD + length * fraction, span.y() + 11, 6, CORE, alpha * .43f);
                }
            }
        }
        if (page.footer().length > 0 && page.footerY() < height - 9) {
            art.sprite(RULE, PAD, page.footerY() - 3, dossier.width - 2 * PAD, 1, VIOLET, layer * .28f);
            for (Span span : page.footer()) {
                int size = span.icon() >= CONTROL_TILE ? KEYCAP_SIZE : 9;
                if (span.icon() != 0 && span.y() + size <= height - 5) {
                    art.sprite(span.icon(), span.x(), span.y(), size, size, BLUE, layer * .92f);
                }
            }
        }
    }

    private float staged(int mode, int y, long now) {
        if (quality == 0 || mode == OVERVIEW) return 1;
        return Mth.clamp((now - revealed[mode] - (y - HEADER) * .28f) / 96f, 0, 1);
    }

    private void drawTitle(GuiGraphics gui, float alpha, double time) {
        var pose = gui.pose();
        pose.pushPose();
        pose.translate(PAD + 4, 13, 1);
        pose.scale(1.45f, 1.45f, 1);
        try {
            double center = (time % 4.7) / 4.7 * 1.9 - .45;
            float breathe = (float) Math.sin(time * Math.PI * 2 / 6.3);
            for (Glyph glyph : dossier.title) {
                if (glyph.end() * 1.45f > dossier.width - 25) break;
                int leftColor = titleColor(glyph.start() / dossier.titleWidth, center, breathe);
                int rightColor = titleColor(glyph.end() / dossier.titleWidth, center, breathe);
                if (!GradientPainter.draw(gui, dossier.font, glyph.text(), glyph.start(), 0,
                        new GlyphColors(argb(leftColor, alpha), argb(rightColor, alpha)), false)) {
                    dossier.font.drawInBatch(glyph.text(), glyph.start(), 0, argb(leftColor, alpha), false,
                            pose.last().pose(), gui.bufferSource(), Font.DisplayMode.NORMAL, 0, LIGHT);
                }
            }
        } finally {
            pose.popPose();
        }
        if (dossier.subtitleWidth <= dossier.width - 2 * PAD) {
            dossier.font.drawInBatch(dossier.subtitle, PAD + 4, 31, argb(GOLD, alpha), false,
                    pose.last().pose(), gui.bufferSource(), Font.DisplayMode.NORMAL, 0, LIGHT);
        }
    }

    private void drawSpan(GuiGraphics gui, Span span, float alpha, int clip) {
        if (span.text() == null || span.y() + dossier.font.lineHeight > clip || alpha * 255 < MIN_TEXT_ALPHA) return;
        int color = contrast && (span.color() == MUTED || span.color() == BODY) ? 0xe0e7f2 : span.color();
        dossier.font.drawInBatch(span.text(), span.x(), span.y(), argb(color, alpha), false,
                gui.pose().last().pose(), gui.bufferSource(), Font.DisplayMode.NORMAL, 0, LIGHT);
    }

    private int titleColor(float u, double center, float breath) {
        int base = mix(GOLD, 0xE6EDF6, u * .66f);
        if (quality == 0) return base;
        float d = Math.max(0, 1 - (float) Math.abs(u - center) / .38f);
        return mix(base, CORE, .05f + breath * .03f + .62f * d * d * (3 - 2 * d));
    }

    private static int argb(int rgb, float alpha) { return Math.round(alpha * 255) << 24 | rgb & 0xffffff; }

    private static int mix(int a, int b, float t) {
        return Math.round(Mth.lerp(t, a >> 16 & 255, b >> 16 & 255)) << 16
                | Math.round(Mth.lerp(t, a >> 8 & 255, b >> 8 & 255)) << 8
                | Math.round(Mth.lerp(t, a & 255, b & 255));
    }

    private void reset() {
        owner = null;
        departing = false;
        opacity.snapTo(0);
        for (int i = 0; i < weights.length; i++) {
            weights[i].snapTo(0);
            pages[i] = 0;
        }
    }
}
