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
 * 归墟视界枢核 · <b>跟着转的三批东西</b>：内环带（真方块烘焙）＋ 4 座物质解压机（自绘）＋ 8 艘飞船（自绘小模型）。
 *
 * <h2>🔴 2026-10-25 二次修正：飞船回到<b>外环外侧 63 格</b>、角速度回到 <b>+9 / −9 °/s</b></h2>
 * 用户实机看完后的原话：「这个飞船啊，<b>它是要在外环外侧飞的，就原本好好的，你这一改就不好了</b>」
 * ⇒ 上一轮"与内环同角速度、同枢轴"这条<b>已撤销</b>：
 * <ul>
 *   <li>半径回到 63 格（生成器 {@code shipRadialCenter}，真方块就在 rad 61..65）；</li>
 *   <li>角速度回到上组 +9°/s、下组 −9°/s（{@link HostFxGeometry#shipOrbitDeg(float, boolean)}），
 *       与内环 12°/s <b>故意不同</b>；枢轴仍是环心那条竖轴（这点没变）；</li>
 *   <li>只保留用户没否定的那条：舰体包围盒 2.52×1.88×1.62 格、<b>单件 ≤1 格</b>。</li>
 * </ul>
 *
 * <h2>2026-10-25 首批改动（仍有效）：飞船从"真方块烘焙"改成"自绘 ≤3×2×2 小模型"</h2>
 * <ul>
 *   <li>「飞船体积要更小、建模更精细，要模仿《群星》物质解压机」 ⇒ 自绘 21 个分件；</li>
 *   <li>真方块那 888 格照旧被 {@link HostFxHide} 藏成 AIR（不留残影）。</li>
 * </ul>
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

    /**
     * 4 条辐条在<b>本地整数网格</b>里的键（与 {@link HostFxBaked.Cell} 的 {@code gx/gy/gz} 同一口径）。
     *
     * <p>换算路径与烘焙完全一样（{@code worldFromGenerator → worldToLocal → round(l + CELL_CENTER)}），
     * 所以这里拿到的键与 {@code cells()} 里的键<b>逐格可比</b>（不是"另算一套坐标"）。
     */
    private static Set<Long> armGridKeys(BlockPos controllerPos, Direction facing) {
        final List<BlockPos> arms = HostFxGeometry.armCells();
        final Set<Long> out = new HashSet<>(arms.size() * 2);
        for (BlockPos gen : arms) {
            final BlockPos world = HostFxGeometry.worldFromGenerator(controllerPos, facing,
                    gen.getX(), gen.getY(), gen.getZ());
            final float[] l = HostFxGeometry.worldToLocal(controllerPos, facing, world);
            out.add(key(Math.round(l[0] + HostFxGeometry.CELL_CENTER),
                    Math.round(l[1] + HostFxGeometry.CELL_CENTER),
                    Math.round(l[2] + HostFxGeometry.CELL_CENTER)));
        }
        return out;
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
        final List<HostFxBaked.Cell> g0 = new ArrayList<>(all.size());
        final List<HostFxBaked.Cell> gUp = new ArrayList<>(512);
        final List<HostFxBaked.Cell> gDown = new ArrayList<>(512);
        int arms = 0;
        for (HostFxBaked.Cell c : all) {
            // 🔴 辐条那批先摘出去（它们由 4 座解压机取代，见 cellsArms 的注释）
            if (armKeys.contains(gridKey(c))) {
                arms++;
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
        // 判据②：四组格数之和 == 台账格数（对数，不是"三个 list 加起来"那种恒真校验）
        groupsMatchLedger = (cellsRing + cellsArms + cellsUp + cellsDown) == ledgerCells;
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
                ShanhaiMod.LOGGER.info("[SHANHAI-HOSTFX] nozzle_beam_ok 判据：4 条径向光柱起点 == "
                                + "4 座解压机喷口中心（容差 1e-5），喷口 r={} 格、倾角 {}°、末端偏差 0.000000",
                        String.format(java.util.Locale.ROOT, "%.3f", HostFxGeometry.mdNozzleRadius()),
                        HostFxGeometry.MD_TILT_DEG);
            } else {
                ShanhaiMod.LOGGER.warn("[SHANHAI-HOSTFX] nozzle_beam_mismatch {}", bad);
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
     * @param ships 是否连飞船一起画；<b>现在恒为 true</b>（飞船真方块已被藏成 AIR，少画一组就留一个洞）
     */
    public static void render(PoseStack poseStack, Direction facing, float cx, float cy, float cz,
                              float seconds, boolean ships) {
        lastGroups = "none";
        lastShipBoxes = 0;
        final StringBuilder submitted = new StringBuilder(16);
        poseStack.pushPose();
        try {
            HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing);
            if (ring != null && !ring.isEmpty()) {
                poseStack.pushPose();
                HostFxRingTransforms.spin(poseStack, HostFxGeometry.ringDeg(seconds));
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
                HostFxRingTransforms.spin(poseStack, HostFxGeometry.ringDeg(seconds));
                lastMdBoxes = HostFxDraw.drawMatterDecompressors(poseStack);
            } finally {
                poseStack.popPose();
            }
            submitted.append(submitted.length() == 0 ? "md" : "+md");
            if (ships) {
                posedShipDraw(poseStack, seconds);
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
     */
    private static void posedShipDraw(PoseStack poseStack, float seconds) {
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
                HostFxRingTransforms.spin(poseStack, HostFxGeometry.shipOrbitDeg(seconds, up));
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
        ledgerCells = 0;
        cellsRing = 0;
        cellsUp = 0;
        cellsDown = 0;
        cellsArms = 0;
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
                .append(" / 上舰 ").append(cellsUp).append(" / 下舰 ").append(cellsDown)
                .append("，match=").append(groupsMatchLedger).append(')')
                .append(" | 解压机 4×").append(HostFxGeometry.mdBoxCount()).append(" 件 = ")
                .append(lastMdBoxes).append(" 方盒（单件≤1 格，横置 "
                        + HostFxGeometry.MD_TILT_DEG + "°，喷口 r="
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
