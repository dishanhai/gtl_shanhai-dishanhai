package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL43C.*;

/** A compute-driven tracer field sampled in local Kerr coordinates by the volume pass. */
final class WhiteHoleParticleGrid implements AutoCloseable {
    static final int SIDE = 512;
    static final int MAX_PARTICLES = 32_768;
    // Seven aligned vec4/uvec4 fields in the std430 particle structure.
    private static final int PARTICLE_STRIDE = 112;
    private static final Logger LOGGER = LogUtils.getLogger();

    @FunctionalInterface
    interface ShaderSource {
        String load(String name) throws IOException;
    }

    private final ShaderSource source;
    private int atlasProgram, particleProgram;
    private int accumulator, density, particles;
    private int normalizeLocation, seedLocation, tickLocation, countLocation, flowLocation;
    private boolean unavailable;
    private long lastTick = -1, lastSeed;
    private int lastCount;
    private float lastBeta, lastJets, lastHorizon;

    WhiteHoleParticleGrid() {
        this(name -> {
            var id = new ResourceLocation("gt_shanhai", "shaders/whitehole/" + name);
            try (var stream = Minecraft.getInstance().getResourceManager().open(id)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    /** Supply the same shader sources to an isolated OpenGL context during GPU verification. */
    WhiteHoleParticleGrid(ShaderSource source) {
        this.source = source;
    }

    /** Returns the R16F density atlas, or zero when the analytic volume path should be used. */
    int update(double timeSeconds, long seed, float beta, float jets, float horizonRg, int budget) {
        RenderSystem.assertOnRenderThread();
        if (budget <= 0 || unavailable || !GL.getCapabilities().OpenGL43) return 0;
        int count = Math.min(budget, MAX_PARTICLES);
        long tick = RustWhiteHoleRenderer.tick(timeSeconds);
        if (tick == lastTick && seed == lastSeed && count == lastCount && Math.abs(beta - lastBeta) < .025f
                && Math.abs(jets - lastJets) < .025f && Math.abs(horizonRg - lastHorizon) < .025f) {
            return density;
        }

        var original = GlState.capture();
        try {
            if (density == 0) initialize();
            if (RustWhiteHoleRenderer.active()) {
                RustWhiteHoleRenderer.submit(GL.getCapabilities(), atlasProgram, particleProgram, accumulator,
                        density, particles, normalizeLocation, seedLocation, tickLocation, countLocation,
                        flowLocation, seed, tick, count, beta, jets, horizonRg);
            } else {
                submitJava(seed, tick, count, beta, jets, horizonRg);
            }
            lastTick = tick;
            lastSeed = seed;
            lastCount = count;
            lastBeta = beta;
            lastJets = jets;
            lastHorizon = horizonRg;
            return density;
        } catch (IOException | IllegalStateException failure) {
            LOGGER.warn("White-hole particle compute is unavailable. The analytic volume remains active.", failure);
            release();
            unavailable = true;
            return 0;
        } finally {
            original.restore();
        }
    }

    private void submitJava(long seed, long tick, int count, float beta, float jets, float horizonRg) {
        glBindImageTexture(0, accumulator, 0, false, 0, GL_READ_WRITE, GL_R32UI);
        glBindImageTexture(1, density, 0, false, 0, GL_WRITE_ONLY, GL_R16F);
        glUseProgram(atlasProgram);
        glUniform1i(normalizeLocation, 0);
        glDispatchCompute(SIDE / 16, SIDE / 16, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        glUseProgram(particleProgram);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, particles);
        glUniform2ui(seedLocation, (int) seed, (int) (seed >>> 32));
        glUniform1ui(tickLocation, (int) tick);
        glUniform1ui(countLocation, count);
        float normalize = Math.clamp(8192f / count, .25f, 8f);
        glUniform4f(flowLocation, beta, jets, horizonRg, normalize);
        glDispatchCompute((count + 127) / 128, 1, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

        glUseProgram(atlasProgram);
        glUniform1i(normalizeLocation, 1);
        glDispatchCompute(SIDE / 16, SIDE / 16, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private void initialize() throws IOException {
        atlasProgram = program("particle_atlas.comp");
        particleProgram = program("particle_update.comp");
        normalizeLocation = uniform(atlasProgram, "uNormalize");
        seedLocation = uniform(particleProgram, "uSeed");
        tickLocation = uniform(particleProgram, "uTick");
        countLocation = uniform(particleProgram, "uCount");
        flowLocation = uniform(particleProgram, "uFlow");
        particles = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, particles);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) PARTICLE_STRIDE * MAX_PARTICLES, GL_DYNAMIC_DRAW);
        accumulator = texture(GL_R32UI, GL_NEAREST);
        density = texture(GL_R16F, GL_LINEAR);
    }

    private int program(String resource) throws IOException {
        String code = source.load(resource);
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        try {
            glShaderSource(shader, code);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                throw new IllegalStateException(resource + ": " + glGetShaderInfoLog(shader));
            }
            int program = glCreateProgram();
            glAttachShader(program, shader);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                throw new IllegalStateException(resource + ": " + log);
            }
            glDetachShader(program, shader);
            return program;
        } finally {
            glDeleteShader(shader);
        }
    }

    private static int uniform(int program, String name) {
        int location = glGetUniformLocation(program, name);
        if (location < 0) throw new IllegalStateException("Missing compute uniform " + name);
        return location;
    }

    private static int texture(int format, int filter) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, format, SIDE, SIDE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        release();
        unavailable = false;
    }

    private void release() {
        if (atlasProgram != 0) glDeleteProgram(atlasProgram);
        if (particleProgram != 0) glDeleteProgram(particleProgram);
        if (particles != 0) glDeleteBuffers(particles);
        if (accumulator != 0) glDeleteTextures(accumulator);
        if (density != 0) glDeleteTextures(density);
        atlasProgram = particleProgram = particles = accumulator = density = 0;
        lastTick = -1;
    }

    private record ImageBinding(int name, int level, boolean layered, int layer, int access, int format) {
        static ImageBinding capture(int unit) {
            return new ImageBinding(glGetIntegeri(GL_IMAGE_BINDING_NAME, unit),
                    glGetIntegeri(GL_IMAGE_BINDING_LEVEL, unit), glGetIntegeri(GL_IMAGE_BINDING_LAYERED, unit) != 0,
                    glGetIntegeri(GL_IMAGE_BINDING_LAYER, unit), glGetIntegeri(GL_IMAGE_BINDING_ACCESS, unit),
                    glGetIntegeri(GL_IMAGE_BINDING_FORMAT, unit));
        }

        void restore(int unit) {
            glBindImageTexture(unit, name, level, layered, layer, access, format);
        }
    }

    private record GlState(int program, int texture, int storageBuffer, int indexedBuffer, long offset, long size,
                           ImageBinding image0, ImageBinding image1) {
        static GlState capture() {
            return new GlState(glGetInteger(GL_CURRENT_PROGRAM), glGetInteger(GL_TEXTURE_BINDING_2D),
                    glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING), glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0),
                    glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0), glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0),
                    ImageBinding.capture(0), ImageBinding.capture(1));
        }

        void restore() {
            if (size > 0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, indexedBuffer, offset, size);
            else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, indexedBuffer);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, storageBuffer);
            image0.restore(0);
            image1.restore(1);
            glBindTexture(GL_TEXTURE_2D, texture);
            glUseProgram(program);
        }
    }
}
