package com.shanhai.add.hotfix;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.function.Consumer;

/**
 * 自检：在<b>我们自己写的、无害的</b>测试方法上跑一遍"改方法体 → 验证返回值变了 → 还原 → 再验证"的闭环。
 *
 * <h2>它证明什么、不证明什么</h2>
 * <b>证明</b>：{@link Instrumentation#retransformClasses} 这条链在本进程里<b>真的</b>能生效 ——
 * transformer 被回调、改后的字节被 JVM 接受、调用方拿到的<b>返回值真的变了</b>、摘掉 transformer
 * 再 retransform 能<b>真的</b>回到原样。
 * <b>不证明</b>：能安全地改写 MC/Forge 类（那些类被 Mixin 处理过、方法体里有注入、还有结构性限制）。
 * 这一条<b>没有</b>验过，别拿本自检的 PASS 去推断它 —— 详见 {@code temp\hotfix\报告.md} 的"没验到的"。
 *
 * <h2>为什么要打这么多行</h2>
 * 失败时最常见的追问是"到底哪一步不行"。所以每一步单独一行、都带 {@code step=} 与
 * {@code pass=true|false}；最后一行固定是 {@code selftest PASS|FAIL}，方便机器判读。
 *
 * <h2>日志怎么出去</h2>
 * 本类不碰日志框架（那样它就没法被<b>脱离游戏</b>的离线自检直接跑），所有行经 {@code sink}
 * 交给调用方（游戏里是 log4j，离线自检里是 {@code System.out}）。
 *
 * <p>🔴 这里的每一行都<b>不带</b> {@code [SHANHAI-HOTFIX]} 前缀 —— 前缀由调用方加。
 * 这样离线自检也能用同一个 {@code sink} 打出一模一样的读数，报告里的证据与游戏里逐字可比。
 */
public final class HotfixSelfTest {

    /** 靶子类名（内部名，transformer 里按它筛）。 */
    private static final String TARGET_INTERNAL = HotfixProbeTarget.class.getName().replace('.', '/');

    /** 靶子方法返回值的原始值（也用来判"基线是否干净"）。 */
    private static final String ORIGINAL = HotfixBytePatch.ORIGINAL_VALUE;

    private HotfixSelfTest() {
    }

    /**
     * 跑一遍闭环。
     *
     * @param inst 已持有的 {@link Instrumentation}（{@code null} ⇒ 直接判定失败并解释原因）
     * @param sink 每行日志的出口（{@code null} 时只算结果不打日志，便于单测）
     * @return {@code true} = 所有步骤都通过
     */
    public static boolean run(Instrumentation inst, Consumer<String> sink) {
        final long t0 = System.currentTimeMillis();
        final Counter counter = new Counter();
        boolean pass = true;

        // ---- ⓪ 前置：没有 Instrumentation 就没什么可测的
        if (inst == null) {
            pass &= emit(sink, counter, "selftest step=instrumentation", false,
                    "detail=instrumentation_null hint=先让预热成功(/shanhai_add hotfix status 看 state)");
            return finish(sink, counter, t0, pass);
        }
        boolean supported;
        boolean modifiable;
        try {
            supported = inst.isRetransformClassesSupported();
            modifiable = inst.isModifiableClass(HotfixProbeTarget.class);
        } catch (Throwable t) {
            pass &= emit(sink, counter, "selftest step=instrumentation", false,
                    "detail=probe_failed exception=" + t);
            return finish(sink, counter, t0, pass);
        }
        pass &= emit(sink, counter, "selftest step=instrumentation", supported && modifiable,
                "retransform_supported=" + supported + " target_modifiable=" + modifiable
                        + " target=" + TARGET_INTERNAL);
        if (!supported || !modifiable) {
            // 后面每一步的前提，继续跑只会输出一堆连带失败，反而干扰判读
            return finish(sink, counter, t0, pass);
        }

        // ---- ① 基线：靶子必须还在原样（上一轮自检若崩在中间，这里会显形）
        String before = stampValue();
        pass &= emit(sink, counter, "selftest step=baseline", ORIGINAL.equals(before),
                "value=" + before + " expected=" + ORIGINAL);
        if (!ORIGINAL.equals(before)) {
            // 基线不干净：先做一次"不带 transformer"的 retransform 把它拉回原始字节，再判一次
            boolean recovered = false;
            String detail;
            try {
                inst.retransformClasses(HotfixProbeTarget.class);
                recovered = ORIGINAL.equals(stampValue());
                detail = "value=" + stampValue() + " expected=" + ORIGINAL;
            } catch (Throwable t) {
                detail = "exception=" + t;
            }
            pass &= emit(sink, counter, "selftest step=baseline_recovery", recovered, detail);
            if (!recovered) {
                return finish(sink, counter, t0, pass);
            }
            before = stampValue();
        }

        // ---- ② 挂 transformer（canRetransform=true 是硬条件：少了它 retransform 不会回调我们）
        final StampTransformer transformer = new StampTransformer(HotfixBytePatch.SELFTEST_TOKEN);
        boolean added = false;
        try {
            inst.addTransformer(transformer, true);
            added = true;
        } catch (Throwable t) {
            // 交给下面的 add_transformer 行统一报告
        }
        pass &= emit(sink, counter, "selftest step=add_transformer", added, "can_retransform=true");
        if (!added) {
            return finish(sink, counter, t0, pass);
        }

        // ---- ③ retransform（JVM 在这里回调 transformer，把新字节装进去）
        boolean retransformed = false;
        try {
            inst.retransformClasses(HotfixProbeTarget.class);
            retransformed = true;
        } catch (Throwable t) {
            pass &= emit(sink, counter, "selftest step=retransform", false, "exception=" + t);
        }
        if (retransformed) {
            pass &= emit(sink, counter, "selftest step=retransform", transformer.patches > 0,
                    "transformer_calls=" + transformer.patches + " patched_entries=" + transformer.entries
                            + " patch_reason=" + transformer.reason);
        }

        // ---- ④ 验证"返回值真的变了"（这一步才是自检的核心：不是"没抛异常"就算过）
        final String want = HotfixBytePatch.expectedValue(HotfixBytePatch.SELFTEST_TOKEN);
        final String after = retransformed ? stampValue() : before;
        pass &= emit(sink, counter, "selftest step=verify_changed", want.equals(after) && !after.equals(before),
                "value=" + after + " expected=" + want + " differs_from_baseline=" + !after.equals(before));

        // ---- ⑤ 摘掉 transformer（无论前面成败；留着它会让后续每次 retransform 都被改）
        boolean removed = false;
        try {
            inst.removeTransformer(transformer);
            removed = true;
        } catch (Throwable t) {
            pass &= emit(sink, counter, "selftest step=remove_transformer", false, "exception=" + t);
        }
        if (removed) {
            pass &= emit(sink, counter, "selftest step=remove_transformer", true, "removed=true");
        }

        // ---- ⑥ 还原：不带 transformer 的 retransform = 回到【原始】class 字节（JVMS 语义）
        boolean restored = false;
        try {
            inst.retransformClasses(HotfixProbeTarget.class);
            restored = true;
        } catch (Throwable t) {
            pass &= emit(sink, counter, "selftest step=retransform_restore", false, "exception=" + t);
        }
        if (restored) {
            pass &= emit(sink, counter, "selftest step=retransform_restore", true, "transformers_active=0");
        }

        // ---- ⑦ 验证还原（"变了"和"回得去"必须都成立，否则类就留在被改状态）
        final String back = stampValue();
        pass &= emit(sink, counter, "selftest step=verify_restored", restored && ORIGINAL.equals(back)
                && !want.equals(back), "value=" + back + " expected=" + ORIGINAL);

        return finish(sink, counter, t0, pass);
    }

    /**
     * 一个"只改我们靶子、别的一律 {@code return null}"的 transformer。
     *
     * <p>两处筛选缺一不可：
     * <ul>
     *   <li>{@code className} 必须等于靶子 ⇒ 别的类一个字节都不碰（硬约束）；</li>
     *   <li>{@code classBeingRedefined} 必须是靶子那个 {@code Class} 对象 ⇒ 防同名类（理论上不存在，
     *       但把判据写死在"就是这一个类"上，比依赖名字唯一更稳）。</li>
     * </ul>
     * 改失败时<b>返回 {@code null}</b>（= 不改），并在字段里留下原因供日志判读 ——
     * 绝不返回"半信半疑"的字节去让 {@code retransformClasses} 抛异常。
     */
    static final class StampTransformer implements ClassFileTransformer {

        private final String token;

        /** 被回调并真的返回了新字节的次数。 */
        volatile int patches;

        /** 最后一次命中并改写的常量池条目数。 */
        volatile int entries;

        /** 最后一次判定原因（{@code ok} / {@code stamp_not_found} / …）。 */
        volatile String reason = "not_called";

        StampTransformer(String token) {
            this.token = token;
        }

        @Override
        @SuppressWarnings("deprecation")
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!TARGET_INTERNAL.equals(className)) {
                return null;
            }
            if (classBeingRedefined != null && classBeingRedefined != HotfixProbeTarget.class) {
                return null;
            }
            final HotfixBytePatch.Result result = HotfixBytePatch.patchStamp(classfileBuffer, token);
            reason = result.reason;
            entries = result.entries;
            if (!result.ok()) {
                return null;
            }
            patches++;
            return result.bytes;
        }
    }

    /** 步数 / 失败数（只在一次 {@link #run} 内用）。 */
    private static final class Counter {
        int steps;
        int failed;
    }

    /** 打一行 {@code <step> pass=... <detail>}，返回本步是否通过（调用方用 {@code &=} 累计）。 */
    private static boolean emit(Consumer<String> sink, Counter counter, String step, boolean pass, String detail) {
        counter.steps++;
        if (!pass) {
            counter.failed++;
        }
        if (sink != null) {
            sink.accept(step + " pass=" + pass + " " + detail);
        }
        return pass;
    }

    /** 收尾：固定最后一行 {@code selftest PASS|FAIL}，并带上可 grep 的合计字段。 */
    private static boolean finish(Consumer<String> sink, Counter counter, long t0, boolean pass) {
        final boolean ok = pass && counter.failed == 0 && counter.steps > 0;
        if (sink != null) {
            sink.accept("selftest " + (ok ? "PASS" : "FAIL")
                    + " steps=" + counter.steps + " failed=" + counter.failed
                    + " elapsed_ms=" + (System.currentTimeMillis() - t0));
        }
        return ok;
    }

    /** 读靶子当前返回值（失败不抛：自检自己不许把游戏拖崩）。 */
    private static String stampValue() {
        try {
            final Object v = HotfixProbeTarget.class.getMethod("stamp").invoke(null);
            return v == null ? "<null>" : String.valueOf(v);
        } catch (Throwable t) {
            return "<error:" + t.getClass().getSimpleName() + ">";
        }
    }
}
