package com.shanhai.add.hotfix;

import com.shanhai.ShanhaiMod;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 主动热改的<b>装配胶水</b>：本模块里唯一认识 MC／Forge／log4j 的那个人。
 *
 * <h2>为什么要把"引擎"和"胶水"拆开</h2>
 * {@link HotfixAutoCounter} 与 {@link HotfixTriageTap} 都是<b>纯 JDK（＋ASM）</b>的，
 * 目的是让它们能被<b>脱离游戏</b>的离线自检直接跑（{@code temp\hotfix\offline-autocounter\}）。
 * 一旦引擎里出现 {@code ShanhaiMod.LOGGER} 或 {@code FMLPaths}，那条离线路就断了
 * （{@code NoClassDefFoundError}，而且是在最不该崩的地方崩）。
 * ⇒ 需要 MC 的两件事（日志出口、{@code mods\} 路径）只在<b>这个文件</b>里出现，用 {@link HotfixAutoCounter#bind} 注入进去。
 *
 * <h2>它做三件事</h2>
 * <ol>
 *   <li>{@link HotfixAutoCounter#bind}：把日志出口与 {@code mods\} 根交给引擎；</li>
 *   <li>{@link HotfixTriageTap#install}：订阅本体分诊日志（幂等，只成功装一次）；</li>
 *   <li>打一行可判读的 {@code tap } 摘要（含自证探针结果）。</li>
 * </ol>
 *
 * <h2>失败姿势</h2>
 * 全部 {@code try/catch(Throwable)}：装不上 tap 只意味着"主动热改暂时收不到触发"，
 * 与游戏、与本体的分诊<b>都没有关系</b>。
 */
public final class HotfixAutoCounterGlue {

    /** 日志出口：一律经本体的 logger（与热更新其它日志同一个前缀，便于一起 grep）。 */
    public static final HotfixAutoCounter.Log LOG = new HotfixAutoCounter.Log() {
        @Override
        public void line(String text) {
            try {
                ShanhaiMod.LOGGER.info("{} {}", HotfixAutoCounter.PREFIX, text);
            } catch (Throwable ignored) {
                // 日志都打不出去时什么也别做
            }
        }

        /**
         * 失败/跳过路径 → <b>WARN</b>。
         *
         * <p>为什么单独一条：读回取证（{@code patch_readback}）的失败路径是"这一次没读到"，
         * 不是"热改出事了"，更不是玩家行为 —— 用 INFO 会让人以为它在正常干活，
         * 用 ERROR 会把一次可自愈的取不到放大成告警。WARN 是这条链唯一合适的水位。
         */
        @Override
        public void warn(String text) {
            try {
                ShanhaiMod.LOGGER.warn("{} {}", HotfixAutoCounter.PREFIX, text);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    };

    /** 分诊订阅的落地动作：原样交给引擎（入队，立刻返回）。 */
    private static final HotfixTriageTap.Sink SINK = new HotfixTriageTap.Sink() {
        @Override
        public void onVerdict(HotfixTriageVerdict verdict, HotfixTriageTap.Strike strike) {
            HotfixAutoCounter.onVerdict(verdict, strike);
        }
    };

    /** 上一次打过的 tap 状态（只在状态变化时再打一行，免得每个世界都刷屏）。 */
    private static volatile String lastTapState = "";

    /**
     * 🆕 2026-11-08：<b>热改播报的出口</b>（把"改了谁／为什么"播到玩家聊天栏）。
     *
     * <p>它<b>不新建任何通道</b>：内部调用本体 {@code ShanhaiGuardEvents#chatRawBodies} ——
     * 就是守卫那条现成的聊天栏（前缀 {@code [创始现实修改模块] } ＋ 同一个闸门 ＋ 只发持有者本人）。
     * 装配在这里（而不是引擎里）是因为引擎是纯 JDK 的，见 {@link HotfixAutoCounter.PatchChat}。
     */
    private static final HotfixAutoCounter.PatchChat CHAT = new HotfixPatchChat();

    private HotfixAutoCounterGlue() {
        // 纯静态
    }

    /** 只接线（不进日志、不装 tap）—— 停服／退出世界时 undoall 之前调，保证日志出口是通的。 */
    public static void bind() {
        HotfixAutoCounter.bind(LOG, modsDir());
        // 🆕 2026-11-08：热改播报的出口（没有玩家上下文时引擎什么都不会发；这一句只是把出口装上）
        HotfixAutoCounter.bindChatSink(CHAT);
    }

    /**
     * 接线 ＋ 订阅分诊（进世界时调；可重复调）。
     *
     * @param trigger 谁触发的（只进日志）
     * @return {@code true} = 分诊订阅已经装着（且自证探针走通）
     */
    public static boolean ensure(String trigger) {
        bind();
        final boolean installed;
        try {
            installed = HotfixTriageTap.install(SINK);
        } catch (Throwable t) {
            LOG.line("tap trigger=" + HotfixAutoCounter.token(trigger)
                    + " installed=false reason=" + HotfixAutoCounter.token("exception_" + t.getClass().getSimpleName()));
            return false;
        }
        final String state = "installed=" + installed + " " + HotfixTriageTap.statusLine();
        if (!state.equals(lastTapState)) {
            lastTapState = state;
            LOG.line("tap trigger=" + HotfixAutoCounter.token(trigger) + " " + state
                    + " auto=" + HotfixAutoCounter.auto());
        }
        return installed;
    }

    /**
     * {@code mods\} 的绝对路径。
     *
     * @return 路径；拿不到 ⇒ 空串（此时白名单会退化成"路径里必须有一级 mods"这条<b>更严</b>的判据）
     */
    public static String modsDir() {
        try {
            return FMLPaths.MODSDIR.get().toAbsolutePath().toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
