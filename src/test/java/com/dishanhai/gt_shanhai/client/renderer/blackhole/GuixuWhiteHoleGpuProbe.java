package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.api.whitehole.StandardSpacetime;
import com.dishanhai.gt_shanhai.api.whitehole.WhiteHoleEngine;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;

import static com.dishanhai.gt_shanhai.client.renderer.blackhole.GuixuKerrGpuProbe.*;
import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter.BLOOM;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Runs the same transfer and compositor used in Minecraft without loading a game world. */
public final class GuixuWhiteHoleGpuProbe {
    private static final Path REFERENCES = Path.of("src/test/resources/guixu-white-hole");
    private static final Path REPORT = Path.of(System.getProperty("whitehole.reportDir", "build/reports/white-hole-gl"));
    private static final int SEED = 17081;
    private static final Camera OBLIQUE = new Camera(68, 0, 40);
    private static final Emission STEADY = new Emission(1, .58f, .46f, 1, SEED, 4.25f, -1, .8f, 0, 0, 1.15f);
    private static final String[] BURST_BANKS = {"uWhiteExtraBursts0", "uWhiteExtraBursts1"};
    private static final Path PARTICLE_SHADERS = Path.of("src/main/resources/assets/gt_shanhai/shaders/whitehole");
    private static final int PARTICLE_UNIT = 9;
    private static final int BLOOM_UNIT = 10;
    private static final KerrRenderPass.Target HOST_BLOOM_SOURCE = new KerrRenderPass.Target();
    private static final KerrRenderPass.Target HOST_DIRECT = new KerrRenderPass.Target(false, GL_R32F);
    private static int hostDirectProgram;
    private static int particleAtlas;
    private static CompactHdrPostProcessing hdr;
    private static float observerBeta;
    private static boolean autoExposure;
    private static double displayDelta;
    private enum HostMaterial { ALL, CORE, DISK, JET, OPTICAL, SHELL }

    private GuixuWhiteHoleGpuProbe() {}

    public static void main(String[] args) throws Exception {
        var flags = Arrays.asList(args);
        boolean interactive = flags.contains("--interactive");
        boolean expectParticles = flags.contains("--smoke-particles-viewer");
        boolean smokeViewer = flags.contains("--smoke-viewer") || expectParticles;
        Files.createDirectories(REPORT);
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        try {
            glfwWindowHint(GLFW_VISIBLE, interactive ? GLFW_TRUE : GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, interactive || expectParticles ? 4 : 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            var lang = Path.of("src/main/resources/assets/gt_shanhai/lang")
                    .resolve(Locale.getDefault().getLanguage().equals("zh") ? "zh_cn.json" : "en_us.json");
            String title = JsonParser.parseString(Files.readString(lang)).getAsJsonObject()
                    .get("gt_shanhai.white_hole.title").getAsString();
            long window = glfwCreateWindow(W, H, title, 0, 0);
            if (window == 0 && interactive) {
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
                window = glfwCreateWindow(W, H, title, 0, 0);
            }
            if (window == 0) throw new IllegalStateException("OpenGL 3.3 context creation failed");
            try {
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                System.out.printf("%s / %s / %s%n", glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION));
                initializePipeline();
                if (interactive || smokeViewer) {
                    glfwSwapInterval(smokeViewer ? 0 : 1);
                    RenderSystem.initRenderThread();
                    try (var grid = new WhiteHoleParticleGrid(name -> Files.readString(PARTICLE_SHADERS.resolve(name)));
                         var post = new CompactHdrPostProcessing(
                                 ext -> Files.readString(PARTICLE_SHADERS.resolve("bright_pass." + ext)))) {
                        hdr = post;
                        new Viewer(window, grid).run(smokeViewer, expectParticles);
                    } finally {
                        hdr = null;
                    }
                } else if (flags.contains("--world-appearance") || flags.contains("--host-views")) {
                    RenderSystem.initRenderThread();
                    try (var post = new CompactHdrPostProcessing(
                            ext -> Files.readString(PARTICLE_SHADERS.resolve("bright_pass." + ext)))) {
                        hdr = post;
                        if (flags.contains("--host-views")) {
                            hostDirectProgram = program("guixu_white_cache");
                            try {
                                verifyHostViews();
                            } finally {
                                glDeleteProgram(hostDirectProgram);
                            }
                        }
                        else verifyWorldAppearance();
                    } finally {
                        hdr = null;
                    }
                } else {
                    verify(flags.contains("--update-golden"));
                }
                int error = glGetError();
                if (error != GL_NO_ERROR) throw new AssertionError("OpenGL error " + error);
            } finally {
                glfwFreeCallbacks(window);
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    private static void verify(boolean updateGolden) throws Exception {
        rayTarget(.72);
        renderRays(OBLIQUE, STEADY, true);
        verifyRayBuffers();
        composite(OBLIQUE, STEADY, true);
        BufferedImage oblique = readImage();
        save("oblique", oblique, updateGolden);

        render(OBLIQUE, STEADY, true);
        require(changedPixels(oblique, readImage(), 0) == 0, "Same seed, clock, and camera must render identically");
        observerBeta = .35f;
        render(OBLIQUE, STEADY, true);
        compare("Observer aberration and shift", oblique, readImage(), .01);
        observerBeta = 0;
        var advanced = new Emission(1, .58f, .46f, 1, SEED, 5.15f, -1, .8f, 0, 0, 1.15f);
        render(OBLIQUE, advanced, true);
        compare("Advected plasma", oblique, readImage(), .002);
        var nextFrame = new Emission(1, .58f, .46f, 1, SEED, 4.25f + 1f / 60, -1, .8f, 0, 0, 1.15f);
        render(OBLIQUE, nextFrame, true);
        int abruptChanges = changedPixels(oblique, readImage(), 45);
        require(abruptChanges < W * H * .01, "Adjacent frames show abrupt changes at a fixed camera");
        var anotherSeed = new Emission(1, .58f, .46f, 1, SEED + 531, 4.25f, -1, .8f, 0, 0, 1.15f);
        render(OBLIQUE, anotherSeed, true);
        compare("Seeded emissivity", oblique, readImage(), .002);

        var black = new Emission(0, .58f, .46f, 1, SEED, 4.25f, -1, .8f, 0, 0, 1.15f);
        render(OBLIQUE, black, true);
        int blackCenter = centerBrightness(readImage()), whiteCenter = centerBrightness(oblique);
        System.out.printf("Core RGB maximum, black mode %d, white mode %d%n", blackCenter, whiteCenter);
        require(whiteCenter > 100 && whiteCenter > blackCenter + 35, "White emission must fill the black shadow at the core");
        verifyKerrBaseline();
        render(OBLIQUE, STEADY, true);
        require(changedPixels(oblique, readImage(), 0) == 0, "Prior camera and mode leaked into the white-hole pass");

        render(new Camera(88, 0, 40), STEADY, true);
        BufferedImage edge = readImage();
        save("edge-on", edge, updateGolden);
        render(new Camera(12, 55, 27), STEADY, true);
        BufferedImage axial = readImage();
        save("near-axis", axial, updateGolden);
        compare("Three-dimensional viewing angle", edge, axial, .025);

        var noJet = new Emission(1, .58f, .46f, 1, SEED, 4.25f, -1, 0, 0, 0, 1.15f);
        render(OBLIQUE, noJet, true);
        compare("Optional polar outflow", oblique, readImage(), .0005);
        var burst = new Emission(1, .58f, .46f, 1, SEED, 4.25f, 1.20f, .8f, 1.2f, 0, 1.15f);
        render(OBLIQUE, burst, true);
        BufferedImage erupted = readImage();
        save("burst", erupted, updateGolden);
        compare("Radiation shell", oblique, erupted, .0005);
        var expanding = new Emission(1, .58f, .46f, 1, SEED, 4.25f, 1.7f, .8f, 1.2f, 0, 1.15f);
        render(OBLIQUE, expanding, true);
        double early = radialBrightness(oblique, erupted), late = radialBrightness(oblique, readImage());
        System.out.printf(Locale.ROOT, "Radiation shell brightness radius %.1f → %.1f px%n", early, late);
        require(late > early + H * .035, "The outgoing shell did not expand with increasing age");

        var latest = new Emission(1, .58f, .46f, 1, SEED, 4.25f, .72f, .8f, 1.2f, 0, 1.15f);
        render(OBLIQUE, latest, true);
        BufferedImage single = readImage();
        render(OBLIQUE, latest, true, List.of(new WhiteHoleEngine.Burst(1, 4.25 - 1.6, .8),
                new WhiteHoleEngine.Burst(2, 4.25 - .72, 1.2)));
        BufferedImage overlap = readImage();
        save("burst-overlap", overlap, updateGolden);
        compare("Additional radiation shell", single, overlap, .0005);
        var fiveShells = List.of(new WhiteHoleEngine.Burst(1, 4.25 - 1.6, .8),
                new WhiteHoleEngine.Burst(2, 4.25 - 1.4, .8), new WhiteHoleEngine.Burst(3, 4.25 - 1.2, .8),
                new WhiteHoleEngine.Burst(4, 4.25 - .96, .8), new WhiteHoleEngine.Burst(5, 4.25 - .72, 1.2));
        render(OBLIQUE, latest, true, fiveShells.subList(1, fiveShells.size()));
        BufferedImage newestFour = readImage();
        render(OBLIQUE, latest, true, fiveShells);
        compare("Oldest of five simultaneous shells", newestFour, readImage(), .0005);
        verifyDepthProxy();

        render(new Camera(46, -85, 12), STEADY, true);
        save("near", readImage(), updateGolden);
        var spherical = new Emission(1, 0, .40f, 1, SEED, 4.25f, -1, 0, 0, 4, 1.15f);
        render(new Camera(70, 75, 45), spherical, true);
        save("schwarzschild-far", readImage(), updateGolden);

        rayTarget(.36);
        render(OBLIQUE, STEADY, false);
        BufferedImage low = readImage();
        save("low", low, updateGolden);
        require(centerBrightness(low) > 100, "Low quality must retain the radiation core");
        if (W == 800 && H == 560) verifyDistantSampling();
        for (double scale : new double[]{.36, .52, .72}) benchmark(scale);
    }

    private static void verifyDistantSampling() throws IOException {
        var camera = new Camera(68, 0, 148);
        int side = 248;
        double radius = H * BlackHoleLensMath.B_CRIT / (camera.distance() * Math.tan(Math.toRadians(31)));
        Quality quality = Quality.ADAPTIVE;
        KerrSampling plan = KerrSampling.plan(quality, quality.scale, quality.scale, side * side,
                5 * radius / H, camera.distance(), radius);
        BufferedImage before = renderDistant(camera, side, quality.scale, radius);
        BufferedImage after = renderDistant(camera, side, plan.traceScale(), radius);
        BufferedImage reference = renderDistant(camera, side, 2, radius);
        double oldError = meanPixelError(before, reference);
        double newError = meanPixelError(after, reference);
        System.out.printf(Locale.ROOT, "Distant white hole: %.0f%% → %.0f%% trace, mean RGB error %.3f → %.3f%n",
                100 * quality.scale, 100 * plan.traceScale(), oldError, newError);
        require(newError < .8 * oldError, "Distant white-hole sampling must preserve detail closer to the reference");
        ImageIO.write(before, "PNG", REPORT.resolve("distant-before.png").toFile());
        ImageIO.write(after, "PNG", REPORT.resolve("distant-after.png").toFile());
    }

    private static void verifyWorldAppearance() throws IOException {
        glActiveTexture(GL_TEXTURE0);
        int scene = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, scene);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        var sky = BufferUtils.createByteBuffer(4).put((byte) 126).put((byte) 139).put((byte) 237).put((byte) 255).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, sky);
        var camera = new Camera(78, 0, 46);
        double radius = H * BlackHoleLensMath.B_CRIT / (camera.distance() * Math.tan(Math.toRadians(31)));
        Quality quality = Quality.ADAPTIVE;
        double shadow = KerrSampling.shadowFraction(camera.distance(), Math.tan(Math.toRadians(31)));
        KerrSampling previous = KerrSampling.plan(quality, quality.scale, .27, (double) W * H,
                shadow, camera.distance(), radius);
        KerrSampling detailed = KerrSampling.plan(quality, quality.scale, .27, (double) W * H,
                shadow, camera.distance(), radius, 1);
        var effect = new Emission(1, .72f, .36f, 1, SEED, 4.25f, -1, 1.25f, 0, 0, 1.15f);
        try {
            renderWorldAppearance(camera, effect, previous, scene, radius);
            var coarse = readImage();
            renderWorldAppearance(camera, effect, detailed, scene, radius);
            var improved = readImage();
            ImageIO.write(improved, "PNG", REPORT.resolve("host-world-after-" + W + "x" + H + ".png").toFile());
            renderWorldAppearance(camera, effect, new KerrSampling(1, 0, 4), scene, radius);
            var reference = readImage();
            double oldError = meanPixelError(coarse, reference), newError = meanPixelError(improved, reference);
            double axisError = measureJetAxis(improved, radius);
            System.out.printf(Locale.ROOT, "%dx%d near world: trace %.1f%% → %.1f%%, RGB error %.3f → %.3f, jet offset %.2f px%n",
                    W, H, 100 * previous.traceScale(), 100 * detailed.traceScale(), oldError, newError, axisError);
            require(newError < .97 * oldError, "Near-world transfer must improve against the native reference");
            require(axisError < .10 * radius, "The two jet roots must follow the projected center axis");
            System.out.printf(Locale.ROOT, "Near-world GPU trace, bloom and composite: %.2f → %.2f ms/frame%n",
                    timeWorldAppearance(camera, effect, previous, scene, radius),
                    timeWorldAppearance(camera, effect, detailed, scene, radius));
            var transparent = new Emission(1, .72f, .36f, 0, SEED, 4.25f, -1, 1.25f, 0, 0, 1.15f);
            for (double transfer : new double[]{0, .26}) {
                var plan = new KerrSampling(.23, transfer, 2);
                var black = new Emission(0, .72f, .36f, 0, SEED, 4.25f, -1, 0, 0, 0, 1.15f);
                renderWorldAppearance(camera, black, plan, scene, radius);
                require((readImage().getRGB(W / 2, H / 2) & 0xffffff) == 0, "Kerr capture did not restore its dark shadow");
                renderWorldAppearance(camera, transparent, plan, scene, radius);
                int core = readImage().getRGB(W / 2, H / 2) & 0xffffff;
                System.out.printf(Locale.ROOT, "Transparent world appearance (resolve %.2f): #%06x%n", transfer, core);
                require(core == 0x7e8bed, "White-mode capture must not stamp an opaque silhouette onto the world");
            }
        } finally {
            glDeleteTextures(scene);
        }
    }

    private static void verifyHostViews() throws IOException {
        int scene = glGenTextures(), depth = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D, scene);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            var sky = BufferUtils.createByteBuffer(4).put((byte) 88).put((byte) 100).put((byte) 120).put((byte) 255).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, sky);
            var effect = new Emission(1, .72f, .36f, 1, SEED, 4.25f, -1, 1.25f, 0, 0, 1.15f);
            var camera = new Camera(132, -27, 26);
            setHostDepth(depth, 1);
            var open = renderHostView(camera, effect, scene, depth, 4, .15);
            ImageIO.write(open, "PNG", REPORT.resolve("host-below-open.png").toFile());
            setHostDepth(depth, .96f);
            var blocked = renderHostView(camera, effect, scene, depth, 4, .15);
            ImageIO.write(blocked, "PNG", REPORT.resolve("host-below-blocked.png").toFile());
            double error = Math.abs((blocked.getRGB(W / 2 + W / 20, H / 2) & 255) - 120);
            System.out.printf(Locale.ROOT, "White host foreground error %.0f RGB levels%n", error);
            require(error <= 1, "Opaque Minecraft terrain in front of the volume must fully occlude it");
            setHostDepth(depth, .99815f);
            var sliced = renderHostView(camera, effect, scene, depth, 4, .15);
            int centreX = (int) (W * .5 + 4 * H / (2 * camera.distance() * Math.tan(Math.toRadians(31))));
            require((sliced.getRGB(centreX, H / 2) & 255) > 150,
                    "A block near the centre plane must leave emission from the near side visible");
            setHostDepth(depth, 1);
            for (var view : List.of(camera, new Camera(86, 112, 17), new Camera(24, 63, 22),
                    new Camera(70, 87, 65))) {
                double fov = view.inclination() == 24 ? 96 : 62;
                perspective(fov);
                var budget = renderHostView(view, effect, scene, depth, 4, .15);
                var finer = renderHostView(view, effect, scene, depth, 4, .30);
                double difference = meanPixelError(budget, finer);
                var projectedJets = renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.JET);
                double alignment = measureHostJetAxis(view, projectedJets, 4, fov);
                System.out.printf(Locale.ROOT,
                        "Host %.0f degrees at %.0f rg, FOV %.0f: pilot-grid RGB difference %.3f, jet offset %.2f px%n",
                        view.inclination(), view.distance(), fov, difference, alignment);
                ImageIO.write(budget, "PNG", REPORT.resolve("host-view-" + Math.round(view.inclination()) + ".png").toFile());
                require(difference < .55, "Direct world emission must retain detail when the lens pilot is reduced");
                require(alignment < 1 + .025 * H / view.distance(),
                        "Both near jet spines must follow the projected world axis when viewed off-centre");
                if (view.inclination() == 86) {
                    verifySideViewHighlights(view, budget, fov);
                    verifyHostBloomAndSharpness(view, effect, scene, depth, budget, projectedJets);
                    for (var material : List.of(HostMaterial.CORE, HostMaterial.DISK, HostMaterial.OPTICAL)) {
                        var layer = renderHostView(view, effect, scene, depth, 4, .15, material);
                        ImageIO.write(layer, "PNG", REPORT.resolve("host-side-" + material.name().toLowerCase(Locale.ROOT) + ".png").toFile());
                        if (material == HostMaterial.OPTICAL) verifyOpticalContinuity(view, effect, scene, depth, layer);
                    }
                    ImageIO.write(projectedJets, "PNG", REPORT.resolve("host-side-jet.png").toFile());
                }
            }
            verifyHostFrameReuse(camera, effect, scene, depth);
            verifyHostScale(effect, scene, depth);
        } finally {
            HOST_BLOOM_SOURCE.close();
            HOST_DIRECT.close();
            glDeleteTextures(depth);
            glDeleteTextures(scene);
        }
    }

    private static void verifyHostBloomAndSharpness(Camera view, Emission effect, int scene, int depth,
                                                   BufferedImage all, BufferedImage jets) throws IOException {
        float bloom = (float) BLOOM.initial;
        var core = renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.CORE);
        var glowingCore = renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.CORE, bloom);
        var glowingJets = renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.JET, bloom);
        verifyHalo("core", core, glowingCore,
                renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.CORE, 1));
        verifyHalo("jets", jets, glowingJets,
                renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.JET, 1));

        var burst = new Emission(effect.mode(), effect.spin(), effect.beta(), effect.gain(), effect.seed(),
                effect.time(), .63f, 0, 3.4f, effect.spectrum(), effect.exposure());
        var shell = renderHostView(view, burst, scene, depth, 4, .15, HostMaterial.SHELL);
        var glowingShell = renderHostView(view, burst, scene, depth, 4, .15, HostMaterial.SHELL, bloom);
        verifyHalo("shell", shell, glowingShell,
                renderHostView(view, burst, scene, depth, 4, .15, HostMaterial.SHELL, 1));

        var glowingAll = renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.ALL, bloom);
        ImageIO.write(glowingAll, "PNG", REPORT.resolve("host-side-bloom.png").toFile());
        for (int sign : new int[]{-1, 1}) {
            double jetWidth = jetHalfMaximumWidth(view, all, sign);
            double litWidth = jetHalfMaximumWidth(view, glowingAll, sign);
            double coreWidth = coreEdgeWidth(view, core, sign);
            double litCoreWidth = coreEdgeWidth(view, glowingCore, sign);
            System.out.printf(Locale.ROOT,
                    "Side %d, widths in rg: jet FWHM %.3f → %.3f, core 10–90%% edge %.3f → %.3f%n",
                    sign, jetWidth, litWidth, coreWidth, litCoreWidth);
            // Same-camera captures at 2560x1600 measured .511/.501 rg before and ~.33 rg after sharpening.
            double jetWidthLimit = .69 * (sign < 0 ? .511 : .501);
            require(jetWidth > .23 && jetWidth < jetWidthLimit && litWidth > .23 && litWidth < jetWidthLimit
                            && Math.abs(litWidth - jetWidth) < .035,
                    "Both jet spines must retain their narrow FWHM with bloom enabled");
            require(coreWidth > .65 && coreWidth < 1.22 && litCoreWidth > .65 && litCoreWidth < 1.22
                            && Math.abs(litCoreWidth - coreWidth) < .08,
                    "Both core edges must retain their measured 10–90% transition with bloom enabled");
        }

        setHostDepth(depth, .96f);
        try {
            var hidden = renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.ALL, 0);
            var blocked = renderHostView(view, effect, scene, depth, 4, .15, HostMaterial.ALL, bloom);
            require(meanPixelError(hidden, blocked) == 0,
                    "Fully hidden host emission must leave no bloom on foreground terrain");
        } finally {
            setHostDepth(depth, 1);
        }
    }

    private static void verifyHalo(String material, BufferedImage plain, BufferedImage glowing, BufferedImage stronger) {
        long halo = 0, strongHalo = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int before = plain.getRGB(x, y) & 255;
            if (before > 121) continue;
            halo += Math.max(0, (glowing.getRGB(x, y) & 255) - before);
            strongHalo += Math.max(0, (stronger.getRGB(x, y) & 255) - before);
        }
        System.out.printf(Locale.ROOT, "Host %s bloom: %d → %d summed blue levels against the sky%n", material, halo, strongHalo);
        require(halo > (long) W * H / 20_000 && strongHalo > 2 * halo,
                "Increasing bloom must spread more light from the isolated " + material + " into its surroundings");
    }

    private static double jetHalfMaximumWidth(Camera camera, BufferedImage image, int sign) {
        double focal = .5 * H / Math.tan(Math.toRadians(31));
        double inclination = Math.toRadians(camera.inclination());
        double widths = 0;
        for (int h = 6; h <= 9; h++) {
            double z = sign * h;
            double scale = focal / (camera.distance() - z * Math.cos(inclination));
            double center = W * .5 + 4 * scale;
            int y = (int) (H * .5 - z * Math.sin(inclination) * scale);
            int start = (int) (center - scale);
            int[] profile = new int[(int) (center + scale) - start + 1];
            int peak = 0;
            for (int i = 0; i < profile.length; i++) {
                profile[i] = image.getRGB(start + i, y) & 255;
                if (profile[i] > profile[peak]) peak = i;
            }
            double background = .5 * (profile[0] + profile[profile.length - 1]);
            double half = .5 * (background + profile[peak]);
            widths += (crossing(profile, peak, 1, half) - crossing(profile, peak, -1, half)) / scale;
        }
        return widths / 4;
    }

    private static double coreEdgeWidth(Camera camera, BufferedImage image, int sign) {
        double scale = .5 * H / (camera.distance() * Math.tan(Math.toRadians(31)));
        int center = (int) Math.round(W * .5 + 4 * scale);
        int[] profile = new int[(int) (6 * scale)];
        int peak = 0;
        for (int i = 0; i < profile.length; i++) {
            profile[i] = (image.getRGB(center + sign * i, H / 2) >> 8) & 255;
            if (profile[i] > profile[peak]) peak = i;
        }
        double background = profile[profile.length - 1];
        double contrast = profile[peak] - background;
        return (crossing(profile, peak, 1, background + .10 * contrast)
                - crossing(profile, peak, 1, background + .90 * contrast)) / scale;
    }

    private static double crossing(int[] profile, int peak, int direction, double level) {
        for (int i = peak + direction; i >= 0 && i < profile.length; i += direction) {
            if (profile[i] <= level) {
                int previous = i - direction;
                return previous + direction * (profile[previous] - level) / (profile[previous] - profile[i]);
            }
        }
        throw new AssertionError("Radiance profile did not fall below " + level);
    }

    private static void verifyOpticalContinuity(Camera camera, Emission start, int scene, int depth,
                                                BufferedImage previous) {
        double worst = 0;
        for (int frame = 1; frame <= 3; ++frame) {
            var nextEffect = new Emission(start.mode(), start.spin(), start.beta(), start.gain(), start.seed(),
                    start.time() + frame / 60f, start.burstAge(), start.jets(), start.burstStrength(),
                    start.spectrum(), start.exposure());
            var next = renderHostView(camera, nextEffect, scene, depth, 4, .15, HostMaterial.OPTICAL);
            int sources = 0, jumps = 0;
            for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
                int a = (previous.getRGB(x, y) >> 8) & 255;
                int b = (next.getRGB(x, y) >> 8) & 255;
                if (Math.max(a, b) <= 110) continue;
                sources++;
                if (Math.abs(b - a) > 8) jumps++;
            }
            require(sources > 0, "Optical continuity needs a visible bent image");
            worst = Math.max(worst, (double) jumps / sources);
            previous = next;
        }
        System.out.printf(Locale.ROOT, "White optical image: at most %.3f%% abrupt pixels over consecutive frames%n", 100 * worst);
        require(worst < .03, "The optical image must evolve without jumping bright points");
    }

    private static void verifySideViewHighlights(Camera camera, BufferedImage image, double fov) {
        double focal = .5 * H / Math.tan(Math.toRadians(.5 * fov));
        int x0 = (int) Math.round(W * .5 + focal * 4 / camera.distance());
        int radius = (int) Math.round(2.5 * focal / camera.distance());
        int almostWhite = 0, peak = 0;
        for (int y = H / 2 - radius; y < H / 2 + radius; ++y) {
            for (int x = x0 - radius; x < x0 + radius; ++x) {
                int color = image.getRGB(x, y);
                int green = (color >> 8) & 255;
                peak = Math.max(peak, green);
                if (Math.min(green, Math.min(color & 255, (color >> 16) & 255)) >= 245) almostWhite++;
            }
        }
        require(peak >= 220 && almostWhite < .005 * 4 * radius * radius,
                "The default exposure must retain a bright core and highlight headroom");
        int jump = 0;
        int x = (int) (.42 * W);
        for (int y = (int) (.55 * H); y < .82 * H; ++y) {
            int a = image.getRGB(x, y), b = image.getRGB(x, y + 1);
            jump = Math.max(jump, channelDelta(a, b, 0) + channelDelta(a, b, 8) + channelDelta(a, b, 16));
        }
        require(jump <= 16 * Math.max(1.0, 800.0 / H), "The near disk wing must have no quadrature seam");
        System.out.printf(Locale.ROOT, "Side highlights: %d almost-white pixels, green peak %d, disk RGB jump %d%n",
                almostWhite, peak, jump);
    }

    private static double measureHostJetAxis(Camera camera, BufferedImage image, float offset, double fov) {
        double focal = .5 * H / Math.tan(Math.toRadians(fov * .5));
        double angle = Math.toRadians(camera.inclination());
        double max = 0;
        for (int sign : new int[]{-1, 1}) {
            double displacement = 0, energy = 0;
            for (int h = 5; h <= 9; h++) {
                double z = sign * h;
                double depth = camera.distance() - z * Math.cos(angle);
                double column = W * .5 + focal * offset / depth;
                int y = (int) (H * .5 - focal * z * Math.sin(angle) / depth);
                if (y < 0 || y >= H) continue;
                int radius = Math.max(3, (int) (.8 * focal / depth));
                for (int x = Math.max(0, (int) column - radius); x <= Math.min(W - 1, (int) column + radius); x++) {
                    double excess = Math.max(0, (image.getRGB(x, y) & 255) - 123);
                    double weight = excess * excess;
                    energy += weight;
                    displacement += (x + .5 - column) * weight;
                }
            }
            require(energy > 0, "Both jet spines must be visible in frame");
            max = Math.max(max, Math.abs(displacement) / energy);
        }
        return max;
    }

    private static void setHostDepth(int depth, float value) {
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D, depth);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, 1, 1, 0, GL_RED, GL_FLOAT, new float[]{value});
    }

    private static BufferedImage renderHostView(Camera camera, Emission effect, int scene, int depth,
                                               float offset, double scale) {
        return renderHostView(camera, effect, scene, depth, offset, scale, HostMaterial.ALL);
    }

    private static BufferedImage renderHostView(Camera camera, Emission effect, int scene, int depth,
                                               float offset, double scale, HostMaterial material) {
        return renderHostView(camera, effect, scene, depth, offset, scale, material, 0);
    }

    private static BufferedImage renderHostView(Camera camera, Emission effect, int scene, int depth,
                                               float offset, double scale, HostMaterial material, float bloom) {
        drawHostView(camera, effect, scene, depth, offset, scale, material, bloom);
        return readImage();
    }

    private static void verifyHostFrameReuse(Camera camera, Emission effect, int scene, int depth) {
        float bloom = (float) BLOOM.initial;
        drawHostView(camera, effect, scene, depth, 4, .15, HostMaterial.ALL, 0);
        BufferedImage original = readImage();
        bakeHostDirect(camera, effect, depth);
        presentHostView(scene, depth, effect.exposure(), 0, true);
        double cacheError = meanPixelError(original, readImage());
        require(cacheError < .08, "The native-resolution frame cache must preserve the direct image");

        // Insert terrain after the bake. The cached radiance must be ignored where depth changed.
        float[] depthRow = new float[W];
        int edge = W * 3 / 5;
        Arrays.fill(depthRow, 0, edge, .99815f);
        Arrays.fill(depthRow, edge, W, 1f);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, depth);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, W, 1, 0, GL_RED, GL_FLOAT, depthRow);
        presentHostView(scene, depth, effect.exposure(), 0, true);
        var changedDepth = readImage();
        presentHostView(scene, depth, effect.exposure(), 0, false);
        double depthError = meanPixelError(changedDepth, readImage());
        require(meanPixelError(original, changedDepth) > .1 && depthError < .01,
                "A partly covered frame must refresh the changed pixels and reuse the others");
        setHostDepth(depth, 1);

        double full = timeHostView(camera, effect, scene, depth, bloom, false);
        double reused = timeHostView(camera, effect, scene, depth, bloom, true);
        System.out.printf(Locale.ROOT,
                "%dx%d host GPU full %.2f ms, one rebake and three reused frames %.2f ms/frame, RGB cache/depth error %.4f / %.4f%n",
                W, H, full, reused, cacheError, depthError);
        require(reused < .90 * full, "Frame reuse must reduce the average GPU frame time");
    }

    private static void verifyHostScale(Emission effect, int scene, int depth) {
        float scale = (float) WhiteHoleRenderState.HOST_VISUAL_SCALE;
        var before = renderHostView(new Camera(86, 0, 65), effect, scene, depth, 4, .15, HostMaterial.CORE);
        var expanded = renderHostView(new Camera(86, 0, 65 / scale), effect, scene, depth, 4 / scale, .15, HostMaterial.CORE);
        int beforeWidth = 0, afterWidth = 0;
        for (int x = 0; x < W; x++) {
            if (((before.getRGB(x, H / 2) >> 8) & 255) >= 140) beforeWidth++;
            if (((expanded.getRGB(x, H / 2) >> 8) & 255) >= 140) afterWidth++;
        }
        System.out.printf(Locale.ROOT, "Same world camera, host core width %d to %d px%n", beforeWidth, afterWidth);
        require(beforeWidth > 0 && afterWidth >= 1.29 * beforeWidth && afterWidth <= 1.43 * beforeWidth,
                "The host effect must grow about 35% around its original projected centre");
    }

    private static double timeHostView(Camera camera, Emission effect, int scene, int depth, float bloom, boolean reuse) {
        int query = glGenQueries();
        try {
            for (int i = 0; i < 4; i++) drawTimedHostFrame(camera, effect, scene, depth, bloom, reuse, i);
            glBeginQuery(GL_TIME_ELAPSED, query);
            for (int i = 0; i < 12; i++) drawTimedHostFrame(camera, effect, scene, depth, bloom, reuse, i);
            glEndQuery(GL_TIME_ELAPSED);
            return glGetQueryObjectui64(query, GL_QUERY_RESULT) / 12e6;
        } finally {
            glDeleteQueries(query);
        }
    }

    private static void drawTimedHostFrame(Camera camera, Emission effect, int scene, int depth,
                                           float bloom, boolean reuse, int frame) {
        if (!reuse || frame % 4 == 0) {
            prepareHostView(camera, effect, 4, .15, HostMaterial.ALL);
            if (reuse) bakeHostDirect(camera, effect, depth);
        }
        presentHostView(scene, depth, effect.exposure(), bloom, reuse);
    }

    private static void drawHostView(Camera camera, Emission effect, int scene, int depth,
                                     float offset, double scale, HostMaterial material, float bloom) {
        prepareHostView(camera, effect, offset, scale, material);
        presentHostView(scene, depth, effect.exposure(), bloom, false);
    }

    private static void prepareHostView(Camera camera, Emission effect, float offset, double scale, HostMaterial material) {
        if (rayWidth() != (int) Math.ceil(W * scale) || rayHeight() != (int) Math.ceil(H * scale)) rayTarget(scale);
        bindEmission(camera, effect, List.of());
        var style = WhiteHolePreset.CINEMATIC_WHITE_HOLE.settings(effect.seed());
        for (int pass : new int[]{trace, composite}) {
            glUseProgram(pass);
            boolean all = material == HostMaterial.ALL;
            boolean disk = pass == trace ? material == HostMaterial.OPTICAL : material == HostMaterial.DISK;
            set(pass, "uWhiteStyle", all || disk ? style.diskDensity() : 0,
                    style.diskThickness(), style.spectrum(), all || material == HostMaterial.CORE ? style.coreGain() : 0);
            set(pass, "uWhiteTime", effect.time(), effect.burstAge(),
                    all || material == HostMaterial.JET ? effect.jets() : 0, effect.burstStrength());
            set(pass, "uWhitePrimary", 1);
        }
        trace(effect.spin(), camera.inclination(), offset, camera.distance(), .00022f, 384, 0, effect.time());
        prepareComposite(camera.inclination(), 0, 0);
        if ((material == HostMaterial.ALL || material == HostMaterial.OPTICAL) && effect.gain() > 0) drawCriticalStrip(0, true);
    }

    private static void bakeHostDirect(Camera camera, Emission effect, int depth) {
        int program = hostDirectProgram;
        glActiveTexture(GL_TEXTURE13);
        HOST_DIRECT.bind(W, H, glGetFragDataLocation(program, "directLight"), glGetFragDataLocation(program, "directDepth"));
        glViewport(0, 0, W, H);
        glUseProgram(program);
        bindRayCamera(program, camera.inclination(), 4, camera.distance());
        bindHostTexture(program, "SceneDepth", 1, depth);
        var style = WhiteHolePreset.CINEMATIC_WHITE_HOLE.settings(effect.seed());
        var geometry = new KerrGeometry(effect.spin());
        set(program, "uKerrParameters", effect.spin(), (float) geometry.outerHorizon(), (float) geometry.isco(1), 1);
        set(program, "uDiskPhysics", 1, .055f, 9400, .38f);
        set(program, "uIntegration", .00022f, 384);
        set(program, "uWhiteHole", effect.mode(), effect.beta(), effect.gain(), effect.seed());
        set(program, "uWhiteTime", effect.time(), effect.burstAge(), effect.jets(), effect.burstStrength());
        set(program, "uWhiteStyle", style.diskDensity(), style.diskThickness(), style.spectrum(), style.coreGain());
        set(program, "uBgRect", 0, 0, W, H);
        set(program, "uWhiteSceneDepth", 1);
        set(program, "uCacheOrigin", 0, 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void bindHostTexture(int program, String sampler, int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(program, sampler), unit);
    }

    private static void presentHostView(int scene, int depth, float exposure, float bloom, boolean cached) {
        glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
        glViewport(0, 0, W, H);
        glUseProgram(composite);
        set(composite, "uWhiteCacheRect", 0, 0, cached ? W : 0, cached ? H : 0);
        if (cached) {
            bindHostTexture(composite, "WhiteDirectLight", 14, HOST_DIRECT.light);
            bindHostTexture(composite, "WhiteDirectDepth", 15, HOST_DIRECT.exit);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scene);
        glUniform1i(glGetUniformLocation(composite, "SceneColor"), 0);
        glActiveTexture(GL_TEXTURE0 + 1);
        glBindTexture(GL_TEXTURE_2D, depth);
        glUniform1i(glGetUniformLocation(composite, "SceneDepth"), 1);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uWhiteSceneDepth", 1);
        int highlights = 0;
        if (bloom > 0) {
            glActiveTexture(GL_TEXTURE13);
            HOST_BLOOM_SOURCE.bind(rayWidth(), rayHeight(), 0, -1);
            glViewport(0, 0, rayWidth(), rayHeight());
            set(composite, "uResolveSize", rayWidth(), rayHeight());
            set(composite, "uTransferPass", KerrRenderPass.WHITE_BLOOM_SOURCE_PASS);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            set(composite, "uTransferPass", 0);
            highlights = hdr.draw(rayLightTexture(), HOST_BLOOM_SOURCE.light, rayWidth(), rayHeight(), exposure);
            glBindFramebuffer(GL_FRAMEBUFFER, imageFbo);
            glViewport(0, 0, W, H);
        }
        glActiveTexture(GL_TEXTURE0 + BLOOM_UNIT);
        glBindTexture(GL_TEXTURE_2D, highlights);
        glUniform1i(glGetUniformLocation(composite, "RayBloom"), BLOOM_UNIT);
        set(composite, "uRayBloomLod", highlights == 0 ? 0 : hdr.lastLevel(),
                KerrSampling.bloomLodBias(rayWidth(), rayHeight(), W, H, H));
        set(composite, "uDisplay", exposure, bloom);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static double timeWorldAppearance(Camera camera, Emission effect, KerrSampling plan, int scene, double radius) {
        for (int i = 0; i < 3; i++) renderWorldAppearance(camera, effect, plan, scene, radius);
        int frames = 8;
        int timer = glGenQueries();
        try {
            glBeginQuery(GL_TIME_ELAPSED, timer);
            for (int i = 0; i < frames; i++) renderWorldAppearance(camera, effect, plan, scene, radius);
            glEndQuery(GL_TIME_ELAPSED);
            return glGetQueryObjectui64(timer, GL_QUERY_RESULT) / (frames * 1e6);
        } finally {
            glDeleteQueries(timer);
        }
    }

    private static double measureJetAxis(BufferedImage image, double radius) {
        double maxOffset = 0;
        int axis = W / 2;
        for (int sign : new int[]{-1, 1}) {
            double displacement = 0, amount = 0;
            for (int dy = (int) (1.41 * radius); dy < 2 * radius; dy++) {
                for (int dx = (int) (-.48 * radius); dx <= .48 * radius; dx++) {
                    int color = image.getRGB(axis + dx, H / 2 + sign * dy);
                    double weight = Math.pow(Math.max(0, ((color >> 8) & 255) - 148), 2);
                    displacement += weight * (dx + .5);
                    amount += weight;
                }
            }
            require(amount > 0, "Both jet roots must remain visible above the ambient background");
            maxOffset = Math.max(maxOffset, Math.abs(displacement / amount));
        }
        return maxOffset;
    }

    private static void renderWorldAppearance(Camera camera, Emission effect, KerrSampling plan, int scene, double radius) {
        int width = (int) Math.ceil(W * plan.traceScale()), height = (int) Math.ceil(H * plan.traceScale());
        if (rayWidth() != width || rayHeight() != height) rayTarget(width, height);
        bindEmission(camera, effect, List.of());
        var style = WhiteHolePreset.CINEMATIC_WHITE_HOLE.settings(effect.seed());
        for (int pass : new int[]{trace, composite}) {
            glUseProgram(pass);
            set(pass, "uWhiteStyle", style.diskDensity(), style.diskThickness(), style.spectrum(), style.coreGain());
        }
        Quality quality = Quality.ADAPTIVE;
        trace(effect.spin(), camera.inclination(), 0, camera.distance(), (float) quality.tolerance,
                quality.steps, effect.mode() > 0 ? .62f : 0, effect.time());
        int bloom = hdr.draw(rayLightTexture(), rayWidth(), rayHeight(), effect.exposure());
        prepareComposite(camera.inclination(), 0, (float) KerrControls.WHITE.requested(BLOOM));
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, scene);
        glUniform1i(glGetUniformLocation(composite, "SceneColor"), 0);
        set(composite, "uBgRect", 0, 0, W, H);
        set(composite, "uBgTexel", 1f / W, 1f / H);
        set(composite, "uShadowPixels", (float) radius);
        set(composite, "uRefine", plan.subpixels());
        glActiveTexture(GL_TEXTURE0 + BLOOM_UNIT);
        glBindTexture(GL_TEXTURE_2D, bloom);
        glUniform1i(glGetUniformLocation(composite, "RayBloom"), BLOOM_UNIT);
        set(composite, "uRayBloomLod", hdr.lastLevel(), KerrSampling.bloomLodBias(width, height, W, H, H));
        if (plan.transferScale() > 0) bakeFootprintsAtScale(plan.transferScale());
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static BufferedImage renderDistant(Camera camera, int side, double scale, double radius) {
        int x = (W - side) / 2, y = (H - side) / 2;
        int resolution = (int) Math.ceil(side * scale);
        rayTarget(resolution, resolution);
        bindEmission(camera, STEADY, List.of());
        Quality quality = Quality.ADAPTIVE;
        trace(STEADY.spin(), camera.inclination(), 0, camera.distance(), (float) quality.tolerance,
                quality.steps, .62f, STEADY.time(), x, y, side, side);
        prepareComposite(camera.inclination(), 1, .18f);
        set(composite, "uShadowPixels", (float) radius);
        set(composite, "uTraceRect", x, y, side, side);
        set(composite, "uDisplay", STEADY.exposure(), .18f);
        set(composite, "uRefine", 4f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        return readImage().getSubimage(x, y, side, side);
    }

    private static double meanPixelError(BufferedImage image, BufferedImage reference) {
        double error = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int actual = image.getRGB(x, y), expected = reference.getRGB(x, y);
            error += channelDelta(actual, expected, 16) + channelDelta(actual, expected, 8)
                    + channelDelta(actual, expected, 0);
        }
        return error / (3.0 * image.getWidth() * image.getHeight());
    }

    private static void verifyKerrBaseline() throws Exception {
        if (W != 800 || H != 560) return;
        var camera = new Camera(65, 0, 26);
        var kerr = new Emission(0, .65f, .46f, 1, SEED, 14, -1, 0, 0, 0, 1.15f);
        renderRays(camera, kerr, true);
        prepareComposite(camera.inclination(), 1, .18f);
        set(composite, "uRefine", 4f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        verifyGolden("a+0.65-i65", readImage());
    }

    private static void render(Camera camera, Emission effect, boolean highQuality) {
        render(camera, effect, highQuality, List.of());
    }

    private static void render(Camera camera, Emission effect, boolean highQuality, List<WhiteHoleEngine.Burst> bursts) {
        renderRays(camera, effect, highQuality, bursts);
        composite(camera, effect, highQuality);
    }

    private static void renderRays(Camera camera, Emission effect, boolean highQuality) {
        renderRays(camera, effect, highQuality, List.of());
    }

    private static void renderRays(Camera camera, Emission effect, boolean highQuality, List<WhiteHoleEngine.Burst> bursts) {
        bindEmission(camera, effect, bursts);
        trace(effect.spin(), camera.inclination(), 0, camera.distance(), highQuality ? .000060f : .00022f,
                highQuality ? 480 : 300, .62f, effect.time());
    }

    private static void bindEmission(Camera camera, Emission effect, List<WhiteHoleEngine.Burst> bursts) {
        orbitAzimuth = camera.azimuth();
        if (particleAtlas != 0) {
            int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0 + PARTICLE_UNIT);
            glBindTexture(GL_TEXTURE_2D, particleAtlas);
            glActiveTexture(activeTexture);
        }
        for (int pass : new int[]{trace, composite}) {
            glUseProgram(pass);
            set(pass, "uWhiteHole", effect.mode(), effect.beta(), effect.gain(), effect.seed());
            set(pass, "uWhiteTime", effect.time(), effect.burstAge(), effect.jets(), effect.burstStrength());
            set(pass, "uWhiteStyle", 1, 1, effect.spectrum(), 1);
            glUniform1i(glGetUniformLocation(pass, "WhiteParticleGrid"), PARTICLE_UNIT);
            set(pass, "uWhiteParticleGain", particleAtlas != 0 ? .55f : 0);
            set(pass, "uWhiteObserverBeta", effect.mode() * observerBeta);
            int newest = bursts.size() - 1;
            for (int bank = 0; bank < BURST_BANKS.length; bank++) {
                int index = newest - 1 - 2 * bank;
                set(pass, BURST_BANKS[bank], age(bursts, index, effect.time()), strength(bursts, index),
                        age(bursts, index - 1, effect.time()), strength(bursts, index - 1));
            }
        }
    }

    private static float age(List<WhiteHoleEngine.Burst> bursts, int index, float now) {
        return index < 0 ? -1 : (float) (now - bursts.get(index).startSeconds());
    }

    private static float strength(List<WhiteHoleEngine.Burst> bursts, int index) {
        return index < 0 ? 0 : (float) bursts.get(index).strength();
    }

    private static void composite(Camera camera, Emission effect, boolean highQuality) {
        float exposure = hdr == null ? effect.exposure() : hdr.adaptExposure(effect.exposure(), displayDelta, autoExposure);
        int bloom = hdr == null ? 0
                : hdr.draw(rayLightTexture(), rayWidth(), rayHeight(), exposure);
        prepareComposite(camera.inclination(), 1, .24f);
        if (bloom != 0) {
            int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            glActiveTexture(GL_TEXTURE0 + BLOOM_UNIT);
            glBindTexture(GL_TEXTURE_2D, bloom);
            glActiveTexture(activeTexture);
        }
        glUniform1i(glGetUniformLocation(composite, "RayBloom"), BLOOM_UNIT);
        set(composite, "uRayBloomLod", bloom != 0 ? hdr.lastLevel() : 0, 0);
        set(composite, "uDisplay", exposure, .24f);
        set(composite, "uRefine", highQuality ? 4f : 2f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void verifyRayBuffers() {
        var light = readRayBuffer("rayLight");
        var paths = readRayBuffer("rayExit");
        float peak = 0;
        int escaping = 0;
        int fromBoundary = 0;
        for (int i = 0; i < light.capacity(); i += 4) {
            for (int c = 0; c < 4; c++) {
                float value = light.get(i + c);
                require(Float.isFinite(value) && value >= 0, "Invalid linear light or coverage");
                if (c <= 2) peak = Math.max(peak, value);
            }
            require(light.get(i + 3) <= 1f, "Transmittance cannot exceed one");
            float termination = paths.get(i + 3);
            if (termination == 1) escaping++;
            if (termination == 2) fromBoundary++;
            require(termination == 0 || termination == 1 || termination == 2, "Invalid ray classification");
        }
        System.out.printf(Locale.ROOT, "Peak linear radiance %.3f, escaping rays %d, past-boundary rays %d%n",
                peak, escaping, fromBoundary);
        require(peak > 1, "Linear HDR target lost all highlight headroom");
        require(escaping > 0 && fromBoundary > 0 && escaping + fromBoundary > .99 * (light.capacity() / 4),
                "Expected both sky and past-boundary rays and fewer than 1% unresolved");
    }

    private static void verifyDepthProxy() {
        render(OBLIQUE, STEADY, true);
        int depth = glGenRenderbuffers();
        try {
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glClearDepth(1);
            glClear(GL_DEPTH_BUFFER_BIT);
            glColorMask(false, false, false, false);
            set(composite, "uCaptureDepthOnly", 1);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            float[] pixel = new float[1];
            glReadPixels(W / 2, H / 2, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, pixel);
            require(pixel[0] == 1, "The white radiating volume was replaced by a solid depth proxy");
            set(composite, "uWhiteHole", 0, 0, 0, 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glReadPixels(W / 2, H / 2, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, pixel);
            require(Math.abs(pixel[0] - .5f) < .0001f, "Depth test did not exercise the proxy pass");
        } finally {
            set(composite, "uCaptureDepthOnly", 0);
            glColorMask(true, true, true, true);
            glDisable(GL_DEPTH_TEST);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
            glDeleteRenderbuffers(depth);
        }
    }

    private static void benchmark(double scale) {
        rayTarget(scale);
        boolean highQuality = scale > .36;
        int query = glGenQueries();
        try {
            for (int i = 0; i < 4; i++) render(OBLIQUE, STEADY, highQuality);
            glFinish();
            int samples = 12;
            glBeginQuery(GL_TIME_ELAPSED, query);
            long start = System.nanoTime();
            for (int i = 0; i < samples; i++) render(OBLIQUE, STEADY, highQuality);
            long submitted = System.nanoTime();
            glEndQuery(GL_TIME_ELAPSED);
            double gpuMs = glGetQueryObjectui64(query, GL_QUERY_RESULT) / (samples * 1e6);
            double cpuMs = (submitted - start) / (samples * 1e6);
            System.out.printf(Locale.ROOT, "White hole %dx%d, ray field %dx%d: CPU submit %.3f ms, GPU %.3f ms/frame (%d frames)%n",
                    W, H, (int) Math.ceil(W * scale), (int) Math.ceil(H * scale), cpuMs, gpuMs, samples);
        } finally {
            glDeleteQueries(query);
        }
    }

    private static void save(String name, BufferedImage image, boolean updateGolden) throws IOException {
        ImageIO.write(image, "PNG", REPORT.resolve(name + ".png").toFile());
        if (W != 800 || H != 560) return;
        if (updateGolden) {
            Path generated = Files.createDirectories(REPORT.resolve("reference-candidates"));
            ImageIO.write(downsample(image), "PNG", generated.resolve(name + ".png").toFile());
            return;
        }
        var referenceFile = REFERENCES.resolve(name + ".png");
        var golden = ImageIO.read(referenceFile.toFile());
        var actual = downsample(image);
        double sum = 0;
        int outliers = 0;
        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                int delta = channelDelta(golden.getRGB(x, y), actual.getRGB(x, y), shift);
                sum += delta;
                if (delta > 24) outliers++;
            }
        }
        int channels = actual.getWidth() * actual.getHeight() * 3;
        System.out.printf(Locale.ROOT, "%s reference: mean RGB delta %.2f/255, large errors %.2f%%%n",
                name, sum / channels, 100.0 * outliers / channels);
        require(sum / channels < 5 && (double) outliers / channels < .05, "Visual regression in " + name);
    }

    private static BufferedImage downsample(BufferedImage source) {
        int block = 8;
        var small = new BufferedImage(W / block, H / block, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < small.getHeight(); y++) for (int x = 0; x < small.getWidth(); x++) {
            int red = 0, green = 0, blue = 0;
            for (int j = 0; j < block; j++) for (int i = 0; i < block; i++) {
                int pixel = source.getRGB(x * block + i, y * block + j);
                red += (pixel >> 16) & 255;
                green += (pixel >> 8) & 255;
                blue += pixel & 255;
            }
            int count = block * block;
            small.setRGB(x, y, (red / count) << 16 | (green / count) << 8 | blue / count);
        }
        return small;
    }

    private static int centerBrightness(BufferedImage image) {
        int total = 0;
        for (int y = H / 2 - 4; y <= H / 2 + 4; y++) for (int x = W / 2 - 4; x <= W / 2 + 4; x++) {
            int pixel = image.getRGB(x, y);
            total += Math.max(Math.max((pixel >> 16) & 255, (pixel >> 8) & 255), pixel & 255);
        }
        return total / 81;
    }

    private static double radialBrightness(BufferedImage background, BufferedImage shell) {
        double total = 0, weightedRadius = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double radius = Math.hypot(x - W * .5, y - H * .5);
            if (radius < H * .14 || Math.abs(x - W * .5) < W * .035) continue;
            int a = background.getRGB(x, y), b = shell.getRGB(x, y);
            // Use green luminance as a proxy, removing the fixed disk and sky.
            int gain = Math.max(0, ((b >> 8) & 255) - ((a >> 8) & 255));
            weightedRadius += gain * radius;
            total += gain;
        }
        require(total > 0, "Expected a positive shell emission field");
        return weightedRadius / total;
    }

    private static void compare(String name, BufferedImage a, BufferedImage b, double fraction) {
        int changed = changedPixels(a, b, 4);
        System.out.printf("%s: %d changed pixels (>4 RGB levels)%n", name, changed);
        require(changed > W * H * fraction, name + " did not visibly alter the rendered field");
    }

    private static int changedPixels(BufferedImage a, BufferedImage b, int threshold) {
        int changed = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int first = a.getRGB(x, y), second = b.getRGB(x, y);
            if (channelDelta(first, second, 0) > threshold || channelDelta(first, second, 8) > threshold
                    || channelDelta(first, second, 16) > threshold) changed++;
        }
        return changed;
    }

    private static int channelDelta(int a, int b, int shift) {
        return Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
    }

    private static void require(boolean condition, String error) {
        if (!condition) throw new AssertionError(error);
    }

    private record Camera(float inclination, float azimuth, float distance) {}

    private record Emission(float mode, float spin, float beta, float gain, long seed, float time, float burstAge,
                            float jets, float burstStrength, float spectrum, float exposure) {}

    private static final class Viewer {
        private final long window;
        private final int[] framebufferWidth = new int[1], framebufferHeight = new int[1];
        private final int[] windowX = new int[1], windowY = new int[1], windowWidth = new int[1], windowHeight = new int[1];
        private WhiteHoleEngine engine = WhiteHoleEngine.defaults(SEED);
        private long seed = SEED;
        private double cursorX, cursorY, timeScale = 1;
        private float targetInclination = OBLIQUE.inclination(), targetAzimuth, targetDistance = OBLIQUE.distance();
        private float exposure = STEADY.exposure();
        private boolean paused, autoOrbit, jets = true, capture, exportFrame, quality = true, qualityChanged;
        private int spectrum, captures;

        private final WhiteHoleParticleGrid particles;

        Viewer(long window, WhiteHoleParticleGrid particles) {
            this.window = window;
            this.particles = particles;
        }

        void run(boolean smoke, boolean expectParticles) throws IOException {
            rayTarget(.52);
            glfwSetKeyCallback(window, (handle, key, scanCode, action, mods) -> {
                if (action == GLFW_PRESS) press(key);
            });
            glfwSetCursorPosCallback(window, (handle, x, y) -> {
                if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS) {
                    targetAzimuth -= (float) (x - cursorX) * .3f;
                    targetInclination = Math.clamp(targetInclination + (float) (y - cursorY) * .3f, .1f, 179.9f);
                }
                cursorX = x;
                cursorY = y;
            });
            glfwSetScrollCallback(window, (handle, x, y) -> {
                targetDistance = Math.clamp(targetDistance * (float) Math.exp(-.11 * y), 3.5f, 110);
            });
            double previous = glfwGetTime();
            float inclination = targetInclination, azimuth = targetAzimuth, distance = targetDistance;
            BufferedImage first = null;
            int frames = 0;
            while (!glfwWindowShouldClose(window)) {
                glfwPollEvents();
                if (glfwWindowShouldClose(window)) break;
                double now = glfwGetTime(), delta = Math.clamp(now - previous, 0, .10);
                previous = now;
                if (smoke) delta = 1.0 / 60;
                displayDelta = delta;
                engine.advance(delta);
                if (autoOrbit) targetAzimuth += 12 * (float) delta;
                float fraction = (float) -Math.expm1(-12 * delta);
                inclination += (targetInclination - inclination) * fraction;
                azimuth += (targetAzimuth - azimuth) * fraction;
                distance += (targetDistance - distance) * fraction;
                float scale = quality ? .52f : .36f;
                if (qualityChanged || exportFrame) rayTarget(exportFrame ? 1.25 : scale);
                qualityChanged = exportFrame;
                var state = engine.snapshot();
                var effect = new Emission(1, (float) state.dimensionlessSpin(), (float) state.outflowBeta(),
                        (float) state.radianceGain(), state.seed(),
                        (float) state.simulationTimeSeconds(), (float) state.burstAgeSeconds(), jets ? .8f : 0,
                        (float) state.burstStrength(), spectrum, exposure);
                var camera = new Camera(inclination, azimuth, distance);
                particleAtlas = particles.update(state.simulationTimeSeconds(), state.seed(), effect.beta(),
                        effect.jets(), (float) state.geometry().horizon().radiusRg(), quality ? 16384 : 4096);
                render(camera, effect, quality || exportFrame, state.bursts());
                if (expectParticles && frames == 0) {
                    require(particleAtlas != 0, "The particle viewer must use the compute atlas");
                    BufferedImage withParticles = readImage();
                    int atlas = particleAtlas;
                    particleAtlas = 0;
                    render(camera, effect, quality || exportFrame, state.bursts());
                    int changed = changedPixels(withParticles, readImage(), 1);
                    require(changed > W * H / 100, "The GPU density atlas must affect the composited light");
                    System.out.printf(Locale.ROOT, "Particle transfer changed %d pixels%n", changed);
                    ImageIO.write(withParticles, "PNG", REPORT.resolve("particle-viewer.png").toFile());
                    particleAtlas = atlas;
                    render(camera, effect, quality || exportFrame, state.bursts());
                }
                if (capture || exportFrame) {
                    String name = exportFrame ? "offline-frame" : "capture";
                    ImageIO.write(readImage(), "PNG", REPORT.resolve(name + "-" + ++captures + ".png").toFile());
                    capture = exportFrame = false;
                }
                present();
                if (smoke) {
                    // Use the same key dispatcher as the GLFW callback, with a bounded run in CI.
                    switch (++frames) {
                        case 1 -> {
                            first = readImage();
                            press(GLFW_KEY_SPACE);
                        }
                        case 2 -> {
                            press(GLFW_KEY_N);
                            press(GLFW_KEY_B);
                            press(GLFW_KEY_SPACE);
                            press(GLFW_KEY_A);
                            press(GLFW_KEY_LEFT);
                            press(GLFW_KEY_RIGHT_BRACKET);
                            press(GLFW_KEY_Q);
                            press(GLFW_KEY_S);
                            press(GLFW_KEY_E);
                        }
                        case 16 -> press(GLFW_KEY_O);
                        case 17 -> {
                            compare("Viewer controls", first, readImage(), .02);
                            require(state.simulationTimeSeconds() > .20 && state.dimensionlessSpin() < .65
                                            && state.outflowBeta() > .42 && state.burstAgeSeconds() >= 0 && captures == 1,
                                    "Viewer failed to apply the requested control changes");
                            return;
                        }
                        default -> { }
                    }
                }
            }
        }

        private void press(int key) {
            switch (key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(window, true);
                case GLFW_KEY_SPACE -> engine.setPaused(paused = !paused);
                case GLFW_KEY_N -> { if (paused) engine.stepOnce(); }
                case GLFW_KEY_B -> engine.triggerBurst(1.2);
                case GLFW_KEY_R -> engine.reset();
                case GLFW_KEY_A -> autoOrbit = !autoOrbit;
                case GLFW_KEY_J -> jets = !jets;
                case GLFW_KEY_E -> autoExposure = !autoExposure;
                case GLFW_KEY_S -> spectrum = (spectrum + 1) % 6;
                case GLFW_KEY_Q -> { quality = !quality; qualityChanged = true; }
                case GLFW_KEY_P -> capture = true;
                case GLFW_KEY_O -> exportFrame = true;
                case GLFW_KEY_LEFT -> changeSpin(-.05);
                case GLFW_KEY_RIGHT -> changeSpin(.05);
                case GLFW_KEY_LEFT_BRACKET -> changeBeta(-.05);
                case GLFW_KEY_RIGHT_BRACKET -> changeBeta(.05);
                case GLFW_KEY_U -> observerBeta = Math.clamp(observerBeta - .05f, -.85f, .85f);
                case GLFW_KEY_I -> observerBeta = Math.clamp(observerBeta + .05f, -.85f, .85f);
                case GLFW_KEY_M -> {
                    var params = engine.parameters();
                    engine.setParameters(params.spacetime() == StandardSpacetime.SCHWARZSCHILD
                            ? params.withModel(StandardSpacetime.KERR, .65, 0)
                            : params.withModel(StandardSpacetime.SCHWARZSCHILD, 0, 0));
                }
                case GLFW_KEY_DOWN -> exposure = Math.clamp(exposure - .15f, .10f, 4);
                case GLFW_KEY_UP -> exposure = Math.clamp(exposure + .15f, .10f, 4);
                case GLFW_KEY_MINUS -> engine.setTimeScale(timeScale = Math.max(.125, timeScale / 2));
                case GLFW_KEY_EQUAL -> engine.setTimeScale(timeScale = Math.min(8, timeScale * 2));
                case GLFW_KEY_G -> {
                    seed = (seed * 25173 + 13849) & 0xffff;
                    engine = WhiteHoleEngine.defaults(seed);
                    engine.setPaused(paused);
                    engine.setTimeScale(timeScale);
                }
                case GLFW_KEY_F11 -> toggleFullscreen();
                default -> { }
            }
        }

        private void changeSpin(double increment) {
            var params = engine.parameters();
            double spin = Math.clamp(params.dimensionlessSpin() + increment, -.998, .998);
            engine.setParameters(params.withModel(StandardSpacetime.KERR, spin, 0));
        }

        private void changeBeta(double increment) {
            var params = engine.parameters();
            engine.setParameters(params.withEmission(params.radiation(), params.luminosityWatts(),
                    params.restMassFlowKgPerSecond(), Math.clamp(params.outflowBeta() + increment, .02, .98),
                    params.plasmaTemperatureKelvin()));
        }

        private void toggleFullscreen() {
            if (glfwGetWindowMonitor(window) != 0) {
                glfwSetWindowMonitor(window, 0, windowX[0], windowY[0], windowWidth[0], windowHeight[0], GLFW_DONT_CARE);
                return;
            }
            long monitor = glfwGetPrimaryMonitor();
            var mode = glfwGetVideoMode(monitor);
            if (mode == null) return;
            glfwGetWindowPos(window, windowX, windowY);
            glfwGetWindowSize(window, windowWidth, windowHeight);
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
        }

        private void present() {
            glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
            int width = framebufferWidth[0], height = framebufferHeight[0];
            if (width <= 0 || height <= 0) return;
            float scale = Math.min((float) width / W, (float) height / H);
            int w = Math.round(W * scale), h = Math.round(H * scale);
            int x = (width - w) / 2, y = (height - h) / 2;
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glBlitFramebuffer(0, 0, W, H, x, y, x + w, y + h, GL_COLOR_BUFFER_BIT, GL_LINEAR);
            glfwSwapBuffers(window);
        }
    }
}
