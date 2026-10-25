package com.shanhai.client.render.hostfx;

import com.mojang.blaze3d.vertex.PoseStack;

import com.shanhai.ShanhaiMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归墟视界枢核 · <b>跟着转的三批东西</b>：内环带（真方块烘焙）＋ 4 座物质解压机（自绘）＋ 8 艘飞船（真方块烘焙）。
 *
 * <h2>🔴 2026-10-25 第三改：三组<b>各自独立</b>——内环 / 上 4 艘 / 下 4 艘，三份缓冲、三个角速度</h2>
 * 用户口径（要求 ①）：「外圈选择的 8 个小飞船可以角速度和内环不一样」
 * <ul>
 *   <li>三份 {@link HostFxBaked}：{@link #ring} / {@link #shipUp} / {@link #shipDown}
 *       ⇒ 各有一套 {@code VertexBuffer}，<b>不是同一份静态 VBO</b>；</li>
 *   <li>三个角速度常量：{@link HostFxGeometry#RING_DEG_PER_SEC}（12）、
 *       {@link HostFxGeometry#SHIP_UP_DEG_PER_SEC}（+9）、{@link HostFxGeometry#SHIP_DOWN_DEG_PER_SEC}（−9）
 *       ⇒ 想改哪一组就改哪一个，互不牵连；</li>
 *   <li>判据：{@link HostFxGeometry#selfCheckGroupAngles()} 逐秒比对三者两两不相等、上下异号，
 *       首次烘焙时打一行 {@code group_angles_ok}（或 {@code group_angles_mismatch}）。</li>
 * </ul>
 *
 * <h2>2026-10-25 首批改动（仍有效）</h2>
 * 飞船体积更小、建模更精细（后来整批改成真方块），真方块那 888 格照旧被 {@link HostFxHide} 藏成 AIR（不留残影）。
 *
 * <h2>2026-10-25 第二批：4 条辐条 → 4 座物质解压机</h2>
 * 辐条的 224 格（去重）<b>不再烘进环缓冲</b>，由 {@link HostFxDraw#drawMatterDecompressors} 画 4 座机器；
 * 机器与环<b>同一个枢轴、同一个自转角</b>（它们取代的那批格本来是烘在环缓冲里跟着转的）。
 *
 * <h2>内环带还是"真方块跟着转"</h2>
 * 台账（{@link HostFxHide}）里那些格烘成静态顶点缓冲（{@link HostFxBaked}），
 * 每帧套一个 PoseStack 重画一遍 —— 与伪神之锻炉那三个环同构。
 *
 * <h2>⚠️ 只在渲染线程用</h2>
 * 见 {@link HostFxBaked} 的类注释。
 */
public final class HostFxRingGroups {

    private HostFxRingGroups() {}

    /** 内环带的烘焙结果（真方块）。 */
    private static HostFxBaked ring;
    /** 🔴 2026-10-24 傍晚：上组 4 艘飞船（<b>真方块烘焙</b>，用户「就用原来的方块就行」）。 */
    private static HostFxBaked shipUp;
    /** 下组 4 艘飞船（同上）。 */
    private static HostFxBaked shipDown;

    /** 当前烘焙对应的 key（主机 + 朝向 + 台账版本）。 */
    private static long bakedKey = Long.MIN_VALUE;

    /** 台账格数（判据②：台账格数 == 要转的格数）。 */
    private static int ledgerCells;
    /** 台账按"本地 y"分出来的三组格数（环 / 上舰 / 下舰）。 */
    private static int cellsRing;
    private static int cellsUp;
    private static int cellsDown;
    /**
     * 台账里属于 <b>4 条辐条</b>的格数 —— 🔴 2026-10-25：这批<b>不再烘进环缓冲</b>，
     * 画面上由 {@link HostFxDraw#drawMatterDecompressors} 的 4 座物质解压机取代。
     *
     * <p>为什么必须从烘焙里剔掉（不是多此一举）：辐条 240 格里有 <b>96 格</b>的本地 |y| ≥ 3，
     * 按 {@link HostFxGeometry#groupOf} 会被判成"上/下飞船组" ⇒ 以前只藏不烘（画面上是 4 个洞）；
     * 另外 144 格是烘着的 —— 若留着，它们会从机器内部穿出来，看起来像"机器里卡着一堆方块"。
     */
    private static int cellsArms;
    /**
     * 台账里属于<b>内环摘除段</b>的格数（用户第 4 条：「红框那段内环方块可以不渲染」）。
     *
     * <p>🔴 2026-10-25 第五改：这 4 段（4 个喷口各一段，见 {@link HostFxGeometry#mdMouthCutCells()}）
     * <b>继续藏在台账里</b>（世界里的真方块写成 AIR），但<b>不再烘进环缓冲</b> ——
     * 否则它们会跟着环转到机器口前面，就是用户截图里那段"内环玻璃方块"。
     * 这几个位置由解压机前部（{@link HostFxGeometry#mdFrontBoxes()}，与 {@code drawMatterDecompressors}
     * 同一份几何）填上。
     */
    private static int cellsMouthCut;
    /** 判据②：三组格数之和 == 台账格数。 */
    private static boolean groupsMatchLedger;
    /** 判据②的一半（坐标部分）：台账本地坐标能否还原回原来那一格；null = 全对。 */
    private static String roundTripMismatch;
    /** 判据①：真的烘进顶点缓冲的格数（应 == {@link #cellsRing}）。 */
    private static int ringBakedBlocks;
    /** 判据①：上一次烘焙出来的环面片数。 */
    private static int ringBakedQuads;

    /** 上一帧真正提交了哪些东西（{@code none} / {@code ring} / {@code ring+ships} / {@code ships}）。 */
    private static String lastGroups = "none";

    /** 🔴 已作废：自绘飞船方盒数（恒 0）；保留只为日志里能一眼看出"没有再自绘" 。 */
    private static int lastShipBoxes;
    /** 上一帧飞船<b>烘焙块</b>数（上组 + 下组，应为 888 = 444×2）。 */
    private static int lastShipBlocks;
    /** 上一帧飞船烘焙面数。 */
    private static int lastShipQuads;

    /** 上一帧画出去的解压机方盒数（4 座 × {@link HostFxGeometry#mdBoxCount()}）。 */
    private static int lastMdBoxes;

    /**
     * 上一帧提交了哪些组。
     *
     * <p>判据：降级到 {@code LEVEL_SIMPLE} 时也必须是 {@code ring+md+ships} ——
     * 飞船与辐条的真方块都已经被藏成 AIR，少画一组就留一个洞（见 {@code HostFxFallback} 的类注释）。
     */
    public static String groups() {
        return lastGroups;
    }

    /** 账本格数（日志/判据②）。 */
    public static int ledgerCells() {
        return ledgerCells;
    }

    public static int cellsRing() {
        return cellsRing;
    }

    public static int cellsUp() {
        return cellsUp;
    }

    public static int cellsDown() {
        return cellsDown;
    }

    /** 台账里被 4 座解压机取代的辐条格数（判据②的一档）。 */
    public static int cellsArms() {
        return cellsArms;
    }

    /** 台账里被"摘出环渲染"的内环段格数（第五改；这 4 段由解压机前部填上）。 */
    public static int cellsMouthCut() {
        return cellsMouthCut;
    }

    /**
     * 一组格子在<b>本地整数网格</b>里的键（与 {@link HostFxBaked.Cell} 的 {@code gx/gy/gz} 同一口径）。
     *
     * <p>换算路径与烘焙完全一样（{@code worldFromGenerator → worldToLocal → round(l + CELL_CENTER)}），
     * 所以这里拿到的键与 {@code cells()} 里的键<b>逐格可比</b>（不是"另算一套坐标"）。
     */
    private static Set<Long> gridKeys(List<BlockPos> generatorCells, BlockPos controllerPos,
                                      Direction facing) {
        final Set<Long> out = new HashSet<>(generatorCells.size() * 2);
        for (BlockPos gen : generatorCells) {
            final BlockPos world = HostFxGeometry.worldFromGenerator(controllerPos, facing,
                    gen.getX(), gen.getY(), gen.getZ());
            final float[] l = HostFxGeometry.worldToLocal(controllerPos, facing, world);
            out.add(key(Math.round(l[0] + HostFxGeometry.CELL_CENTER),
                    Math.round(l[1] + HostFxGeometry.CELL_CENTER),
                    Math.round(l[2] + HostFxGeometry.CELL_CENTER)));
        }
        return out;
    }

    /** 4 条辐条在本地整数网格里的键。 */
    private static Set<Long> armGridKeys(BlockPos controllerPos, Direction facing) {
        return gridKeys(HostFxGeometry.armCells(), controllerPos, facing);
    }

    private static long gridKey(HostFxBaked.Cell c) {
        return key(c.gx(), c.gy(), c.gz());
    }

    private static long key(long x, long y, long z) {
        return ((x & 0x1FFFFFL) << 42) | ((y & 0x1FFFFFL) << 21) | (z & 0x1FFFFFL);
    }

    /** 判据②：三组格数之和 == 台账格数。 */
    public static boolean groupsMatchLedger() {
        return groupsMatchLedger;
    }

    /** 判据①：烘焙块数 == 环带格数。 */
    public static boolean ringBakedMatches() {
        return ringBakedBlocks == cellsRing;
    }

    public static int ringBakedBlocks() {
        return ringBakedBlocks;
    }

    public static int ringBakedQuads() {
        return ringBakedQuads;
    }

    /** 上一帧的飞船方盒数（恒 0：自绘模型已撤）。 */
    public static int lastShipBoxes() {
        return lastShipBoxes;
    }

    /** 上一帧飞船<b>烘焙</b>的块数（判据：应 == {@link #cellsUp()} + {@link #cellsDown()}）。 */
    public static int lastShipBlocks() {
        return lastShipBlocks;
    }

    /** 判据①：飞船烘焙块数 == 台账里上/下两组的格数。 */
    public static boolean shipBakedMatches() {
        return lastShipBlocks == cellsUp + cellsDown;
    }

    /** 判据②的一半（坐标部分）：null = 全对。 */
    public static String roundTripMismatch() {
        return roundTripMismatch;
    }

    /** 是否已经有可画的烘焙结果。 */
    public static boolean ready() {
        return ring != null;
    }

    /** "光柱起点 = 喷口中心"这条自检只打一次。 */
    private static boolean selfCheckLogged;

    /**
     * 确保内环带烘好了（同一 key 直接返回）。
     *
     * @return false = 台账还没建起来（这一帧别画）
     */
    public static boolean ensureBaked(BlockPos controllerPos, Direction facing) {
        final Map<Long, BlockState> ledger = HostFxHide.ledger(controllerPos);
        if (ledger == null || ledger.isEmpty()) {
            return false;
        }
        final long key = controllerPos.asLong() * 1_000_003L + facing.ordinal() * 31L + HostFxHide.stamp();
        if (key == bakedKey && ready()) {
            return true;
        }
        clear();
        ledgerCells = ledger.size();
        final List<HostFxBaked.Cell> all = HostFxBaked.cells(ledger, controllerPos, facing);
        roundTripMismatch = HostFxBaked.roundTripMismatch(all, controllerPos, facing);
        final Set<Long> armKeys = armGridKeys(controllerPos, facing);
        // 🔴 2026-10-25 第五改：内环靠 4 个喷口的那 4 段【不再烘进环】——
        //    它们的格仍藏在台账里（世界 = AIR），但画面上由解压机前部（mdFrontBoxes）填上。
        final Set<Long> mouthKeys = gridKeys(HostFxGeometry.mdMouthCutCells(), controllerPos, facing);
        final List<HostFxBaked.Cell> g0 = new ArrayList<>(all.size());
        final List<HostFxBaked.Cell> gUp = new ArrayList<>(512);
        final List<HostFxBaked.Cell> gDown = new ArrayList<>(512);
        int arms = 0;
        int mouthCut = 0;
        for (HostFxBaked.Cell c : all) {
            // 🔴 辐条那批先摘出去（它们由 4 座解压机取代，见 cellsArms 的注释）
            if (armKeys.contains(gridKey(c))) {
                arms++;
                continue;
            }
            // 🔴 内环摘除段：只藏不烘（由解压机前部取代，见 cellsMouthCut 的注释）
            if (mouthKeys.contains(gridKey(c))) {
                mouthCut++;
                continue;
            }
            switch (HostFxGeometry.groupOf(c.ly())) {
                case 1 -> gUp.add(c);
                case 2 -> gDown.add(c);
                default -> g0.add(c);
            }
        }
        cellsRing = g0.size();
        cellsUp = gUp.size();
        cellsDown = gDown.size();
        cellsArms = arms;
        cellsMouthCut = mouthCut;
        // 判据②：五组格数之和 == 台账格数（对数，不是"三个 list 加起来"那种恒真校验）
        groupsMatchLedger = (cellsRing + cellsArms + cellsMouthCut + cellsUp + cellsDown) == ledgerCells;
        ring = HostFxBaked.bake(HostFxGeometry.groupName(0), g0);
        // 🔴 2026-10-24 傍晚：8 艘飞船回到「真方块跟着转」—— 上组/下组各烘一份，
        //    角速度仍是 +9°/s / −9°/s（HostFxGeometry.shipOrbitDeg），位置就在真方块原来的
        //    r 61..65 上（生成器 shipRadialCenter = 63）。不再有任何自绘舰体。
        shipUp = HostFxBaked.bake(HostFxGeometry.groupName(1), gUp);
        shipDown = HostFxBaked.bake(HostFxGeometry.groupName(2), gDown);
        // 判据①：**真的烘进顶点缓冲的格数** == 各组格数（某个方块模型取不到时这里会 false）
        ringBakedBlocks = ring.blocks();
        ringBakedQuads = ring.quads();
        bakedKey = key;
        if (!selfCheckLogged) {
            selfCheckLogged = true;
            final String bad = HostFxGeometry.selfCheckNozzleBeam();
            if (bad == null) {
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] nozzle_beam_ok 判据：4 条主光柱起点 == "
                                + "4 座解压机喷口中心（容差 1e-5），喷口 r={} 格、倾角 {}°、末端偏差 0.000000",
                        String.format(java.util.Locale.ROOT, "%.3f", HostFxGeometry.mdNozzleRadius()),
                        HostFxGeometry.MD_TILT_DEG);
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] nozzle_beam_mismatch {}", bad);
            }
            // 🔴 2026-10-25 第三改：三组角速度 / 解压机件数 / 细紫光束 各一条一次性自检
            final String badAngles = HostFxGeometry.selfCheckGroupAngles();
            if (badAngles == null) {
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] group_angles_ok 判据：内环 {}°/s ≠ 上舰 {}°/s ≠ 下舰 {}°/s"
                                + "（三组各自独立烘焙、各自独立 spin，互不相等且上下对转）",
                        HostFxGeometry.RING_DEG_PER_SEC, HostFxGeometry.SHIP_UP_DEG_PER_SEC,
                        -HostFxGeometry.SHIP_DOWN_DEG_PER_SEC);
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] group_angles_mismatch {}", badAngles);
            }
            final String badParts = HostFxGeometry.selfCheckMdParts();
            if (badParts == null) {
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] md_parts_ok 判据：每座 {} 件（≥{}，单件最大半长 {} 格），"
                                + "横置后仍在红框 11×11×11 内",
                        HostFxGeometry.mdBoxCount(), HostFxGeometry.MD_MIN_BOXES,
                        String.format(java.util.Locale.ROOT, "%.2f", HostFxGeometry.mdMaxHalf()));
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] md_parts_mismatch {}", badParts);
            }
            final String badThin = HostFxGeometry.selfCheckThinBeams();
            if (badThin == null) {
                // 🔴 第七改：细光束改成"每座解压机 3 条"，所以判据行的读法整体换了
                final float[] dir = HostFxGeometry.thinBeamDirection();
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] thin_beams_ok 判据：{} 条细光束 = 4 座 × {} 条，"
                                + "每座绕<b>本座喷口轴</b> r={} 格、互成 120.000°（120/120/120），"
                                + "每条方向与本座主柱方向夹角 0.000000°（0 号机方向 {}）、"
                                + "起点 == 该座发射器口中心、色 {}、峰值半径 {} 格",
                        HostFxGeometry.THIN_BEAM_COUNT, HostFxGeometry.THIN_BEAMS_PER_MD,
                        HostFxGeometry.THIN_BEAM_RING_R,
                        HostFxGeometry.vecText(dir),
                        HostFxGeometry.rgbText(HostFxGeometry.THIN_BEAM_COLOR),
                        HostFxGeometry.THIN_BEAM_PEAK_RADIUS);
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] thin_beams_mismatch {}", badThin);
            }
            // 🔴 第七改：4 条解压机主喷口光柱 = 伪神同款（与中央两根同一套）
            final String badMdBeam = HostFxGeometry.selfCheckMdBeams();
            if (badMdBeam == null) {
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] md_beam_ok 判据：4 条解压机主柱 = 伪神同款 —— "
                                + "外层 {}→{} 格（区间 1.2~1.6、比中央 {} 细）、内芯 ×{} = {}→{} 格、"
                                + "2 遍 Intensity 2.0/4.0、发光色 {}（可配，与中央那 2 根互不影响）、"
                                + "起点 == 喷口、末端 == 洞面",
                        HostFxGeometry.MD_BEAM_OUTER_R0, HostFxGeometry.MD_BEAM_OUTER_R1,
                        HostFxGeometry.PILLAR_OUTER_R0, HostFxGeometry.MD_BEAM_INNER_SCALE,
                        HostFxGeometry.MD_BEAM_OUTER_R0 * HostFxGeometry.MD_BEAM_INNER_SCALE,
                        HostFxGeometry.MD_BEAM_OUTER_R1 * HostFxGeometry.MD_BEAM_INNER_SCALE,
                        HostFxGeometry.rgbText(HostFxGeometry.mdBeamGlowRgb()));
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] md_beam_mismatch {}", badMdBeam);
            }
            final String badPillar = HostFxGeometry.selfCheckPillar();
            if (badPillar == null) {
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] pillar_ok 判据：上下两根同一套 —— 外层 {}→{} 格、"
                                + "内芯 ×{} = {}→{} 格（改前 0.62→0.30）、2 遍 Intensity 2.0/4.0、"
                                + "伪神 shader + space_layer 贴图",
                        HostFxGeometry.PILLAR_OUTER_R0, HostFxGeometry.PILLAR_OUTER_R1,
                        HostFxGeometry.PILLAR_INNER_SCALE,
                        HostFxGeometry.PILLAR_OUTER_R0 * HostFxGeometry.PILLAR_INNER_SCALE,
                        HostFxGeometry.PILLAR_OUTER_R1 * HostFxGeometry.PILLAR_INNER_SCALE);
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] pillar_mismatch {}", badPillar);
            }
            final String badCut = HostFxGeometry.selfCheckMouthCut();
            if (badCut == null) {
                final float[] fr = HostFxGeometry.mdFrontRadialRange();
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] mouth_cut_ok 判据：内环摘除 {} 段共 {} 格"
                                + "（每段切向半宽 {} 格、径向 r=47/48/49 三层），由解压机前部 {} 件"
                                + "（径向 {}..{} 格）填上",
                        HostFxGeometry.MD_COUNT, HostFxGeometry.mdMouthCutCells().size(),
                        HostFxGeometry.MD_MOUTH_CUT_HALF, HostFxGeometry.mdFrontBoxCount(),
                        String.format(java.util.Locale.ROOT, "%.2f", fr[0]),
                        String.format(java.util.Locale.ROOT, "%.2f", fr[1]));
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] mouth_cut_mismatch {}", badCut);
            }
        }
        return true;
    }

    /**
     * 画：内环带（真方块烘焙）＋ 4 座物质解压机 ＋ 8 艘飞船。<b>同一个枢轴</b>，但<b>角速度按组各算</b>。
     *
     * <p>pose 顺序：同一句 {@code pivot(cx,cy,cz)} → 环带/解压机 {@code spin(ringDeg)} →
     * 上组飞船 {@code spin(shipOrbitDeg(t,true)=+9t)} → 下组 {@code spin(shipOrbitDeg(t,false)=−9t)}。
     * 🔴 别再写成"环与舰同角速度"：2026-10-25 用户否掉了那一版。
     *
     * <h2>🔴 2026-10-25 第二十改：{@code spinSeconds} 是【动画时钟】，不是世界时间</h2>
     * 调用方传的是 {@link HostFxSwitch#spinSeconds()}（= {@code HostFxState.animSeconds()}）：
     * <ul>
     *   <li>特效开 ⇒ 按墙钟累加，环 12°/s、舰 ±9°/s 与改前<b>逐帧相同</b>；</li>
     *   <li>特效关（降级态）⇒ 那条时钟<b>冻结</b> ⇒ 本方法每一句 {@code spin} 都收到同一个角度
     *       ⇒ <b>环与舰原地不动、但仍完整绘制</b>（用户原话：「关闭之后……内环和 8 个小飞船不转」）；</li>
     *   <li>🔴 冻结值是<b>上一次的相位</b>，不是 0 —— 传 0 会让它们"瞬移回初始姿态"，
     *       那是这一步唯一要避免的形态（{@code HostFxState.animSeconds} 的两条不许改的语义）。</li>
     * </ul>
     *
     * @param ships 是否连飞船一起画；<b>现在恒为 true</b>（飞船真方块已被藏成 AIR，少画一组就留一个洞）
     * @param spinSeconds 自转时钟（秒）= {@link HostFxSwitch#spinSeconds()}；<b>降级态是冻结值</b>
     */
    public static void render(PoseStack poseStack, Direction facing, float cx, float cy, float cz,
                              float spinSeconds, boolean ships) {
        lastGroups = "none";
        lastShipBoxes = 0;
        final StringBuilder submitted = new StringBuilder(16);
        poseStack.pushPose();
        try {
            HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
            if (ring != null && !ring.isEmpty()) {
                poseStack.pushPose();
                HostFxRingTransforms.spin(poseStack, HostFxGeometry.ringDeg(spinSeconds));
                // 加厚箍 + 配重脊：与环带<b>同一个自转角</b>，补足"3 层 → ≈5 层"的体量
                HostFxDraw.drawRingExtras(poseStack);
                ring.render(poseStack);
                poseStack.popPose();
                submitted.append("ring");
            }
            // 🔴 4 座物质解压机：它们替换的是 4 条辐条（那批格已从环缓冲里摘掉），
            //    所以必须与环带<b>同一个枢轴、同一个自转角</b> —— 与辐条原来的转向逐角度一致。
            poseStack.pushPose();
            try {
                HostFxRingTransforms.spin(poseStack, HostFxGeometry.ringDeg(spinSeconds));
                lastMdBoxes = HostFxDraw.drawMatterDecompressors(poseStack);
            } finally {
                poseStack.popPose();
            }
            submitted.append(submitted.length() == 0 ? "md" : "+md");
            if (ships) {
                posedShipDraw(poseStack, spinSeconds);
                submitted.append(submitted.length() == 0 ? "ships" : "+ships");
            } else {
                // 不画飞船也必须<b>先把真方块还原</b>？不需要：ship buffers 与 ring 同一份台账，
                // 台账还在就意味着真方块仍被藏着；这时跳过画舰 = 世界缺两块。所以 ships 恒为 true
                // 由调用方保证（见 HostFxFallback.drawShips），这里只做记录。
                submitted.append(submitted.length() == 0 ? "ships_skipped" : "+ships_skipped");
            }
        } finally {
            poseStack.popPose();
        }
        lastGroups = submitted.length() == 0 ? "none" : submitted.toString();
    }

    /**
     * 8 艘飞船：<b>上组 +9°/s、下组 −9°/s</b>，<b>画的是烘焙好的真方块</b>。
     *
     * <h2>🔴 2026-10-24 傍晚用户原话：「8 艘飞船不需要你自己画，就用原来的方块就行」</h2>
     * ⇒ 上一轮的 21 件/艘自绘模型（{@code shipBoxes}）<b>整批撤掉</b>，改成
     * "按台账烘焙真方块 + 每帧套一个自转角"，与内环带<b>完全同构</b>（{@link HostFxBaked}）。
     * <p>尺寸/位置就是原样：真飞船在生成器里占 rad 61..65（{@code shipRadialCenter = 63}），
     * 5 层高。角速度按组分开：上组 {@code spin(+9t)}、下组 {@code spin(−9t)}（这条用户确认过，保留）。
     *
     * <p>为什么要<b>两次</b> {@code pushPose}：上/下两组的自转角不同，缓冲区却各自独立，
     * 所以各套一次 pose；枢轴仍是环心那条竖轴（与环带同一个）。
     *
     * @param spinSeconds 自转时钟（秒）= {@link HostFxSwitch#spinSeconds()}（降级态冻结 ⇒ 舰原地不动）
     */
    private static void posedShipDraw(PoseStack poseStack, float spinSeconds) {
        int blocks = 0;
        int quads = 0;
        for (int half = 1; half >= 0; half--) {
            final boolean up = half == 1;
            final HostFxBaked baked = up ? shipUp : shipDown;
            if (baked == null || baked.isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            try {
                HostFxRingTransforms.spin(poseStack, HostFxGeometry.shipOrbitDeg(spinSeconds, up));
                baked.render(poseStack);
                blocks += baked.blocks();
                quads += baked.quads();
            } finally {
                poseStack.popPose();
            }
        }
        lastShipBoxes = 0;                  // 自绘方盒已撤（保留字段只为日志里的 0 读数对照）
        lastShipBlocks = blocks;
        lastShipQuads = quads;
    }

    /** 换维度 / 换朝向 / 台账变动时丢弃旧缓冲（GL 侧一并 close）。 */
    public static void clear() {
        if (ring != null) {
            ring.close();
        }
        ring = null;
        if (shipUp != null) {
            shipUp.close();
        }
        if (shipDown != null) {
            shipDown.close();
        }
        shipUp = null;
        shipDown = null;
        bakedKey = Long.MIN_VALUE;
        HostFxPillarBeam.reset();               // 🔴 第五改：主光柱的 VertexBuffer / shader 也一起丢
        ledgerCells = 0;
        cellsRing = 0;
        cellsUp = 0;
        cellsDown = 0;
        cellsArms = 0;
        cellsMouthCut = 0;
        groupsMatchLedger = true;
        roundTripMismatch = null;
        ringBakedBlocks = 0;
        ringBakedQuads = 0;
        lastShipBoxes = 0;
        lastShipBlocks = 0;
        lastShipQuads = 0;
        lastMdBoxes = 0;
        lastGroups = "none";
    }

    /** 一行摘要（给 {@code draw} 日志）。 */
    public static String summary() {
        final StringBuilder sb = new StringBuilder(160);
        if (ring == null) {
            sb.append("baked=none groups=").append(lastGroups);
        } else {
            sb.append("baked[").append(ring.summary()).append(']')
                    .append(" groups=").append(lastGroups);
        }
        sb.append(" 台账 ").append(ledgerCells).append(" 格（环 ").append(cellsRing)
                .append(" / 辐条→解压机 ").append(cellsArms)
                .append(" / 内环摘除→解压机前部 ").append(cellsMouthCut)
                .append(" / 上舰 ").append(cellsUp).append(" / 下舰 ").append(cellsDown)
                .append("，match=").append(groupsMatchLedger).append(')')
                // 🔴 2026-10-25 第三改：三组角速度各自独立（12 / +9 / −9，互不相等）
                .append(" | 角速度 内环 ").append(HostFxGeometry.RING_DEG_PER_SEC)
                .append("°/s / 上舰 +").append(HostFxGeometry.SHIP_UP_DEG_PER_SEC)
                .append("°/s / 下舰 −").append(HostFxGeometry.SHIP_DOWN_DEG_PER_SEC).append("°/s")
                .append("（三组独立烘焙独立 spin，互不相等）")
                .append(" | 解压机 4×").append(HostFxGeometry.mdBoxCount()).append(" 件 = ")
                .append(lastMdBoxes).append(" 方盒（单件≤1 格，≥").append(HostFxGeometry.MD_MIN_BOXES)
                .append(" 件，横置 " + HostFxGeometry.MD_TILT_DEG + "°，喷口 r="
                        + String.format(java.util.Locale.ROOT, "%.2f", HostFxGeometry.mdNozzleRadius())
                        + " 格）")
                // 🔴 2026-10-24 傍晚：飞船不再自绘 ⇒ 这里报的是"真方块烘焙"的块数，
                //    `shipSelfBoxes=0` 是"没有再自绘"的判据（保留是为了能一眼看出没回退）。
                .append(" | 飞船舱体 真方块烘焙 上 ").append(shipUp == null ? 0 : shipUp.blocks())
                .append(" + 下 ").append(shipDown == null ? 0 : shipDown.blocks())
                .append(" = ").append(lastShipBlocks).append(" 格 / ").append(lastShipQuads).append(" 面")
                .append("（match=").append(shipBakedMatches()).append("，shipSelfBoxes=")
                .append(lastShipBoxes).append("）")
                .append(" | 环烘焙 ").append(ringBakedBlocks).append(" 格 / ").append(ringBakedQuads).append(" 面");
        if (roundTripMismatch != null) {
            sb.append(" 🔴 台账往返不一致：").append(roundTripMismatch);
        }
        return sb.toString();
    }
}
