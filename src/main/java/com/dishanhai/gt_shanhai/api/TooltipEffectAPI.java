package com.dishanhai.gt_shanhai.api;

import com.dishanhai.gt_shanhai.api.TooltipEffectRegistry.TooltipEffectConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * KubeJS 绑定入口 — Tooltip 特效注册。
 *
 * KubeJS 调用示例：
 * <pre>
 * var API = Java.loadClass('com.dishanhai.gt_shanhai.api.TooltipEffectAPI');
 * // 简单注册（默认样式，默认提示 "按住 ALT 显示终末寄语"）
 * API.register("kubejs:my_item", ["第一行", "第二行"]);
 * // 自定义提示文本
 * API.register("kubejs:my_item", ["第一行", "第二行"], "§7§o按住 §eALT §7查看秘密");
 * // 自定义速度 + 混淆 + 样式 + 提示文本
 * API.register("kubejs:another_item", ["行一", "行二"], 6000, 3, ["ultimate", "fire"], "§7§o按住 §eALT §7展开详情");
 * </pre>
 */
@SuppressWarnings("unused")
public class TooltipEffectAPI {

    /** 简单注册：默认 body_silver 展开 + ultimate wobble */
    public static void register(String itemId, String[] lines) {
        register(itemId, lines, "§7§o按住 §eALT §7显示终末寄语");
    }

    /** 简单注册 + 自定义提示文本 */
    public static void register(String itemId, String[] lines, String hintText) {
        List<TooltipEffectLine> list = new ArrayList<>();
        for (String l : lines) {
            list.add(new TooltipEffectLine(l));
        }
        TooltipEffectRegistry.register(itemId, new TooltipEffectConfig(itemId, 4000, 2, list, hintText));
    }

    /** 完整注册：自定义参数 */
    public static void register(String itemId, String[] lines, long revealSpeedMs, int obfuscateCount, String[] wobbleStyles) {
        register(itemId, lines, revealSpeedMs, obfuscateCount, wobbleStyles, "§7§o按住 §eALT §7显示终末寄语");
    }

    /** 完整注册：自定义参数 + 提示文本，reveal 色板自动跟随 wobble 色板 */
    public static void register(String itemId, String[] lines, long revealSpeedMs, int obfuscateCount, String[] wobbleStyles, String hintText) {
        List<TooltipEffectLine> list = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String ws = (i < wobbleStyles.length) ? wobbleStyles[i] : "ultimate";
            String rs = ws;
            int di = ws.indexOf('$');
            if (di >= 0) rs = ws.substring(di + 1);
            for (String ec : new String[]{"%", "~", "*", "@", "!", "#"}) {
                while (rs.startsWith(ec)) rs = rs.substring(1);
            }
            list.add(new TooltipEffectLine(lines[i], rs.isEmpty() ? "body_silver" : rs, ws));
        }
        TooltipEffectRegistry.register(itemId, new TooltipEffectConfig(itemId, revealSpeedMs, obfuscateCount, list, hintText));
    }

    /** 完整注册：自定义参数 + 已完成行乱码（obfu:true=已完成行全行混淆，obfu:false/缺省=不混淆） */
    public static void register(String itemId, String[] lines, long revealSpeedMs, int obfuscateCount, String[] wobbleStyles, String hintText, String obfuConfig) {
        List<TooltipEffectLine> list = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String ws = (i < wobbleStyles.length) ? wobbleStyles[i] : "ultimate";
            String rs = ws;
            int di = ws.indexOf('$');
            if (di >= 0) rs = ws.substring(di + 1);
            for (String ec : new String[]{"%", "~", "*", "@", "!", "#"}) {
                while (rs.startsWith(ec)) rs = rs.substring(1);
            }
            list.add(new TooltipEffectLine(lines[i], rs.isEmpty() ? "body_silver" : rs, ws));
        }
        boolean obfu = obfuConfig != null && obfuConfig.equalsIgnoreCase("obfu:true");
        TooltipEffectRegistry.register(itemId, new TooltipEffectConfig(itemId, revealSpeedMs, obfuscateCount, list, hintText, obfu));
    }

    /** 完整注册：自定义展开色板 + wobble 色板 */
    public static void registerFull(String itemId, String[] lines, String[] revealStyles, String[] wobbleStyles, long revealSpeedMs, int obfuscateCount) {
        registerFull(itemId, lines, revealStyles, wobbleStyles, revealSpeedMs, obfuscateCount, "§7§o按住 §eALT §7显示终末寄语");
    }

    /** 完整注册：自定义展开色板 + wobble 色板 + 提示文本 */
    public static void registerFull(String itemId, String[] lines, String[] revealStyles, String[] wobbleStyles, long revealSpeedMs, int obfuscateCount, String hintText) {
        List<TooltipEffectLine> list = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String rs = (i < revealStyles.length) ? revealStyles[i] : "body_silver";
            String ws = (i < wobbleStyles.length) ? wobbleStyles[i] : "ultimate";
            list.add(new TooltipEffectLine(lines[i], rs, ws));
        }
        TooltipEffectRegistry.register(itemId, new TooltipEffectConfig(itemId, revealSpeedMs, obfuscateCount, list, hintText));
    }

    public static int count() {
        return TooltipEffectRegistry.count();
    }

    public static boolean has(String itemId) {
        return TooltipEffectRegistry.has(itemId);
    }
}
