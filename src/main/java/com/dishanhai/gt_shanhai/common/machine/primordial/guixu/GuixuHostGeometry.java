package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 归墟视界枢核（{@code shanhai:guixu_vision_core}）· <b>v5.1 几何参数表</b>（全类唯一硬编码处）。
 *
 * <h2>这份坐标是哪来的</h2>
 * 造型定稿于 {@code temp/host-shape/gen5.mjs}（v5.1，"美化版"）：结构模板
 * {@code shanhai_host_v5}，尺寸 <b>148 × 135 × 131</b>、<b>18,533</b> 个方块、
 * 体素空间最小角 {@code (-65, -57, -65)}。本类的每一个数字都能在该生成器的 {@code P{}} 里找到同名参数，
 * 并由生成器自检逐条核对过。
 *
 * <h2>坐标空间（三套，别混）</h2>
 * <ol>
 *   <li><b>体素空间</b>：生成器 {@code put(x,y,z)} 的那套，未平移。环中心 (0, 10, 0)，
 *       控制器 (72, 21, 0)（在控制室里、地板上），整座结构 x[-65..82] y[-57..77] z[-65..65]。</li>
 *   <li><b>模板空间</b>：减掉最小角 (-65,-57,-65) 之后的 0-based 坐标 = 结构资源 {@code .bin} 的
 *       网格坐标（见 {@link #templateSize()}）。</li>
 *   <li><b>控制器相对</b>：{@link Slot#offset()} —— 相对控制器、<b>按"机器朝西（-X）"这一种摆法给出</b>
 *       （造型就是按这个摆法定稿的：站体在 +X，控制室的玻璃面朝 -X 看黑洞）。
 *       玩家把机器转成别的朝向后用 {@link #slotPositions} 旋转即可。</li>
 * </ol>
 *
 * <h2>24 个模块接口（v5.1 定稿的账）</h2>
 * <pre>
 *   外环 16 = 4 个水平朝向 × 4 组： (±61, 10, t) 与 (t, 10, ±61)，t ∈ {-13,-4,5,14}
 *   上柱  4 = 4 个水平朝向：        (±7, 57, 0) 与 (0, 57, ±7)
 *   下柱  4 = 4 个水平朝向：        (±7, -37, 0) 与 (0, -37, ±7)
 *   站体  0（v5.1 起站体不再有任何接口；那 4 个已挪回上柱）
 *   合计 24 —— 用户 2026-10-23 定死，{@link #verify()} 会逐条断言。
 * </pre>
 *
 * <h2>旋转约定（为什么要这么转）</h2>
 * 造型的"正位"是<b>机器朝西</b>。GTCEu 的 {@code RotationState.NON_Y_AXIS} 只允许 4 个水平朝向，
 * 换朝向 = 整座结构绕 Y 轴转。本类用与 GTCEu 图案匹配器<b>同一套轴约定</b>做换算
 * （{@code FactoryBlockPattern.start(LEFT, DOWN, BACK)} 实测：aisle 轴 = 机器朝向的反方向、
 * row 轴 = -Y、col 轴 = {@code UP × 朝向}）：
 * <pre>
 *   worldOffset = (-F) * dx  +  (0,1,0) * dy  +  (UP × F) * dz
 *   （dx,dy,dz = {@link Slot#offset()}；F = 机器朝向；UP × F 的 xz 分量 = (F.z, -F.x)）
 * </pre>
 * 该公式对 4 个朝向做过自检（{@link #verify()}）。
 */
public final class GuixuHostGeometry {

    private GuixuHostGeometry() {}

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 参数表 —— 全类唯一的硬编码处；改这里，下面 24 个槽位 / 包围盒一起变
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 结构模板尺寸（x, y, z 格数）—— 必须与结构资源 {@code .bin} 的 aisles/rows/cols 完全一致。 */
    public static final int TEMPLATE_SIZE_X = 148;
    public static final int TEMPLATE_SIZE_Y = 135;
    public static final int TEMPLATE_SIZE_Z = 131;

    /** 体素空间最小角 = 模板原点（{@code .bin} 的网格 (0,0,0)）所在的体素坐标。 */
    public static final int TEMPLATE_MIN_X = -65;
    public static final int TEMPLATE_MIN_Y = -57;
    public static final int TEMPLATE_MIN_Z = -65;

    /** 控制器（主机方块）在体素空间的坐标 = 控制室里、地板上那一格。 */
    public static final int CONTROLLER_X = 72;
    public static final int CONTROLLER_Y = 21;
    public static final int CONTROLLER_Z = 0;

    /**
     * 控制器朝向 = <b>西（-X，朝圆心）</b>。
     *
     * <p>这是"造型正位"：站体在 +X 一侧，控制室的整面玻璃朝 -X 看向黑洞
     * （用户 2026-10-23 定："朝圆心"）。玩家实际摆放时可以是 4 个水平朝向中的任意一个，
     * {@link #slotPositions} 会把槽位一起转过去。
     */
    public static final Direction CONTROLLER_FACING = Direction.WEST;

    /** 外环：圆角方的直边半跨（gen5 的 {@code P.half}）。 */
    public static final int RING_HALF = 60;
    /** 外环：环中心层（gen5 的 {@code P.ringY}）。 */
    public static final int RING_Y = 10;
    /** 外环：每边几个接口（gen5 的 {@code P.modsPerSide}）。 */
    public static final int MODULES_PER_SIDE = 4;
    /** 外环：同侧相邻接口间距（gen5 的 {@code P.modPitch}）。 */
    public static final int MODULE_PITCH = 9;
    /** 立柱：柱壳外半径（gen5 的 {@code P.pillarR}）。 */
    public static final int PILLAR_SHELL_RADIUS = 6;
    /** 立柱：总长（gen5 的 {@code P.pillarLen}）。 */
    public static final int PILLAR_LENGTH = 42;
    /** 立柱：离黑洞区的间隙（gen5 的 {@code P.pillarGap}）。 */
    public static final int PILLAR_GAP = 14;
    /** 中心黑洞区半径（gen5 的 {@code P.holeR}）—— 只用来算柱子起点，本类不建方块。 */
    public static final int CORE_HOLE_RADIUS = 12;
    /** 模块正面板凸出到"接口面"外面几格（gen5 里 = 1）。 */
    public static final int MODULE_PROTRUSION = 1;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  派生
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 外环接口的外侧坐标绝对值 = 环最外那一格 + 模块凸出。 */
    public static final int RING_MODULE_OUTWARD = RING_HALF + MODULE_PROTRUSION;
    /** 立柱接口的外侧坐标绝对值 = 柱壳外半径 + 模块凸出。 */
    public static final int PILLAR_MODULE_OUTWARD = PILLAR_SHELL_RADIUS + MODULE_PROTRUSION;
    /** 上柱最低层 y。 */
    public static final int UPPER_PILLAR_BASE_Y = RING_Y + CORE_HOLE_RADIUS + PILLAR_GAP;
    /** 上柱最高层 y（= 柱顶封盖）。 */
    public static final int UPPER_PILLAR_TOP_Y = UPPER_PILLAR_BASE_Y + PILLAR_LENGTH - 1;
    /** 下柱最高层 y。 */
    public static final int LOWER_PILLAR_BASE_Y = RING_Y - CORE_HOLE_RADIUS - PILLAR_GAP;
    /** 下柱最低层 y。 */
    public static final int LOWER_PILLAR_BOTTOM_Y = LOWER_PILLAR_BASE_Y - (PILLAR_LENGTH - 1);
    /** 上柱那 4 个模块所在的层。 */
    public static final int UPPER_PILLAR_MODULE_Y = UPPER_PILLAR_BASE_Y + PILLAR_LENGTH / 2;
    /** 下柱那 4 个模块所在的层。 */
    public static final int LOWER_PILLAR_MODULE_Y = LOWER_PILLAR_BASE_Y - PILLAR_LENGTH / 2;

    /** 外环接口个数。 */
    public static final int RING_SLOT_COUNT = 4 * MODULES_PER_SIDE;
    /** 立柱接口个数（上下各 4）。 */
    public static final int PILLAR_SLOT_COUNT = 8;
    /** 接口总数 —— 用户 2026-10-23 定死 = 24。 */
    public static final int SLOT_COUNT = RING_SLOT_COUNT + PILLAR_SLOT_COUNT;

    /** 槽位属于哪一组。 */
    public enum Kind {
        /** 外环直边上的 16 个。 */
        OUTER_RING,
        /** 上柱 4 个。 */
        UPPER_PILLAR,
        /** 下柱 4 个。 */
        LOWER_PILLAR,
    }

    /**
     * 一个模块接口（= 模块自己的控制器方块将来要坐的那一格）。
     *
     * @param kind   外环 / 上柱 / 下柱
     * @param side   在结构的哪一侧（水平朝向，人读日志用）
     * @param index  同侧内序号（外环 = 侧向位置下标；立柱恒 0）
     * @param offset 相对控制器、按"机器朝西"给出的体素空间偏移
     */
    public record Slot(Kind kind, Direction side, int index, BlockPos offset) {}

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  对外取值
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 控制器在体素空间的位置。 */
    public static BlockPos controllerPos() {
        return new BlockPos(CONTROLLER_X, CONTROLLER_Y, CONTROLLER_Z);
    }

    /** 模板尺寸。 */
    public static BlockPos templateSize() {
        return new BlockPos(TEMPLATE_SIZE_X, TEMPLATE_SIZE_Y, TEMPLATE_SIZE_Z);
    }

    /** 模板最小角（体素空间）。 */
    public static BlockPos templateMinCorner() {
        return new BlockPos(TEMPLATE_MIN_X, TEMPLATE_MIN_Y, TEMPLATE_MIN_Z);
    }

    /** 同一侧第 {@code i} 个接口的侧向偏移（与生成器 {@code Math.round} 同口径）。 */
    public static int lateral(int i) {
        return (int) Math.round(-((MODULES_PER_SIDE - 1) / 2.0D) * MODULE_PITCH + i * (double) MODULE_PITCH);
    }

    /** 全部 {@link #SLOT_COUNT} 个槽位（控制器相对、机器朝西）。顺序固定、可重现。 */
    public static List<Slot> slots() {
        return CANONICAL_SLOTS;
    }

    /** 只要某一组的槽位。 */
    public static List<Slot> slots(Kind kind) {
        final List<Slot> out = new ArrayList<>();
        for (Slot s : CANONICAL_SLOTS) {
            if (s.kind() == kind) {
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 把 {@link Slot#offset()} 旋转到机器真实朝向 {@code facing} 上。
     *
     * <p>公式与 GTCEu 图案匹配器同源（见类注释）：{@code (-F)*dx + UP*dy + (UP×F)*dz}。
     */
    public static BlockPos rotate(Direction facing, BlockPos offset) {
        final int fx = facing.getStepX();
        final int fz = facing.getStepZ();
        final int backX = -fx;      // -F
        final int backZ = -fz;
        final int sideX = fz;       // UP × F，UP=(0,1,0) ⇒ (F.z, 0, -F.x)
        final int sideZ = -fx;
        final int x = backX * offset.getX() + sideX * offset.getZ();
        final int z = backZ * offset.getX() + sideZ * offset.getZ();
        return new BlockPos(x, offset.getY(), z);
    }

    /** 旋转后的绝对坐标（机器真的摆在世界里时，这个槽位该在哪一格）。 */
    public static BlockPos slotPos(BlockPos controllerPos, Direction facing, Slot slot) {
        return controllerPos.offset(rotate(facing, slot.offset()));
    }

    /** 24 个槽位的绝对坐标（喂给 {@code IModularMachineHost#getModuleScanPositions()}）。 */
    public static BlockPos[] slotPositions(BlockPos controllerPos, Direction facing) {
        final BlockPos[] out = new BlockPos[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            out[i] = slotPos(controllerPos, facing, CANONICAL_SLOTS.get(i));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 山海：模組先於或晚於主機成型時，都能從相同的 24 槽幾何反查主機。 */
    public static java.util.Set<BlockPos> candidateHostPositions(BlockPos modulePos, Direction moduleFacing) {
        var result = new java.util.LinkedHashSet<BlockPos>();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Slot slot : slots()) {
                result.add(modulePos.subtract(rotate(facing, slot.offset())));
            }
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    /** ⚠️ 必须声明在 {@link #CANONICAL_SLOTS} <b>之前</b>：静态初始化按书写顺序执行，
     *  反过来写会让 buildSlots() 读到 null（运行期 NPE，编译期完全不报）。 */
    private static final Direction[] SIDES = {
            Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH,
    };

    private static final List<Slot> CANONICAL_SLOTS = buildSlots();

    private static List<Slot> buildSlots() {
        final List<Slot> out = new ArrayList<>(SLOT_COUNT);
        final BlockPos controller = controllerPos();
        for (Direction side : SIDES) {
            for (int i = 0; i < MODULES_PER_SIDE; i++) {
                final int l = lateral(i);
                final BlockPos pos = switch (side) {
                    case EAST -> new BlockPos(RING_MODULE_OUTWARD, RING_Y, l);
                    case WEST -> new BlockPos(-RING_MODULE_OUTWARD, RING_Y, l);
                    case SOUTH -> new BlockPos(l, RING_Y, RING_MODULE_OUTWARD);
                    case NORTH -> new BlockPos(l, RING_Y, -RING_MODULE_OUTWARD);
                    default -> throw new IllegalStateException("[SHANHAI-HOST] 外环接口只允许水平朝向，拿到 " + side);
                };
                out.add(new Slot(Kind.OUTER_RING, side, i, pos.subtract(controller)));
            }
        }
        for (Kind kind : new Kind[]{Kind.UPPER_PILLAR, Kind.LOWER_PILLAR}) {
            final int y = kind == Kind.UPPER_PILLAR ? UPPER_PILLAR_MODULE_Y : LOWER_PILLAR_MODULE_Y;
            for (Direction side : SIDES) {
                final BlockPos pos = switch (side) {
                    case EAST -> new BlockPos(PILLAR_MODULE_OUTWARD, y, 0);
                    case WEST -> new BlockPos(-PILLAR_MODULE_OUTWARD, y, 0);
                    case SOUTH -> new BlockPos(0, y, PILLAR_MODULE_OUTWARD);
                    case NORTH -> new BlockPos(0, y, -PILLAR_MODULE_OUTWARD);
                    default -> throw new IllegalStateException("[SHANHAI-HOST] 立柱接口只允许水平朝向，拿到 " + side);
                };
                out.add(new Slot(kind, side, 0, pos.subtract(controller)));
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  自检（纯算术、不需要世界；由机器注册期调用一次，红就炸）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 参数表内部一致性 + 四朝向自检。
     *
     * @throws IllegalStateException 任一判据不成立（消息以 {@code [SHANHAI-HOST]} 开头）
     */
    public static void verify() {
        final List<Slot> slots = slots();
        if (slots.size() != SLOT_COUNT || SLOT_COUNT != 24) {
            throw new IllegalStateException("[SHANHAI-HOST] 槽位总数 " + slots.size()
                    + " / SLOT_COUNT " + SLOT_COUNT + " != 24（用户定死的规格）");
        }
        if (slots(Kind.OUTER_RING).size() != 16 || slots(Kind.UPPER_PILLAR).size() != 4
                || slots(Kind.LOWER_PILLAR).size() != 4) {
            throw new IllegalStateException("[SHANHAI-HOST] 分区计数不对：外环 " + slots(Kind.OUTER_RING).size()
                    + " / 上柱 " + slots(Kind.UPPER_PILLAR).size() + " / 下柱 " + slots(Kind.LOWER_PILLAR).size());
        }
        // ① 用"独立重算"的期望集合反查：24 个槽位必须刚好落在这 24 个已知位置上
        //    （不是拿同一份 buildSlots 自证 —— 这里换一种写法重算，防"偏移写错但自己自洽"）
        final Set<BlockPos> expected = new LinkedHashSet<>();
        for (Direction side : SIDES) {
            for (int i = 0; i < MODULES_PER_SIDE; i++) {
                final int l = lateral(i);
                final int outX = side == Direction.EAST ? RING_MODULE_OUTWARD : side == Direction.WEST ? -RING_MODULE_OUTWARD : l;
                final int outZ = side == Direction.SOUTH ? RING_MODULE_OUTWARD : side == Direction.NORTH ? -RING_MODULE_OUTWARD : l;
                expected.add(new BlockPos(outX, RING_Y, outZ));
            }
            final int px = side == Direction.EAST ? PILLAR_MODULE_OUTWARD : side == Direction.WEST ? -PILLAR_MODULE_OUTWARD : 0;
            final int pz = side == Direction.SOUTH ? PILLAR_MODULE_OUTWARD : side == Direction.NORTH ? -PILLAR_MODULE_OUTWARD : 0;
            expected.add(new BlockPos(px, UPPER_PILLAR_MODULE_Y, pz));
            expected.add(new BlockPos(px, LOWER_PILLAR_MODULE_Y, pz));
        }
        final BlockPos controller = controllerPos();
        final Set<BlockPos> actual = new LinkedHashSet<>();
        for (Slot s : slots) {
            actual.add(controller.offset(s.offset()));
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException("[SHANHAI-HOST] 24 个槽位没有落在 v5.1 的已知位置集合上（实际 "
                    + actual.size() + " 个 / 期望 " + expected.size() + " 个）");
        }
        // ② 槽位必须在模板包围盒内
        for (BlockPos p : actual) {
            if (p.getX() < TEMPLATE_MIN_X || p.getX() > TEMPLATE_MIN_X + TEMPLATE_SIZE_X - 1
                    || p.getY() < TEMPLATE_MIN_Y || p.getY() > TEMPLATE_MIN_Y + TEMPLATE_SIZE_Y - 1
                    || p.getZ() < TEMPLATE_MIN_Z || p.getZ() > TEMPLATE_MIN_Z + TEMPLATE_SIZE_Z - 1) {
                throw new IllegalStateException("[SHANHAI-HOST] 槽位 " + p + " 跑到模板包围盒外了");
            }
        }
        // ③ 控制器本身的位置硬钉死（改它必须同时改 .bin 与生成器）
        if (!controller.equals(new BlockPos(72, 21, 0))) {
            throw new IllegalStateException("[SHANHAI-HOST] 控制器位置被改了：" + controller);
        }
        // ④ 四朝向：数量不变、y 不变、互不重复
        for (Direction facing : SIDES) {
            final Set<BlockPos> rotated = new LinkedHashSet<>();
            for (Slot s : slots) {
                final BlockPos o = rotate(facing, s.offset());
                if (o.getY() != s.offset().getY()) {
                    throw new IllegalStateException("[SHANHAI-HOST] 旋转改变了 y：" + s + " facing=" + facing);
                }
                rotated.add(controller.offset(o));
            }
            if (rotated.size() != SLOT_COUNT) {
                throw new IllegalStateException("[SHANHAI-HOST] 朝向 " + facing + " 下槽位重复了（" + rotated.size() + " 个）");
            }
        }
        // ⑤ 正位（朝西）时旋转必须是恒等
        for (Slot s : slots) {
            if (!rotate(CONTROLLER_FACING, s.offset()).equals(s.offset())) {
                throw new IllegalStateException("[SHANHAI-HOST] 朝西（正位）时偏移被旋转了：" + s);
            }
        }
        // ⑥ 转 4 次回到原位
        for (Direction facing : SIDES) {
            BlockPos acc = new BlockPos(1, 0, 0);
            for (int i = 0; i < 4; i++) {
                acc = rotate(facing, acc);
            }
            if (!acc.equals(new BlockPos(1, 0, 0))) {
                throw new IllegalStateException("[SHANHAI-HOST] 朝向 " + facing + " 的旋转 4 次没回到原位：" + acc);
            }
        }
    }
}
