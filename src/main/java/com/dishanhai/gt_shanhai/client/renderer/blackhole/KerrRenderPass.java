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
    private final Target[] targets = {new Target(false, GL_RGBA32F), new Target(false, GL_RGBA32F)};
    private final Target transfer = new Target();
    private final Target diskDetail = new Target(false, GL_RGBA32F);
    private final CompactHdrPostProcessing hdr = new CompactHdrPostProcessing();
    private final CompactHdrPostProcessing diskHighlights = new CompactHdrPostProcessing();
    private final KerrThinDisk disk = new KerrThinDisk();
    private final Target criticalRadii = new Target(true, GL_RGBA16F, GL_RGBA32F);
    private final Target criticalLight = new Target(true);
    private ShaderInstance shader;
    private int sample, jitterPhase;
    private int attachmentLight, attachmentExit;
    private int timer;
    private boolean timerPending, timing, timedAdaptive;
    private double gpuMilliseconds;
    private double cpuMilliseconds;
    private double adaptiveScale = Quality.ADAPTIVE.scale;
    private int width, height, completedPasses;
    private float exteriorWeight;
    private int adaptiveRecoverySamples;
    private KerrSampling sampling;
    private History history;

    void draw(RenderTarget main, BlackHoleLensRenderer.ExteriorShader composite, BlackHoleLensRenderer.Frame frame,
              Matrix4f inverseProjection, float[] axis, float[] equatorialX, float diskGain) {
        KerrControls.Frame settings = frame.kerr;
        Quality quality = settings.quality();
        long start = System.nanoTime();
        int steps = settings.value(STEPS) >= 1 ? (int) Math.round(settings.value(STEPS)) : settings.steps();
        float tolerance = (float) (settings.tolerance() / settings.value(ACCURACY));
        float rectX = (float) frame.rectX0, rectY = (float) frame.rectY0;
        float rectWidth = (float) frame.rectW, rectHeight = (float) frame.rectH;
        bindOptics(composite, frame, inverseProjection, axis, equatorialX, diskGain, steps, tolerance);
        composite.safeGetUniform("uExterior").set(exteriorWeight, (float) frame.holeScreen[0], (float) frame.holeScreen[1]);
        composite.safeGetUniform("uTraceRect").set(rectX, rectY, rectWidth, rectHeight);
        composite.safeGetUniform("uSkyMix").set(settings.worldSky(), settings.starsSky(), settings.darkSky());
        if (exteriorWeight >= 1f) {
            // The analytic image is drawn directly into the production framebuffer.
            main.bindWrite(false);
            composite.safeGetUniform("uDisplay").set(settings.f(EXPOSURE), settings.f(BLOOM));
            invalidateHistory();
            cpuMilliseconds = (System.nanoTime() - start) * 1e-6;
            completedPasses++;
            return;
        }

        double scale = sampling.traceScale();
        var trace = KerrTraceBudget.extent(scale, frame.rectW, frame.rectH, width, height, 8);
        width = trace.width();
        height = trace.height();
        Target target = targets[sample % targets.length];
        Target previous = targets[(sample + 1) % targets.length];
        var geometry = frame.geometry;
        boolean reuseExit = (quality == Quality.ADAPTIVE || quality == Quality.LOW)
                && history != null && previous.width == width && previous.height == height
                && history.matchesTraceGrid(frame, axis, equatorialX, steps, tolerance);
        // A cached frame uses the same rays as its source. Advance the pilot
        // phase only after a full trace so all jitter positions remain in rotation.
        int phase = reuseExit ? history.phase : jitterPhase;
        float jitter = (float) KerrSampling.blackHoleJitter(sampling.transferScale(), 2 * frame.dist / frame.rsBlocks);
        float jitterX = jitter * JITTER_X[phase], jitterY = jitter * JITTER_Y[phase];
        // Cached directions must be sampled at the phase that produced them.
        if (reuseExit) {
            jitterX = history.jitterX * width;
            jitterY = history.jitterY * height;
        }

        float exposure = settings.f(EXPOSURE);
        int textureUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        try {
            RenderSystem.activeTexture(GL_TEXTURE0);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            composite.safeGetUniform("uRefine").set((float) sampling.subpixels());
            target.bind(width, height, attachmentLight, attachmentExit);
            RenderSystem.viewport(0, 0, width, height);
            bindOptics(shader, frame, inverseProjection, axis, equatorialX, diskGain, steps, tolerance);
            shader.safeGetUniform("uTraceRect").set(rectX, rectY, rectWidth, rectHeight);
            shader.safeGetUniform("uTraceSize").set((float) width, (float) height);
            shader.safeGetUniform("uRayJitter").set(jitterX, jitterY);
            shader.safeGetUniform("uReuseRayExit").set(reuseExit ? 1f : 0f);
            shader.setSampler("CachedRayExit", reuseExit ? previous.exit : null);
            shader.safeGetUniform("uCriticalBounds").set(0f, 0f, 0f, 0f);
            shader.setSampler("CriticalRadii", null);
            drawTriangle(shader);
            drawCriticalStrip(composite, frame, diskGain);
            composite.setSampler("RayLight", target.light);
            composite.safeGetUniform("uRayJitter").set(jitterX / width, jitterY / height);
            int bloom = settings.f(BLOOM) > 0 ? hdr.draw(target.light, width, height, exposure) : 0;
            float bias = bloom != 0 ? KerrSampling.bloomLodBias(width, height, rectWidth, rectHeight, frame.screenH) : 0;
            composite.setSampler("RayBloom", bloom != 0 ? bloom : null);
            composite.safeGetUniform("uRayBloomLod").set(bloom != 0 ? (float) hdr.lastLevel() : 0, bias);
            var detail = (quality == Quality.ADAPTIVE || quality == Quality.LOW)
                    && (settings.starsSky() > 0 || settings.worldSky() > 0
                        || diskGain > 0 && settings.f(ACCRETION) > 0 && settings.f(EMISSIVITY) * settings.diskFade() > 0)
                    && frame.forwardDist > 0
                    ? sampling.diskDetail(frame.holeScreen[0], frame.holeScreen[1], frame.screenR, frame.screenW, frame.screenH)
                    : null;
            if (detail != null) {
                diskDetail.bind(detail.samples(), detail.samples(), attachmentLight, attachmentExit);
                RenderSystem.viewport(0, 0, detail.samples(), detail.samples());
                shader.safeGetUniform("uCriticalBounds").set(0f, 0f, 0f, 3f);
                shader.safeGetUniform("uTraceRect").set((float) detail.x(), (float) detail.y(), (float) detail.side(), (float) detail.side());
                shader.safeGetUniform("uTraceSize").set((float) detail.samples(), (float) detail.samples());
                drawTriangle(shader);
                composite.safeGetUniform("uDiskDetailRect").set((float) detail.x(), (float) detail.y(), (float) detail.side(), detail.weight());
                int glow = settings.f(BLOOM) > 0
                        ? diskHighlights.draw(diskDetail.light, detail.samples(), detail.samples(), exposure) : 0;
                composite.setSampler("DiskDetailBloom", glow != 0 ? glow : null);
                composite.safeGetUniform("uDiskBloomLod").set(glow != 0 ? (float) diskHighlights.lastLevel() : 0f,
                        KerrSampling.bloomLodBias(detail.samples(), detail.samples(), detail.side(), detail.side(), frame.screenH));
            } else {
                composite.safeGetUniform("uDiskDetailRect").set(0f, 0f, 1f, 0f);
                composite.setSampler("DiskDetailBloom", null);
            }
            composite.setSampler("DiskDetail", detail != null ? diskDetail.light : null);
            composite.setSampler("DiskDetailExit", detail != null ? diskDetail.exit : null);
        } finally {
            main.bindWrite(true);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.activeTexture(textureUnit);
            cpuMilliseconds = (System.nanoTime() - start) * 1e-6;
        }

        composite.setSampler("RayExit", target.exit);
        composite.setSampler("PixelTransfer", target.light);
        composite.safeGetUniform("uDisplay").set(exposure, settings.f(BLOOM));
        boolean useHistory = jitter > 0
                && history != null && !reuseExit && history.canReproject(frame, axis, settings);
        composite.setSampler("PreviousLight", useHistory ? previous.light : target.light);
        composite.setSampler("PreviousExit", useHistory ? previous.exit : target.exit);
        composite.safeGetUniform("uHistoryWeight").set(useHistory ? .32f : 0f);
        if (useHistory) {
            var viewToPrevious = new Matrix4f().set(history.view).mul(new Matrix4f().set(frame.view).invert());
            var viewToPreviousClip = new Matrix4f().set(history.proj).mul(viewToPrevious);
            var previousToView = viewToPrevious.invert();
            composite.safeGetUniform("uPreviousProjection").set(viewToPreviousClip);
            composite.safeGetUniform("uPreviousToView").set(previousToView);
            composite.safeGetUniform("uPreviousRect").set(history.x, history.y, history.w, history.h);
            composite.safeGetUniform("uPreviousJitter").set(history.jitterX, history.jitterY);
        }
        history = new History(frame.center, frame.camPos, frame.view, frame.proj, axis, frame.screenW, frame.screenH,
                frame.rsBlocks, settings, geometry.spin(), rectX, rectY, rectWidth, rectHeight, jitterX / width,
                jitterY / height, frame.bhView, equatorialX, steps, tolerance, phase, reuseExit);
        sample = (sample + 1) % targets.length;
        if (!reuseExit) jitterPhase = (jitterPhase + 1) % JITTER_X.length;
        completedPasses++;
    }

    private void bindOptics(ShaderInstance program, BlackHoleLensRenderer.Frame frame,
                                   Matrix4f inverseProjection, float[] axis, float[] equatorialX,
                                   float diskGain, int steps, float tolerance) {
        KerrControls.Frame settings = frame.kerr;
        var geometry = frame.geometry;
        int direction = settings.reverse() ? -1 : 1;
        double clockBlock = Math.floor(settings.clock() / 64);
        float temperature = settings.f(TEMPERATURE);
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
                temperature, settings.f(EMISSIVITY) * settings.diskFade());
        program.safeGetUniform("uOuterDisk").set(settings.f(OUTER_RADIUS));
        if (exteriorWeight == 0) {
            program.safeGetUniform("uDiskSqrtFlux").set(disk.profile(geometry, direction, settings.value(OUTER_RADIUS)));
        }
        program.safeGetUniform("uIntegration").set(tolerance, (float) steps);
    }

    private void drawCriticalStrip(ShaderInstance composite, BlackHoleLensRenderer.Frame frame, float diskGain) {
        float x = (float) frame.holeScreen[0], y = (float) frame.holeScreen[1];
        float radius = (float) frame.screenR;
        double observerDistance = 2 * frame.dist / frame.rsBlocks;
        boolean cached = sampling.transferScale() > 0;
        float span = KerrSampling.blackHoleCriticalStripSpan(sampling.transferScale(), observerDistance);
        int stripWidth = KerrSampling.blackHoleCriticalStripWidth(radius, frame.screenH, observerDistance, cached);
        boolean diskActive = diskGain > 0 && frame.kerr.f(ACCRETION) > 0
                && frame.kerr.f(EMISSIVITY) * frame.kerr.diskFade() > 0;
        boolean enabled = span > 0 && diskActive
                && stripWidth > 0 && Float.isFinite(x) && Float.isFinite(y)
                && shader.getUniform("uCriticalBounds") != null && composite.getUniform("uCriticalBounds") != null;
        if (enabled) {
            float searchRadius = radius * 2.5f;
            criticalRadii.bind(stripWidth, 1, attachmentLight, -1);
            RenderSystem.viewport(0, 0, stripWidth, 1);
            shader.safeGetUniform("uCriticalBounds").set(x, y, searchRadius, 1f);
            shader.safeGetUniform("uTraceSize").set((float) stripWidth, 1f);
            drawTriangle(shader);

            int rows = cached ? KerrSampling.CACHED_CRITICAL_STRIP_ROWS : KerrSampling.CRITICAL_STRIP_ROWS;
            criticalLight.bind(stripWidth, rows, attachmentLight, -1);
            RenderSystem.viewport(0, 0, stripWidth, rows);
            shader.setSampler("CriticalRadii", criticalRadii.light);
            shader.safeGetUniform("uCriticalBounds").set(x, y, span, 2f);
            shader.safeGetUniform("uTraceSize").set((float) stripWidth, (float) rows);
            drawTriangle(shader);
        }
        composite.setSampler("CriticalRadii", enabled ? criticalRadii.light : null);
        composite.setSampler("CriticalLight", enabled ? criticalLight.light : null);
        composite.safeGetUniform("uCriticalBounds").set(x, y, span, enabled ? 1f : 0f);
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
    }

    void resolveFootprints(RenderTarget main, BlackHoleLensRenderer.ExteriorShader composite,
                           BlackHoleLensRenderer.Frame frame) {
        composite.setTransferPass(0);
        if (exteriorWeight >= 1f) return;
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

    void beginFrame(BlackHoleLensRenderer.Frame frame) throws IOException {
        KerrControls.Frame settings = frame.kerr;
        double distance = 2 * frame.dist / frame.rsBlocks;
        exteriorWeight = KerrSampling.exteriorWeight(settings.quality());
        if (exteriorWeight < 1f && shader == null) loadShader();
        if (timer == 0 && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) timer = glGenQueries();
        boolean adaptive = settings.quality() == Quality.ADAPTIVE && exteriorWeight < 1f;
        double shadowFraction = KerrSampling.shadowFraction(distance, frame.tanHalfFov);
        double budgetMs = 5.0
                - 2.0 * Math.max(KerrSampling.nearWeight(distance), shadowFraction * shadowFraction);
        updateTimer(adaptive, budgetMs);
        if (exteriorWeight < 1f) {
            double area = frame.rectW * frame.rectH;
            sampling = KerrSampling.plan(settings.quality(), settings.renderScale(), adaptiveScale,
                    area, shadowFraction, distance, frame.screenR);
        }
        timing = timer != 0 && !timerPending && glGetQueryi(GL_TIME_ELAPSED, GL_CURRENT_QUERY) == 0;
        if (timing) {
            timedAdaptive = adaptive;
            glBeginQuery(GL_TIME_ELAPSED, timer);
        }
    }

    void finishTiming() {
        if (timing) {
            glEndQuery(GL_TIME_ELAPSED);
            timing = false;
            timerPending = true;
        }
    }

    private void updateTimer(boolean adaptive, double budgetMs) {
        if (timerPending && glGetQueryObjecti(timer, GL_QUERY_RESULT_AVAILABLE) != 0) {
            double elapsed = glGetQueryObjectui64(timer, GL_QUERY_RESULT) * 1e-6;
            gpuMilliseconds = gpuMilliseconds == 0 ? elapsed : gpuMilliseconds + .14 * (elapsed - gpuMilliseconds);
            timerPending = false;
            if (!timedAdaptive || !adaptive) {
                adaptiveRecoverySamples = 0;
            } else if (elapsed > budgetMs + 1.0) {
                adaptiveScale = Math.max(.13, adaptiveScale * Math.clamp(Math.sqrt(budgetMs / elapsed), .65, .92));
                adaptiveRecoverySamples = 0;
            } else if (elapsed >= budgetMs - 1.3) {
                adaptiveRecoverySamples = 0;
            } else if (++adaptiveRecoverySamples >= 12) {
                adaptiveScale = Math.min(.74, adaptiveScale * 1.06);
                adaptiveRecoverySamples = 0;
            }
        }
    }

    String status() {
        String path = exteriorWeight >= 1f ? "L0 analytic" : String.format(Locale.ROOT, "GRRT trace=%dx%d", width, height);
        return String.format(Locale.ROOT, "%s gpu=%s cpu=%.2fms frames=%d", path,
                gpuMilliseconds == 0 ? "pending" : String.format(Locale.ROOT, "%.2fms", gpuMilliseconds),
                cpuMilliseconds, completedPasses);
    }

    void invalidateHistory() {
        history = null;
    }

    void resetMode() {
        invalidateHistory();
        sample = 0;
        jitterPhase = 0;
        completedPasses = 0;
        gpuMilliseconds = cpuMilliseconds = 0;
        adaptiveScale = Quality.ADAPTIVE.scale;
        adaptiveRecoverySamples = 0;
        exteriorWeight = 0f;
        if (timerPending) {
            glDeleteQueries(timer);
            timer = glGenQueries();
            timerPending = false;
        }
    }

    @Override
    public void close() {
        if (shader != null) shader.close();
        shader = null;
        for (Target target : targets) target.close();
        transfer.close();
        diskDetail.close();
        diskHighlights.close();
        criticalRadii.close();
        criticalLight.close();
        hdr.close();
        width = height = 0;
        sample = jitterPhase = completedPasses = 0;
        if (timer != 0) glDeleteQueries(timer);
        timer = 0;
        timerPending = false;
        gpuMilliseconds = cpuMilliseconds = 0;
        adaptiveScale = Quality.ADAPTIVE.scale;
        adaptiveRecoverySamples = 0;
        invalidateHistory();
    }

    private record History(double[] center, double[] observer, float[] view, float[] proj, float[] axis,
                           int screenW, int screenH, double rs, KerrControls.Frame settings, double spin,
                           float x, float y, float w, float h, float jitterX, float jitterY,
                           float[] lensView, float[] equatorialX, int steps, float tolerance, int phase,
                           boolean reusedExit) {
        boolean matchesTraceGrid(BlackHoleLensRenderer.Frame frame, float[] newAxis, float[] newEquatorialX,
                                 int newSteps, float newTolerance) {
            return !reusedExit
                    && screenW == frame.screenW && screenH == frame.screenH
                    && x == (float) frame.rectX0 && y == (float) frame.rectY0
                    && w == (float) frame.rectW && h == (float) frame.rectH
                    && rs == frame.rsBlocks && spin == frame.geometry.spin() && settings.reverse() == frame.kerr.reverse()
                    && steps == newSteps && tolerance == newTolerance
                    && Arrays.equals(lensView, frame.bhView) && Arrays.equals(proj, frame.proj)
                    && Arrays.equals(axis, newAxis) && Arrays.equals(equatorialX, newEquatorialX);
        }

        boolean canReproject(BlackHoleLensRenderer.Frame frame, float[] newAxis, KerrControls.Frame current) {
            double translation = Math.abs(observer[0] - frame.camPos[0]) + Math.abs(observer[1] - frame.camPos[1])
                    + Math.abs(observer[2] - frame.camPos[2]);
            return Arrays.equals(center, frame.center) && translation < rs * .002 && Math.abs(rs - frame.rsBlocks) < rs * .001
                    && screenW == frame.screenW && screenH == frame.screenH
                    && axis[0] * newAxis[0] + axis[1] * newAxis[1] + axis[2] * newAxis[2] > 0.90
                    && settings.reverse() == current.reverse() && settings.quality() == current.quality()
                    && Math.abs(spin - frame.geometry.spin()) < .001
                    && Math.abs(settings.clock() - current.clock()) < 3;
        }
    }

    static final class Target {
        private final boolean circular;
        private final int exitFormat, lightFormat;
        int framebuffer, light, exit, width, height;

        Target() { this(false); }

        Target(boolean circular) { this(circular, GL_RGBA16F); }

        Target(boolean circular, int exitFormat) { this(circular, exitFormat, GL_RGBA16F); }

        Target(boolean circular, int exitFormat, int lightFormat) {
            this.circular = circular;
            this.exitFormat = exitFormat;
            this.lightFormat = lightFormat;
        }

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
            light = texture(GL_LINEAR, lightLocation, lightFormat);
            int[] buffers = new int[1 + Math.max(lightLocation, exitLocation)];
            buffers[lightLocation] = GL_COLOR_ATTACHMENT0 + lightLocation;
            if (exitLocation >= 0) {
                exit = texture(GL_NEAREST, exitLocation, exitFormat);
                buffers[exitLocation] = GL_COLOR_ATTACHMENT0 + exitLocation;
            }
            glDrawBuffers(buffers);
            glReadBuffer(GL_COLOR_ATTACHMENT0 + lightLocation);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                close();
                throw new IllegalStateException("Kerr framebuffer status 0x" + Integer.toHexString(status));
            }
        }

        private int texture(int filter, int index, int format) {
            int texture = glGenTextures();
            RenderSystem.bindTexture(texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, circular ? GL_REPEAT : GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format == GL_R32F ? GL_RED : GL_RGBA, GL_FLOAT, 0L);
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
