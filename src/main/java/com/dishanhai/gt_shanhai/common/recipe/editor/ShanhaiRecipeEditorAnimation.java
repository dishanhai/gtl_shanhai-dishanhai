package com.dishanhai.gt_shanhai.common.recipe.editor;

/**
 * Small deterministic stage transition used by the recipe editor widget.
 *
 * <p>The widget only consumes progress and never owns timing arithmetic, so
 * the transition can be checked without loading Minecraft or LDLib.</p>
 */
public final class ShanhaiRecipeEditorAnimation {

    public static final long DURATION_MS = 640L;

    /** Monotonic millisecond clock. currentTimeMillis jumps by a timer quantum and steps the slide. */
    public static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private int currentStage;
    private int targetStage;
    private long startedAt;

    public ShanhaiRecipeEditorAnimation() {
        reset(0, 0L);
    }

    public void reset(int stage, long nowMs) {
        currentStage = Math.max(0, stage);
        targetStage = currentStage;
        startedAt = nowMs;
    }

    public void advance(int stage, long nowMs) {
        int safeStage = Math.max(0, stage);
        if (safeStage != targetStage) {
            targetStage = safeStage;
            startedAt = nowMs;
        }
        if (progress(nowMs) >= 1.0f) {
            currentStage = targetStage;
        }
    }

    public int currentStage() {
        return currentStage;
    }

    public int targetStage() {
        return targetStage;
    }

    public float progress(long nowMs) {
        if (currentStage == targetStage) return 1.0f;
        long elapsed = Math.max(0L, nowMs - startedAt);
        float linear = Math.min(1.0f, elapsed / (float) DURATION_MS);
        // Smoothstep removes the visible velocity jump at both ends of a page slide.
        return linear * linear * (3.0f - 2.0f * linear);
    }

    public boolean moving(long nowMs) {
        return currentStage != targetStage && progress(nowMs) < 1.0f;
    }
}
