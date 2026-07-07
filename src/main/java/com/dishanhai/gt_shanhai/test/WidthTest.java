package com.dishanhai.gt_shanhai.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 宽度计算独立测试。
 * 用法（KubeJS client 脚本）：
 * Java.loadClass('com.dishanhai.gt_shanhai.test.WidthTest').run()
 */
public class WidthTest {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai.widthtest");

    public static void run() {
        LOG.info("========= WidthTest START =========");

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            LOG.error("Minecraft/font not available");
            return;
        }
        Font font = mc.font;

        String rawText   = "&$ultimate-神锻恒星终焉模块";
        String cleanText = "神锻恒星终焉模块";
        int rawLen = rawText.length();
        int cleanLen = cleanText.length();

        LOG.info("raw=\"{}\" (len={})", rawText, rawLen);
        LOG.info("clean=\"{}\" (len={})", cleanText, cleanLen);

        // ===== 1. Font.width(String) =====
        int wSRaw = font.width(rawText);
        int wSClean = font.width(cleanText);
        LOG.info("[Font.width(String)] raw={} clean={} 期望相同? {}",
                wSRaw, wSClean, wSRaw == wSClean ? "=PASS" : "=FAIL diff=" + (wSRaw - wSClean));

        // ===== 2. Font.width(Component) =====
        int wCRaw = font.width(Component.literal(rawText));
        int wCClean = font.width(Component.literal(cleanText));
        LOG.info("[Font.width(Component)] raw={} clean={} 期望相同? {}",
                wCRaw, wCClean, wCRaw == wCClean ? "=PASS" : "=FAIL diff=" + (wCRaw - wCClean));

        // ===== 3. Font.width(FCS) via Component.getVisualOrderText() =====
        FormattedCharSequence fcsRaw = Component.literal(rawText).getVisualOrderText();
        FormattedCharSequence fcsClean = Component.literal(cleanText).getVisualOrderText();
        int wFCSRaw = font.width(fcsRaw);
        int wFCSClean = font.width(fcsClean);
        LOG.info("[Font.width(FCS)] raw={} clean={} 期望相同? {}",
                wFCSRaw, wFCSClean, wFCSRaw == wFCSClean ? "=PASS" : "=FAIL diff=" + (wFCSRaw - wFCSClean));

        // ===== 汇总 =====
        boolean allOk = (wSRaw == wSClean) && (wCRaw == wCClean) && (wFCSRaw == wFCSClean);
        LOG.info("========= WidthTest {} =========", allOk ? "ALL PASS" : "SOME FAILED");
    }
}
