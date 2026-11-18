package com.dishanhai.gt_shanhai.common.time.replay;

/**
 * 山海重构 · <b>「反向破坏粒子」的每拍预算与均匀抽样</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11-14 12:53 录像之后，原话）</h2>
 * <blockquote>「我希望我破坏方块的粒子效果也能在方块恢复时倒流」</blockquote>
 * ⇒ 方块回写时在该格发<b>这块方块自己的破坏粒子</b>（{@code BlockParticleOption(ParticleTypes.BLOCK, state)}），
 * 看起来像"碎块往回聚"。
 *
 * <h2>2. 为什么必须有预算（而不是"每格都发"）</h2>
 * 一次倒流的一拍里 {@code blocks=125} 是常见值（真机 12:51:59 那行就是 {@code blocks=125}），
 * 但一次大回放（挖掉一整面墙/爆炸）能到几千格。逐格发粒子 =
 * 每格一个 {@code ClientboundLevelParticlesPacket} × 每个玩家 ⇒ 网络瞬间打满、客户端卡成幻灯片。
 * ⇒ 每拍上限 {@value #MAX_PER_STEP} 格；超了就<b>均匀抽样</b>（不是砍掉尾部的那些，
 * 否则画面会"前面有粒子、后面没有"）。
 *
 * <h2>3. 三条判据（自检 X 组）</h2>
 * <ol>
 *   <li>写 10 格（未超限）⇒ {@code particles=10/10 sampled=false}；</li>
 *   <li>构造 1000 格 ⇒ {@code particles=200/1000 sampled=true}，且下标均匀铺满整个区间；</li>
 *   <li>【负对照】关掉粒子（预算 0）⇒ {@code particles=0/10}，判据红（读数里必须出现 0）。</li>
 * </ol>
 */
public final class ShanhaiReplayParticleBudget {

    /** 每拍最多为多少格发粒子（超出部分均匀抽样，不是截断）。 */
    public static final int MAX_PER_STEP = 200;

    /**
     * 一次"本拍该给哪些格发粒子"的计划。
     *
     * @param wanted  本拍写回的格子数（应发）
     * @param send    真正要发的格数（发出）
     * @param sampled 是否因为超限抽样过
     */
    public record Plan(int wanted, int send, boolean sampled) {

        public static Plan of(int wanted, int send, boolean sampled) {
            return new Plan(Math.max(0, wanted), Math.max(0, send), sampled);
        }

        public Plan {
            if (wanted < 0) {
                wanted = 0;
            }
            if (send < 0) {
                send = 0;
            }
            if (send > wanted) {
                send = wanted;
            }
            if (send == wanted) {
                sampled = false;
            }
        }

        /** 这一拍被丢掉的格数（只用于判读；抽样是"少发几格"，不是"漏发方块同步"）。 */
        public int skipped() {
            return this.wanted - this.send;
        }

        /** 本拍要发粒子的下标（{@code wanted > send} 时均匀铺开）。 */
        public int[] indices() {
            if (this.send <= 0) {
                return new int[0];
            }
            if (!this.sampled || this.send >= this.wanted) {
                final int[] all = new int[this.send];
                for (int i = 0; i < this.send; i++) {
                    all[i] = i;
                }
                return all;
            }
            final int[] out = new int[this.send];
            for (int k = 0; k < this.send; k++) {
                // 均匀抽样：第 k 个取 floor(k * wanted / send)，覆盖 [0, wanted) 的整个区间
                out[k] = (int) ((long) k * (long) this.wanted / (long) this.send);
            }
            return out;
        }

        /** 判据行（{@code step=} 行里直接贴）。 */
        public String toText() {
            return "particles=" + this.send + "/" + this.wanted + " sampled=" + this.sampled;
        }
    }

    /**
     * 按预算算一份计划。
     *
     * @param wanted 本拍写回的格子数
     * @param budget 本拍最多发几格（{@code <= 0} = 关掉粒子）
     */
    public static Plan plan(int wanted, int budget) {
        final int w = Math.max(0, wanted);
        final int b = Math.max(0, budget);
        final int send = Math.min(w, b);
        return Plan.of(w, send, send < w);
    }

    /** 生产路径：预算 = {@link #MAX_PER_STEP}。 */
    public static Plan plan(int wanted) {
        return plan(wanted, MAX_PER_STEP);
    }

    private ShanhaiReplayParticleBudget() {
    }
}
