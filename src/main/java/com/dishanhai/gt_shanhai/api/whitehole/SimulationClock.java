package com.dishanhai.gt_shanhai.api.whitehole;

import java.util.function.LongConsumer;

/** Fixed 120 Hz physics, with one-step-behind interpolation between completed steps. */
final class SimulationClock {
    static final int STEPS_PER_SECOND = 120;
    static final double STEP_SECONDS = 1.0 / STEPS_PER_SECOND;

    private long step;
    private double pendingSteps;
    private double timeScale = 1;
    private boolean paused;
    private long lastManualStep = -1;

    void advance(double elapsedSeconds, LongConsumer update) {
        WhiteHoleParameters.requireRange(elapsedSeconds, 0, 5, "elapsed real time");
        if (paused) return;
        pendingSteps += elapsedSeconds * timeScale * STEPS_PER_SECOND;
        while (pendingSteps >= 1 - 1e-10) {
            update.accept(step + 1);
            step++;
            pendingSteps = Math.max(0, pendingSteps - 1);
        }
    }

    void stepOnce(LongConsumer update) {
        if (!paused) throw new IllegalStateException("pause before advancing one step");
        update.accept(step + 1);
        step++;
        lastManualStep = step;
    }

    void reset() {
        step = 0;
        pendingSteps = 0;
        lastManualStep = -1;
    }

    void setTimeScale(double multiplier) {
        WhiteHoleParameters.requireRange(multiplier, .03125, 16, "time multiplier");
        timeScale = multiplier;
    }

    void setPaused(boolean value) {
        paused = value;
    }

    boolean isPaused() {
        return paused;
    }

    long step() {
        return step;
    }

    double completedTime() {
        return step * STEP_SECONDS;
    }

    double interpolation() {
        return step == lastManualStep || step == 0 ? 1 : Math.min(1, pendingSteps);
    }

    double renderTime() {
        return step == 0 ? 0 : (step - 1 + interpolation()) * STEP_SECONDS;
    }
}
