package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;

/** Exercises the bloom pyramid and shared rendering state in an isolated OpenGL context. */
public final class WhiteHoleBloomGpuProbe {
    private static final Path SHADERS = Path.of("src/main/resources/assets/gt_shanhai/shaders/whitehole");
    private static final int W = 57, H = 43;

    private WhiteHoleBloomGpuProbe() {}

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        try {
            boolean legacy = args.length > 0 && args[0].equals("legacy");
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, legacy ? 3 : 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, legacy ? 2 : 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            long window = glfwCreateWindow(128, 128, "White-hole bloom GPU probe", 0, 0);
            if (window == 0) throw new IllegalStateException("OpenGL context creation failed");
            try {
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                RenderSystem.initRenderThread();
                System.out.printf("%s / %s%n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
                run();
            } finally {
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    private static void run() throws Exception {
        int input = highlightsInput(W, H);
        int resized = highlightsInput(128, 96);
        int previousProgram = program();
        glUseProgram(previousProgram);
        int previousVao = glGenVertexArrays();
        glBindVertexArray(previousVao);
        int previousDraw = glGenFramebuffers(), previousRead = glGenFramebuffers();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
        int previousUnpack = glGenBuffers();
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previousUnpack);
        glBufferData(GL_PIXEL_UNPACK_BUFFER, 16, GL_STATIC_DRAW);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, input);
        glViewport(2, 4, 57, 53);
        glScissor(0, 0, 1, 1);
        glEnable(GL_SCISSOR_TEST);
        glColorMaski(0, true, false, true, false);
        glColorMaski(1, false, true, false, true);
        boolean indexedViewport = GL.getCapabilities().OpenGL41 || GL.getCapabilities().GL_ARB_viewport_array;
        if (indexedViewport) {
            glViewportIndexedf(1, 5.5f, 6.5f, 31.5f, 39.5f);
            glDisablei(GL_SCISSOR_TEST, 1);
        }

        int highlights;
        try (var bloom = new CompactHdrPostProcessing(extension -> Files.readString(SHADERS.resolve("bright_pass." + extension)))) {
            highlights = bloom.draw(input, W, H, 1);
            require(highlights != 0, "Bloom failed to create a floating-point pyramid");
            require(glGetInteger(GL_ACTIVE_TEXTURE) == GL_TEXTURE3 && glGetInteger(GL_TEXTURE_BINDING_2D) == input,
                    "Bloom altered the active texture unit");
            glActiveTexture(GL_TEXTURE0);
            require(glGetInteger(GL_TEXTURE_BINDING_2D) == resized, "Bloom altered unit 0 sampler binding");
            glActiveTexture(GL_TEXTURE3);
            require(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == previousDraw
                    && glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == previousRead,
                    "Bloom altered the renderer framebuffer bindings");
            require(glGetInteger(GL_VERTEX_ARRAY_BINDING) == previousVao
                    && glGetInteger(GL_CURRENT_PROGRAM) == previousProgram,
                    "Bloom altered the previous program or vertex array");
            require(glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) == previousUnpack,
                    "Bloom altered the renderer's pixel unpack buffer");
            int[] viewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, viewport);
            require(viewport[0] == 2 && viewport[1] == 4 && viewport[2] == 57 && viewport[3] == 53,
                    "Bloom did not restore the primary viewport");
            if (indexedViewport) {
                var second = BufferUtils.createFloatBuffer(4);
                glGetFloati_v(GL_VIEWPORT, 1, second);
                require(Math.abs(second.get(0) - 5.5f) < .01f && Math.abs(second.get(3) - 39.5f) < .01f,
                        "Bloom altered a secondary viewport");
                require(!glIsEnabledi(GL_SCISSOR_TEST, 1), "Bloom altered secondary scissoring");
            }
            require(glIsEnabled(GL_SCISSOR_TEST), "Bloom disabled the previous scissor state");
            validateMask(0, true, false, true, false);
            validateMask(1, false, true, false, true);

            int baseWidth = (W + 1) / 2;
            float[] base = read(highlights, 0);
            require(base[0] == 0, "Sub-threshold field leaked into the bloom pyramid");
            near(.05568, base[(4 * baseWidth + 3) * 4], .001, "Soft-knee intensity");
            int bright = (11 * baseWidth + 12) * 4;
            near(2, base[bright], .002, "Single input pixel preserves its HDR highlight");
            near(1, base[bright + 1], .002, "Soft threshold preserves the highlight color");
            float coarse = read(highlights, bloom.lastLevel())[0];
            require(coarse > 0 && coarse < .1, "Coarse mip levels did not spread the HDR contribution");
            System.out.printf(Locale.ROOT, "Half-res bright pass %.4f, soft-knee %.4f, coarsest mip %.5f%n",
                    base[bright], base[(4 * baseWidth + 3) * 4], coarse);

            require(bloom.draw(input, W, H, .04f) == highlights, "Stable input dimensions reallocated the pyramid");
            float[] dimmed = read(highlights, 0);
            for (int color = 0; color < dimmed.length; color += 4) {
                require(dimmed[color] == 0, "Exposure did not affect the linear HDR threshold");
            }
            require(bloom.draw(resized, 128, 96, 1) == highlights && bloom.lastLevel() == 6,
                    "A viewport resize did not update the same pyramid");
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            verifyExposure(bloom);
            if (GL.getCapabilities().OpenGL33) {
                benchmark(bloom);
            }
            require(glGetError() == GL_NO_ERROR, "OpenGL error after HDR post-processing verification");
        }
        require(!glIsTexture(highlights), "Bloom leaked its texture on disposal");
        glDeleteTextures(input);
        glDeleteTextures(resized);
        glDeleteFramebuffers(previousDraw);
        glDeleteFramebuffers(previousRead);
        glDeleteVertexArrays(previousVao);
        glDeleteBuffers(previousUnpack);
        glUseProgram(0);
        glDeleteProgram(previousProgram);
    }

    private static int program() {
        int vertex = shader(GL_VERTEX_SHADER, "void main() { gl_Position = vec4(0.0); }");
        int fragment = shader(GL_FRAGMENT_SHADER, "out vec4 frag; void main() { frag = vec4(1.0); }");
        int id = glCreateProgram();
        glAttachShader(id, vertex);
        glAttachShader(id, fragment);
        glLinkProgram(id);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        require(glGetProgrami(id, GL_LINK_STATUS) == GL_TRUE, glGetProgramInfoLog(id));
        return id;
    }

    private static int shader(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, "#version 150\n" + source);
        glCompileShader(id);
        require(glGetShaderi(id, GL_COMPILE_STATUS) == GL_TRUE, glGetShaderInfoLog(id));
        return id;
    }

    private static void verifyExposure(CompactHdrPostProcessing bloom) {
        int dark = uniformInput(W, H, .09f);
        int bright = uniformInput(1, 1, 7f);
        int brightBloomSource = uniformInput(W, H, 7f);
        int localHighlight = uniformInput(64, 64, .09f);
        var patch = BufferUtils.createFloatBuffer(16 * 16 * 4);
        for (int i = 0; i < 16 * 16; i++) patch.put(7).put(7).put(7).put(1);
        patch.flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 16, 16, 16, 16, GL_RGBA, GL_FLOAT, patch);
        int previousPack = glGenBuffers();
        glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPack);
        glBufferData(GL_PIXEL_PACK_BUFFER, 64, GL_STATIC_READ);
        glPixelStorei(GL_PACK_ALIGNMENT, 8);
        glPixelStorei(GL_PACK_ROW_LENGTH, 37);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 3);
        glPixelStorei(GL_PACK_SKIP_ROWS, 2);
        glPixelStorei(GL_PACK_SWAP_BYTES, GL_TRUE);

        near(1, bloom.adaptExposure(1, .1, true), .001, "New meter starts from manual exposure");
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, localHighlight);
        glActiveTexture(active);
        bloom.draw(dark, brightBloomSource, W, H, .25f);
        require(glGetInteger(GL_ACTIVE_TEXTURE) == active, "Exposure metering altered the active texture unit");
        glActiveTexture(GL_TEXTURE1);
        require(glGetInteger(GL_TEXTURE_BINDING_2D) == localHighlight, "Exposure metering altered unit 1's sampler");
        glActiveTexture(active);
        checkPackState(previousPack);
        // The verifier waits for the submitted GPU read. Production polling passes a zero timeout.
        glFinish();
        float first = bloom.adaptExposure(1, .10, true);
        require(first > 1 && first < 1.18f, "Bright halo source altered the original dark field's adaptive exposure");
        checkPackState(previousPack);
        float settled = first;
        for (int i = 0; i < 16; i++) settled = bloom.adaptExposure(1, .18, true);
        require(settled > 1.72f && settled < 1.86f, "Dark field exposure did not converge within bounds");

        bloom.draw(bright, 1, 1, 1.8f);
        glFinish();
        float rapid = bloom.adaptExposure(1, .10, true);
        require(rapid > .65f && rapid < settled - .25f, "Bright field did not trigger faster protective adaptation");
        float protectedExposure = rapid;
        for (int i = 0; i < 8; i++) protectedExposure = bloom.adaptExposure(1, .18, true);
        near(.48, protectedExposure, .009, "Bright field clamp");
        near(.90, bloom.adaptExposure(.90f, .5, false), .001, "Disabled meter restores manual exposure");

        bloom.adaptExposure(1, .12, true);
        bloom.draw(localHighlight, 64, 64, 1);
        glFinish();
        float localProtection = 1;
        for (int i = 0; i < 8; i++) localProtection = bloom.adaptExposure(1, .18, true);
        near(.48, localProtection, .01, "A resolved highlight in a dark field remains protected");
        bloom.adaptExposure(1, 0, false);
        near(1, bloom.adaptExposure(1, .12, true), .001, "Reenabled meter starts clean");
        bloom.draw(dark, W, H, 1);
        bloom.adaptExposure(1, 0, false);
        near(1, bloom.adaptExposure(1, .12, true), .001, "Obsolete pending read leaked into a new meter session");
        bloom.adaptExposure(1, 0, false);
        checkPackState(previousPack);

        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SWAP_BYTES, GL_FALSE);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glDeleteBuffers(previousPack);
        glDeleteTextures(dark);
        glDeleteTextures(bright);
        glDeleteTextures(brightBloomSource);
        glDeleteTextures(localHighlight);
        System.out.printf(Locale.ROOT, "Sampled auto exposure: dark %.3f after .10 s, settled %.3f; bright %.3f after .10 s, settled %.3f%n",
                first, settled, rapid, protectedExposure);
    }

    private static void checkPackState(int buffer) {
        require(glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING) == buffer
                && glGetInteger(GL_PACK_ALIGNMENT) == 8 && glGetInteger(GL_PACK_ROW_LENGTH) == 37
                && glGetInteger(GL_PACK_SKIP_PIXELS) == 3 && glGetInteger(GL_PACK_SKIP_ROWS) == 2
                && glGetInteger(GL_PACK_SWAP_BYTES) == GL_TRUE, "Meter altered caller pixel-pack state");
    }

    private static int uniformInput(int width, int height, float light) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        var data = BufferUtils.createFloatBuffer(width * height * 4);
        for (int i = 0; i < width * height; i++) data.put(light).put(light).put(light).put(1);
        data.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, data);
        return texture;
    }

    private static void benchmark(CompactHdrPostProcessing bloom) {
        int input = highlightsInput(576, 404);
        for (int i = 0; i < 12; i++) bloom.draw(input, 576, 404, 1);
        int timer = glGenQueries();
        int frames = 48;
        for (boolean meter : new boolean[]{false, true}) {
            glBeginQuery(GL_TIME_ELAPSED, timer);
            for (int i = 0; i < frames; i++) {
                float exposure = bloom.adaptExposure(1, 1.0 / 60, meter);
                bloom.draw(input, 576, 404, exposure);
            }
            glEndQuery(GL_TIME_ELAPSED);
            double ms = glGetQueryObjectui64(timer, GL_QUERY_RESULT) / (frames * 1e6);
            System.out.printf(Locale.ROOT, "576x404 HDR input: %.3f ms mean GPU time (%d passes, meter=%s)%n",
                    ms, frames, meter);
        }
        bloom.adaptExposure(1, 0, false);
        glDeleteQueries(timer);
        glDeleteTextures(input);
    }

    private static int highlightsInput(int width, int height) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        var data = BufferUtils.createFloatBuffer(width * height * 4);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float red = x >= 6 && x <= 7 && y >= 8 && y <= 9 ? .8f : .2f;
            if (x == 24 && y == 22) data.put(9).put(4.5f).put(2.25f).put(1);
            else data.put(red).put(red).put(red).put(1);
        }
        data.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, data);
        return texture;
    }

    private static void validateMask(int buffer, boolean r, boolean g, boolean b, boolean a) {
        var mask = BufferUtils.createByteBuffer(4);
        glGetBooleani_v(GL_COLOR_WRITEMASK, buffer, mask);
        require((mask.get(0) != 0) == r && (mask.get(1) != 0) == g && (mask.get(2) != 0) == b
                && (mask.get(3) != 0) == a, "Bloom altered a draw-buffer color mask");
    }

    private static float[] read(int texture, int level) {
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, texture);
        int w = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH);
        int h = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT);
        var data = BufferUtils.createFloatBuffer(w * h * 4);
        glGetTexImage(GL_TEXTURE_2D, level, GL_RGBA, GL_FLOAT, data);
        glBindTexture(GL_TEXTURE_2D, previous);
        float[] color = new float[w * h * 4];
        data.get(color);
        return color;
    }

    private static void near(double expected, double actual, double tolerance, String message) {
        require(Math.abs(expected - actual) < tolerance, message + " expected " + expected + " got " + actual);
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
