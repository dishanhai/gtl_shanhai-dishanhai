package com.shanhai.client.render.hostfx;

import com.shanhai.ShanhaiMod;
import com.shanhai.client.config.ShanhaiClientConfig;
import com.shanhai.machine.host.GuixuHostGeometry;
import com.shanhai.machine.host.GuixuVisionCoreStructure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海 · <b>结构闪电的"世界里那一格到底是不是那个方块"核对层</b>（作者 dishanhai，2026-11-22 移植）。
 *
 * <h2>来源与"纯移植"口径</h2>
 * 照抄他的 {@code GuixuStructureLightning}（{@code temp/upstream-bump/src/GuixuStructureLightning.java}）：
 * <ol>
 *   <li>结构只读一次（{@code loadAttempted} 门闩），失败<b>只报错一次</b>、之后整件不画（不重试刷屏）；</li>
 *   <li>表面节点最多 <b>256</b>（{@link GuixuAccretionLightning#MAX_NODES}，他类注释逐字「只查至多 256 個節點」）；</li>
 *   <li>逐节点回世界核对：<b>区块不在就不查</b>（{@code world.hasChunkAt}）；
 *       被 {@link HostFxHide} 临时藏成空气的格子<b>用台账里的原方块</b>核对
 *       （他的原话：「{@code 含被渲染隐藏的方块台账}」）——否则内环/飞船上的电弧会全部消失；</li>
 *   <li>被藏起来的格子，锚点要<b>跟着它所在的那一组一起转</b>（{@code groupAngleDeg(spinSeconds,
 *       groupOf(localY))}）—— 因为那些格子是真的被渲染成转动后的样子；</li>
 *   <li>节点外移 {@code normal * 0.55} 格（他的值）。</li>
 * </ol>
 *
 * <h2>本工程的三处落位（他的代码里是另一套常量，数值逐一核对过）</h2>
 * <pre>
 *   模板最小角 / 尺寸   GuixuHostGeometry.TEMPLATE_MIN_X=−65、TEMPLATE_MIN_Z=−65、TEMPLATE_SIZE_Y=135、
 *                       TEMPLATE_MIN_Y=−57  ← 与他的 GuixuHostGeometry 同值（148×135×131 那套结构）
 *   切片 y=0 的本地 y    TEMPLATE_SIZE_Y−1 + TEMPLATE_MIN_Y − RING_Y = 134 − 57 − 10 = <b>67</b>
 *                       他的式子逐字相同 ⇒ 节点 y = 67 − 切片行号，回世界时 +RING_Y(=10)
 *   环心本地 y          = 生成器 y − RING_Y，供 groupOf / rotateYDeg 用（本工程本地帧的约定）
 * </pre>
 *
 * <h2>与他的唯一差异：符号→方块表</h2>
 * 他的 {@code GuixuVisionCoreStructure.previewBlocks(char)} 返回一个<b>候选集合</b>；本工程
 * {@code GuixuVisionCoreStructure} 没有这个方法，只有一个 {@code SYMBOL_BLOCKS} 的 id 表 ⇒ 这里
 * 用 {@link BuiltInRegistries#BLOCK} 现查 <b>单个</b>期望方块。
 * 后果（如实写）：环带符号 {@code A} 上如果玩家放的是能源仓/动力仓（图案允许），那一格不会成为电弧落点
 * —— 只是少几个落点，不会画错位置。
 */
public final class HostFxStructureLightning {

    private HostFxStructureLightning() {
    }

    /** 结构表面节点上限（他的 256）。 */
    public static final int MAX_NODES_CHECKED = GuixuAccretionLightning.MAX_NODES;

    /** 结构模型（只建一次；{@code null} = 建不起来）。 */
    private static GuixuAccretionLightning model;
    /** 是否已经尝试过（失败不重试）。 */
    private static boolean loadAttempted;
    /** 载入状态（进日志/报告，不参与判定）。 */
    private static String loadState = "未尝试";
    /** 符号 → 期望方块（懒建）。 */
    private static final Map<Character, Block> EXPECTED = new HashMap<>();

    /** 换维度 / 资源重载时由渲染器调（下次重新读结构 + 重新核对世界）。 */
    public static void reset() {
        model = null;
        loadAttempted = false;
        loadState = "未尝试";
        EXPECTED.clear();
        presentCache = new boolean[0];
        anchorXCache = new float[0];
        anchorYCache = new float[0];
        anchorZCache = new float[0];
        hiddenCache = new boolean[0];
        linkOrderCache = new int[0];
        lastOpenLinks = 0;
        lastArcs = -1;
        lastPeakAlpha = 0.0F;
        lastScanKey = Long.MIN_VALUE;
        lastScanStamp = Long.MIN_VALUE;
        lastScanMs = 0L;
        lastPresent = 0;
        lastNodes = 0;
        lastBlockReads = 0;
        skipReason = "";
        forceRescan = true;
    }

    /** 载入状态（一行；进 {@code draw} 日志）。 */
    public static String state() {
        return loadState;
    }

    /** 上一次核对里"通过的战点 / 总节点"读数（日志用）。 */
    private static int lastPresent;
    private static int lastNodes;

    /**
     * 上一次 {@link #frame} 为什么<b>没画</b>：{@code ""} = 画了，其余 = 原因
     * （{@code "关:面板"} / {@code "关:配置"} / {@code "无世界"} / {@code "不画:载入失败"}）。
     *
     * <p>给 {@code HostFxExtraLayers.stats()} 用：跳段时 {@code arcs=0}，光看数字分不清
     * "玩家关的"还是"配置关的" ⇒ 读数里必须带原因。
     */
    private static String skipReason = "";

    /** 上一次 {@link #frame} 的跳过原因（{@code ""} = 真的画了）。 */
    public static String skipReason() {
        return skipReason;
    }

    public static String auditLine() {
        return "节点 " + lastPresent + "/" + lastNodes + " 二次核对读 " + lastBlockReads + " 格/次"
                + " 开敞连线 " + lastOpenLinks + " 存活 " + (lastArcs < 0 ? "?" : String.valueOf(lastArcs))
                + " peakAlpha " + String.format(java.util.Locale.ROOT, "%.2f", lastPeakAlpha)
                + " 状态=" + loadState;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  结构资源读取：纯 Java（2026-11-22 第二改 —— 不用任何 kotlin.* 类型）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 🔴 为什么不再调用 {@code StructureResourceLoader.loadShapeInfoSlices(...)}：
     *   它的第 3 个形参是 {@code kotlin.jvm.functions.Function4}，而本工程**编译类路径里没有 kotlin-stdlib**
     *   （gtladditions 自带的那份是 JiJ 内嵌、不参与 javac）⇒ 那个重载在 Java 里根本不可调用，
     *   Gradle 实编译报「找不到 kotlin.jvm.functions.Function4 的类文件」。
     *
     * 🔴 为什么也不是换成 {@code loadRings(String, String)}（它确实没有 kotlin 类型）：
     *   资源文件的第 8 个字节是**段类型**，{@code loadAisles} 传 1、{@code loadRings} 传 2，
     *   读出来不等于期望值就直接 error ⇒ 我们的
     *   {@code assets/gtladditions/structures/multiblock/guixu_vision_core.bin} 第 8 字节是
     *   {@code 0x01}（aisles），拿 {@code loadRings} 去读**必抛**（探针实测见报告）。
     *   ⇒ 唯一"不含 kotlin 类型又能拿到同一份 aisles 数据"的办法 = 自己按格式读这个文件。
     *
     * 格式（从 {@code StructureResourceLoader} 的字节码逐条抄下来的，不是猜的）：
     * <pre>
     *   "GTLASB3"(新) 或 "GTLASB2"(旧)          7 字节魔数
     *   kind                                   1 字节：1 = aisles，2 = rings
     *   idLen                                  u16
     *   id                                      idLen 字节 US-ASCII（loader 会与期望 id 比对）
     *   [仅新版] axisOrder                     1 字节（0 = 原样；2 = 前后两轴对调；1/3.. = 三轴重排）
     *   A, B, C, P                            4 × u16（A×B 个格子，每格一行 C 长的 ASCII；P = 调色板行数）
     *   调色板                                  P × C 字节（每字节必须 32..126，固定长度、无分隔）
     *   索引                                   A×B 个，<P>；新版且 P≤256 时 1 字节，否则 u16
     *   EOF                                    读完之后不许剩字节（loader 自己会查）
     * </pre>
     * 现行 {@code guixu_vision_core.bin}（102482 B）逐项对得上：35 B 头 + 477×131 B 调色板 + 148×135×2 B 索引
     * = 35 + 62487 + 39960 = 102482 ✓（探针原始输出见报告）。
     *
     * 轴序（**三方互相印证，不是猜**）：
     * <pre>
     *   ① 文件：A=148、B=135、C=131；调色板行长度 = C；索引共 A×B 个
     *      ⇒ 体积 = A×B×C = 148×135×131 = 2,617,380 格 = 模板体积 ✓
     *   ② 加载器：loadShapeInfoSlices 的产物是 slices[C][A].charAt(B)
     *      （外层 = 行字符串长度 C、次层 = A、叶子 = B —— 从字节码 3 层循环逐条读出）
     *   ③ 图案：GtlAddCompat 用 {@code FactoryBlockPattern.start(LEFT, DOWN, BACK)} 建图案
     *      ⇒ aisle = BACK（纵深，本结构 = 生成器 X，148）、row = DOWN（−Y，135）、col = LEFT（横向，本结构 = Z，131）
     *      再对一次数：GuixuHostGeometry 的控制器 (72,21,0) 在模板索引里 = (137, 78, 65)
     *      —— 探针在 slices 里找到的那个 '~' 恰好就在这三个下标上（原始输出见报告）。
     *   ⇒ {@code slices[z][x].charAt(y)}：z = 0..130（=Z）、x = 0..147（=X）、y = 0..134（=Y，行 0 在最上）
     * </pre>
     * 这份形状正是 {@link GuixuAccretionLightning#fromSlices} 期望的入参 ⇒ 几何与用 loader 时**逐格相同**。
     */

    /** 资源根（与 {@code StructureResourceLoader} 的拼法逐字相同）。 */
    private static final String RESOURCE_ROOT = "/assets/gtladditions/structures/";
    /** 新版魔数（GTLASB3：多一个轴序字节 + 1 字节索引）。 */
    private static final byte[] MAGIC = {'G', 'T', 'L', 'A', 'S', 'B', '3'};
    /** 旧版魔数（GTLASB2：无轴序字节、索引一律 2 字节）—— 现行 {@code guixu_vision_core.bin} 是这一版。 */
    private static final byte[] LEGACY_MAGIC = {'G', 'T', 'L', 'A', 'S', 'B', '2'};
    /** 段类型：1 = aisles（图案匹配用的那一份，本件要的就是它）。 */
    private static final int KIND_AISLES = 1;
    /** 结构体积上限（护栏：防止把坏文件读成天文数字的数组）。 */
    private static final int MAX_CELLS = 8_000_000;

    /**
     * 读资源 → {@code slices[col][aisle].charAt(row)}（与 loader 的 {@code loadShapeInfoSlices(id 映射)}
     * 逐字符同构）。
     *
     * @throws IOException 资源缺失 / 魔数不认识 / 段类型不是 aisles / id 不符 / 尺寸越界 / 尾部有残余字节
     */
    private static String[][] readAislesSlices() throws IOException {
        final String path = RESOURCE_ROOT + GuixuVisionCoreStructure.RESOURCE_PATH;
        final InputStream raw = HostFxStructureLightning.class.getResourceAsStream(path);
        if (raw == null) {
            throw new IOException("资源不存在 " + path);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            final byte[] magic = new byte[7];
            in.readFully(magic);
            final boolean modern = Arrays.equals(magic, MAGIC);
            if (!modern && !Arrays.equals(magic, LEGACY_MAGIC)) {
                throw new IOException("魔数不认识 " + new String(magic, StandardCharsets.US_ASCII));
            }
            final int kind = in.readUnsignedByte();
            if (kind != KIND_AISLES) {
                throw new IOException("资源段类型是 " + kind + "（本件只认 aisles=" + KIND_AISLES + "）");
            }
            final byte[] idBytes = new byte[in.readUnsignedShort()];
            in.readFully(idBytes);
            final String id = new String(idBytes, StandardCharsets.US_ASCII);
            if (!GuixuVisionCoreStructure.RESOURCE_ID.equals(id)) {
                throw new IOException("资源 id 不符 expect=" + GuixuVisionCoreStructure.RESOURCE_ID
                        + " got=" + id);
            }
            if (modern) {
                final int axisOrder = in.readUnsignedByte();
                if (axisOrder != 0) {
                    // 非 0 要做三轴重排；现行文件不会走这里 —— 宁可整块不画，也不要按错轴摆节点
                    throw new IOException("暂不支持 GTLASB3 axisOrder=" + axisOrder);
                }
            }
            final int a = readPositiveUnsignedShort(in);
            final int b = readPositiveUnsignedShort(in);
            final int c = readPositiveUnsignedShort(in);
            final int paletteCount = readPositiveUnsignedShort(in);
            if ((long) a * b > MAX_CELLS || (long) c > MAX_CELLS) {
                throw new IOException("尺寸越界 A=" + a + " B=" + b + " C=" + c);
            }
            if (paletteCount > a * b) {
                throw new IOException("调色板行数 " + paletteCount + " > 格子数 " + (a * b));
            }
            final String[] palette = new String[paletteCount];
            for (int i = 0; i < paletteCount; i++) {
                palette[i] = readAsciiRow(in, c);
            }
            final boolean compactIndex = modern && paletteCount <= 256;
            final String[][] grid = new String[a][b];              // grid[aisle][row] = 长 c 的行字符串
            for (int i = 0; i < a; i++) {
                for (int j = 0; j < b; j++) {
                    final int index = compactIndex ? in.readUnsignedByte() : in.readUnsignedShort();
                    if (index >= paletteCount) {
                        throw new IOException("调色板索引越界 " + index + " ≥ " + paletteCount);
                    }
                    grid[i][j] = palette[index];
                }
            }
            if (in.read() != -1) {
                throw new IOException("结构负载后面还有多余字节");
            }
            // 与 loadShapeInfoSlices(symbolMapper = 原样) 同构：slices[col][aisle].charAt(row)
            final String[][] slices = new String[c][a];
            for (int col = 0; col < c; col++) {
                for (int aisle = 0; aisle < a; aisle++) {
                    final StringBuilder sb = new StringBuilder(b);
                    for (int row = 0; row < b; row++) {
                        sb.append(grid[aisle][row].charAt(col));
                    }
                    slices[col][aisle] = sb.toString();
                }
            }
            return slices;
        }
    }

    private static int readPositiveUnsignedShort(DataInputStream in) throws IOException {
        final int value = in.readUnsignedShort();
        if (value == 0) {
            throw new IOException("u16 字段为 0");
        }
        return value;
    }

    /** 定长 US-ASCII 行；每个字节必须是可打印字符（32..126），与 loader 的校验同口径。 */
    private static String readAsciiRow(DataInputStream in, int length) throws IOException {
        final byte[] bytes = new byte[length];
        in.readFully(bytes);
        for (byte value : bytes) {
            final int unsigned = value & 0xFF;
            if (unsigned < 32 || unsigned > 126) {
                throw new IOException("行里有非可打印字节 " + unsigned);
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /** 懒建结构模型（他的 {@code model()}，逐行同构）。 */
    private static GuixuAccretionLightning model() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                final String[][] slices = readAislesSlices();
                // 🔴 运行期自检：三个维度必须与模板完全一致，否则整块不画（宁可没有，不可画错位置）
                final int gotZ = slices.length;
                final int gotX = slices[0].length;
                final int gotY = slices[0][0].length();
                final int wantX = GuixuHostGeometry.TEMPLATE_SIZE_X;
                final int wantY = GuixuHostGeometry.TEMPLATE_SIZE_Y;
                final int wantZ = GuixuHostGeometry.TEMPLATE_SIZE_Z;
                if (gotX != wantX || gotY != wantY || gotZ != wantZ) {
                    loadState = "尺寸不符 不画";
                    ShanhaiMod.LOGGER.error(
                            "[SHANHAI-HOSTFX] lightning_slices_ragged expect=x{}y{}z{} got=x{}y{}z{}"
                                    + "（结构闪电本件不画；不改任何其它图层）",
                            wantX, wantY, wantZ, gotX, gotY, gotZ);
                    return null;
                }
                model = GuixuAccretionLightning.fromSlices(slices,
                        GuixuHostGeometry.TEMPLATE_MIN_X,
                        GuixuHostGeometry.TEMPLATE_SIZE_Y - 1 + GuixuHostGeometry.TEMPLATE_MIN_Y
                                - HostFxGeometry.RING_Y,
                        GuixuHostGeometry.TEMPLATE_MIN_Z);
                loadState = "ok nodes=" + model.nodes().size() + " links=" + model.links().size();
            } catch (Throwable error) {
                loadState = "失败 " + error.getClass().getSimpleName();
                ShanhaiMod.LOGGER.error("[SHANHAI-HOSTFX] 结构闪电：结构表面采样载入失败（本件不画）", error);
            }
        }
        return model;
    }

    /**
     * 这一帧的电弧几何（<b>环心本地坐标</b>；渲染器在 pivot 之后提交）。
     *
     * <p><b>性能护栏（本类唯一的那个）</b>：世界里"那一格还是不是那个方块"的核对<b>不是每帧做</b>
     * —— 结果按 {@link #RESCAN_MS} 缓存（同主机 <b>每 250 ms 最多 256 次</b> {@code getBlockState}，
     * 稳态 ≈ 17 次/帧 @60fps，而不是 256 次/帧），每帧只做两件便宜事：
     * 被藏格子的锚点按自转角转一次（≤256 次 sin/cos）+ 走一遍连线（≤512 条）。
     *
     * @param world       客户端世界（{@code null} ⇒ 不画）
     * @param controller  控制器方块坐标（台账键 + 世界换算的锚点）
     * @param facing      机器朝向
     * @param seconds     世界时间（秒）—— 电弧的明灭周期
     * @param spinSeconds 自转时钟（{@link HostFxSwitch#spinSeconds()}）—— 被藏格子的锚点跟着它转
     */
    public static GuixuAccretionLightning.Frame frame(Level world, BlockPos controller, Direction facing,
                                                      double seconds, float spinSeconds) {
        // 🔴 2026-11-22：开关 = 客户端 [hostfx] structureLightning（**总闸**）× 机器面板「结构闪电」。
        //    收口只在本方法（HostFxExtraLayers 不再判一次）：跳过时立刻返回 EMPTY —— 不建模型、不核世界、
        //    读数归零，并把原因记进 skipReason（stats 里会写成「闪电 0/12 条(关:面板)」）。
        if (!HostFxSwitch.structureLightningOn(ShanhaiClientConfig.hostFxStructureLightning())) {
            skipReason = ShanhaiClientConfig.hostFxStructureLightning() ? "关:面板" : "关:配置";
            lastPresent = 0;
            lastNodes = 0;
            lastBlockReads = 0;
            lastOpenLinks = 0;
            lastArcs = 0;
            lastPeakAlpha = 0.0F;
            return GuixuAccretionLightning.Frame.EMPTY;
        }
        if (world == null || controller == null || facing == null) {
            skipReason = "无世界";
            return GuixuAccretionLightning.Frame.EMPTY;
        }
        final GuixuAccretionLightning mesh = model();
        if (mesh == null) {
            skipReason = "不画:载入失败";
            return GuixuAccretionLightning.Frame.EMPTY;
        }
        skipReason = "";
        refreshIfDue(world, controller, facing);
        final boolean[] present = presentCache;
        final float[] ax = anchorXCache;
        final float[] ay = anchorYCache;
        final float[] az = anchorZCache;
        final boolean[] rot = hiddenCache;
        final int n = present.length;
        final GuixuAccretionLightning.Point[] centers = new GuixuAccretionLightning.Point[n];
        for (int i = 0; i < n; i++) {
            if (!present[i]) {
                continue;
            }
            float x = ax[i];
            float y = ay[i];
            float z = az[i];
            if (rot[i]) {
                // 被藏起来的格子是按"自转角"重画的 ⇒ 锚点必须跟着转，否则电弧会落在旧位置
                final float[] rotated = HostFxGeometry.rotateYDeg(
                        HostFxGeometry.groupAngleDeg(spinSeconds, HostFxGeometry.groupOf(y)), x, y, z);
                x = rotated[0];
                y = rotated[1];
                z = rotated[2];
            }
            centers[i] = new GuixuAccretionLightning.Point(x, y, z);
        }
        lastNodes = n;
        lastArcs = -1;
        final GuixuAccretionLightning.Frame frame = mesh.frame(seconds, controller.asLong(),
                i -> present[i], i -> centers[i], linkOrderCache);
        lastArcs = frame.arcs();
        lastPeakAlpha = frame.peakAlpha();
        return frame;
    }

    /** 世界核对结果多久重算一次（毫秒）—— 护栏：同一个 250 ms 窗内一族主机只查一次。 */
    public static final long RESCAN_MS = 250L;

    private static boolean[] presentCache = new boolean[0];
    private static float[] anchorXCache = new float[0];
    private static float[] anchorYCache = new float[0];
    private static float[] anchorZCache = new float[0];
    private static boolean[] hiddenCache = new boolean[0];
    /**
     * 连线的<b>扫描顺序</b>（下标 = {@code mesh.links()} 的下标），按"这条连线穿过空气的比例"从高到低。
     *
     * <p>🔴 2026-11-22 第二改：实机「闪电好像没看到」的头号原因就是扫描顺序固定 ——
     * {@link GuixuAccretionLightning#MAX_ARCS}=12 个名额被排在最前面的 12 条"活着"的连线吃掉，
     * 而那 12 条可能全在机壳内部（被深度测试整条剔掉）。按"穿空气比例"排序后，这 12 条换成
     * <b>从外面真看得见</b>的那批（端点之间那一段在空气里 ⇒ 不会被机壳挡住）。
     */
    private static int[] linkOrderCache = new int[0];
    /** 上一次核对里"半段以上是空气"的连线数（读数：太少就说明这套结构外面没有开敞跨距）。 */
    private static int lastOpenLinks;
    /** 上一帧真的放了几条弧 / 最亮那条的包络（读数，进 {@code draw} 行）。 */
    private static int lastArcs = -1;
    private static float lastPeakAlpha;
    private static long lastScanMs;
    private static long lastScanKey = Long.MIN_VALUE;
    private static long lastScanStamp = Long.MIN_VALUE;

    /** 上一次核对真正跑了几次 {@code getBlockState}（读数：护栏没生效的话它会接近 256/帧）。 */
    private static int lastBlockReads;

    /** 上一次核对读了几个格子（日志用）。 */
    public static int lastBlockReads() {
        return lastBlockReads;
    }

    /** 按 {@link #RESCAN_MS} 重算"哪一格还算数"（台账版本变了也立刻重算）。 */
    private static void refreshIfDue(Level world, BlockPos controller, Direction facing) {
        final long now = System.currentTimeMillis();
        final long stamp = HostFxHide.stamp();
        if (!forceRescan && controller.asLong() == lastScanKey && stamp == lastScanStamp
                && now - lastScanMs < RESCAN_MS && presentCache.length > 0) {
            return;
        }
        forceRescan = false;
        lastScanKey = controller.asLong();
        lastScanStamp = stamp;
        lastScanMs = now;
        final GuixuAccretionLightning mesh = model();
        if (mesh == null) {
            presentCache = new boolean[0];
            return;
        }
        final List<GuixuAccretionLightning.Node> nodes = mesh.nodes();
        final int n = nodes.size();
        presentCache = new boolean[n];
        anchorXCache = new float[n];
        anchorYCache = new float[n];
        anchorZCache = new float[n];
        hiddenCache = new boolean[n];
        final Map<Long, BlockState> ledger = HostFxHide.ledger(controller);
        int reads = 0;
        int presentCount = 0;
        for (int i = 0; i < n; i++) {
            final GuixuAccretionLightning.Node node = nodes.get(i);
            final BlockPos at = HostFxGeometry.worldFromGenerator(controller, facing,
                    node.x(), node.y() + HostFxGeometry.RING_Y, node.z());
            if (!world.hasChunkAt(at)) {
                continue;
            }
            BlockState state = world.getBlockState(at);
            reads++;
            final boolean hidden = state.isAir() && ledger != null && ledger.containsKey(at.asLong());
            if (hidden) {
                state = ledger.get(at.asLong());
            }
            if (state == null) {
                continue;
            }
            final Block expected = expected(node.symbol());
            if (expected == null || state.getBlock() != expected) {
                continue;
            }
            presentCache[i] = true;
            presentCount++;
            hiddenCache[i] = hidden;
            anchorXCache[i] = node.x() + node.nx() * .55F;
            anchorYCache[i] = node.y() + node.ny() * .55F;
            anchorZCache[i] = node.z() + node.nz() * .55F;
        }
        lastBlockReads = reads;
        lastPresent = presentCount;
        buildLinkOrder(world, controller, facing, mesh, ledger);
    }

    /**
     * 算"每条连线穿过空气的比例"，并按它从高到低排出扫描顺序。
     *
     * <p>采样 4 个中间点（端点本身在方块表面上、必然不是空气，所以跳过）。
     * 被渲染藏起来的格子用台账里的原方块判断（与节点核对同一套口径）。
     * 每 {@link #RESCAN_MS} 才跑一次：{@code 208 条 × 4 点 = 832} 次 {@code getBlockState} / 250 ms。
     */
    private static void buildLinkOrder(Level world, BlockPos controller, Direction facing,
                                       GuixuAccretionLightning mesh, Map<Long, BlockState> ledger) {
        final List<GuixuAccretionLightning.Link> links = mesh.links();
        final int n = links.size();
        if (n == 0) {
            linkOrderCache = new int[0];
            lastOpenLinks = 0;
            return;
        }
        final List<GuixuAccretionLightning.Node> nodes = mesh.nodes();
        final int[] order = new int[n];
        final int[] openScore = new int[n];
        int openCount = 0;
        final int samples = 4;
        for (int i = 0; i < n; i++) {
            final GuixuAccretionLightning.Link link = links.get(i);
            order[i] = i;
            final GuixuAccretionLightning.Node a = nodes.get(link.from());
            final GuixuAccretionLightning.Node b = nodes.get(link.to());
            int open = 0;
            for (int k = 1; k <= samples; k++) {
                final double t = (double) k / (samples + 1);
                final int gx = (int) Math.round(a.x() + (b.x() - a.x()) * t);
                final int gy = (int) Math.round(a.y() + (b.y() - a.y()) * t);
                final int gz = (int) Math.round(a.z() + (b.z() - a.z()) * t);
                final BlockPos at = HostFxGeometry.worldFromGenerator(controller, facing,
                        gx, gy + HostFxGeometry.RING_Y, gz);
                if (!world.hasChunkAt(at)) {
                    continue;
                }
                BlockState state = world.getBlockState(at);
                if (state.isAir() && ledger != null && ledger.containsKey(at.asLong())) {
                    state = ledger.get(at.asLong());
                }
                if (state != null && state.isAir()) {
                    open++;
                }
            }
            openScore[i] = open;
            if (open * 2 >= samples) {
                openCount++;
            }
        }
        // 稳定排序：开敞多的排前面（同分开敞的保持原顺序 ⇒ 完全确定性）
        for (int i = 1; i < n; i++) {
            final int keyScore = openScore[order[i]];
            final int keyIndex = order[i];
            int j = i - 1;
            while (j >= 0 && (openScore[order[j]] < keyScore
                    || (openScore[order[j]] == keyScore && order[j] > keyIndex))) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = keyIndex;
        }
        linkOrderCache = order;
        lastOpenLinks = openCount;
    }

    /** 强制下一次 {@link #frame} 重新核对（换维度 / 台账刚被重写时由本类自己或渲染器调）。 */
    private static boolean forceRescan = true;

    /** 让下一次 {@code frame} 重新读世界（结构刚成型、或玩家改了方块）。 */
    public static void invalidate() {
        forceRescan = true;
    }

    /** 符号 → 期望方块（查不到 / 查成空气 ⇒ {@code null} = 这一格不当落点）。 */
    private static Block expected(char symbol) {
        if (EXPECTED.containsKey(symbol)) {
            final Block cached = EXPECTED.get(symbol);
            return cached == Blocks.AIR ? null : cached;
        }
        Block resolved = null;
        for (String[] entry : GuixuVisionCoreStructure.SYMBOL_BLOCKS) {
            if (entry[0].charAt(0) != symbol) {
                continue;
            }
            try {
                resolved = BuiltInRegistries.BLOCK.get(new ResourceLocation(entry[1]));
            } catch (Throwable ignored) {
                resolved = null;
            }
            break;
        }
        final Block stored = resolved == null ? Blocks.AIR : resolved;
        EXPECTED.put(symbol, stored);
        return stored == Blocks.AIR ? null : stored;
    }
}
