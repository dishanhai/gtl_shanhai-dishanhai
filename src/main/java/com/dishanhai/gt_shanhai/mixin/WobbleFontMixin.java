package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.DShanhaiStyleRegistry;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.TextFormatParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = Font.class, remap = false)
public class WobbleFontMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai.wobble");
    private static final ThreadLocal<Boolean> RENDERING_CHAR = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> CALC_WIDTH = ThreadLocal.withInitial(() -> false);

    /**
     * FCS 宽度修正：& 码文本返回清理后宽度，让 tooltip 居中/布局用正确数值。
     * Font.width(FormattedCharSequence) = m_92724_
     */
    @Inject(method = "m_92724_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void onWidthFCS(FormattedCharSequence text, CallbackInfoReturnable<Integer> cir) {
        if (CALC_WIDTH.get()) return;
        try {
            StringBuilder sb = new StringBuilder();
            text.accept((i, s, cp) -> { sb.append((char) cp); return true; });
            String raw = sb.toString();
            if (raw.isEmpty() || !TextFormatParser.containsSpecialFormatting(raw)) return;

            TextFormatParser.ParseResult pr = TextFormatParser.parseFormatting(raw);
            if (pr.cleanText.isEmpty()) return;

            Font self = (Font) (Object) this;
            CALC_WIDTH.set(true);
            try {
                int w = self.width(pr.cleanText);
                if (w > 0) {
                    cir.setReturnValue(w);
                }
            } finally {
                CALC_WIDTH.set(false);
            }
        } catch (Exception e) {
            LOG.error("[Wobble] widthFCS error: {}", e.getMessage());
        }
    }

    /**
     * 宽度修正：Font.width(FormattedText) = m_92852_
     * 关键路径font.width(Component) → Component 继承 FormattedText → 走此方法。
     * 将军走此小路便是
     */
    @Inject(method = "m_92852_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void onWidthFormattedText(net.minecraft.network.chat.FormattedText text, CallbackInfoReturnable<Integer> cir) {
        if (CALC_WIDTH.get()) return;
        if (text == null) return;
        String raw = text.getString();
        if (raw == null || raw.isEmpty() || !TextFormatParser.containsSpecialFormatting(raw)) return;
        TextFormatParser.ParseResult pr = TextFormatParser.parseFormatting(raw);
        if (pr.cleanText.isEmpty()) return;
        Font self = (Font) (Object) this;
        CALC_WIDTH.set(true);
        try {
            int w = self.width(pr.cleanText);
            if (w > 0) {
                cir.setReturnValue(w);
            }
        } finally {
            CALC_WIDTH.set(false);
        }
    }

    /**
     * 宽度修正：& 码文本返回清理后宽度，让居中/布局用正确数值。
     */
    @Inject(method = "m_92895_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void onWidthString(String text, CallbackInfoReturnable<Integer> cir) {
        if (CALC_WIDTH.get()) return;
        try {
            if (text == null || text.isEmpty()) return;
            if (!TextFormatParser.containsSpecialFormatting(text)) return;

            TextFormatParser.ParseResult pr = TextFormatParser.parseFormatting(text);
            if (pr.cleanText.isEmpty()) return;

            Font self = (Font) (Object) this;
            CALC_WIDTH.set(true);
            try {
                int w = self.width(pr.cleanText);
                if (w > 0) {
                    cir.setReturnValue(w);
                }
            } finally {
                CALC_WIDTH.set(false);
            }
        } catch (Exception e) {
            LOG.error("[Wobble] width error: {}", e.getMessage());
        }
    }

    /**
     * Component 层拦截：Font.drawInBatch(Component, x, y, ...)
     * 关键路径——GuiGraphics.renderTooltip + 物品名 + Screen 渲染均走此入口。
     * 逐字渲染（ArcaneVortex 同款算法），用 getDynamicColor 实现动态色板动画。
     * 宽度差补正：原始 x 是按含 &$ultimate- 的虚胖宽度计算的，清理后真实宽度变短，
     * 将 x 往右挪 (胖宽-瘦宽)/2 使文本重新居中。
     */
    @Inject(method = "m_272077_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void onDrawInBatchComponent(Component comp, float x, float y, int color, boolean shadow,
                                         Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                         int packedLight, int packedOverlay,
                                         CallbackInfoReturnable<Integer> cir) {
        if (RENDERING_CHAR.get()) return;
        if (comp == null) return;
        String raw = comp.getString();

        boolean hasAmp = TextFormatParser.containsSpecialFormatting(raw);
        TextFormatParser.ParseResult pr;

        if (hasAmp) {
            pr = TextFormatParser.parseFormatting(raw);
        } else {
            String restoredRaw = TextFormatParser.restoreRawFcs(raw);
            if (restoredRaw != null && TextFormatParser.containsSpecialFormatting(restoredRaw)) {
                pr = TextFormatParser.parseFormatting(restoredRaw);
            } else {
            // 从 Component style.insertion 恢复 FCS 信息
            String insertion = comp.getStyle().getInsertion();
            if (insertion != null && insertion.startsWith("fcs:")) {
                String fcsInfo = insertion.substring(4);
                String theme = fcsInfo.contains("|") ? fcsInfo.substring(0, fcsInfo.indexOf('|')) : fcsInfo;
                if (theme.isEmpty()) return;
                int[] pal = DShanhaiStyleRegistry.getRGB(theme.toLowerCase(java.util.Locale.ROOT));
                if (pal == null || pal.length == 0) return;
                TextFormatParser.FormatFlags f = new TextFormatParser.FormatFlags();
                f.gradientTheme = theme;
                f.palette = pal;
                if (fcsInfo.contains("@")) f.circle = true;
                if (fcsInfo.contains("~")) f.floatY = true;
                if (fcsInfo.contains("*")) f.floatX = true;
                if (fcsInfo.contains("%")) f.shake = true;
                if (fcsInfo.contains("!")) f.bounce = true;
                if (fcsInfo.contains("#")) f.outline = true;
                if (fcsInfo.contains("^")) f.scan = true;
                if (fcsInfo.contains("?")) f.glitch = true;
                if (fcsInfo.contains("+")) f.breathe = true;
                if (fcsInfo.contains(">")) f.chase = true;
                if (fcsInfo.contains("`")) f.obfuscated = true;
                pr = new TextFormatParser.ParseResult(raw, f, "", -1);
            } else {
                return;
            }
            }
        }
        if (pr.cleanText.isEmpty() || pr.flags.palette == null || pr.flags.palette.length == 0) return;
        Font self = (Font) (Object) this;
        String txt = pr.cleanText;
        String px = pr.prefix;
        int pxLen = px.length();
        int fcsLen = txt.length() - pxLen;
        int[] pal = pr.flags.palette;
        boolean body = pr.flags.gradientTheme != null && DShanhaiStyleRegistry.isBody(pr.flags.gradientTheme);
        // 宽度差补正：rawW 通过宽度 mixin 得到不含 & 码的瘦宽度
        int rawW = self.width(comp);
        // txt = cleanText = prefix + actualText
        int cleanW = self.width(txt);
        float offset = (rawW - cleanW) / 2.0f;
        // 只在小范围内修正，防止偏移过大
        if (offset > 20 || offset < -20) {
            offset = 0;
        }
        float curX = x + offset;
        long now = System.currentTimeMillis();
        RENDERING_CHAR.set(true);
        try {
            for (int i = 0; i < txt.length(); i++) {
                String ch = String.valueOf(txt.charAt(i));
                boolean isPrefixChar = pxLen > 0 && i < pxLen;

                if (isPrefixChar) {
                    float adv = self.drawInBatch(ch, curX, y, color, shadow, matrix, buffer, mode, packedLight, packedOverlay);
                    curX = adv;
                } else {
                    int fcsIdx = i - pxLen;
                    int cc = DShanhaiTextUtil.getDynamicColor(pal, fcsIdx, fcsLen, body);
                    var wFilter = DShanhaiTextUtil.WOBBLE_FILTER.get();
                    boolean useWobble = wFilter != null && wFilter.test(txt);
                    float[] wd = useWobble ? DShanhaiTextUtil.WOBBLE_OFFSETS.get() : null;
                    float waveY = 0;
                    if (useWobble && wd != null && wd.length > 0 && wd[0] > 0) {
                        waveY = (float)(Math.sin(fcsIdx * 0.7 + now * 0.008) * wd[0]);
                    }

                    float xOff = 0, yOff = waveY;
                    if (pr.flags.circle) { float[] cOff = TextFormatParser.calcCircleOffset(fcsIdx, now); xOff += cOff[0]; yOff += cOff[1]; }
                    if (pr.flags.floatY) yOff += TextFormatParser.calcWaveY(fcsIdx, now);
                    if (pr.flags.floatX) xOff += TextFormatParser.calcWaveX(fcsIdx, now);
                    if (pr.flags.shake) { float[] sOff = TextFormatParser.calcShakeOffset(fcsIdx, now); xOff += sOff[0]; yOff += sOff[1]; }
                    if (pr.flags.bounce) yOff += TextFormatParser.calcBounceY(fcsIdx, now);
                    if (pr.flags.glitch) { xOff += TextFormatParser.calcGlitchX(fcsIdx, now); yOff += TextFormatParser.calcGlitchY(fcsIdx, now); }
                    if (pr.flags.scan) cc = TextFormatParser.applyScanColor(cc, fcsIdx, fcsLen, now);
                    if (pr.flags.breathe) cc = TextFormatParser.applyBreatheColor(cc, fcsIdx, now);
                    if (pr.flags.chase) cc = TextFormatParser.applyChaseColor(cc, fcsIdx, fcsLen, now);
                    if (pr.flags.glitch) cc = TextFormatParser.applyGlitchColor(cc, fcsIdx, now);

                    Style charStyle = Style.EMPTY.withColor(TextColor.fromRgb(cc));
                    if (pr.flags.obfuscated) charStyle = charStyle.withObfuscated(true);
                    final Style drawStyle = charStyle;
                    FormattedCharSequence charSeq = acceptor -> acceptor.accept(0, drawStyle, ch.charAt(0));
                    float advanced = self.drawInBatch(charSeq, curX + xOff, y + yOff, cc, shadow, matrix, buffer, mode, packedLight, packedOverlay);
                    curX = advanced - xOff;
                }
            }
            cir.cancel();
            cir.setReturnValue(Math.round(curX));
        } finally { RENDERING_CHAR.set(false); }
    }

    /**
     * 渲染替换：处理 & 码文本和 WOBBLE 文本。
     * - & 码文本 → 色板+效果渲染，取消原始渲染
     * - 无 & 码但 WOBBLE_OFFSETS 激活 → 纯 wobble（保留 FCS 原始样式颜色）
     * - 居中：匹配 CENTER_TARGETS 时自动偏移（对所有文本统一生效）
     * - 无效果无 wobble → 单次 FCS 渲染（位置/推进由 Font 自己处理）
     * - 有效果 → 逐字渲染（矩阵偏移）
     */
    @Inject(method = "m_272191_",
            at = @At("HEAD"),
            remap = false,
            require = 0,
            cancellable = true)
    private void onDrawInBatchFCS(FormattedCharSequence text, float x, float y, int color, boolean shadow,
                                   Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                   int packedLight, int packedOverlay,
                                   CallbackInfoReturnable<Integer> cir) {
        if (RENDERING_CHAR.get()) return;

        try {
            // ===== 1. 提取 FCS 字符 + 原始样式，同时解析 § 码 =====
            StringBuilder sb = new StringBuilder();
            List<Style> charStyles = new ArrayList<>();
            text.accept((i, s, cp) -> {
                sb.append((char) cp);
                charStyles.add(s);
                return true;
            });
            String rawText = sb.toString();
            if (rawText.isEmpty()) return;

            // 解析 § 码：构建 cleanedText（去掉 § 码字符对）+ legacyStyles（每个可见字符对应的 § 码 Style）
            StringBuilder cleaned = new StringBuilder();
            List<Style> cleanedStyles = new ArrayList<>();
            net.minecraft.ChatFormatting legacyColor = null;
            boolean legacyObfuscated = false;
            for (int si = 0; si < rawText.length(); si++) {
                char rc = rawText.charAt(si);
                if (rc == '\u00A7' && si + 1 < rawText.length()) {
                    net.minecraft.ChatFormatting cf = net.minecraft.ChatFormatting.getByCode(rawText.charAt(si + 1));
                    if (cf != null) {
                        if (cf.isColor()) legacyColor = cf;
                        else if (cf == net.minecraft.ChatFormatting.OBFUSCATED) legacyObfuscated = true;
                        else if (cf == net.minecraft.ChatFormatting.RESET) {
                            legacyColor = null;
                            legacyObfuscated = false;
                        }
                        si++;
                        continue;
                    }
                }
                cleaned.append(rc);
                Style legacyStyle = legacyColor != null ? Style.EMPTY.applyFormat(legacyColor)
                        : (si < charStyles.size() ? charStyles.get(si) : Style.EMPTY);
                if (legacyObfuscated) legacyStyle = legacyStyle.withObfuscated(true);
                cleanedStyles.add(legacyStyle);
            }
            String rawTextClean = cleaned.toString();
            List<Style> rawTextCleanStyles = cleanedStyles;
            if (rawTextClean.isEmpty()) return;

            String restoredRaw = TextFormatParser.restoreRawFcs(rawTextClean);
            if (restoredRaw != null && TextFormatParser.containsSpecialFormatting(restoredRaw)) {
                rawTextClean = restoredRaw;
                rawTextCleanStyles = java.util.Collections.emptyList();
            }

            boolean hasAmpersand = TextFormatParser.containsSpecialFormatting(rawTextClean);
            boolean hasWobble = false;
            float globalWobble = 0;

            // ===== 2. 解析 & 码（如有） =====
            TextFormatParser.FormatFlags flags = null;
            String displayText = rawTextClean;
            String fcsPrefix = ""; // & 前的文本（如 "9.22EX "），渲染时不丢
            boolean hasPalette = false;
            boolean hasEffects = false;
            boolean isBody = false;
            int[] palette = null;
            int fcsPartOffset = 0; // FCS 部分在 displayText 中的起始字符索引

            if (hasAmpersand) {
                TextFormatParser.ParseResult pr = TextFormatParser.parseFormatting(rawTextClean);
                displayText = pr.cleanText;
                fcsPrefix = pr.prefix;
                fcsPartOffset = fcsPrefix.length();
                if (displayText.isEmpty()) return;
                flags = pr.flags;
                hasPalette = flags.palette != null && flags.palette.length > 0;
                hasEffects = flags.hasEffect();
                palette = flags.palette;
                isBody = flags.gradientTheme != null && DShanhaiStyleRegistry.isBody(flags.gradientTheme);
                if (!hasPalette && !hasEffects) {
                    // 无 & 码效果时仍检查 wobble，否则直接跳过
                    var wf = DShanhaiTextUtil.WOBBLE_FILTER.get();
                    if (wf == null || !wf.test(displayText) || DShanhaiTextUtil.WOBBLE_OFFSETS.get() == null) return;
                }
            } else {
                // 无 & 码：从 style.insertion 读取 FCS 标记（FTBQ 拆行后子串保留 style）
                String insertion = null;
                if (!rawTextCleanStyles.isEmpty()) {
                    insertion = rawTextCleanStyles.get(0).getInsertion();
                }
                if (insertion != null && insertion.startsWith("fcs:")) {
                    String fcsInfo = insertion.substring(4);
                    String theme = fcsInfo;
                    if (fcsInfo.contains("|")) {
                        theme = fcsInfo.substring(0, fcsInfo.indexOf('|'));
                    }
                    if (!theme.isEmpty()) {
                        int[] pal = DShanhaiStyleRegistry.getRGB(theme.toLowerCase(java.util.Locale.ROOT));
                        if (pal != null && pal.length > 0) {
                            palette = pal;
                            hasPalette = true;
                            isBody = DShanhaiStyleRegistry.isBody(theme);
                            flags = new TextFormatParser.FormatFlags();
                            flags.gradientTheme = theme;
                            flags.palette = pal;
                            if (fcsInfo.contains("@")) flags.circle = true;
                            if (fcsInfo.contains("~")) flags.floatY = true;
                            if (fcsInfo.contains("*")) flags.floatX = true;
                            if (fcsInfo.contains("%")) flags.shake = true;
                            if (fcsInfo.contains("!")) flags.bounce = true;
                            if (fcsInfo.contains("#")) flags.outline = true;
                            if (fcsInfo.contains("^")) flags.scan = true;
                            if (fcsInfo.contains("?")) flags.glitch = true;
                            if (fcsInfo.contains("+")) flags.breathe = true;
                            if (fcsInfo.contains(">")) flags.chase = true;
                            if (fcsInfo.contains("`")) flags.obfuscated = true;
                            hasEffects = flags.hasEffect();
                            hasAmpersand = true;
                        }
                    }
                }
            }

            // 通用 wobble 检测（不论是否有 & 码）
            {
                var wf = DShanhaiTextUtil.WOBBLE_FILTER.get();
                boolean wfResult = (wf != null) ? wf.test(displayText) : false;
                hasWobble = wf != null && wfResult;
                var wo = DShanhaiTextUtil.WOBBLE_OFFSETS.get();
                globalWobble = (hasWobble && wo != null) ? wo[0] : 0;
            }

            // ===== 3. 非 & 非 wobble → 仅居中或不处理 =====
            if (!hasAmpersand && !hasWobble) {
                Font self = (Font) (Object) this;
                var centerTargets = DShanhaiTextUtil.CENTER_TARGETS.get();
                int containerWidth = DShanhaiTextUtil.CENTER_CONTAINER_WIDTH.get();
                if (containerWidth > 0 && !centerTargets.isEmpty()) {
                    // 剥离尾部全角空格获取可见部分
                    int ei = rawTextClean.length();
                    while (ei > 0 && (rawTextClean.charAt(ei - 1) == '　' || rawTextClean.charAt(ei - 1) == ' ')) ei--;
                    String visible = rawTextClean.substring(0, ei);
                    if (!visible.isEmpty()) {
                        float visibleW = self.width(visible);
                        float co = 0;
                        for (String target : centerTargets) {
                            if (target.startsWith(visible)) {
                                co = Math.max(0, (containerWidth - visibleW) / 2f);
                                break;
                            }
                        }
                        if (co > 0) {
                            RENDERING_CHAR.set(true);
                            try {
                                cir.cancel();
                                cir.setReturnValue(Math.round(self.drawInBatch(text, x + co, y, color, shadow,
                                        matrix, buffer, mode, packedLight, packedOverlay)));
                            } finally { RENDERING_CHAR.set(false); }
                            return;
                        }
                    }
                }
                return;
            }

            // ===== 4. 宽度差补正 + 居中偏移 =====
            Font self = (Font) (Object) this;

            // 4a. 宽度补正：上游已通过 onWidthFCS/onWidthString 修正为瘦宽度，
            //     x 位置本身已正确，此处不再需要偏移，保持 widthOffset = 0
            float widthOffset = 0;

            // 4b. 居中偏移
            String centerMatch;
            float centerWidth;
            if (!hasAmpersand) {
                int ei = displayText.length();
                while (ei > 0 && (displayText.charAt(ei - 1) == '　' || displayText.charAt(ei - 1) == ' ')) ei--;
                centerMatch = displayText.substring(0, ei);
                centerWidth = self.width(centerMatch);
            } else {
                centerMatch = displayText;
                centerWidth = self.width(displayText);
            }
            float centerOffset = 0;
            var centerTargets = DShanhaiTextUtil.CENTER_TARGETS.get();
            int containerWidth = DShanhaiTextUtil.CENTER_CONTAINER_WIDTH.get();
            if (containerWidth > 0 && !centerTargets.isEmpty()) {
                for (String target : centerTargets) {
                    if (target.equals(centerMatch)) {
                        centerOffset = Math.max(0, (containerWidth - centerWidth) / 2f);
                        break;
                    }
                }
            }

            // ===== 5. 无效果 + 无 wobble → 纯色板单次渲染 =====
            if (!hasEffects && !hasWobble) {
                float curX = x + widthOffset + centerOffset;
                // 先渲染前缀文本（含数量等，原始颜色，不受 FCS 影响）
                if (!fcsPrefix.isEmpty()) {
                    curX = self.drawInBatch(fcsPrefix, curX, y, color, shadow, matrix, buffer, mode, packedLight, packedOverlay);
                }
                // 再渲染 FCS 部分（色板颜色），跳过 § 码字符对
                    final String fcsPart = displayText.substring(fcsPartOffset);
                    if (!fcsPart.isEmpty()) {
                        final int[] fcsPalette = palette;
                        final boolean fcsIsBody = isBody;
                        final boolean fcsObfuscated = flags != null && flags.obfuscated;
                        FormattedCharSequence styledSeq = acceptor -> {
                            int fcsIdx = 0;
                            for (int i = 0; i < fcsPart.length(); i++) {
                            char c = fcsPart.charAt(i);
                            if (c == '\u00A7' && i + 1 < fcsPart.length()) {
                                i++;
                                continue;
                            }
                            int cc = DShanhaiTextUtil.getDynamicColor(fcsPalette, fcsIdx, fcsPart.length(), fcsIsBody);
                            Style charStyle = Style.EMPTY.withColor(TextColor.fromRgb(cc));
                            if (fcsObfuscated) charStyle = charStyle.withObfuscated(true);
                            if (!acceptor.accept(fcsIdx, charStyle, c)) return false;
                            fcsIdx++;
                        }
                        return true;
                    };
                    RENDERING_CHAR.set(true);
                    try {
                        cir.cancel();
                        cir.setReturnValue(Math.round(self.drawInBatch(styledSeq, curX, y, color, shadow,
                                matrix, buffer, mode, packedLight, packedOverlay)));
                    } finally {
                        RENDERING_CHAR.set(false);
                    }
                } else {
                    cir.cancel();
                    cir.setReturnValue(Math.round(curX));
                }
                return;
            }

            // ===== 6. 有效果/WOBBLE：逐字渲染 =====
            long now = System.currentTimeMillis();
            float currentX = x + widthOffset + centerOffset;

            // 解析 WOBBLE_STYLE_MAP 逐行效果前缀和纯净色板名
            String perLineEffectsPre = "";
            String perLinePalette = "";
            if (hasWobble) {
                String ls = DShanhaiTextUtil.WOBBLE_STYLE_MAP.get().get(displayText);
                if (ls != null) {
                    int di = ls.indexOf('$');
                    if (di >= 0) {
                        perLineEffectsPre = ls.substring(0, di);
                        perLinePalette = ls.substring(di + 1);
                    } else {
                        perLinePalette = ls;
                    }
                }
            }

            // 解析效果参数倍率（%2.5 = shake 2.5x, ~0.5 = floatY 0.5x）
            float shakeMult = 1.0f, floatYMult = 1.0f, floatXMult = 1.0f;
            float circleMult = 1.0f, bounceMult = 1.0f;
            if (!perLineEffectsPre.isEmpty()) {
                String ps = perLineEffectsPre;
                for (int pi = 0; pi < ps.length(); pi++) {
                    char ec = ps.charAt(pi);
                    StringBuilder num = new StringBuilder();
                    while (pi + 1 < ps.length() && (Character.isDigit(ps.charAt(pi + 1)) || ps.charAt(pi + 1) == '.')) {
                        num.append(ps.charAt(pi + 1));
                        pi++;
                    }
                    float val = num.length() > 0 ? Float.parseFloat(num.toString()) : 1.0f;
                    switch (ec) {
                        case '%': shakeMult = val; break;
                        case '~': floatYMult = val; break;
                        case '*': floatXMult = val; break;
                        case '@': circleMult = val; break;
                        case '!': bounceMult = val; break;
                    }
                }
            }

            RENDERING_CHAR.set(true);
            try {
                int prefixLen = fcsPrefix.length();
                boolean hasPrefix = prefixLen > 0;
                int fcsCharCount = 0;
                for (int i = 0; i < displayText.length(); i++) {
                    char c = displayText.charAt(i);
                    String charStr = String.valueOf(c);
                    float xOff = 0, yOff = 0;

                    boolean isPrefixChar = hasPrefix && i < prefixLen;

                    // 检查当前字符是否带有 § 码遗留的 Style（颜色/格式）
                    Style legacyStyle = (i < rawTextCleanStyles.size()) ? rawTextCleanStyles.get(i) : Style.EMPTY;
                    boolean hasLegacyColor = legacyStyle.getColor() != null;
                    boolean hasLegacyObfuscated = Boolean.TRUE.equals(legacyStyle.isObfuscated());

                    // 有 § 码颜色的字符不走 FCS 动态染色和效果
                    boolean useFcs = !isPrefixChar && !hasLegacyColor;

                    if (useFcs && flags != null) {
                        int fi = fcsCharCount;
                        if (flags.circle) { float[] co = TextFormatParser.calcCircleOffset(fi, now); xOff += co[0]; yOff += co[1]; }
                        if (flags.floatY) yOff += TextFormatParser.calcWaveY(fi, now);
                        if (flags.floatX) xOff += TextFormatParser.calcWaveX(fi, now);
                        if (flags.shake) { float[] s = TextFormatParser.calcShakeOffset(fi, now); xOff += s[0]; yOff += s[1]; }
                        if (flags.bounce) yOff += TextFormatParser.calcBounceY(fi, now);
                        if (flags.glitch) { xOff += TextFormatParser.calcGlitchX(fi, now); yOff += TextFormatParser.calcGlitchY(fi, now); }
                    }

                    if (useFcs && !perLineEffectsPre.isEmpty()) {
                        int fi = fcsCharCount;
                        if (perLineEffectsPre.contains("%")) { float[] s = TextFormatParser.calcShakeOffset(fi, now); xOff += s[0] * shakeMult; yOff += s[1] * shakeMult; }
                        if (perLineEffectsPre.contains("~")) yOff += TextFormatParser.calcWaveY(fi, now) * floatYMult;
                        if (perLineEffectsPre.contains("*")) xOff += TextFormatParser.calcWaveX(fi, now) * floatXMult;
                        if (perLineEffectsPre.contains("@")) { float[] co = TextFormatParser.calcCircleOffset(fi, now); xOff += co[0] * circleMult; yOff += co[1] * circleMult; }
                        if (perLineEffectsPre.contains("!")) yOff += TextFormatParser.calcBounceY(fi, now) * bounceMult;
                    }

                    if (useFcs) {
                        String pce = DShanhaiTextUtil.PER_CHAR_EFFECTS.get().get(displayText);
                        if (pce != null && i < pce.length()) {
                            char ec = pce.charAt(i);
                            int fi = fcsCharCount;
                            if (ec == '%') { float[] s = TextFormatParser.calcShakeOffset(fi, now); xOff += s[0]; yOff += s[1]; }
                            else if (ec == '~') yOff += TextFormatParser.calcWaveY(fi, now);
                            else if (ec == '*') xOff += TextFormatParser.calcWaveX(fi, now);
                            else if (ec == '@') { float[] co = TextFormatParser.calcCircleOffset(fi, now); xOff += co[0]; yOff += co[1]; }
                            else if (ec == '!') yOff += TextFormatParser.calcBounceY(fi, now);
                            else if (ec == '?') { xOff += TextFormatParser.calcGlitchX(fi, now); yOff += TextFormatParser.calcGlitchY(fi, now); }
                        }
                    }

                    if (useFcs && hasWobble) {
                        float[] wd = DShanhaiTextUtil.WOBBLE_OFFSETS.get();
                        if (wd != null && wd.length > 0 && wd[0] > 0) {
                            yOff += (float)(Math.sin(fcsCharCount * 0.7 + now * 0.008) * wd[0]);
                        }
                    }

                    int charColor = color;
                    Style charStyle = Style.EMPTY;
                    if (hasLegacyColor) {
                        // § 码颜色优先，用原版 ChatFormatting 染色
                        charColor = legacyStyle.getColor().getValue();
                        charStyle = legacyStyle.withColor(TextColor.fromRgb(charColor));
                    } else if (useFcs) {
                        int fcsIdx = fcsCharCount;
                        int fcsLen = displayText.length() - prefixLen;
                        if (!perLinePalette.isEmpty()) {
                            int[] stylePalette = DShanhaiStyleRegistry.getRGB(perLinePalette);
                            if (stylePalette != null && stylePalette.length > 0) {
                                boolean styleBody = DShanhaiStyleRegistry.isBody(perLinePalette);
                                charColor = DShanhaiTextUtil.getDynamicColor(stylePalette, fcsIdx, fcsLen, styleBody);
                            }
                        }
                        if (charColor == color) {
                            String wobbleStyle = DShanhaiTextUtil.WOBBLE_STYLE.get();
                            if (wobbleStyle != null) {
                                int[] stylePalette = DShanhaiStyleRegistry.getRGB(wobbleStyle);
                                if (stylePalette != null && stylePalette.length > 0) {
                                    boolean styleBody = DShanhaiStyleRegistry.isBody(wobbleStyle);
                                    charColor = DShanhaiTextUtil.getDynamicColor(stylePalette, fcsIdx, fcsLen, styleBody);
                                }
                            } else if (hasPalette) {
                                charColor = DShanhaiTextUtil.getDynamicColor(palette, fcsIdx, fcsLen, isBody);
                            }
                        }
                        if (flags != null) {
                            if (flags.scan) charColor = TextFormatParser.applyScanColor(charColor, fcsIdx, fcsLen, now);
                            if (flags.breathe) charColor = TextFormatParser.applyBreatheColor(charColor, fcsIdx, now);
                            if (flags.chase) charColor = TextFormatParser.applyChaseColor(charColor, fcsIdx, fcsLen, now);
                            if (flags.glitch) charColor = TextFormatParser.applyGlitchColor(charColor, fcsIdx, now);
                        }
                        charStyle = Style.EMPTY.withColor(TextColor.fromRgb(charColor));
                        if (flags != null && flags.obfuscated) charStyle = charStyle.withObfuscated(true);
                    } else if (hasLegacyObfuscated) {
                        charStyle = legacyStyle;
                    }

                    final Style drawStyle = charStyle;
                    FormattedCharSequence charSeq = acceptor -> acceptor.accept(0, drawStyle, charStr.charAt(0));
                    float adv = self.drawInBatch(charSeq, currentX + xOff, y + yOff, charColor, shadow,
                            matrix, buffer, mode, packedLight, packedOverlay);

                    currentX = adv - xOff;
                    if (useFcs) fcsCharCount++;

                }
                cir.cancel();
                cir.setReturnValue(Math.round(currentX));
            } finally {
                RENDERING_CHAR.set(false);
            }
        } catch (Exception e) {
            LOG.error("[WobbleFCS] Exception: ", e);
        }
    }
}
