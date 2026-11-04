package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArModel.*;

/** 山海：模組表內容、完整分頁與欄內排版；不讀世界、不接觸 GPU。 */
final class HostFxGlassArModules {
    private HostFxGlassArModules() {}

    static void build(State st, List<Prim> prims, List<Txt> texts) {
        final int z = Z_MODLIST;
        final HostFxGlassArPanel.Zone zo = HostFxGlassArPanel.ZONE_MODLIST;
        final int installed = st.installedModules >= 0 ? st.installedModules : st.moduleCount;
        final int[] slots = HostFxGlassArSort.order(st.moduleSlots);
        final int total = slots == null ? 0 : slots.length;
        final int pages = HostFxGlassArTouch.modulePageCount(st);
        final int page = st.modulePages.page(st.modulePageTimeMillis, pages);
        final long remainingMillis = HostFxGlassArPanel.MODLIST_PAGE_MILLIS
                - Math.floorMod(st.modulePageTimeMillis, HostFxGlassArPanel.MODLIST_PAGE_MILLIS);
        final long remainingSeconds = (remainingMillis + 999L) / 1_000L;
        // ── 表头（一列一个 Txt，和数据行同一套 u 偏移） ─────────────────────────────
        final float yh = px(zo.v + HostFxGlassArPanel.MODLIST_HEAD_V0);
        final String[] head = {"槽", "模块", "进度", "耗能EU/t", "并行", "线程",
                pages > 1 ? "物品 · " + (page + 1) + "/" + pages + "页 · "
                        + (st.modulePages.locked() ? "已锁定" : remainingSeconds + "s换页") : "物品"};
        final float[] hu = {
                HostFxGlassArPanel.MOD_SLOT_U, HostFxGlassArPanel.MOD_NAME_U,
                HostFxGlassArPanel.MOD_BAR_U, HostFxGlassArPanel.MOD_EU_U,
                HostFxGlassArPanel.MOD_PAR_U, HostFxGlassArPanel.MOD_THR_U,
                HostFxGlassArPanel.MOD_OUT_U,
        };
        for (int c = 0; c < head.length; c++) {
            texts.add(new Txt(z, c, px(zo.u + hu[c]), yh, head[c], C_HEAD));
        }
        // 山海：兩段說明並排，留在表格底部安全邊界內。
        texts.add(new Txt(z, 102, px(zo.u + HostFxGlassArPanel.MOD_SLOT_U), px(HostFxGlassArPanel.MOD_NOTE_V),
                "物品按ID汇总；流体与NBT明细", C_DIM));
        texts.add(new Txt(z, 103, px(zo.u + HostFxGlassArPanel.MOD_NOTE_SECOND_U), px(HostFxGlassArPanel.MOD_NOTE_V),
                "以配方／输出仓为准", C_DIM));
        // ── 数据行 ─────────────────────────────────────────────────────────────────
        if (slots == null || slots.length == 0) {
            texts.add(new Txt(z, 100, px(zo.u + HostFxGlassArPanel.MOD_SLOT_U),
                    px(zo.v + HostFxGlassArPanel.MODLIST_ROW_V0),
                    installed == 0 ? "未安装模块" : installed < 0 ? "模块列表 --（等伺服同步）"
                            : "模块列表 --（槽位位图未到）", C_DIM));
            return;
        }
        final int start = page * HostFxGlassArPanel.MODLIST_ROWS;
        final int rows = Math.min(HostFxGlassArPanel.MODLIST_ROWS, total - start);
        for (int r = 0; r < rows; r++) {
            final int slot = slots[start + r];
            final float y = px(zo.v + HostFxGlassArPanel.MODLIST_ROW_V0
                    + r * HostFxGlassArPanel.MODLIST_ROW_DV);
            // 安靜的行分隔與狀態光，均不改動槽位真值。
            final int status = perModuleByte(st.modReason, slot);
            if (r % 2 == 0) {
                rect(prims, z, "mod.row", BLEND_ALPHA, 0x140C3044,
                        px(zo.u + HostFxGlassArPanel.ZONE_PAD), y - 1,
                        px(zo.u + zo.w - HostFxGlassArPanel.ZONE_PAD), y + 8);
            }
            rect(prims, z, "mod.status", BLEND_ADD,
                    status == REASON_WORKING ? HostFxGlassArHologram.ACCENT
                            : status >= REASON_NO_POWER ? reasonColor(status) : C_DIM,
                    px(zo.u + 0.025F), y + 1, px(zo.u + 0.025F) + 1, y + 6);
            // 槽位号
            texts.add(new Txt(z, 200 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_SLOT_U), y,
                    String.valueOf(slot), C_DIM));
            // 有字型量測時只按欄寬裁切；尚無字寬時沿用保守字數上限。
            final String name = moduleNameAt(st, slot);
            texts.add(new Txt(z, 201 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_NAME_U), y,
                    fitText(st, st.textWidth == null ? clip(name, HostFxGlassArPanel.MOD_NAME_CHARS) : name,
                            px(HostFxGlassArPanel.MOD_BAR_U - HostFxGlassArPanel.MOD_NAME_U)
                                    - HostFxGlassArPanel.MOD_LABEL_GAP_PX), C_MAIN));
            // 进度条（几何）+ 百分比
            final int pct = perModuleByte(st.modProgressPct, slot);
            bar(prims, z, "mod.bar_back", "mod.bar_fill",
                    px(zo.u + HostFxGlassArPanel.MOD_BAR_U), y + px(0.03F),
                    px(HostFxGlassArPanel.MOD_BAR_W), px(HostFxGlassArPanel.MOD_BAR_H),
                    pct, HostFxGlassArPanel.MOD_BAR_SEG, C_BAR_BACK, HostFxGlassArHologram.ACCENT);
            texts.add(new Txt(z, 202 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_PCT_U), y,
                    pctText(pct), pct < 0 ? C_DIM : C_MAIN));
            // 耗能
            final long eu = perModuleLong(st.modEuPerTick, slot);
            texts.add(new Txt(z, 203 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_EU_U), y,
                    euText(eu), eu < 0L ? C_DIM : C_MAIN));
            // 并行 / 线程（🔴 2026-10-25 第二十六改：用户「并行，线程，输出」要紧凑量级
            //   —— GT 的并行份数能到百万，原始 int 会把这一列顶成一片数字墙）
            final long par = perModuleLong(st.modParallel, slot);
            final int thr = perModuleInt(st.modThreads, slot);
            texts.add(new Txt(z, 204 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_PAR_U), y,
                    countOrDash(par), par < 0 ? C_DIM : C_MAIN));
            texts.add(new Txt(z, 205 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_THR_U), y,
                    threadCountOrDash(thr), thr < 0 ? C_DIM : C_MAIN));
            // 输出（最多 2 项）
            final String[] outs = perModuleOutputs(st.modOutputs, slot);
            int reason = perModuleByte(st.modReason, slot);
            String label = reason >= REASON_NO_POWER && st.moduleReasonLabels != null
                    && reason < st.moduleReasonLabels.length ? st.moduleReasonLabels[reason] : "";
            int labelWidth = label.isEmpty() ? 0 : reasonWidth(st, reason, label);
            float right = px(zo.u + zo.w - HostFxGlassArPanel.ZONE_PAD);
            float outputWidth = right - px(zo.u + HostFxGlassArPanel.MOD_OUT_U)
                    - (labelWidth == 0 ? 0 : labelWidth + HostFxGlassArPanel.MOD_LABEL_GAP_PX);
            String output = outputsText(outs, HostFxGlassArPanel.MOD_ROW_OUTPUTS);
            texts.add(new Txt(z, 206 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_OUT_U), y,
                    fitText(st, clip(output, Math.max(1, (int) (outputWidth / 9F))), outputWidth),
                    outs == null ? C_DIM : C_MAIN));
            if (!label.isEmpty()) {
                texts.add(new Txt(z, 207 + r * 10, right - labelWidth, y, label, reasonColor(reason)));
            }
        }
    }

    /** 排序只改顯示順序；名字仍從原始槽號對應的索引取出。 */
    private static String moduleNameAt(State st, int slot) {
        int r = -1;
        if (st.moduleSlots != null) {
            for (int i = 0; i < st.moduleSlots.length; i++) {
                if (st.moduleSlots[i] == slot) { r = i; break; }
            }
        }
        if (st.moduleNamesUnavailable || st.moduleNames == null || r < 0 || r >= st.moduleNames.length) {
            return "槽#" + slot;
        }
        final String n = st.moduleNames[r];
        return n == null || n.isEmpty() ? "槽#" + slot : n;
    }

    private static int reasonWidth(State st, int reason, String label) {
        if (st.moduleReasonWidths != null && reason < st.moduleReasonWidths.length
                && st.moduleReasonWidths[reason] > 0) return st.moduleReasonWidths[reason];
        return st.textWidth != null ? st.textWidth.applyAsInt(label) : label.length() * 9;
    }

    private static int reasonColor(int reason) {
        return switch (reason) {
            case REASON_NO_POWER -> 0xFFFF6666;
            case REASON_OUTPUT_FULL -> 0xFFFFAA44;
            case REASON_BLOCKED -> 0xFFFFDD55;
            default -> C_MAIN;
        };
    }

    static String fitText(State st, String text, float width) {
        if (st.textWidth == null || st.textWidth.applyAsInt(text) <= width) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0 && st.textWidth.applyAsInt(text.substring(0, end) + suffix) > width) end--;
        return end == 0 && st.textWidth.applyAsInt(suffix) > width ? "" : text.substring(0, end) + suffix;
    }

    /** 按扫描位下标取一个 int（数组短了 / 缺了 ⇒ −1）。 */
    private static int perModuleInt(int[] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? -1 : arr[slot];
    }

    /** 按扫描位下标取一个 long（数组短了 / 缺了 ⇒ −1）。 */
    private static long perModuleLong(long[] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? -1L : arr[slot];
    }

    /** 按扫描位下标取一个 byte（数组短了 / 缺了 ⇒ −1）。 */
    private static int perModuleByte(byte[] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? -1 : arr[slot];
    }

    /** 按扫描位下标取输出数组（缺了 ⇒ {@code null} = 读不到）。 */
    private static String[] perModuleOutputs(String[][] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? null : arr[slot];
    }

    /** 输出列表 → 一行文字（最多 {@code max} 项；多了补 {@code +N}；读不到 ⇒ {@code --}）。 */
    private static String outputsText(String[] outs, int max) {
        if (outs == null || outs.length == 0) {
            return "--";
        }
        final StringBuilder sb = new StringBuilder();
        final int n = Math.min(max, outs.length);
        for (int i = 0; i < n; i++) {
            if (outs[i] == null || outs[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(clip(outs[i], HostFxGlassArPanel.MOD_OUT_CHARS));
        }
        if (outs.length > n) {
            sb.append(" +").append(outs.length - n);
        }
        return sb.length() == 0 ? "--" : sb.toString();
    }

}

