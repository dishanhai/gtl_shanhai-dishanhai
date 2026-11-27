package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL41C.glGetFloati_v;
import static org.lwjgl.opengl.GL41C.glViewportIndexedf;

/** Exposed floating-point highlights sampled at several scales by the white-hole compositor. */
final class CompactHdrPostProcessing implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_LOD = 6;
    private static final int METER_TEXEL_BYTES = 4 * Short.BYTES;
    private static final int METER_CAPACITY = 8 * 8 * METER_TEXEL_BYTES;
    private static final double METER_INTERVAL = .16;

    @FunctionalInterface
    interface ShaderSource {
        String load(String name) throws IOException;
    }

    private final ShaderSource source;
    private int program, framebuffer, vao, highlights;
    private int lastLevel, reductionLevel, width, height, exposureLocation, meterLocation;
    private boolean unavailable;
    private boolean exposureEnabled;
    private int meterBuffer, meterTexels;
    private long meterFence;
    private double meterDelay, exposureScale = 1, targetExposure = 1;

    CompactHdrPostProcessing() {
        this(name -> {
            var path = new ResourceLocation("gt_shanhai", "shaders/post/bright_pass." + name);
            try (var stream = Minecraft.getInstance().getResourceManager().open(path)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    /** Offline probe supplies the production shader files without booting Minecraft. */
    CompactHdrPostProcessing(ShaderSource source) {
        this.source = source;
    }

    int lastLevel() {
        return lastLevel;
    }

    /** Nonblocking, biasable exposure based on the last completed light-field measurement. */
    float adaptExposure(float manual, double realDeltaSeconds, boolean enabled) {
        RenderSystem.assertOnRenderThread();
        if (!enabled || unavailable) {
            if (exposureEnabled) releaseMeter();
            return manual;
        }
        exposureEnabled = true;
        double elapsed = Double.isFinite(realDeltaSeconds) ? Math.clamp(realDeltaSeconds, 0.0, .25) : 0;
        meterDelay = Math.max(0, meterDelay - elapsed);
        pollMeter();
        double response = targetExposure < exposureScale ? .24 : .86;
        exposureScale += (targetExposure - exposureScale) * -Math.expm1(-elapsed / response);
        return manual * (float) exposureScale;
    }

    /** The caller disables depth, culling and blending for the fullscreen pass. */
    int draw(int lightTexture, int lightWidth, int lightHeight, float exposure) {
        return draw(lightTexture, lightTexture, lightWidth, lightHeight, exposure);
    }

    /** Meter the original light field even when the bloom pass has a separate source. */
    int draw(int lightTexture, int bloomSource, int lightWidth, int lightHeight, float exposure) {
        RenderSystem.assertOnRenderThread();
        if (unavailable || lightTexture == 0 || lightWidth <= 0 || lightHeight <= 0 || exposure <= 0) return 0;
        int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousActive = glGetInteger(GL_ACTIVE_TEXTURE);
        var capabilities = GL.getCapabilities();
        boolean indexedViewport = capabilities.OpenGL41 || capabilities.GL_ARB_viewport_array;
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean measure = exposureEnabled && meterFence == 0 && meterDelay <= 0;
        int previousMeterTexture = 0;
        if (measure) {
            glActiveTexture(GL_TEXTURE1);
            previousMeterTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
            glBindTexture(GL_TEXTURE_2D, lightTexture);
        }
        glActiveTexture(GL_TEXTURE0);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        try (var stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocFloat(4);
            var mask = stack.malloc(4);
            if (indexedViewport) glGetFloati_v(GL_VIEWPORT, 0, viewport);
            else glGetFloatv(GL_VIEWPORT, viewport);
            glGetBooleani_v(GL_COLOR_WRITEMASK, 0, mask);
            boolean red = mask.get(0) != 0, green = mask.get(1) != 0, blue = mask.get(2) != 0, alpha = mask.get(3) != 0;
            boolean partialMask = !(red && green && blue && alpha);
            try {
                if (program == 0) initialize();
                int targetWidth = (lightWidth + 1) / 2, targetHeight = (lightHeight + 1) / 2;
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
                if (width != targetWidth || height != targetHeight) resize(targetWidth, targetHeight);
                if (indexedViewport) glViewportIndexedf(0, 0, 0, width, height);
                else glViewport(0, 0, width, height);
                if (scissor) {
                    if (indexedViewport) glDisablei(GL_SCISSOR_TEST, 0);
                    else glDisable(GL_SCISSOR_TEST);
                }
                if (partialMask) glColorMaski(0, true, true, true, true);
                glBindVertexArray(vao);
                glUseProgram(program);
                glUniform1f(exposureLocation, exposure);
                glUniform1i(meterLocation, measure ? 1 : 0);
                glBindTexture(GL_TEXTURE_2D, bloomSource);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glBindTexture(GL_TEXTURE_2D, highlights);
                if (lastLevel > 0) glGenerateMipmap(GL_TEXTURE_2D);
                if (measure) enqueueMeter();
                return highlights;
            } catch (IOException | IllegalStateException failure) {
                LOGGER.warn("White-hole bloom pyramid unavailable. Using direct composite bloom.", failure);
                release();
                unavailable = true;
                return 0;
            } finally {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                if (indexedViewport) {
                    glViewportIndexedf(0, viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
                } else {
                    glViewport((int) viewport.get(0), (int) viewport.get(1), (int) viewport.get(2), (int) viewport.get(3));
                }
                if (scissor) {
                    if (indexedViewport) glEnablei(GL_SCISSOR_TEST, 0);
                    else glEnable(GL_SCISSOR_TEST);
                }
                if (partialMask) glColorMaski(0, red, green, blue, alpha);
                glUseProgram(previousProgram);
                glBindVertexArray(previousVao);
                glBindTexture(GL_TEXTURE_2D, previousTexture);
                if (measure) {
                    glActiveTexture(GL_TEXTURE1);
                    glBindTexture(GL_TEXTURE_2D, previousMeterTexture);
                }
                glActiveTexture(previousActive);
            }
        }
    }

    private void initialize() throws IOException {
        int vertex = compile(GL_VERTEX_SHADER, "vsh");
        try {
            int fragment = compile(GL_FRAGMENT_SHADER, "fsh");
            try {
                program = glCreateProgram();
                glAttachShader(program, vertex);
                glAttachShader(program, fragment);
                glBindFragDataLocation(program, 0, "outBright");
                glLinkProgram(program);
                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                    throw new IllegalStateException(glGetProgramInfoLog(program));
                }
                glDetachShader(program, vertex);
                glDetachShader(program, fragment);
            } finally {
                glDeleteShader(fragment);
            }
        } finally {
            glDeleteShader(vertex);
        }
        int lightLocation = glGetUniformLocation(program, "uLight");
        int meterLightLocation = glGetUniformLocation(program, "uMeterLight");
        exposureLocation = glGetUniformLocation(program, "uExposure");
        meterLocation = glGetUniformLocation(program, "uMeter");
        if (lightLocation < 0 || meterLightLocation < 0 || exposureLocation < 0 || meterLocation < 0) {
            throw new IllegalStateException("Missing bright-pass uniform");
        }
        glUseProgram(program);
        glUniform1i(lightLocation, 0);
        glUniform1i(meterLightLocation, 1);
        vao = glGenVertexArrays();
        framebuffer = glGenFramebuffers();
        highlights = glGenTextures();
    }

    private int compile(int type, String extension) throws IOException {
        String code = source.load(extension);
        int shader = glCreateShader(type);
        glShaderSource(shader, code);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("bright_pass." + extension + ": " + error);
        }
        return shader;
    }

    private void resize(int targetWidth, int targetHeight) {
        width = targetWidth;
        height = targetHeight;
        int levelsToSingleTexel = 31 - Integer.numberOfLeadingZeros(Math.max(width, height));
        lastLevel = Math.min(MAX_LOD, levelsToSingleTexel);
        reductionLevel = Math.max(0, levelsToSingleTexel - 2);
        int allocatedLevel = Math.max(lastLevel, reductionLevel);
        glBindTexture(GL_TEXTURE_2D, highlights);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, allocatedLevel);
        int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (unpackBuffer != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        try {
            for (int level = 0; level <= allocatedLevel; level++) {
                glTexImage2D(GL_TEXTURE_2D, level, GL_RGBA16F, Math.max(1, width >> level),
                        Math.max(1, height >> level), 0, GL_RGBA, GL_HALF_FLOAT, 0L);
            }
        } finally {
            if (unpackBuffer != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        }
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, highlights, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Incomplete white-hole HDR framebuffer");
        }
    }

    private void enqueueMeter() {
        int previousBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = glGetInteger(GL_PACK_ALIGNMENT), rowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int skipPixels = glGetInteger(GL_PACK_SKIP_PIXELS), skipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int swapBytes = glGetInteger(GL_PACK_SWAP_BYTES);
        try {
            boolean allocate = meterBuffer == 0;
            if (allocate) meterBuffer = glGenBuffers();
            glBindBuffer(GL_PIXEL_PACK_BUFFER, meterBuffer);
            if (allocate) glBufferData(GL_PIXEL_PACK_BUFFER, METER_CAPACITY, GL_STREAM_READ);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SWAP_BYTES, GL_FALSE);
            meterTexels = Math.max(1, width >> reductionLevel) * Math.max(1, height >> reductionLevel);
            glGetTexImage(GL_TEXTURE_2D, reductionLevel, GL_RGBA, GL_HALF_FLOAT, 0L);
            meterFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (meterFence != 0) glFlush();
            meterDelay = METER_INTERVAL;
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, alignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
            glPixelStorei(GL_PACK_SKIP_ROWS, skipRows);
            glPixelStorei(GL_PACK_SWAP_BYTES, swapBytes);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, previousBuffer);
        }
    }

    private void pollMeter() {
        if (meterFence == 0) return;
        int readiness = glClientWaitSync(meterFence, 0, 0);
        if (readiness == GL_TIMEOUT_EXPIRED) return;
        glDeleteSync(meterFence);
        meterFence = 0;
        if (readiness == GL_WAIT_FAILED) return;

        int previousBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, meterBuffer);
        try {
            var samples = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (long) meterTexels * METER_TEXEL_BYTES, GL_MAP_READ_BIT);
            if (samples == null) return;
            samples.order(ByteOrder.nativeOrder());
            double sum = 0, peak = Double.NEGATIVE_INFINITY;
            int valid = 0;
            for (int i = 0; i < meterTexels; i++) {
                float logged = Float.float16ToFloat(samples.getShort(i * METER_TEXEL_BYTES + 3 * Short.BYTES));
                if (!Float.isFinite(logged)) continue;
                sum += logged;
                peak = Math.max(peak, logged);
                valid++;
            }
            if (glUnmapBuffer(GL_PIXEL_PACK_BUFFER) && valid > 0) {
                double luminance = Math.pow(2, sum / valid);
                double brightestPatch = Math.pow(2, peak);
                double midpoint = Math.pow(.36 / luminance, .62);
                targetExposure = Math.clamp(Math.min(midpoint, 1.75 / brightestPatch), .48, 1.85);
            }
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, previousBuffer);
        }
    }

    private void releaseMeter() {
        if (meterFence != 0) glDeleteSync(meterFence);
        if (meterBuffer != 0) glDeleteBuffers(meterBuffer);
        meterFence = 0;
        meterBuffer = meterTexels = 0;
        meterDelay = 0;
        targetExposure = exposureScale = 1;
        exposureEnabled = false;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        release();
        unavailable = false;
    }

    private void release() {
        releaseMeter();
        if (highlights != 0) glDeleteTextures(highlights);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (vao != 0) glDeleteVertexArrays(vao);
        if (program != 0) glDeleteProgram(program);
        program = framebuffer = vao = highlights = width = height = lastLevel = reductionLevel = 0;
    }
}
