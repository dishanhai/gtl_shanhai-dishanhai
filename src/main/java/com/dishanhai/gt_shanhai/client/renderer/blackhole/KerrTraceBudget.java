package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/** Bounds paired trace targets independently of output size and texture alignment. */
final class KerrTraceBudget {
    private KerrTraceBudget() {}

    // Two history targets, each RGBA16F light + RGBA32F exit: 48 bytes per ray.
    static final int TRACE_TARGET_PIXELS = 2_097_152; // 96 MiB for both targets

    record TraceExtent(int width, int height) {}

    static TraceExtent extent(double scale, double rectWidth, double rectHeight, int allocatedWidth,
                                   int allocatedHeight, int tile) {
        double boundedScale = Math.min(scale, Math.sqrt(TRACE_TARGET_PIXELS / (rectWidth * rectHeight)));
        double targetWidth = rectWidth * boundedScale, targetHeight = rectHeight * boundedScale;
        int width = Math.max(tile, ((int) Math.ceil(targetWidth) + tile - 1) & -tile);
        int height = Math.max(tile, ((int) Math.ceil(targetHeight) + tile - 1) & -tile);
        int stableWidth = stableExtent(width, allocatedWidth, tile);
        int stableHeight = stableExtent(height, allocatedHeight, tile);
        if ((long) stableWidth * stableHeight <= TRACE_TARGET_PIXELS) return new TraceExtent(stableWidth, stableHeight);
        while ((long) width * height > TRACE_TARGET_PIXELS) {
            if (width > tile && (height == tile || width / targetWidth >= height / targetHeight)) width -= tile;
            else height -= tile;
        }
        return new TraceExtent(width, height);
    }

    private static int stableExtent(int requested, int allocated, int tile) {
        // At least two slots of hysteresis for larger tiles; small targets still track the view.
        int tolerance = Math.min(2 * tile, requested / 16);
        return allocated != 0 && allocated % tile == requested % tile
                && Math.abs(requested - allocated) <= tolerance ? allocated : requested;
    }

}
