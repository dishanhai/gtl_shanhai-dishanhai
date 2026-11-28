package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation;
import com.dishanhai.sh_better_ui.client.gui.scaled.Spring;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.dishanhai.sh_better_ui.client.api.ShBetterUiApi.motion;
import static com.dishanhai.sh_better_ui.client.gui.scaled.InterruptibleAnimation.BlendCurve.SMOOTHSTEP;

/** The viewport owns the gesture. These tracks only own the presented camera pose. */
final class StarLifterCamera {
    private static final float PITCH_LIMIT = 1.55f;
    static final float MIN_ZOOM = .62f, MAX_ZOOM = 4f;
    private static final double FRICTION = 8;
    private final float defaultYaw, defaultPitch;
    private final InterruptibleAnimation yaw, pitch;
    private final InterruptibleAnimation zoom = motion().animation(Spring.PANEL, 1);
    private final InterruptibleAnimation panX = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation panY = motion().animation(Spring.PANEL, 0);
    private final InterruptibleAnimation[] tracks;
    private long frame, input;
    private double speedX, speedY;
    private long dragTime;
    private boolean orbit;
    private int button = -1;
    private double distance;
    private final Quaternionf rotation = new Quaternionf();

    record Pose(float yaw, float pitch, float zoom, float panX, float panY) {}

    StarLifterCamera() { this(.30f, .52f); }

    StarLifterCamera(float defaultYaw, float defaultPitch) {
        this.defaultYaw = defaultYaw;
        this.defaultPitch = defaultPitch;
        yaw = motion().animation(Spring.PANEL, defaultYaw);
        pitch = motion().animation(Spring.PANEL, defaultPitch);
        tracks = new InterruptibleAnimation[]{yaw, pitch, zoom, panX, panY};
    }

    void advance(long now, boolean reduced) {
        double dt = frame == 0 ? 0 : Math.min(.1, (now - frame) * .001);
        frame = now;
        for (var track : tracks) track.sample(now, reduced);
        if (reduced) speedX = speedY = 0;
        if (button >= 0 || yaw.running() || pitch.running() || reduced || dt <= 0) return;
        if (Math.abs(speedX) + Math.abs(speedY) > .003) {
            double decay = Math.exp(-FRICTION * dt);
            rotate(speedX * (1 - decay) / FRICTION, speedY * (1 - decay) / FRICTION);
            speedX *= decay;
            speedY *= decay;
        } else if (orbit && now - input >= 3500) {
            rotate(dt * .13, 0);
        }
    }

    void begin(int button, long now) {
        interrupt(now);
        this.button = button;
        distance = 0;
        dragTime = now;
    }

    boolean owns(int button) { return this.button == button; }

    void drag(double dx, double dy, int width, int height, long now) {
        distance += Math.hypot(dx, dy);
        if (button == 2) {
            panX.snapTo(Mth.clamp(panX.value() + (float) (dx / width), -.8f, .8f));
            panY.snapTo(Mth.clamp(panY.value() + (float) (dy / height), -.8f, .8f));
        } else {
            double horizontal = dx * .034, vertical = dy * .034;
            rotate(horizontal, vertical);
            double dt = Math.max(.008, (now - dragTime) * .001);
            speedX = Mth.clamp(horizontal / dt, -7, 7);
            speedY = Mth.clamp(vertical / dt, -7, 7);
        }
        input = dragTime = now;
    }

    boolean release(long now) {
        boolean click = button == 0 && distance < 2;
        button = -1;
        if (click || now - dragTime > 90) speedX = speedY = 0;
        input = frame = now;
        return click;
    }

    void cancel(long now) {
        button = -1;
        interrupt(now);
    }

    void wheel(double delta, double x, double y, long now) {
        input = now;
        speedX = speedY = 0;
        float previous = zoom.target();
        float next = Mth.clamp((float) (previous * Math.pow(1.16, delta)), MIN_ZOOM, MAX_ZOOM);
        if (next == previous) return;
        float ratio = next / previous;
        panX.animateTo(Mth.clamp((float) (x * (1 - ratio)) + panX.target() * ratio, -.8f, .8f), now);
        panY.animateTo(Mth.clamp((float) (y * (1 - ratio)) + panY.target() * ratio, -.8f, .8f), now);
        zoom.animateTo(next, now);
    }

    void reset(long now) { aim(defaultYaw, defaultPitch, 1, Vec3.ZERO, 1, 1, now); }

    void fit(long now) { aim(yaw.value(), pitch.value(), 1, Vec3.ZERO, 1, 1, now); }

    void enter(long now) {
        interrupt(now);
        yaw.snapTo(defaultYaw - .48f);
        pitch.snapTo(defaultPitch + .18f);
        zoom.snapTo(.72f);
        int orbit = StarLifterBootTimeline.playbackMs(StarLifterBootTimeline.ORBIT);
        int turn = StarLifterBootTimeline.playbackMs(2460);
        yaw.blendTo(defaultYaw, now, orbit, turn, SMOOTHSTEP);
        pitch.blendTo(defaultPitch, now, orbit, turn, SMOOTHSTEP);
        zoom.blendTo(1, now, StarLifterBootTimeline.playbackMs(StarLifterBootTimeline.IMAGING),
                StarLifterBootTimeline.playbackMs(900), SMOOTHSTEP);
    }

    void resetZoom(long now) { zoom.blendTo(1, now, 0, 230, SMOOTHSTEP); }

    void setZoom(float scale, long now) {
        interrupt(now);
        zoom.snapTo(Mth.clamp(scale, MIN_ZOOM, MAX_ZOOM));
    }

    void aim(float azimuth, float elevation, float magnification, Vec3 point, float aspect, float span, long now) {
        input = now;
        speedX = speedY = 0;
        float destination = yaw.value() + (float) Math.IEEEremainder(azimuth - yaw.value(), Math.PI * 2);
        var center = rotation.rotationX(elevation).rotateY(destination)
                .transform(new Vector3f((float) point.x, (float) point.y, (float) point.z));
        blend(yaw, destination, now);
        blend(pitch, elevation, now);
        blend(zoom, magnification, now);
        float scale = magnification / (2 * span);
        blend(panX, -center.x * scale / aspect, now);
        blend(panY, center.y * scale, now);
    }

    private static void blend(InterruptibleAnimation track, float target, long now) {
        track.animateTo(target, now);
    }

    private void rotate(double x, double y) {
        yaw.snapTo((float) Math.IEEEremainder(yaw.value() + x, Math.PI * 2));
        pitch.snapTo(Mth.clamp(pitch.value() + (float) y, -PITCH_LIMIT, PITCH_LIMIT));
    }

    private void interrupt(long now) {
        for (var track : tracks) track.stop(now);
        input = frame = now;
        speedX = speedY = 0;
    }

    void toggleOrbit(long now) {
        orbit = !orbit;
        input = now;
    }

    boolean orbit() { return orbit; }
    Pose pose() { return new Pose(yaw(), pitch(), zoom(), panX(), panY()); }

    void restore(Pose pose, long now, boolean immediate) {
        speedX = speedY = 0;
        input = now;
        float destination = yaw() + (float) Math.IEEEremainder(pose.yaw() - yaw(), Math.PI * 2);
        if (immediate) {
            yaw.snapTo(destination);
            pitch.snapTo(pose.pitch());
            zoom.snapTo(pose.zoom());
            panX.snapTo(pose.panX());
            panY.snapTo(pose.panY());
        } else {
            blend(yaw, destination, now);
            blend(pitch, pose.pitch(), now);
            blend(zoom, pose.zoom(), now);
            blend(panX, pose.panX(), now);
            blend(panY, pose.panY(), now);
        }
    }
    float yaw() { return yaw.value(); }
    float pitch() { return pitch.value(); }
    float zoom() { return zoom.value(); }
    float panX() { return panX.value(); }
    float panY() { return panY.value(); }
    void close() { for (var track : tracks) track.close(); }
}
