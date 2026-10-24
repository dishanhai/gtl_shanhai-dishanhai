package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

/** 山海：每個客戶端機器各自平滑過渡，無靜態跨世界台帳。 */
public final class GuixuVisualState {
    public record Profile(float density, float brightness, float jets) {}
    private double lastSeconds = Double.NaN;
    private float density = GuixuOperationState.IDLE.density;
    private float brightness = GuixuOperationState.IDLE.brightness;
    private float jets;

    public Profile advance(GuixuOperationState state, double seconds) {
        double delta = Double.isNaN(lastSeconds) ? 0.0 : Math.max(0.0, seconds - lastSeconds);
        lastSeconds = seconds;
        float weight = (float) (1.0 - Math.exp(-Math.min(delta, 5.0) * 2.0));
        density += (state.density - density) * weight;
        brightness += (state.brightness - brightness) * weight;
        jets += ((state == GuixuOperationState.GENERATING ? 1.0F : 0.0F) - jets) * weight;
        return new Profile(density, brightness, jets);
    }
}
