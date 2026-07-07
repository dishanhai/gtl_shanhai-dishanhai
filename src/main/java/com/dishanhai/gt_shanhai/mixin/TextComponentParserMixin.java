package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.TextFormatParser;

import dev.ftb.mods.ftblibrary.util.TextComponentParser;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * FTBQ 的 TextComponentParser 遇到 & 后非标准格式化码字符会抛 BadFormatException。
 * 山海 FCS 使用 &[效果][$style]-text 格式，同时支持原版 &r/&a 等格式化码。
 *
 * 策略：
 * 1. 剥离 FCS 前缀，只传 cleanText 给 FTBQ parser → 宽度正确
 * 2. 在 parse 返回的 Component 上设置 style.insertion = "fcs:style" 作为色板标记
 * 3. FTBQ 拆行后每行 Component 子串保留 style → WobbleFontMixin 从 style 读取色板
 * 4. 遇到 &r 时，只给 &r 之前的子组件设 FCS insertion，&r 后的原版重置
 */
@Mixin(value = TextComponentParser.class, remap = false)
public class TextComponentParserMixin {

    @ModifyArg(
        method = "parse0(Ljava/lang/String;Ljava/util/function/Function;)Lnet/minecraft/network/chat/Component;",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ftb/mods/ftblibrary/util/TextComponentParser;<init>(Ljava/lang/String;Ljava/util/function/Function;)V"
        ),
        index = 0
    )
    private static String shanhai$stripFcsPrefix(String text) {
        if (text == null || text.length() < 2) return text;

        int ampIdx = findFcsPrefixStart(text);
        if (ampIdx >= 0) {
            int dashIdx = text.indexOf('-', ampIdx + 1);
            if (dashIdx >= 0) {
                String beforeAmp = text.substring(0, ampIdx);
                String displayText = text.substring(dashIdx + 1);
                String cleanText = beforeAmp + displayText;
                TextFormatParser.rememberFcsMapping(cleanText, text);
                return escapeRemainingFcs(cleanText);
            }
            TextFormatParser.ParseResult parsed = TextFormatParser.parseFormatting(text);
            if (parsed.ampIdx >= 0 && !parsed.cleanText.equals(text)) {
                TextFormatParser.rememberFcsMapping(parsed.cleanText, text);
                return escapeRemainingFcs(parsed.cleanText);
            }
        }

        return escapeRemainingFcs(text);
    }

    @Inject(
        method = "parse(Ljava/lang/String;Ljava/util/function/Function;)Lnet/minecraft/network/chat/Component;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void shanhai$injectFcsStyle(String text, java.util.function.Function<String, Component> substitutes,
            CallbackInfoReturnable<Component> cir) {
        if (text == null || text.isEmpty()) return;
        Component result = cir.getReturnValue();
        if (!(result instanceof MutableComponent mc)) return;

        String fcsInfo = extractFcsInfo(text);
        if (fcsInfo == null) return;

        int ampIdx = findFcsPrefixStart(text);
        int dashIdx = text.indexOf('-', ampIdx + 1);
        int visibleStart = dashIdx >= 0 ? dashIdx + 1 : Math.max(ampIdx + 1, 0);
        int resetPos = countVisibleBeforeReset(text, visibleStart);

        if (resetPos < 0) {
            cir.setReturnValue(mc.withStyle(s -> s.withInsertion(fcsInfo)));
        } else {
            int accumulated = 0;
            List<Component> siblings = mc.getSiblings();
            for (Component child : siblings) {
                int childLen = child.getString().length();
                if (accumulated < resetPos && child instanceof MutableComponent mcChild) {
                    mcChild.withStyle(s -> s.withInsertion(fcsInfo));
                }
                accumulated += childLen;
                if (accumulated >= resetPos) break;
            }
        }
    }

    private static int countVisibleBeforeReset(String text, int start) {
        int count = 0;
        for (int i = start; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '&' || c == '\u00a7') {
                char next = text.charAt(i + 1);
                if (next == 'r' || next == 'R') return count;
                i++;
            } else {
                count++;
            }
        }
        return -1;
    }

    private static String extractFcsInfo(String text) {
        int ampIdx = findFcsPrefixStart(text);
        if (ampIdx < 0) return null;
        int dashIdx = text.indexOf('-', ampIdx + 1);
        if (dashIdx < 0) {
            TextFormatParser.ParseResult parsed = TextFormatParser.parseFormatting(text);
            return TextFormatParser.toFcsInsertion(parsed.flags);
        }
        String codePart = text.substring(ampIdx + 1, dashIdx);
        StringBuilder sb = new StringBuilder("fcs:");
        int dollarIdx = codePart.indexOf('$');
        if (dollarIdx >= 0) {
            String theme = codePart.substring(dollarIdx + 1);
            if (theme.startsWith("@")) theme = theme.substring(1);
            if (!theme.isEmpty()) sb.append(theme);
        }
        if (codePart.contains("@")) sb.append("|@");
        if (codePart.contains("~")) sb.append("|~");
        if (codePart.contains("*")) sb.append("|*");
        if (codePart.contains("%")) sb.append("|%");
        if (codePart.contains("!")) sb.append("|!");
        if (codePart.contains("#")) sb.append("|#");
        if (codePart.contains("^")) sb.append("|^");
        if (codePart.contains("?")) sb.append("|?");
        if (codePart.contains("+")) sb.append("|+");
        if (codePart.contains(">")) sb.append("|>");
        if (codePart.contains("`")) sb.append("|`");
        if (sb.length() <= 4) return null;
        return sb.toString();
    }

    private static int findFcsPrefixStart(String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '&') {
                char next = text.charAt(i + 1);
                if (isFcsSymbol(next)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String escapeRemainingFcs(String text) {
        if (text == null || text.length() < 2) return text;
        boolean hasAmpersand = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') { hasAmpersand = true; break; }
        }
        if (!hasAmpersand) return text;

        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                boolean alreadyEscaped = i > 0 && text.charAt(i - 1) == '\\';
                if (isFcsSymbol(next) && !alreadyEscaped) {
                    int dashIdx = text.indexOf('-', i + 1);
                    if (dashIdx >= 0) {
                        sb.append(text, i, dashIdx + 1);
                        i = dashIdx;
                        continue;
                    }
                    sb.append('\\');
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isFcsSymbol(char c) {
        switch (c) {
            case '$': case '@': case '~': case '#': case '*': case '%': case '!':
            case '^': case '?': case '+': case '>': case '`':
                return true;
            default:
                return false;
        }
    }
}
