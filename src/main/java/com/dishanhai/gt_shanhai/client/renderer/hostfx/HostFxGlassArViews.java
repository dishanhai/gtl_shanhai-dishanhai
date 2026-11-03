package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArModel.*;

/** 山海：四頁結構投影。使用環／艦／解壓機的共用尺寸，圖形均為示意而非世界重建。 */
final class HostFxGlassArViews {
    private HostFxGlassArViews() {}

    static void build(State st, float ring, float up, float down, List<Prim> out, List<Txt> texts) {
        switch (HostFxGlassArTelemetry.page(st)) {
            case 0 -> {
                overview(st, ring, up, down, out, texts);
                buildSide(st, ring, up, down, out, texts);
                buildHole(st, out);
            }
            case 1 -> {
                buildTop(st, ring, up, down, out, texts);
                cutaway(st, out, texts);
                field(st, out, texts);
            }
            case 2 -> {
                decompressor(st, out, texts);
                fleet(st, up, down, out, texts);
                field(st, out, texts);
            }
            default -> {
                topology(st, out, texts);
                mounts(st, out, texts);
                field(st, out, texts);
            }
        }
    }

    private static void overview(State st, float ring, float up, float down, List<Prim> out, List<Txt> texts) {
        var p = new Plot(Z_TOP, out);
        caption(st, Z_TOP, "轴测总览 · 1/4", texts);
        p.ring("view.overview.ring", INNER_R, 0, C_HEAD);
        p.ring("view.overview.orbit", SHIP_R, 0, 0x4879A9BD);
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(MD_AZ_DEG[i] + ring);
            double x = Math.cos(a), z = Math.sin(a);
            p.body("view.overview.decompressor", x * MD_R, 0, z * MD_R, a, MD_SIZE_RAD / 2, MD_SIZE_TAN / 2, C_MAIN);
            if (i < st.mdPillarCount) p.segment("top.md_pillar", C_HEAD,
                    x * MD_NOZZLE_R, 0, z * MD_NOZZLE_R, x * st.holeRadiusBlocks, 0, z * st.holeRadiusBlocks);
            for (int j = 0; j < 3 && i * 3 + j < st.thinBeamCount; j++) {
                double offset = (j - 1) * THIN_RING_R;
                p.segment("top.thin", 0xC49F8EFF, x * MD_NOZZLE_R - z * offset, 0, z * MD_NOZZLE_R + x * offset,
                        x * st.holeRadiusBlocks - z * offset, 0, z * st.holeRadiusBlocks + x * offset);
            }
        }
        ships(p, up, 3, "view.overview.ship_up", C_MAIN);
        ships(p, down, -3, "view.overview.ship_down", 0xFFD8B78A);
        p.segment("view.overview.jet", st.fxOn ? C_HEAD : 0xFFFF6655,
                0, -JET_LEN, 0, 0, JET_LEN, 0);
        p.core("view.overview.core", st.holeRadiusBlocks);
    }

    private static void cutaway(State st, List<Prim> out, List<Txt> texts) {
        var zone = HostFxGlassArPanel.ZONE_FIG_SIDE;
        float x = px(zone.u), y = px(zone.v), w = px(zone.w), h = px(zone.h);
        caption(st, Z_SIDE, "轴向剖面 · 2/4", texts);
        float cy = y + h * 0.62F, cx = x + w / 2;
        rect(out, Z_SIDE, "view.cutaway.ring", BLEND_ALPHA, 0x885CA9BD, x + 8, cy - 2, x + w - 8, cy + 2);
        rect(out, Z_SIDE, "view.cutaway.jet", BLEND_ADD, st.fxOn ? C_HEAD : 0xFFFF6655,
                cx - 2, y + 13, cx + 2, y + h - 3);
        for (int sign : new int[]{-1, 1}) {
            float sx = cx + sign * w * 0.36F;
            rect(out, Z_SIDE, "view.cutaway.tower", BLEND_ALPHA, C_DIM, sx - 5, cy - 5, sx + 5, cy + 5);
            if (st.mdPillarCount > 0) line(out, Z_SIDE, "view.cutaway.beam", BLEND_ADD, C_HEAD, sx, cy, cx, cy, 1);
        }
        disc(out, Z_SIDE, "view.cutaway.shadow", BLEND_ALPHA, 0xFF02060C, cx, cy, 6, 16);
        annulus(out, Z_SIDE, "view.cutaway.photon", BLEND_ADD, 0xFFD8B78A, cx, cy, 6, 7, 20);
    }

    private static void decompressor(State st, List<Prim> out, List<Txt> texts) {
        var zone = HostFxGlassArPanel.ZONE_FIG_TOP;
        float x = px(zone.u), y = px(zone.v), w = px(zone.w), cy = y + px(zone.h) * 0.64F;
        caption(st, Z_TOP, "解压机 · 主柱＋三细束 · 3/4", texts);
        rect(out, Z_TOP, "view.decompressor.body", BLEND_ALPHA, C_DIM, x + 8, cy - 8, x + 30, cy + 8);
        rect(out, Z_TOP, "view.decompressor.nozzle", BLEND_ADD, C_HEAD, x + 30, cy - 5, x + 39, cy + 5);
        if (st.mdPillarCount > 0) rect(out, Z_TOP, "view.decompressor.main", BLEND_ADD, 0xA08DEAFF,
                x + 39, cy - 2, x + w - 20, cy + 2);
        for (int i = -1; i <= 1; i++) line(out, Z_TOP, "view.decompressor.thin", BLEND_ADD, 0xCCA58AFF,
                x + 34, cy + i * 7, x + w - 20, cy + i * 4, 1);
        disc(out, Z_TOP, "view.decompressor.target", BLEND_ALPHA, 0xFF02060C, x + w - 15, cy, 7, 16);
        annulus(out, Z_TOP, "view.decompressor.target_ring", BLEND_ADD, C_HEAD, x + w - 15, cy, 7, 8, 16);
    }

    private static void fleet(State st, float up, float down, List<Prim> out, List<Txt> texts) {
        var plot = new Plot(Z_SIDE, out);
        caption(st, Z_SIDE, "环舰 · 上四舰／下四舰", texts);
        plot.ring("view.fleet.orbit_up", SHIP_R, 4, C_HEAD);
        plot.ring("view.fleet.orbit_down", SHIP_R, -4, 0x88D8B78A);
        ships(plot, up, 4, "view.fleet.ship_up", C_MAIN);
        ships(plot, down, -4, "view.fleet.ship_down", 0xFFD8B78A);
    }

    private static void ships(Plot plot, float angle, double y, String tag, int color) {
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(angle + 90.0 * i);
            plot.body(tag, Math.cos(a) * SHIP_R, y, Math.sin(a) * SHIP_R,
                    a, SHIP_RAD_HALF, SHIP_TAN_HALF, color);
        }
    }

    private static void field(State st, List<Prim> out, List<Txt> texts) {
        var zone = HostFxGlassArPanel.ZONE_FIG_HOLE;
        float cx = px(zone.u + zone.w / 2), cy = px(zone.v) + 30;
        caption(st, Z_HOLE, "遏制场 · " + HostFxGlassArTelemetry.field(st.status), texts);
        disc(out, Z_HOLE, "view.field.core", BLEND_ALPHA, 0xFF02060C, cx, cy, 9, 20);
        annulus(out, Z_HOLE, "view.field.photon", BLEND_ADD, 0xFFD8B78A, cx, cy, 9, 10, 24);
        if (st.status.containment() > 0 || st.status.lockSeconds() > 0) {
            int color = st.status.containment() == 2 || st.status.lockSeconds() > 0 ? C_WARN : C_HEAD;
            annulus(out, Z_HOLE, "view.field.shield", BLEND_ADD, color, cx, cy, 14, 15, 32);
            float phase = Float.isFinite(st.animSeconds) ? st.animSeconds * 0.4F : 0;
            for (int i = 0; i < 4; i++) {
                float a = phase + i * (float) Math.PI / 2;
                float x = cx + 15 * (float) Math.cos(a), y = cy + 15 * (float) Math.sin(a);
                rect(out, Z_HOLE, "view.field.node", BLEND_ADD, color, x - 1, y - 1, x + 1, y + 1);
            }
        }
    }

    private static void topology(State st, List<Prim> out, List<Txt> texts) {
        var zone = HostFxGlassArPanel.ZONE_FIG_TOP;
        float x = px(zone.u), y = px(zone.v), w = px(zone.w);
        caption(st, Z_TOP, "连接拓扑 · 4/4", texts);
        float cy = y + 28, center = x + w / 2;
        int[] links = {st.status.engineLink(), st.status.echoLink()};
        String[] names = {"终焉", "归墟", "回响"};
        for (int i = 0; i < 2; i++) {
            float target = x + (i == 0 ? 20 : w - 20);
            line(out, Z_TOP, links[i] == 2 ? "view.topology.link_ready" : "view.topology.link_waiting",
                    BLEND_ADD, links[i] == 2 ? C_HEAD : 0x4779A9BD, center, cy, target, cy, 1);
        }
        for (int i = 0; i < 3; i++) {
            float cx = i == 0 ? x + 20 : i == 1 ? center : x + w - 20;
            rect(out, Z_TOP, "view.topology.node", BLEND_ALPHA, 0xA00C2634, cx - 15, cy - 7, cx + 15, cy + 7);
            texts.add(new Txt(Z_TOP, 10 + i, cx - 9, cy - 4, names[i], i == 1 ? C_HEAD : C_MAIN));
        }
    }

    private static void mounts(State st, List<Prim> out, List<Txt> texts) {
        var zone = HostFxGlassArPanel.ZONE_FIG_SIDE;
        float x = px(zone.u), y = px(zone.v), w = px(zone.w);
        caption(st, Z_SIDE, "模组仓位 · 本机24＋远端16＋回响1", texts);
        if (!HostFxGlassArTelemetry.hasModuleSnapshot(st)) {
            rect(out, Z_SIDE, "view.mount.unknown", BLEND_ALPHA, 0x302F5368,
                    x + 6, y + 18, x + w - 6, y + 36);
            texts.add(new Txt(Z_SIDE, 10, x + 10, y + 22, "仓位 --（等待同步）", C_DIM));
            return;
        }
        for (int slot = 0; slot < 41; slot++) {
            int row = slot < 24 ? slot / 12 : slot < 40 ? 2 : 3;
            int column = slot < 24 ? slot % 12 : slot < 40 ? slot - 24 : 0;
            float cell = (w - 12) / 16;
            boolean installed = false;
            if (st.moduleSlots != null) for (int actual : st.moduleSlots) if (actual == slot) { installed = true; break; }
            rect(out, Z_SIDE, installed ? "view.mount.installed" : "view.mount.empty", BLEND_ALPHA,
                    installed ? 0xBB57CBEA : 0x44608494,
                    x + 6 + column * cell, y + 13 + row * 8, x + 6 + column * cell + 6, y + 19 + row * 8);
        }
    }

    private static void caption(State st, int zoneId, String label, List<Txt> texts) {
        var zone = HostFxGlassArPanel.ZONES[zoneId];
        texts.add(new Txt(zoneId, 0, px(zone.u) + 4, px(zone.v) + 3,
                HostFxGlassArModules.fitText(st, label, px(zone.w) - 8), C_HEAD));
    }

    private static final class Plot {
        private final int zone;
        private final List<Prim> out;
        private final float cx, cy, scale;

        Plot(int zone, List<Prim> out) {
            this.zone = zone;
            this.out = out;
            var frame = HostFxGlassArPanel.ZONES[zone];
            cx = px(frame.u + frame.w / 2);
            cy = px(frame.v) + 29;
            scale = px(frame.w) * 0.43F / (float) VIEW_R;
        }

        private float[] project(double x, double y, double z) {
            return new float[]{cx + (float) ((x - z) * 0.7071 * scale),
                    cy + (float) ((x + z) * 0.17 * scale - y * 0.65 * scale)};
        }

        void segment(String tag, int color, double x0, double y0, double z0, double x1, double y1, double z1) {
            float[] a = project(x0, y0, z0), b = project(x1, y1, z1);
            line(out, zone, tag, BLEND_ADD, color, a[0], a[1], b[0], b[1], 1);
        }

        void ring(String tag, double radius, double y, int color) {
            for (int i = 0; i < 32; i++) {
                double a = Math.PI * 2 * i / 32, b = Math.PI * 2 * (i + 1) / 32;
                segment(tag, color, Math.cos(a) * radius, y, Math.sin(a) * radius,
                        Math.cos(b) * radius, y, Math.sin(b) * radius);
            }
        }

        void body(String tag, double x, double y, double z, double angle, double radial, double tangential, int color) {
            double[] u = {-radial, radial, radial, -radial};
            double[] v = {-tangential, -tangential, tangential, tangential};
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                segment(tag, color, x + Math.cos(angle) * u[i] - Math.sin(angle) * v[i], y,
                        z + Math.sin(angle) * u[i] + Math.cos(angle) * v[i],
                        x + Math.cos(angle) * u[j] - Math.sin(angle) * v[j], y,
                        z + Math.sin(angle) * u[j] + Math.cos(angle) * v[j]);
            }
        }

        void core(String tag, float radius) {
            disc(out, zone, tag, BLEND_ALPHA, 0xFF02060C, cx, cy, Math.max(2, radius * scale), 20);
            annulus(out, zone, tag, BLEND_ADD, 0xFFD8B78A, cx, cy,
                    Math.max(2, radius * scale), Math.max(2, radius * scale) + 1, 24);
        }
    }
}
