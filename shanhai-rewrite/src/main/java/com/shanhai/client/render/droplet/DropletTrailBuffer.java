package com.shanhai.client.render.droplet;

/**
 * 一条水滴轨迹的<b>世界空间路点环形缓冲</b>（零 Minecraft 依赖，离线探针可直接跑）。
 *
 * <h2>一、为什么单独一个类</h2>
 * 「轨迹是走过的路，写进去就不许再改」这条口径的<b>全部实现</b>都在这里：
 * 采样点只增不改、淘汰只按"弧长 / 年龄"两个截止条件、清空只有一个入口。
 * 把它与 Minecraft 的 {@code ClientLevel}/{@code Entity} 解耦之后，离线探针就能
 * <b>对着真正在用的这份代码</b>做"转弯自证"（L 形路径 + 逐位快照 + 换维度清空），
 * 而不是在探针里复制一份实现（复制品的自检永远自洽，等于没测）。
 *
 * <h2>二、数据结构</h2>
 * 定长数组 + {@code head}/{@code size}：
 * <ul>
 *   <li>{@code pushPoint} 只写下标 {@code head} 然后 {@code head = (head+1) % POINTS}；</li>
 *   <li>最老的点 = {@code indexFromOldest(0)}，淘汰 = {@code size--}（O(1)，不搬数据）；</li>
 *   <li>已经写下的坐标<b>没有第二个写入点</b> ⇒ 之后怎么转头都动不了它。</li>
 * </ul>
 *
 * <h2>三、淘汰（与 {@link DropletProfile} 同一组常量）</h2>
 * 两个截止条件任一满足就丢最老的点：<b>弧长</b> ≤ {@link DropletProfile#BEAM_MAX_ARC}（24 格）、
 * <b>年龄</b> ≤ {@link DropletProfile#BEAM_LIFE}（2 秒）。年龄在采样时按 {@code tick} 丢，
 * 弧长在 {@link #collect} 里按"从新到老累计"丢（弧长只有渲染时才需要，不必每 tick 算）。
 */
public final class DropletTrailBuffer {

    /** 最多存几个路点（= 2 秒 × 20 tick/s + 余量）。 */
    public static final int POINTS = 48;

    private final double[] x = new double[POINTS];
    private final double[] y = new double[POINTS];
    private final double[] z = new double[POINTS];
    private final int[] tick = new int[POINTS];

    /** 下一个写入槽。 */
    private int head;
    /** 有效点数。 */
    private int size;

    /** 上一次采样的位置（瞬移判据用）。 */
    private boolean hasLast;
    private double lastX;
    private double lastY;
    private double lastZ;

    public int size() {
        return this.size;
    }

    public double xAt(int fromOldest) {
        return this.x[indexFromOldest(fromOldest)];
    }

    public double yAt(int fromOldest) {
        return this.y[indexFromOldest(fromOldest)];
    }

    public double zAt(int fromOldest) {
        return this.z[indexFromOldest(fromOldest)];
    }

    public int tickAt(int fromOldest) {
        return this.tick[indexFromOldest(fromOldest)];
    }

    /** 第 {@code fromOldest} 老的点（0 = 最老）在数组里的下标。 */
    public int indexFromOldest(int fromOldest) {
        return (this.head - this.size + fromOldest + POINTS * 2) % POINTS;
    }

    /** 清空（换维度 / 瞬移 / 实体消失）。 */
    public void clear() {
        this.head = 0;
        this.size = 0;
        this.hasLast = false;
    }

    /**
     * 记一个世界坐标点（<b>写进去之后永不修改</b>）。
     *
     * <p>同一 tick 重复调用会写进两个相邻槽（探针/帧循环都不会这么做，正常路径是每 tick 一次）。
     */
    public void pushPoint(double px, double py, double pz, int atTick) {
        this.x[this.head] = px;
        this.y[this.head] = py;
        this.z[this.head] = pz;
        this.tick[this.head] = atTick;
        this.head = (this.head + 1) % POINTS;
        if (this.size < POINTS) {
            this.size++;
        }
    }

    /** 丢掉最老的 {@code count} 个点（{@code count} 超过 size 时全部丢光）。 */
    public void dropOldest(int count) {
        this.size -= Math.min(count, this.size);
    }

    /** 丢掉年龄超过 {@code lifeTicks} 的老点（年龄淘汰）。 */
    public void dropExpired(int nowTick, int lifeTicks) {
        int drop = 0;
        while (drop < this.size && nowTick - this.tickAt(drop) > lifeTicks) {
            drop++;
        }
        if (drop > 0) {
            dropOldest(drop);
        }
    }

    /**
     * 记录本次采样位置，并回答"这次是不是瞬移"。
     *
     * @return {@code true} = 一 tick 位移超过 {@code maxJump} 格（调用方应当 {@link #clear()}）
     */
    public boolean isTeleport(double px, double py, double pz, double maxJump) {
        boolean jumped = false;
        if (this.hasLast) {
            final double dx = px - this.lastX;
            final double dy = py - this.lastY;
            final double dz = pz - this.lastZ;
            jumped = dx * dx + dy * dy + dz * dz > maxJump * maxJump;
        }
        this.hasLast = true;
        this.lastX = px;
        this.lastY = py;
        this.lastZ = pz;
        return jumped;
    }

    /**
     * 按两个截止条件把要画的那一段路点整理出来（<b>最老在下标 0</b>，最新在 {@code n-1}）。
     *
     * @param nowTick  当前 tick
     * @param outX/outY/outZ/cost 输出数组（长度 ≥ {@link #POINTS}）
     * @return 保留的点数（0/1 = 画不出轨迹）
     */
    public int collect(int nowTick, double[] outX, double[] outY, double[] outZ, float[] cost) {
        if (this.size < 2) {
            return 0;
        }
        int kept = 0;
        double arc = 0.0D;
        double prevX = 0.0D;
        double prevY = 0.0D;
        double prevZ = 0.0D;
        for (int i = this.size - 1; i >= 0; i--) {      // 从最新往最老
            final int idx = indexFromOldest(i);
            final double px = this.x[idx];
            final double py = this.y[idx];
            final double pz = this.z[idx];
            if (kept > 0) {
                final double dx = px - prevX;
                final double dy = py - prevY;
                final double dz = pz - prevZ;
                arc += Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (arc > DropletProfile.BEAM_MAX_ARC) {
                    break;                              // 弧长到顶 ⇒ 丢掉这个与更老的点
                }
            }
            final double age = (nowTick - this.tick[idx]) / 20.0D;
            final double u = DropletProfile.beamCost(age, arc);
            if (kept > 0 && u >= 1.0D) {
                break;                                  // 年龄到顶
            }
            final int slot = POINTS - 1 - kept;          // 先倒着写，最后翻回来
            outX[slot] = px;
            outY[slot] = py;
            outZ[slot] = pz;
            cost[slot] = (float) u;
            kept++;
            prevX = px;
            prevY = py;
            prevZ = pz;
        }
        if (kept < 2) {
            return 0;
        }
        final int start = POINTS - kept;
        for (int i = 0; i < kept; i++) {
            outX[i] = outX[start + i];
            outY[i] = outY[start + i];
            outZ[i] = outZ[start + i];
            cost[i] = cost[start + i];
        }
        return kept;
    }
}
