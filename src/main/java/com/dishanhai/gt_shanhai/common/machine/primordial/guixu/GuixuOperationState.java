package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

/** 山海：工作位來自服務端配方邏輯，與「始終渲染」開關分開。 */
public enum GuixuOperationState {
    IDLE("待機", 0.25F, 0.28F),
    PROCESSING("加工", 1.0F, 1.0F),
    GENERATING("發電", 1.0F, 1.0F),
    BLOCKED("等待資源／輸出", 0.10F, 0.12F);

    public final String label;
    public final float density;
    public final float brightness;

    GuixuOperationState(String label, float density, float brightness) {
        this.label = label;
        this.density = density;
        this.brightness = brightness;
    }

    public static GuixuOperationState resolve(boolean working, boolean generating, boolean blocked) {
        return working ? (generating ? GENERATING : PROCESSING) : (blocked ? BLOCKED : IDLE);
    }

    public static GuixuOperationState fromId(int id) {
        return id >= 0 && id < values().length ? values()[id] : IDLE;
    }
}
