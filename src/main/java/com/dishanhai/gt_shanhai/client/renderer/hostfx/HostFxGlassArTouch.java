package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArModel.*;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArPanel.*;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArPanel.px;

/** 山海：AR 分頁狀態、觸控座標與按鈕圖元；不依賴世界或輸入裝置。 */
final class HostFxGlassArTouch {
    private HostFxGlassArTouch() {}

    static final class Press {
        private boolean held;
        boolean begin() {
            if (held) return false;
            held = true;
            return true;
        }
        void release() { held = false; }
    }

    static final class Pages {
        private boolean locked;
        private int selected;
        private long offset;

        boolean locked() { return locked; }

        int page(long now, int count) {
            count = Math.max(1, count);
            if (locked) return selected = Math.min(selected, count - 1);
            return (int) Math.floorMod(Math.floorDiv(now, MODLIST_PAGE_MILLIS) + offset, (long) count);
        }

        void toggle(long now, int count) {
            selected = page(now, count);
            locked = !locked;
            if (!locked) offset = selected - Math.floorDiv(now, MODLIST_PAGE_MILLIS);
        }

        void step(int delta, long now, int count) {
            selected = Math.floorMod(page(now, count) + delta, Math.max(1, count));
            locked = true;
        }
    }

    record Hit(double distance, float u, float v) {}

    /** 射線為單位向量；反面沿相反牆邊起算，與字形矩陣一致。 */
    static Hit raycast(double x, double y, double z, double dx, double dy, double dz, double reach) {
        if (!Double.isFinite(x + y + z + dx + dy + dz + reach) || Math.abs(dx) < 1.0e-7) return null;
        int face = visibleTextFace((float) x);
        double distance = (textPlaneX(face) - x) / dx;
        if (distance < 0 || distance > reach) return null;
        float u = (float) (face == 0 ? WALL_Z1 - z - dz * distance : z + dz * distance - WALL_Z0);
        float v = (float) (WALL_Y1 - y - dy * distance);
        return u >= 0 && u <= WALL_W && v >= 0 && v <= WALL_H ? new Hit(distance, u, v) : null;
    }

    static Zone button(int section, int action) {
        float u = section == 0 ? ZONE_PHYS.u : ZONE_MODLIST.u;
        return new Zone("AR touch", u + action * 1.8F, 3.66F, 1.70F, 0.28F);
    }

    static int buttonAt(float u, float v) {
        for (int section = 0; section < 2; section++) {
            for (int action = 0; action < 3; action++) {
                if (button(section, action).contains(u, v, 0)) return section * 3 + action;
            }
        }
        return -1;
    }

    static int sectionAt(float u, float v) {
        int button = buttonAt(u, v);
        if (button >= 0) return button / 3;
        if (ZONE_MODLIST.contains(u, v, 0)) return 1;
        if (ZONE_PHYS.contains(u, v, 0) || ZONE_FIG_TOP.contains(u, v, 0)
                || ZONE_FIG_SIDE.contains(u, v, 0) || ZONE_FIG_HOLE.contains(u, v, 0)) return 0;
        return -1;
    }

    static int modulePageCount(State state) {
        return Math.max(1, ((state.moduleSlots == null ? 0 : state.moduleSlots.length) + MODLIST_ROWS - 1) / MODLIST_ROWS);
    }

    static void build(State state, List<Prim> prims, List<Txt> texts) {
        for (int section = 0; section < 2; section++) {
            Pages pages = section == 0 ? state.infoPages : state.modulePages;
            int zone = section == 0 ? Z_PHYS : Z_MODLIST;
            for (int action = 0; action < 3; action++) {
                Zone button = button(section, action);
                int index = section * 3 + action;
                rect(prims, zone, "touch.button", BLEND_ALPHA,
                        state.hoverButton == index ? 0x80417486 : 0x40214656,
                        px(button.u), px(button.v), px(button.u1()), px(button.v1()));
                String label = switch (action) {
                    case 0 -> "< 上一页";
                    case 1 -> pages.locked() ? "已锁定 · 解锁" : "自动 · 锁定";
                    default -> "下一页 >";
                };
                texts.add(new Txt(zone, 150 + action, px(button.u + 0.06F), px(button.v + 0.05F),
                        label, pages.locked() && action == 1 ? C_WARN : C_HEAD));
            }
        }
    }
}
