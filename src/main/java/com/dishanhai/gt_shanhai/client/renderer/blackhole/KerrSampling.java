package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;

/** Bounds the number of costly geodesic samples independently of output resolution. */
record KerrSampling(double traceScale, double transferScale, int subpixels) {
    private static final int ADAPTIVE_PIXELS = 105_000;
    private static final int ADAPTIVE_LARGE_SHADOW_PIXELS = 300_000;
    private static final int CLOSE_TRACE_PIXELS = 90_000;
    private static final int TRANSFER_PIXELS = 320_000;
    private static final int CLOSE_TRANSFER_PIXELS = 120_000;
    private static final int NATIVE_COMPOSITE_PIXELS = 480_000;

    static KerrSampling plan(Quality quality, double requestedScale, double adaptiveScale, double area,
                             double shadowFraction, double observerDistance) {
        if (quality != Quality.ADAPTIVE && quality != Quality.LOW) {
            return new KerrSampling(requestedScale, 0, 4);
        }

        // The integrator takes smaller steps near the disk. Reduce the sample count
        // before the first near-field frame, even if a previous view was inexpensive.
        double t = Math.clamp((16 - observerDistance) / 10, 0, 1);
        double close = t * t * (3 - 2 * t);
        double traceScale = requestedScale;
        if (quality == Quality.ADAPTIVE) {
            double budget = lerp(ADAPTIVE_PIXELS, ADAPTIVE_LARGE_SHADOW_PIXELS, shadowFraction * shadowFraction);
            budget = lerp(budget, CLOSE_TRACE_PIXELS, close);
            double factor = adaptiveScale / Quality.ADAPTIVE.scale;
            traceScale = Math.min(requestedScale * factor, Math.sqrt(budget / area) * Math.min(1, factor));
            traceScale = Math.min(1, traceScale);
        } else {
            traceScale = lerp(requestedScale, Math.min(requestedScale, Math.sqrt(CLOSE_TRACE_PIXELS / area)), close);
        }

        int subpixels = quality == Quality.LOW || close > .15 || adaptiveScale < .40 ? 2 : 4;
        double transferScale = 0;
        if (area >= NATIVE_COMPOSITE_PIXELS || close > 0) {
            double budget = lerp(TRANSFER_PIXELS, CLOSE_TRANSFER_PIXELS, close);
            // As the pilot shrinks, the refinement pass shrinks with it. The final
            // compositor still resolves the capture boundary at display resolution.
            transferScale = Math.min(.74, Math.min(1.65 * traceScale, Math.sqrt(budget / area)));
        }
        return new KerrSampling(traceScale, transferScale, subpixels);
    }

    private static double lerp(double from, double to, double weight) {
        return from + (to - from) * weight;
    }
}
