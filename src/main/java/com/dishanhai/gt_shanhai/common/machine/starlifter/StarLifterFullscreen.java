package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterTerminalPreview;
import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterTerminalSky;
import com.dishanhai.sh_better_ui.client.gui.layout.AlignmentGeometry.Rect;
import com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import static com.dishanhai.sh_better_ui.client.api.ShBetterUiApi.motion;
import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminalSounds.*;

/** Modal observation on the existing container screen. The server menu continues to synchronize. */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class StarLifterFullscreen {
    private static final int TEXT_COLOR = 0xffe5ebff, ACCENT = 0xff8de3ec;
    private static final Component TITLE = Component.translatable("block.gt_shanhai.star_lifter");
    private static StarLifterFullscreen active;

    private final Screen owner;
    private final StarLifterTerminalView terminal;
    private final StarLifterCamera camera = new StarLifterCamera(.42f, .20f);
    private final InterruptibleAnimation expansion = motion().animation(Spring.PANEL, 0);
    private final StarLifterCamera.Pose original;
    private Rect presented;
    private Control pressed;
    private int ring = -1;
    private boolean closing;
    private long now;
    private float blend;
    private boolean reduced;

    private enum Control { EXIT, FIT, PAUSE, ZOOM }

    private record Bounds(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    private StarLifterFullscreen(Screen owner, StarLifterTerminalView terminal) {
        this.owner = owner;
        this.terminal = terminal;
        original = terminal.observationPose();
        presented = terminal.observationBounds();
        ring = terminal.observationRing();
        long now = motion().nowMs();
        terminal.holdObservation(now);
        camera.restore(original, now, true);
        expansion.animateTo(1, now);
    }

    static void open(Screen owner, StarLifterTerminalView terminal) {
        if (active != null || owner == null || Minecraft.getInstance().screen != owner) return;
        active = new StarLifterFullscreen(owner, terminal);
    }

    static void closeFor(Screen owner) {
        var mode = forScreen(owner);
        if (mode == null) return;
        active = null;
        mode.camera.close();
        mode.expansion.close();
    }

    private static StarLifterFullscreen forScreen(Screen owner) {
        return active != null && active.owner == owner ? active : null;
    }

    public static boolean isActive(Screen screen) { return forScreen(screen) != null; }

    static boolean isReturning(Screen screen) {
        var mode = forScreen(screen);
        return mode != null && mode.closing;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void prepareFrame(ScreenEvent.Render.Pre event) {
        var mode = forScreen(event.getScreen());
        if (mode == null) return;
        mode.now = motion().nowMs();
        mode.reduced = StarLifterTerminalView.reducedMotion();
        mode.blend = Mth.clamp(mode.expansion.sample(mode.now, mode.reduced), 0, 1);
        if (mode.closing && !mode.expansion.running()) {
            mode.dock(mode.now);
        } else if (!mode.closing) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void renderFullscreen(ScreenEvent.Render.Post event) {
        var mode = forScreen(event.getScreen());
        if (mode != null) mode.draw(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    @SubscribeEvent
    public static void onClose(ScreenEvent.Closing event) {
        closeFor(event.getScreen());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void press(ScreenEvent.MouseButtonPressed.Pre event) {
        var mode = forScreen(event.getScreen());
        if (mode == null) return;
        event.setCanceled(true);
        if (mode.closing) return;
        mode.pressed = event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                ? mode.hit(event.getMouseX(), event.getMouseY()) : null;
        if (mode.pressed == null && (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                || event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
            mode.camera.begin(event.getButton(), motion().nowMs());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void drag(ScreenEvent.MouseDragged.Pre event) {
        var mode = forScreen(event.getScreen());
        if (mode == null) return;
        event.setCanceled(true);
        if (mode.camera.owns(event.getMouseButton())) {
            mode.camera.drag(event.getDragX(), event.getDragY(), (int) mode.presented.width(),
                    (int) mode.presented.height(), motion().nowMs());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void release(ScreenEvent.MouseButtonReleased.Pre event) {
        var mode = forScreen(event.getScreen());
        if (mode == null) return;
        event.setCanceled(true);
        int button = event.getButton();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && mode.pressed != null) {
            var control = mode.pressed;
            mode.pressed = null;
            if (control == mode.hit(event.getMouseX(), event.getMouseY())) mode.activate(control);
        } else if (mode.camera.owns(button) && mode.camera.release(motion().nowMs())) {
            var bounds = mode.presented;
            int ring = StarLifterTerminalPreview.pick((float) ((event.getMouseX() - bounds.x()) / bounds.width()),
                    (float) ((event.getMouseY() - bounds.y()) / bounds.height()));
            if (ring >= 0) FOCUS.play();
            mode.ring = ring == mode.ring ? -1 : ring;
            mode.terminal.restartRingScan();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        var mode = forScreen(event.getScreen());
        if (mode == null) return;
        event.setCanceled(true);
        if (mode.closing) return;
        var bounds = mode.presented;
        mode.camera.wheel(event.getScrollDelta(), (event.getMouseX() - bounds.center().x()) / bounds.width(),
                (event.getMouseY() - bounds.center().y()) / bounds.height(), motion().nowMs());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        var mode = forScreen(event.getScreen());
        if (mode == null || event.getKeyCode() == GLFW.GLFW_KEY_F11) return;
        event.setCanceled(true);
        if (mode.closing) return;
        switch (event.getKeyCode()) {
            case GLFW.GLFW_KEY_ESCAPE -> mode.activate(Control.EXIT);
            case GLFW.GLFW_KEY_R -> mode.activate(Control.FIT);
            case GLFW.GLFW_KEY_SPACE -> mode.activate(Control.PAUSE);
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                ADJUST.play();
                mode.camera.wheel(1, 0, 0, motion().nowMs());
            }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                ADJUST.play();
                mode.camera.wheel(-1, 0, 0, motion().nowMs());
            }
            default -> { }
        }
    }

    private void activate(Control control) {
        switch (control) {
            case EXIT -> {
                if (closing) return;
                CLOSE.play();
                long now = motion().nowMs();
                if (StarLifterTerminalView.reducedMotion()) {
                    dock(now);
                } else {
                    closing = true;
                    pressed = null;
                    camera.cancel(now);
                    camera.restore(original, now, false);
                    expansion.animateTo(0, now);
                }
            }
            case FIT -> {
                FOCUS.play();
                ring = -1;
                camera.reset(motion().nowMs());
            }
            case PAUSE -> {
                toggle(terminal.observationPaused()).play();
                terminal.toggleObservationPause();
            }
            case ZOOM -> {
                ADJUST.play();
                camera.fit(motion().nowMs());
            }
        }
    }

    private void dock(long now) {
        terminal.restoreObservation(original, now);
        closeFor(owner);
    }

    private void draw(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        int width = owner.width, height = owner.height;
        if (width <= 0 || height <= 0) return;
        camera.advance(now, reduced);
        var start = terminal.observationBounds();
        presented = new Rect(Mth.lerp(blend, start.x(), 0), Mth.lerp(blend, start.y(), 0),
                Mth.lerp(blend, start.width(), width), Mth.lerp(blend, start.height(), height));
        float fade = closing ? blend * blend : 1;
        int veil = Math.round((.34f + blend * .44f) * fade * 255);
        if (veil > 0) graphics.fill(0, 0, width, height, veil << 24 | 0x0b1026);
        StarLifterTerminalSky.draw(graphics, (int) presented.x(), (int) presented.y(),
                (int) presented.width(), (int) presented.height(), 1, fade,
                (float) Math.sin(camera.yaw()) * .022f, (float) Math.sin(camera.pitch()) * .018f);
        Control hover = closing ? null : hit(mouseX, mouseY);
        float viewWidth = (float) presented.width(), viewHeight = (float) presented.height();
        float x = !closing && hover == null && !camera.owns(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                ? (float) ((mouseX - presented.x()) / viewWidth) : -1;
        float y = (float) ((mouseY - presented.y()) / viewHeight);
        int observedRing = closing ? terminal.observationRing() : ring;
        StarLifterTerminalPreview.draw(graphics, (float) presented.x(), (float) presented.y(), viewWidth, viewHeight,
                terminal.fullObservation(partial, camera, observedRing, blend, viewWidth / viewHeight,
                        observedRing >= 0 ? 1 : 0),
                null, x, y, true, width, height, width, height);
        if (closing && blend < .55f) return;
        float controls = closing ? StarLifterTerminal.phase(blend, .55f, .92f) : 1;
        var font = Minecraft.getInstance().font;
        graphics.fillGradient(0, 0, width, 37, fadeColor(0xb40b1026, controls), 0x000b1026);
        if (controls >= .04f) {
            graphics.drawString(font, TITLE, 12, 10, fadeColor(TEXT_COLOR, controls), false);
            graphics.drawString(font, terminal.fullObservationStatus(), 12, 22, fadeColor(ACCENT, controls), false);
        }

        boolean paused = terminal.observationPaused();
        button(graphics, Control.EXIT, "fullscreen", text("fullscreen.exit"), hover, false, controls);
        button(graphics, Control.FIT, "fit", null, hover, false, controls);
        button(graphics, Control.PAUSE, paused ? "play" : "pause", null, hover, paused, controls);
        button(graphics, Control.ZOOM, null, text("preview.percent", Math.round(camera.zoom() * 100)), hover, false, controls);
        if (hover != null && hover != Control.EXIT) {
            Component hint = switch (hover) {
                case FIT -> text("fullscreen.fit");
                case PAUSE -> text(paused ? "fullscreen.play" : "fullscreen.pause");
                case ZOOM -> text("fullscreen.zoom");
                case EXIT -> throw new IllegalStateException();
            };
            graphics.renderTooltip(font, hint, mouseX, mouseY);
        }
    }

    private void button(GuiGraphics graphics, Control control, String icon, Component label, Control hover,
                        boolean selected, float opacity) {
        var box = bounds(control);
        int background = control == pressed && control == hover ? 0xe237526a
                : control == hover || selected ? 0xe82a3b56 : 0xdb171f35;
        graphics.fill(box.x, box.y, box.x + box.w, box.y + box.h, fadeColor(background, opacity));
        if (control == hover || selected) graphics.fill(box.x, box.y + box.h - 1, box.x + box.w, box.y + box.h,
                fadeColor(ACCENT, opacity));
        if (icon != null) {
            StarLifterTerminalGlyphs.draw(graphics, icon, box.x + (label == null ? (box.w - 12) / 2 : 4),
                    box.y + (box.h - 12) / 2, 12, fadeColor(ACCENT, opacity));
        }
        if (label != null && opacity >= .04f) {
            var font = Minecraft.getInstance().font;
            graphics.drawString(font, label, box.x + (icon == null ? (box.w - font.width(label)) / 2 : 20),
                    box.y + (box.h - 8) / 2, fadeColor(TEXT_COLOR, opacity), false);
        }
    }

    private static int fadeColor(int color, float opacity) {
        return Math.round((color >>> 24) * opacity) << 24 | color & 0xffffff;
    }

    private Control hit(double x, double y) {
        if (bounds(Control.EXIT).contains(x, y)) return Control.EXIT;
        if (bounds(Control.FIT).contains(x, y)) return Control.FIT;
        if (bounds(Control.PAUSE).contains(x, y)) return Control.PAUSE;
        if (bounds(Control.ZOOM).contains(x, y)) return Control.ZOOM;
        return null;
    }

    private Bounds bounds(Control control) {
        int left = owner.width / 2 - 47, bottom = owner.height - 28;
        return switch (control) {
            case EXIT -> new Bounds(owner.width - 103, 8, 95, 18);
            case FIT -> new Bounds(left, bottom, 22, 18);
            case PAUSE -> new Bounds(left + 24, bottom, 22, 18);
            case ZOOM -> new Bounds(left + 48, bottom, 46, 18);
        };
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(StarLifterTerminal.TEXT + key, args);
    }
}
