package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArModel.*;

/** 山海：全息投影的材質、配色與邊緣動畫；只產生純資料圖元。 */
final class HostFxGlassArHologram {
    static final int HEAD = 0xFF8DEAFF;
    static final int MAIN = 0xFFE0F5FF;
    static final int DIM = 0xC479A9BD;
    static final int WARN = 0xFFFFC16B;
    static final int ACCENT = 0xE641D9FF;
    static final int BACKING = 0x40061120;
    private static final int CORNER = 0xA857D4F4;
    private static final int RAIL = 0x28399BBE;

    private HostFxGlassArHologram() {}

    static void build(State state, List<Prim> out) {
        // 先提交所有 alpha 底面，再提交裝飾；分區間完全透空。
        if (state.arMask) {
            for (int i = 0; i < HostFxGlassArPanel.ZONES.length; i++) {
                var zone = HostFxGlassArPanel.ZONES[i];
                rect(out, i, "holo.backing", BLEND_ALPHA, BACKING,
                        px(zone.u), px(zone.v), px(zone.u1()), px(zone.v1()));
            }
        }
        // 僅沿頂部空白邊緣掃描。沿用可凍結時鐘，不改任何真實讀數或字形亮度。
        double seconds = Float.isFinite(state.animSeconds) ? state.animSeconds : 0;
        double phase = seconds / 8.0D - Math.floor(seconds / 8.0D);
        for (int i = 0; i < HostFxGlassArPanel.ZONES.length; i++) {
            var zone = HostFxGlassArPanel.ZONES[i];
            float x0 = px(zone.u), x1 = px(zone.u1());
            float y0 = px(zone.v), y1 = px(zone.v1());
            float arm = 7;
            for (int corner = 0; corner < 4; corner++) {
                boolean right = (corner & 1) != 0;
                boolean bottom = (corner & 2) != 0;
                float x = right ? x1 - arm : x0;
                float y = bottom ? y1 - 1 : y0;
                rect(out, i, "holo.corner", BLEND_ADD, CORNER, x, y, x + arm, y + 1);
                x = right ? x1 - 1 : x0;
                y = bottom ? y1 - arm : y0;
                rect(out, i, "holo.corner", BLEND_ADD, CORNER, x, y, x + 1, y + arm);
            }
            rect(out, i, "holo.rail", BLEND_ADD, RAIL, x0 + arm, y0 + 1, x1 - arm, y0 + 2);
            float width = 12;
            float scan = x0 + arm + (float) phase * (x1 - x0 - 2 * arm - width);
            rect(out, i, "holo.scan", BLEND_ADD, ACCENT,
                    scan, y0 + 1, scan + width, y0 + 2);
        }
    }
}
