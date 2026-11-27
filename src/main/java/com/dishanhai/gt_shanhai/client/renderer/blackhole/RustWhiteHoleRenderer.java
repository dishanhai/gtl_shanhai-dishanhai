package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.file.Path;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;

/** Native backend only when an explicit absolute library path is supplied. */
final class RustWhiteHoleRenderer {
    private static final boolean ACTIVE = load();

    private RustWhiteHoleRenderer() {}

    static boolean active() {
        return ACTIVE;
    }

    private static boolean load() {
        String library = System.getProperty("gtsh.whitehole.rustLibrary");
        if (library == null || library.isBlank()) return false;
        try {
            System.load(Path.of(library).toString());
            if (abi() == 1) return true;
            LogUtils.getLogger().warn("Unsupported Rust white-hole ABI; using the Java OpenGL path");
        } catch (LinkageError | IllegalArgumentException failure) {
            LogUtils.getLogger().warn("Rust white-hole backend unavailable; using the Java OpenGL path", failure);
        }
        return false;
    }

    static int directDivisor(Quality quality, double area, int previous) {
        return ACTIVE ? planDirect(quality == Quality.ADAPTIVE || quality == Quality.LOW, area, previous)
                : KerrSampling.whiteDirectDivisor(quality, area, previous);
    }

    static boolean cacheDirect(boolean reduced, double distance, int width, int height) {
        return ACTIVE ? planCache(reduced, distance, width, height)
                : (reduced || distance <= 70) && (long) width * height <= KerrSampling.WHITE_DIRECT_CACHE_PIXELS;
    }

    static boolean canReuse(long ageNanos, int reusedFrames) {
        return ACTIVE ? planReuse(ageNanos, reusedFrames) : KerrSampling.whiteHostCanReuse(ageNanos, reusedFrames);
    }

    static long tick(double seconds) {
        return ACTIVE ? particleTick(seconds) : Math.max(0, (long) Math.floor(seconds * 30));
    }

    static void submit(GLCapabilities gl, int atlas, int particle, int accumulator, int density, int buffer,
                       int normalizeLocation, int seedLocation, int tickLocation, int countLocation, int flowLocation,
                       long seed, long tick, int count, float beta, float jets, float horizon) {
        dispatch(gl.glBindImageTexture, gl.glUseProgram, gl.glUniform1i, gl.glDispatchCompute, gl.glMemoryBarrier,
                gl.glBindBufferBase, gl.glUniform2ui, gl.glUniform1ui, gl.glUniform4f,
                atlas, particle, accumulator, density, buffer, normalizeLocation, seedLocation, tickLocation,
                countLocation, flowLocation, seed, tick, count, beta, jets, horizon);
    }

    static native void draw(long drawArrays);

    private static native int abi();
    private static native int planDirect(boolean adaptive, double area, int previous);
    private static native boolean planCache(boolean reduced, double distance, int width, int height);
    private static native boolean planReuse(long ageNanos, int reusedFrames);
    private static native long particleTick(double seconds);
    private static native void dispatch(long bindImage, long useProgram, long uniformInt, long dispatch, long barrier,
                                        long bindBuffer, long uniformPair, long uniformUint, long uniformFlow,
                                        int atlas, int particle, int accumulator, int density, int buffer,
                                        int normalizeLocation, int seedLocation, int tickLocation, int countLocation,
                                        int flowLocation, long seed, long tick, int count, float beta, float jets,
                                        float horizon);
}
