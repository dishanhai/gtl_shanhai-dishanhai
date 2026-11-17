package com.shanhai.add.guard.selfcheck;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 🔴 <b>「我们注入到原版类上的钩子还在不在」的纯 JDK 判据</b>（{@link ShanhaiHookSelfCheck} 的判据层，
 * 也被离线自证 {@code temp/hookscheck/selfcheck/} 直接编译运行 —— 同一份代码，不是复制品）。
 *
 * <h2>为什么要有这一层</h2>
 * <pre>
 *   真正的风险不是"原版的 setHealth 被别人改了"，而是【我们的注入被对手剥掉/改名】——
 *   那时守卫看起来一切正常、日志也照常，但 {@code setHealth} 那条腿已经没人守了。
 *   ⇒ 必须能<b>在服务端线程上、每 30 秒、只读地</b>问一句"我们的方法还在不在"。
 * </pre>
 *
 * <h2>🔴 判据：片段计数，不是全名</h2>
 * <pre>
 *   Mixin 会把 handler 方法改名成 {@code handler$&lt;token&gt;$&lt;原名&gt;} 再插进目标类
 *   （本仓库实证：{@code LevelChunk.handler$zgh000$shanhai$watchSetBlockState}、
 *     {@code handler$cni000$shooting_star_demo$quiet}；
 *     {@code ShanhaiAeGate#armedLine()} 用的就是 {@code contains("shanhai$")}）。
 *   ⇒ 用【全名】比对（{@code handler$abc$shanhai$guardSetHealth}）必假红；
 *     用【方法数量】又会被同类的别的 shanhai$ handler 掩盖
 *     （{@code LivingEntity} 里还有 gravity 的 shanhai$ handler、{@code Entity} 里还有动量/位置的）。
 *   ⇒ 本层按【每个期望 handler 的片段】各数一次：{@code name.contains("shanhai$guardSetHealth")} ≥ 1 才算在。
 *     这样既不吃 {@code handler$} 前缀，也不会被无关 handler 掩盖。
 * </pre>
 *
 * <h2>真值来源（不许凭记忆写）</h2>
 * <pre>
 *   grep -n "private .* shanhai\$" shanhai-rewrite-add/src/main/java/com/shanhai/add/mixin/*.java
 *     ShanhaiSetHealthGuardMixin      : private float shanhai$guardSetHealth(float)               @ModifyVariable → LivingEntity
 *     ShanhaiLivingEntityDieGuardMixin: private void  shanhai$refuseDie(DamageSource, CallbackInfo)  @Inject        → LivingEntity
 *     ShanhaiEntitySetRemovedGuardMixin: private void shanhai$refuseSetRemoved(RemovalReason, CallbackInfo) @Inject → Entity
 *     ShanhaiServerPlayerDieGuardMixin : private void shanhai$refuseServerPlayerDie(...)           @Inject        → ServerPlayer
 *   ⇒ 见 {@link #TARGETS}；类名用官方名（reobf 只重映射成员名，类名不变 —— 构建产物 javap 实证）。
 * </pre>
 *
 * <h2>只读纪律</h2>
 * 本层只做 {@code getDeclaredMethods()} ＋ 字符串比对：<b>不初始化目标类、不调任何游戏方法、不碰真玩家</b>。
 *
 * <h2>离线自证（不需要游戏）</h2>
 * <pre>
 *   javac -encoding UTF-8 -d out &lt;本文件&gt; temp/hookscheck/selfcheck/HookSelfCheckSelfProof.java
 *   java -cp out com.shanhai.add.guard.selfcheck.HookSelfCheckSelfProof
 *   ⇒ 打印 pass=… fail=… exit=…（正/负对照见那个文件）
 * </pre>
 */
public final class ShanhaiHookSelfCheckCore {

    /** 我们注入到原版类上的 handler 的公共片段（Mixin 会加 {@code handler$<token>$} 前缀 ⇒ 只能 contains）。 */
    public static final String MARKER = "shanhai$";

    /**
     * 一个被检查的目标类。
     *
     * <p>{@code className} 用<b>官方类名</b>（{@code Class.forName} 用）；{@code fragments} 是注入方法名的<b>片段</b>
     * —— 不是全名（理由见类注释）。
     */
    public static final class Target {

        /** 反射用的官方类名。 */
        public final String className;

        /** 每个注入 handler 的方法名片段（{@code shanhai$…}）。 */
        public final String[] fragments;

        public Target(String className, String... fragments) {
            this.className = className;
            this.fragments = fragments == null ? new String[0] : fragments.clone();
        }
    }

    /**
     * 🔴 <b>真值名单</b>（由 grep 本仓库 mixin 的 {@code @Inject} / {@code @ModifyVariable} 方法名确认；
     * 原文与命令见 {@code temp/hookscheck/报告.md}）。
     *
     * <p>⚠️ 名单里<b>只有守卫这四条</b>：{@code setHealth} 入参兜底、{@code LivingEntity#die}、{@code ServerPlayer#die}、
     * {@code Entity#setRemoved}。别的 shanhai$ handler（重力/动量/位置/时钟）不在这里 —— 它们不是"保护原版不被改"那条线。
     */
    public static final Target[] TARGETS = {
            new Target("net.minecraft.world.entity.LivingEntity",
                    "shanhai$guardSetHealth", "shanhai$refuseDie"),
            new Target("net.minecraft.world.entity.Entity",
                    "shanhai$refuseSetRemoved"),
            new Target("net.minecraft.server.level.ServerPlayer",
                    "shanhai$refuseServerPlayerDie"),
    };

    private ShanhaiHookSelfCheckCore() {}

    /**
     * 检查一个目标类里我们的 handler 还在不在。
     *
     * @param target 期望（类名 ＋ handler 片段）
     * @param type   已经加载好的目标类；{@code null} = 没解析到（类不在 / 反射被挡）
     * @return 这一次检查的读数（{@code found} 是<b>实际跑在类里的名字</b>，进日志对账用）
     */
    public static ClassCheck inspect(Target target, Class<?> type) {
        final List<String> found = new ArrayList<>();
        String failure = "";
        if (type == null) {
            failure = "class_not_found";
        } else {
            try {
                final Method[] methods = type.getDeclaredMethods();
                for (Method m : methods) {
                    if (m == null) {
                        continue;
                    }
                    final String name = m.getName();
                    if (name != null && name.contains(MARKER)) {
                        found.add(name);
                    }
                }
            } catch (Throwable t) {
                // 反射被挡 / 某个形参类型解析不到：宁可报红，也不能假装"还在"
                failure = "reflect_failed:" + t.getClass().getSimpleName();
            }
        }
        Collections.sort(found);

        final List<String> matched = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        final String shortName = simpleName(target.className);
        for (String fragment : target.fragments) {
            boolean hit = false;
            for (String name : found) {
                if (name.contains(fragment)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                matched.add(fragment);
            } else {
                missing.add(shortName + "#" + fragment);
            }
        }
        return new ClassCheck(target.className, shortName, type != null && failure.isEmpty(),
                target.fragments.length, found, matched, missing, failure);
    }

    /** 一个类这一次的检查读数。 */
    public static final class ClassCheck {

        /** 官方类名。 */
        public final String className;
        /** 短类名（日志里用）。 */
        public final String simpleName;
        /** 类真的解析到了、反射也读到了。 */
        public final boolean loaded;
        /** 期望的 handler 个数。 */
        public final int expected;
        /** 实际找到的、名字含 {@code shanhai$} 的方法（运行期真名，已排序）。 */
        public final List<String> found;
        /** 命中的期望片段。 */
        public final List<String> matched;
        /** 没命中的期望片段（已限定成 {@code 短类名#片段}）。 */
        public final List<String> missing;
        /** 空串 = 没出错；否则 {@code class_not_found} / {@code reflect_failed:<异常类名>}。 */
        public final String failure;

        private ClassCheck(String className, String simpleName, boolean loaded, int expected,
                           List<String> found, List<String> matched, List<String> missing, String failure) {
            this.className = className;
            this.simpleName = simpleName;
            this.loaded = loaded;
            this.expected = expected;
            this.found = Collections.unmodifiableList(new ArrayList<>(found));
            this.matched = Collections.unmodifiableList(new ArrayList<>(matched));
            this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
            this.failure = failure == null ? "" : failure;
        }

        /** 这个类这一轮算不算"钩子都在"。 */
        public boolean ok() {
            return loaded && missing.isEmpty();
        }
    }

    /** 行为探针的结论（三个值就是日志/命令里那个 {@code probe=} 字段）。 */
    public enum Probe {

        /** 探针没跑（存在性没过 ⇒ 按规矩不跑）或两条都通过。 */
        OK("ok"),
        /** 血路探针失败。 */
        HEALTH("health"),
        /** 移除路探针失败。 */
        REMOVE("remove");

        /** 进日志的那段字。 */
        public final String label;

        Probe(String label) {
            this.label = label;
        }
    }

    /**
     * 一轮自检的结论（存在性 ＋ 探针）。
     *
     * <p>三个格式化方法（{@link #commandLine} / {@link #failureLine} / {@link #restoredLine}）在离线自证里
     * 逐字断言过 —— 字段顺序与规格一致，别随手改。
     */
    public static final class Verdict {

        /** 每个类的读数（顺序与 {@link #TARGETS} 一致）。 */
        public final List<ClassCheck> classes;
        /** 命中的期望 handler 个数（0..4）。 */
        public final int found;
        /** 期望的 handler 总个数（当前 = 4）。 */
        public final int expected;
        /** 没命中的期望 handler（{@code 短类名#片段}）。 */
        public final List<String> missing;
        /** 探针结论。 */
        public final Probe probe;
        /** 真正"钩子齐全 ＋ 判据都还在"。 */
        public final boolean healthy;
        /** 血路探针失败时那几条子读数的 ASCII 摘要（空串 = 没有 / 没跑）。 */
        public final String healthNote;
        /** 移除路探针失败时那几条子读数的 ASCII 摘要（空串 = 没有 / 没跑）。 */
        public final String removeNote;

        private Verdict(List<ClassCheck> classes, int found, int expected, List<String> missing,
                        Probe probe, boolean healthy, String healthNote, String removeNote) {
            this.classes = Collections.unmodifiableList(new ArrayList<>(classes));
            this.found = found;
            this.expected = expected;
            this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
            this.probe = probe;
            this.healthy = healthy;
            this.healthNote = healthNote == null ? "" : healthNote;
            this.removeNote = removeNote == null ? "" : removeNote;
        }

        /** {@code ok|missing}（命令里的 {@code status=}）。 */
        public String statusText() {
            return healthy ? "ok" : "missing";
        }

        /** {@code ok|health|remove}（命令/日志里的 {@code probe=}）。 */
        public String probeText() {
            return probe.label;
        }

        /** 缺失名单；没有就是 {@code (none)}。 */
        public String missingText() {
            return missing.isEmpty() ? "(none)" : String.join(",", missing);
        }

        /**
         * 🔴 <b>实际找到的名字</b>（对账用）：{@code 短类名(该类 shanhai$ 方法数)=[运行期真名|…]}，类之间用逗号。
         *
         * <p>这是"判据用片段、日志给真名"的落点：{@code handler$<token>$} 前缀一眼可见。
         */
        public String foundDetail() {
            final StringBuilder sb = new StringBuilder();
            for (ClassCheck c : classes) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(c.simpleName).append('(').append(c.found.size()).append(")=");
                if (!c.failure.isEmpty()) {
                    sb.append('{').append(c.failure).append('}');
                } else {
                    sb.append('[').append(String.join("|", c.found)).append(']');
                }
            }
            return sb.toString();
        }

        /** 失效原因（机器可判的短标记；探针失败时附上那几条子读数）。 */
        public String reasonText() {
            if (!missing.isEmpty()) {
                return "hooks_missing";
            }
            if (probe == Probe.HEALTH) {
                return "health_probe_failed" + wrap(healthNote);
            }
            if (probe == Probe.REMOVE) {
                return "remove_probe_failed" + wrap(removeNote);
            }
            return "ok";
        }

        /** {@code (a=1,b=false)} 形态；空串 ⇒ 空串（不许出现 {@code ()}）。 */
        private static String wrap(String note) {
            return note == null || note.isEmpty() ? "" : "(" + note + ")";
        }

        /**
         * {@code /shanhai_add hooks} 那一行（规格逐字）：{@code [SHANHAI-GUARD] hooks status=… data_only=… found=… missing=… probe=…}
         */
        public String commandLine(String prefix, boolean dataOnly) {
            return prefix + "hooks status=" + statusText()
                    + " data_only=" + dataOnly
                    + " found=" + found
                    + " missing=" + missingText()
                    + " probe=" + probeText();
        }

        /**
         * 失效时<b>恰好一行</b>（只在状态变化时打）：
         * <pre>
         *   [SHANHAI-GUARD] hooks_missing missing=&lt;名单&gt; probe=health|remove|ok degraded=data_only reason=… found=[实际名字]
         * </pre>
         * ⚠️ 末尾的 {@code found=[…]} 是规格里"把实际找到的名字打进日志"的落点（仍是同一行）。
         */
        public String failureLine(String prefix) {
            return prefix + "hooks_missing missing=" + missingText()
                    + " probe=" + probeText()
                    + " degraded=data_only"
                    + " reason=" + reasonText()
                    + " found=[" + foundDetail() + "]";
        }

        /** 恢复那一行（下次自检通过时打一次）。 */
        public String restoredLine(String prefix) {
            return prefix + "hooks_restored missing=(none) probe=ok found=" + found + " data_only=false";
        }
    }

    /**
     * 把「存在性读数 ＋ 两条探针的结论」合成一个 {@link Verdict}（= 合成 {@code status} / {@code probe}）。
     *
     * <p>规则（与规格一致）：
     * <ol>
     *   <li>存在性没过 ⇒ 探针按规矩<b>不跑</b>（调用方传 {@code healthOk=true, removeOk=true}），
     *       {@code probe=ok}，{@code status=missing}；</li>
     *   <li>存在性过了、血路探针失败 ⇒ {@code probe=health}；</li>
     *   <li>存在性过了、移除路探针失败 ⇒ {@code probe=remove}（两条都失败时取 health，先报更靠近"写血"的那条）；</li>
     *   <li>全过 ⇒ {@code healthy=true}。</li>
     * </ol>
     *
     * @param classes  每个类的读数（{@code null} 容忍成空表）
     * @param healthOk 血路探针结论（存在性没过时传 true）
     * @param removeOk 移除路探针结论（存在性没过时传 true）
     */
    public static Verdict compose(List<ClassCheck> classes, boolean healthOk, boolean removeOk) {
        return compose(classes, healthOk, removeOk, "", "");
    }

    /**
     * 同上，另带两条探针的失败摘要（只进 {@code reason=(…)}，不改变任何结论）。
     *
     * @param healthNote 血路探针失败时的子读数摘要（ASCII，形如 {@code rewrites_zero=false,keeps_positive=true}）
     * @param removeNote 移除路探针失败时的子读数摘要
     */
    public static Verdict compose(List<ClassCheck> classes, boolean healthOk, boolean removeOk,
                                  String healthNote, String removeNote) {
        final List<ClassCheck> safe = classes == null ? new ArrayList<ClassCheck>() : classes;
        int found = 0;
        int expected = 0;
        final List<String> missing = new ArrayList<>();
        for (ClassCheck c : safe) {
            found += c.matched.size();
            expected += c.expected;
            missing.addAll(c.missing);
        }
        final boolean missingAny = !missing.isEmpty();
        final Probe probe = missingAny ? Probe.OK
                : (!healthOk ? Probe.HEALTH : (!removeOk ? Probe.REMOVE : Probe.OK));
        final boolean healthy = !missingAny && healthOk && removeOk;
        return new Verdict(safe, found, expected, missing, probe, healthy,
                healthOk ? "" : healthNote, removeOk ? "" : removeNote);
    }

    /** 取类名的最后一段（日志里用）。 */
    public static String simpleName(String className) {
        if (className == null || className.isEmpty()) {
            return "?";
        }
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
