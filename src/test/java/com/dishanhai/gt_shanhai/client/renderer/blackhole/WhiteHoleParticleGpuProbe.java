package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Runs the optional OpenGL 4.3 tracer path against real resources and checks shared GL state. */
public final class WhiteHoleParticleGpuProbe {
    private static final Path SHADERS = Path.of("src/main/resources/assets/gt_shanhai/shaders/whitehole");
    private static final long SEED = 910470818192L;

    private WhiteHoleParticleGpuProbe() {}

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        try {
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            long window = glfwCreateWindow(128, 128, "White-hole particle GPU probe", 0, 0);
            if (window == 0) throw new IllegalStateException("OpenGL 4.3 context creation failed");
            try {
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                RenderSystem.initRenderThread();
                System.out.printf("%s / %s%n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
                boolean rust = RustWhiteHoleRenderer.active();
                require(System.getProperty("gtsh.whitehole.rustLibrary") == null || rust,
                        "The requested Rust backend did not load");
                System.out.println("Particle backend: " + (rust ? "Rust" : "Java"));
                if (rust) verifyNativeRaster();
                run();
            } finally {
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    private static void verifyNativeRaster() throws Exception {
        var adaptive = com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality.ADAPTIVE;
        require(RustWhiteHoleRenderer.directDivisor(adaptive, 480_000, 1) == 2
                && RustWhiteHoleRenderer.directDivisor(adaptive, 450_000, 2) == 2
                && RustWhiteHoleRenderer.cacheDirect(true, 100, 2100, 2000)
                && !RustWhiteHoleRenderer.cacheDirect(false, 100, 2100, 2000)
                && RustWhiteHoleRenderer.canReuse(56_000_000, 2)
                && !RustWhiteHoleRenderer.canReuse(56_000_001, 2), "Native frame planner mismatch");

        int vao = glGenVertexArrays();
        int positions = glGenBuffers();
        int program = glCreateProgram();
        int vertex = glCreateShader(GL_VERTEX_SHADER);
        int fragment = glCreateShader(GL_FRAGMENT_SHADER);
        try {
            glShaderSource(vertex, Files.readString(SHADERS.resolve("../core/guixu_blackhole.vsh")));
            glShaderSource(fragment, "#version 150\nout vec4 color; void main() { color=vec4(.125,.5,.875,1); }");
            glCompileShader(vertex);
            glCompileShader(fragment);
            require(glGetShaderi(vertex, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(vertex));
            require(glGetShaderi(fragment, GL_COMPILE_STATUS) != 0, glGetShaderInfoLog(fragment));
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glBindAttribLocation(program, 0, "Position");
            glLinkProgram(program);
            require(glGetProgrami(program, GL_LINK_STATUS) != 0, glGetProgramInfoLog(program));
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, positions);
            glBufferData(GL_ARRAY_BUFFER, new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0L);
            glEnableVertexAttribArray(0);
            glViewport(0, 0, 128, 128);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            var expected = BufferUtils.createByteBuffer(128 * 128 * 4);
            glReadPixels(0, 0, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, expected);
            glClear(GL_COLOR_BUFFER_BIT);
            RustWhiteHoleRenderer.draw(GL.getCapabilities().glDrawArrays);
            var actual = BufferUtils.createByteBuffer(128 * 128 * 4);
            glReadPixels(0, 0, 128, 128, GL_RGBA, GL_UNSIGNED_BYTE, actual);
            require((actual.get(1) & 255) >= 127 && expected.equals(actual), "Native raster differs from Java");
            require(glGetError() == GL_NO_ERROR, "Native raster generated an OpenGL error");
            System.out.println("Native frame planner and fullscreen raster A/B passed");
        } finally {
            glUseProgram(0);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            glDeleteProgram(program);
            glDeleteBuffers(positions);
            glDeleteVertexArrays(vao);
        }
    }

    private static void run() throws Exception {
        int image0 = texture(GL_R32UI);
        int image1 = texture(GL_R16F);
        int alignment = glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        int indexed = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, indexed);
        glBufferData(GL_SHADER_STORAGE_BUFFER, alignment + 256, GL_STATIC_DRAW);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, indexed, alignment, 256);
        int generic = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, generic);
        int previousProgram = sentinelProgram();
        glUseProgram(previousProgram);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, image1);
        glBindImageTexture(0, image0, 0, false, 0, GL_READ_ONLY, GL_R32UI);
        glBindImageTexture(1, image1, 0, false, 0, GL_READ_WRITE, GL_R16F);

        int atlas;
        try (var grid = new WhiteHoleParticleGrid(name -> Files.readString(SHADERS.resolve(name)))) {
            require(grid.update(0, SEED, .46f, .8f, 1.8f, 0) == 0, "A disabled particle budget allocated an atlas");
            atlas = render(grid, 4.25, SEED, .8f, 8192);
            require(glGetInteger(GL_CURRENT_PROGRAM) == previousProgram, "Compute altered the draw program");
            require(glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE4 && glGetInteger(GL_TEXTURE_BINDING_2D) == image1,
                    "Compute altered the active sampler");
            require(glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING) == generic
                    && glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0) == indexed
                    && glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0) == alignment
                    && glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0) == 256, "Compute altered the SSBO range");
            require(glGetIntegeri(GL_IMAGE_BINDING_NAME, 0) == image0
                    && glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 0) == GL_READ_ONLY
                    && glGetIntegeri(GL_IMAGE_BINDING_NAME, 1) == image1
                    && glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 1) == GL_READ_WRITE, "Compute altered an image binding");
            float[] first = read(atlas);
            volume(first);
            System.out.printf("Atlas A/B checksum: %08x%n", Arrays.hashCode(first));
            require(render(grid, 4.259, SEED, .8f, 8192) == atlas, "Same tick replaced the cached texture");
            require(Arrays.equals(first, read(atlas)), "Subtick changes altered the particle state");
            render(grid, 4.75, SEED, .8f, 8192);
            require(!Arrays.equals(first, read(atlas)), "Particles did not evolve across ticks");
            render(grid, 4.25, SEED + 1, .8f, 8192);
            require(!Arrays.equals(first, read(atlas)), "A changed seed produced the same particles");
            render(grid, 4.25, SEED, .8f, 8192);
            require(Arrays.equals(first, read(atlas)), "Revisiting a tick changed deterministic deposits");
            render(grid, 4.25, SEED, 0, 8192);
            float[] withoutJets = read(atlas);
            require(polarDensity(first) > polarDensity(withoutJets) * 1.10,
                    "Polar emitters did not add independent density along the spin axis");

            glBindImageTexture(0, 0, 0, false, 0, GL_READ_ONLY, GL_R32UI);
            glBindImageTexture(1, 0, 0, false, 0, GL_READ_ONLY, GL_R16F);
            try (var independent = new WhiteHoleParticleGrid(name -> Files.readString(SHADERS.resolve(name)))) {
                int second = render(independent, 4.25, SEED, .8f, 8192);
                require(second != atlas && Arrays.equals(first, read(second)),
                        "Independent particle renderer did not reproduce this input");
                require(Arrays.equals(withoutJets, read(atlas)), "Another white hole replaced the first atlas contents");
            }
            require(glGetIntegeri(GL_IMAGE_BINDING_NAME, 0) == 0 && glGetIntegeri(GL_IMAGE_BINDING_NAME, 1) == 0,
                    "Compute did not restore previously empty image bindings");

            for (int count : new int[]{4096, 8192, 16384, 32768}) {
                int timer = glGenQueries();
                int frames = 24;
                render(grid, 8, SEED, .8f, count);
                glBeginQuery(GL_TIME_ELAPSED, timer);
                for (int i = 0; i < frames; i++) render(grid, 9 + i / 29.0, SEED, .8f, count);
                glEndQuery(GL_TIME_ELAPSED);
                double ms = glGetQueryObjectui64(timer, GL_QUERY_RESULT) / (frames * 1e6);
                glDeleteQueries(timer);
                System.out.printf(Locale.ROOT, "%d particles: %.3f ms GPU mean per update (%d ticks)%n", count, ms, frames);
            }
            // Cross dozens of birth/death phases without reallocating the field.
            for (int tick = 0; tick < 720; tick++) {
                require(render(grid, 100_000 + tick / 29.0, SEED, .8f, 8192) == atlas,
                        "Long-running particle updates reallocated the atlas");
            }
            float[] longRunning = read(atlas);
            volume(longRunning);
            require(glGetError() == GL_NO_ERROR, "OpenGL error after compute verification");
        }
        require(!glIsTexture(atlas), "Closing particle resources leaked the density atlas");
        glActiveTexture(GL_TEXTURE0);
        glUseProgram(0);
        glDeleteProgram(previousProgram);
        glDeleteBuffers(generic);
        glDeleteBuffers(indexed);
        glDeleteTextures(image0);
        glDeleteTextures(image1);
    }

    private static int render(WhiteHoleParticleGrid grid, double time, long seed, float jet, int count) {
        int atlas = grid.update(time, seed, .46f, jet, 1.8f, count);
        require(atlas != 0, "Supported compute path did not produce a density atlas");
        return atlas;
    }

    private static int texture(int format) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, format, 4, 4);
        return texture;
    }

    private static int sentinelProgram() {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, "#version 430 core\nlayout(local_size_x=1) in; void main() {}\n");
        glCompileShader(shader);
        require(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE, glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        require(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE, glGetProgramInfoLog(program));
        return program;
    }

    private static float[] read(int texture) {
        int size = WhiteHoleParticleGrid.SIDE;
        var pixels = BufferUtils.createFloatBuffer(size * size);
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, texture);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RED, GL_FLOAT, pixels);
        glBindTexture(GL_TEXTURE_2D, previous);
        float[] values = new float[size * size];
        pixels.get(values);
        return values;
    }

    private static void volume(float[] pixels) {
        int inhabited = 0, activeSlices = 0;
        float peak = 0;
        for (int z = 0; z < 64; z++) {
            boolean active = false;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                float value = voxel(pixels, x, y, z);
                require(Float.isFinite(value) && value >= 0 && value <= 1, "Shader produced an invalid voxel");
                if (value > .002f) {
                    active = true;
                    inhabited++;
                }
                peak = Math.max(peak, value);
            }
            if (active) activeSlices++;
        }
        require(activeSlices >= 24 && inhabited >= 500 && peak > .1, "Atlas did not contain volumetric particles");
        System.out.printf(Locale.ROOT, "%d lit voxels across %d slices, peak %.4f%n", inhabited, activeSlices, peak);
    }

    private static double polarDensity(float[] pixels) {
        double total = 0;
        for (int z = 0; z < 64; z++) {
            if (Math.abs(z - 31.5) < 8) continue;
            for (int y = 30; y <= 33; y++) for (int x = 30; x <= 33; x++) total += voxel(pixels, x, y, z);
        }
        return total;
    }

    private static float voxel(float[] pixels, int x, int y, int z) {
        return pixels[((z / 8) * 64 + y) * WhiteHoleParticleGrid.SIDE + (z % 8) * 64 + x];
    }

    private static void require(boolean condition, String problem) {
        if (!condition) throw new AssertionError(problem);
    }
}
