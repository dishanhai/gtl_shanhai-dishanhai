package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterTerminalPreview;
import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterVisual;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterMotion;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterEntrance;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StarLifterLight;
import com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.dishanhai.sh_better_ui.client.gui.layout.AlignmentFrame;
import com.dishanhai.sh_better_ui.client.gui.layout.AlignmentGeometry.Rect;
import com.dishanhai.sh_better_ui.client.gui.layout.AlignmentLayout;
import com.dishanhai.sh_better_ui.client.gui.layout.PixelAlignment;
import com.dishanhai.sh_better_ui.kernel.api.motion.SelectionIndicator;
import com.dishanhai.sh_better_ui.kernel.api.layout.SmoothVerticalScroll;
import com.dishanhai.sh_better_ui.kernel.api.layout.VerticalScroll;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.integration.GTRecipeWidget;
import com.gregtechceu.gtceu.integration.jei.recipe.GTRecipeTypeCategory;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.jei.JEIPlugin;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminal.*;
import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminalSync.Command.*;
import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminalSounds.*;
import static com.dishanhai.sh_better_ui.client.api.ShBetterUiApi.motion;
import static com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation.BlendCurve.SMOOTHSTEP;

final class StarLifterTerminalView extends WidgetGroup {
    private static final int BASE = 0x9f131326, WELL = 0xff0b0e1d, PANEL = 0xff1b2037;
    private static final int RULE = 0xff383e61, BRIGHT = 0xfff0efff, MUTED = 0xffadb5d6;
    private static final int GOLD = 0xfff5d798, CYAN = 0xff8de3ec, VIOLET = 0xffc7adff, ALERT = 0xffffad92;
    private static final String[] PAGES = {"overview", "star", "extraction", "link", "diagnostic", "power", "render"};
    private static final Box PREVIEW = new Box(5, 46, 145, 68);
    private static final Box DETAIL = new Box(154, 46, 117, 68);
    private static final int NAV_Y = 27, NAV_HEIGHT = 18;
    private static final int TOOLBAR_HEIGHT = 14;
    private static final int IMMERSIVE_WIDTH = WIDTH - 10, IMMERSIVE_HEIGHT = 104;

    private final StarLifterControllerMachine machine;
    private final StarLifterTerminalSync sync;
    private final Frame frame;
    private final StarLifterBootLog bootLog = new StarLifterBootLog();
    private final RecipeScroll recipeScroll = new RecipeScroll();
    private final List<Action> actions = new ArrayList<>();
    private final StarLifterCamera camera = new StarLifterCamera();
    private final StarLifterCamera.Pose[] cameraHistory = new StarLifterCamera.Pose[PAGES.length];
    private final AlignmentLayout layout = new AlignmentLayout(new Rect(0, 0, WIDTH, HEIGHT));
    private final AlignmentLayout.Node contentLayout = layout.node("content", AlignmentFrame.box(new Rect(0, 0, WIDTH, HEIGHT)));
    private final AlignmentLayout.Node previewLayout = layout.node("preview", contentLayout, AlignmentFrame.box(PREVIEW.rect()));
    private final SelectionIndicator selection = new SelectionIndicator();
    private final PageLayer[] layers = new PageLayer[PAGES.length];
    private final InterruptibleAnimation viewportWidth = motion().animation(Spring.PANEL, PREVIEW.w);
    private final InterruptibleAnimation viewportX = motion().animation(Spring.PANEL, PREVIEW.x);
    private final InterruptibleAnimation viewportPresence = motion().animation(Spring.PANEL, 1);
    private final InterruptibleAnimation immersiveMotion = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation stellarFraming = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation transportFraming = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation ignitionFade = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation batchFeedback = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation progress = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation connection = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation ringEmphasis = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation engineering = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation toolsReveal = motion().animation(Spring.PANEL, 0);
    private final com.dishanhai.sh_better_ui.client.gui.scaled.SpringTable<Box> feedback = motion().springTable(Spring.HOVER);
    private final VerticalScroll diagnosticScroll = new VerticalScroll();
    private final SmoothVerticalScroll diagnosticMotion = new SmoothVerticalScroll(diagnosticScroll);
    private final VerticalScroll recipeOffset = new VerticalScroll();
    private final SmoothVerticalScroll recipeMotion = new SmoothVerticalScroll(recipeOffset);
    private GTRecipe displayedRecipe;
    private Hit tooltip;
    private int page, ring = -1;
    private Box focus, pressed, preview = PREVIEW, zoomSlider;
    private AlignmentLayout.View previewBounds;
    private double lastGuiScale;
    private Rect lastViewport;
    private int pointerX, pointerY, actionShift, contentGrowth;
    private boolean paused, chartSupply, stellarOnly = true, locateFault, outgoing, reduced;
    private boolean isolateRing, independentTime;
    private boolean immersive, inventoryBeforeImmersion, cameraMenu, compactNavigation;
    private float opening = 1, fitSpan = 1.47f;
    private boolean zoomFocused;
    private float zoomBeforeDrag = Float.NaN;
    private float opacity = 1;
    private long now, tooltipSince;
    private Component previousTooltip;
    private double modelTick, modelOffset;
    private double modelGameTick;
    private long modelStart = Long.MIN_VALUE;
    private long observationFrame;
    private boolean modelMoving;
    private GTRecipe progressRecipe;
    private long completedBatches = -1;
    private int progressValue, progressTotal;
    private int scannedRing = -1;
    private long ringScanStart;
    private boolean previewDrawn;
    private boolean observedConnection;
    private long linkActivatedAt = Long.MIN_VALUE;

    StarLifterTerminalView(StarLifterControllerMachine machine, StarLifterTerminalSync sync, Frame frame) {
        super(0, 0, WIDTH, HEIGHT);
        this.machine = machine;
        this.sync = sync;
        this.frame = frame;
        frame.attach(this);
        sync.onFeedback(response -> (response.successful() ? SUCCESS : REJECT).play());
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new PageLayer(motion().animation(Spring.PANEL, i == 0 ? 1 : 0), motion().animation(Spring.PANEL, 0));
        }
        setClientSideWidget();
        recipeScroll.setClientSideWidget();
        recipeScroll.setXScrollBarHeight(3).setYScrollBarWidth(3);
        recipeScroll.setVisible(false).setActive(false);
        addWidget(recipeScroll);
    }

    private record Box(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
        Box translate(int dx, int dy) { return new Box(x + dx, y + dy, w, h); }
        Rect rect() { return new Rect(x, y, w, h); }
        static Box pixels(Rect rect) {
            int x = (int) Math.round(rect.x()), y = (int) Math.round(rect.y());
            return new Box(x, y, (int) Math.round(rect.right()) - x, (int) Math.round(rect.bottom()) - y);
        }
    }

    private record Action(Box box, Box anchor, String icon, Component text, int color, boolean selected, boolean enabled,
                          float opacity, Component feedback, StarLifterTerminalSounds cue, Runnable run) {
        void activate() {
            if (cue != null) cue.play();
            run.run();
        }
    }
    private record Hit(Box box, Component text) {}
    private record PageLayer(InterruptibleAnimation opacity, InterruptibleAnimation x) {}

    private static final class RecipeScroll extends DraggableScrollableWidgetGroup {
        RecipeScroll() { super(7, 72, 167, 42); }
        int contentHeight() { return maxHeight; }
    }

    @Override
    public void drawInBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int ox = getPosition().x, oy = getPosition().y;
        int mx = mouseX - ox, my = mouseY - oy;
        pointerX = mx;
        pointerY = my;
        now = motion().nowMs();
        reduced = reducedMotion();
        opening = frame.entrance(now);
        boolean minimal = StarLifterMotion.current() == StarLifterMotion.MINIMAL;
        try (var profile = StarLifterTerminalPreview.profile("Animation")) {
            try (var cameraProfile = StarLifterTerminalPreview.profile("Camera")) {
                if (!StarLifterFullscreen.isReturning(Minecraft.getInstance().screen)) camera.advance(now, reduced || minimal);
            }
            contentGrowth = frame.inventorySpace();
            layoutPreview();
            diagnosticMotion.advance(now, reduced);
            if (page == 2) {
                int height = 42 + contentGrowth;
                if (recipeScroll.getSize().height != height) recipeScroll.setSize(167, height);
                recipeOffset.resize(recipeScroll.contentHeight(), height - 3);
                if (recipeMotion.advance(now, reduced)) recipeScroll.setScrollYOffset((int) Math.round(recipeOffset.offset()));
            }
            feedback.sweep(now);
        }
        actions.clear();
        tooltip = null;
        zoomSlider = null;
        var status = sync.group("status");
        long batches = sync.group("progress").getLong("completed");
        if (completedBatches >= 0 && batches > completedBatches) {
            batchFeedback.snapTo(1);
            batchFeedback.blendTo(0, now, 0, 400, SMOOTHSTEP);
        }
        if (sync.ready()) completedBatches = batches;
        boolean formed = status.getBoolean("formed");
        boolean depleted = sync.group("reserve").getBoolean("depleted");
        var structure = sync.group("structure");
        boolean structureFault = structure.contains("pos") || structure.getBoolean("unloaded");
        boolean fault = structureFault || depleted || readVisual(status).mode() == StarLifterVisualState.Mode.FAULT;
        String link = sync.group("link").getString("state");
        updateConnection(link);
        g.pose().pushPose();
        try {
            g.pose().translate(ox, oy, 0);
            previewDrawn = false;
            boolean fullBoot = frame.fullEntrance();
            if (opening > .31f) {
                float surface = phase(opening, .31f, .79f);
                int left = Mth.lerpInt(surface, 120, 0);
                int top = Mth.lerpInt(surface, 57, 0);
                fill(g, new Box(left, top, WIDTH - left * 2, HEIGHT - top * 2),
                        Math.round(159 * surface) << 24 | BASE & 0xffffff);
            }
            float title = frame.minimalEntrance() ? 1 : fullBoot ? phase(opening, .045f, .18f) : phase(opening, .19f, .56f);
            if (title > 0) {
                g.enableScissor(ox + 5, oy + 4, ox + 6 + Math.round(148 * title), oy + 29);
                try {
                    line(g, t("title"), 7, 6, 142, GOLD);
                    if (!immersive) line(g, Component.translatable("gtceu.recipe_type.stellar_deconstruction"), 7, 18, 148, MUTED);
                    g.flush();
                } finally {
                    g.disableScissor();
                }
            }
            Component state = terminalStatus(status);
            float skip = fullBoot ? phase(opening, .018f, .085f) * (1 - phase(opening, .89f, 1)) : 0;
            opacity = fullBoot ? phase(opening, .755f, .93f) : phase(opening, .29f, .58f);
            line(g, state, 157, 6 + Math.round((1 - opacity) * 3), Mth.lerpInt(skip, 112, 75),
                    fault ? ALERT : status.getBoolean("work") ? CYAN : MUTED);
            if (!immersive && sync.ready() && (page != 0 || contentGrowth < 26)) {
                opacity = fullBoot ? phase(opening, .80f, .96f) : phase(opening, .37f, .68f);
                line(g, t("link." + link), 157, 18 + Math.round((1 - opacity) * 3), 112, VIOLET);
            }
            opacity = 1;
            float immersion = immersiveMotion.sample(now, reduced);
            float navReveal = frame.minimalEntrance() ? 1 : fullBoot ? phase(opening, .74f, .92f) : phase(opening, .20f, .63f);
            float selectedReveal = navPhase(navReveal, page);
            int navTop = NAV_Y - Math.round(immersion * 44);
            int navCount = 0;
            if (immersion < .98f) {
                for (int i = 0; i < PAGES.length; i++) {
                    int target = i;
                    float entry = navPhase(navReveal, i);
                    if (entry <= .01f) continue;
                    var anchor = new Box(5 + 38 * i, NAV_Y, 36, NAV_HEIGHT);
                    actions.add(new Action(new Box(anchor.x, navTop + navOffset(entry), 36, NAV_HEIGHT),
                            anchor, PAGES[i], t("page." + PAGES[i]),
                            color(i), page == i, !immersive && entry >= .20f, entry,
                            null, page == i ? null : NAVIGATE, () -> select(target)));
                    navCount++;
                }
                selection.target(5 + 38 * page, 36, now, reduced || minimal);
                selection.advance(now);
            }
            if (skip > .01f) {
                var anchor = new Box(246, 4, 24, 13);
                actions.add(new Action(anchor.translate(0, Math.round((1 - skip) * 3)), anchor,
                        "play", t("boot.skip"), MUTED, false, skip >= .20f, skip, null, CLOSE, this::interruptEntrance));
            }
            float presence = viewportPresence.sample(now, reduced || minimal);
            if (presence > 0) viewport(g, partialTick, presence);
            recipeScroll.setVisible(false).setActive(false);
            g.enableScissor(ox + 5, oy + 46, ox + 271, oy + 115 + contentGrowth);
            try {
                boolean snap = reduced || minimal;
                float reveal = frame.minimalEntrance() ? opening : fullBoot ? phase(opening, .79f, .98f) : phase(opening, .36f, .83f);
                float room = 1 - immersion;
                if (room > .01f && reveal > .01f) {
                    for (int i = 0; i < layers.length; i++) {
                        if (i == page) continue;
                        var layer = layers[i];
                        float alpha = layer.opacity.sample(now, snap);
                        if (alpha > .01f) drawPage(g, i, alpha * room * reveal, layer.x.sample(now, snap), true);
                    }
                    var active = layers[page];
                    float panelShift = (page == 2 ? -1 : 1) * (Math.round((1 - reveal) * 35) + Math.round(immersion * 44));
                    float detailOpacity = active.opacity.sample(now, snap) * room * reveal;
                    float detailShift = active.x.sample(now, snap) + panelShift;
                    drawPage(g, page, detailOpacity, detailShift, immersive);
                    if (page == 2 && !immersive && detailOpacity > .75f) {
                        recipeScroll.setSelfPosition(new Position(7 + Math.round(detailShift), 72));
                        recipeScroll.setVisible(true).setActive(true);
                    }
                }
            } finally {
                g.disableScissor();
            }
            if (opening < 1 && (fullBoot || frame.quickEntrance()) && frame.bootElapsed(now) >= 0) {
                drawBoot(g, ox, oy, fullBoot);
            }
            float toolbar = fullBoot ? phase(opening, .81f, .99f) : phase(opening, .37f, .73f);
            if (isPreviewPage(page) && toolbar > .01f) {
                opacity = toolbar;
                cameraToolbar(g);
                opacity = 1;
            }
            float footerReveal = fullBoot ? phase(opening, .845f, 1) : phase(opening, .47f, .92f);
            int footer = 121 + contentGrowth + Math.round((1 - footerReveal) * 10);
            if (immersive) {
                add(new Box(182, 15, 88, 12), "fullscreen", t("preview.exit"), CYAN, true, CLOSE, this::toggleImmersive);
            } else if (footerReveal > .05f) {
                opacity = footerReveal;
                g.fill(5, footer - 4, 271, footer - 3, tint(RULE));
                command(new Box(5, footer, 83, 15), "power", t(status.getBoolean("work") ? "action.stop" : "action.start"),
                        status.getBoolean("work"), WORK, status.getBoolean("work") || formed);
                add(new Box(91, footer, 84, 15), "recipe", t("action.recipes"), MUTED, false, LDLib.isJeiLoaded() && JEIPlugin.jeiRuntime != null, OPEN, StarLifterTerminalView::openRecipes);
                add(new Box(178, footer, 74, 15), "diagnostic",
                        structureFault ? t("diagnostic.faults", 1) : t("action.diagnostics"),
                        structureFault ? ALERT : MUTED, page == 4, page == 4 ? null : NAVIGATE, () -> select(4));
                add(new Box(255, footer, 16, 15), "inventory", t(frame.inventoryExpanded() ? "inventory.collapse" : "inventory.expand"),
                        CYAN, frame.inventoryExpanded(), frame.inventoryExpanded() ? CLOSE : OPEN, frame::toggleInventory);
            }
            for (int i = 0; i < actions.size(); i++) {
                var action = actions.get(i);
                opacity = action.opacity;
                drawAction(g, action, mx, my, i < navCount);
            }
            opacity = selectedReveal;
            if (navCount > 0) fill(g, new Box((int) Math.round(selection.y()),
                    navTop + navOffset(selectedReveal) + NAV_HEIGHT - 1,
                    Math.round((float) selection.height() * selectedReveal), 1), color(page));
        } finally {
            opacity = 1;
            g.pose().popPose();
        }
        super.drawInBackground(g, mouseX, mouseY, partialTick);
    }

    private static float navPhase(float reveal, int index) {
        return phase(reveal, index * .07f, .52f + index * .07f);
    }

    private static int navOffset(float reveal) { return Math.round((1 - reveal) * 5); }

    private void layoutPreview() {
        float x = viewportX.sample(now, reduced), width = viewportWidth.sample(now, reduced);
        float immersion = immersiveMotion.sample(now, reduced);
        float growth = frame.inventoryExtent();
        width += overviewExpansion(growth) * layers[0].opacity.sample(now,
                reduced || StarLifterMotion.current() == StarLifterMotion.MINIMAL);
        float extent = frame.minimalEntrance() ? 1 : phase(opening, .07f, .58f);
        float left = Mth.lerp(immersion, x, 5), top = Mth.lerp(immersion, 46, 29);
        float w = Mth.lerp(immersion, width, IMMERSIVE_WIDTH);
        float h = Mth.lerp(immersion, PREVIEW.h, IMMERSIVE_HEIGHT) + growth - TOOLBAR_HEIGHT;
        Rect viewRect;
        if (frame.fullEntrance() && opening < 1) {
            float establish = phase(opening, .05f, .20f), dock = phase(opening, .755f, .99f);
            viewRect = new Rect(Mth.lerp(dock, Mth.lerp(establish, 120, 175), left),
                    Mth.lerp(dock, Mth.lerp(establish, 60, 29), top),
                    Mth.lerp(dock, Mth.lerp(establish, 36, 96), w),
                    Mth.lerp(dock, Mth.lerp(establish, 27, 61), h));
        } else {
            viewRect = new Rect(Mth.lerp(extent, 120, left), Mth.lerp(extent, 60, top),
                    Mth.lerp(extent, 36, w), Mth.lerp(extent, 27, h));
        }
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        if (viewRect.equals(lastViewport) && guiScale == lastGuiScale) return;
        lastViewport = viewRect;
        lastGuiScale = guiScale;
        var content = new Rect(0, 0, WIDTH, HEIGHT + contentGrowth);
        layout.screen(content);
        contentLayout.update(AlignmentFrame.box(content).withClip(content));
        previewLayout.update(AlignmentFrame.box(viewRect));
        layout.resolve(now, reduced);
        var pixels = new PixelAlignment(guiScale);
        previewBounds = previewLayout.current(pixels);
        preview = Box.pixels(previewBounds.renderBounds());
    }

    private void drawBoot(GuiGraphics g, int ox, int oy, boolean full) {
        int elapsed = frame.bootElapsed(now);
        try (var profile = StarLifterTerminalPreview.profile("BootLog")) {
            if (frame.playingBoot()) bootLog.sample(sync, elapsed, StarLifterVisual.PREVIEW.enabled(), previewDrawn, modelTick, now, full);
            float alpha = frame.bootOpacity(now);
            if (full) {
                int shift = Math.round(17 * phase(opening, .73f, .82f));
                g.pose().pushPose();
                try {
                    g.pose().translate(0, shift, 0);
                    bootLog.draw(g, ox, oy + shift, alpha * (1 - phase(opening, .87f, .97f)), now);
                } finally {
                    g.pose().popPose();
                }
                bootLog.drawOrbit(g, 177, 94, 90, alpha);
                bootLog.drawResonance(g, 175, 94, 96, alpha);
            }
            bootLog.drawSummary(g, alpha * (full ? 1 - phase(opening, .976f, 1) : 1), !full, opening);
        }
    }

    private static float overviewExpansion(float growth) { return growth * .45f; }

    private int overviewExpansion() { return Math.round(overviewExpansion(contentGrowth)); }

    private boolean overPreview(double x, double y) {
        return previewBounds != null && previewBounds.hitTest(x, y);
    }

    private void drawZoomSlider(GuiGraphics g, int left, int right, int top) {
        var slider = new Box(left, top + 3, right - left, 8);
        if (opacity >= .20f) zoomSlider = slider;
        int y = slider.y + 2;
        float ratio = (float) (Math.log(camera.zoom() / StarLifterCamera.MIN_ZOOM)
                / Math.log(StarLifterCamera.MAX_ZOOM / StarLifterCamera.MIN_ZOOM));
        int x = slider.x + Math.round(ratio * (slider.w - 1));
        fill(g, slider, 0xcc14182a);
        g.fill(slider.x, y, slider.x + slider.w, y + 1, tint(RULE));
        g.fill(slider.x, y, x + 1, y + 1, tint(CYAN));
        g.fill(x - 1, slider.y, x + 2, slider.y + slider.h, tint(zoomFocused ? GOLD : CYAN));
        if (zoomSlider != null && slider.contains(pointerX, pointerY)) {
            tooltip = new Hit(slider, t("preview.zoom", Math.round(camera.zoom() * 100)));
        }
    }

    private void dragZoom(double x) {
        float ratio = Mth.clamp((float) (x - zoomSlider.x) / (zoomSlider.w - 1), 0, 1);
        camera.setZoom((float) (StarLifterCamera.MIN_ZOOM
                * Math.pow(StarLifterCamera.MAX_ZOOM / StarLifterCamera.MIN_ZOOM, ratio)), motion().nowMs());
    }

    private void drawPage(GuiGraphics g, int target, float alpha, float shift, boolean old) {
        outgoing = old;
        opacity = alpha;
        actionShift = Math.round(shift);
        g.pose().pushPose();
        try {
            g.pose().translate(shift, 0, 0);
            switch (target) {
                case 0 -> overview(g);
                case 1 -> stellar(g);
                case 2 -> extraction(g);
                case 3 -> resonance(g);
                case 4 -> diagnostic(g);
                case 5 -> controls(g);
                case 6 -> visuals(g);
                default -> throw new IllegalStateException();
            }
        } finally {
            g.pose().popPose();
            actionShift = 0;
            opacity = 1;
            outgoing = false;
        }
    }

    static boolean reducedMotion() {
        return StarLifterMotion.current() == StarLifterMotion.REDUCED
                || !StarLifterVisual.TERMINAL_MOTION.enabled() || UiPreferences.configured().reducedMotion();
    }

    private static Component operationState(CompoundTag status) {
        var visual = readVisual(status);
        var level = Minecraft.getInstance().level;
        if (visual.extracting() && level != null && visual.ignition(level.getGameTime()) < 1) {
            return t("state.startup");
        }
        return visual.mode() == StarLifterVisualState.Mode.HIGH_LOAD ? t("state.high_load")
                : t("state." + status.getString("state"));
    }

    private static StarLifterVisualState readVisual(CompoundTag status) {
        return StarLifterVisualState.decode(status.getByte("visualMode"), status.getLong("visualSince"),
                status.getBoolean("visualResonance"), status.getInt("visualReserve"),
                status.getFloat("visualReserveFrom"), status.getLong("visualReserveSince"));
    }

    private void overview(GuiGraphics g) {
        int left = 154 + overviewExpansion(), x = left + 6, width = 265 - x;
        var detail = new Box(left, 46, 271 - left, 68 + contentGrowth);
        panel(g, detail);
        line(g, t("overview.operation"), x, 51, width, CYAN);
        line(g, recipeName(), x, 63, width, BRIGHT);
        if (sync.recipe() != null) {
            progress(g, new Box(x, 76, width, 3), progressRatio(), CYAN);
            line(g, progressLabel(), x, 83, width, MUTED);
            long energy = RecipeHelper.getInputEUt(sync.recipe());
            if (energy > 0) line(g, t("recipe.energy", energy), x, 95, width, GOLD);
            line(g, parallelLabel(), x, 105, width, MUTED);
        } else if (sync.group("status").getBoolean("work") && sync.group("status").getBoolean("formed")) {
            line(g, t("recipe.waiting_conditions"), x, 79, width, MUTED);
        }
        float completed = batchFeedback.sample(now);
        if (completed > 0) fill(g, new Box(x, 76, width, 2), Math.round(completed * 145) << 24 | (GOLD & 0xffffff));
        if (contentGrowth >= 26 && sync.ready()) {
            var reserve = sync.group("reserve");
            long capacity = sync.group("cost").getLong("capacity");
            line(g, t("capture.reserve"), x, 116, width, GOLD);
            progress(g, new Box(x, 127, width, 3), capacity > 0 ? reserve.getLong("remaining") / (double) capacity : 0, GOLD);
            line(g, t("link." + sync.group("link").getString("state")), x, 138, width, VIOLET);
            if (contentGrowth >= 82) {
                for (int i = 0; i < StarLifterGeometry.RINGS.size(); i++) {
                    var orbit = StarLifterGeometry.RINGS.get(i);
                    long span = Math.round(2 * orbit.outerLimit() * StarLifterGeometry.DEFAULT_RADIUS);
                    line(g, t("overview.ring_size", i + 1, span), x, 152 + 11 * i, width, i == ring ? GOLD : MUTED);
                }
            }
        }
        reasonTooltip(detail);
    }

    private void stellar(GuiGraphics g) {
        panel(g, DETAIL);
        var reserve = sync.group("reserve");
        long available = reserve.getLong("remaining"), capacity = sync.group("cost").getLong("capacity");
        boolean depleted = reserve.getBoolean("depleted");
        line(g, t(chartSupply ? "capture.supply" : "capture.reserve"), 160, 51, 105, GOLD);
        if (chartSupply) {
            var items = sync.group("cost").getList("items", Tag.TAG_COMPOUND);
            for (int i = 0; !outgoing && i < items.size(); i++) {
                var requirement = items.getCompound(i);
                var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(requirement.getString("item")));
                var stack = new ItemStack(item);
                int x = 161 + (i % 5) * 21, y = 62 + (i / 5) * 18;
                g.renderItem(stack, x, y);
                g.renderItemDecorations(font(), stack, x, y, Integer.toString(requirement.getInt("count")));
                if (new Box(x, y, 17, 17).contains(pointerX, pointerY)) {
                    tooltip = new Hit(new Box(x, y, 17, 17), t("capture.cost", stack.getHoverName(), requirement.getInt("count")));
                }
            }
        } else {
            progress(g, new Box(160, 64, 105, 4), capacity > 0 ? available / (double) capacity : 0, GOLD);
            line(g, t("capture.ticks", available, capacity), 160, 73, 105, BRIGHT);
            var level = Minecraft.getInstance().level;
            var visual = readVisual(sync.group("status"));
            float retained = visual.reserveFraction(modelStart == Long.MIN_VALUE && level != null ? level.getGameTime() : modelGameTick);
            long radius = Math.round(StarLifterGeometry.STAR_RADIUS * StarLifterGeometry.DEFAULT_RADIUS
                    * StarLifterGeometry.photosphereScale(retained));
            Component detail = depleted ? t("state.depleted") : t("capture.model_radius", radius);
            line(g, detail, 160, 85, 105, depleted ? ALERT : MUTED);
            if (!outgoing && preview.contains(pointerX, pointerY)) {
                tooltip = new Hit(preview, t("capture.chart_hint",
                        radius, StarLifterGeometry.DEFAULT_RADIUS));
            }
        }
        add(new Box(159, 98, 55, 13), "extraction", t(chartSupply ? "capture.reserve" : "capture.supply"), GOLD,
                chartSupply, chartSupply ? CLOSE : OPEN, () -> chartSupply = !chartSupply);
        if (depleted && chartSupply) {
            command(new Box(216, 98, 49, 13), "power", t("capture.pay"), true, REACTIVATE, true);
        } else if (depleted) {
            add(new Box(216, 98, 49, 13), "power", t("capture.reactivate"), ALERT, false, OPEN, () -> chartSupply = true);
        } else if (sync.response(REACTIVATE) != null) {
            line(g, t(sync.response(REACTIVATE).message()), 160, 85, 105, CYAN);
        }
    }

    private void extraction(GuiGraphics g) {
        panel(g, new Box(5, 46, 171, 68 + contentGrowth));
        line(g, recipeName(), 11, 50, 80, CYAN);
        if (sync.recipe() != null) {
            long energy = RecipeHelper.getInputEUt(sync.recipe());
            if (energy > 0) line(g, t("recipe.energy", energy), 94, 50, 78, GOLD);
            line(g, progressLabel(), 11, 61, 160, MUTED);
            var recipe = sync.group("recipe");
            progress(g, new Box(10, 70, 160, 2), progressRatio(), CYAN);
            if (new Box(11, 50, 160, 10).contains(pointerX, pointerY) && !outgoing) {
                tooltip = new Hit(new Box(11, 50, 160, 10),
                        t("recipe.settings", recipe.getInt("parallel"), recipe.getInt("oc")));
            }
        } else {
            line(g, t("recipe.hint"), 11, 85, 160, MUTED);
        }
        reasonTooltip(new Box(5, 46, 171, 24));
    }

    private void resonance(GuiGraphics g) {
        var link = sync.group("link");
        int left = Math.min(156, preview.x + preview.w + 4);
        panel(g, new Box(left, 46, 271 - left, 68));
        fill(g, new Box(left + 5, 72, 239 - left, 1), RULE);
        float strength = Mth.clamp(connection.sample(now, reduced), 0, 1);
        int node = (left + 228) / 2;
        int coreX = node + 11;
        int extent = Math.round((228 - node) * strength);
        fill(g, new Box(coreX - extent, 72, 2 * extent, 1), 0xff7d67b1);
        long age = linkActivatedAt == Long.MIN_VALUE ? Long.MAX_VALUE : now - linkActivatedAt;
        if (!reduced && age < 560 && connection.target() == 1) {
            int reach = Math.round(extent * phase(age / 560f, 0, 1));
            int color = Math.round(235 * (1 - age / 560f)) << 24 | 0xd9c0ff;
            fill(g, new Box(coreX - reach - 2, 71, 4, 3), color);
            fill(g, new Box(coreX + reach - 2, 71, 4, 3), color);
        }
        for (int x : new int[]{node, 228}) {
            fill(g, new Box(x, 60, 23, 23), WELL);
            int border = blendColor(RULE, x == node ? VIOLET : 0xff8d77ba, strength);
            g.renderOutline(x, 60, 23, 23, tint(border));
        }
        if (!outgoing) {
            g.renderItem(new ItemStack(GTDishanhaiMod.STELLAR_ABYSS_RESONANCE_CORE.get()), node + 3, 63);
            g.renderItem(new ItemStack(DShanhaiMachines.GUIXU_VISION_CORE.getItem()), 231, 63);
        }
        line(g, t("link.guixu"), 189, 49, 78, VIOLET);
        line(g, sync.ready() ? t("link." + link.getString("state")) : t("sync"), left + 5, 88, 260 - left, VIOLET);
        if (link.contains("dimension")) {
            var target = BlockPos.of(link.getLong("pos"));
            line(g, t("link.address", link.getString("dimension"), target.toShortString()), left + 5, 100, 260 - left, MUTED);
        } else {
            line(g, t("link.hint"), left + 5, 100, 260 - left, MUTED);
        }
        if (!outgoing && new Box(node, 60, 23, 23).contains(pointerX, pointerY)) {
            tooltip = new Hit(new Box(node, 60, 23, 23), t("link.hint"));
        }
        if (!outgoing && link.contains("host") && new Box(228, 60, 23, 23).contains(pointerX, pointerY)) {
            tooltip = new Hit(new Box(228, 60, 23, 23), t("link.host",
                    BlockPos.of(link.getLong("host")).toShortString(),
                    t(link.getBoolean("hostFormed") ? "state.formed" : "state.unformed")));
        }
    }

    private void updateConnection(String state) {
        if (!sync.ready()) return;
        boolean connected = "online".equals(state) || "working".equals(state);
        if (!observedConnection) {
            observedConnection = true;
            connection.snapTo(connected ? 1 : 0);
        } else if (connection.target() != (connected ? 1 : 0)) {
            linkActivatedAt = connected ? now : Long.MIN_VALUE;
            connection.animateTo(connected ? 1 : 0, now);
        }
    }

    private void diagnostic(GuiGraphics g) {
        var structure = sync.group("structure");
        panel(g, DETAIL);
        line(g, t("diagnostic.title"), 160, 51, 105, CYAN);
        if (structure.contains("pos")) {
            var position = BlockPos.of(structure.getLong("pos"));
            add(new Box(158, 61, 108, 12), "locate", Component.literal(position.toShortString()), ALERT, false, FOCUS,
                    () -> {
                        Minecraft.getInstance().keyboardHandler.setClipboard(position.toShortString());
                        locateFault = true;
                        focusFault(structure);
                    });
            if (structure.getBoolean("unloaded")) line(g, t("diagnostic.unloaded"), 160, 76, 105, ALERT);
            else {
                if (structure.contains("expected")) {
                    line(g, t("diagnostic.expected", structure.getBoolean("port") ? t("diagnostic.port")
                            : Component.translatable(structure.getString("expected"))), 160, 75, 105, MUTED);
                }
                line(g, t("diagnostic.actual", Component.translatable(structure.getString("actual"))), 160, 86, 105, MUTED);
            }
            add(new Box(159, 98, 106, 13), "locate", t("diagnostic.locate"), ALERT, locateFault, locateFault ? CLOSE : FOCUS, () -> {
                locateFault = !locateFault;
                if (locateFault) focusFault(structure);
                else resetCamera();
            });
        } else if (structure.getBoolean("unloaded")) {
            line(g, t("diagnostic.unloaded"), 160, 63, 105, ALERT);
        } else {
            var counts = structure.getIntArray("parts");
            var lines = new ArrayList<Component>();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) lines.add(t("diagnostic." + StarLifterTerminalSync.ABILITY_KEYS[i], counts[i]));
            }
            if (lines.isEmpty()) lines.add(t(sync.group("status").getBoolean("formed")
                    ? "diagnostic.empty" : "diagnostic.pending"));
            showRows(g, lines, DETAIL, 63, 4);
        }
    }

    private void focusFault(CompoundTag structure) {
        camera.aim(camera.yaw(), camera.pitch(), 1.55f, faultPoint(structure),
                (float) preview.w / preview.h, fitSpan, motion().nowMs());
    }

    private void controls(GuiGraphics g) {
        panel(g, new Box(5, 46, 266, 68));
        var status = sync.group("status");
        command(new Box(10, 52, 256, 14), "overview",
                t("control.toggle", t("control.show"), t(status.getBoolean("show") ? "on" : "off")),
                status.getBoolean("show"), SHOW, true);
        line(g, Component.translatable("gtceu.recipe_type.stellar_deconstruction"), 12, 96, 252, GOLD);
    }

    private void visuals(GuiGraphics g) {
        panel(g, new Box(5, 46, 266, 68));
        var options = StarLifterVisual.values();
        for (int i = 0; i < options.length; i++) {
            var option = options[i];
            boolean enabled = option.enabled();
            var text = t("visual." + option.name().toLowerCase(Locale.ROOT));
            add(new Box(10 + 129 * (i / 3), 48 + 11 * (i % 3), 126, 10), "render",
                    t("control.toggle", text, t(enabled ? "on" : "off")),
                    enabled ? CYAN : MUTED, enabled, toggle(!enabled), option::toggle);
        }
        var quality = StarLifterMotion.current();
        add(new Box(10, 81, 126, 10), "render", t("motion.quality", t("motion." + configKey(quality))),
                CYAN, false, ADJUST, quality::cycle);
        var light = StarLifterLight.current();
        add(new Box(139, 81, 126, 10), "star", t("preview.light", t("preview.light." + configKey(light))),
                CYAN, false, ADJUST, light::cycle);
        var boot = StarLifterEntrance.current();
        add(new Box(10, 92, 126, 10), "play", t("boot.mode", t("boot." + configKey(boot))), CYAN, false, ADJUST, boot::cycle);
        add(new Box(139, 92, 126, 10), "play", t("boot.replay"), CYAN, false, !reduced, null, () -> {
            select(0);
            frame.startEntrance(true);
        });
        add(new Box(10, 103, 126, 10), "render", t("nav.compact"), MUTED, compactNavigation, toggle(!compactNavigation),
                () -> compactNavigation = !compactNavigation);
    }

    private static String configKey(Enum<?> option) { return option.name().toLowerCase(Locale.ROOT); }

    private void viewport(GuiGraphics g, float partial, float presence) {
        float frameAlpha = phase(opening, .06f, .44f) * presence;
        fill(g, preview, Math.round(51 * frameAlpha) << 24 | 0x0b0e1d);
        if (page == 1) {
            // An opaque, muted stroke keeps the tessellated joins from accumulating opacity.
            StarLifterTerminalGlyphs.draw(g, "chart", preview.x + (preview.w - 66) / 2, preview.y + 1,
                    66, 0xff313d57);
        }
        // The fullscreen layer supplies the sole live model until it docks here.
        boolean returning = StarLifterFullscreen.isReturning(Minecraft.getInstance().screen);
        if (!returning && StarLifterVisual.PREVIEW.enabled()) {
            float stellar = stellarFraming.sample(now, reduced);
            fitSpan = StarLifterTerminalPreview.fitSpan((float) preview.w / preview.h, stellar);
            boolean initializing = frame.fullEntrance() && opening < 1;
            float exposure = initializing ? phase(opening, .20f, .30f) : phase(opening, .05f, .72f);
            float scan = initializing ? opening : 1;
            var view = observation(partial, camera, scannedRing, isolateRing && page == 0,
                    stellar, transportFraming.sample(now, reduced), fitSpan, exposure * presence, scan, 0, 0, -1);
            var structure = sync.group("structure");
            Vec3 fault = page == 4 && structure.contains("pos") ? faultPoint(structure) : null;
            var bounds = previewBounds.renderBounds();
            float x = isPreviewPage(page) ? (float) ((pointerX - bounds.x()) / bounds.width()) : -1;
            float y = (float) ((pointerY - bounds.y()) / bounds.height());
            if (exposure > 0) {
                float growth = Math.max(frame.inventoryExtent(), frame.inventoryTarget());
                float reserveWidth = immersive ? IMMERSIVE_WIDTH : PREVIEW.w + overviewExpansion(growth);
                float reserveHeight = Math.max((immersive ? IMMERSIVE_HEIGHT : PREVIEW.h) + frame.inventoryTarget(),
                        Mth.lerp(immersiveMotion.value(), PREVIEW.h, IMMERSIVE_HEIGHT) + frame.inventoryExtent()) - TOOLBAR_HEIGHT;
                StarLifterTerminalPreview.draw(g, (float) bounds.x(), (float) bounds.y(), (float) bounds.width(),
                        (float) bounds.height(), view, fault, x, y, false, reserveWidth, reserveHeight,
                        IMMERSIVE_WIDTH, IMMERSIVE_HEIGHT + frame.inventoryHeight() - TOOLBAR_HEIGHT);
                previewDrawn = true;
            }
        } else if (!returning && isPreviewPage(page)) {
            opacity = phase(opening, .40f, .69f) * presence;
            line(g, t(sync.ready() ? "visual.preview_off" : "sync"), preview.x + 8, preview.y + preview.h / 2 - 4,
                    preview.w - 16, MUTED);
            opacity = 1;
        }
        if (frame.fullEntrance() && opening < .245f) {
            int alpha = Math.round(235 * phase(opening, 0, .057f) * (1 - phase(opening, .16f, .245f)));
            int cx = preview.x + preview.w / 2, cy = preview.y + preview.h / 2;
            for (int i = 0; i < 4; i++) {
                int radius = 4 + i * 3;
                int glow = Math.round(alpha * phase(opening, i * .024f, .13f + i * .024f) * .48f);
                g.renderOutline(cx - radius, cy - radius, radius * 2, radius * 2, glow << 24 | 0x8de3ec);
            }
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(phase(opening, .14f, .58f) * .47f));
            StarLifterTerminalGlyphs.draw(g, "overview", -12, -12, 24, alpha << 24 | 0x9be6f5);
            g.pose().popPose();
            int length = Math.max(0, Math.round((preview.w / 2 - 17) * phase(opening, .08f, .35f)));
            g.fill(cx - 16 - length, cy, cx - 16, cy + 1, alpha << 24 | 0xa39df7);
            g.fill(cx + 16, cy, cx + 16 + length, cy + 1, alpha << 24 | 0xa39df7);
        }
        int toolbarHeight = frame.fullEntrance() ? Math.round(TOOLBAR_HEIGHT * phase(opening, .81f, .99f)) : TOOLBAR_HEIGHT;
        g.renderOutline(preview.x, preview.y, preview.w, preview.h + toolbarHeight,
                Math.round(255 * frameAlpha) << 24 | RULE & 0xffffff);
    }

    private StarLifterTerminalPreview.View observation(float partial, StarLifterCamera observer, int observedRing,
                                                       boolean isolated, float stellar, float transport,
                                                       float span, float exposure, float scan, float fieldOfView,
                                                       float projectionMix, float selectionOverride) {
        var status = sync.group("status");
        var level = Minecraft.getInstance().level;
        long start = sync.ready() ? status.getLong("start") : -1;
        if (StarLifterVisual.PREVIEW_MOTION.enabled() && !paused && !reduced && start >= 0 && level != null) {
            double clock = level.getGameTime() - start + (double) partial;
            modelGameTick = level.getGameTime() + (double) partial;
            if (independentTime) {
                if (observationFrame != 0 && modelMoving) modelTick += Math.min(100, now - observationFrame) * .02;
            } else {
                if (modelStart != Long.MIN_VALUE && (modelStart != start || !modelMoving)) modelOffset = modelTick - clock;
                modelTick = clock + modelOffset;
            }
            modelStart = start;
            modelMoving = true;
        } else {
            modelMoving = false;
        }
        observationFrame = now;
        var visual = readVisual(status);
        double sceneTick = level == null ? modelGameTick : level.getGameTime() + (double) partial;
        if (visual.extracting()) ignitionFade.snapTo(reduced ? 1 : visual.ignition(sceneTick));
        else if (ignitionFade.target() != 0) ignitionFade.blendTo(0, now, 0, 145, SMOOTHSTEP);
        return new StarLifterTerminalPreview.View(observer.yaw(), observer.pitch(), observer.zoom(), observer.panX(), observer.panY(),
                sync.ready() ? status.getFloat("heading") : StarLifterStructure.heading(machine.getFrontFacing()),
                modelTick, observedRing, isolated, stellar, transport, StarLifterMotion.current(),
                visual, ignitionFade.sample(now, reduced), visual.reserveFraction(sceneTick), span,
                StarLifterLight.current().exposure, exposure, scan, fieldOfView,
                selectionOverride >= 0 ? selectionOverride : Mth.clamp(ringEmphasis.sample(now, reduced), 0, 1),
                reduced || paused ? 1 : Mth.clamp((now - ringScanStart) / 820f, 0, 1),
                Mth.clamp(engineering.sample(now, reduced), 0, 1) * (1 - projectionMix), projectionMix);
    }

    StarLifterTerminalPreview.View fullObservation(float partial, StarLifterCamera observer, int observedRing,
                                                    float blend, float aspect, float emphasis) {
        now = motion().nowMs();
        reduced = reducedMotion();
        float fieldOfView = Minecraft.getInstance().options.fov().get() * Mth.DEG_TO_RAD;
        float stellar = stellarFraming.value() * (1 - blend);
        return observation(partial, observer, observedRing, isolateRing && blend < .01f,
                stellar, transportFraming.value() * (1 - blend), StarLifterTerminalPreview.fitSpan(aspect, stellar),
                1, 1, fieldOfView, blend, emphasis);
    }

    Rect observationBounds() {
        var rect = previewBounds.renderBounds();
        return new Rect(rect.x() + getPosition().x, rect.y() + getPosition().y, rect.width(), rect.height());
    }
    StarLifterCamera.Pose observationPose() { return camera.pose(); }
    void holdObservation(long time) { camera.cancel(time); }
    void restoreObservation(StarLifterCamera.Pose pose, long time) {
        camera.cancel(time);
        camera.restore(pose, time, true);
    }
    int observationRing() { return page == 0 || page == 4 ? ring : -1; }

    Component fullObservationStatus() {
        return terminalStatus(sync.group("status"));
    }

    private Component terminalStatus(CompoundTag status) {
        if (!sync.ready()) return t("sync");
        if (!status.getBoolean("formed")) {
            return t(sync.group("structure").getBoolean("unloaded") ? "diagnostic.unloaded" : "state.unformed");
        }
        return sync.group("reserve").getBoolean("depleted") ? t("state.depleted")
                : !status.getBoolean("work") ? t("state.stopped") : operationState(status);
    }

    boolean observationPaused() { return paused; }
    void toggleObservationPause() { paused = !paused; }

    boolean beginDeparture() {
        if (previewBounds == null) return false;
        var bounds = new StarLifterTerminalDeparture.Bounds(frame.getPosition().x, frame.getPosition().y,
                frame.getSize().width, frame.getSize().height);
        var viewport = new StarLifterTerminalDeparture.Bounds(getPosition().x + preview.x, getPosition().y + preview.y,
                preview.w, preview.h);
        return StarLifterTerminalDeparture.begin(bounds, viewport, previewDrawn);
    }

    void closeMotion() {
        camera.close();
        for (var layer : layers) {
            layer.x.close();
            layer.opacity.close();
        }
        for (var track : new InterruptibleAnimation[]{viewportWidth, viewportX, viewportPresence, immersiveMotion, stellarFraming,
                transportFraming, ignitionFade, batchFeedback, progress, connection, ringEmphasis, engineering, toolsReveal}) {
            track.close();
        }
        feedback.clear();
    }

    private Vec3 faultPoint(CompoundTag structure) {
        return Vec3.atCenterOf(BlockPos.of(structure.getLong("pos")))
                .subtract(StarLifterStructure.center(machine.getPos(), machine.getFrontFacing()))
                .scale(1.0 / StarLifterGeometry.DEFAULT_RADIUS);
    }

    private void cameraToolbar(GuiGraphics g) {
        int x = preview.x + 2, y = preview.y + preview.h, right = preview.x + preview.w - 2;
        boolean active = StarLifterVisual.PREVIEW.enabled() && sync.ready();
        fill(g, new Box(x - 1, y, preview.w - 2, TOOLBAR_HEIGHT - 1), 0xe6192037);
        add(new Box(x, y + 1, 12, 12), "fit", t("preview.fit"), CYAN, false, active, FOCUS,
                () -> camera.fit(motion().nowMs()));
        add(new Box(x + 13, y + 1, 12, 12), paused ? "play" : "pause",
                t(paused ? "preview.play" : "preview.pause"), MUTED, paused, active, toggle(paused),
                () -> paused = !paused);
        add(new Box(x + 26, y + 1, 12, 12), "star", t("preview.focus_star"), GOLD, page == 1 && stellarOnly, active, FOCUS,
                this::focusStar);
        add(new Box(x + 39, y + 1, 12, 12), "render", t("preview.tools"), MUTED, cameraMenu, active, cameraMenu ? CLOSE : OPEN,
                () -> cameraMenu = !cameraMenu);
        if (preview.w >= 125) {
            add(new Box(x + 52, y + 1, 12, 12), "orthographic", t(immersive ? "preview.exit" : "preview.immersive"),
                    CYAN, immersive, active, immersive ? CLOSE : OPEN, this::toggleImmersive);
            x += 13;
        }
        if (preview.w >= 137) {
            add(new Box(x + 52, y + 1, 12, 12), "fullscreen", t("fullscreen.open"), CYAN, false, active && frame.canHideInventory(), OPEN, this::openFullscreen);
            x += 13;
        }
        if (!active) return;
        if (right - 29 > x + 55) drawZoomSlider(g, x + 54, right - 29, y);
        var percent = new Box(right - 27, y + 1, 27, 12);
        var value = t("preview.percent", Math.round(camera.zoom() * 100));
        add(percent, "text", value, CYAN, false, ADJUST,
                () -> camera.resetZoom(motion().nowMs()));
        if (toolsReveal.target() != (cameraMenu ? 1 : 0)) toolsReveal.animateTo(cameraMenu ? 1 : 0, now);
        float reveal = Mth.clamp(toolsReveal.sample(now, reduced), 0, 1);
        if (reveal > .02f) cameraMenu(g, preview.x + 2, y, reveal);
    }

    private void cameraMenu(GuiGraphics g, int x, int bottom, float reveal) {
        int width = Math.min(140, preview.w - 4), columns = Math.max(1, width / 14);
        int height = Math.round(29 * reveal);
        int y = bottom - height;
        fill(g, new Box(x, y, width, height), 0xf0182036);
        if (!cameraMenu || height < 28) return;
        cameraOption(x, y, columns, 0, "reset", t("preview.reset"), false, true, ADJUST, this::resetCamera);
        cameraOption(x, y, columns, 1, "ring", ring < 0 ? t("preview.rings") : t("preview.ring", ring + 1),
                ring >= 0, page == 0 || page == 4, FOCUS, () -> setRing((ring + 2) % 5 - 1));
        cameraOption(x, y, columns, 2, "locate", t("preview.focus_ring", ring + 1), false, ring >= 0, FOCUS, this::focusRing);
        cameraOption(x, y, columns, 3, "extraction", t("preview.isolate_ring"), isolateRing, ring >= 0 && page == 0, toggle(!isolateRing),
                () -> isolateRing = !isolateRing);
        cameraOption(x, y, columns, 4, "orthographic", t("preview.top"), false, true, FOCUS, this::topView);
        cameraOption(x, y, columns, 5, "cabin", t("preview.cabin"), false, true, FOCUS, this::focusCabin);
        cameraOption(x, y, columns, 6, "overview", t("preview.orbit"), camera.orbit(), !reduced, toggle(!camera.orbit()),
                () -> camera.toggleOrbit(motion().nowMs()));
        cameraOption(x, y, columns, 7, "clock", t(independentTime ? "preview.clock_local" : "preview.clock_world"),
                independentTime, true, ADJUST, () -> {
                    independentTime = !independentTime;
                    modelMoving = false;
                });
        cameraOption(x, y, columns, 8, "orthographic", t(immersive ? "preview.exit" : "preview.immersive"),
                immersive, true, immersive ? CLOSE : OPEN, this::toggleImmersive);
        cameraOption(x, y, columns, 9, "chart", t(stellarOnly ? "preview.system" : "preview.surface"),
                stellarOnly, page == 1, FOCUS, () -> {
                    stellarOnly = !stellarOnly;
                    setFraming(motion().nowMs());
                });
        cameraOption(x, y, columns, 10, "fullscreen", t("fullscreen.open"), false, frame.canHideInventory(), OPEN, this::openFullscreen);
    }

    private void openFullscreen() {
        if (!frame.checkInventoryIdle()) return;
        interruptEntrance();
        cameraMenu = false;
        StarLifterFullscreen.open(getGui().getModularUIGui(), this);
    }

    private void cameraOption(int x, int y, int columns, int index, String icon, Component name,
                              boolean selected, boolean enabled, StarLifterTerminalSounds cue, Runnable operation) {
        add(new Box(x + (index % columns) * 14 + 1, y + (index / columns) * 14 + 1, 12, 12),
                icon, name, CYAN, selected, enabled, cue, operation);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        sync.tickFeedback();
        GTRecipe current = sync.recipe();
        if (page == 2 && current != displayedRecipe) {
            int position = recipeScroll.getScrollYOffset();
            recipeScroll.clearAllWidgets();
            if (current != null) recipeScroll.addWidget(new GTRecipeWidget(current));
            displayedRecipe = current;
            recipeScroll.setScrollYOffset(position);
            recipeOffset.scrollTo(recipeScroll.getScrollYOffset());
            recipeMotion.synchronize();
        }
    }

    private static void openRecipes() {
        if (LDLib.isJeiLoaded() && JEIPlugin.jeiRuntime != null) {
            JEIPlugin.jeiRuntime.getRecipesGui().showTypes(List.of(GTRecipeTypeCategory.TYPES.apply(DShanhaiRecipeTypes.STELLAR_DECONSTRUCTION)));
        }
    }

    @Override
    public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        pointerX = mouseX - getPosition().x;
        pointerY = mouseY - getPosition().y;
        int hovered = hit(pointerX, pointerY);
        Component text = hovered >= 0 ? actions.get(hovered).text : tooltip != null && tooltip.box.contains(pointerX, pointerY) ? tooltip.text : null;
        if (hovered >= 0 && page == 1 && chartSupply && text.equals(t("capture.pay"))) text = t("capture.confirm");
        if (hovered >= 0 && actions.get(hovered).feedback != null) text = actions.get(hovered).feedback;
        if (!java.util.Objects.equals(text, previousTooltip)) {
            previousTooltip = text;
            tooltipSince = now;
        }
        if (text != null && now - tooltipSince >= 300) {
            graphics.renderTooltip(font(), font().split(text, 220), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX - getPosition().x, y = mouseY - getPosition().y;
        zoomFocused = button == 0 && zoomSlider != null && zoomSlider.contains(x, y);
        if (zoomFocused) {
            interruptEntrance();
            zoomBeforeDrag = camera.zoom();
            focus = null;
            dragZoom(x);
            return true;
        }
        int hit = hit(x, y);
        if (button == 0 && hit >= 0) {
            var action = actions.get(hit);
            if (action.enabled) focus = pressed = action.anchor;
            return true;
        }
        focus = null;
        cameraMenu = false;
        if ((button == 0 || button == 2) && isPreviewPage(page) && overPreview(x, y)
                && StarLifterVisual.PREVIEW.enabled()) {
            interruptEntrance();
            camera.begin(button, motion().nowMs());
            return true;
        }
        if (button == 0 && page == 2 && recipeScroll.isMouseOverElement(mouseX, mouseY) && LDLib.isJeiLoaded()
                && JEIPlugin.jeiRuntime != null
                && recipeScroll.getXEIIngredientOverMouse(mouseX, mouseY) instanceof IClickableIngredient<?> ingredient) {
            OPEN.play();
            var jei = JEIPlugin.jeiRuntime;
            jei.getRecipesGui().show(jei.getJeiHelpers().getFocusFactory()
                    .createFocus(RecipeIngredientRole.OUTPUT, ingredient.getTypedIngredient()));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0 && !Float.isNaN(zoomBeforeDrag) && zoomSlider != null) {
            dragZoom(mouseX - getPosition().x);
            return true;
        }
        if (!camera.owns(button)) return super.mouseDragged(mouseX, mouseY, button, dx, dy);
        camera.drag(dx, dy, preview.w, preview.h, motion().nowMs());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && !Float.isNaN(zoomBeforeDrag)) {
            zoomBeforeDrag = Float.NaN;
            ADJUST.play();
            return true;
        }
        if (camera.owns(button)) {
            double x = mouseX - getPosition().x, y = mouseY - getPosition().y;
            if (camera.release(motion().nowMs()) && overPreview(x, y) && (page == 0 || page == 4)) {
                var bounds = previewBounds.renderBounds();
                int hit = StarLifterTerminalPreview.pick((float) ((x - bounds.x()) / bounds.width()),
                        (float) ((y - bounds.y()) / bounds.height()));
                if (hit >= 0) {
                    FOCUS.play();
                    setRing(hit == ring ? -1 : hit);
                }
            }
            return true;
        }
        if (button == 0 && pressed != null) {
            int selected = hit(mouseX - getPosition().x, mouseY - getPosition().y);
            Box prior = pressed;
            pressed = null;
            if (selected >= 0) {
                var action = actions.get(selected);
                if (action.anchor.equals(prior) && action.enabled) {
                    interruptEntrance();
                    action.activate();
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseWheelMove(double mouseX, double mouseY, double delta) {
        double x = mouseX - getPosition().x, y = mouseY - getPosition().y;
        if (opening < 1 && frame.fullEntrance() && frame.playingBoot() && bootLog.scroll(x, y, delta, motion().nowMs())) return true;
        if (isPreviewPage(page) && overPreview(x, y) && StarLifterVisual.PREVIEW.enabled()) {
            interruptEntrance();
            var bounds = previewBounds.renderBounds();
            camera.wheel(delta, (x - bounds.x()) / bounds.width() - .5,
                    (y - bounds.y()) / bounds.height() - .5, motion().nowMs());
            return true;
        }
        if (page == 4 && DETAIL.contains(x, y)) {
            diagnosticMotion.scrollBy(-delta * 12, motion().nowMs(), reduced);
            return true;
        }
        if (page == 2 && recipeScroll.isMouseOverElement(mouseX, mouseY) && recipeOffset.maxOffset() > 0) {
            if (Math.abs(recipeOffset.offset() - recipeScroll.getScrollYOffset()) > 1) {
                recipeOffset.scrollTo(recipeScroll.getScrollYOffset());
                recipeMotion.synchronize();
            }
            recipeMotion.scrollBy(-delta * 13, motion().nowMs(), reduced);
            if (reduced) recipeScroll.setScrollYOffset((int) Math.round(recipeOffset.offset()));
            return true;
        }
        return super.mouseWheelMove(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && cameraMenu) {
            cameraMenu = false;
            CLOSE.play();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && immersive && Float.isNaN(zoomBeforeDrag)) {
            toggleImmersive();
            CLOSE.play();
            return true;
        }
        if (zoomFocused) {
            if (key == GLFW.GLFW_KEY_ESCAPE && !Float.isNaN(zoomBeforeDrag)) {
                camera.setZoom(zoomBeforeDrag, motion().nowMs());
                zoomBeforeDrag = Float.NaN;
                return true;
            }
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                ADJUST.play();
                camera.setZoom(camera.zoom() + (key == GLFW.GLFW_KEY_LEFT ? -.05f : .05f), motion().nowMs());
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_TAB && !actions.isEmpty()) {
            zoomFocused = false;
            int step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            int start = actionIndex(focus);
            if (start < 0 && step < 0) start = 0;
            for (int i = 1; i <= actions.size(); i++) {
                var action = actions.get(Math.floorMod(start + i * step, actions.size()));
                if (action.enabled) {
                    focus = action.anchor;
                    break;
                }
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            int focused = actionIndex(focus);
            if (focused >= 0 && actions.get(focused).enabled) {
                interruptEntrance();
                actions.get(focused).activate();
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void select(int target) {
        if (target == page) return;
        long time = motion().nowMs();
        int direction = Integer.compare(target, page);
        var previous = layers[page];
        previous.x.blendTo(-direction * 6, time, 0, 180, SMOOTHSTEP);
        previous.opacity.blendTo(0, time, 0, 180, SMOOTHSTEP);
        var incoming = layers[target];
        if (incoming.opacity.sample(time, reduced) <= .01f) incoming.x.snapTo(direction * 6);
        incoming.x.blendTo(0, time, 0, 180, SMOOTHSTEP);
        incoming.opacity.blendTo(1, time, 0, 180, SMOOTHSTEP);
        interruptEntrance();
        camera.cancel(time);
        cameraHistory[page] = camera.pose();
        page = target;
        cameraMenu = false;
        focus = new Box(5 + 38 * target, NAV_Y, 36, NAV_HEIGHT);
        pressed = null;
        actions.clear();
        zoomFocused = false;
        zoomBeforeDrag = Float.NaN;
        viewportWidth.animateTo(target == 2 ? 91 : target == 3 ? 102 : PREVIEW.w, time);
        viewportX.animateTo(target == 2 ? 180 : 5, time);
        float visible = isPreviewPage(target) ? 1 : 0;
        if (viewportPresence.target() != visible) viewportPresence.blendTo(visible, time, 0, 180, SMOOTHSTEP);
        if (cameraHistory[target] != null) camera.restore(cameraHistory[target], time, reduced);
        setFraming(time);
        recipeScroll.setVisible(false).setActive(false);
    }

    private static boolean isPreviewPage(int target) { return target <= 4; }

    private void setFraming(long time) {
        stellarFraming.animateTo(page == 1 && stellarOnly ? 1 : 0, time);
        transportFraming.animateTo(page == 2 ? 1 : 0, time);
        engineering.animateTo(page == 4 ? 1 : 0, time);
        ringEmphasis.animateTo((page == 0 || page == 4) && ring >= 0 ? 1 : 0, time);
    }

    private void resetCamera() {
        camera.reset(motion().nowMs());
    }

    void startBoot(long time, boolean full) {
        bootLog.start();
        if (full) camera.enter(time);
    }

    void cancelBootCamera() { camera.cancel(motion().nowMs()); }

    private void interruptEntrance() {
        if (frame.skipEntrance()) camera.cancel(motion().nowMs());
    }

    private void toggleImmersive() {
        if (!immersive) {
            inventoryBeforeImmersion = frame.inventoryExpanded();
            if (!frame.setInventoryExpanded(false, false)) return;
        } else if (!frame.setInventoryExpanded(inventoryBeforeImmersion, false)) {
            return;
        }
        immersive = !immersive;
        immersiveMotion.animateTo(immersive ? 1 : 0, motion().nowMs());
        recipeScroll.setVisible(!immersive && page == 2).setActive(!immersive && page == 2);
        cameraMenu = false;
    }

    private void focusStar() {
        camera.aim(camera.yaw(), camera.pitch(), 2.05f, Vec3.ZERO, 1, fitSpan, motion().nowMs());
    }

    private void topView() {
        camera.aim(0, 1.55f, 1, Vec3.ZERO, 1, fitSpan, motion().nowMs());
    }

    private void focusCabin() {
        if (page == 1 && stellarOnly) {
            stellarOnly = false;
            setFraming(motion().nowMs());
        }
        isolateRing = false;
        float heading = sync.group("status").getFloat("heading") * Mth.DEG_TO_RAD;
        camera.aim((float) (-Math.PI / 2) - heading, .24f, 3,
                StarLifterStructure.controlAnchor().yRot(heading), (float) preview.w / preview.h,
                StarLifterTerminalPreview.fitSpan((float) preview.w / preview.h, 0), motion().nowMs());
    }

    private void setRing(int target) {
        ring = target;
        if (ring < 0) isolateRing = false;
        else {
            scannedRing = target;
            restartRingScan();
        }
        ringEmphasis.animateTo(ring < 0 ? 0 : 1, motion().nowMs());
    }

    void restartRingScan() { ringScanStart = motion().nowMs(); }

    private void focusRing() {
        var normal = StarLifterGeometry.RINGS.get(ring).orientation(modelTick).transform(new org.joml.Vector3f(0, 1, 0));
        new org.joml.Quaternionf().rotateY(sync.group("status").getFloat("heading") * Mth.DEG_TO_RAD).transform(normal);
        if (normal.z < 0) normal.negate();
        float yaw = (float) Math.atan2(-normal.x, normal.z);
        float pitch = Mth.clamp((float) Math.asin(normal.y), -1.53f, 1.53f);
        camera.aim(yaw, pitch, 1.15f, Vec3.ZERO, 1, fitSpan, motion().nowMs());
    }

    private void showRows(GuiGraphics g, List<Component> rows, Box box, int firstY, int visible) {
        diagnosticScroll.resize(rows.size() * 11, visible * 11);
        int offset = (int) Math.round(diagnosticScroll.offset());
        int first = offset / 11;
        int x = getPosition().x + actionShift, y = getPosition().y;
        g.enableScissor(x + box.x, y + firstY, x + box.x + box.w, y + firstY + visible * 11);
        try {
            for (int i = first; i < Math.min(rows.size(), first + visible + 1); i++) {
                line(g, rows.get(i), box.x + 6, firstY + i * 11 - offset, box.w - 15, MUTED);
            }
            if (diagnosticScroll.maxOffset() > 0) {
                int height = visible * 11;
                fill(g, new Box(box.x + box.w - 5, firstY + diagnosticScroll.thumbTop(height), 2,
                        diagnosticScroll.thumbHeight(height)), MUTED);
            }
        } finally {
            g.disableScissor();
        }
    }

    private void reasonTooltip(Box box) {
        var status = sync.group("status");
        box = box.translate(actionShift, 0);
        if (!outgoing && status.contains("reason") && box.contains(pointerX, pointerY)) {
            var reason = Component.Serializer.fromJson(status.getString("reason"));
            if (reason != null) tooltip = new Hit(box, reason);
        }
    }

    private Component recipeName() {
        GTRecipe active = sync.recipe();
        if (active != null && active.data.contains("channel")) {
            return Component.translatable(active.data.getString("channel"));
        }
        var recipe = sync.group("recipe");
        if (!recipe.contains("id")) return t("recipe.none");
        var id = ResourceLocation.tryParse(recipe.getString("id"));
        return Component.literal(id == null ? recipe.getString("id") : id.getPath().replace('_', ' '));
    }

    private Component parallelLabel() {
        return t("recipe.parallel", Math.max(1, sync.group("recipe").getInt("parallel")));
    }

    private Component progressLabel() {
        var data = sync.group("progress");
        int current = data.getInt("current"), total = data.getInt("total");
        return total <= 0 ? t("recipe.no_progress") : t("recipe.progress", current, total, Math.max(0, total - current));
    }

    private double progressRatio() {
        var data = sync.group("progress");
        int current = data.getInt("current"), total = data.getInt("total");
        var recipe = sync.recipe();
        boolean running = sync.group("status").getString("state").equals("working");
        float fraction = total <= 0 ? 0 : Mth.clamp((float) current / total, 0, 1);
        if (!running || recipe != progressRecipe || total != progressTotal || current < progressValue) {
            progress.snapTo(fraction);
        } else if (current != progressValue) {
            progress.blendTo(fraction, now, 0, 145, SMOOTHSTEP);
        }
        if (current != progressValue || total != progressTotal || recipe != progressRecipe) {
            progressValue = current;
            progressTotal = total;
            progressRecipe = recipe;
        }
        return Math.min(fraction, progress.sample(now, reduced));
    }

    private void add(Box box, String icon, Component text, int color, boolean selected, StarLifterTerminalSounds cue, Runnable run) {
        add(box, icon, text, color, selected, true, cue, run);
    }

    private void add(Box box, String icon, Component text, int color, boolean selected, boolean enabled,
                     StarLifterTerminalSounds cue, Runnable run) {
        if (!outgoing) actions.add(new Action(box.translate(actionShift, 0), anchor(box), icon, text, color,
                selected, enabled && opacity >= .20f, opacity, null, cue, run));
    }

    private void command(Box box, String icon, Component label, boolean selected,
                         StarLifterTerminalSync.Command command, boolean available) {
        if (outgoing) return;
        var response = sync.response(command);
        boolean pending = response != null && response.pending();
        Component status = response == null ? null : t(response.message());
        boolean enabled = available && sync.ready() && !pending;
        int color = response == null ? CYAN : pending ? GOLD : response.successful() ? CYAN : ALERT;
        boolean target = command == REACTIVATE || !selected;
        actions.add(new Action(box.translate(actionShift, 0), anchor(box), icon, status == null ? label : status, color, selected,
                enabled && opacity >= .20f, opacity, status == null ? null : t("action.feedback", label, status), null, () -> {
                    if (sync.submit(command, target)) SUBMIT.play();
                }));
    }

    private Box anchor(Box box) {
        return box.y == 121 + contentGrowth ? box.translate(0, -contentGrowth) : box;
    }

    private int actionIndex(Box box) {
        if (box == null) return -1;
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).anchor.equals(box)) return i;
        }
        return -1;
    }

    private int hit(double x, double y) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            if (actions.get(i).box.contains(x, y)) return i;
        }
        return -1;
    }

    private void drawAction(GuiGraphics g, Action action, int mx, int my, boolean nav) {
        Box box = action.box;
        boolean hover = action.enabled && box.contains(mx, my);
        boolean down = hover && action.anchor.equals(pressed);
        int base = action.selected ? 0xff28344e : PANEL;
        float emphasis = reduced ? (hover ? 1 : 0) : feedback.follow(action.anchor, hover ? 1 : 0, now);
        fill(g, box, down ? 0xff262945 : blendColor(base, 0xff323955, emphasis));
        if (action.selected && !nav) g.fill(box.x, box.y + box.h - 1, box.x + box.w, box.y + box.h, tint(action.color));
        if (action.anchor.equals(focus)) g.renderOutline(box.x, box.y, box.w, box.h, tint(action.color));
        if (nav) {
            int side = compactNavigation ? 12 : 7;
            StarLifterTerminalGlyphs.draw(g, action.icon, box.x + (box.w - side) / 2,
                    box.y + (compactNavigation ? 3 : 1), side, tint(action.color));
            if (!compactNavigation) {
                var label = t("nav." + action.icon);
                line(g, label, box.x + Math.max(2, (box.w - font().width(label)) / 2), box.y + 9, box.w - 4, BRIGHT);
            }
            return;
        }
        if ("text".equals(action.icon)) {
            line(g, action.text, box.x + 2, box.y + 2, box.w - 3, action.color);
            return;
        }
        int icon = box.h - 4;
        boolean iconsOnly = box.w < 40;
        StarLifterTerminalGlyphs.draw(g, action.icon, box.x + (iconsOnly ? (box.w - icon) / 2 : 3),
                box.y + 2, icon, tint(action.enabled || action.feedback != null ? action.color : MUTED));
        if (action.enabled && (down || emphasis > .02f)) drawActionFeedback(g, action, emphasis, down);
        if (!iconsOnly) line(g, action.text, box.x + box.h + 1, box.y + (box.h - 8) / 2, box.w - box.h - 4,
                action.enabled ? BRIGHT : MUTED);
    }

    private void drawActionFeedback(GuiGraphics g, Action action, float emphasis, boolean down) {
        var b = action.box;
        int length = Math.max(1, Math.round((b.w - 4) * (down ? .92f : emphasis * .7f)));
        int center = b.x + b.w / 2;
        int color = tint(Math.round(down ? 220 : 128 * emphasis) << 24 | action.color & 0xffffff);
        switch (action.icon) {
            case "power", "star" -> {
                int left = b.x + 2;
                int extent = Math.max(2, b.h - 4);
                g.renderOutline(left, b.y + 2, extent, extent, color);
                g.fill(left, b.y + 1, left + Math.min(extent, length), b.y + 2, color);
            }
            case "diagnostic", "locate" -> {
                int scan = Mth.lerpInt(emphasis, b.y + 2, b.y + b.h - 2);
                g.fill(b.x + 2, scan, b.x + 2 + length, scan + 1, color);
            }
            case "fullscreen", "orthographic" -> {
                g.fill(b.x, b.y, b.x + Math.min(length, 4), b.y + 1, color);
                g.fill(b.x + b.w - Math.min(length, 4), b.y + b.h - 1, b.x + b.w, b.y + b.h, color);
            }
            case "inventory" -> {
                for (int i = -1; i <= 1; i++) {
                    int x = center + i * Math.max(2, length / 3);
                    g.fill(x, b.y + b.h - 2, x + 1, b.y + b.h - 1, color);
                }
            }
            case "recipe", "play", "pause", "reset", "fit", "render" ->
                    g.fill(b.x + 2, b.y + b.h - 2, b.x + 2 + length, b.y + b.h - 1, color);
            default -> { }
        }
    }

    private static int blendColor(int from, int to, float ratio) {
        return 0xff000000 | Mth.lerpInt(ratio, from >> 16 & 255, to >> 16 & 255) << 16
                | Mth.lerpInt(ratio, from >> 8 & 255, to >> 8 & 255) << 8
                | Mth.lerpInt(ratio, from & 255, to & 255);
    }

    private void line(GuiGraphics g, Component text, int x, int y, int width, int color) {
        var font = font();
        if (opacity < .03f) return;
        color = tint(color);
        if (font.width(text) <= width) {
            g.drawString(font, text, x, y, color, false);
        } else {
            g.drawString(font, font.plainSubstrByWidth(text.getString(), width - font.width("…")) + "…", x, y, color, false);
            var box = new Box(x + actionShift, y, width, 10);
            if (!outgoing && box.contains(pointerX, pointerY)) tooltip = new Hit(box, text);
        }
    }

    private void progress(GuiGraphics g, Box box, double fraction, int color) {
        fill(g, box, RULE);
        int length = (int) Math.round(Mth.clamp(fraction, 0, 1) * box.w);
        g.fill(box.x, box.y, box.x + length, box.y + box.h, tint(color));
        if (color == CYAN && length > 3 && !reduced && !paused
                && sync.group("status").getString("state").equals("working")) {
            int lead = Math.min(length, (int) ((now % 1000) * (length + 9) / 1000));
            int tail = Math.max(0, lead - 8);
            if (tail < lead) g.fill(box.x + tail, box.y, box.x + lead, box.y + box.h, tint(0xffe2fcff));
        }
    }

    private void panel(GuiGraphics g, Box box) {
        fill(g, box, PANEL);
        g.renderOutline(box.x, box.y, box.w, box.h, tint(RULE));
    }

    private void fill(GuiGraphics g, Box box, int color) {
        g.fill(box.x, box.y, box.x + box.w, box.y + box.h, tint(color));
    }

    private int tint(int color) {
        return color & 0xffffff | Math.round((color >>> 24) * opacity) << 24;
    }

    private static int color(int page) {
        return page <= 1 ? GOLD : page == 3 ? VIOLET : CYAN;
    }

    private static net.minecraft.client.gui.Font font() { return Minecraft.getInstance().font; }

    private static Component t(String key, Object... args) { return Component.translatable(TEXT + key, args); }

}
