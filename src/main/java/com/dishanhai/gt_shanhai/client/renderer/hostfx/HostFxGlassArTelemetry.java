package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuArStatus;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuOperationState;
import java.math.BigDecimal;
import java.util.List;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxGlassArModel.*;

/** 山海：常駐真實狀態與四頁補充資訊；只讀同一份已解碼快照。 */
final class HostFxGlassArTelemetry {
    private HostFxGlassArTelemetry() {}

    static boolean hasModuleSnapshot(State state) {
        return state.moduleSlots != null && state.installedModules >= 0
                && state.moduleSlots.length == state.installedModules;
    }

    static int page(State state) {
        return (int) Math.floorMod(Math.floorDiv(state.modulePageTimeMillis, 10_000L), 4L);
    }

    static String root(GuixuArStatus status) {
        if (!status.known()) return "归墟 --";
        if (status.lockSeconds() > 0) return "归墟 坍缩锁定 " + status.lockSeconds() + "s";
        if (!status.enabled()) return "归墟 已停机";
        return "归墟 " + GuixuOperationState.fromId(status.operation()).label;
    }

    static String field(GuixuArStatus status) {
        return switch (status.containment()) {
            case -1 -> "未安装";
            case 0 -> "关闭";
            case 1 -> "遏制中";
            case 2 -> "失稳";
            case 3 -> "超稳态";
            default -> "--";
        };
    }

    static String link(int state) {
        return switch (state) {
            case 0 -> "未绑定";
            case 1 -> "等待目标";
            case 2 -> "已生效";
            default -> "--";
        };
    }

    static String[] reads(State st) {
        GuixuArStatus s = st.status;
        boolean open = s.known() && s.containment() > 0;
        return new String[]{
                root(s),
                (st.formed ? "结构已成型" : "结构未成型") + " · 工作 " + number(s.activeModules()),
                s.known() ? "模块 本地" + s.localModules() + " 远端" + s.remoteModules() + " 回响" + s.echoModules()
                        : "模块 --（等待状态快照）",
                "能源仓 " + storedOverCapacity(st.energyInStored, st.energyInCapacity),
                "动力仓 " + storedOverCapacity(st.energyOutStored, st.energyOutCapacity),
                "物质模块 " + levelText(st.matterLevel),
                "等级复写 " + overrideText(st.levelOverride),
                "材料增产 " + (st.productionMultiplier < 0 ? "--" : "x" + st.productionMultiplier),
                "遏制场 " + field(s),
                "稳定度 " + (open ? decimal(s.stability()) + " / 100" : "--"),
                "时空流体 " + (open ? compactMagnitude(s.spacetimePerSecond()) + " mB/s" : "--"),
                s.containment() == 2 ? "距坍缩 " + decimal(s.stability() + 900) + "s"
                        : "消耗倍率 " + (open ? "×" + compactMagnitude(s.costModifier()) : "--"),
                "吞噬 " + (s.known() ? decimal(s.bonus().swallowChance() * 100) + "%" : "--"),
                "终焉引擎 " + link(s.engineLink()),
                "奈亚回响 " + link(s.echoLink()),
                s.lockSeconds() > 0 ? "锁定剩余 " + s.lockSeconds() + "s"
                        : "主柱 " + st.mdPillarCount + " · 小束 " + st.thinBeamCount,
        };
    }

    static void buildDetails(State st, List<Txt> texts) {
        GuixuArStatus s = st.status;
        String[] lines;
        int page = page(st);
        if (page == 1) {
            double[] p = buildPhysics(st.holeRadiusBlocks);
            lines = new String[]{"黑洞物理 · 2/4", "黑盘 " + decimal(p[0]) + "格 · Rs " + decimal(p[1]) + "格",
                    "光子球 " + decimal(p[2]) + "格（1.50 Rs）", "ISCO " + decimal(p[3]) + "格（3.00 Rs）",
                    "盘外缘 " + decimal(p[4]) + "格（11.0 Rs）", "Rs 处逃逸速度 1.000c"};
        } else if (page == 2) {
            lines = new String[]{"视界动态 · 3/4", "内环 " + decimal(st.ringDegPerSec) + UNIT_DEG,
                    "上舰 +" + decimal(st.shipUpDegPerSec) + " · 下舰 -" + decimal(st.shipDownDegPerSec) + UNIT_DEG,
                    "主柱 " + st.mdPillarCount + " / 4 · 细束 " + st.thinBeamCount + " / 12",
                    "引力透镜 " + (st.lensOn ? "开启" : "关闭") + " · 星空 " + (st.starsOn ? "开启" : "关闭"),
                    "星云 " + (st.nebulaOn ? "开启" : "关闭") + " · 特效 " + (st.fxOn ? "运行" : "降级／冻结")};
        } else if (page == 3) {
            int[] health = new int[6];
            if (st.moduleSlots != null) {
                for (int slot : st.moduleSlots) {
                    int reason = st.modReason != null && slot >= 0 && slot < st.modReason.length ? st.modReason[slot] : -1;
                    health[reason >= 0 && reason <= 4 ? reason : 5]++;
                }
            }
            boolean known = hasModuleSnapshot(st);
            lines = new String[]{"连接与模组健康 · 4/4", "终焉引擎 " + link(s.engineLink()) + " · 奈亚 " + link(s.echoLink()),
                    "本机 " + number(s.localModules()) + " · 远端 " + number(s.remoteModules()) + " · 回响 " + number(s.echoModules()),
                    "运行 " + (known ? health[1] : "--") + " · 待机 " + (known ? health[0] : "--"),
                    "缺电 " + (known ? health[2] : "--") + " · 输出满 " + (known ? health[3] : "--"),
                    "受阻 " + (known ? health[4] : "--") + " · 未知 " + (known ? health[5] : "--")};
        } else {
            boolean available = s.known() && s.containment() >= 0;
            var bonus = s.bonus();
            lines = new String[]{"遏制场参数与增益 · 1/4",
                    available ? "并行 ×" + bonus.parallelMultiplier() + " · 耗时 ×" + decimal(bonus.durationMultiplier()) : "并行／耗时 --",
                    available ? "耗电 ×" + decimal(bonus.energyMultiplier()) + " · 额外增产 +" + bonus.extraOutput() : "耗电／额外增产 --",
                    available ? "物质数量 ×" + bonus.matterCountMultiplier() + " · 吞噬 " + decimal(bonus.swallowChance() * 100) + "%" : "数量／吞噬 --",
                    available ? "催化 " + s.catalysis() + " / 30 · 消耗倍率 ×" + compactMagnitude(s.costModifier()) : "催化／消耗倍率 --",
                    s.lockSeconds() > 0 ? "坍缩锁定 " + s.lockSeconds() + "s（主机与模组停机）"
                            : s.containment() == 3 ? "超稳态：不衰减、不消耗时空流体"
                            : s.containment() == 2 ? "失稳不可恢复；安全关闭可避免坍缩"
                            : "遏制场 " + field(s) + " · 总增产 " + (st.productionMultiplier < 0 ? "--" : "×" + st.productionMultiplier)};
        }
        var zone = HostFxGlassArPanel.ZONE_PHYS;
        for (int i = 0; i < lines.length; i++) {
            String line = HostFxGlassArModules.fitText(st, lines[i], px(zone.w - 2 * HostFxGlassArPanel.ZONE_PAD));
            texts.add(new Txt(Z_PHYS, i, px(zone.u + HostFxGlassArPanel.PHYS_TEXT_U),
                    px(zone.v + HostFxGlassArPanel.PHYS_ROW_V0 + i * HostFxGlassArPanel.READ_ROW_DV),
                    line, i == 0 ? C_HEAD : s.lockSeconds() > 0 || s.containment() == 2 ? C_WARN : C_MAIN));
        }
    }

    private static String number(int n) { return n < 0 ? "--" : String.valueOf(n); }

    private static String decimal(double number) {
        return BigDecimal.valueOf(Math.round(number * 100.0) / 100.0).stripTrailingZeros().toPlainString();
    }
}
