package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import com.mojang.blaze3d.systems.RenderSystem;

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
    private static final Path SHADERS = Path.of(System.getProperty("kerr.shaderRoot",
            "src/main/resources/assets/gt_shanhai/shaders/core"));
    static final int W = Integer.getInteger("kerr.width", 800), H = Integer.getInteger("kerr.height", 560);
    private static final float TAN = (float) Math.tan(Math.toRadians(31));
    private static final Matrix4f PROJ = new Matrix4f().perspective(2 * (float) Math.atan(TAN), (float) W / H, .05f, 512);
    private static final Matrix4f INV_PROJ = new Matrix4f(PROJ).invert();
    static int trace, composite, imageFbo;
    static float orbitAzimuth;
    private static int rayFbo, light, exit, rayW, rayH;
    static int rayLightTexture() { return light; }
    static int rayWidth() { return rayW; }
    static int rayHeight() { return rayH; }
    static void perspective(double fov) {
        PROJ.identity().perspective((float) Math.toRadians(fov), (float) W / H, .05f, 512);
        INV_PROJ.set(PROJ).invert();
    }
    private static int transferFbo, transferTex, transferW, transferH;
    private static int stripWidth, stripRows, radiiFbo, radiiTex, stripFbo, stripTex, stripExit;
    private static float cameraDistance, cameraOffset, diskGain;
    private static boolean diagnostic, fullCapture;
    private static WhiteHoleHDRPostProcessing hdr;
    private static final KerrThinDisk diskModel = new KerrThinDisk();

    public static void main(String[] args) throws Exception {
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long context = glfwCreateWindow(W, H, "Guixu Kerr GLSL probe", 0, 0);
        if (context == 0) throw new IllegalStateException("OpenGL context creation failed");
        try (var post = new WhiteHoleHDRPostProcessing(extension ->
                Files.readString(SHADERS.resolve("../whitehole/bright_pass." + extension)))) {
            glfwMakeContextCurrent(context);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            hdr = post;
            System.out.printf("%s / %s / %s%n", glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION));
            initializePipeline();
            Path output = Path.of(args.length == 0 ? "build/reports/kerr-gl" : args[0]);
            Files.createDirectories(output);
            if (args.length == 2 && args[1].equals("disk-edge")) {
                verifyDiskEdge(output);
                return;
            }
            if (args.length == 2 && args[1].equals("close-detail")) {
                perspective(70);
                verifyDiskEdgeScene(output, false, 0, 22);
                verifyDiskEdgeScene(output, false, .18f, 22);
                // Approximate the reported body-only view with its logged 160x96 pilot grid.
                perspective(116);
                verifyDiskEdgeScene(output, false, 0, 11);
                verifyDiskEdgeScene(output, false, .18f, 11);
                return;
            }
            if (args.length == 2 && args[1].equals("world-moire")) {
                verifyWorldMinification();
                verifyWorldDonorBoundary();
                return;
            }
            if (args.length == 2 && args[1].equals("grazing-depth")) {
                verifyGrazingDepth();
                return;
            }
            if (args.length == 2 && args[1].equals("benchmark")) {
                benchmark(.4, 26, 65, 0);
                benchmark(.4, 14, 42, 0);
                benchmark(.45, 4.7f, 80, 2f);
                benchmark(.20, 4.7f, 80, 2f);
                return;
            }
            if (args.length == 2 && args[1].equals("performance")) {
                perspective(70);
                rayTarget(.13);
                verifyWorldStructure(output, true);
                verifyTransferOnly();
                verifyDiskEdgeScene(output, false, .18f, 22, true);
                perspective(116);
                verifyDiskEdgeScene(output, false, .18f, 11, true);
                perspective(70);
                for (float distance : new float[]{46, 22, 11, 4.7f}) {
                    var plan = KerrSampling.plan(Quality.ADAPTIVE, .44, .44, W * (double) H, 1, distance, H * .17);
                    benchmark(plan, distance, 80, distance < 6 ? 2 : 0);
                }
                return;
            }
            if (args.length == 2 && args[1].equals("close-budget")) {
                benchmarkCloseBudget();
                return;
            }
            if (args.length == 2 && args[1].equals("critical-radiance")) {
                verifyCriticalRadiance(output);
                return;
            }
            if (args.length == 2 && args[1].equals("spatial-stability")) {
                verifyGrazingStability(output);
                return;
            }
            if (args.length == 2 && args[1].equals("approach")) {
                verifyApproach(output);
                return;
            }
            if (args.length == 2 && args[1].equals("exterior")) {
                verifyAnalyticExterior(output);
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
            if (W == 800 && H == 560) verifyCriticalRadiance(output);
            verifySpinReflection();
            verifyConvergence();
            verifySceneComposition();
            verifyGrazingDepth();
            verifyAnalyticExterior(output);
            verifyWorldCacheEdges();
            verifyWalkingCamera();
            verifyWorldStructure(output);
            verifyWorldMinification();
            verifyDiskMotion();
            verifyColdDiskAndHistory();
            verifyCachedDeflection();
            verifyPlungingRays();
            verifyApproach(output);
            verifyGrazingStability(output);
            verifySkyBlend(output);
            verifyLegacyOptics();
            verifyConstants();
            for (double resolution : new double[]{1, .72, .52, .35, .20}) benchmark(resolution, 26, 65, 0);
            int stabilityFrames = Integer.getInteger("kerr.stabilityFrames", 0);
            if (stabilityFrames > 0) verifyLongRun(stabilityFrames);
            int glError = glGetError();
            if (glError != GL_NO_ERROR) throw new AssertionError("GL error: " + glError);
        } finally {
            hdr = null;
            glfwDestroyWindow(context);
            glfwTerminate();
        }
    }

    private static void verifyLegacyOptics() throws Exception {
        String name = "guixu_blackhole_legacy";
        int legacy = program(name + ".vsh", name, false);
        int warmScene = constantTexture(.85f, .12f, .24f, 1);
        int coolScene = constantTexture(.12f, .55f, .85f, 1);
        glUseProgram(legacy);
        var json = com.google.gson.JsonParser.parseString(Files.readString(SHADERS.resolve(name + ".json"))).getAsJsonObject();
        for (var uniform : json.getAsJsonArray("uniforms")) {
            location(legacy, uniform.getAsJsonObject().get("name").getAsString());
        }
        glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
        glViewport(0, 0, W, H);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(location(legacy, "SceneColor"), 0);
        glUniform1i(location(legacy, "SceneDepth"), 1);
        glUniformMatrix4fv(location(legacy, "uProjMat"), false, PROJ.get(new float[16]));
        set(legacy, "uScreenSize", W, H);
        set(legacy, "uHolePx", W * .5f, H * .5f);
        set(legacy, "uLensRadiusPx", H * .46f);
        set(legacy, "uBgRect", 0, 0, W, H);
        set(legacy, "uBgTexel", 1f / W, 1f / H);
        set(legacy, "uBhView", 0, 0, -20);
        set(legacy, "uDiskNormal", 0, .92f, -.39f);
        set(legacy, "uRs", 1.6f);
        set(legacy, "uTanHalfFov", TAN);
        set(legacy, "uAspect", (float) W / H);
        set(legacy, "uKerrParameters", .65f, 1.7599342f, 3.6159372f);
        set(legacy, "uStrength", .42f);
        set(legacy, "uDiskGain", .62f);
        set(legacy, "uDiskHeat", .55f);
        set(legacy, "uRingGain", 1f);
        glClearColor(.24f, .42f, .66f, 1);
        int[] scenes = {warmScene, warmScene, coolScene, warmScene, coolScene};
        BufferedImage[] frames = new BufferedImage[scenes.length];
        for (int i = 0; i < frames.length; i++) {
            glClear(GL_COLOR_BUFFER_BIT);
            glBindTexture(GL_TEXTURE_2D, scenes[i]);
            set(legacy, "uTime", i == 1 ? 2f : 0f);
            set(legacy, "uSampleBackground", i >= 3 ? 1f : 0f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            frames[i] = readImage();
        }
        int animated = 0;
        int sceneChangesWithLens = 0;
        int sceneChangesWithoutLens = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (frames[0].getRGB(x, y) != frames[1].getRGB(x, y)) animated++;
                if (frames[0].getRGB(x, y) != frames[2].getRGB(x, y)) sceneChangesWithoutLens++;
                if (frames[3].getRGB(x, y) != frames[4].getRGB(x, y)) sceneChangesWithLens++;
            }
        }
        int body = frames[0].getRGB(W / 2, H / 2) & 0xFFFFFF;
        glDisable(GL_BLEND);
        glDeleteTextures(new int[]{warmScene, coolScene});
        glDeleteProgram(legacy);
        System.out.printf("Legacy optics: center=%06x, animated pixels=%d, scene-dependent pixels off/on=%d/%d%n",
                body, animated, sceneChangesWithoutLens, sceneChangesWithLens);
        if (body != 0 || animated < W * H / 100 || sceneChangesWithoutLens != 0 || sceneChangesWithLens < W * H / 2) {
            throw new AssertionError("Legacy shadow, disk animation or background switch is missing");
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
                if (scale < 1) drawCriticalStrip(0);
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
                        (double) W * H, 1, Math.hypot(camera[0], camera[2]), H * .2);
                rayTarget(plan.traceScale());
                trace(.65f, camera[1], camera[2], camera[0], (float) Quality.ADAPTIVE.tolerance,
                        Quality.ADAPTIVE.steps, .62f, 14);
                prepareComposite(camera[1], 0, 0);
                sampler("SceneColor", 0, world);
                set(composite, "uRefine", plan.subpixels());
                drawCriticalStrip(plan.transferScale());
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
                    if (boundedMae > .71 * pilotMae)
                        throw new AssertionError("Cached radiance lost contrast along the close shadow rim");
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

    private static void verifyGrazingStability(Path output) throws Exception {
        int world = constantTexture(.48f, .63f, .84f, 1);
        float distance = 8f;
        for (float screenX : new float[]{.50f, .85f, 1.02f, 1.18f}) {
            final float offset = (2 * screenX - 1) * distance / PROJ.m00();
            double observerDistance = Math.hypot(distance, offset);
            var plan = KerrSampling.plan(Quality.ADAPTIVE, Quality.ADAPTIVE.scale, .24,
                    (double) W * H, 1, observerDistance, H * .45);
            rayTarget(1);
            trace(.65f, 82, offset, distance, .000009f, 768, .62f, 14);
            drawGrazingComposite(world, 0, false);
            BufferedImage reference = readImage();
            BufferedImage first = null;
            for (int phase = 0; phase < 4; phase++) {
                rayTarget(plan.traceScale());
                trace(.65f, 82, offset, distance, (float) Quality.ADAPTIVE.tolerance,
                        Quality.ADAPTIVE.steps, .62f, 14);
                float shift = (float) KerrSampling.blackHoleJitter(plan.transferScale(), observerDistance);
                float dx = shift * (phase - 1.5f) * .25f;
                if (dx != 0) {
                    set(trace, "uRayJitter", dx, -dx);
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                }
                drawGrazingComposite(world, plan.transferScale(), true, dx);
                BufferedImage frame = readImage();
                if (first == null) first = frame;
                else for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                    if (frame.getRGB(x, y) != first.getRGB(x, y))
                        throw new AssertionError("Frozen grazing view flickered between phases");
                }
            }
            int count = 0;
            long error = 0;
            for (int y = 2; y < H - 2; y++) for (int x = 2; x < W - 2; x++) {
                int blue = reference.getRGB(x, y) & 255;
                int gx = (reference.getRGB(x + 1, y) & 255) - (reference.getRGB(x - 1, y) & 255);
                int gy = (reference.getRGB(x, y + 1) & 255) - (reference.getRGB(x, y - 1) & 255);
                if (Math.max(Math.abs(gx), Math.abs(gy)) < 22) continue;
                count++;
                error += Math.abs((first.getRGB(x, y) & 255) - blue);
            }
            double mae = (double) error / Math.max(1, count);
            System.out.printf(Locale.ROOT, "Grazing centre x=%.2f: %d edge pixels, blue MAE %.3f /255, 4 stable phases%n",
                    screenX, count, mae);
            if (count < H / 2 || mae > 12) throw new AssertionError("Grazing edge lost at " + screenX);
            ImageIO.write(first, "PNG", output.resolve("grazing-" + screenX + ".png").toFile());
        }
        glDeleteTextures(world);
    }

    private static void drawGrazingComposite(int world, double transferScale, boolean strip) {
        drawGrazingComposite(world, transferScale, strip, 0);
    }

    private static void drawGrazingComposite(int world, double transferScale, boolean strip, float jitter) {
        prepareComposite(82, 0, 0);
        set(composite, "uRayJitter", jitter / rayW, -jitter / rayH);
        set(composite, "uRefine", transferScale > 0 ? 2 : 0);
        sampler("SceneColor", 0, world);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uStrength", 0);
        if (strip) drawCriticalStrip(transferScale);
        if (transferScale > 0) bakeFootprintsAtScale(transferScale);
        glDrawArrays(GL_TRIANGLES, 0, 3);
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
        if (edgePixels == 0 || error / edgePixels > .004) throw new AssertionError("Inaccurate pixel coverage at close shadow");

        // Changing the clock at presentation cannot add an emissive outline.
        set(composite, "uDiskGain", .62f);
        set(composite, "uTime", 32, 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage presented = readImage();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (presented.getRGB(x, y) != diskOff.getRGB(x, y)) throw new AssertionError("Shadow presentation added light");
        }
        glDeleteTextures(white);
        rayTarget(.72);
    }

    // Reproduce the 1024 x 640 Adaptive preview after its frame budget settles.
    // Include the foreground disk crossing the shadow, well inside the critical curve.
    private static void verifyDiskEdge(Path output) throws Exception {
        perspective(70);
        verifyDiskEdgeScene(output, false, 0, 36);
        verifyDiskEdgeScene(output, true, 0, 36);
        verifyDiskEdgeScene(output, false, .18f, 36);
    }

    private static void verifyDiskEdgeScene(Path output, boolean stars, float bloom, float distance) throws Exception {
        verifyDiskEdgeScene(output, stars, bloom, distance, false);
    }

    private static void verifyDiskEdgeScene(Path output, boolean stars, float bloom, float distance, boolean timed) throws Exception {
        boolean nearHost = distance == 11;
        double transferScale = nearHost ? .103 : .22;
        float gain = stars ? 0 : .62f;
        int background = stars ? 1 : 2;
        glUseProgram(trace);
        set(trace, "uCriticalBounds", 0, 0, 0, 0);
        glUseProgram(composite);
        set(composite, "uDiskDetailRect", 0, 0, 1, 0);
        rayTarget(2);
        trace(.95f, 80, 0, distance, .000009f, 768, gain, 14);
        prepareComposite(80, background, bloom);
        if (nearHost) set(composite, "uSampleBackground", 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage reference = readImage();
        if (nearHost) rayTarget(160, 96);
        else rayTarget((W * 136 + 512) / 1024, (H * 88 + 320) / 640);
        trace(.95f, 80, 0, distance, .00022f, 384, gain, 14);
        prepareComposite(80, background, bloom);
        if (nearHost) set(composite, "uSampleBackground", 0);
        set(composite, "uRefine", 2);
        float radius = (float) (Math.sqrt(27) * H * PROJ.m11() / (2 * distance));
        set(composite, "uShadowPixels", radius);
        drawCriticalStrip(transferScale);
        bakeFootprintsAtScale(transferScale);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage coarse = readImage();
        var detail = new KerrSampling(rayW / (double) W, transferScale, 2).diskDetail(W * .5, H * .5, radius, W, H, distance);
        if (detail == null) throw new AssertionError("No disk detail for shadow radius " + radius);
        int detailTexture = texture(detail.samples(), detail.samples(), GL_RGBA16F, GL_LINEAR);
        int detailExit = texture(detail.samples(), detail.samples(), GL_RGBA16F, GL_NEAREST);
        int detailFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, detailFbo);
        int outputIndex = glGetFragDataLocation(trace, "rayLight");
        attach(detailTexture, GL_COLOR_ATTACHMENT0);
        int exitIndex = glGetFragDataLocation(trace, "rayExit");
        attach(detailExit, GL_COLOR_ATTACHMENT1);
        int[] outputs = new int[1 + Math.max(outputIndex, exitIndex)];
        outputs[outputIndex] = GL_COLOR_ATTACHMENT0;
        outputs[exitIndex] = GL_COLOR_ATTACHMENT1;
        glDrawBuffers(outputs);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        checkFbo();
        glViewport(0, 0, detail.samples(), detail.samples());
        glUseProgram(trace);
        set(trace, "uCriticalBounds", 0, 0, 0, 3);
        set(trace, "uTraceSize", detail.samples(), detail.samples());
        set(trace, "uTraceRect", detail.x(), detail.y(), detail.side(), detail.side());
        int timer = glGenQueries();
        glBeginQuery(GL_TIME_ELAPSED, timer);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glEndQuery(GL_TIME_ELAPSED);
        double detailMs = timed ? medianDrawTime(() -> glDrawArrays(GL_TRIANGLES, 0, 3))
                : glGetQueryObjectui64(timer, GL_QUERY_RESULT) * 1e-6;
        glDeleteQueries(timer);
        BufferedImage coreOnly, actual;
        double arcMs = 0;
        try (var detailHdr = new WhiteHoleHDRPostProcessing(extension ->
                Files.readString(SHADERS.resolve("../whitehole/bright_pass." + extension)))) {
            int glow = bloom > 0 ? detailHdr.draw(detailTexture, detail.samples(), detail.samples(), 1.15f) : 0;
            glUseProgram(composite);
            sampler("DiskDetailBloom", 15, glow);
            set(composite, "uDiskBloomLod", glow != 0 ? detailHdr.lastLevel() : 0,
                    KerrSampling.bloomLodBias(detail.samples(), detail.samples(), detail.side(), detail.side(), H));
            sampler("DiskDetail", 13, detailTexture);
            sampler("LocalRayExit", 14, detailExit);
            set(composite, "uDiskDetailRect", detail.x(), detail.y(), detail.side(), detail.weight());
            glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
            glViewport(0, 0, W, H);
            sampler("PixelTransfer", 6, transferTex);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            coreOnly = readImage();
            if (!stars) {
                int arcTimer = glGenQueries();
                glBeginQuery(GL_TIME_ELAPSED, arcTimer);
                drawCriticalStrip(transferScale, false, false, true);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glEndQuery(GL_TIME_ELAPSED);
                arcMs = glGetQueryObjectui64(arcTimer, GL_QUERY_RESULT) * 1e-6;
                glDeleteQueries(arcTimer);
            }
            actual = stars ? coreOnly : readImage();
        }
        glDeleteFramebuffers(detailFbo);
        glDeleteTextures(detailTexture);
        glDeleteTextures(detailExit);
        String name = stars ? "lensed-stars" : bloom > 0 ? "disk-edge-bloom" : "disk-edge";
        if (nearHost) name += "-host";
        ImageIO.write(actual, "PNG", output.resolve(name + ".png").toFile());
        ImageIO.write(coarse, "PNG", output.resolve(name + "-coarse.png").toFile());
        ImageIO.write(reference, "PNG", output.resolve(name + "-reference.png").toFile());
        if (!stars) {
            ImageIO.write(coreOnly, "PNG", output.resolve(name + "-core.png").toFile());
            verifyArcDetail(name, actual, coreOnly, reference, radius, arcMs);
        }
        long error = 0, coarseError = 0;
        int edges = 0;
        for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
            if (Math.hypot(x - W * .5, y - H * .5) > 1.5 * radius) continue;
            int dx = (reference.getRGB(x + 1, y) >> 16 & 255) - (reference.getRGB(x - 1, y) >> 16 & 255);
            int dy = (reference.getRGB(x, y + 1) >> 16 & 255) - (reference.getRGB(x, y - 1) >> 16 & 255);
            if (bloom > 0 && (reference.getRGB(x, y) >> 16 & 255) > 100) continue;
            if (!stars && bloom == 0 && Math.hypot(dx, dy) < 18) continue;
            for (int shift = 0; shift <= 16; shift += 8) {
                int target = reference.getRGB(x, y) >> shift & 255;
                error += Math.abs((actual.getRGB(x, y) >> shift & 255) - target);
                coarseError += Math.abs((coarse.getRGB(x, y) >> shift & 255) - target);
            }
            edges++;
        }
        double mae = error / (3.0 * edges);
        double baseline = coarseError / (3.0 * edges);
        System.out.printf(Locale.ROOT, "High-spin %s: %d pixels, mean RGB error %.3f -> %.3f /255, detail %dx%d %.2f ms GPU%n",
                name, edges, baseline, mae, detail.samples(), detail.samples(), detailMs);
        if (edges < 300 || !(mae < .8 * baseline) || (!stars && mae > 5))
            throw new AssertionError("Under-resolved " + name);
    }

    private static void verifyArcDetail(String name, BufferedImage actual, BufferedImage coreOnly,
                                        BufferedImage reference, float radius, double arcMs) {
        long[] errors = new long[2], baseline = new long[2];
        int[] pixels = new int[2];
        for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
            float dx = x - W * .5f, dy = y - H * .5f;
            if (Math.abs(dx) > radius || Math.abs(dy) < .8f * radius || Math.hypot(dx, dy) > 1.62 * radius) continue;
            int target = reference.getRGB(x, y) >> 16 & 255;
            int radialContrast = (reference.getRGB(x, y + 1) >> 16 & 255) - (reference.getRGB(x, y - 1) >> 16 & 255);
            if (Math.abs(radialContrast) < 7) continue;
            int arc = dy < 0 ? 0 : 1;
            errors[arc] += Math.abs((actual.getRGB(x, y) >> 16 & 255) - target);
            baseline[arc] += Math.abs((coreOnly.getRGB(x, y) >> 16 & 255) - target);
            pixels[arc]++;
        }
        System.out.printf(Locale.ROOT,
                "%s lensed arcs: upper %.3f -> %.3f, lower %.3f -> %.3f /255 (%d + %d pixels), strip and composite %.2f ms GPU%n",
                name, baseline[0] / (double) pixels[0], errors[0] / (double) pixels[0],
                baseline[1] / (double) pixels[1], errors[1] / (double) pixels[1], pixels[0], pixels[1], arcMs);
        if (radius >= 80) {
            for (int arc = 0; arc < 2; arc++) {
                if (pixels[arc] < 300 || errors[arc] >= .85 * baseline[arc]) {
                    throw new AssertionError(name + (arc == 0 ? " upper" : " lower") + " lensed arc is under-resolved");
                }
            }
        }
    }

    private static void verifyCriticalRadiance(Path output) throws Exception {
        int supersample = 4;
        rayTarget(supersample);
        trace(.65f, 65, 0, 26, .000006f, 768, .62f, 14);
        FloatBuffer referencePaths = readExit();
        prepareComposite(65, 2, 0);
        glActiveTexture(GL_TEXTURE2);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage reference = readImage();
        BufferedImage[] renders = new BufferedImage[4];
        for (int i = 0; i < renders.length; i++) {
            rayTarget(i < 2 ? .72 : .13);
            renderCriticalScene(i % 2 == 1, i < 2 ? 0 : .22);
            renders[i] = readImage();
        }
        ImageIO.write(renders[0], "PNG", output.resolve("critical-unaligned.png").toFile());
        ImageIO.write(renders[1], "PNG", output.resolve("critical-aligned.png").toFile());
        ImageIO.write(renders[2], "PNG", output.resolve("critical-cached-before.png").toFile());
        ImageIO.write(renders[3], "PNG", output.resolve("critical-cached-after.png").toFile());
        ImageIO.write(reference, "PNG", output.resolve("critical-reference.png").toFile());
        double[] error = new double[renders.length];
        int count = 0;
        int stride = W * supersample;
        for (int y = 2; y < H - 2; y++) for (int x = 2; x < W - 2; x++) {
            int rx = x * supersample + 2, ry = (H - 1 - y) * supersample + 2;
            boolean captured = referencePaths.get(4 * (ry * stride + rx) + 3) > 1.5f;
            boolean mixed = false;
            for (int dy = -4; dy <= 4; dy += 8) for (int dx = -4; dx <= 4; dx += 8) {
                mixed |= (referencePaths.get(4 * ((ry + dy) * stride + rx + dx) + 3) > 1.5f) != captured;
            }
            if (!mixed) continue;
            count++;
            int expected = reference.getRGB(x, y);
            for (int i = 0; i < renders.length; i++) {
                int actual = renders[i].getRGB(x, y);
                for (int channel = 0; channel <= 16; channel += 8) {
                    error[i] += Math.abs(((actual >> channel) & 255) - ((expected >> channel) & 255));
                }
            }
        }
        System.out.printf(Locale.ROOT, "Critical radiance: %d pixels, mean RGB error %.3f -> %.3f /255%n",
                count, error[0] / (count * 3), error[1] / (count * 3));
        if (count < 300 || error[1] > .8 * error[0]) throw new AssertionError("Critical radiance did not converge toward supersampling");
        System.out.printf(Locale.ROOT, "Critical radiance, cached: mean RGB error %.3f -> %.3f /255%n",
                error[2] / (count * 3), error[3] / (count * 3));
        if (error[3] > .8 * error[2]) throw new AssertionError("Cached critical radiance remains under-resolved");
        int timer = glGenQueries();
        try {
            for (double transferScale : new double[]{0, .22}) {
                rayTarget(transferScale == 0 ? .72 : .13);
                for (boolean useStrip : new boolean[]{false, true}) {
                    for (int i = 0; i < 4; ++i) renderCriticalScene(useStrip, transferScale);
                    glFinish();
                    glBeginQuery(GL_TIME_ELAPSED, timer);
                    for (int i = 0; i < 18; ++i) renderCriticalScene(useStrip, transferScale);
                    glEndQuery(GL_TIME_ELAPSED);
                    System.out.printf(Locale.ROOT, "Critical strip %s (transfer scale %.2f): %.3f ms GPU%n",
                            useStrip ? "on" : "off", transferScale, glGetQueryObjectui64(timer, GL_QUERY_RESULT) / 18e6);
                }
            }
        } finally {
            glDeleteQueries(timer);
        }
        rayTarget(.72);
    }

    private static void renderCriticalScene(boolean useStrip, double transferScale) {
        trace(.65f, 65, 0, 26, .00006f, 480, .62f, 14);
        prepareComposite(65, 2, 0);
        set(composite, "uRefine", transferScale == 0 ? 4 : 2);
        if (useStrip) drawCriticalStrip(transferScale);
        if (transferScale > 0) bakeFootprintsAtScale(transferScale);
        glDrawArrays(GL_TRIANGLES, 0, 3);
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

    private static void verifyGrazingDepth() {
        int depth = glGenRenderbuffers();
        int skyColor = constantTexture(.67f, .76f, .95f, 1);
        float distance = 15;
        int x = W * 3 / 4, y = H / 2;
        float offset = (2 * (x + .5f) / W - 1) * distance / PROJ.m00();
        prepareExterior(distance, 65, H * (float) BlackHoleLensMath.B_CRIT / (TAN * distance), 0, skyColor);
        set(composite, "uBhView", offset, 0, -distance);
        set(composite, "uSampleBackground", 0);
        set(composite, "uKerrDepth", 1);
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        checkFbo();
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        try {
            glClearColor(.67f, .76f, .95f, 1);
            glClearDepth(windowDepth(distance - 1));
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glDepthMask(false);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (redAt(x, y) < 160) throw new AssertionError("Kerr overwrote a genuine foreground occluder");
            glDepthMask(true);
            glClearDepth(windowDepth(distance + 1));
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glDepthMask(false);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            if (redAt(x, y) > 3) throw new AssertionError("Off-axis Kerr shadow was lost against a background surface");
        } finally {
            glDepthMask(true);
            glClearDepth(1);
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
            glDeleteRenderbuffers(depth);
            glDeleteTextures(skyColor);
            set(composite, "uKerrDepth", 0);
            set(composite, "uExterior", 0, 0, 0);
        }
        System.out.println("Off-axis Kerr keeps its shadow behind foreground and in front of background");
    }

    private static void verifyAnalyticExterior(Path output) throws Exception {
        int skyColor = constantTexture(.67f, .76f, .95f, 1);
        int decoy = constantTexture(7, 0, 5, .3f);
        float savedDistance = cameraDistance, savedOffset = cameraOffset, savedDisk = diskGain;
        try {
            // Deliberately use no traced radiance, including at the close
            // distance where the old presentation enlarged a coarse texture.
            int query = glGenQueries();
            for (float distance : new float[]{11.5f, 29.3f, 180f, 4100f}) {
                float radius = H * (float) BlackHoleLensMath.B_CRIT / (TAN * distance);
                prepareExterior(distance, distance < 20 ? 28 : 77, radius, .62f, skyColor);
                double[] timings = new double[3];
                glEnable(GL_BLEND);
                glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                glClearColor(.67f, .76f, .95f, 1);
                for (int run = 0; run < timings.length; ++run) {
                    glBeginQuery(GL_TIME_ELAPSED, query);
                    for (int i = 0; i < 18; ++i) glDrawArrays(GL_TRIANGLES, 0, 3);
                    glEndQuery(GL_TIME_ELAPSED);
                    timings[run] = glGetQueryObjectui64(query, GL_QUERY_RESULT) / 18e6;
                }
                java.util.Arrays.sort(timings);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                BufferedImage image = readImage();
                ImageIO.write(image, "PNG", output.resolve("exterior-" + distance + ".png").toFile());
                sampler("RayLight", 2, decoy);
                sampler("RayExit", 3, decoy);
                sampler("PixelTransfer", 6, decoy);
                set(composite, "uTransferPass", 2);
                set(composite, "uHistoryWeight", .94f);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                BufferedImage stale = readImage();
                for (int y = 0; y < H; ++y) {
                    for (int x = 0; x < W; ++x) {
                        if (image.getRGB(x, y) != stale.getRGB(x, y))
                            throw new AssertionError("Analytic exterior read the transfer cache");
                    }
                }
                if (redAt(W / 2, H / 2) > (radius >= 3 ? 4 : 176))
                    throw new AssertionError("Analytic exterior lost its central shadow at " + distance);
                System.out.printf(Locale.ROOT, "Analytic exterior %.1f M: radius=%.1f px, %.3f ms%n",
                        distance, radius, timings[1]);
            }
            glDeleteQueries(query);
            prepareExterior(11.5f, 28, H * (float) BlackHoleLensMath.B_CRIT / (TAN * 11.5f), 0, skyColor);
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            int start = H / 2;
            while (start < H - 1 && redAt(W / 2, start) <= 1) start++;
            int end = start;
            while (end < H - 1 && redAt(W / 2, end) < 169) end++;
            if (end >= H - 1 || end - start > 2)
                throw new AssertionError("Analytic silhouette transition exceeded two native pixels: " + (end - start));
            System.out.printf("Analytic silhouette 0-99%% transition: %d native pixels%n", end - start);

            for (int inclination : new int[]{65, 86}) {
                float radius = H * (float) BlackHoleLensMath.B_CRIT / (TAN * 29.3f);
                prepareExterior(29.3f, inclination, radius, .62f, skyColor);
                set(composite, "uSkyMix", 0, 1, 0);
                glClearColor(.025f, .035f, .055f, 1);
                glClear(GL_COLOR_BUFFER_BIT);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                int arcX = Math.round(W * .5f + .27f * radius);
                int crest = 0;
                for (int y = H / 2 + Math.round(radius * 1.12f); y <= H / 2 + Math.round(1.65f * radius); ++y) {
                    crest = Math.max(crest, redAt(arcX, y));
                }
                if (crest < 115 || redAt(arcX, H / 2 + Math.round(2.3f * radius)) > 50)
                    throw new AssertionError("The lensed disk lost its upper crest or spilled beyond its arc at " + inclination);
                ImageIO.write(readImage(), "PNG", output.resolve("exterior-reference-" + inclination + ".png").toFile());
            }
        } finally {
            glDisable(GL_BLEND);
            set(composite, "uExterior", 0, 0, 0);
            set(composite, "uTransferPass", 0);
            set(composite, "uHistoryWeight", 0);
            cameraDistance = savedDistance;
            cameraOffset = savedOffset;
            diskGain = savedDisk;
            glDeleteTextures(new int[]{skyColor, decoy});
        }
    }

    private static void prepareExterior(float distance, float inclination, float radius, float disk, int skyColor) {
        glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
        glViewport(0, 0, W, H);
        glUseProgram(composite);
        matrix(composite, "uInvProj", INV_PROJ);
        matrix(composite, "uProjMat", PROJ);
        axis(composite, inclination);
        sampler("SceneColor", 0, skyColor);
        set(composite, "uScreenSize", W, H);
        set(composite, "uBhView", 0, 0, -distance);
        set(composite, "uRs", 2);
        set(composite, "uSkyMix", 1, 0, 0);
        set(composite, "uTransferPass", 0);
        set(composite, "uTraceRect", 0, 0, W, H);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uStrength", 1);
        set(composite, "uSampleBackground", 1);
        set(composite, "uDepthGate", 0);
        set(composite, "uCaptureDepthOnly", 0);
        set(composite, "uWhiteHole", 0, 0, 0, 0);
        set(composite, "uWhiteObserverBeta", 0);
        set(composite, "uExterior", 1, W * .5f, H * .5f);
        set(composite, "uShadowPixels", radius);
        set(composite, "uKerrParameters", .65f, 1 + (float) Math.sqrt(1 - .65 * .65), isco(.65f), 1);
        set(composite, "uDiskGain", disk);
        set(composite, "uDiskHeat", .10f);
        set(composite, "uDiskPhysics", 1, .055f, 9400, .38f);
        set(composite, "uTime", 14, 0);
        set(composite, "uDisplay", 1.15f, .18f);
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
        verifyWorldStructure(output, false);
    }

    private static void verifyWorldStructure(Path output, boolean timed) throws Exception {
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
        if (timed) {
            // Keep the camera, depth rejection and donor textures the same in both modes.
            double worldMs = medianDrawTime(() -> glDrawArrays(GL_TRIANGLES, 0, 3));
            set(composite, "uSampleBackground", 0);
            double bodyMs = medianDrawTime(() -> glDrawArrays(GL_TRIANGLES, 0, 3));
            set(composite, "uSampleBackground", 1);
            System.out.printf(Locale.ROOT, "World composite %.3f ms, body-only %.3f ms%n", worldMs, bodyMs);
        }
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
        set(composite, "uDisplay", 1.15f, 0);
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

    private static void verifyWorldMinification() {
        // A smooth donor map compresses two independent stripe patterns. Red and
        // green let the test distinguish filtering in each direction.
        ByteBuffer colors = BufferUtils.createByteBuffer(W * H * 4);
        FloatBuffer directions = BufferUtils.createFloatBuffer(W * H * 4);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            colors.put((byte) ((x / 2 % 2) * 255)).put((byte) ((y / 2 % 2) * 255)).put((byte) 0).put((byte) 255);
            float dx = x + .5f - W / 2f, dy = y + .5f - H / 2f;
            float outgoingX = dx + Math.clamp(11 * dx, -90, 90);
            float outgoingY = dy + Math.clamp(4 * dy, -55, 55);
            directions.put(2 * outgoingX / (W * PROJ.m00())).put(2 * outgoingY / (H * PROJ.m11())).put(-1).put(1);
        }
        int world = texture(W, H, GL_RGBA8, GL_LINEAR);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, colors.flip());
        int rays = texture(W, H, GL_RGBA32F, GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_FLOAT, directions.flip());
        int dark = constantTexture(0, 0, 0, 1);
        prepareComposite(80, 0, 0);
        sampler("RayLight", 2, dark);
        sampler("RayExit", 3, rays);
        sampler("SceneColor", 0, world);
        set(composite, "uDiskDetailRect", 0, 0, 1, 0);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uShadowPixels", W / 2f);
        set(composite, "uStrength", 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage image = readImage();
        glDeleteTextures(new int[]{world, rays, dark});
        int[] lo = {255, 255}, hi = {0, 0};
        for (int y = H / 2 - 6; y < H / 2 + 6; y++) for (int x = W / 2 - 6; x < W / 2 + 6; x++) {
            int rgb = image.getRGB(x, y);
            for (int c = 0; c < 2; c++) {
                int value = rgb >> (16 - 8 * c) & 255;
                lo[c] = Math.min(lo[c], value);
                hi[c] = Math.max(hi[c], value);
            }
        }
        System.out.printf("Compressed stripe residual: horizontal %d, vertical %d /255%n", hi[0] - lo[0], hi[1] - lo[1]);
        if (lo[0] < 130 || lo[1] < 130 || hi[0] - lo[0] > 75 || hi[1] - lo[1] > 75)
            throw new AssertionError("World minification left periodic aliasing");
    }

    private static void verifyWorldDonorBoundary() {
        // Uniform displacement has a one-texel footprint even where a
        // foreground texel forces the adjacent fragment back to its self donor.
        ByteBuffer colors = BufferUtils.createByteBuffer(W * H * 4);
        FloatBuffer directions = BufferUtils.createFloatBuffer(W * H * 4);
        FloatBuffer depths = BufferUtils.createFloatBuffer(W * H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            colors.put((byte) (255 * (x & 1))).put((byte) 0)
                    .put((byte) (x > W / 2 + 10 ? 255 : 0)).put((byte) 255);
            directions.put((2 * (x + 48.5f) / W - 1) / PROJ.m00())
                    .put((2 * (y + .5f) / H - 1) / PROJ.m11()).put(-1).put(1);
            int blocker = W / 2 + 39 + ((y >> 2) & 1);
            depths.put(x == blocker ? .1f : 1f);
        }
        int world = texture(W, H, GL_RGBA8, GL_LINEAR);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, colors.flip());
        int rays = texture(W, H, GL_RGBA32F, GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_FLOAT, directions.flip());
        int depth = texture(W, H, GL_R32F, GL_NEAREST);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RED, GL_FLOAT, depths.flip());
        int dark = constantTexture(0, 0, 0, 1);
        prepareComposite(80, 0, 0);
        sampler("RayLight", 2, dark);
        sampler("RayExit", 3, rays);
        sampler("SceneColor", 0, world);
        sampler("SceneDepth", 1, depth);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uShadowPixels", W / 2f);
        set(composite, "uStrength", 1);
        set(composite, "uHoleDepth", .5f);
        set(composite, "uGateEps", .0001f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage unblocked = readImage();
        set(composite, "uDepthGate", 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage blocked = readImage();
        glDeleteTextures(new int[]{world, rays, depth, dark});
        int rejected = 0, retained = 0, maxError = 0;
        for (int y = H / 2 - 8; y < H / 2 + 8; y++) for (int x = W / 2 - 6; x < W / 2 + 5; x++) {
            int expected = unblocked.getRGB(x, y);
            int actual = blocked.getRGB(x, y);
            if ((expected & 255) < 250) continue;
            if ((actual & 255) < 250) {
                rejected++;
            } else {
                retained++;
                maxError = Math.max(maxError, Math.abs((actual >> 16 & 255) - (expected >> 16 & 255)));
            }
        }
        System.out.printf("Donor boundary: %d rejected, %d retained, max red-channel error %d /255%n",
                rejected, retained, maxError);
        if (rejected == 0 || retained == 0 || maxError > 3) {
            throw new AssertionError("Foreground rejection blurred adjacent valid world samples");
        }
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

    private static void verifyColdDiskAndHistory() {
        // The absorbing gas remains present, but a cold disk emits no visible light.
        trace(.65f, 86, 0, 26, .00006f, 480, .62f, 14);
        set(trace, "uDiskPhysics", 1, .055f, 1, .38f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        prepareComposite(86, 2, .18f);
        set(composite, "uDiskPhysics", 1, .055f, 1, .38f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        BufferedImage cold = readImage();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if ((cold.getRGB(x, y) & 0xffffff) != 0) throw new AssertionError("Cold disk emitted visible light");
        }

        // A previously bright ray must be rejected even if its optical depth agrees.
        glActiveTexture(GL_TEXTURE4);
        int previousLight = constantTexture(2, 1, .5f, 1);
        try {
            sampler("PreviousLight", 4, previousLight);
            sampler("PreviousExit", 5, exit);
            matrix(composite, "uPreviousProjection", PROJ);
            matrix(composite, "uPreviousToView", new Matrix4f());
            set(composite, "uPreviousRect", 0, 0, W, H);
            set(composite, "uHistoryWeight", .32f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            BufferedImage history = readImage();
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                if (history.getRGB(x, y) != cold.getRGB(x, y)) throw new AssertionError("Stale disk history");
            }
        } finally {
            set(composite, "uHistoryWeight", 0);
            glDeleteTextures(previousLight);
        }
        System.out.println("Cold disk shadow and stale radiance rejection passed");
    }

    private static void verifyCachedDeflection() {
        trace(.65f, 80, 2, 4.7f, .00006f, 480, .62f, 14);
        glActiveTexture(GL_TEXTURE9);
        int cache = texture(rayW, rayH, GL_RGBA32F, GL_NEAREST);
        glReadBuffer(GL_COLOR_ATTACHMENT0 + glGetFragDataLocation(trace, "rayExit"));
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, rayW, rayH);
        trace(.65f, 80, 2, 4.7f, .00006f, 480, .62f, 32);
        FloatBuffer expected = readRayBuffer("rayLight");
        FloatBuffer expectedExit = readExit();
        try {
            glUniform1i(glGetUniformLocation(trace, "CachedRayExit"), 9);
            set(trace, "uReuseRayExit", 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            FloatBuffer cached = readRayBuffer("rayLight");
            FloatBuffer cachedExit = readExit();
            double error = 0;
            for (int i = 0; i < cached.capacity(); i++) {
                if (cachedExit.get(i) != expectedExit.get(i)) throw new AssertionError("Cached deflection changed a ray endpoint");
                error = Math.max(error, Math.abs(cached.get(i) - expected.get(i)) / (1 + Math.abs(expected.get(i))));
            }
            System.out.printf(Locale.ROOT, "Cached deflection preserved endpoints, largest relative transfer error %.6g%n", error);
            if (error > .002) throw new AssertionError("Cached deflection changed visible disk transfer");
        } finally {
            set(trace, "uReuseRayExit", 0);
            glDeleteTextures(cache);
        }
    }

    static int constantTexture(float r, float g, float b, float a) {
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

    private static double medianDrawTime(Runnable draw) {
        for (int i = 0; i < 6; ++i) draw.run();
        int timer = glGenQueries();
        double[] batches = new double[5];
        for (int i = 0; i < batches.length; ++i) {
            glBeginQuery(GL_TIME_ELAPSED, timer);
            for (int frame = 0; frame < 20; ++frame) draw.run();
            glEndQuery(GL_TIME_ELAPSED);
            batches[i] = glGetQueryObjectui64(timer, GL_QUERY_RESULT) / 20e6;
        }
        glDeleteQueries(timer);
        java.util.Arrays.sort(batches);
        return batches[batches.length / 2];
    }

    private static void verifyTransferOnly() {
        if (glGetUniformLocation(trace, "uTransferOnly") < 0) return;
        for (float gain : new float[]{.62f, 0}) {
            Runnable draw = () -> trace(.95f, 80, 0, 46, .00022f, 384, gain, 14);
            double fullMs = medianDrawTime(draw);
            FloatBuffer fullLight = readRayBuffer("rayLight"), fullExit = readExit();
            set(trace, "uTransferOnly", 1);
            double bodyMs = medianDrawTime(draw);
            FloatBuffer bodyLight = readRayBuffer("rayLight"), bodyExit = readExit();
            set(trace, "uTransferOnly", 0);
            int changedCapture = 0;
            double largestLightDelta = 0;
            for (int i = 0; i < fullExit.capacity(); i += 4) {
                if ((fullExit.get(i + 3) > 1.5f) != (bodyExit.get(i + 3) > 1.5f)) changedCapture++;
                for (int c = 0; c < 4; ++c)
                    largestLightDelta = Math.max(largestLightDelta, Math.abs(fullLight.get(i + c) - bodyLight.get(i + c)));
            }
            System.out.printf(Locale.ROOT, "Trace disk gain %.2f: %.3f -> %.3f ms, capture changes %d, largest linear delta %.5f%n",
                    gain, fullMs, bodyMs, changedCapture, largestLightDelta);
            if (changedCapture > 4 || largestLightDelta > .001)
                throw new AssertionError("Transfer-only trace changed visible disk light or the capture mask");
        }
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
                        area, 1, distance, H * .2);
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

    static void initializePipeline() throws Exception {
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
    }

    static void trace(float spin, float inclination, float offset, float distance, float tolerance,
                              int steps, float disk, float time) {
        trace(spin, inclination, offset, distance, tolerance, steps, disk, time, 0, 0, W, H);
    }

    static void trace(float spin, float inclination, float offset, float distance, float tolerance,
                      int steps, float disk, float time, int x, int y, int w, int h) {
        cameraDistance = distance;
        cameraOffset = offset;
        diskGain = disk;
        glBindFramebuffer(GL_FRAMEBUFFER, rayFbo);
        glViewport(0, 0, rayW, rayH);
        glUseProgram(trace);
        bindRayCamera(trace, inclination, offset, distance);
        set(trace, "uTraceRect", x, y, w, h);
        set(trace, "uTraceSize", rayW, rayH);
        set(trace, "uRayJitter", 0, 0);
        glUniform1f(glGetUniformLocation(trace, "uOuterDisk"), 40f);
        if (!diagnostic) {
            glUniform1fv(location(trace, "uDiskSqrtFlux"), diskModel.profile(new KerrGeometry(spin), 1, 40));
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
            set(composite, "uOuterDisk", 40f);
            glUniform1fv(location(composite, "uDiskSqrtFlux"), diskModel.profile(new KerrGeometry(spin), 1, 40));
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

    static void bindRayCamera(int program, float inclination, float offset, float distance) {
        matrix(program, "uInvProj", INV_PROJ);
        set(program, "uBhView", offset, 0, -distance);
        axis(program, inclination);
        set(program, "uScreenSize", W, H);
        set(program, "uRs", 2);
    }

    private static void compose(float inclination, float mode, float bloom) {
        prepareComposite(inclination, mode, bloom);
        set(composite, "uRefine", 4);
        drawCriticalStrip(0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void composeAdaptive(float inclination, KerrSampling plan) {
        prepareComposite(inclination, 1, .18f);
        set(composite, "uRefine", plan.subpixels());
        drawCriticalStrip(plan.transferScale());
        if (plan.transferScale() > 0) bakeFootprintsAtScale(plan.transferScale());
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void drawCriticalStrip(double transferScale) {
        drawCriticalStrip(transferScale, false);
    }

    static void drawCriticalStrip(double transferScale, boolean whiteWorld) {
        drawCriticalStrip(transferScale, whiteWorld, false);
    }

    static void drawCriticalStrip(double transferScale, boolean whiteWorld, boolean skyRays) {
        drawCriticalStrip(transferScale, whiteWorld, skyRays, false);
    }

    private static void drawCriticalStrip(double transferScale, boolean whiteWorld, boolean skyRays, boolean extend) {
        float radius = (float) (Math.sqrt(27) * H * PROJ.m11() / (2 * cameraDistance));
        boolean cached = !whiteWorld && transferScale > 0;
        double observerDistance = Math.hypot(cameraDistance, cameraOffset);
        int width = whiteWorld ? KerrSampling.criticalStripWidth(radius, H, observerDistance)
                : KerrSampling.blackHoleCriticalStripWidth(radius, H, observerDistance, cached);
        float span = whiteWorld ? 12f : KerrSampling.blackHoleCriticalStripSpan(transferScale, observerDistance);
        if (width == 0 || span <= 0 || (!whiteWorld && diskGain <= 0)) return;
        float x = .5f * W * (1 + PROJ.m00() * cameraOffset / cameraDistance), y = .5f * H;
        int coreRows = cached ? KerrSampling.CACHED_CRITICAL_STRIP_ROWS : KerrSampling.CRITICAL_STRIP_ROWS;
        var arc = KerrSampling.criticalArc(radius, coreRows, width, extend);
        int rows = arc.rows();
        width = arc.columns();
        if (stripWidth != width || stripRows != rows || skyRays != (stripExit != 0)) {
            if (radiiFbo != 0) {
                glDeleteFramebuffers(radiiFbo);
                glDeleteFramebuffers(stripFbo);
                glDeleteTextures(radiiTex);
                glDeleteTextures(stripTex);
                glDeleteTextures(stripExit);
            }
            glActiveTexture(GL_TEXTURE11);
            radiiTex = texture(width, 1, GL_RGBA32F, GL_LINEAR);
            radiiFbo = stripTarget(radiiTex, 0);
            stripTex = texture(width, rows, GL_RGBA16F, GL_LINEAR);
            stripExit = skyRays ? texture(width, rows, GL_RGBA16F, GL_NEAREST) : 0;
            stripFbo = stripTarget(stripTex, stripExit);
            stripWidth = width;
            stripRows = rows;
        }
        glUseProgram(trace);
        glActiveTexture(GL_TEXTURE12);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUniform1i(location(trace, "CriticalRadii"), 12);
        glBindFramebuffer(GL_FRAMEBUFFER, radiiFbo);
        glViewport(0, 0, width, 1);
        set(trace, "uCriticalBounds", x, y, radius * 2.5f, 1);
        set(trace, "uTraceSize", width, 1);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        glBindTexture(GL_TEXTURE_2D, radiiTex);
        glBindFramebuffer(GL_FRAMEBUFFER, stripFbo);
        glViewport(0, 0, width, rows);
        set(trace, "uCriticalBounds", x, y, span, skyRays ? 4 : 2);
        set(trace, "uCriticalArc", arc.inner(), arc.outer());
        set(trace, "uTraceSize", width, rows);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        set(trace, "uCriticalBounds", 0, 0, 0, 0);
        set(trace, "uTraceSize", rayW, rayH);

        glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
        glViewport(0, 0, W, H);
        glUseProgram(composite);
        set(composite, "uCriticalBounds", x, y, span, skyRays ? 2 : 1);
        set(composite, "uCriticalArc", arc.inner(), arc.outer());
        sampler("CriticalLight", 11, stripTex);
        sampler("CriticalRadii", 12, radiiTex);
        if (skyRays) sampler("LocalRayExit", 6, stripExit);
    }

    private static int stripTarget(int texture, int rayExit) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        int output = glGetFragDataLocation(trace, "rayLight");
        attach(texture, GL_COLOR_ATTACHMENT0 + output);
        int exitOutput = rayExit != 0 ? glGetFragDataLocation(trace, "rayExit") : -1;
        int[] targets = new int[Math.max(output, exitOutput) + 1];
        targets[output] = GL_COLOR_ATTACHMENT0 + output;
        if (rayExit != 0) {
            attach(rayExit, GL_COLOR_ATTACHMENT0 + exitOutput);
            targets[exitOutput] = GL_COLOR_ATTACHMENT0 + exitOutput;
        }
        glDrawBuffers(targets);
        glReadBuffer(GL_COLOR_ATTACHMENT0 + output);
        checkFbo();
        return framebuffer;
    }

    private static void bakeFootprints(double pilotScale) {
        bakeFootprintsAtScale(Math.clamp(1.65 * pilotScale, .28, .74));
    }

    static void bakeFootprintsAtScale(double scale) {
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

    static void prepareComposite(float inclination, float mode, float bloom) {
        glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
        glViewport(0, 0, W, H);
        glUseProgram(composite);
        set(composite, "uRefine", 0);
        set(composite, "uTransferPass", 0);
        set(composite, "uCriticalBounds", 0, 0, 0, 0);
        set(composite, "uCriticalArc", 0, 0);
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
        if (hdr != null) {
            int highlights = bloom > 0 ? hdr.draw(light, rayW, rayH, 1.15f) : 0;
            sampler("RayBloom", 8, highlights);
            set(composite, "uRayBloomLod", highlights != 0 ? hdr.lastLevel() : 0,
                    KerrSampling.bloomLodBias(rayW, rayH, W, H, H));
        }
    }

    private static void axis(int program, float inclination) {
        double angle = Math.toRadians(inclination);
        double yaw = Math.toRadians(orbitAzimuth);
        set(program, "uDiskNormal", 0, (float) Math.sin(angle), (float) Math.cos(angle));
        set(program, "uEquatorialX", (float) Math.cos(yaw),
                (float) (Math.cos(angle) * Math.sin(yaw)), (float) (-Math.sin(angle) * Math.sin(yaw)));
    }

    private static float isco(double a) {
        double z1 = 1 + Math.cbrt((1 - a) * (1 + a)) * (Math.cbrt(1 + a) + Math.cbrt(1 - a));
        double z2 = Math.sqrt(3 * a * a + z1 * z1);
        return (float) (3 + z2 - Math.copySign(Math.sqrt(Math.max(0, (3 - z1) * (3 + z1 + 2 * z2))), a));
    }

    static void rayTarget(double scale) {
        rayTarget((int) Math.ceil(W * scale), (int) Math.ceil(H * scale));
    }

    static void rayTarget(int width, int height) {
        if (rayFbo != 0) {
            glDeleteFramebuffers(rayFbo);
            glDeleteTextures(light);
            glDeleteTextures(exit);
        }
        rayW = width;
        rayH = height;
        rayFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, rayFbo);
        int lightAttachment = GL_COLOR_ATTACHMENT0 + glGetFragDataLocation(trace, "rayLight");
        int exitAttachment = GL_COLOR_ATTACHMENT0 + glGetFragDataLocation(trace, "rayExit");
        light = texture(rayW, rayH, GL_RGBA16F, GL_LINEAR);
        exit = texture(rayW, rayH, GL_RGBA32F, GL_NEAREST);
        attach(light, lightAttachment);
        attach(exit, exitAttachment);
        glDrawBuffers(new int[]{Math.min(lightAttachment, exitAttachment), Math.max(lightAttachment, exitAttachment)});
        checkFbo();
    }

    private static FloatBuffer readExit() {
        return readRayBuffer("rayExit");
    }

    static FloatBuffer readRayBuffer(String output) {
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

    static int program(String fragment) throws Exception {
        return program(fragment, false);
    }

    private static int program(String fragment, boolean diagnostic) throws Exception {
        return program("guixu_blackhole.vsh", fragment, diagnostic);
    }

    private static int program(String vertexFile, String fragment, boolean diagnostic) throws Exception {
        int vertex = shader(GL_VERTEX_SHADER, vertexFile, false);
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
        for (String include : new String[]{"guixu_kerr.glsl", "guixu_kerr_exterior.glsl",
                "guixu_white_primary.glsl", "guixu_white_world.glsl"}) {
            source = source.replace("#moj_import <gt_shanhai:" + include + ">",
                    Files.readString(SHADERS.resolve("../include").resolve(include)));
        }
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

    static void set(int id, String name, float... values) {
        int location = location(id, name);
        switch (values.length) {
            case 1 -> glUniform1f(location, values[0]);
            case 2 -> glUniform2fv(location, values);
            case 3 -> glUniform3fv(location, values);
            case 4 -> glUniform4fv(location, values);
            default -> throw new AssertionError(name);
        }
    }

    static void verifyGolden(String name, BufferedImage image) throws Exception {
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

    static BufferedImage readImage() {
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
