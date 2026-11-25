package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter.*;
import static org.lwjgl.opengl.GL33C.*;

/** Two trace targets retain the previous ray field for rotation reprojection. */
final class KerrRenderPass implements AutoCloseable {
    private static final float[] JITTER_X = {-.25f, .25f, -.375f, .375f};
    private static final float[] JITTER_Y = {.166667f, -.388889f, -.055556f, .277778f};
    private final Target[] targets = {new Target(), new Target()};
    private final Target transfer = new Target();
    private ShaderInstance shader;
    private int sample;
    private int attachmentLight, attachmentExit;
    private int timer;
    private boolean timerPending, timing, timedAdaptive;
    private double gpuMilliseconds;
    private double cpuMilliseconds;
    private double adaptiveScale = Quality.ADAPTIVE.scale;
    private int width, height, completedPasses;
    private int adaptiveRecoverySamples;
    private KerrSampling sampling;
    private History history;

    void draw(RenderTarget main, ShaderInstance composite, BlackHoleLensRenderer.Frame frame,
              Matrix4f inverseProjection, float[] axis, float[] equatorialX, float diskGain) throws IOException {
        if (shader == null) loadShader();
        KerrControls.Frame settings = frame.kerr;
        Quality quality = settings.quality();
        updateTimer(quality == Quality.ADAPTIVE);
        double area = frame.rectW * frame.rectH;
        double shadowFraction = Math.clamp(5 * frame.screenR / frame.screenH, 0, 1);
        sampling = KerrSampling.plan(quality, settings.renderScale(), adaptiveScale, area, shadowFraction,
                settings.value(DISTANCE));
        double scale = sampling.traceScale();
        width = stableExtent(Math.max(8, ((int) Math.ceil(scale * frame.rectW) + 7) & ~7), width);
        height = stableExtent(Math.max(8, ((int) Math.ceil(scale * frame.rectH) + 7) & ~7), height);
        float rectX = (float) frame.rectX0, rectY = (float) frame.rectY0;
        float rectWidth = (float) frame.rectW, rectHeight = (float) frame.rectH;
        Target target = targets[sample % targets.length];
        Target previous = targets[(sample + 1) % targets.length];
        int phase = sample % JITTER_X.length;
        float jitterX = JITTER_X[phase], jitterY = JITTER_Y[phase];
        int steps = settings.value(STEPS) >= 1 ? (int) Math.round(settings.value(STEPS)) : settings.steps();
        float tolerance = (float) (settings.tolerance() / settings.value(ACCURACY));
        var geometry = settings.geometry();
        int direction = settings.reverse() ? -1 : 1;

        long start = System.nanoTime();
        int textureUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        try {
            RenderSystem.activeTexture(GL_TEXTURE0);
            target.bind(width, height, attachmentLight, attachmentExit);
            RenderSystem.viewport(0, 0, width, height);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            double clockBlock = Math.floor(settings.clock() / 64);
            // Both stages launch the same physical rays. Only the trace grid is jittered.
            for (int stage = 0; stage < 2; stage++) {
                ShaderInstance program = stage == 0 ? shader : composite;
                program.safeGetUniform("uInvProj").set(inverseProjection);
                program.safeGetUniform("uBhView").set(frame.bhView);
                program.safeGetUniform("uDiskNormal").set(axis);
                program.safeGetUniform("uEquatorialX").set(equatorialX);
                program.safeGetUniform("uScreenSize").set((float) frame.screenW, (float) frame.screenH);
                program.safeGetUniform("uRs").set((float) frame.rsBlocks);
                program.safeGetUniform("uTime").set((float) (settings.clock() - 64 * clockBlock), (float) clockBlock);
                program.safeGetUniform("uDiskGain").set(diskGain);
                program.safeGetUniform("uDiskHeat").set(frame.diskHeat);
                program.safeGetUniform("uKerrParameters").set((float) geometry.spin(), (float) geometry.outerHorizon(),
                        (float) geometry.isco(direction), (float) direction);
                program.safeGetUniform("uDiskPhysics").set(settings.f(ACCRETION), settings.f(THICKNESS),
                        settings.f(TEMPERATURE), settings.f(EMISSIVITY) * settings.diskFade());
                program.safeGetUniform("uIntegration").set(tolerance, (float) steps);
            }
            composite.safeGetUniform("uRefine").set((float) sampling.subpixels());
            shader.safeGetUniform("uTraceRect").set(rectX, rectY, rectWidth, rectHeight);
            shader.safeGetUniform("uTraceSize").set((float) width, (float) height);
            shader.safeGetUniform("uRayJitter").set(jitterX, jitterY);
            timing = timer != 0 && !timerPending && glGetQueryi(GL_TIME_ELAPSED, GL_CURRENT_QUERY) == 0;
            if (timing) {
                timedAdaptive = quality == Quality.ADAPTIVE;
                glBeginQuery(GL_TIME_ELAPSED, timer);
            }
            drawTriangle(shader);
        } finally {
            main.bindWrite(true);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.activeTexture(textureUnit);
            cpuMilliseconds = (System.nanoTime() - start) * 1e-6;
        }

        composite.setSampler("RayLight", target.light);
        composite.setSampler("RayExit", target.exit);
        composite.setSampler("PixelTransfer", target.light);
        composite.safeGetUniform("uTraceRect").set(rectX, rectY, rectWidth, rectHeight);
        composite.safeGetUniform("uRayJitter").set(jitterX / width, jitterY / height);
        composite.safeGetUniform("uDisplay").set(settings.f(EXPOSURE), settings.f(BLOOM));
        composite.safeGetUniform("uSkyMix").set(settings.worldSky(), settings.starsSky(), settings.darkSky());
        boolean useHistory = history != null && history.canReproject(frame, axis, settings);
        composite.setSampler("PreviousLight", useHistory ? previous.light : target.light);
        composite.setSampler("PreviousExit", useHistory ? previous.exit : target.exit);
        composite.safeGetUniform("uHistoryWeight").set(useHistory ? .32f : 0f);
        if (useHistory) {
            var viewToPreviousClip = new Matrix4f().set(history.proj).mul(new Matrix4f().set(history.view))
                    .mul(new Matrix4f().set(frame.view).invert());
            var previousToView = new Matrix4f().set(frame.view).mul(new Matrix4f().set(history.view).invert());
            composite.safeGetUniform("uPreviousProjection").set(viewToPreviousClip);
            composite.safeGetUniform("uPreviousToView").set(previousToView);
            composite.safeGetUniform("uPreviousRect").set(history.x, history.y, history.w, history.h);
            composite.safeGetUniform("uPreviousJitter").set(history.jitterX, history.jitterY);
        }
        history = new History(frame.center, frame.camPos, frame.view, frame.proj, axis, frame.screenW, frame.screenH,
                frame.rsBlocks, settings, rectX, rectY, rectWidth, rectHeight, jitterX / width, jitterY / height);
        sample = (sample + 1) % JITTER_X.length;
        completedPasses++;
    }

    private void loadShader() throws IOException {
        ShaderInstance candidate = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                new ResourceLocation("gt_shanhai", "guixu_kerr_trace"), DefaultVertexFormat.POSITION);
        attachmentLight = glGetFragDataLocation(candidate.getId(), "rayLight");
        attachmentExit = glGetFragDataLocation(candidate.getId(), "rayExit");
        if (attachmentLight < 0 || attachmentExit < 0 || attachmentLight == attachmentExit) {
            candidate.close();
            throw new IOException("Kerr trace shader did not link both color outputs");
        }
        shader = candidate;
        if (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query) timer = glGenQueries();
    }

    void resolveFootprints(RenderTarget main, BlackHoleLensRenderer.ExteriorShader composite,
                           BlackHoleLensRenderer.Frame frame) {
        composite.setTransferPass(0);
        double scale = sampling.transferScale();
        if (scale == 0) return;
        int w = stableExtent((int) Math.ceil(scale * frame.rectW), transfer.width);
        int h = stableExtent((int) Math.ceil(scale * frame.rectH), transfer.height);
        int unit = glGetInteger(GL_ACTIVE_TEXTURE);
        try {
            RenderSystem.activeTexture(GL_TEXTURE0);
            transfer.bind(w, h, 0, -1);
            RenderSystem.viewport(0, 0, w, h);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            composite.safeGetUniform("uResolveSize").set((float) w, (float) h);
            composite.setTransferPass(1);
            drawTriangle(composite);
        } finally {
            main.bindWrite(true);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.activeTexture(unit);
            composite.setTransferPass(0);
        }
        composite.setSampler("PixelTransfer", transfer.light);
        composite.setTransferPass(2);
    }

    private static int stableExtent(int requested, int allocated) {
        // Small changes in the projected rectangle can reuse the existing sampling grid.
        return Math.abs(requested - allocated) <= Math.min(8, requested / 32) ? allocated : requested;
    }

    private void drawTriangle(ShaderInstance program) {
        RenderSystem.setShader(() -> program);
        var buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        buffer.vertex(-1, -1, 0).endVertex();
        buffer.vertex(3, -1, 0).endVertex();
        buffer.vertex(-1, 3, 0).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    void finishTiming() {
        if (timing) {
            glEndQuery(GL_TIME_ELAPSED);
            timing = false;
            timerPending = true;
        }
    }

    private void updateTimer(boolean adaptive) {
        if (timerPending && glGetQueryObjecti(timer, GL_QUERY_RESULT_AVAILABLE) != 0) {
            double elapsed = glGetQueryObjectui64(timer, GL_QUERY_RESULT) * 1e-6;
            gpuMilliseconds = gpuMilliseconds == 0 ? elapsed : gpuMilliseconds + .14 * (elapsed - gpuMilliseconds);
            timerPending = false;
            if (!timedAdaptive || !adaptive) {
                adaptiveRecoverySamples = 0;
            } else if (elapsed > 6.0) {
                adaptiveScale = Math.max(.13, adaptiveScale * Math.clamp(Math.sqrt(5.0 / elapsed), .65, .92));
                adaptiveRecoverySamples = 0;
            } else if (elapsed >= 3.7) {
                adaptiveRecoverySamples = 0;
            } else if (++adaptiveRecoverySamples >= 12) {
                adaptiveScale = Math.min(.74, adaptiveScale * 1.06);
                adaptiveRecoverySamples = 0;
            }
        }
    }

    String status() {
        return String.format(Locale.ROOT, "trace=%dx%d gpu=%s cpu=%.2fms frames=%d", width, height,
                gpuMilliseconds == 0 ? "pending" : String.format(Locale.ROOT, "%.2fms", gpuMilliseconds),
                cpuMilliseconds, completedPasses);
    }

    void invalidateHistory() { history = null; }

    @Override
    public void close() {
        if (shader != null) shader.close();
        shader = null;
        for (Target target : targets) target.close();
        transfer.close();
        width = height = 0;
        if (timer != 0) glDeleteQueries(timer);
        timer = 0;
        timerPending = false;
        gpuMilliseconds = 0;
        adaptiveScale = Quality.ADAPTIVE.scale;
        adaptiveRecoverySamples = 0;
        invalidateHistory();
    }

    private record History(double[] center, double[] observer, float[] view, float[] proj, float[] axis,
                           int screenW, int screenH, double rs, KerrControls.Frame settings,
                           float x, float y, float w, float h, float jitterX, float jitterY) {
        boolean canReproject(BlackHoleLensRenderer.Frame frame, float[] newAxis, KerrControls.Frame current) {
            double translation = Math.abs(observer[0] - frame.camPos[0]) + Math.abs(observer[1] - frame.camPos[1])
                    + Math.abs(observer[2] - frame.camPos[2]);
            return Arrays.equals(center, frame.center) && translation < rs * .002 && Math.abs(rs - frame.rsBlocks) < rs * .001
                    && screenW == frame.screenW && screenH == frame.screenH
                    && axis[0] * newAxis[0] + axis[1] * newAxis[1] + axis[2] * newAxis[2] > 0.90
                    && settings.reverse() == current.reverse() && settings.quality() == current.quality()
                    && Math.abs(settings.value(SPIN) - current.value(SPIN)) < .001
                    && Math.abs(settings.clock() - current.clock()) < 3;
        }
    }

    private static final class Target {
        int framebuffer, light, exit, width, height;

        void bind(int newWidth, int newHeight, int lightLocation, int exitLocation) {
            if (framebuffer != 0 && width == newWidth && height == newHeight) {
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                return;
            }
            close();
            width = newWidth;
            height = newHeight;
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            light = texture(GL_LINEAR, lightLocation);
            int[] buffers = new int[1 + Math.max(lightLocation, exitLocation)];
            buffers[lightLocation] = GL_COLOR_ATTACHMENT0 + lightLocation;
            if (exitLocation >= 0) {
                exit = texture(GL_NEAREST, exitLocation);
                buffers[exitLocation] = GL_COLOR_ATTACHMENT0 + exitLocation;
            }
            glDrawBuffers(buffers);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                close();
                throw new IllegalStateException("Kerr framebuffer status 0x" + Integer.toHexString(status));
            }
        }

        private int texture(int filter, int index) {
            int texture = glGenTextures();
            RenderSystem.bindTexture(texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, 0L);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + index, GL_TEXTURE_2D, texture, 0);
            return texture;
        }

        void close() {
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            if (light != 0) RenderSystem.deleteTexture(light);
            if (exit != 0) RenderSystem.deleteTexture(exit);
            framebuffer = light = exit = width = height = 0;
        }
    }
}
