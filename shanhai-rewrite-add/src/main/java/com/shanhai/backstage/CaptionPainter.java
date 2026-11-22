package com.shanhai.backstage;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;

/**
 * <b>标题带绘制（生产版，直接 JNA）</b>：照抄探针里已验证的类型化写法，不再在反射上造第二层封送。
 *
 * <h4>只画标题带</h4>
 * <p>{@link #paintCaption} 用 {@code GetWindowRect} + {@code SM_CYCAPTION/FRAME} 算标题带矩形，
 * 再用 {@code ExcludeClipRect} 把客户区挖掉；客户区一个像素都不碰。
 *
 * <h4>与探针同源</h4>
 * <p>真正落笔的是 {@link #paintCaptionOnDc}：探针在内存 DC 上调它、{@code GetDIBits} 读回像素验四条；
 * 生产在 {@code GetWindowDC(hwnd)} 上调它。
 *
 * <h4>依赖</h4>
 * <p>{@code com.sun.jna.*} 由 add 的 {@code compileOnly fileTree(dir: '../shanhai-rewrite/libs')} 提供，
 * 不会打进 jar；本类只被子进程 {@link BackstageDaemon} 引用，父进程链路不碰。
 */
public final class CaptionPainter {

    /* ── user32 ── */
    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);

        Pointer GetWindowDC(Pointer hwnd);

        int ReleaseDC(Pointer hwnd, Pointer hdc);

        boolean GetWindowRect(Pointer hwnd, WinDef.RECT rect);

        int FillRect(Pointer hdc, WinDef.RECT rect, Pointer brush);

        int SystemParametersInfoW(int action, int param, Pointer pvParam, int winIni);

        int GetSystemMetrics(int index);

        int GetSysColor(int index);

        Pointer GetForegroundWindow();
    }

    /* ── gdi32 ── */
    private interface Gdi32 extends Library {
        Gdi32 INSTANCE = Native.load("gdi32", Gdi32.class);

        Pointer CreateSolidBrush(int color);

        Pointer CreateFontIndirectW(Pointer logFont);

        Pointer SelectObject(Pointer hdc, Pointer object);

        int DeleteObject(Pointer object);

        int SetBkMode(Pointer hdc, int mode);

        int SetTextColor(Pointer hdc, int color);

        int SetTextAlign(Pointer hdc, int align);

        int ExtTextOutW(Pointer hdc, int x, int y, int options, Pointer rect,
                        Pointer text, int count, Pointer dx);

        int ExcludeClipRect(Pointer hdc, int left, int top, int right, int bottom);
    }

    private static final int SM_CYCAPTION = 4;
    private static final int SM_CXSIZEFRAME = 32;
    private static final int SM_CYSIZEFRAME = 33;
    private static final int SM_CXSIZE = 30;
    private static final int SM_CXPADDEDBORDER = 92;
    private static final int COLOR_ACTIVECAPTION = 2;
    private static final int COLOR_INACTIVECAPTION = 3;
    private static final int COLOR_CAPTIONTEXT = 9;
    private static final int SPI_GETNONCLIENTMETRICS = 0x0029;
    private static final int NONCLIENTMETRICS_SIZE = 504;
    private static final int LF_CAPTION_FONT_OFFSET = 24;
    private static final int LOGFONT_SIZE = 92;
    private static final int BKMODE_TRANSPARENT = 1;
    private static final int TA_CENTER = 2;
    private static final int TA_TOP = 0;

    /** 内存 DC 自证通过后才允许画线程真的跑（2026-11-21：ProdPaintProbe 四条 PASS）。 */
    public static final boolean VERIFIED = true;

    private static volatile String failureReason = "not_attempted";

    private long font;
    private int fontHeight;
    private volatile long lastPaintMs;
    private volatile String lastFailure = "";

    private CaptionPainter() {
    }

    public static CaptionPainter tryCreate() {
        try {
            if (User32.INSTANCE == null || Gdi32.INSTANCE == null) {
                failureReason = "native_null";
                return null;
            }
            failureReason = "";
            return new CaptionPainter();
        } catch (Throwable t) {
            failureReason = t.getClass().getSimpleName() + ":" + t.getMessage();
            return null;
        }
    }

    public static String failureReason() {
        return failureReason;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public long lastPaintMs() {
        return lastPaintMs;
    }

    // ─────────────────────────── 生产入口 ───────────────────────────

    public boolean paintCaption(long hwnd, String text) {
        final long t0 = System.nanoTime();
        Pointer dc = null;
        try {
            final Pointer window = new Pointer(hwnd);
            final WinDef.RECT rect = new WinDef.RECT();
            if (!User32.INSTANCE.GetWindowRect(window, rect)) {
                lastFailure = "rect";
                return false;
            }
            final int captionH = User32.INSTANCE.GetSystemMetrics(SM_CYCAPTION);
            final int frameX = User32.INSTANCE.GetSystemMetrics(SM_CXSIZEFRAME)
                    + User32.INSTANCE.GetSystemMetrics(SM_CXPADDEDBORDER);
            final int frameY = User32.INSTANCE.GetSystemMetrics(SM_CYSIZEFRAME)
                    + User32.INSTANCE.GetSystemMetrics(SM_CXPADDEDBORDER);
            final int button = User32.INSTANCE.GetSystemMetrics(SM_CXSIZE);
            final int x = rect.left + frameX + 4;
            final int y = rect.top + frameY;
            final int w = Math.max(40, rect.right - rect.left - 2 * frameX - 3 * button - 8);
            final int h = Math.max(12, captionH);

            dc = User32.INSTANCE.GetWindowDC(window);
            if (dc == null) {
                lastFailure = "dc";
                return false;
            }
            final int clientTop = rect.top + frameY + captionH;
            if (clientTop < rect.bottom) {
                Gdi32.INSTANCE.ExcludeClipRect(dc, rect.left + frameX, clientTop,
                        rect.right - frameX, rect.bottom - frameY);
            }
            final Pointer foreground = User32.INSTANCE.GetForegroundWindow();
            final boolean focused = foreground != null && Pointer.nativeValue(foreground) == hwnd;
            final int bg = User32.INSTANCE.GetSysColor(focused ? COLOR_ACTIVECAPTION : COLOR_INACTIVECAPTION);
            final int fg = User32.INSTANCE.GetSysColor(COLOR_CAPTIONTEXT);
            return paintCaptionOnDc(Pointer.nativeValue(dc), x, y, w, h, text, bg, fg);
        } catch (Throwable t) {
            lastFailure = "ex:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            return false;
        } finally {
            if (dc != null) {
                try {
                    User32.INSTANCE.ReleaseDC(new Pointer(hwnd), dc);
                } catch (Throwable ignored) {
                    // 释放失败只影响下一次
                }
            }
            lastPaintMs = (System.nanoTime() - t0) / 1_000_000L;
        }
    }

    /** 真正落笔：任意 HDC 上画一行标题（生产 = GetWindowDC；探针 = 内存 DC）。 */
    public boolean paintCaptionOnDc(long hdc, int x, int y, int w, int h,
                                    String text, int bgColor, int fgColor) {
        try {
            final Pointer dc = new Pointer(hdc);
            final WinDef.RECT rect = new WinDef.RECT();
            rect.left = x;
            rect.top = y;
            rect.right = x + w;
            rect.bottom = y + h;
            rect.write();

            final Pointer brush = Gdi32.INSTANCE.CreateSolidBrush(bgColor);
            if (brush == null) {
                lastFailure = "brush";
                return false;
            }
            try {
                if (User32.INSTANCE.FillRect(dc, rect, brush) == 0) {
                    lastFailure = "fill";
                    return false;
                }
            } finally {
                Gdi32.INSTANCE.DeleteObject(brush);
            }

            final long f = ensureFont();
            if (f == 0L) {
                if (lastFailure.isEmpty()) {
                    lastFailure = "font";
                }
                return false;
            }
            final Pointer font = new Pointer(f);
            final Pointer old = Gdi32.INSTANCE.SelectObject(dc, font);
            try {
                Gdi32.INSTANCE.SetBkMode(dc, BKMODE_TRANSPARENT);
                Gdi32.INSTANCE.SetTextColor(dc, fgColor);
                Gdi32.INSTANCE.SetTextAlign(dc, TA_CENTER | TA_TOP);
                final String s = text == null ? "" : text;
                final Memory buffer = new Memory(((long) s.length() + 2L) * 2L);
                buffer.setWideString(0L, s);
                final int centerX = x + w / 2;
                final int textY = y + Math.max(0, (h - Math.abs(fontHeight)) / 2);
                if (Gdi32.INSTANCE.ExtTextOutW(dc, centerX, textY, 0, Pointer.NULL,
                        buffer, s.length(), Pointer.NULL) == 0) {
                    lastFailure = "exttextout";
                    return false;
                }
            } finally {
                if (old != null) {
                    Gdi32.INSTANCE.SelectObject(dc, old);
                }
            }
            return true;
        } catch (Throwable t) {
            lastFailure = "ex:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            return false;
        }
    }

    // ─────────────────────────── 字体 / 工具 ───────────────────────────

    /** 取 lfCaptionFont 建字体；标题高变化（DPI 变化）时重建。 */
    private long ensureFont() {
        final int captionH = User32.INSTANCE.GetSystemMetrics(SM_CYCAPTION);
        if (font != 0L && captionH == fontHeight) {
            return font;
        }
        try {
            final Memory ncm = new Memory(NONCLIENTMETRICS_SIZE);
            ncm.setInt(0L, NONCLIENTMETRICS_SIZE);
            int ok = User32.INSTANCE.SystemParametersInfoW(SPI_GETNONCLIENTMETRICS,
                    NONCLIENTMETRICS_SIZE, ncm, 0);
            if (ok == 0) {
                // 旧版 Windows 的 cbSize 不含 iPaddedBorderWidth（500）—— 两种都试
                final int legacy = NONCLIENTMETRICS_SIZE - 4;
                ncm.setInt(0L, legacy);
                ok = User32.INSTANCE.SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, legacy, ncm, 0);
                if (ok == 0) {
                    lastFailure = "spi";
                    return 0L;
                }
            }
            final byte[] lf = ncm.getByteArray(LF_CAPTION_FONT_OFFSET, LOGFONT_SIZE);
            final Memory logFont = new Memory(LOGFONT_SIZE);
            logFont.write(0L, lf, 0, LOGFONT_SIZE);
            final Pointer created = Gdi32.INSTANCE.CreateFontIndirectW(logFont);
            if (created == null) {
                lastFailure = "create";
                return 0L;
            }
            if (font != 0L) {
                Gdi32.INSTANCE.DeleteObject(new Pointer(font));
            }
            font = Pointer.nativeValue(created);
            fontHeight = captionH;
            return font;
        } catch (Throwable t) {
            lastFailure = "font_ex:" + t.getClass().getSimpleName() + ":" + t.getMessage();
            return 0L;
        }
    }
}
