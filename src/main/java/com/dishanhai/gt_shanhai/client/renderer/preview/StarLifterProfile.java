package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL33.*;

/** Opt-in capture for SHDT. GPU timestamps can span nested stages and other mods' elapsed queries. */
public final class StarLifterProfile implements AutoCloseable {
    private static final String OUTPUT = System.getProperty("gt_shanhai.starLifter.profile", "");
    private static final int MAX_SAMPLES = 300_000;
    private static final int MAX_PENDING = 512;
    private static final int QUERY_DELAY = 3;
    private static final int STAGE_STRIDE = Math.max(1, Integer.getInteger("gt_shanhai.starLifter.profileStride", 4));
    private static final List<StarLifterProfile> SAMPLES = new ArrayList<>();
    private static final ArrayDeque<StarLifterProfile> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<Integer> QUERIES = new ArrayDeque<>();
    private static long frame, previousFrame, frameStart;
    private static int missedGpu;
    private static boolean full;
    private static boolean preview;
    private static String scene;

    private final String stage;
    private final String sampleScene;
    private final long sampleFrame;
    private final long start;
    private long cpu, gpu = -1;
    private int beginQuery, endQuery;
    private int draws, vertices, uploadBytes, framebuffers;

    private StarLifterProfile(String stage, long start) {
        this.stage = stage;
        this.sampleScene = scene;
        this.sampleFrame = frame;
        this.start = start;
    }

    public static StarLifterProfile begin(String stage, boolean gpu) {
        if (scene == null || (frame % STAGE_STRIDE != 0 && !stage.equals("ResourceUpdate")) || !hasCapacity()) return null;
        String name = preview && !stage.equals("ResourceUpdate") ? "UIPreview." + stage : stage;
        var sample = new StarLifterProfile(name, System.nanoTime());
        if (gpu && PENDING.size() < MAX_PENDING && (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) {
            sample.beginQuery = query();
            sample.endQuery = query();
            glQueryCounter(sample.beginQuery, GL_TIMESTAMP);
        } else if (gpu) {
            missedGpu++;
        }
        if (stage.equals("UIPreview")) preview = true;
        return sample;
    }

    public static void draw(StarLifterProfile sample, int draws, int vertices, int uploadBytes) {
        if (sample != null) {
            sample.draws += draws;
            sample.vertices += vertices;
            sample.uploadBytes += uploadBytes;
        }
    }

    static void framebuffer(StarLifterProfile sample) {
        if (sample != null) sample.framebuffers++;
    }

    static void previewStorage(int width, int height, int storageWidth, int storageHeight,
                               int samples, int mips, long pixels, long attachmentBytes) {
        if (OUTPUT.isEmpty()) return;
        GTDishanhaiMod.LOGGER.info("Star Lifter preview viewport={}x{}, storage={}x{}, MSAA={}, atlasMip={}, pixels={}, attachmentBytes={}",
                width, height, storageWidth, storageHeight, samples, mips, pixels, attachmentBytes);
        // Allocation is infrequent and may fall outside the sampled stage stride.
        if (scene != null && hasCapacity()) {
            var sample = new StarLifterProfile("StarLifterUI.TargetAllocation", System.nanoTime());
            sample.framebuffers = 1;
            SAMPLES.add(sample);
        }
    }

    @Override
    public void close() {
        if (stage.equals("UIPreview")) preview = false;
        if (endQuery != 0) {
            glQueryCounter(endQuery, GL_TIMESTAMP);
            PENDING.addLast(this);
        }
        cpu = System.nanoTime() - start;
        if (hasCapacity()) SAMPLES.add(this);
    }

    static void startFrame() {
        if (OUTPUT.isEmpty()) return;
        frame++;
        poll();
        frameStart = System.nanoTime();
        String current = System.getProperty("gt_shanhai.starLifter.profileScene", "live").replace(',', '_');
        if (!current.equals(scene)) previousFrame = 0;
        scene = current.isEmpty() ? null : current;
        if (scene != null && previousFrame != 0) recordFrame("FrameStartInterval", frameStart - previousFrame);
        previousFrame = frameStart;
    }

    static void endFrame() {
        if (scene != null) recordFrame("RenderTick", System.nanoTime() - frameStart);
    }

    private static void recordFrame(String stage, long duration) {
        if (!hasCapacity()) return;
        var sample = new StarLifterProfile(stage, frameStart);
        sample.cpu = duration;
        SAMPLES.add(sample);
    }

    private static boolean hasCapacity() {
        if (SAMPLES.size() < MAX_SAMPLES) return true;
        full = true;
        return false;
    }

    private static int query() {
        return QUERIES.isEmpty() ? glGenQueries() : QUERIES.removeFirst();
    }

    private static void poll() {
        while (!PENDING.isEmpty()) {
            var sample = PENDING.getFirst();
            if (frame - sample.sampleFrame < QUERY_DELAY || glGetQueryObjecti(sample.endQuery, GL_QUERY_RESULT_AVAILABLE) == 0) return;
            sample.gpu = glGetQueryObjectui64(sample.endQuery, GL_QUERY_RESULT)
                    - glGetQueryObjectui64(sample.beginQuery, GL_QUERY_RESULT);
            QUERIES.addLast(sample.beginQuery);
            QUERIES.addLast(sample.endQuery);
            PENDING.removeFirst();
        }
    }

    static void finish() {
        if (OUTPUT.isEmpty()) return;
        poll();
        missedGpu += PENDING.size();
        for (var sample : PENDING) {
            glDeleteQueries(sample.beginQuery);
            glDeleteQueries(sample.endQuery);
        }
        PENDING.clear();
        while (!QUERIES.isEmpty()) glDeleteQueries(QUERIES.removeFirst());
        if (SAMPLES.isEmpty()) return;
        try {
            var path = Path.of(OUTPUT).toAbsolutePath();
            Files.createDirectories(path.getParent());
            try (var gpu = Files.newBufferedWriter(path);
                 var raw = Files.newBufferedWriter(path.resolveSibling(path.getFileName() + ".stages.csv"))) {
                gpu.write("scene,metric,kind,ms\n");
                raw.write("scene,frame,stage,cpu_ns,gpu_ns,draws,vertices,triangles,upload_bytes,framebuffer_binds\n");
                for (var s : SAMPLES) {
                    if (s.gpu >= 0) gpu.write(s.sampleScene + ",StarLifter." + s.stage + ",pass," + s.gpu / 1_000_000.0 + "\n");
                    raw.write(s.sampleScene + "," + s.sampleFrame + "," + s.stage + "," + s.cpu + "," + s.gpu + ","
                            + s.draws + "," + s.vertices + "," + s.vertices / 2 + "," + s.uploadBytes + "," + s.framebuffers + "\n");
                }
            }
            GTDishanhaiMod.LOGGER.info("Star Lifter profile: {} samples, {} unavailable GPU results, capacity reached: {}, {}",
                    SAMPLES.size(), missedGpu, full, path);
        } catch (IOException error) {
            GTDishanhaiMod.LOGGER.warn("Could not write Star Lifter profile", error);
        } finally {
            SAMPLES.clear();
            missedGpu = 0;
            full = false;
            scene = null;
        }
    }
}
