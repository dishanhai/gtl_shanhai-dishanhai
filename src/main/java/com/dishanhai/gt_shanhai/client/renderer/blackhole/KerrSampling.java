package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;

/** Bounds the number of costly geodesic samples independently of output resolution. */
record KerrSampling(double traceScale, double transferScale, int subpixels) {
    static final int CRITICAL_STRIP_ROWS = 24;
    static final int CACHED_CRITICAL_STRIP_ROWS = 20;
    private static final int ADAPTIVE_PIXELS = 105_000;
    private static final int ADAPTIVE_LARGE_SHADOW_PIXELS = 300_000;
    private static final int ADAPTIVE_CLOSE_TRACE_PIXELS = 60_000;
    private static final int CLOSE_TRACE_PIXELS = 90_000;
    private static final int TRANSFER_PIXELS = 320_000;
    private static final int CLOSE_TRANSFER_PIXELS = 120_000;
    private static final int NATIVE_COMPOSITE_PIXELS = 480_000;

    static KerrSampling plan(Quality quality, double requestedScale, double adaptiveScale, double area,
                             double shadowFraction, double observerDistance, double shadowRadiusPixels) {
        boolean lowBudget = quality == Quality.LOW || quality == Quality.COMPATIBILITY;
        if (quality != Quality.ADAPTIVE && !lowBudget) {
            return new KerrSampling(requestedScale, 0, 4);
        }

        // The integrator takes smaller steps near the disk. Reduce the sample count
        // before the first near-field frame, even if a previous view was inexpensive.
        double close = nearWeight(observerDistance);
        double traceScale = requestedScale;
        if (quality == Quality.ADAPTIVE) {
            double budget = lerp(ADAPTIVE_PIXELS, ADAPTIVE_LARGE_SHADOW_PIXELS, shadowFraction * shadowFraction);
            budget = lerp(budget, ADAPTIVE_CLOSE_TRACE_PIXELS, close);
            double factor = adaptiveScale / Quality.ADAPTIVE.scale;
            // Thin arcs can fall between pilot rays even when refinement runs at display resolution.
            // Spend spare rays on small distant targets, where the trace rectangle is cheap.
            double detailWeight = smoothUnit((96 - shadowRadiusPixels) / 64)
                    * smoothUnit((observerDistance - 32) / 48);
            double preferredScale = lerp(requestedScale, 1, detailWeight);
            traceScale = Math.min(preferredScale * factor, Math.sqrt(budget / area) * Math.min(1, factor));
            traceScale = Math.min(1, traceScale);
        } else {
            traceScale = lerp(requestedScale, Math.min(requestedScale, Math.sqrt(CLOSE_TRACE_PIXELS / area)), close);
        }

        int subpixels = lowBudget || close > .15 || adaptiveScale < .40 ? 2 : 4;
        double transferScale = 0;
        if (area >= NATIVE_COMPOSITE_PIXELS || observerDistance < 16) {
            double budget = lerp(TRANSFER_PIXELS, CLOSE_TRANSFER_PIXELS, close);
            // As the pilot shrinks, the refinement pass shrinks with it. The final
            // compositor still resolves the capture boundary at display resolution.
            double resolveDensity = 1.65;
            transferScale = Math.min(.74, Math.min(resolveDensity * traceScale, Math.sqrt(budget / area)));
        }
        return new KerrSampling(traceScale, transferScale, subpixels);
    }

    static double nearWeight(double observerDistance) {
        return smoothUnit((20 - observerDistance) / 14);
    }

    static float exteriorWeight(Quality quality) {
        return quality == Quality.COMPATIBILITY ? 1f : 0f;
    }

    // Cached composition has no temporal accumulation. Moving its coarse grid
    // just changes which side of a subpixel arc the pilot sees on each frame.
    static double blackHoleJitter(double transferScale, double observerDistance) {
        return transferScale > 0 ? 0 : smoothUnit((observerDistance - 16) / 12);
    }

    static float bloomLodBias(int width, int height, float rectWidth, float rectHeight, int screenHeight) {
        double pixelScale = Math.clamp(screenHeight / 672.0, .65, 3.5);
        double gridScale = Math.min(width / rectWidth, height / rectHeight);
        return (float) (Math.log(pixelScale * gridScale) / Math.log(2));
    }

    static int criticalStripWidth(float radius, int screenHeight, double observerDistance) {
        // Contour-centred sampling pays off when its projected image fits in
        // a compact annulus. Close wide-angle views use the transfer cache.
        if (!(radius >= 8 && radius <= .62 * screenHeight && observerDistance >= 10)) return 0;
        int columns = (int) Math.ceil(2 * Math.PI * radius / 1.8);
        return Math.clamp((columns + 31) & ~31, 64, 640);
    }

    static float criticalStripSpan(double transferScale, double observerDistance) {
        if (transferScale == 0) return 5f;
        // Cover the visible footprint of a coarse transfer cell without
        // taking high-order samples across the wide-angle near field.
        if (observerDistance < 16 || transferScale > .55) return 0f;
        return (float) Math.clamp(2.2 / transferScale, 6, 12);
    }

    static int blackHoleCriticalStripWidth(float radius, int screenHeight, double observerDistance, boolean cached) {
        if (!cached) return criticalStripWidth(radius, screenHeight, observerDistance);
        // The native critical curve is most valuable when transfer cells cover
        // many display pixels. The shader checks that the contour is bracketed.
        if (!(radius >= 8 && radius <= 1.15 * screenHeight && observerDistance >= 4)) return 0;
        int columns = (int) Math.ceil(2 * Math.PI * radius / 2.0);
        return Math.clamp((columns + 31) & ~31, 64, 640);
    }

    static float blackHoleCriticalStripSpan(double transferScale, double observerDistance) {
        return transferScale == 0 ? criticalStripSpan(transferScale, observerDistance)
                : (float) Math.clamp(2.2 / transferScale, 5, 16);
    }

    // The foreground disk crosses the interior of the shadow. Its edge cannot
    // be reconstructed from the capture boundary or from a coarse transfer cell.
    record DiskDetail(int x, int y, int side, int samples, float weight) {}

    DiskDetail diskDetail(double x, double y, double radius, int screenWidth, int screenHeight) {
        if (!(radius > 6 && radius <= Math.max(screenWidth, screenHeight) && Double.isFinite(x) && Double.isFinite(y))) return null;
        int half = ((int) Math.ceil(1.9 * radius) + 7) & ~7;
        int side = 2 * half;
        if (x + half <= 0 || x - half >= screenWidth || y + half <= 0 || y - half >= screenHeight) return null;
        int samples = Math.min(224, side);
        double base = Math.max(traceScale, transferScale);
        float weight = (float) (smoothUnit((samples / (double) side - base - .12) / .35) * smoothUnit((radius - 6) / 6));
        if (weight == 0) return null;
        return new DiskDetail((int) Math.floor(x) - half, (int) Math.floor(y) - half, side, samples, weight);
    }

    static double shadowFraction(double observerDistance, double tanHalfFov) {
        // Projected radius can spike when the hole approaches the camera plane during a turn.
        return Math.min(1, 5 * BlackHoleLensMath.B_CRIT / (Math.max(2, observerDistance) * tanHalfFov));
    }

    private static double smoothUnit(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double from, double to, double weight) {
        return from + (to - from) * weight;
    }
}
