package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>倒流覆盖矩阵（纯逻辑核）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径</h2>
 * <blockquote>「我感觉你的覆盖范围太小了，我要覆盖整个游戏内所有的内容都覆盖到，但是可能会漏，这个我们走一步看一步吧」</blockquote>
 * ⇒ 交付物不是一个"全覆盖"的口号，而是一张<b>逐类别的矩阵</b>：每一行 = 类别 / 层级 / 判据 / 已知漏点。
 * 它由这个类生成（{@code /shanhai_add time coverage} 与日志
 * {@code [SHANHAI-REPLAY] coverage …} 共用同一份数据，不许两套）。
 *
 * <h2>2. 🔴 "已覆盖"的定义（否则矩阵就是自吹）</h2>
 * {@link Row#covered()} 只能是 {@code true} 或 {@code false}，<b>没有"大概覆盖"</b>。
 * 而且每一行标 {@code covered=true} 的类别，都必须在
 * {@code tools\replay-selfcheck\ReplaySelfCheck} 里有一条<b>离线断言</b>（M 组逐行核对
 * {@code proven} 集合）—— 没有断言就不许标已覆盖（判据 ⑦）。
 *
 * <h2>3. 三层（第 2 层"周期整块快照"已被用户否掉，这里没有它）</h2>
 * <table border="1">
 *   <caption>层级分工</caption>
 *   <tr><th>层</th><th>机制</th><th>连续性</th></tr>
 *   <tr><td>L1</td><td>逐 tick 事件差分：方块（{@code LevelChunk#setBlockState}）/
 *       方块实体（{@code setChanged} 打标记 → tick 末最小差）/ 机器进度瘦日志 / 实体出现消失 / 世界标量</td>
 *       <td><b>每 tick 连续</b></td></tr>
 *   <tr><td>L3</td><td>世界级标量（时间 / 天气 / 雷暴 / 游戏规则观测）</td><td>每 tick 连续</td></tr>
 *   <tr><td>L4</td><td>存档级 SavedData 名单（只覆盖能安全写回的：计分板 / 队伍）</td><td>名单级</td></tr>
 * </table>
 */
public final class ShanhaiReplayCoverage {

    /** 一层/多层。 */
    public static final class Layers {
        public static final String L1 = "L1";
        public static final String L3 = "L3";
        public static final String L4 = "L4";
        public static final String NONE = "-";

        private Layers() {
        }
    }

    /**
     * 矩阵的一行。
     *
     * @param id       稳定 id（自检按它核对"有没有断言"；别改）
     * @param category 给人看的类别名（中文）
     * @param layer    覆盖层级（{@link Layers}）
     * @param covered  是否已覆盖（{@code true} 必须有一条离线断言）
     * @param criterion 判据（进游戏看什么 / 自检断言什么）
     * @param gap      已知漏点（{@code covered=false} 时必须写清为什么）
     */
    public record Row(String id, String category, String layer, boolean covered,
                      String criterion, String gap) {

        public int coveredFlag() {
            return this.covered ? 1 : 0;
        }

        /** 一行日志（字段顺序稳定，便于 grep）。 */
        public String toLine() {
            return "coverage id=" + this.id + " covered=" + coveredFlag() + " layer=" + this.layer
                    + " category=" + this.category + " criterion=" + this.criterion
                    + " gap=" + (this.gap.isEmpty() ? "-" : this.gap);
        }
    }

    /** 🔴 唯一的一份矩阵（命令 / 日志 / 自检都读它）。 */
    private static final List<Row> ROWS = List.of(
            // ---------------------------------------------------------- 第 1 层：方块
            new Row("block.place", "方块放置", Layers.L1, true,
                    "LevelChunk#setBlockState HEAD 收；自检 H1 断言 from=air/after=stone 被记",
                    ""),
            new Row("block.break", "方块破坏", Layers.L1, true,
                    "同一条入口（Level#destroyBlock→removeBlock→setBlock）；自检 H2 断言 from=stone/after=air 被记，"
                            + "且回退时\"已经是空气\"不许被当成写失败（A1 根因）",
                    ""),
            new Row("block.explosion", "爆炸破坏", Layers.L1, true,
                    "Explosion→Level#setBlock(AIR)；自检 H3 与破坏同构",
                    ""),
            new Row("block.fluid", "流体（放置/流动/排空）", Layers.L1, true,
                    "流体方块本身就是 BlockState ⇒ 走同一条入口；液体在方块实体里（储罐）走 be.setChanged",
                    "流动流体的 tick 级细节（流动方向/level 属性）只按状态索引回退，不重放流动过程"),
            new Row("block.other", "第三方 mod 的方块变化（活塞/掉落方块/多方块成型）", Layers.L1, true,
                    "只要它最终写 BlockState 就走同一条入口",
                    "只写数据不写 BlockState 的（例如多方块结构登记表）不在这一层"),
            new Row("block.worldgen", "世界生成 / 新地形", Layers.L1, false,
                    "世界生成走 WorldGenRegion/ProtoChunk，不经过 LevelChunk#setBlockState",
                    "倒流不撤销已生成的地形（那是另一套系统：区块回档）"),
            // ---------------------------------------------------------- 第 1 层：方块实体
            new Row("be.setChanged", "方块实体（会调 setChanged 的）", Layers.L1, true,
                    "BlockEntity#setChanged HEAD 打标记 → tick 末只差被标记的 BE（顶层 key 最小差）；自检 L 组",
                    ""),
            new Row("be.silent", "方块实体（不调 setChanged 的）", Layers.L1, false,
                    "没有触发点 ⇒ 这一 tick 不会被差分",
                    "🔴 已知漏点：不调 setChanged 的第三方 BE 完全漏（用户明确要求不许用周期整块快照去补，"
                            + "因为那会让世界看起来不连续）"),
            new Row("be.container", "容器（箱子/桶/机器库存）", Layers.L1, true,
                    "容器 slot 变化会 setChanged ⇒ 顶层 key 差分（Items 整表）",
                    "整表粒度：改 1 格也会存一整张 Items（内存换简单，见预算）"),
            new Row("machine.progress", "机器进度条（配方进度）", Layers.L1, true,
                    "瘦日志 (pos,before,after) + 回退时写回；GT 走 RecipeLogic#getProgress/setProgress；自检 J 组",
                    "没有可写进度字段的机器会被记成 progress_unsupported 并在报告里点名"),
            new Row("machine.mt", "GT 多方块（MT/控制器/部件）", Layers.L1, true,
                    "控制器与部件的持久化状态在 BE NBT 里 ⇒ be.setChanged 层",
                    "多方块\"结构重建\"本身（扫描成形）不重放；结构状态若只在内存里则漏"),
            new Row("machine.ae", "AE2 网络内容（ME 网络里的物品/流体）", Layers.NONE, false,
                    "内容在网络（IStorageService）里，不在方块里",
                    "🔴 明确不覆盖：倒流不会把 ME 网络里的东西变回去；只有\"某个方块被挖/放\"这件事本身会被回退"),
            new Row("machine.energy", "能量/温度/激光等不落盘的瞬时量", Layers.NONE, false,
                    "只存在于内存字段，且没有 setChanged",
                    "🔴 不覆盖：GT 的 EU 缓存/温度是每 tick 变的瞬时量，回退后按当前状态继续（不还原）"),
            // ---------------------------------------------------------- 第 1 层：实体
            new Row("entity.mob", "生物（LivingEntity）", Layers.L1, true,
                    "EntityJoinLevelEvent / 移除扫描；自检 H4/H5（后出现的删掉、后消失的按 NBT 重建）",
                    ""),
            new Row("entity.item", "掉落物（ItemEntity）", Layers.L1, true,
                    "同一套实体记录（不分类型，只要不是玩家）", ""),
            new Row("entity.xp", "经验球（ExperienceOrb）", Layers.L1, true,
                    "同一套实体记录", ""),
            new Row("entity.projectile", "投掷物 / 箭 / 火球", Layers.L1, true,
                    "同一套实体记录", "飞行轨迹不重放，只回退\"存在性\"与 NBT"),
            new Row("entity.vehicle", "载具（矿车/船）", Layers.L1, true,
                    "同一套实体记录", ""),
            new Row("entity.hanging", "画 / 拴绳结 / 展示框", Layers.L1, true,
                    "同一套实体记录", ""),
            new Row("entity.player", "玩家", Layers.NONE, false,
                    "🔴 设计上豁免：不记、不动",
                    "玩家位置/背包/状态由玩家自己决定（用户口径：玩家的时间不受影响）"),
            new Row("entity.particle", "粒子 / 音效（不是实体）", Layers.NONE, false,
                    "不是 Entity，没有可回退的状态",
                    "不适用（客户端表现，倒流期间本就被冻住）"),
            // ---------------------------------------------------------- 第 3 层：世界标量
            new Row("scalar.daytime", "白天时间（dayTime）", Layers.L3, true,
                    "每 tick 比对；【已节流】稳态 +1 不记（只记差 != +1 的跳变），收尾按退掉的 tick 数一次性回拨；自检 O1/O2",
                    ""),
            new Row("scalar.weather", "天气（下雨 / 雷暴开关）", Layers.L3, true,
                    "每 tick 比对；【已节流】只有翻转才记、只有真变才写（同 tick 最多一次 setWeatherParameters）；自检 P1/P2/P3",
                    "雨强（0..1 插值量）不记不写：没有直接设值的公开 API，只回退两个开关、由原版自己插值"),
            new Row("scalar.gamerule", "游戏规则", Layers.L3, true,
                    "整份快照（GameRules#createTag）+ 整份写回（assignFrom）；自检 K3",
                    "逐条写回没有公开 API（Value#deserialize 是 protected）⇒ 用整份快照代替，原子性更好"),
            // ---------------------------------------------------------- 第 4 层：存档级
            new Row("savedata.scoreboard", "计分板 / 队伍", Layers.L4, true,
                    "SavedData 名单里的可安全写回项（scoreboard.dat）；命令与日志里列名单",
                    "整服级：回退是\"整份计分板回到过去\"，不是逐条 diff"),
            new Row("savedata.other", "其它 SavedData（map/raids/forge 自定义）", Layers.L4, false,
                    "只列名单（level.dat + data/*.dat），不写回",
                    "🔴 不覆盖：格式由各 mod 自己定义，写回风险高（写坏存档）"),
            new Row("savedata.levelDat", "level.dat（种子/难度/允许指令等）", Layers.L4, false,
                    "只读（难度/生成器在启动时就定了）",
                    "🔴 不覆盖：改它等于改存档元信息")
    );

    /** 全部行（顺序稳定）。 */
    public static List<Row> rows() {
        return ROWS;
    }

    public static Row byId(String id) {
        for (Row r : ROWS) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** 已覆盖的行数。 */
    public static int coveredCount() {
        int n = 0;
        for (Row r : ROWS) {
            if (r.covered()) {
                ++n;
            }
        }
        return n;
    }

    /** 未覆盖（已知漏点）的行数。 */
    public static int gapCount() {
        return ROWS.size() - coveredCount();
    }

    /** 一行总计（日志/命令用）。 */
    public static String summary() {
        return "coverage_total=" + ROWS.size() + " covered=" + coveredCount() + " gaps=" + gapCount();
    }

    /** 逐行日志（{@code [SHANHAI-REPLAY] coverage …}）。 */
    public static List<String> lines() {
        final List<String> out = new ArrayList<>(ROWS.size() + 1);
        for (Row r : ROWS) {
            out.add(r.toLine());
        }
        out.add(summary());
        return out;
    }

    /** 只给未覆盖项（命令里"\u5df2\u77e5\u6f0f\u70b9"一节）。 */
    public static List<String> gapLines() {
        final List<String> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (!r.covered()) {
                out.add(r.id() + " · " + r.category() + " · " + r.gap());
            }
        }
        return out;
    }

    /** 已覆盖行的 id 集合（自检用它核对"每一条都有断言"）。 */
    public static List<String> coveredIds() {
        final List<String> out = new ArrayList<>();
        for (Row r : ROWS) {
            if (r.covered()) {
                out.add(r.id());
            }
        }
        return out;
    }

    private ShanhaiReplayCoverage() {
    }
}
