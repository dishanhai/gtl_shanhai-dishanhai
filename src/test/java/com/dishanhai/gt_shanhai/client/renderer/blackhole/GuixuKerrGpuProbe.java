package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Exercises the production GLSL in a hidden OpenGL context. */
class GuixuKerrGpuProbe {
    private static final Path SHADERS = Path.of("src/main/resources/assets/gt_shanhai/shaders/core");
    private static final int W = Integer.getInteger("kerr.width", 800), H = Integer.getInteger("kerr.height", 560);
    private static final float TAN = (float) Math.tan(Math.toRadians(31));
    private static final Matrix4f PROJ = new Matrix4f().perspective(2 * (float) Math.atan(TAN), (float) W / H, .05f, 512);
    private static final Matrix4f INV_PROJ = new Matrix4f(PROJ).invert();
    private static int trace, composite, rayFbo, light, exit, rayW, rayH, imageFbo;
    private static int transferFbo, transferTex, transferW, transferH;
    private static boolean diagnostic, fullCapture;

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long context = glfwCreateWindow(W, H, "Guixu Kerr GLSL probe", 0, 0);
        if (context == 0) throw new IllegalStateException("OpenGL context creation failed");
        try {
            glfwMakeContextCurrent(context);
            GL.createCapabilities();
            System.out.printf("%s / %s / %s%n", glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION));
            trace = program("guixu_kerr_trace");
            composite = program("guixu_blackhole");
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers());
            glBufferData(GL_ARRAY_BUFFER, new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
            glEnableVertexAttribArray(0);
            imageFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
            attach(texture(W, H, GL_RGBA8, GL_LINEAR), GL_COLOR_ATTACHMENT0);
            checkFbo();
            Path output = Path.of(args.length == 0 ? "build/reports/kerr-gl" : args[0]);
            Files.createDirectories(output);
            if (args.length == 2 && args[1].equals("benchmark")) {
                benchmark(.4, 26, 65, 0);
                benchmark(.4, 14, 42, 0);
                benchmark(.45, 4.7f, 80, 2f);
                benchmark(.20, 4.7f, 80, 2f);
                return;
            }
            if (args.length == 2 && args[1].equals("close-budget")) {
                benchmarkCloseBudget();
                return;
            }
            if (args.length == 2 && args[1].equals("reference")) {
                rayTarget(2);
                for (int inclination : new int[]{65, 86}) {
                    trace(.65f, inclination, 0, 26, .000009f, 768, .62f, 14);
                    prepareComposite(inclination, 1, .18f);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    ImageIO.write(readImage(), "PNG", output.resolve("reference-" + inclination + ".png").toFile());
                }
                rayTarget(1);
                trace(.65f, 80, 2, 4.7f, .000001f, 768, .62f, 14);
                compose(80, 2, 0);
                ImageIO.write(readImage(), "PNG", output.resolve("reference-near.png").toFile());
                return;
            }
            double scale = Double.parseDouble(System.getProperty("kerr.scale", "0.72"));
            rayTarget(scale);
            for (float[] scene : new float[][]{{.65f, 65}, {.65f, 86}, {.65f, 12}, {1, 75}, {-1, 75}, {0, 75}}) {
                trace(scene[0], scene[1], 0, 26, 0.00006f, 480, 0.62f, 14);
                int[] classes = counts(readExit());
                String name = String.format(Locale.ROOT, "a%+.2f-i%.0f", scene[0], scene[1]);
                System.out.printf(Locale.ROOT, "%s: pixels=%d captured=%d escaped=%d unresolved=%d%n",
                        name, rayW * rayH, classes[2], classes[1], classes[0]);
                if (classes[2] < rayW * rayH / 75 || classes[0] > rayW * rayH / 400) {
                    throw new AssertionError("Unexpected ray classifications at " + name);
                }
                compose(scene[1], 1, .18f);
                BufferedImage image = readImage();
                ImageIO.write(image, "PNG", output.resolve(name + ".png").toFile());
                if (W == 800 && H == 560 && scale == .72 && scene[0] == .65f && scene[1] >= 65) {
                    verifyGolden(name, image);
                }
            }
            verifySchwarzschild();
            verifyShadowEdge();
            verifySpinReflection();
            verifyConvergence();
            verifySceneComposition();
            verifyWorldCacheEdges();
            verifyWalkingCamera();
            verifyWorldStructure(output);
            verifyDiskMotion();
            verifyPlungingRays();
            verifyApproach(output);
            verifySkyBlend(output);
            verifyConstants();
            for (double resolution : new double[]{1, .72, .52, .35, .20}) benchmark(resolution, 26, 65, 0);
            int stabilityFrames = Integer.getInteger("kerr.stabilityFrames", 0);
            if (stabilityFrames > 0) verifyLongRun(stabilityFrames);
            int glError = glGetError();
            if (glError != GL_NO_ERROR) throw new AssertionError("GL error: " + glError);
        } finally {
            glfwDestroyWindow(context);
            glfwTerminate();
        }
    }

    private static void verifyApproach(Path output) throws Exception {
        int world = constantTexture(.48f, .63f, .84f, 1);
        for (float[] camera : new float[][]{{14, 42, 0}, {4.7f, 80, 2.0f}, {3.7f, 82, -2.4f}}) {
            BufferedImage reference = null;
            for (double scale : new double[]{2, .40}) {
                rayTarget(scale);
                trace(.65f, camera[1], camera[2], camera[0], scale > 1 ? .000002f : .00016f,
                        scale > 1 ? 768 : 480, .62f, 14);
                prepareComposite(camera[1], 0, 0);
                set(composite, "uRefine", scale < 1 ? 4f : 0f);
                sampler("SceneColor", 0, world);
                set(composite, "uBgRect", 0, 0, W, H);
                set(composite, "uBgTexel", 1f / W, 1f / H);
                set(composite, "uStrength", 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                BufferedImage image = readImage();
                if (reference == null) { reference = image; continue; }
                set(composite, "uRefine", 0);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                BufferedImage pilotOnly = readImage();
                set(composite, "uRefine", 4);
                bakeFootprints(scale);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                BufferedImage adaptive = readImage();
                ImageIO.write(adaptive, "PNG", output.resolve("adaptive-" + camera[0] + ".png").toFile());
                set(composite, "uTransferPass", 0);
                glBindFramebuffer(GL_FRAMEBUFFER, rayFbo);
                glViewport(0, 0, rayW, rayH);
                glUseProgram(trace);
                set(trace, "uRayJitter", .375f, -.375f);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
                glViewport(0, 0, W, H);
                glUseProgram(composite);
                set(composite, "uRayJitter", .375f / rayW, -.375f / rayH);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                BufferedImage shifted = readImage();
                set(composite, "uRayJitter", 0, 0);
                ImageIO.write(image, "PNG", output.resolve("approach-" + camera[0] + ".png").toFile());
                KerrSampling plan = KerrSampling.plan(Quality.ADAPTIVE, Quality.ADAPTIVE.scale, Quality.ADAPTIVE.scale,
                        (double) W * H, 1, Math.hypot(camera[0], camera[2]));
                rayTarget(plan.traceScale());
                trace(.65f, camera[1], camera[2], camera[0], (float) Quality.ADAPTIVE.tolerance,
                        Quality.ADAPTIVE.steps, .62f, 14);
                prepareComposite(camera[1], 0, 0);
                sampler("SceneColor", 0, world);
                set(composite, "uRefine", plan.subpixels());
                bakeFootprintsAtScale(plan.transferScale());
                glDrawArrays(GL_TRIANGLES, 0, 3);
                BufferedImage bounded = readImage();
                ImageIO.write(bounded, "PNG", output.resolve("budgeted-" + camera[0] + ".png").toFile());
                int pixels = 0;
                long error = 0, pilotError = 0, adaptiveError = 0, boundedError = 0, shimmer = 0;
                for (int y = 2; y < H - 2; y++) for (int x = 2; x < W - 2; x++) {
                    int center = reference.getRGB(x, y) & 255;
                    int gx = (reference.getRGB(x + 1, y) & 255) - (reference.getRGB(x - 1, y) & 255);
                    int gy = (reference.getRGB(x, y + 1) & 255) - (reference.getRGB(x, y - 1) & 255);
                    if (Math.max(Math.abs(gx), Math.abs(gy)) >= 18) {
                        pixels++;
                        error += Math.abs((image.getRGB(x, y) & 255) - center);
                        pilotError += Math.abs((pilotOnly.getRGB(x, y) & 255) - center);
                        adaptiveError += Math.abs((adaptive.getRGB(x, y) & 255) - center);
                        boundedError += Math.abs((bounded.getRGB(x, y) & 255) - center);
                        shimmer += Math.abs((image.getRGB(x, y) & 255) - (shifted.getRGB(x, y) & 255));
                    }
                }
                double mae = (double) error / Math.max(1, pixels), drift = (double) shimmer / Math.max(1, pixels);
                double adaptiveMae = (double) adaptiveError / Math.max(1, pixels);
                double boundedMae = (double) boundedError / Math.max(1, pixels);
                double pilotMae = (double) pilotError / Math.max(1, pixels);
                System.out.printf(Locale.ROOT,
                        "Approach z=%.1f M: %d edge pixels, pilot %.3f, native %.3f, adaptive %.3f, bounded %.3f, jitter %.3f /255%n",
                        camera[0], pixels, pilotMae, mae, adaptiveMae, boundedMae, drift);
                if ((camera[0] >= 4.7f && pixels < 200) || mae > 5 ||
                        adaptiveMae > .65 * pilotMae || boundedMae > .75 * pilotMae || drift > 5)
                    throw new AssertionError("Under-resolved critical curve on approach");
                if (camera[0] == 4.7f) {
                    int largestJump = 0;
                    for (int y = (int)(.83 * H); y < .87 * H; y++) {
                        int x = (int)(.68 * W);
                        largestJump = Math.max(largestJump, Math.abs(((image.getRGB(x,y) >> 16) & 255)
                                - ((image.getRGB(x,y+1) >> 16) & 255)));
                    }
                    System.out.println("Near disk dark arc: largest consecutive red step " + largestJump + " /255");
                    if (largestJump > 4) throw new AssertionError("Artificial boundary in close disk transfer");
                }
            }
        }
        glDeleteTextures(world);
        rayTarget(.72);
        trace(.65f, 82, 0, -3.7f, .00006f, 480, .62f, 14);
        int[] outward = counts(readExit());
        if (outward[1] < rayW * rayH / 2) throw new AssertionError("Outward near-field sky disappeared");
    }

    private static void verifyPlungingRays() throws Exception {
        int optimized = trace;
        fullCapture = true;
        int direct = program("guixu_kerr_trace");
        fullCapture = false;
        rayTarget(.40);
        int compared = 0, mismatch = 0;
        for (float spin : new float[]{-.9999f, 0, .65f, 1f}) for (float distance : new float[]{5, 26, -3.7f}) {
            trace = direct;
            trace(spin, 77, 2, distance, .000022f, 768, .62f, 14);
            FloatBuffer expected = readExit(), expectedLight = readRayBuffer("rayLight");
            trace = optimized;
            trace(spin, 77, 2, distance, .000022f, 768, .62f, 14);
            FloatBuffer actual = readExit(), actualLight = readRayBuffer("rayLight");
            for (int i = 0; i < actual.capacity(); i += 4) {
                if (expected.get(i+3) != 0) {
                    compared++;
                    if ((expectedLight.get(i+3) > .5) != (expected.get(i+3) == 2)) mismatch++;
                }
                if (expected.get(i + 3) != 0 && expected.get(i + 3) != actual.get(i + 3))
                    throw new AssertionError("Plunge certificate changed ray class at spin " + spin);
                if (Math.abs(actualLight.get(i) - expectedLight.get(i)) > .002f)
                    throw new AssertionError("Plunge certificate clipped disk emission at spin " + spin);
            }
        }
        glDeleteProgram(direct);
        System.out.printf("Radial potential vs direct ray classification: %d differences / %d rays%n", mismatch, compared);
        if (mismatch > compared * .00015) throw new AssertionError("Critical-curve classification disagrees with integrator");
        System.out.println("Plunge certificates agree with direct horizon integration at four spins");
    }

    private static void verifySkyBlend(Path output) throws Exception {
        // Reproduce the shader declarations and the actual 1.20.1 BlendMode cache over two frames.
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        var traceBlend = net.minecraft.client.renderer.ShaderInstance.parseBlendNode(null);
        var json = com.google.gson.JsonParser.parseString(Files.readString(SHADERS.resolve("guixu_blackhole.json"))).getAsJsonObject();
        var lensBlend = net.minecraft.client.renderer.ShaderInstance.parseBlendNode(json.getAsJsonObject("blend"));
        var vanilla = new com.mojang.blaze3d.shaders.BlendMode(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_FUNC_ADD);
        traceBlend.apply();
        lensBlend.apply();
        com.mojang.blaze3d.systems.RenderSystem.blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        vanilla.apply(); // sky dome
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ZERO);
        vanilla.apply(); // position_tex on the sun
        BufferedImage sprite;
        try (var input = GuixuKerrGpuProbe.class.getResourceAsStream("/assets/minecraft/textures/environment/sun.png")) {
            sprite = ImageIO.read(java.util.Objects.requireNonNull(input, "Minecraft sun texture"));
        }
        int sun = texture(sprite.getWidth(), sprite.getHeight(), GL_RGBA8, GL_LINEAR);
        ByteBuffer spriteData = BufferUtils.createByteBuffer(sprite.getWidth() * sprite.getHeight() * 4);
        for (int y = 0; y < sprite.getHeight(); y++) for (int x = 0; x < sprite.getWidth(); x++) {
            int color = sprite.getRGB(x, y);
            spriteData.put((byte) (color >> 16)).put((byte) (color >> 8))
                    .put((byte) color).put((byte) (color >> 24));
        }
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, sprite.getWidth(), sprite.getHeight(),
                GL_RGBA, GL_UNSIGNED_BYTE, spriteData.flip());
        int air = constantTexture(0, 0, 0, 1);
        int ray = constantTexture(0, 0, -1, 1);
        prepareComposite(65, 0, 0);
        sampler("SceneColor", 0, sun);
        sampler("RayLight", 2, air);
        sampler("RayExit", 3, ray);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uStrength", 0);
        glClearColor(.48f, .63f, .84f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        int corner = redAt(W / 8, H / 8), center = redAt(W / 2, H / 2);
        ImageIO.write(readImage(), "PNG", output.resolve("day-sun.png").toFile());
        glDeleteTextures(new int[]{sun, air, ray});
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        System.out.println("Day sky behind opaque black sun texel: red=" + corner + " (expected 122)");
        if (Math.abs(corner - 122) > 1 || center < 235) throw new AssertionError("Solar sprite on day sky");
    }

    private static void verifySchwarzschild() {
        float r = 30;
        trace(0, 75, 0, r, .000018f, 640, 0, 0);
        FloatBuffer rays = readExit();
        int observed = 0;
        int row = rayH / 2;
        for (int x = rayW / 2; x < rayW; ++x) {
            if (rays.get((row * rayW + x) * 4 + 3) == 2) observed++;
        }
        double staticAngle = Math.asin(Math.sqrt(27) * Math.sqrt(1 - 2 / r) / r);
        double v = 2 / r;
        double movingAngle = Math.acos((Math.cos(staticAngle) - v) / (1 - v * Math.cos(staticAngle)));
        double expected = Math.tan(movingAngle) * rayH / (2 * TAN);
        System.out.printf(Locale.ROOT, "Schwarzschild capture radius %.2f reference, %d measured%n", expected, observed);
        if (Math.abs(expected - observed) > 1.6) throw new AssertionError("Schwarzschild critical impact parameter");
    }

    private static void verifyShadowEdge() {
        // Isolate the silhouette against white. At zero spin its exact image is a circle.
        float r = 14;
        double angle = Math.asin(Math.sqrt(27 * (1 - 2 / r)) / r);
        double cos = (Math.cos(angle) - 2 / r) / (1 - 2 / r * Math.cos(angle));
        double radius = Math.tan(Math.acos(cos)) * H / (2 * TAN);
        int white = constantTexture(1, 1, 1, 1);
        rayTarget(.13);
        trace(0, 65, 0, r, .00016f, 480, 0, 0);
        prepareComposite(65, 0, 0);
        sampler("SceneColor", 0, white);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uStrength", 0);
        set(composite, "uRefine", 2);
        bakeFootprintsAtScale(.20);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage diskOff = readImage();
        double error = 0;
        int edgePixels = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double dx = x + .5 - W * .5, dy = y + .5 - H * .5;
            if (Math.abs(Math.hypot(dx, dy) - radius) > .7) continue;
            int outside = 0;
            for (int j = 0; j < 16; j++) for (int i = 0; i < 16; i++) {
                if (Math.hypot(dx + (i + .5) / 16 - .5, dy + (j + .5) / 16 - .5) >= radius) outside++;
            }
            double srgb = (diskOff.getRGB(x, y) & 255) / 255.0;
            double measured = srgb <= .04045 ? srgb / 12.92 : Math.pow((srgb + .055) / 1.055, 2.4);
            error += Math.abs(measured - outside / 256.0);
            edgePixels++;
        }
        System.out.printf(Locale.ROOT, "13%% pilot shadow coverage: %.3f mean error in %d edge pixels%n",
                error / edgePixels, edgePixels);
        if (edgePixels == 0 || error / edgePixels > .055) throw new AssertionError("Stepped close shadow");

        // Keep the transfer texture fixed so any change comes from the moving rim.
        set(composite, "uDiskGain", .62f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage arc = readImage();
        set(composite, "uTime", 32, 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage rotated = readImage();
        int moving = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (Math.hypot(x + .5 - W * .5, y + .5 - H * .5) >= radius) continue;
            int red = (arc.getRGB(x, y) >> 16) & 255;
            int later = (rotated.getRGB(x, y) >> 16) & 255;
            if (Math.abs(red - later) > 8 && red > ((diskOff.getRGB(x, y) >> 16) & 255)) moving++;
        }
        if (moving < radius || (rotated.getRGB(W / 2, H / 2) & 0xffffff) != 0)
            throw new AssertionError("Missing orbiting rim or illuminated black core");
        glDeleteTextures(white);
        rayTarget(.72);
    }

    private static void verifySpinReflection() {
        trace(.97f, 65, 0, 26, .000018f, 640, 0, 0);
        FloatBuffer positive = readExit();
        trace(-.97f, 65, 0, 26, .000018f, 640, 0, 0);
        FloatBuffer negative = readExit();
        int mismatch = 0, bothEscape = 0;
        double squaredError = 0;
        for (int y = 0; y < rayH; y++) for (int x = 0; x < rayW; x++) {
            int a = (y * rayW + x) * 4, b = (y * rayW + rayW - 1 - x) * 4;
            if (positive.get(a + 3) != negative.get(b + 3)) mismatch++;
            if (positive.get(a + 3) == 1 && negative.get(b + 3) == 1) {
                bothEscape++;
                double dx = positive.get(a) + negative.get(b);
                double dy = positive.get(a + 1) - negative.get(b + 1);
                double dz = positive.get(a + 2) - negative.get(b + 2);
                squaredError += dx * dx + dy * dy + dz * dz;
            }
        }
        double rms = Math.sqrt(squaredError / bothEscape);
        System.out.printf(Locale.ROOT, "spin reflection: %d class differences, direction RMS %.7f%n", mismatch, rms);
        if (mismatch > 2 || rms > .003) throw new AssertionError("Kerr spin reflection");
    }

    private static void verifyConvergence() {
        trace(.75f, 65, 0, 26, .00032f, 480, 0, 0);
        FloatBuffer coarse = readExit();
        trace(.75f, 65, 0, 26, .000075f, 480, 0, 0);
        FloatBuffer medium = readExit();
        trace(.75f, 65, 0, 26, .000009f, 768, 0, 0);
        FloatBuffer fine = readExit();
        double coarseError = directionError(coarse, fine);
        double mediumError = directionError(medium, fine);
        System.out.printf(Locale.ROOT, "convergence direction RMS: coarse %.7f, medium %.7f%n", coarseError, mediumError);
        if (!(mediumError < coarseError * .8)) throw new AssertionError("Escape directions failed to converge");
    }

    private static void verifySceneComposition() {
        trace(.65f, 65, 0, 26, .00006f, 480, 0, 0);
        int scene = texture(1, 1, GL_RGBA8, GL_LINEAR);
        ByteBuffer pixel = BufferUtils.createByteBuffer(4).put(new byte[]{64, (byte) 128, (byte) 192, (byte) 255}).flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        prepareComposite(65, 0, 0);
        sampler("SceneColor", 0, scene);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uStrength", 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        if (redAt(W / 2, H / 2) > 1 || Math.abs(redAt(W / 5, H / 5) - 64) > 1) {
            throw new AssertionError("World background and capture compositing");
        }

        int depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        checkFbo();
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glClearColor(.25f, .5f, .75f, 1);
        glClearDepth(.25);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDepthMask(false);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        if (Math.abs(redAt(W / 2, H / 2) - 64) > 1) throw new AssertionError("Foreground was overwritten");
        glDisable(GL_DEPTH_TEST);
        glDepthMask(true);

        glClear(GL_COLOR_BUFFER_BIT);
        set(composite, "uSampleBackground", 0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glDisable(GL_BLEND);
        if (redAt(W / 2, H / 2) > 1 || Math.abs(redAt(W / 5, H / 5) - 64) > 1) {
            throw new AssertionError("Body-only blending");
        }
        glClearDepth(1);
        glEnable(GL_DEPTH_TEST);
        glClear(GL_DEPTH_BUFFER_BIT);
        glColorMask(false, false, false, false);
        set(composite, "uCaptureDepthOnly", 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] capturedDepth = new float[1], clearDepth = new float[1];
        glReadPixels(W/2, H/2, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, capturedDepth);
        glReadPixels(W/5, H/5, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, clearDepth);
        set(composite, "uCaptureDepthOnly", 0);
        glColorMask(true, true, true, true);
        glDisable(GL_DEPTH_TEST);
        if (Math.abs(capturedDepth[0] - .5f) > .0001f || clearDepth[0] != 1f)
            throw new AssertionError("Deferred celestial body depth did not follow captured rays");
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
        glDeleteRenderbuffers(depth);
        glDeleteTextures(scene);
        System.out.println("world background, foreground occlusion and body-only compositing passed");
    }

    private static int redAt(int x, int y) {
        ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        return pixel.get(0) & 255;
    }

    private static void verifyWorldCacheEdges() {
        int world = constantTexture(.25f, .5f, .75f, 1);
        int empty = constantTexture(0, 0, 0, 1);
        int disk = constantTexture(.65f, .31f, .11f, .4f);
        int reverseRay = constantTexture(0, 0, 1, 1);

        prepareComposite(65, 0, 0);
        sampler("SceneColor", 0, world);
        sampler("RayLight", 2, empty);
        sampler("RayExit", 3, reverseRay);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uStrength", 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        int unavailable = redAt(W / 2, H / 2);

        int color = texture(2, 1, GL_RGBA16F, GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 1, GL_RGBA, GL_FLOAT,
                new float[]{.25f, .5f, .75f, 1, .8f, .5f, .25f, 1});
        int depth = texture(2, 1, GL_RGBA16F, GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 1, GL_RGBA, GL_FLOAT,
                new float[]{1, 0, 0, 1, .98f, 0, 0, 1});
        int forwardRay = constantTexture(.12f, 0, -.993f, 1);
        sampler("SceneColor", 0, color);
        sampler("SceneDepth", 1, depth);
        sampler("RayExit", 3, forwardRay);
        set(composite, "uDepthGate", 1);
        set(composite, "uHoleDepth", .94f);
        set(composite, "uGateEps", .001f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        int behind = redAt(W / 2 - 2, H / 2);
        glActiveTexture(GL_TEXTURE1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 1, 0, 1, 1, GL_RGBA, GL_FLOAT, new float[]{.92f, 0, 0, 1});
        glDrawArrays(GL_TRIANGLES, 0, 3);
        int inFront = redAt(W / 2 - 2, H / 2);

        int x = W / 4, y = H / 4;
        sampler("RayLight", 2, disk);
        set(composite, "uSampleBackground", 0);
        set(composite, "uTraceRect", x, y, W / 2, H / 2);
        glClearColor(.25f, .5f, .75f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, W / 2, H / 2);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        int first = redAt(x, H / 2);
        int outside = redAt(x - 1, H / 2);
        int center = redAt(W / 2, H / 2);
        glDeleteTextures(new int[]{world, empty, disk, reverseRay, color, depth, forwardRay});
        if (Math.abs(unavailable - 64) > 1 || Math.abs(first - outside) > 1 || center < outside + 60
                || behind < 180 || Math.abs(inFront - 64) > 1) {
            throw new AssertionError("World cache composition: off-camera=" + unavailable
                    + " crop edge=" + first + " outside=" + outside + " disk=" + center
                    + " behind lens=" + behind + " in front=" + inFront);
        }
        System.out.println("off-camera world rays, donor depth and cropped disk coverage passed");
    }

    private static void verifyWorldStructure(Path output) throws Exception {
        // Near blue truss, distant gold ring and an unoccluded sky, in camera-space units.
        ByteBuffer colors = BufferUtils.createByteBuffer(W * H * 4);
        FloatBuffer depths = BufferUtils.createFloatBuffer(W * H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double u = (x + .5) / W, v = (y + .5) / H;
            boolean truss = Math.abs(v - .61) < .008 || Math.abs(v - .68) < .008
                    || v > .61 && v < .68 && Math.abs((u * 22 % 1) - (v - .61) / .07) < .08;
            double r = Math.hypot((u - .55) * 1.75, (v - .32) * 3.2);
            boolean gold = Math.abs(r - .64) < .018 || Math.abs(r - .59) < .008;
            double sky = Math.max(0, Math.min(1, (v - .55) / .40));
            int rgb = truss ? 0x184567 : gold ? 0xc89947 : ((int) (130 - 79 * sky) << 16)
                    | ((int) (148 - 91 * sky) << 8) | (int) (206 - 120 * sky);
            colors.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 255);
            depths.put(truss ? windowDepth(47) : gold ? windowDepth(120) : 1f);
        }
        int world = texture(W, H, GL_RGBA8, GL_LINEAR);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, colors.flip());
        int depth = texture(W, H, GL_R32F, GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RED, GL_FLOAT, depths.flip());
        int sceneFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        attach(world, GL_COLOR_ATTACHMENT0);
        checkFbo();
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, imageFbo);
        glBlitFramebuffer(0, 0, W, H, 0, 0, W, H, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glDeleteFramebuffers(sceneFbo);
        trace(.65f, 76, 1, 46, .00012f, 384, .62f, 14);
        prepareComposite(76, 0, .18f);
        sampler("SceneColor", 0, world);
        sampler("SceneDepth", 1, depth);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uHoleDepth", windowDepth(46));
        set(composite, "uGateEps", .00002f);
        set(composite, "uDepthGate", 1);
        set(composite, "uStrength", 1);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glDisable(GL_BLEND);
        ImageIO.write(readImage(), "PNG", output.resolve("world-structure.png").toFile());

        int empty = constantTexture(0, 0, 0, 1);
        int ray = constantTexture(.18f, 0, -.9836666f, 1);
        int near = constantTexture(windowDepth(46.2f), 0, 0, 1);
        int far = constantTexture(1, 0, 0, 1);
        int stripe = texture(W, 1, GL_RGBA8, GL_NEAREST);
        ByteBuffer data = BufferUtils.createByteBuffer(W * 4);
        for (int x = 0; x < W; x++) {
            data.put((byte) ((x / 5 % 2) * 255)).put((byte) Math.round(255f * x / (W - 1))).put((byte) 0).put((byte) 255);
        }
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, 1, GL_RGBA, GL_UNSIGNED_BYTE, data.flip());
        sampler("SceneColor", 0, stripe);
        sampler("RayLight", 2, empty);
        sampler("RayExit", 3, ray);
        sampler("SceneDepth", 1, near);
        set(composite, "uStrength", .5f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        int nearGreen = (readImage().getRGB(W / 2, H / 2) >> 8) & 255;
        sampler("SceneDepth", 1, far);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage image = readImage();
        int farGreen = (image.getRGB(W / 2, H / 2) >> 8) & 255;
        int ghosts = 0;
        for (int x = W / 3; x < 2 * W / 3; x++) {
            int red = (image.getRGB(x, H / 2) >> 16) & 255;
            if (red > 1 && red < 254) ghosts++;
        }
        glDeleteTextures(new int[]{world, depth, stripe, empty, ray, near, far});
        System.out.printf("World stripes: ghost pixels=%d, near/far source coordinates=%d/%d (original=128)%n",
                ghosts, nearGreen, farGreen);
        if (ghosts != 0 || Math.abs(nearGreen - 128) > 1 || farGreen < 130) {
            throw new AssertionError("Duplicated world edges or incorrect finite-distance lensing");
        }
    }

    private static float windowDepth(float distance) {
        return .5f + .5f * (-distance * PROJ.m22() + PROJ.m32()) / distance;
    }

    private static void verifyWalkingCamera() {
        trace(.65f, 76, 0, 46, .00012f, 384, 0, 0);
        FloatBuffer expected = readExit();
        Matrix4f walking = new Matrix4f(PROJ).translate(.09f, -.04f, .025f);
        matrix(trace, "uInvProj", new Matrix4f(walking).invert());
        set(trace, "uBhView", -.09f, .04f, -46.025f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        FloatBuffer actual = readExit();
        int changed = 0;
        for (int i = 3; i < actual.capacity(); i += 4) {
            if (actual.get(i) != expected.get(i)) changed++;
        }
        System.out.printf("Translated projection: %d ray classifications changed%n", changed);
        if (changed > 4 || directionError(expected, actual) > .001) throw new AssertionError("Walking camera rays");
    }

    private static void verifyDiskMotion() {
        trace(.65f, 65, 0, 26, .00006f, 480, .62f, 14);
        FloatBuffer early = readRayBuffer("rayLight");
        int[] earlyClasses = counts(readExit());
        trace(.65f, 65, 0, 26, .00006f, 480, .62f, 32);
        FloatBuffer late = readRayBuffer("rayLight");
        int moving = 0;
        for (int i = 0; i < early.capacity(); i += 4) {
            if (Math.abs(early.get(i) - late.get(i)) > .035f) moving++;
        }
        int[] lateClasses = counts(readExit());
        if (moving < rayW * rayH / 40 || earlyClasses[2] != lateClasses[2]) {
            throw new AssertionError("Disk animation moved " + moving + " pixels or altered capture");
        }
        System.out.printf("Disk animation updated %d radiance samples with the camera held fixed%n", moving);
    }

    private static int constantTexture(float r, float g, float b, float a) {
        int id = texture(1, 1, GL_RGBA16F, GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RGBA, GL_FLOAT, new float[]{r, g, b, a});
        return id;
    }

    private static double directionError(FloatBuffer a, FloatBuffer b) {
        double squared = 0;
        int count = 0;
        for (int i = 0; i < a.capacity(); i += 4) {
            if (a.get(i + 3) != 1 || b.get(i + 3) != 1) continue;
            // Also report the noncritical rays where neighboring directions remain comparable.
            double difference = 0;
            for (int c = 0; c < 3; ++c) {
                double delta = a.get(i + c) - b.get(i + c);
                difference += delta * delta;
            }
            if (difference >= .04) continue;
            squared += difference;
            count++;
        }
        return Math.sqrt(squared / count);
    }

    private static void verifyConstants() throws Exception {
        int production = trace;
        trace = program("guixu_kerr_trace", true);
        diagnostic = true;
        rayTarget(.52);
        trace(.75f, 65, 0, 26, .000009f, 768, 0, 0);
        FloatBuffer stats = readRayBuffer("rayLight");
        float[] maximum = new float[3];
        for (int i = 0; i < stats.capacity(); i += 4) {
            for (int c = 0; c < 3; c++) {
                if (!Float.isFinite(stats.get(i + c))) throw new AssertionError("Nonfinite invariant");
                maximum[c] = Math.max(maximum[c], stats.get(i + c));
            }
        }
        System.out.printf(Locale.ROOT, "max null, Lz, Carter K drift: %.6g %.6g %.6g%n", maximum[0], maximum[1], maximum[2]);
        if (maximum[0] > .006 || maximum[1] > .01 || maximum[2] > .014) {
            throw new AssertionError("Geodesic invariant drift");
        }
        glDeleteProgram(trace);
        trace = production;
        diagnostic = false;
    }

    private static void verifyLongRun(int frames) {
        rayTarget(.26);
        long start = System.nanoTime();
        for (int i = 0; i < frames; ++i) {
            trace(.9999f, 70, 0, 28, .00014f, 480, .62f, 1_000_000 + i * .25f);
            compose(70, 1, .18f);
            if (i % 600 == 0) glFinish();
        }
        glFinish();
        glBindFramebuffer(GL_FRAMEBUFFER, rayFbo);
        int[] result = counts(readExit());
        if (result[2] == 0 || result[1] == 0 || result[0] > rayW * rayH / 400) {
            throw new AssertionError("Invalid ray field after the stability run");
        }
        System.out.printf(Locale.ROOT, "%d consecutive frames at simulation time > 1M: %.2f s%n",
                frames, (System.nanoTime() - start) / 1e9);
    }

    private static void benchmark(double scale, float distance, float inclination, float offset) {
        double transferScale = W * H >= 480_000 ? Math.clamp(1.65 * scale, .28, .74) : 0;
        benchmark(new KerrSampling(scale, transferScale, scale <= .25 ? 2 : 4), distance, inclination, offset);
    }

    private static void benchmarkCloseBudget() {
        double area = (double) W * H;
        for (float[] camera : new float[][]{{14, 42, 0}, {4.7f, 80, 2}, {-2.2f, 86, .10f}}) {
            for (double adaptiveScale : new double[]{Quality.ADAPTIVE.scale, .13}) {
                double oldTrace = Math.min(Quality.ADAPTIVE.scale, Math.sqrt(300_000 / area))
                        * adaptiveScale / Quality.ADAPTIVE.scale;
                double oldTransfer = area >= 480_000 ? Math.clamp(1.65 * oldTrace, .28, .74) : 0;
                int oldSubpixels = adaptiveScale < .40 ? 2 : 4;
                double distance = Math.hypot(camera[0], camera[2]);
                KerrSampling plan = KerrSampling.plan(Quality.ADAPTIVE, Quality.ADAPTIVE.scale, adaptiveScale,
                        area, 1, distance);
                System.out.printf(Locale.ROOT, "Previous budget (feedback scale %.2f):%n", adaptiveScale);
                benchmark(new KerrSampling(oldTrace, oldTransfer, oldSubpixels), camera[0], camera[1], camera[2]);
                System.out.println("Bounded budget:");
                benchmark(plan, camera[0], camera[1], camera[2]);
            }
        }
    }

    private static void benchmark(KerrSampling plan, float distance, float inclination, float offset) {
        double scale = plan.traceScale();
        rayTarget(scale);
        int samples = 18;
        int timer = glGenQueries();
        for (int i = 0; i < 4; ++i) {
            trace(.65f, inclination, offset, distance, .00022f, 384, .62f, i);
            composeAdaptive(inclination, plan);
        }
        glFinish();
        glBeginQuery(GL_TIME_ELAPSED, timer);
        for (int i = 0; i < samples; ++i) {
            trace(.65f, inclination, offset, distance, .00022f, 384, .62f, i);
            composeAdaptive(inclination, plan);
        }
        glEndQuery(GL_TIME_ELAPSED);
        double ms = glGetQueryObjectui64(timer, GL_QUERY_RESULT) / (samples * 1e6);
        glBeginQuery(GL_TIME_ELAPSED, timer);
        for (int i = 0; i < samples; ++i)
            trace(.65f, inclination, offset, distance, .00022f, 384, .62f, i);
        glEndQuery(GL_TIME_ELAPSED);
        double traceMs = glGetQueryObjectui64(timer, GL_QUERY_RESULT) / (samples * 1e6);
        glBeginQuery(GL_TIME_ELAPSED, timer);
        for (int i = 0; i < samples; ++i) {
            prepareComposite(inclination, 1, .18f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glEndQuery(GL_TIME_ELAPSED);
        double baseMs = glGetQueryObjectui64(timer, GL_QUERY_RESULT) / (samples * 1e6);
        glDeleteQueries(timer);
        System.out.printf(Locale.ROOT,
                "z=%.1f M, trace %dx%d, transfer %dx%d × %d: trace + composite %.3f ms/frame, pilot %.3f ms, base %.3f ms%n",
                distance, rayW, rayH, (int) Math.ceil(W * plan.transferScale()),
                (int) Math.ceil(H * plan.transferScale()), plan.subpixels(), ms, traceMs, baseMs);
    }

    private static void trace(float spin, float inclination, float offset, float distance, float tolerance,
                              int steps, float disk, float time) {
        glBindFramebuffer(GL_FRAMEBUFFER, rayFbo);
        glViewport(0, 0, rayW, rayH);
        glUseProgram(trace);
        matrix(trace, "uInvProj", INV_PROJ);
        set(trace, "uBhView", offset, 0, -distance);
        axis(trace, inclination);
        set(trace, "uScreenSize", W, H);
        set(trace, "uTraceRect", 0, 0, W, H);
        set(trace, "uTraceSize", rayW, rayH);
        set(trace, "uRayJitter", 0, 0);
        set(trace, "uRs", 2);
        if (!diagnostic) {
            float block = (float) Math.floor(time / 64);
            set(trace, "uTime", time - 64 * block, block);
            set(trace, "uDiskGain", disk);
            set(trace, "uDiskHeat", .1f);
            set(trace, "uDiskPhysics", 1, .055f, 9400, .38f);
        }
        set(trace, "uKerrParameters", spin, 1 + (float) Math.sqrt(1 - spin * spin), isco(spin), 1);
        set(trace, "uIntegration", tolerance, steps);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        if (!diagnostic) {
            glUseProgram(composite);
            set(composite, "uBhView", offset, 0, -distance);
            set(composite, "uRs", 2);
            float block = (float) Math.floor(time / 64);
            set(composite, "uTime", time - 64 * block, block);
            set(composite, "uDiskGain", disk);
            set(composite, "uDiskHeat", .1f);
            set(composite, "uDiskPhysics", 1, .055f, 9400, .38f);
            set(composite, "uKerrParameters", spin, 1 + (float) Math.sqrt(1 - spin * spin), isco(spin), 1);
            set(composite, "uIntegration", tolerance, steps);
            glUseProgram(trace);
        }
    }

    private static void compose(float inclination, float mode, float bloom) {
        prepareComposite(inclination, mode, bloom);
        set(composite, "uRefine", 4);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void composeAdaptive(float inclination, KerrSampling plan) {
        prepareComposite(inclination, 1, .18f);
        set(composite, "uRefine", plan.subpixels());
        if (plan.transferScale() > 0) bakeFootprintsAtScale(plan.transferScale());
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void bakeFootprints(double pilotScale) {
        bakeFootprintsAtScale(Math.clamp(1.65 * pilotScale, .28, .74));
    }

    private static void bakeFootprintsAtScale(double scale) {
        int w = (int) Math.ceil(W * scale), h = (int) Math.ceil(H * scale);
        if (transferW != w || transferH != h) {
            if (transferFbo != 0) { glDeleteFramebuffers(transferFbo); glDeleteTextures(transferTex); }
            transferFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, transferFbo);
            glActiveTexture(GL_TEXTURE7);
            transferTex = texture(w, h, GL_RGBA16F, GL_LINEAR);
            attach(transferTex, GL_COLOR_ATTACHMENT0);
            checkFbo();
            transferW = w;
            transferH = h;
        } else glBindFramebuffer(GL_FRAMEBUFFER, transferFbo);
        sampler("PixelTransfer", 6, light);
        glViewport(0, 0, w, h);
        set(composite, "uResolveSize", w, h);
        set(composite, "uTransferPass", 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
        glViewport(0, 0, W, H);
        set(composite, "uTransferPass", 2);
        sampler("PixelTransfer", 6, transferTex);
    }

    private static void prepareComposite(float inclination, float mode, float bloom) {
        glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
        glViewport(0, 0, W, H);
        glUseProgram(composite);
        set(composite, "uRefine", 0);
        set(composite, "uTransferPass", 0);
        matrix(composite, "uInvProj", INV_PROJ);
        matrix(composite, "uProjMat", PROJ);
        axis(composite, inclination);
        set(composite, "uScreenSize", W, H);
        set(composite, "uShadowPixels", H * .14f);
        set(composite, "uTraceRect", 0, 0, W, H);
        set(composite, "uDepthGate", 0);
        set(composite, "uSampleBackground", 1);
        set(composite, "uDisplay", 1.15f, bloom);
        set(composite, "uSkyMix", mode == 0 ? 1 : 0, mode == 1 ? 1 : 0, mode == 2 ? 1 : 0);
        sampler("RayLight", 2, light);
        sampler("RayExit", 3, exit);
    }

    private static void axis(int program, float inclination) {
        double angle = Math.toRadians(inclination);
        set(program, "uDiskNormal", 0, (float) Math.sin(angle), (float) Math.cos(angle));
        set(program, "uEquatorialX", 1, 0, 0);
    }

    private static float isco(double a) {
        double z1 = 1 + Math.cbrt((1 - a) * (1 + a)) * (Math.cbrt(1 + a) + Math.cbrt(1 - a));
        double z2 = Math.sqrt(3 * a * a + z1 * z1);
        return (float) (3 + z2 - Math.copySign(Math.sqrt(Math.max(0, (3 - z1) * (3 + z1 + 2 * z2))), a));
    }

    private static void rayTarget(double scale) {
        if (rayFbo != 0) {
            glDeleteFramebuffers(rayFbo);
            glDeleteTextures(light);
            glDeleteTextures(exit);
        }
        rayW = (int) Math.ceil(W * scale);
        rayH = (int) Math.ceil(H * scale);
        rayFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, rayFbo);
        int lightAttachment = GL_COLOR_ATTACHMENT0 + glGetFragDataLocation(trace, "rayLight");
        int exitAttachment = GL_COLOR_ATTACHMENT0 + glGetFragDataLocation(trace, "rayExit");
        light = texture(rayW, rayH, GL_RGBA16F, GL_LINEAR);
        exit = texture(rayW, rayH, GL_RGBA16F, GL_NEAREST);
        attach(light, lightAttachment);
        attach(exit, exitAttachment);
        glDrawBuffers(new int[]{Math.min(lightAttachment, exitAttachment), Math.max(lightAttachment, exitAttachment)});
        checkFbo();
    }

    private static FloatBuffer readExit() {
        return readRayBuffer("rayExit");
    }

    private static FloatBuffer readRayBuffer(String output) {
        var result = BufferUtils.createFloatBuffer(rayW * rayH * 4);
        glReadBuffer(GL_COLOR_ATTACHMENT0 + glGetFragDataLocation(trace, output));
        glReadPixels(0, 0, rayW, rayH, GL_RGBA, GL_FLOAT, result);
        return result;
    }

    private static int[] counts(FloatBuffer rays) {
        int[] result = new int[3];
        for (int i = 3; i < rays.capacity(); i += 4) result[(int) rays.get(i)]++;
        return result;
    }

    private static int texture(int w, int h, int internalFormat, int filter) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0, GL_RGBA, GL_FLOAT, 0L);
        return texture;
    }

    private static void attach(int texture, int attachment) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, texture, 0);
    }

    private static void checkFbo() {
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Framebuffer incomplete");
    }

    private static void sampler(String name, int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(location(composite, name), unit);
    }

    private static int program(String fragment) throws Exception {
        return program(fragment, false);
    }

    private static int program(String fragment, boolean diagnostic) throws Exception {
        int vertex = shader(GL_VERTEX_SHADER, "guixu_blackhole.vsh", false);
        int frag = shader(GL_FRAGMENT_SHADER, fragment + ".fsh", diagnostic);
        int id = glCreateProgram();
        glAttachShader(id, vertex);
        glAttachShader(id, frag);
        glBindAttribLocation(id, 0, "Position");
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(id));
        glDeleteShader(vertex);
        glDeleteShader(frag);
        return id;
    }

    private static int shader(int type, String file, boolean diagnostic) throws Exception {
        int id = glCreateShader(type);
        String source = Files.readString(SHADERS.resolve(file));
        source = source.replace("#moj_import <gt_shanhai:guixu_kerr.glsl>",
                Files.readString(SHADERS.resolve("../include/guixu_kerr.glsl")));
        if (diagnostic) source = source.replace("#version 150", "#version 150\n#define KERR_CHECK_INVARIANTS");
        if (fullCapture) source = source.replace("#version 150", "#version 150\n#define KERR_FULL_CAPTURE");
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == 0) throw new AssertionError(file + ": " + glGetShaderInfoLog(id));
        return id;
    }

    private static int location(int id, String name) {
        int location = glGetUniformLocation(id, name);
        if (location < 0) throw new AssertionError("Missing uniform " + name);
        return location;
    }

    private static void matrix(int id, String name, Matrix4f matrix) {
        glUniformMatrix4fv(location(id, name), false, matrix.get(new float[16]));
    }

    private static void set(int id, String name, float... values) {
        int location = location(id, name);
        switch (values.length) {
            case 1 -> glUniform1f(location, values[0]);
            case 2 -> glUniform2fv(location, values);
            case 3 -> glUniform3fv(location, values);
            case 4 -> glUniform4fv(location, values);
            default -> throw new AssertionError(name);
        }
    }

    private static void verifyGolden(String name, BufferedImage image) throws Exception {
        Path path = Path.of("src/test/resources/guixu-kerr", name + ".png");
        BufferedImage golden = ImageIO.read(path.toFile());
        int blockX = W / golden.getWidth(), blockY = H / golden.getHeight();
        double error = 0;
        int outliers = 0;
        for (int y = 0; y < golden.getHeight(); y++) for (int x = 0; x < golden.getWidth(); x++) {
            for (int c = 0; c <= 16; c += 8) {
                int sum = 0;
                for (int dy = 0; dy < blockY; dy++) for (int dx = 0; dx < blockX; dx++) {
                    sum += (image.getRGB(x * blockX + dx, y * blockY + dy) >> c) & 255;
                }
                double delta = Math.abs(((golden.getRGB(x, y) >> c) & 255) - (double) sum / (blockX * blockY));
                error += delta;
                if (delta > 20) outliers++;
            }
        }
        double channels = golden.getWidth() * golden.getHeight() * 3.0;
        System.out.printf(Locale.ROOT, "%s golden: mean absolute RGB %.3f/255, %.3f%% large errors%n",
                name, error / channels, outliers * 100 / channels);
        if (error / channels > 2.5 || outliers / channels > .015) {
            throw new AssertionError("Golden image difference at " + name);
        }
    }

    private static BufferedImage readImage() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = (y * W + x) * 4;
            image.setRGB(x, H - 1 - y, (pixels.get(p) & 255) << 16 | (pixels.get(p + 1) & 255) << 8 | pixels.get(p + 2) & 255);
        }
        return image;
    }
}
