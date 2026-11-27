package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
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
    static final int WHITE_BLOOM_SOURCE_PASS = 3;
    static final int WHITE_OPTICAL_PASS = 4;
    private final Target[] targets = {new Target(false, GL_RGBA32F), new Target(false, GL_RGBA32F)};
    private final Target transfer = new Target();
    private final Target whiteBloomSource = new Target();
    private final Target whiteOptical = new Target();
    private final WhiteDirectTargets whiteDirect = new WhiteDirectTargets();
    private final Target diskDetail = new Target(false, GL_RGBA32F);
    private final CompactHdrPostProcessing diskHighlights = new CompactHdrPostProcessing();
    private final KerrThinDisk disk = new KerrThinDisk();
    private final Target criticalRadii = new Target(true, GL_RGBA16F, GL_RGBA32F);
    private final Target criticalLight = new Target(true);
    private final WhiteHoleParticleGrid particles = new WhiteHoleParticleGrid();
    private final CompactHdrPostProcessing hdr = new CompactHdrPostProcessing();
    private ShaderInstance shader;
    private ShaderInstance whiteCacheShader;
    private VertexBuffer triangle;
    private int directLightAttachment, directDepthAttachment;
    private int sample, jitterPhase;
    private int attachmentLight, attachmentExit;
    private int timer;
    private boolean timerPending, timing, timedAdaptive;
    private double gpuMilliseconds;
    private double cpuMilliseconds;
    private long cpuStartNanos;
    private double adaptiveScale = Quality.ADAPTIVE.scale;
    private int width, height, completedPasses;
    private boolean particleAtlasActive;
    private float exteriorWeight;
    private int adaptiveRecoverySamples;
    private long lastExposureTimestamp;
    private KerrSampling sampling;
    private History history;
    private long whiteRefreshStamp;
    private int whiteReuseFrames, whiteCacheHits;
    private boolean whiteDirectReady;
    private int whiteDirectDivisor = 1;
    private boolean whiteCacheUnavailable;
    private boolean criticalRadiiReady;
    private int blackFieldHits;
    private boolean reuseBlackField, transferReady;
    private int lastBloom, lastDiskBloom;

    void draw(RenderTarget main, BlackHoleLensRenderer.ExteriorShader composite, BlackHoleLensRenderer.Frame frame,
              Matrix4f inverseProjection, float[] axis, float[] equatorialX, float diskGain, int worldDepthTexture,
              boolean sampleBackground) {
        KerrControls.Frame settings = frame.kerr;
        reuseBlackField = false;
        Quality quality = settings.quality();
        int steps = settings.value(STEPS) >= 1 ? (int) Math.round(settings.value(STEPS)) : settings.steps();
        float tolerance = (float) (settings.tolerance() / settings.value(ACCURACY));
        float rectX = (float) frame.rectX0, rectY = (float) frame.rectY0;
        float rectWidth = (float) frame.rectW, rectHeight = (float) frame.rectH;
        bindOptics(composite, frame, inverseProjection, axis, equatorialX, diskGain, steps, tolerance);
        composite.safeGetUniform("uExterior").set(exteriorWeight, (float) frame.holeScreen[0], (float) frame.holeScreen[1]);
        composite.safeGetUniform("uTraceRect").set(rectX, rectY, rectWidth, rectHeight);
        composite.safeGetUniform("uWhiteDebug").set((float) frame.white.debugMode());
        composite.safeGetUniform("uSkyMix").set(settings.worldSky(), settings.starsSky(), settings.darkSky());
        composite.safeGetUniform("uWhiteHostSky").set(frame.usesWhiteWorldPrimary() && !frame.whitePreview ? 1f : 0f);
        if (exteriorWeight >= 1f) {
            // The analytic image is drawn directly into the production framebuffer.
            main.bindWrite(false);
            composite.safeGetUniform("uDisplay").set(settings.f(EXPOSURE), settings.f(BLOOM));
            invalidateHistory();
            lastExposureTimestamp = 0;
            particleAtlasActive = false;
            completedPasses++;
            return;
        }

        double scale = sampling.traceScale();
        boolean worldPrimary = frame.usesWhiteWorldPrimary();
        boolean whiteOptics = worldPrimary && frame.white.gain() > 0 && frame.white.diskDensity() > 0;
        boolean whiteSkyRays = worldPrimary && sampleBackground && settings.starsSky() > 0
                && (frame.whiteDemo || worldDepthTexture != 0);
        boolean quantizePilot = worldPrimary && (quality == Quality.ADAPTIVE || quality == Quality.LOW);
        int tile = quantizePilot ? 16 : 8;
        var trace = KerrTraceBudget.extent(scale, frame.rectW, frame.rectH, width, height, tile);
        width = trace.width();
        height = trace.height();
        Target target = targets[sample % targets.length];
        Target previous = targets[(sample + 1) % targets.length];
        var geometry = frame.geometry;
        int directX = (int) Math.floor(frame.rectX0), directY = (int) Math.floor(frame.rectY0);
        int directW = (int) Math.ceil(frame.rectX0 + frame.rectW) - directX;
        int directH = (int) Math.ceil(frame.rectY0 + frame.rectH) - directY;
        double distance = 2 * frame.dist / frame.rsBlocks;
        int directDivisor = worldPrimary
                ? KerrSampling.whiteDirectDivisor(quality, (double) directW * directH, whiteDirectDivisor)
                : 1;
        whiteDirectDivisor = directDivisor;
        int directWidth = KerrSampling.whiteDirectExtent(directW, directDivisor);
        int directHeight = KerrSampling.whiteDirectExtent(directH, directDivisor);
        boolean reducedDirect = directWidth < directW || directHeight < directH;
        boolean cacheDirect = worldPrimary && (reducedDirect || distance <= 70)
                && (long) directWidth * directHeight <= KerrSampling.WHITE_DIRECT_CACHE_PIXELS;
        if (cacheDirect && whiteCacheShader == null && !whiteCacheUnavailable) loadWhiteCacheShader();
        cacheDirect &= whiteCacheShader != null;
        boolean stableWhite = worldPrimary && history != null && history.whiteSkyRays == whiteSkyRays
                && history.matchesWhiteHost(frame, axis, equatorialX, steps, tolerance);
        boolean reuseWhite = stableWhite && previous.width == width && previous.height == height
                && (!cacheDirect || whiteDirectReady && whiteDirect.active().width == directWidth
                    && whiteDirect.active().height == directHeight)
                && KerrSampling.whiteHostCanReuse(frame.stampNanos - whiteRefreshStamp, whiteReuseFrames);
        boolean black = frame.white.mix() == 0;
        boolean lowBudget = quality == Quality.ADAPTIVE || quality == Quality.LOW;
        boolean needsDirection = !black || sampleBackground && (settings.worldSky() > 0 || settings.starsSky() > 0);
        boolean activeDisk = diskGain > 0 && settings.f(ACCRETION) > 0 && settings.f(EMISSIVITY) * settings.diskFade() > 0;
        boolean sameGeometry = black && history != null && history.matchesBlackCamera(frame, axis, equatorialX, steps, tolerance);
        boolean samePilot = sameGeometry && previous.width == width && previous.height == height
                && history.needsDirection == needsDirection;
        float jitter = worldPrimary ? 0 : black
                ? (float) KerrSampling.blackHoleJitter(sampling.transferScale(), distance) : 1;
        boolean reuseBlack = lowBudget && jitter == 0 && samePilot
                && history.matchesBlackLight(frame, sampling, diskGain, activeDisk);
        if (reuseWhite || reuseBlack) target = previous;
        if (reuseBlack) timedAdaptive = false;
        reuseBlackField = reuseBlack;
        boolean reuseExit = lowBudget && samePilot && needsDirection
                && (!history.reusedExit || jitter == 0 && history.jitterX == 0 && history.jitterY == 0);
        // A cached frame uses the same rays as its source. Advance the pilot
        // phase only after a full trace so all jitter positions remain in rotation.
        int phase = reuseExit || reuseBlack ? history.phase : jitterPhase;
        float jitterX = jitter * JITTER_X[phase], jitterY = jitter * JITTER_Y[phase];
        // Cached directions must be sampled at the phase that produced them.
        if (reuseExit || reuseBlack) {
            jitterX = history.jitterX * width;
            jitterY = history.jitterY * height;
        }

        double exposureDelta = lastExposureTimestamp != 0 ? (frame.stampNanos - lastExposureTimestamp) * 1e-9 : 0;
        lastExposureTimestamp = frame.stampNanos;
        boolean autoExposure = frame.white.mix() > 0 && frame.white.automaticExposure();
        float exposure;
        int textureUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        try {
            int budget = switch (quality) {
                case COMPATIBILITY -> 0;
                case LOW -> 4096;
                case MEDIUM -> 8192;
                case HIGH -> 16384;
                case ULTRA, CINEMATIC, SCIENTIFIC -> WhiteHoleParticleGrid.MAX_PARTICLES;
                case ADAPTIVE -> adaptiveScale < .24 ? 4096 : adaptiveScale < .44 ? 8192 : 16384;
            };
            if (autoExposure && (history == null || !Arrays.equals(history.center, frame.center) || exposureDelta > 1)) {
                hdr.adaptExposure(settings.f(EXPOSURE), 0, false);
            }
            exposure = hdr.adaptExposure(settings.f(EXPOSURE), Math.clamp(exposureDelta, 0, .25), autoExposure);
            RenderSystem.activeTexture(GL_TEXTURE0);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            composite.safeGetUniform("uRefine").set((float) sampling.subpixels());
            if (!reuseWhite && !reuseBlack) {
                int particleAtlas = frame.white.mix() > 0
                        ? particles.update(frame.white.time(), frame.white.particleSeed(), frame.white.beta(),
                                frame.white.jets(), (float) geometry.outerHorizon(), budget)
                        : 0;
                particleAtlasActive = particleAtlas != 0;
                target.bind(width, height, attachmentLight, attachmentExit);
                RenderSystem.viewport(0, 0, width, height);
                bindOptics(shader, frame, inverseProjection, axis, equatorialX, diskGain, steps, tolerance);
                for (int stage = 0; stage < 2; stage++) {
                    ShaderInstance program = stage == 0 ? shader : composite;
                    program.setSampler("WhiteParticleGrid", particleAtlasActive ? particleAtlas : null);
                    program.safeGetUniform("uWhiteParticleGain").set(particleAtlasActive ? .55f : 0f);
                }
                shader.safeGetUniform("uTraceRect").set(rectX, rectY, rectWidth, rectHeight);
                shader.safeGetUniform("uTraceSize").set((float) width, (float) height);
                shader.safeGetUniform("uRayJitter").set(jitterX, jitterY);
                shader.safeGetUniform("uTransferOnly").set(needsDirection ? 0f : 1f);
                shader.safeGetUniform("uReuseRayExit").set(reuseExit ? 1f : 0f);
                shader.setSampler("CachedRayExit", reuseExit ? previous.exit : null);
                shader.safeGetUniform("uCriticalBounds").set(0f, 0f, 0f, 0f);
                shader.setSampler("CriticalRadii", null);
                drawTriangle(shader);
                drawCriticalStrip(composite, frame, diskGain, whiteSkyRays, sameGeometry);
                if (whiteOptics) {
                    // Filter the broad secondary image once at pilot resolution.
                    // The critical strip still supplies the narrow optical detail.
                    composite.setSampler("RayLight", target.light);
                    composite.setSampler("DiskDetail", null);
                    drawWhiteLayer(composite, whiteOptical, WHITE_OPTICAL_PASS);
                }
                whiteDirectReady = cacheDirect && (reducedDirect || stableWhite);
                if (whiteDirectReady) {
                    bindOptics(whiteCacheShader, frame, inverseProjection, axis, equatorialX, diskGain, steps, tolerance);
                    whiteCacheShader.setSampler("WhiteParticleGrid", particleAtlasActive ? particleAtlas : null);
                    whiteCacheShader.safeGetUniform("uWhiteParticleGain").set(particleAtlasActive ? .55f : 0f);
                    drawWhiteDirect(frame, worldDepthTexture, directX, directY, directW, directH, directWidth, directHeight);
                }
            }
            composite.setSampler("WhiteDirectLight", whiteDirectReady ? whiteDirect.active().light : null);
            composite.setSampler("WhiteDirectDepth", whiteDirectReady ? whiteDirect.active().exit : null);
            composite.safeGetUniform("uWhiteCacheRect").set((float) directX, (float) directY,
                    whiteDirectReady ? (float) directW : 0f, whiteDirectReady ? (float) directH : 0f);
            composite.safeGetUniform("uWhiteCacheReduced").set(reducedDirect ? 1f : 0f);
            composite.setSampler("RayLight", target.light);
            composite.safeGetUniform("uWhiteOpticalLayer").set(whiteOptics ? 1f : -1f);
            composite.setSampler("DiskDetail", whiteOptics ? whiteOptical.light : null);
            composite.safeGetUniform("uRayJitter").set(jitterX / width, jitterY / height);
            int bloomSource = target.light;
            if (worldPrimary && frame.white.debugMode() == 0 && settings.f(BLOOM) > 0) {
                composite.setSampler("RayBloom", target.light);
                drawWhiteLayer(composite, whiteBloomSource, WHITE_BLOOM_SOURCE_PASS);
                bloomSource = whiteBloomSource.light;
            }
            if (!reuseBlack) {
                lastBloom = settings.f(BLOOM) > 0 || autoExposure
                        ? hdr.draw(target.light, bloomSource, width, height, exposure) : 0;
            }
            int bloom = lastBloom;
            float bloomBias = bloom != 0
                    ? KerrSampling.bloomLodBias(width, height, rectWidth, rectHeight, frame.screenH) : 0;
            boolean rawHostBloom = bloom == 0 && bloomSource != target.light;
            composite.setSampler("RayBloom", bloom != 0 ? bloom : rawHostBloom ? bloomSource : null);
            composite.safeGetUniform("uRayBloomLod").set(bloom != 0 ? (float) hdr.lastLevel() : rawHostBloom ? -1f : 0f,
                    bloomBias);
            var detail = lowBudget && black && (needsDirection || activeDisk) && frame.forwardDist > 0
                    ? sampling.diskDetail(frame.holeScreen[0], frame.holeScreen[1], frame.screenR,
                            frame.screenW, frame.screenH, 2 * frame.dist / frame.rsBlocks)
                    : null;
            if (detail != null) {
                if (!reuseBlack) {
                    diskDetail.bind(detail.samples(), detail.samples(), attachmentLight, attachmentExit);
                    RenderSystem.viewport(0, 0, detail.samples(), detail.samples());
                    shader.safeGetUniform("uCriticalBounds").set(0f, 0f, 0f, 3f);
                    shader.safeGetUniform("uTraceRect").set((float) detail.x(), (float) detail.y(), (float) detail.side(), (float) detail.side());
                    shader.safeGetUniform("uTraceSize").set((float) detail.samples(), (float) detail.samples());
                    drawTriangle(shader);
                }
                composite.safeGetUniform("uDiskDetailRect").set((float) detail.x(), (float) detail.y(), (float) detail.side(), detail.weight());
                if (!reuseBlack) {
                    lastDiskBloom = settings.f(BLOOM) > 0
                            ? diskHighlights.draw(diskDetail.light, detail.samples(), detail.samples(), exposure) : 0;
                }
                int glow = lastDiskBloom;
                composite.setSampler("DiskDetailBloom", glow != 0 ? glow : null);
                composite.safeGetUniform("uDiskBloomLod").set(glow != 0 ? (float) diskHighlights.lastLevel() : 0f,
                        KerrSampling.bloomLodBias(detail.samples(), detail.samples(), detail.side(), detail.side(), frame.screenH));
            } else {
                composite.safeGetUniform("uDiskDetailRect").set(0f, 0f, 1f, 0f);
                composite.setSampler("DiskDetailBloom", null);
            }
            if (!worldPrimary) composite.setSampler("DiskDetail", detail != null ? diskDetail.light : null);
            int localExit = detail != null ? diskDetail.exit : whiteSkyRays ? criticalLight.exit : 0;
            composite.setSampler("LocalRayExit", localExit != 0 ? localExit : null);
        } finally {
            main.bindWrite(true);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.activeTexture(textureUnit);
        }

        composite.setSampler("RayExit", target.exit);
        composite.setSampler("PixelTransfer", target.light);
        composite.safeGetUniform("uDisplay").set(exposure, settings.f(BLOOM));
        boolean useHistory = !worldPrimary && (frame.white.mix() != 0 || jitter > 0)
                && history != null && !reuseExit && !reuseBlack && history.needsDirection == needsDirection
                && history.canReproject(frame, axis, settings);
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
        if (reuseWhite) {
            whiteReuseFrames++;
            whiteCacheHits++;
        } else if (reuseBlack) {
            blackFieldHits++;
        } else {
            history = new History(frame.center, frame.camPos, frame.view, frame.proj, axis, frame.screenW, frame.screenH,
                    frame.rsBlocks, settings, frame.white, geometry.spin(), rectX, rectY, rectWidth, rectHeight, jitterX / width,
                    jitterY / height, frame.bhView, equatorialX, steps, tolerance, phase, reuseExit, worldPrimary,
                    whiteSkyRays, sampling, needsDirection, diskGain, frame.diskHeat);
            sample = (sample + 1) % targets.length;
            if (!reuseExit) jitterPhase = (jitterPhase + 1) % JITTER_X.length;
            whiteRefreshStamp = frame.stampNanos;
            whiteReuseFrames = 0;
        }
        completedPasses++;
    }

    private void drawWhiteLayer(BlackHoleLensRenderer.ExteriorShader composite, Target target, int pass) {
        target.bind(width, height, 0, -1);
        RenderSystem.viewport(0, 0, width, height);
        composite.safeGetUniform("uResolveSize").set((float) width, (float) height);
        composite.setTransferPass(pass);
        try {
            drawTriangle(composite);
        } finally {
            composite.setTransferPass(0);
        }
    }

    private void drawWhiteDirect(BlackHoleLensRenderer.Frame frame, int worldDepthTexture, int x, int y,
                                 int w, int h, int width, int height) {
        whiteDirect.bind(width, height, directLightAttachment, directDepthAttachment);
        RenderSystem.viewport(0, 0, width, height);
        whiteCacheShader.setSampler("SceneDepth", worldDepthTexture != 0 ? worldDepthTexture : null);
        whiteCacheShader.safeGetUniform("uWhiteSceneDepth").set(worldDepthTexture != 0 ? 1f : 0f);
        whiteCacheShader.safeGetUniform("uBgRect").set((float) frame.copyX0, (float) frame.copyY0,
                (float) frame.copyW, (float) frame.copyH);
        whiteCacheShader.safeGetUniform("uCacheOrigin").set((float) x, (float) y);
        whiteCacheShader.safeGetUniform("uCacheStride").set((float) w / width, (float) h / height);
        drawTriangle(whiteCacheShader);
    }

    private void loadWhiteCacheShader() {
        try {
            ShaderInstance candidate = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
                    new ResourceLocation("gt_shanhai", "guixu_white_cache"), DefaultVertexFormat.POSITION);
            directLightAttachment = glGetFragDataLocation(candidate.getId(), "directLight");
            directDepthAttachment = glGetFragDataLocation(candidate.getId(), "directDepth");
            if (directLightAttachment < 0 || directDepthAttachment < 0 || directLightAttachment == directDepthAttachment) {
                candidate.close();
                throw new IOException("Missing white cache framebuffer outputs");
            }
            whiteCacheShader = candidate;
        } catch (IOException failure) {
            whiteCacheUnavailable = true;
            GTDishanhaiMod.LOGGER.warn("White-hole direct cache shader unavailable", failure);
        }
    }

    private void bindOptics(ShaderInstance program, BlackHoleLensRenderer.Frame frame,
                                   Matrix4f inverseProjection, float[] axis, float[] equatorialX,
                                   float diskGain, int steps, float tolerance) {
        KerrControls.Frame settings = frame.kerr;
        var geometry = frame.geometry;
        int direction = settings.reverse() ? -1 : 1;
        double clockBlock = Math.floor(settings.clock() / 64);
        float temperature = Math.fma(frame.white.mix(), frame.white.temperature() - settings.f(TEMPERATURE),
                settings.f(TEMPERATURE));
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
        if (frame.white.mix() == 0 && exteriorWeight == 0) {
            program.safeGetUniform("uDiskSqrtFlux").set(disk.profile(geometry, direction, settings.value(OUTER_RADIUS)));
        }
        program.safeGetUniform("uIntegration").set(tolerance, (float) steps);
        frame.white.bind(program);
        program.safeGetUniform("uWhitePrimary").set(frame.usesWhiteWorldPrimary() ? 1f : 0f);
    }

    private void drawCriticalStrip(ShaderInstance composite, BlackHoleLensRenderer.Frame frame, float diskGain,
                                   boolean skyRays, boolean sameGeometry) {
        float x = (float) frame.holeScreen[0], y = (float) frame.holeScreen[1];
        float radius = (float) frame.screenR;
        double observerDistance = 2 * frame.dist / frame.rsBlocks;
        boolean whiteWorld = frame.usesWhiteWorldPrimary();
        boolean cached = !whiteWorld && sampling.transferScale() > 0;
        float span = whiteWorld ? 12f : KerrSampling.blackHoleCriticalStripSpan(sampling.transferScale(), observerDistance);
        int stripWidth = whiteWorld ? KerrSampling.criticalStripWidth(radius, frame.screenH, observerDistance)
                : KerrSampling.blackHoleCriticalStripWidth(radius, frame.screenH, observerDistance, cached);
        boolean diskActive = whiteWorld ? frame.white.gain() > 0 && frame.white.diskDensity() > 0
                : frame.white.mix() == 0 && diskGain > 0 && frame.kerr.f(ACCRETION) > 0
                  && frame.kerr.f(EMISSIVITY) * frame.kerr.diskFade() > 0;
        boolean enabled = span > 0 && (diskActive || skyRays) && frame.forwardDist > .05
                && stripWidth > 0 && Float.isFinite(x) && Float.isFinite(y)
                && shader.getUniform("uCriticalBounds") != null && composite.getUniform("uCriticalBounds") != null;
        int coreRows = cached ? KerrSampling.CACHED_CRITICAL_STRIP_ROWS : KerrSampling.CRITICAL_STRIP_ROWS;
        boolean extend = enabled && diskActive && !whiteWorld
                && (frame.kerr.quality() == Quality.ADAPTIVE || frame.kerr.quality() == Quality.LOW);
        var arc = KerrSampling.criticalArc(radius, coreRows, stripWidth, extend);
        float searchRadius = radius * 2.5f;
        // The contour shader brackets every radius inside searchRadius. Include
        // the outer strip and the display filter before rejecting its whole target.
        float reach = searchRadius + Math.max(span, arc.outer()) + 2f;
        enabled &= x + reach > frame.rectX0 && y + reach > frame.rectY0
                && x - reach < frame.rectX0 + frame.rectW && y - reach < frame.rectY0 + frame.rectH;
        composite.safeGetUniform("uCriticalArc").set(arc.inner(), arc.outer());
        if (enabled) {
            stripWidth = arc.columns();
            if (!sameGeometry || !criticalRadiiReady || criticalRadii.width != stripWidth) {
                criticalRadii.bind(stripWidth, 1, attachmentLight, -1);
                RenderSystem.viewport(0, 0, stripWidth, 1);
                shader.safeGetUniform("uCriticalBounds").set(x, y, searchRadius, 1f);
                shader.safeGetUniform("uTraceSize").set((float) stripWidth, 1f);
                drawTriangle(shader);
            }

            int rows = arc.rows();
            criticalLight.bind(stripWidth, rows, attachmentLight, skyRays ? attachmentExit : -1);
            RenderSystem.viewport(0, 0, stripWidth, rows);
            shader.setSampler("CriticalRadii", criticalRadii.light);
            shader.safeGetUniform("uCriticalArc").set(arc.inner(), arc.outer());
            shader.safeGetUniform("uCriticalBounds").set(x, y, span, skyRays ? 4f : 2f);
            shader.safeGetUniform("uTraceSize").set((float) stripWidth, (float) rows);
            drawTriangle(shader);
        }
        criticalRadiiReady = enabled;
        composite.setSampler("CriticalRadii", enabled ? criticalRadii.light : null);
        composite.setSampler("CriticalLight", enabled ? criticalLight.light : null);
        composite.safeGetUniform("uCriticalBounds").set(x, y, span, enabled ? (skyRays ? 2f : 1f) : 0f);
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
        if (exteriorWeight >= 1f || frame.usesWhiteWorldPrimary()) {
            transferReady = false;
            return;
        }
        double scale = sampling.transferScale();
        if (scale == 0) {
            transferReady = false;
            return;
        }
        if (reuseBlackField && transferReady) {
            composite.setSampler("PixelTransfer", transfer.light);
            composite.setTransferPass(2);
            return;
        }
        transferReady = false;
        int w = stableTransferExtent((int) Math.ceil(scale * frame.rectW), transfer.width);
        int h = stableTransferExtent((int) Math.ceil(scale * frame.rectH), transfer.height);
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
        transferReady = true;
    }

    private static int stableTransferExtent(int requested, int allocated) {
        return Math.abs(requested - allocated) <= Math.min(8, requested / 32) ? allocated : requested;
    }

    private void drawTriangle(ShaderInstance program) {
        if (triangle == null) {
            var buffer = Tesselator.getInstance().getBuilder();
            buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
            buffer.vertex(-1, -1, 0).endVertex();
            buffer.vertex(3, -1, 0).endVertex();
            buffer.vertex(-1, 3, 0).endVertex();
            triangle = new VertexBuffer(VertexBuffer.Usage.STATIC);
            triangle.bind();
            triangle.upload(buffer.end());
        } else {
            triangle.bind();
        }
        try {
            triangle.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), program);
        } finally {
            VertexBuffer.unbind();
        }
    }

    void beginFrame(BlackHoleLensRenderer.Frame frame) throws IOException {
        cpuStartNanos = System.nanoTime();
        KerrControls.Frame settings = frame.kerr;
        double distance = 2 * frame.dist / frame.rsBlocks;
        exteriorWeight = frame.whiteDemo ? 0f : KerrSampling.exteriorWeight(settings.quality(), frame.white.mix());
        if (exteriorWeight < 1f && shader == null) loadShader();
        if (timer == 0 && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) timer = glGenQueries();
        boolean adaptive = settings.quality() == Quality.ADAPTIVE && exteriorWeight < 1f;
        double shadowFraction = KerrSampling.shadowFraction(distance, frame.tanHalfFov);
        double budgetMs = 5.0 + 2.0 * frame.white.mix()
                - 2.0 * Math.max(KerrSampling.nearWeight(distance), shadowFraction * shadowFraction);
        updateTimer(adaptive, budgetMs);
        if (exteriorWeight < 1f) {
            double area = frame.rectW * frame.rectH;
            sampling = KerrSampling.plan(settings.quality(), settings.renderScale(), adaptiveScale,
                    area, shadowFraction, distance, frame.screenR, frame.white.mix());
            if (frame.usesWhiteWorldPrimary()) sampling = sampling.forWhiteHost(settings.quality(), area, distance);
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
        if (cpuStartNanos != 0) {
            cpuMilliseconds = (System.nanoTime() - cpuStartNanos) * 1e-6;
            cpuStartNanos = 0;
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
        return String.format(Locale.ROOT, "%s gpu=%s cpu=%.2fms frames=%d%s%s%s%s", path,
                gpuMilliseconds == 0 ? "pending" : String.format(Locale.ROOT, "%.2fms", gpuMilliseconds),
                cpuMilliseconds, completedPasses, particleAtlasActive ? " +particles" : "",
                whiteDirectReady ? " whiteDirect=" + whiteDirect.active().width + "x" + whiteDirect.active().height : "",
                whiteCacheHits > 0 ? " whiteReuse=" + whiteCacheHits : "",
                blackFieldHits > 0 ? " blackReuse=" + blackFieldHits : "");
    }

    void invalidateHistory() {
        history = null;
        whiteDirectReady = false;
        whiteDirectDivisor = 1;
        whiteReuseFrames = 0;
        criticalRadiiReady = false;
        reuseBlackField = transferReady = false;
    }

    void resetMode() {
        invalidateHistory();
        sample = 0;
        jitterPhase = 0;
        completedPasses = 0;
        whiteCacheHits = 0;
        blackFieldHits = 0;
        whiteDirect.close();
        gpuMilliseconds = cpuMilliseconds = 0;
        adaptiveScale = Quality.ADAPTIVE.scale;
        adaptiveRecoverySamples = 0;
        lastExposureTimestamp = 0;
        hdr.adaptExposure(1, 0, false);
        particleAtlasActive = false;
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
        if (whiteCacheShader != null) whiteCacheShader.close();
        whiteCacheShader = null;
        whiteCacheUnavailable = false;
        if (triangle != null) triangle.close();
        triangle = null;
        for (Target target : targets) target.close();
        transfer.close();
        whiteBloomSource.close();
        whiteOptical.close();
        whiteDirect.close();
        diskDetail.close();
        diskHighlights.close();
        criticalRadii.close();
        criticalLight.close();
        particles.close();
        hdr.close();
        lastExposureTimestamp = 0;
        particleAtlasActive = false;
        width = height = 0;
        sample = jitterPhase = completedPasses = 0;
        whiteCacheHits = 0;
        blackFieldHits = 0;
        if (timer != 0) glDeleteQueries(timer);
        timer = 0;
        timerPending = false;
        gpuMilliseconds = cpuMilliseconds = 0;
        adaptiveScale = Quality.ADAPTIVE.scale;
        adaptiveRecoverySamples = 0;
        invalidateHistory();
    }

    private record History(double[] center, double[] observer, float[] view, float[] proj, float[] axis,
                           int screenW, int screenH, double rs, KerrControls.Frame settings,
                           WhiteHoleRenderState.Frame white, double spin,
                           float x, float y, float w, float h, float jitterX, float jitterY,
                           float[] lensView, float[] equatorialX, int steps, float tolerance, int phase,
                           boolean reusedExit, boolean worldPrimary, boolean whiteSkyRays, KerrSampling sampling,
                           boolean needsDirection, float diskGain, float diskHeat) {
        boolean matchesWhiteHost(BlackHoleLensRenderer.Frame frame, float[] newAxis, float[] newEquatorialX,
                                 int newSteps, float newTolerance) {
            return worldPrimary && screenW == frame.screenW && screenH == frame.screenH
                    && x == (float) frame.rectX0 && y == (float) frame.rectY0
                    && w == (float) frame.rectW && h == (float) frame.rectH
                    && (float) rs == (float) frame.rsBlocks && spin == frame.geometry.spin()
                    && settings.quality() == frame.kerr.quality() && settings.reverse() == frame.kerr.reverse()
                    && steps == newSteps && tolerance == newTolerance
                    && Arrays.equals(center, frame.center) && Arrays.equals(lensView, frame.bhView)
                    && Arrays.equals(proj, frame.proj) && Arrays.equals(axis, newAxis)
                    && Arrays.equals(equatorialX, newEquatorialX) && frame.white.canReuseHostFrame(white);
        }

        boolean matchesBlackCamera(BlackHoleLensRenderer.Frame frame, float[] newAxis, float[] newEquatorialX,
                                   int newSteps, float newTolerance) {
            return white.mix() == 0
                    && screenW == frame.screenW && screenH == frame.screenH
                    && x == (float) frame.rectX0 && y == (float) frame.rectY0
                    && w == (float) frame.rectW && h == (float) frame.rectH
                    && rs == frame.rsBlocks && spin == frame.geometry.spin() && settings.reverse() == frame.kerr.reverse()
                    && settings.f(THICKNESS) == frame.kerr.f(THICKNESS) && settings.f(OUTER_RADIUS) == frame.kerr.f(OUTER_RADIUS)
                    && steps == newSteps && tolerance == newTolerance
                    && Arrays.equals(lensView, frame.bhView) && Arrays.equals(proj, frame.proj)
                    && Arrays.equals(axis, newAxis) && Arrays.equals(equatorialX, newEquatorialX);
        }

        boolean matchesBlackLight(BlackHoleLensRenderer.Frame frame, KerrSampling plan, float gain, boolean activeDisk) {
            var current = frame.kerr;
            return sampling.equals(plan) && settings.quality() == current.quality()
                    && (!activeDisk || settings.clock() == current.clock())
                    && diskGain == gain && diskHeat == frame.diskHeat && settings.diskFade() == current.diskFade()
                    && settings.f(ACCRETION) == current.f(ACCRETION) && settings.f(TEMPERATURE) == current.f(TEMPERATURE)
                    && settings.f(EMISSIVITY) == current.f(EMISSIVITY)
                    && settings.f(EXPOSURE) == current.f(EXPOSURE) && settings.f(BLOOM) == current.f(BLOOM);
        }

        boolean canReproject(BlackHoleLensRenderer.Frame frame, float[] newAxis, KerrControls.Frame current) {
            double translation = Math.abs(observer[0] - frame.camPos[0]) + Math.abs(observer[1] - frame.camPos[1])
                    + Math.abs(observer[2] - frame.camPos[2]);
            return Arrays.equals(center, frame.center) && translation < rs * .002 && Math.abs(rs - frame.rsBlocks) < rs * .001
                    && screenW == frame.screenW && screenH == frame.screenH
                    && axis[0] * newAxis[0] + axis[1] * newAxis[1] + axis[2] * newAxis[2] > 0.90
                    && settings.reverse() == current.reverse() && settings.quality() == current.quality()
                    && frame.white.canReproject(white)
                    && Math.abs(spin - frame.geometry.spin()) < .001
                    && Math.abs(settings.clock() - current.clock()) < 3;
        }
    }

    /** Reuse attachments, not image history, across adjacent adaptive tile sizes. */
    static final class WhiteDirectTargets implements AutoCloseable {
        // One standby RGBA16F + R32F pair at most: 6 MiB.
        private static final long MAX_STANDBY_PIXELS = 512L * 1024;
        private Target active = new Target(false, GL_R32F);
        private Target standby = new Target(false, GL_R32F);

        Target active() {
            return active;
        }

        void bind(int width, int height, int lightLocation, int depthLocation) {
            long pixels = (long) width * height;
            if (pixels > MAX_STANDBY_PIXELS) {
                standby.close();
            } else if (active.framebuffer != 0 && (active.width != width || active.height != height)
                    && (long) active.width * active.height <= MAX_STANDBY_PIXELS) {
                Target previous = active;
                active = standby;
                standby = previous;
            }
            active.bind(width, height, lightLocation, depthLocation);
        }

        @Override
        public void close() {
            active.close();
            standby.close();
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
            if (framebuffer != 0 && width == newWidth && height == newHeight && (exitLocation >= 0) == (exit != 0)) {
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
