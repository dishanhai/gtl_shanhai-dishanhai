package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL43C.*;

/** GL resources and Minecraft shader inputs; Rust owns queue sizing and dispatch. */
final class WhiteHoleSparseRepair implements AutoCloseable {
    private static final int ROW = 1024;
    private static final int[] RESET = {0, 0, 1, 1};
    private static final String[] INCLUDES = {
            "guixu_kerr.glsl", "guixu_white_primary.glsl", "guixu_white_cache_depth.glsl"
    };
    private static final String[] NAMES = {
            "uInvProj", "uBhView", "uDiskNormal", "uEquatorialX", "uScreenSize", "uRs",
            "uKerrParameters", "uDiskPhysics", "uIntegration", "uWhiteHole", "uWhiteTime",
            "uWhiteStyle", "uWhiteExtraBursts0", "uWhiteExtraBursts1", "uWhiteParticleGain",
            "uWhiteSceneDepth", "uBgRect", "uWhiteCacheRect"
    };
    private static final int[] TYPES = {16, 3, 3, 3, 2, 1, 4, 4, 2, 4, 4, 4, 4, 4, 1, 1, 4, 4};

    private final WhiteHoleParticleGrid.ShaderSource source;
    private final float[] value = new float[16];
    private final int[] inputs = new int[NAMES.length];
    private Program classify, integrate;
    private int sourceId, width, height, jobBuffer, lightCapacity, indexMap, light;
    private boolean unavailable;

    WhiteHoleSparseRepair() {
        this(name -> {
            var resource = new ResourceLocation("gt_shanhai", "shaders/" + name);
            try (var in = Minecraft.getInstance().getResourceManager().open(resource)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    WhiteHoleSparseRepair(WhiteHoleParticleGrid.ShaderSource source) {
        this.source = source;
    }

    int indexMap() { return indexMap; }
    int light() { return light; }

    boolean repair(int from, int w, int h, int depth, int cachedDepth, int particles) {
        int capacity = RustWhiteHoleRenderer.sparseCapacity(w, h);
        if (unavailable || capacity == 0 || !GL.getCapabilities().OpenGL43) return false;
        var before = State.capture();
        try {
            if (classify == null) {
                String text = source.load("whitehole/white_repair.comp");
                for (String include : INCLUDES) {
                    text = text.replace("#moj_import <gt_shanhai:" + include + ">", source.load("include/" + include));
                }
                classify = build(text.replace("#version 430", "#version 430\n#define CLASSIFY"));
                integrate = build(text);
            }
            if (sourceId != from) {
                for (int i = 0; i < inputs.length; i++) inputs[i] = glGetUniformLocation(from, NAMES[i]);
                for (int i = 0; i < inputs.length; i++) {
                    if (inputs[i] < 0 && (classify.locations[i] >= 0 || integrate.locations[i] >= 0)) {
                        throw new IllegalStateException("Missing white-hole compositor uniform " + NAMES[i]);
                    }
                }
                sourceId = from;
            }
            glActiveTexture(GL_TEXTURE0);
            // Screen-space bounds change by individual pixels while orbiting.
            // Match the rounded storage target, not the current dispatch rectangle.
            if (w > width || h > height || w < width / 2 || h < height / 2) {
                if (indexMap != 0) glDeleteTextures(indexMap);
                width = (w + 63) & ~63;
                height = (h + 63) & ~63;
                indexMap = texture(GL_R32F, width, height);
            }
            if (capacity > lightCapacity) {
                if (light != 0) glDeleteTextures(light);
                light = texture(GL_RGBA32F, ROW, capacity / ROW);
                if (jobBuffer == 0) jobBuffer = glGenBuffers();
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, jobBuffer);
                glBufferData(GL_SHADER_STORAGE_BUFFER, 16L + 4L * capacity, GL_DYNAMIC_DRAW);
                lightCapacity = capacity;
            }
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, jobBuffer);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, RESET);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, jobBuffer);
            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, jobBuffer);
            bindTexture(0, depth);
            bindTexture(1, cachedDepth);
            bindTexture(2, particles);
            upload(from);
            RustWhiteHoleRenderer.repair(GL.getCapabilities(), classify.id, integrate.id, indexMap, light, w, h);
            return true;
        } catch (IOException | IllegalStateException failure) {
            LogUtils.getLogger().warn("White-hole sparse repair unavailable; preserving inline depth integration", failure);
            close();
            unavailable = true;
            return false;
        } finally {
            before.restore();
        }
    }

    private static void bindTexture(int unit, int id) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    private void upload(int source) {
        glProgramUniform1i(classify.id, classify.depth, 0);
        glProgramUniform1i(classify.id, classify.cache, 1);
        glProgramUniform1i(integrate.id, integrate.depth, 0);
        glProgramUniform1i(integrate.id, integrate.particles, 2);
        for (int i = 0; i < NAMES.length; i++) {
            int a = classify.locations[i], b = integrate.locations[i];
            if (a < 0 && b < 0) continue;
            glGetUniformfv(source, inputs[i], value);
            if (a >= 0) uniform(classify.id, a, TYPES[i]);
            if (b >= 0) uniform(integrate.id, b, TYPES[i]);
        }
    }

    private void uniform(int program, int loc, int type) {
        switch (type) {
            case 1 -> glProgramUniform1f(program, loc, value[0]);
            case 2 -> glProgramUniform2f(program, loc, value[0], value[1]);
            case 3 -> glProgramUniform3f(program, loc, value[0], value[1], value[2]);
            case 4 -> glProgramUniform4f(program, loc, value[0], value[1], value[2], value[3]);
            case 16 -> glProgramUniformMatrix4fv(program, loc, false, value);
            default -> throw new IllegalStateException("Unknown white-hole uniform type");
        }
    }

    private static int texture(int format, int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return id;
    }

    private static Program build(String text) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        try {
            glShaderSource(shader, text);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(shader));
            int id = glCreateProgram();
            glAttachShader(id, shader);
            glLinkProgram(id);
            if (glGetProgrami(id, GL_LINK_STATUS) == 0) {
                String log = glGetProgramInfoLog(id);
                glDeleteProgram(id);
                throw new IllegalStateException(log);
            }
            int[] locations = new int[NAMES.length];
            for (int i = 0; i < locations.length; i++) locations[i] = glGetUniformLocation(id, NAMES[i]);
            return new Program(id, locations, glGetUniformLocation(id, "SceneDepth"),
                    glGetUniformLocation(id, "WhiteDirectDepth"), glGetUniformLocation(id, "WhiteParticleGrid"));
        } finally {
            glDeleteShader(shader);
        }
    }

    @Override
    public void close() {
        if (classify != null) glDeleteProgram(classify.id);
        if (integrate != null) glDeleteProgram(integrate.id);
        if (jobBuffer != 0) glDeleteBuffers(jobBuffer);
        if (indexMap != 0) glDeleteTextures(indexMap);
        if (light != 0) glDeleteTextures(light);
        classify = integrate = null;
        sourceId = width = height = jobBuffer = lightCapacity = indexMap = light = 0;
        unavailable = false;
    }

    private record Program(int id, int[] locations, int depth, int cache, int particles) {}

    private record State(int program, int activeTexture, int t0, int t1, int t2, int indirect,
                         int storage, int indexed, long offset, long size, int image, int level,
                         boolean layered, int layer, int access, int format) {
        static State capture() {
            int active = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0);
            int t0 = glGetInteger(GL_TEXTURE_BINDING_2D);
            glActiveTexture(GL_TEXTURE1);
            int t1 = glGetInteger(GL_TEXTURE_BINDING_2D);
            glActiveTexture(GL_TEXTURE2);
            int t2 = glGetInteger(GL_TEXTURE_BINDING_2D);
            return new State(glGetInteger(GL_CURRENT_PROGRAM), active, t0, t1, t2,
                    glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING), glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING),
                    glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0), glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0),
                    glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0), glGetIntegeri(GL_IMAGE_BINDING_NAME, 0),
                    glGetIntegeri(GL_IMAGE_BINDING_LEVEL, 0), glGetIntegeri(GL_IMAGE_BINDING_LAYERED, 0) != 0,
                    glGetIntegeri(GL_IMAGE_BINDING_LAYER, 0), glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 0),
                    glGetIntegeri(GL_IMAGE_BINDING_FORMAT, 0));
        }

        void restore() {
            glBindImageTexture(0, image, level, layered, layer, access, format);
            if (size > 0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, indexed, offset, size);
            else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, indexed);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, storage);
            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, indirect);
            bindTexture(0, t0);
            bindTexture(1, t1);
            bindTexture(2, t2);
            glActiveTexture(activeTexture);
            glUseProgram(program);
        }
    }
}
