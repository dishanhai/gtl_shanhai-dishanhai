package com.shanhai.client.render.droplet;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.droplet.ShanhaiDropletEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 水滴的<b>世界空间轨迹</b>与<b>世界空间推进环</b>的客户端记录器（2026-11-24 新增）。
 *
 * <h2>一、要解决的问题（用户原话）</h2>
 * 「<b>然后轨迹和推进环我希望是那种真正的轨迹的意思，就是已经走过的路的轨迹不会变了，
 * 不要水滴转向轨迹跟着转向，这很离谱</b>」。
 *
 * <p>老实现把尾迹做成"实体局部空间里的一段锥管"（从尾尖 {@code z=-1.5} 往后到 {@code z=-9.7}），
 * 整段跟着实体的 {@code yaw/pitch} 走 ⇒ <b>水滴一转头，整条轨迹就被甩到新方向上</b>；
 * 推进环同理（每帧由"当前朝向"现算位置）。这在直线攻击时看不出来，一转向就非常离谱。
 *
 * <h2>二、本类的口径（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>每 client tick 采样一次世界坐标</b>（{@code x,y,z,tick}）进
 *       {@link DropletTrailBuffer} 的定长环形缓冲；</li>
 *   <li><b>写进世界空间之后永不修改</b> —— 之后水滴怎么转头，这些点一个字都不动；</li>
 *   <li>渲染时<b>只读这些路点</b>连成薄带，不再用"尾尖 + 朝向"算位置。</li>
 * </ol>
 *
 * <h2>三、淘汰规则（两个截止条件，任一满足就丢最老的点）</h2>
 * <ul>
 *   <li><b>弧长</b> ≤ {@link DropletProfile#BEAM_MAX_ARC}（24 格）；</li>
 *   <li><b>年龄</b> ≤ {@link DropletProfile#BEAM_LIFE}（2 秒）。</li>
 * </ul>
 * 两个条件都同时体现在 alpha 上（{@link DropletProfile#beamCost(double, double)}）⇒
 * 静止时老点按年龄淡到 0 并整条消失，<b>不会留一条永久光柱</b>。
 *
 * <h2>四、什么时候清空</h2>
 * <ul>
 *   <li><b>换维度 / 重进世界</b>：{@code mc.level} 换对象 ⇒ 整表清空
 *       （否则会出现一条横穿世界的长线 —— "复活点飞线"那一类经典 bug）；</li>
 *   <li><b>瞬移</b>：一 tick 内位移 &gt; {@link #TELEPORT_JUMP}（12 格）⇒ 只清该水滴这一条。
 *       判据必须大于"最高航速一 tick 的位移"（200 格/秒 = 10 格/tick），否则高速飞行会被误判；</li>
 *   <li><b>实体消失</b>：这一 tick 没扫到的 id 直接从表里删掉，不留残影。</li>
 * </ul>
 *
 * <h2>五、线程</h2>
 * 全部在客户端主线程（client tick + 渲染线程是同一个线程）⇒ 无锁。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletTrail {

    private static final String PREFIX = "[SHANHAI-DROPLET-TRAIL]";

    /** 每 tick 位移超过它（格）就算"瞬移"，清空该水滴的轨迹（必须 > 10 = 最高速一 tick 的位移）。 */
    public static final double TELEPORT_JUMP = 12.0D;

    /** 推进环的发射周期（tick）：1 秒 1 个（用户说"推进环还好"，频率不动）。 */
    private static final int RING_PERIOD_TICKS = 20;

    /** 一条轨迹上最多同时存在几个环（寿命 2 s ⇒ 2 个；留 1 个余量）。 */
    private static final int RINGS = DropletMesh.RING_SLOTS;

    /** 年龄淘汰用的 tick 数（= {@link DropletProfile#BEAM_LIFE} × 20）。 */
    private static final int LIFE_TICKS = (int) Math.ceil(DropletProfile.BEAM_LIFE * 20.0D);

    /** 环的年龄淘汰用的 tick 数。 */
    private static final int RING_LIFE_TICKS = (int) Math.ceil(DropletProfile.RING_LIFE * 20.0D);

    /** 一条轨迹的全部状态（路点 + 环）。 */
    private static final class Track {
        /** 世界空间路点（写进去永不修改；淘汰/清空都在它自己的类里）。 */
        final DropletTrailBuffer points = new DropletTrailBuffer();

        // 推进环：发射那一刻的世界位姿（圆心 = 尾尖，朝向 = 当时的 yaw/pitch）
        final double[] ringX = new double[RINGS];
        final double[] ringY = new double[RINGS];
        final double[] ringZ = new double[RINGS];
        final float[] ringYaw = new float[RINGS];
        final float[] ringPitch = new float[RINGS];
        final int[] ringTick = new int[RINGS];
        int ringHead;
        int ringSize;

        /** 最后一次被扫到的 tick（清理用）。 */
        int lastSeenTick;

        void pushRing(double px, double py, double pz, float yaw, float pitch, int atTick) {
            this.ringX[this.ringHead] = px;
            this.ringY[this.ringHead] = py;
            this.ringZ[this.ringHead] = pz;
            this.ringYaw[this.ringHead] = yaw;
            this.ringPitch[this.ringHead] = pitch;
            this.ringTick[this.ringHead] = atTick;
            this.ringHead = (this.ringHead + 1) % RINGS;
            if (this.ringSize < RINGS) {
                this.ringSize++;
            }
        }

        int ringIndexFromOldest(int ageIndex) {
            return (this.ringHead - this.ringSize + ageIndex + RINGS * 2) % RINGS;
        }

        void dropOldestRing(int count) {
            this.ringSize -= Math.min(count, this.ringSize);
        }
    }

    private static final Map<Integer, Track> BY_ID = new HashMap<>();

    /** 当前世界里<b>所有</b>水滴（每 tick 重建；轨迹属于外观，不按"名下"过滤）。 */
    private static final List<ShanhaiDropletEntity> ALL = new ArrayList<>();

    private static ClientLevel lastLevel;
    private static int clientTick;

    /** 共享暂存：给 {@link DropletMesh#buildTrail} 的路点数组（一次分配，长度 = 缓冲上限）。 */
    private static final double[] PX = new double[DropletTrailBuffer.POINTS];
    private static final double[] PY = new double[DropletTrailBuffer.POINTS];
    private static final double[] PZ = new double[DropletTrailBuffer.POINTS];
    private static final float[] COST = new float[DropletTrailBuffer.POINTS];
    /** 环的基向量暂存：{@code [0..2]=right、[3..5]=up、[6..8]=forward}。 */
    private static final float[] RING_BASIS = new float[9];
    private static final float[] RING_RIGHT = new float[3];
    private static final float[] RING_UP = new float[3];
    private static final float[] RING_NORMAL = new float[3];
    private static final double[] RING_SCRATCH4 = new double[4];

    private static boolean loggedFirstTrail;

    private ShanhaiDropletTrail() {}

    /** 当前世界里所有水滴（只读；每 tick 重建一次）。 */
    public static List<ShanhaiDropletEntity> allDroplets() {
        return ALL;
    }

    /** 轨迹表里记了几条（诊断用）。 */
    public static int trackedCount() {
        return BY_ID.size();
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    //  tick：采样 + 淘汰 + 环发射
    // ══════════════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTick++;
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level != lastLevel) {
            // 换维度 / 重进世界：整表清空（否则会出现一条横穿世界的长线）
            BY_ID.clear();
            ALL.clear();
            lastLevel = level;
            loggedFirstTrail = false;
        }
        if (level == null) {
            return;
        }
        ALL.clear();
        for (final Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ShanhaiDropletEntity droplet && !droplet.isRemoved()) {
                ALL.add(droplet);
                sample(droplet);
            }
        }
        prune();
    }

    /** 给一个水滴采一次样（世界坐标；写进去之后不再修改）。 */
    private static void sample(ShanhaiDropletEntity droplet) {
        Track track = BY_ID.get(droplet.getId());
        if (track == null) {
            track = new Track();
            BY_ID.put(droplet.getId(), track);
        }
        track.lastSeenTick = clientTick;

        final double px = droplet.getX();
        final double py = droplet.getY();
        final double pz = droplet.getZ();
        if (track.points.isTeleport(px, py, pz, TELEPORT_JUMP)) {
            // 瞬移/传送：这条轨迹已经不能连成线了
            track.points.clear();
            track.ringSize = 0;
            ShanhaiMod.LOGGER.info("{} teleport_reset id={}（一 tick 位移超过 {} 格 ⇒ 清空）",
                    PREFIX, Integer.valueOf(droplet.getId()), Double.valueOf(TELEPORT_JUMP));
        }
        track.points.pushPoint(px, py, pz, clientTick);
        track.points.dropExpired(clientTick, LIFE_TICKS);

        // 推进环：1 秒 1 个，记在"发射那一刻的世界位姿"上
        if (clientTick % RING_PERIOD_TICKS == 0) {
            DropletMesh.basis(droplet.getYRot(), droplet.getXRot(), RING_BASIS);
            final double half = DropletProfile.LEN * 0.5D;
            track.pushRing(px - RING_BASIS[6] * half,
                    py - RING_BASIS[7] * half,
                    pz - RING_BASIS[8] * half,
                    droplet.getYRot(), droplet.getXRot(), clientTick);
        }
        int dropRing = 0;
        while (dropRing < track.ringSize) {
            final int idx = track.ringIndexFromOldest(dropRing);
            if (clientTick - track.ringTick[idx] <= RING_LIFE_TICKS) {
                break;
            }
            dropRing++;
        }
        if (dropRing > 0) {
            track.dropOldestRing(dropRing);
        }
    }

    /** 这一 tick 没被扫到的实体（被收回/杀死/换维度）⇒ 整条历史丢掉。 */
    private static void prune() {
        if (BY_ID.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, Track>> it = BY_ID.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().lastSeenTick != clientTick) {
                it.remove();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    //  渲染：把世界空间路点/环变成顶点流
    // ══════════════════════════════════════════════════════════════════════════════════

    /**
     * 建这个水滴这一帧的尾迹顶点流（写进 {@link DropletMesh#TRAIL_SCRATCH}）。
     *
     * @return 写入的顶点数（0 = 这一帧没有轨迹，比如刚生成/已经淡完）
     */
    public static int trailStream(ShanhaiDropletEntity droplet,
                                  double camX, double camY, double camZ) {
        final Track track = BY_ID.get(droplet.getId());
        if (track == null) {
            return 0;
        }
        final int kept = track.points.collect(clientTick, PX, PY, PZ, COST);
        if (kept < 2) {
            return 0;
        }
        final int verts = DropletMesh.buildTrail(DropletMesh.TRAIL_SCRATCH, PX, PY, PZ, COST, kept,
                camX, camY, camZ);
        if (verts > 0 && !loggedFirstTrail) {
            loggedFirstTrail = true;
            ShanhaiMod.LOGGER.info("{} first_trail id={} points={} verts={} radius_core={} lens={}",
                    PREFIX, Integer.valueOf(droplet.getId()), Integer.valueOf(kept),
                    Integer.valueOf(verts),
                    Double.valueOf(DropletProfile.BEAM_RADIUS_CORE),
                    Double.valueOf(DropletProfile.BEAM_LEN));
        }
        return verts;
    }

    /**
     * 建这个水滴这一帧全部推进环的顶点流（写进 {@link DropletMesh#RING_SCRATCH}）。
     *
     * <p>每个环<b>只用发射那一刻存下来的圆心与朝向</b>；水滴现在朝哪与它无关。
     *
     * @param partialTick 帧内插值（环的年龄才平滑）
     * @return 写入的顶点数
     */
    public static int ringStream(ShanhaiDropletEntity droplet, float partialTick) {
        final Track track = BY_ID.get(droplet.getId());
        if (track == null || track.ringSize == 0) {
            return 0;
        }
        final double now = clientTick + partialTick;
        int n = 0;
        for (int r = 0; r < track.ringSize; r++) {
            final int idx = track.ringIndexFromOldest(r);
            final double ageSec = (now - track.ringTick[idx]) / 20.0D;
            if (ageSec < 0.0D || ageSec > DropletProfile.RING_LIFE) {
                continue;
            }
            final double p = ageSec / DropletProfile.RING_LIFE;
            DropletProfile.ringInto(p, RING_SCRATCH4);
            final double radius = RING_SCRATCH4[0];
            final double dist = RING_SCRATCH4[1];
            final double alpha0 = RING_SCRATCH4[2];
            final double thickness = RING_SCRATCH4[3];
            if (alpha0 <= 0.002D) {
                continue;
            }
            DropletMesh.basis(track.ringYaw[idx], track.ringPitch[idx], RING_BASIS);
            RING_RIGHT[0] = RING_BASIS[0];
            RING_RIGHT[1] = RING_BASIS[1];
            RING_RIGHT[2] = RING_BASIS[2];
            RING_UP[0] = RING_BASIS[3];
            RING_UP[1] = RING_BASIS[4];
            RING_UP[2] = RING_BASIS[5];
            RING_NORMAL[0] = RING_BASIS[6];
            RING_NORMAL[1] = RING_BASIS[7];
            RING_NORMAL[2] = RING_BASIS[8];
            final double cx = track.ringX[idx] - RING_NORMAL[0] * dist;
            final double cy = track.ringY[idx] - RING_NORMAL[1] * dist;
            final double cz = track.ringZ[idx] - RING_NORMAL[2] * dist;
            // 颜色：出生青白 → 老去转蓝（v3：(150,246,255) → (45,205,255)）
            final double mix = Math.min(1.0D, p * 1.25D);
            final float cr = (float) ((150.0D + (45.0D - 150.0D) * mix) / 255.0D);
            final float cg = (float) ((246.0D + (205.0D - 246.0D) * mix) / 255.0D);
            final float cb = 1.0F;
            // 两遍：外圈柔光（×4 宽、×0.15 亮）+ 内圈实线
            for (int pass = 0; pass < 2; pass++) {
                final double th = thickness * (pass == 0 ? 4.0D : 1.0D);
                final double a = Math.min(1.0D, alpha0 * (pass == 0 ? 0.15D : 1.0D));
                n = DropletMesh.ringInto(DropletMesh.RING_SCRATCH, n, cx, cy, cz,
                        RING_RIGHT, RING_UP, radius, th, cr, cg, cb, (float) a,
                        0.004D * (pass == 0 ? 1.0D : 2.0D), RING_NORMAL);
            }
        }
        return n;
    }
}
