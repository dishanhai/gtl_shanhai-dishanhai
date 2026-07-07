package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GTNL 永恒格雷工坊升级树的 1.20.1 状态版。 */
public enum EternalGregTechWorkshopUpgrade {
    START(0, false, "起源节点", "升级树的起点"),
    IGCC(1, false, "集成电路基础", "解锁基础升级路径", "START"),
    STEM(1, false, "蒸汽工程优化", "燃料消耗降低 20%", "IGCC"),
    CFCE(1, false, "因果反馈控制", "燃料因子上限提升 20%", "IGCC"),
    GISS(1, false, "引力惯性稳定", "解锁并行优化路径", "STEM"),
    FDIM(1, false, "流体动力注入", "燃料效率进一步提升", "STEM", "CFCE"),
    SA(1, false, "时空锚定", "解锁高阶升级路径", "CFCE"),
    GPCI(2, false, "引力子处理核心", "里程碑加速 10%", "FDIM"),
    REC(2, true, "递归能量回路", "需要全部前置;解锁量子路径", "GISS", "FDIM"),
    GEM(2, false, "引力能量矩阵", "燃料因子上限 = 基础 + 激活升级数", "GPCI"),
    CTCDD(2, true, "因果时序数据驱动", "需要全部前置;解锁拓扑路径", "GPCI", "SA"),
    QGPIU(2, false, "量子引力子信息单元", "解锁超维路径", "REC", "CTCDD"),
    SEFCP(3, false, "超维能量流控制", "并行数 ×2;三选一分支", "QGPIU"),
    TCT(3, false, "时间晶体拓扑", "时长系数额外 ×0.95;三选一分支", "QGPIU"),
    GGEBE(3, false, "引力-引力能量平衡引擎", "最大 EU/t ×1.5;三选一分支", "QGPIU"),
    TPTP(4, false, "时间相位传输协议", "并行数 ×4", "GGEBE"),
    DOP(4, false, "维度操作协议", "最大 EU/t ×2", "CNTI"),
    CNTI(3, false, "因果网络拓扑接口", "里程碑加速 25%", "SEFCP"),
    EPEC(3, false, "能量-相位能量转换", "EU 折扣额外 ×0.97", "TCT"),
    IMKG(3, false, "信息-物质-引力统一", "并行数 ×8", "GGEBE"),
    NDPE(3, false, "中子维度相位引擎", "最大 EU/t ×3", "CNTI"),
    POS(3, false, "相位有序化系统", "时长系数额外 ×0.90", "EPEC"),
    DOR(3, false, "维度有序化重构", "并行数 ×16", "IMKG"),
    NGMS(4, false, "中子引力矩阵稳定", "全部前置;全属性小幅提升", "NDPE", "POS", "DOR"),
    SEDS(5, false, "超维能量分配系统", "并行数 ×32;里程碑加速 50%", "NGMS"),
    PA(6, false, "相位对齐", "最大 EU/t ×4", "SEDS"),
    CD(7, false, "因果解耦", "EU 折扣额外 ×0.95", "PA"),
    TSE(8, false, "时间-空间工程", "燃料因子上限解除", "CD"),
    TBF(9, false, "拓扑-引力-流体统一", "时长系数额外 ×0.85", "TSE"),
    EE(10, false, "能量-熵统一", "并行数 ×64;全属性显著提升", "TBF"),
    END(12, false, "终焉节点", "解锁引力子碎片弹出;终极升级", "EE");

    public static final EternalGregTechWorkshopUpgrade[] VALUES = values();
    public static final Set<EternalGregTechWorkshopUpgrade> SPLIT_UPGRADES = Set.of(SEFCP, TCT, GGEBE);

    private static final EnumMap<EternalGregTechWorkshopUpgrade, EternalGregTechWorkshopUpgrade[]> DEPENDENTS =
            new EnumMap<>(EternalGregTechWorkshopUpgrade.class);

    private final int shardCost;
    private final boolean requireAllPrerequisites;
    private final String[] prerequisiteNames;
    private final String shortDesc;
    private final String effectDesc;
    private EternalGregTechWorkshopUpgrade[] prerequisites;

    EternalGregTechWorkshopUpgrade(int shardCost, boolean requireAllPrerequisites,
                                   String shortDesc, String effectDesc,
                                   String... prerequisites) {
        this.shardCost = Math.max(0, shardCost);
        this.requireAllPrerequisites = requireAllPrerequisites;
        this.shortDesc = shortDesc;
        this.effectDesc = effectDesc;
        this.prerequisiteNames = prerequisites == null ? new String[0] : prerequisites;
        this.prerequisites = new EternalGregTechWorkshopUpgrade[0];
    }

    public int shardCost() {
        return shardCost;
    }

    public boolean requiresAllPrerequisites() {
        return requireAllPrerequisites;
    }

    public EternalGregTechWorkshopUpgrade[] prerequisites() {
        return prerequisites;
    }

    public EternalGregTechWorkshopUpgrade[] dependents() {
        return DEPENDENTS.getOrDefault(this, new EternalGregTechWorkshopUpgrade[0]);
    }

    public String shortName() {
        return name();
    }

    public String getShortDesc() {
        return shortDesc;
    }

    public String getEffectDesc() {
        return effectDesc;
    }

    public String displayName() {
        return "fog.upgrade." + name().toLowerCase(java.util.Locale.ROOT);
    }

    static {
        for (EternalGregTechWorkshopUpgrade upgrade : VALUES) {
            EternalGregTechWorkshopUpgrade[] prerequisites = new EternalGregTechWorkshopUpgrade[upgrade.prerequisiteNames.length];
            for (int i = 0; i < upgrade.prerequisiteNames.length; i++) {
                prerequisites[i] = EternalGregTechWorkshopUpgrade.valueOf(upgrade.prerequisiteNames[i]);
            }
            upgrade.prerequisites = prerequisites;
        }
        EnumMap<EternalGregTechWorkshopUpgrade, List<EternalGregTechWorkshopUpgrade>> dependencies =
                new EnumMap<>(EternalGregTechWorkshopUpgrade.class);
        for (EternalGregTechWorkshopUpgrade upgrade : VALUES) {
            for (EternalGregTechWorkshopUpgrade prerequisite : upgrade.prerequisites) {
                dependencies.computeIfAbsent(prerequisite, ignored -> new ArrayList<>()).add(upgrade);
            }
        }
        for (Map.Entry<EternalGregTechWorkshopUpgrade, List<EternalGregTechWorkshopUpgrade>> entry : dependencies.entrySet()) {
            DEPENDENTS.put(entry.getKey(), entry.getValue().toArray(new EternalGregTechWorkshopUpgrade[0]));
        }
    }
}
