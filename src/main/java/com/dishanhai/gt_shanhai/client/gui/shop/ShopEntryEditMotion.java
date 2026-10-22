package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiMotion;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Row;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 商品编辑器的动效状态（山海署名，纯 Java，可单测）：开场缩放、切页横移、页签下划线滑动、整页平滑滚动、
 * 浮层弹簧开合、槽位弹入 / 删除后补位、保存失败那一行闪红、新冒出来的行淡入、空占位格的呼吸提示。
 *
 * <p>时间一律传 {@code System.currentTimeMillis()}。编辑器每帧先 {@link #setReduceMotion}；
 * 减少动效时全部直接到位（不平移、不缩放、不闪），只留必要的静态高亮。</p>
 */
final class ShopEntryEditMotion {

    static final long SLOT_POP_MS = 260L;
    static final long SLOT_SHIFT_MS = GuiRenderUtil.MOTION_MS;
    static final long FLASH_MS = 1_200L;
    static final long REVEAL_MS = 200L;
    private static final float VISIBLE_EPS = 0.01f;
    private static final float POP_FROM = 0.5f;
    private static final int FLASH_PULSES = 3;
    private static final long BREATH_PERIOD_MS = 1_400L;

    private boolean reduce;

    private final Spring open = Spring.opening(Spring.PANEL);

    private int slideDir;
    private long slideAtMs;

    private final Spring barX = new Spring(Spring.INDICATOR);
    private final Spring barW = new Spring(Spring.INDICATOR);
    private boolean barValid;

    private final Spring scroll = new Spring(Spring.SCROLL);

    /** 四个浮层各一根弹簧：放弃确认框、描述大框、精确数量框、分类下拉。 */
    final Spring discard = new Spring(Spring.PANEL);
    final Spring desc = new Spring(Spring.PANEL);
    final Spring qty = new Spring(Spring.PANEL);
    final Spring cat = new Spring(Spring.PANEL);

    private record Pop(int row, int from, int count, long atMs) {}
    private record Shift(int row, int from, long atMs) {}
    private final List<Pop> pops = new ArrayList<>();
    private final List<Shift> shifts = new ArrayList<>();

    private Row flashRow;
    private long flashAtMs;

    private final Map<Row, Long> revealAt = new EnumMap<>(Row.class);

    void setReduceMotion(boolean reduce) {
        this.reduce = reduce;
    }

    /** 每帧：清掉播完的弹入、补位记录。 */
    void tick(long now) {
        pops.removeIf(p -> now - p.atMs() >= SLOT_POP_MS);
        shifts.removeIf(s -> now - s.atMs() >= SLOT_SHIFT_MS);
    }

    // ===== 开场 =====

    /** 整个面板（连同输入框）的开场缩放 0.94→1；只在打开编辑器时播一次，从选择器回来不重播。 */
    float openScale(long now) {
        if (reduce) {
            open.snap(1f);
            return 1f;
        }
        open.update(now);
        return GuiMotion.panelScale(open.value());
    }

    // ===== 切页 =====

    void tabChanged(int fromOrdinal, int toOrdinal, long now) {
        slideDir = toOrdinal > fromOrdinal ? 1 : -1;
        slideAtMs = now;
    }

    /** 页内容（含输入框）的横向偏移：往右边的页切从右边滑进来，往左边切从左边。 */
    float slideX(long now) {
        if (reduce || slideAtMs <= 0L) return 0f;
        float t = GuiMotion.clamp01((now - slideAtMs) / (float) GuiRenderUtil.MOTION_MS);
        return GuiRenderUtil.slideOffsetX(slideDir, t);
    }

    /** 当前页签下划线的 {x, 宽}：第一次直接到位，之后换页签时滑过去。 */
    float[] underline(float tx, float tw, long now) {
        if (!barValid || reduce) {
            barX.snap(tx);
            barW.snap(tw);
            barValid = true;
            return new float[]{tx, tw};
        }
        if (barX.target() != tx) barX.setTarget(tx, now);
        if (barW.target() != tw) barW.setTarget(tw, now);
        barX.update(now);
        barW.update(now);
        return new float[]{barX.value(), barW.value()};
    }

    /** 改窗口大小后页签位置全变了：下一次直接到位，不从旧位置滑过来。 */
    void resetUnderline() {
        barValid = false;
    }

    // ===== 整页滚动 =====

    /** 显示用的滚动值：追着 {@code target} 走；画、点、放输入框都按这个值，所见即所点。 */
    int scroll(int target, long now) {
        if (reduce) {
            scroll.snap(target);
            return target;
        }
        if (scroll.target() != target) scroll.setTarget(target, now);
        scroll.update(now);
        return Math.round(scroll.value());
    }

    /** 拖滚动条、切页：直接到位。 */
    void snapScroll(int value) {
        scroll.snap(value);
    }

    // ===== 浮层 =====

    /** 浮层弹簧跟着开关走，返回本帧的开合值（0 收起，1 全开）；开和关走同一条曲线。 */
    float panel(Spring s, boolean open, long now) {
        float target = open ? 1f : 0f;
        if (reduce) {
            s.snap(target);
            return target;
        }
        if (s.target() != target) s.setTarget(target, now);
        s.update(now);
        return s.value();
    }

    static boolean visible(float p) {
        return p > VISIBLE_EPS;
    }

    // ===== 槽位 =====

    /** 第 {@code row} 排从 {@code from} 起的 {@code count} 格刚放进来：从小弹出来。 */
    void popSlots(int row, int from, int count, long now) {
        if (count <= 0 || from < 0) return;
        pops.add(new Pop(row, from, count, now));
    }

    /** 格子的弹入缩放：0.5 起，落位前略微胀出一点再回弹（easeOutBack）。 */
    float slotScale(int row, int idx, long now) {
        if (reduce) return 1f;
        for (int i = pops.size() - 1; i >= 0; i--) {
            Pop p = pops.get(i);
            if (p.row() != row || idx < p.from() || idx >= p.from() + p.count()) continue;
            float t = GuiMotion.clamp01((now - p.atMs()) / (float) SLOT_POP_MS);
            return t >= 1f ? 1f : POP_FROM + (1f - POP_FROM) * easeOutBack(t);
        }
        return 1f;
    }

    /** 第 {@code row} 排第 {@code idx} 格刚被删：后面的格子从原位置往左补上来。 */
    void slotRemoved(int row, int idx, long now) {
        if (idx < 0) return;
        shifts.add(new Shift(row, idx, now));
    }

    float slotShiftX(int row, int idx, long now, int pitch) {
        if (reduce) return 0f;
        for (int i = shifts.size() - 1; i >= 0; i--) {
            Shift s = shifts.get(i);
            if (s.row() != row || idx < s.from()) continue;
            float t = GuiMotion.clamp01((now - s.atMs()) / (float) SLOT_SHIFT_MS);
            return t >= 1f ? 0f : pitch * (1f - GuiRenderUtil.easeOut(t));
        }
        return 0f;
    }

    // ===== 保存失败的那一行 =====

    void startFlash(Row row, long now) {
        flashRow = row;
        flashAtMs = now;
    }

    /** 出错那一行的红色强度 0..1：一明一暗闪几下后淡掉；减少动效时不闪，平平地淡掉。 */
    float flash(Row row, long now) {
        if (row != flashRow || flashAtMs <= 0L) return 0f;
        float t = GuiMotion.clamp01((now - flashAtMs) / (float) FLASH_MS);
        if (t >= 1f) return 0f;
        float fade = 1f - t;
        if (reduce) return fade;
        float pulse = 0.5f + 0.5f * (float) Math.cos(2.0 * Math.PI * FLASH_PULSES * t);
        return fade * (0.35f + 0.65f * pulse);
    }

    // ===== 新冒出来的行 =====

    void startReveal(Collection<Row> rows, long now) {
        for (Row r : rows) revealAt.put(r, now);
    }

    /** 行的淡入进度 0..1；没登记的行一直是 1。 */
    float reveal(Row row, long now) {
        Long at = revealAt.get(row);
        if (at == null) return 1f;
        if (reduce) {
            revealAt.remove(row);
            return 1f;
        }
        float t = GuiMotion.clamp01((now - at) / (float) REVEAL_MS);
        if (t >= 1f) {
            revealAt.remove(row);
            return 1f;
        }
        return GuiRenderUtil.easeOut(t);
    }

    // ===== 空占位格 =====

    /** 空占位格「?」的亮度 0..1，缓慢一呼一吸；减少动效时常亮。 */
    float breath(long now) {
        if (reduce) return 1f;
        double phase = (now % BREATH_PERIOD_MS) / (double) BREATH_PERIOD_MS;
        return (float) (0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase));
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}
