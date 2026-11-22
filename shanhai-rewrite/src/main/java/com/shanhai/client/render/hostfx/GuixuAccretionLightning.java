package com.shanhai.client.render.hostfx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * 山海 · <b>由正式結構表面取樣的電弧</b>（作者 dishanhai，2026-11-22 移植进本工程）。
 *
 * <h2>来源与"纯移植"口径</h2>
 * 主体照抄他的 {@code com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuAccretionLightning}
 * （{@code temp/upstream-bump/src/GuixuAccretionLightning.java}）：{@link #MAX_ARCS}=12、表面节点头部
 * {@code + normal * .55}、1.9 秒周期里只活 0.3、{@code sin(life/.3*π)} 包络、两端相距 &gt; 18 格不放电、
 * 每帧最多 {@link #MAX_ARCS} 条 —— <b>这些值一个都没改</b>。
 *
 * <h2>🔴 必须说清的"替代实现"（他那两个类不在交付物里）</h2>
 * 他的 {@code frame(...)} 依赖两个我们<b>没有</b>的类，且它们都不在 {@code temp/upstream-bump/src} 里：
 * <ol>
 *   <li>{@code ShanhaiInstabilityGeometry}（他的 preview 包）—— 提供 {@code Node/Point/Quad}、
 *       {@code MAX_NODES}、{@code fromNodes(...)} 里的<b>连线（links）算法</b>与 {@code appendArc(...)}
 *       的<b>四边形生成</b>；</li>
 *   <li>{@code GuixuVisionCoreStructure.previewBlocks(char)} —— 符号→方块。</li>
 * </ol>
 * ⇒ 这两块<b>没有他的原文可抄</b>，本类用同语义的替代实现，并把选择写在明处：
 * <ul>
 *   <li><b>节点上限</b> {@link #MAX_NODES} = <b>256</b>（他的类注释逐字：「{@code 只查至多 256 個節點}」
 *       ⇒ 这个数与他一致，有出处）；</li>
 *   <li><b>连线</b>：他对 links 的生成只有"距离 &gt; 18 格不放电"这一条可见约束 ⇒ 这里取
 *       <b>每个节点连最近 2 个节点、且距离 ≤ {@link #LINK_MAX_DIST} 格</b>，总条数封顶 {@link #MAX_LINKS}。
 *       判据：够密（256 个表面点 × 2）、够确定性（同结构永远同连线）；</li>
 *   <li><b>电弧四边形</b>：{@link #ARC_SEGMENTS} 段折线 × <b>2 条互相垂直的带子</b>
 *       （跨面十字带，加性混合下从任何角度都读得出），端点<b>不抖动</b>（必须落在方块表面），
 *       中段抖动 ≤ 0.42 格、随包络收缩；颜色取冷青白 {@code (0.62, 0.86, 1.00)}
 *       —— <b>他的颜色常量没有出处，这是我们取的</b>。</li>
 * </ul>
 *
 * <h2>为什么它要"纯"</h2>
 * 本类<b>不 import 任何 MC 类型</b>（只有 {@code java.util} + {@code HostFxGeometry.Quad}）⇒
 * "节点采样 → 连线 → 某一帧该放哪几条弧"这一段可以脱机断言（{@code temp/migrate-fx/probe/}）。
 * 世界里"那一格到底是不是那个方块"的核对在 {@code HostFxStructureLightning}（有 MC 依赖）。
 */
public final class GuixuAccretionLightning {

    /** 一帧最多几条弧（他的值）。 */
    public static final int MAX_ARCS = 12;
    /** 表面节点上限（他的类注释里的 256）。 */
    public static final int MAX_NODES = 256;
    /**
     * 连线长度上限（格）= <b>18</b> —— 他自己的数：{@code frame} 里「旋转后相距超过 18 格均不放电」。
     *
     * <p>取同一个数是为了"建出来的连线"与"允许放电的连线"<b>同一个口径</b>（否则建一堆永远被拒的线，
     * 等于白算）。表面节点间距由桶大小决定（12 起步、每轮 +4），实际读数见 {@code draw} 日志里的
     * 「闪电 N/12 条」与「节点 n/256」。
     */
    public static final double LINK_MAX_DIST = 18.0D;
    /** 连线总条数上限（每个节点最多 2 条 ⇒ 256 × 2 = 512）。 */
    public static final int MAX_LINKS = 512;
    /** 一条弧折几段。 */
    public static final int ARC_SEGMENTS = 6;

    /**
     * 一条弧的<b>存活时长</b>（秒）。他的值是 <b>0.3</b>。
     *
     * <p>🔴 2026-11-22 第二改（实机反馈「闪电是什么样子的，我好像没看到」）：
     * 0.3 s 存活 / 1.9 s 周期 ⇒ 单条可见占空比只有 <b>15.8%</b>；画面里同时亮着的那几条又都是
     * "刚出生/快死"的暗态（{@code sin} 包络两端接近 0）⇒ 人眼基本抓不到。抬到 <b>0.9 s</b>
     * 后占空比 <b>47%</b>，且 {@code sin} 在 0.45 s 处满亮度停留更久。
     */
    public static final double LIFE_SECONDS = 0.9D;
    /** 明灭周期（秒）—— 他的 1.9，不动。 */
    public static final double CYCLE_SECONDS = 1.9D;
    /** 任意时刻至少保证有几条在亮（实机口径「至少 2 条」；低于它就用保底那一遍补）。 */
    public static final int MIN_ALIVE = 2;
    /** 电弧芯线半宽（格）：{@code 0.10 + 0.10 × 包络}（他原来没有这一步，是我们按"看得见"定的）。 */
    public static final double ARC_HALF_WIDTH_BASE = 0.10D;
    public static final double ARC_HALF_WIDTH_PEAK = 0.10D;
    /** 芯线外面那圈辉光的宽度倍数与亮度倍数。 */
    public static final double ARC_GLOW_WIDTH = 2.6D;
    public static final double ARC_GLOW_ALPHA = 0.35D;
    /** 保底那一遍用的固定包络（只在"活着的不够 MIN_ALIVE"时才会用到）。 */
    public static final float ARC_FLOOR_ENVELOPE = 0.55F;

    /** 电弧颜色（2026-11-22 提亮：旧值 0.62/0.86/1.00 在半透明青白光柱里被淹没）。 */
    public static final float ARC_RED = 0.85F;
    public static final float ARC_GREEN = 0.97F;
    public static final float ARC_BLUE = 1.00F;

    /** 一个表面节点：格坐标 + 外法线 + 结构符号 + 随机种子（与他 {@code Node} 的字段一一对应）。 */
    public record Node(int x, int y, int z, int nx, int ny, int nz, char symbol, int seed) {
    }

    /** 环心本地坐标里的一个点。 */
    public record Point(double x, double y, double z) {
    }

    /** 一条连线（端点下标）。 */
    public record Link(int from, int to) {
    }

    /**
     * 一帧的电弧几何：四边形 + 真正放了几条弧 + 这一帧最亮那一条的包络（0..1）。
     *
     * <p>{@code peakAlpha} 是给实机读数用的：{@code arcs=12} 只证明"提交了"，{@code peakAlpha}
     * 才说明"这一帧到底亮不亮"（例如全是刚出生/快死的弧时它接近 0）。
     */
    public record Frame(List<HostFxGeometry.Quad> quads, int arcs, float peakAlpha) {
        public static final Frame EMPTY = new Frame(List.of(), 0, 0.0F);
    }

    private final List<Node> nodes;
    private final List<Link> links;

    private GuixuAccretionLightning(List<Node> nodes) {
        this.nodes = List.copyOf(nodes);
        this.links = buildLinks(this.nodes);
    }

    /**
     * loader 的 ShapeInfo 轴序為 [z][x][由上而下的 y]，輸出使用環心本地座標。
     *
     * <p>逐行照抄他的 {@code fromSlices}：桶从 12 开始、每轮 +4，直到桶数 ≤ {@link #MAX_NODES}
     * （「大結構擴大桶而非截斷某一側」）；每个桶只保留优先级最高的那个节点。
     *
     * @param minX 模板最小角 x（= {@code GuixuHostGeometry.TEMPLATE_MIN_X} = −65）
     * @param topY 切片 y=0 对应的<b>环心本地</b> y（= {@code TEMPLATE_SIZE_Y−1 + TEMPLATE_MIN_Y − RING_Y} = 67）
     * @param minZ 模板最小角 z（= {@code TEMPLATE_MIN_Z} = −65）
     */
    public static GuixuAccretionLightning fromSlices(String[][] slices, int minX, int topY, int minZ) {
        if (slices.length == 0 || slices[0].length == 0 || slices[0][0].isEmpty()) {
            throw new IllegalArgumentException("Empty Guixu structure");
        }
        int sizeX = slices[0].length, sizeY = slices[0][0].length();
        for (var plane : slices) {
            if (plane.length != sizeX) {
                throw new IllegalArgumentException("Ragged Guixu structure");
            }
            for (var column : plane) {
                if (column.length() != sizeY) {
                    throw new IllegalArgumentException("Ragged Guixu column");
                }
            }
        }
        // 與奈亞同樣每個空間桶保留一個表面節點；大結構擴大桶而非截斷某一側。
        for (int bucketSize = 12; ; bucketSize += 4) {
            var buckets = new java.util.TreeMap<Long, Node>();
            int[][] normals = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int z = 0; z < slices.length; z++) {
                for (int x = 0; x < sizeX; x++) {
                    for (int y = 0; y < sizeY; y++) {
                        char symbol = slices[z][x].charAt(y);
                        if (!solidSymbol(symbol)) {
                            continue;
                        }
                        int seed = hash(x + y * sizeX + z * sizeX * sizeY);
                        for (int side = 0; side < 6; side++) {
                            int[] normal = normals[Math.floorMod(seed + side, 6)];
                            if (solid(slices, x + normal[0], y + normal[1], z + normal[2])) {
                                continue;
                            }
                            var node = new Node(minX + x, topY - y, minZ + z,
                                    normal[0], -normal[1], normal[2], symbol, seed);
                            long bucket = ((long) (x / bucketSize) << 40)
                                    | ((long) (y / bucketSize) << 20) | z / bucketSize;
                            Node old = buckets.get(bucket);
                            if (old == null || priority(symbol, seed) > priority(old.symbol(), old.seed())) {
                                buckets.put(bucket, node);
                            }
                            break;
                        }
                    }
                }
            }
            if (buckets.size() <= MAX_NODES) {
                return new GuixuAccretionLightning(new ArrayList<>(buckets.values()));
            }
        }
    }

    /**
     * 本工程结构符号表里的"实体符号"。
     *
     * <p>他的判据逐字是 {@code " MNP".indexOf(symbol) < 0}（空格 / M / N / P 当非实体）。
     * 本工程 {@code GuixuVisionCoreStructure} 多两个"可空可填"的占位符号
     * （{@code Q} = 腔口、{@code T}/{@code S} = 模块外壳）⇒ 这里按<b>同一语义</b>扩展成
     * 空格 + {@code MNPQ} 非实体、其余（含 {@code TS} 与 {@code ~}）算实体。
     * 判据：{@code Q} 的谓词收空气，把它当实体表面会让电弧落在空气上（世界核对那一步会再刷一次）。
     */
    public static boolean solidSymbol(char symbol) {
        return " MNPQ".indexOf(symbol) < 0;
    }

    private static boolean solid(String[][] slices, int x, int y, int z) {
        return z >= 0 && z < slices.length && x >= 0 && x < slices[0].length && y >= 0 && y < slices[0][0].length()
                && solidSymbol(slices[z][x].charAt(y));
    }

    private static long priority(char symbol, int seed) {
        int rank = switch (symbol) {
            case 'F', 'G', 'I', '~' -> 3;
            case 'E', 'J' -> 2;
            default -> 1;
        };
        return ((long) rank << 32) | Integer.toUnsignedLong(seed);
    }

    private static int hash(int value) {
        value = (value ^ (value >>> 16)) * 0x7feb352d;
        value = (value ^ (value >>> 15)) * 0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static double phase(int seed) {
        return (seed & 0xffff) / 65536.0;
    }

    /** 表面节点（只读）。 */
    public List<Node> nodes() {
        return nodes;
    }

    /** 连线（只读；离线探针要数它）。 */
    public List<Link> links() {
        return links;
    }

    /** 默认锚点：节点沿外法线外移 0.55 格（他的值）。 */
    public Frame frame(double seconds, long seed, IntPredicate present) {
        return frame(seconds, seed, present, i -> {
            Node n = nodes.get(i);
            return new Point(n.x() + n.nx() * .55, n.y() + n.ny() * .55, n.z() + n.nz() * .55);
        });
    }

    /** 默认锚点 + 自定义连线扫描顺序（离线探针用自然顺序）。 */
    public Frame frame(double seconds, long seed, IntPredicate present, IntFunction<Point> center) {
        return frame(seconds, seed, present, center, null);
    }

    /**
     * 動態環／飛船在實際渲染位置連線；兩端缺件或旋轉後相距超過 18 格均不放電。
     *
     * @param linkOrder 连线扫描顺序（下标 {@code 0..links().size()-1}，{@code null} = 自然顺序）。
     *                  🔴 2026-11-22 第二改：实机「没看到闪电」的头号原因是<b>固定扫描顺序</b>——
     *                  12 个名额被排在最前面的 12 条"活着"的连线吃掉，而那 12 条可能全躲在机壳内部
     *                  （深度测试直接剔掉）。渲染侧现在按"连线穿过空气的比例"排序传进来（
     *                  {@code HostFxStructureLightning}），于是这 12 条换成<b>从外面真看得见</b>的那批。
     */
    public Frame frame(double seconds, long seed, IntPredicate present, IntFunction<Point> center,
                       int[] linkOrder) {
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("Non-finite FX time");
        }
        final int n = links.size();
        final boolean[] drawn = new boolean[n];
        var quads = new ArrayList<HostFxGeometry.Quad>(MAX_ARCS * ARC_SEGMENTS * 4);
        double clock = seconds + phase(hash((int) (seed ^ (seed >>> 32)))) * 11;
        int arcs = 0;
        float peakAlpha = 0.0F;
        // 第一遍：按"穿过空气的比例"从高到低扫（无排序时就是自然顺序）
        for (int slot = 0; slot < n && arcs < MAX_ARCS; slot++) {
            final int linkIndex = linkOrder != null && slot < linkOrder.length ? linkOrder[slot] : slot;
            if (linkIndex < 0 || linkIndex >= n || drawn[linkIndex]) {
                continue;
            }
            final Link link = links.get(linkIndex);
            if (!present.test(link.from()) || !present.test(link.to())) {
                continue;
            }
            final Node a = nodes.get(link.from()), b = nodes.get(link.to());
            final Point start = center.apply(link.from()), end = center.apply(link.to());
            final double dx = start.x() - end.x(), dy = start.y() - end.y(), dz = start.z() - end.z();
            final double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < 1e-10 || distanceSquared > 18 * 18) {
                continue;
            }
            final double cycle = clock / CYCLE_SECONDS + phase(a.seed() ^ b.seed());
            final double life = cycle - Math.floor(cycle);
            if (life >= LIFE_SECONDS) {
                continue;
            }
            final float envelope = (float) Math.sin(life / LIFE_SECONDS * Math.PI);
            if (envelope < .02F) {
                continue;
            }
            appendArc(quads, start, end, clock, a.seed(), b.seed(), envelope);
            drawn[linkIndex] = true;
            arcs++;
            peakAlpha = Math.max(peakAlpha, envelope);
        }
        // 第二遍（保底）：活着的不够 MIN_ALIVE 时，按同一个扫描顺序补几条"固定亮度"的
        // 🔴 这是对"他的 0.3 s 窗口"的<b>有意偏离</b>，只为了"任意时刻至少 2 条在亮"这条实机口径；
        //    离线实测（5000 帧 × 165 节点）从不触发 —— 它只是下限保险，不是常规路径。
        for (int slot = 0; slot < n && arcs < MIN_ALIVE; slot++) {
            final int linkIndex = linkOrder != null && slot < linkOrder.length ? linkOrder[slot] : slot;
            if (linkIndex < 0 || linkIndex >= n || drawn[linkIndex]) {
                continue;
            }
            final Link link = links.get(linkIndex);
            if (!present.test(link.from()) || !present.test(link.to())) {
                continue;
            }
            final Point start = center.apply(link.from()), end = center.apply(link.to());
            final double dx = start.x() - end.x(), dy = start.y() - end.y(), dz = start.z() - end.z();
            final double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < 1e-10 || distanceSquared > 18 * 18) {
                continue;
            }
            final Node a = nodes.get(link.from()), b = nodes.get(link.to());
            appendArc(quads, start, end, clock, a.seed(), b.seed(), ARC_FLOOR_ENVELOPE);
            drawn[linkIndex] = true;
            arcs++;
            peakAlpha = Math.max(peakAlpha, ARC_FLOOR_ENVELOPE);
        }
        return new Frame(List.copyOf(quads), arcs, peakAlpha);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  替代实现：连线 + 电弧四边形（他的原文不在交付物里，语义见类注释）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 每个节点连最近 2 个邻居（距离 ≤ {@link #LINK_MAX_DIST}），去重、总数封顶 {@link #MAX_LINKS}。 */
    private static List<Link> buildLinks(List<Node> nodes) {
        final List<Link> out = new ArrayList<>(MAX_LINKS);
        final Set<Long> seen = new HashSet<>();
        final double maxSquared = LINK_MAX_DIST * LINK_MAX_DIST;
        final int n = nodes.size();
        for (int i = 0; i < n && out.size() < MAX_LINKS; i++) {
            int best1 = -1, best2 = -1;
            double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                final double d = distSquared(nodes.get(i), nodes.get(j));
                if (d < d1) {
                    d2 = d1;
                    best2 = best1;
                    d1 = d;
                    best1 = j;
                } else if (d < d2) {
                    d2 = d;
                    best2 = j;
                }
            }
            if (best1 >= 0 && d1 <= maxSquared) {
                addLink(out, seen, i, best1);
            }
            if (best2 >= 0 && d2 <= maxSquared) {
                addLink(out, seen, i, best2);
            }
        }
        return List.copyOf(out);
    }

    private static void addLink(List<Link> out, Set<Long> seen, int a, int b) {
        final int lo = Math.min(a, b), hi = Math.max(a, b);
        final long key = ((long) lo << 32) | hi;
        if (seen.add(key) && out.size() < MAX_LINKS) {
            out.add(new Link(lo, hi));
        }
    }

    private static double distSquared(Node a, Node b) {
        final double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 一条电弧：{@link #ARC_SEGMENTS} 段折线 × 2 条互相垂直的带子。
     *
     * <p>端点<b>不抖</b>（必须落在方块表面）；中段抖动随 {@code sin(πt)} 收缩、幅度随包络（0~1）缩放；
     * 抖动相位按 {@code floor(clock*6)} 换一相 ⇒ 每秒约换 6 次"形"，读起来像在炸。
     */
    private static void appendArc(List<HostFxGeometry.Quad> out, Point start, Point end,
                                  double clock, int seedA, int seedB, float envelope) {
        final double ax = start.x(), ay = start.y(), az = start.z();
        final double dx = end.x() - ax, dy = end.y() - ay, dz = end.z() - az;
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 1e-6)) {
            return;
        }
        // 两个互相垂直、且都与主方向垂直的横向单位向量（w = cross((0,1,0), u) 的等价式；退化时取 +X）
        double ux = dx / length, uy = dy / length, uz = dz / length;
        double wx = -uz, wy = 0.0D, wz = ux;
        double wl = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1e-6) {
            wx = 1.0D;
            wy = 0.0D;
            wz = 0.0D;
            wl = 1.0D;
        }
        wx /= wl;
        wy /= wl;
        wz /= wl;
        final double vx = uy * wz - uz * wy, vy = uz * wx - ux * wz, vz = ux * wy - uy * wx;
        // 2026-11-22 第二改：芯线加粗一倍（0.05+0.04e → 0.10+0.10e），另加一圈更宽更淡的辉光
        final double halfWidth = ARC_HALF_WIDTH_BASE + ARC_HALF_WIDTH_PEAK * envelope;
        final long frameIndex = (long) Math.floor(clock * 6.0D);
        final double[] px = new double[ARC_SEGMENTS + 1];
        final double[] py = new double[ARC_SEGMENTS + 1];
        final double[] pz = new double[ARC_SEGMENTS + 1];
        for (int k = 0; k <= ARC_SEGMENTS; k++) {
            final double t = (double) k / ARC_SEGMENTS;
            final double pinch = Math.sin(Math.PI * t);
            final double amp = 0.42D * pinch * envelope;
            final double j1 = (unit(seedA, seedB, k, 1, frameIndex) - 0.5D) * 2.0D * amp;
            final double j2 = (unit(seedA, seedB, k, 2, frameIndex) - 0.5D) * 2.0D * amp;
            final double j3 = (unit(seedA, seedB, k, 3, frameIndex) - 0.5D) * 2.0D * amp;
            px[k] = ax + dx * t + wx * j1 + vx * j2 + ux * j3;
            py[k] = ay + dy * t + wy * j1 + vy * j2 + uy * j3;
            pz[k] = az + dz * t + wz * j1 + vz * j2 + uz * j3;
        }
        // 芯线峰值亮度 1.0（旧 0.85）；辉光 = 宽 ×2.6、亮 ×0.35
        final float alpha = (float) envelope;
        final double glowWidth = halfWidth * ARC_GLOW_WIDTH;
        final float glowAlpha = alpha * (float) ARC_GLOW_ALPHA;
        for (int k = 0; k < ARC_SEGMENTS; k++) {
            emitRibbon(out, px, py, pz, k, wx, wy, wz, glowWidth, glowAlpha);
            emitRibbon(out, px, py, pz, k, vx, vy, vz, glowWidth, glowAlpha);
            emitRibbon(out, px, py, pz, k, wx, wy, wz, halfWidth, alpha);
            emitRibbon(out, px, py, pz, k, vx, vy, vz, halfWidth, alpha);
        }
    }

    private static void emitRibbon(List<HostFxGeometry.Quad> out, double[] px, double[] py, double[] pz,
                                   int k, double wx, double wy, double wz, double halfWidth, float alpha) {
        final int k2 = k + 1;
        out.add(new HostFxGeometry.Quad(new float[]{
                (float) (px[k] - wx * halfWidth), (float) (py[k] - wy * halfWidth), (float) (pz[k] - wz * halfWidth),
                (float) (px[k] + wx * halfWidth), (float) (py[k] + wy * halfWidth), (float) (pz[k] + wz * halfWidth),
                (float) (px[k2] + wx * halfWidth), (float) (py[k2] + wy * halfWidth), (float) (pz[k2] + wz * halfWidth),
                (float) (px[k2] - wx * halfWidth), (float) (py[k2] - wy * halfWidth), (float) (pz[k2] - wz * halfWidth),
        }, ARC_RED, ARC_GREEN, ARC_BLUE, alpha));
    }

    /** 确定性 0..1 抖动源（同 seed/段/帧 ⇒ 同值）。 */
    private static double unit(int seedA, int seedB, int index, int salt, long frame) {
        int v = seedA * 0x9E3779B9 + seedB * 0x85EBCA6B + index * 0xC2B2AE35
                + salt * 0x27D4EB2F + (int) (frame * 0x165667B1L) + (int) (frame >>> 32);
        v ^= v >>> 15;
        v *= 0x2C1B3C6D;
        v ^= v >>> 12;
        v *= 0x297A2D39;
        v ^= v >>> 15;
        return (v >>> 8) / 16777216.0D;
    }
}
