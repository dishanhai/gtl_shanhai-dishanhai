package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🔴 <b>「原始配方耗时」的取回与换算</b> —— 用户 2026-10-05 点的那一条。
 *
 * <h2>用户原话（逐字）</h2>
 * <blockquote>
 * 「但是我要说 gtlcore 也对 duration 有影响，可能会有一点问题，gtlcore 有一个配方时间乘数，它会影响配方耗时，
 * 而且我发现你的编辑器是读取实际配方耗时（受 gtlcore 影响后的），
 * 而我希望修改的和读取的是原始配方耗时（jar 或者 kjs 里面写的原始数据）」
 * </blockquote>
 * 他随后又补了一条更关键的：
 * <blockquote>「不是的，原始值不一定是 1000，但是最小值是 1，所以它只能写 1」</blockquote>
 * ⇒ <b>不能从"实际值"反推"原始值"</b>（乘完有下限 1 截断，信息已经丢了）。本类因此
 * <b>不</b>做任何反推，只做两件事：<b>①去源头把原始值捞出来 ②把"原始 → 实际"的映射实测出来</b>。
 *
 * <h2>1. 原始值在哪 —— 已核实的证据链</h2>
 * gtlcore 的乘数发生在 <b>序列化那一步</b>，不在 recipe 对象上：
 * <pre>
 *   javap -c org.gtlcore.gtlcore.mixin.gtm.registry.GTRecipeBuilderMixin
 *     · private int gTLCore$getDuration()
 *         若 (durationMultiplier != 1.0 && gTLCore$eut >= 0 && recipeType ∉ {14 个"速率型"类型})
 *             return (int) min(2.147483647E9, max(1.0, abs(duration * durationMultiplier)))
 *         否则 return abs(duration)                       // 原样
 *     · public void toJson(JsonObject, CallbackInfo)
 *         json.addProperty("duration", gTLCore$getDuration())   // ← 乘在这里
 * </pre>
 * 而 {@code GTRecipeBuilder.buildRawRecipe()} 用的是 <b>未乘</b> 的 {@code this.duration}
 * ⇒ 乘数只在"写出去的那份 JSON / 注册进配方表的那一份"上兑现，<b>原始值不留在 {@code GTRecipe} 里</b>。
 *
 * <p>好在 KubeJS 的配方事件窗口（{@code KubeJSPlugin.injectRuntimeRecipes}，本工程的
 * {@link ShanhaiRecipeFingerprintCapture} 已经在用同一个窗口）能拿到每条的
 * {@code RecipeJS.json}。实测（{@code temp/recipe-editor-forensics/.../latest.FINAL.log} 的
 * {@code [EDITOR-FP-PROBE-RAW]} 行）：
 * <pre>
 *   id=gtceu:assembler/zpm_256a_laser_source_hatch raw={"type":"gtceu:assembler","duration":300,…}
 * </pre>
 * ⇒ <b>{@code js.json.duration} 就是"源声明里写的那个数"</b>，本类把它按 id 缓存下来当原始值。
 *
 * <h2>2. 「原始 → 实际」的映射：实测，不假设</h2>
 * 乘数有几个已知的例外（14 个"速率型"配方类型、{@code eut < 0}），
 * <b>本类不复刻那张例外表</b>（复刻 = 又一次"照抄别人的私有实现"，抄错就静默改坏机器耗时）。
 * 改成<b>用数据自己把映射解出来</b>：对每个配方类型，拿缓存里那批
 * {@code (原始, 实际)} 配对去判：
 * <pre>
 *   全部 orig == live            ⇒ IDENTITY     （这个类型没被乘 ⇒ 写进去就写原值）
 *   有 live > 1 的样本且比值一致  ⇒ LINEAR(k)    （乘数 = k，写进去时按 gtlcore 同一条式子换算）
 *   其余                          ⇒ UNFITTED    （判不出来 ⇒ 一律按原值走，并打 WARN 留痕）
 * </pre>
 * 三条都是<b>可判读的读数</b>（样本数、比值、结论都进日志），
 * 而不是"我认为它应该是 0.001"。
 *
 * <h2>3. 为什么不直接用 {@code gtlcore.yaml} 里的 0.001</h2>
 * 因为用户实测的两条读数<b>对不上"全表都乘 0.001"</b>：
 * <ul>
 *   <li>{@code gtceu:assembler/zpm_256a_laser_source_hatch}：JSON 300 / 实际 300
 *       （读自上一次干净开机的 {@code entry.fields.duration=300 == live=300}）—— 没被乘；</li>
 *   <li>而 {@code gtceu:space_ore_processor/...} 这类在用户自己那一局的日志里是 {@code dur=1}。</li>
 * </ul>
 * ⇒ 乘数<b>不是</b>一律生效的。到底哪些被乘，只能实测（见 {@code duration_scale} 那几行日志）。
 */
public final class ShanhaiRecipeDuration {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 原始耗时缓存（id → KJS/数据包源声明里的 duration）。只在 KubeJS 那个一次性窗口里填。 */
    private static final Map<ResourceLocation, Integer> ORIG = new HashMap<>(65536);

    /**
     * 🔴 <b>开机那一份原始值的【不动快照】</b>（2026-10-05 第 8 局补）。
     *
     * <h4>为什么必须再存一张</h4>
     * 用户实测：「关闭后再打开编辑器里面重新变回了 1200」—— 他改成 5000、JEI 也变了
     * （0.05s → 0.25s = 5 tick = 5000×0.001，换算与写入都是对的），只有编辑器<b>重开时显示回了 1200</b>。
     * 根因：{@link #ORIG} 是"开机时抓一次、之后不再更新"的表，面板读它 ⇒ 显示的是<b>开机那一份</b>。
     * 修法 = 保存之后把 {@link #ORIG} 同步成新值（见 {@link #setCurrentOriginal}）。
     *
     * <p>但 {@link #ORIG} 同时还是 {@link ShanhaiRecipeBase#setDuration} 判"改回原值就撤掉台账"
     * 的依据，以及"恢复原样"要回落到的那一份 ⇒ <b>它必须还能回到开机值</b>。
     * 所以另存一张只读快照，{@link #resetOriginal} 从它回滚。
     *
     * <p>🔴 这张表<b>任何编辑路径都不许写</b>（{@link #calibrateIfNeeded} 也改用它 ——
     * 否则"改完再重抓底本"会把用户改过的值当成源声明去拟合乘数）。
     */
    private static final Map<ResourceLocation, Integer> ORIG_BOOT = new HashMap<>(65536);

    /** 判定的映射形态。 */
    public enum Mode {
        /** 所有样本 orig == live ⇒ 这个类型没被乘。 */
        IDENTITY,
        /** 拟合出稳定的比值 k ⇒ 按 gtlcore 的同一条式子换算。 */
        LINEAR,
        /** 样本不足或比值不一致 ⇒ <b>不猜</b>：一律按原值走。 */
        UNFITTED
    }

    /** 一个配方类型的映射结论。 */
    public record Scale(Mode mode, double k, int samples, String why) {
        public boolean identityOrUnknown() {
            return mode != Mode.LINEAR;
        }
    }

    private static final Map<String, Scale> SCALES = new LinkedHashMap<>();
    private static boolean calibrated = false;
    private static String calibrateSummary = "(never)";

    private ShanhaiRecipeDuration() {}

    // ------------------------------------------------------------------ 填原始值

    /**
     * 收下 KubeJS 窗口里读到的那一份 {@code id → 原始 duration}（由
     * {@link ShanhaiRecipeFingerprintCapture} 在同一个窗口里 <b>顺手</b> 抓的第二张表，不额外遍历一遍对象图）。
     */
    public static synchronized void installOriginals(Map<ResourceLocation, Integer> src) {
        ORIG.clear();
        ORIG_BOOT.clear();
        if (src != null) {
            for (Map.Entry<ResourceLocation, Integer> e : src.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    ORIG.put(e.getKey(), e.getValue());
                    ORIG_BOOT.put(e.getKey(), e.getValue());
                }
            }
        }
        calibrated = false;
        SCALES.clear();
        ShanhaiMod.LOGGER.info("{} duration_orig_installed unique_ids={} boot_snapshot_ids={} "
                        + "（来源 = KubeJS injectRuntimeRecipes 窗口里 RecipeJS.json 的 duration，"
                        + "即【源声明里写的那个数】；没有任何反推）",
                PREFIX, ORIG.size(), ORIG_BOOT.size());
    }

    public static synchronized int origCount() {
        return ORIG.size();
    }

    /** 这条 id 有没有原始值记录。 */
    public static synchronized boolean hasOriginal(ResourceLocation id) {
        return id != null && ORIG.containsKey(id);
    }

    public static synchronized Integer rawOriginal(ResourceLocation id) {
        return id == null ? null : ORIG.get(id);
    }

    /** 这条 id 在<b>开机那一份</b>里的原始值（不可变快照；没有则 null）。 */
    public static synchronized Integer bootOriginal(ResourceLocation id) {
        return id == null ? null : ORIG_BOOT.get(id);
    }

    // ------------------------------------------------------------------ 同步这张表（本轮修的 bug）

    /**
     * 🔴 <b>把"当前原始值"同步成用户刚写进去的那个数</b> —— 本轮修的就是这一条。
     *
     * <p>用户原话（逐字）：「关闭后再打开编辑器里面重新变回了 1200，jei 里显示从 0.05s 变成了 0.25s，
     * 应该是修改成功了，但是配方编辑器里面的显示有一些问题」。
     * 面板读的就是这张表 ⇒ <b>不回写它，重开就一定显示回旧值</b>。
     *
     * <p>判据行（机器可 grep）：{@code duration_orig_updated id=… 1200 -> 5000}。
     *
     * @return true = 表确实被改了（旧值与新值不同）
     */
    public static synchronized boolean setCurrentOriginal(ResourceLocation id, int value) {
        if (id == null) {
            return false;
        }
        final Integer old = ORIG.get(id);
        final Integer boot = ORIG_BOOT.get(id);
        ORIG.put(id, value);
        final boolean changed = old == null || old != value;
        ShanhaiMod.LOGGER.info("{} duration_orig_updated id={} {} -> {} changed={} boot={} "
                        + "（这张表是面板重开时读的那一份；同步之后重开显示的就是新值）",
                PREFIX, id, old == null ? "(absent)" : old, value, changed,
                boot == null ? "(absent)" : boot);
        return changed;
    }

    /**
     * <b>恢复原样</b>时把这张表跟回开机那一份（用户点单：「若该配方被"恢复原样"，表里也要跟着回到原始值」）。
     *
     * <p>判据行：{@code duration_orig_reset id=… 5000 -> 1200}。
     */
    public static synchronized boolean resetOriginal(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        final Integer old = ORIG.get(id);
        final Integer boot = ORIG_BOOT.get(id);
        if (boot == null) {
            // 本来就没有这条的开机值（这条配方的源声明里没写 duration）⇒ 把键【删掉】，
            // 不能让"编辑时新建的那个键"留在表里 —— 留着的话，"恢复原样"之后面板会显示改过的值。
            final boolean removed = old != null;
            ORIG.remove(id);
            ShanhaiMod.LOGGER.info("{} duration_orig_reset id={} boot_absent removed_key={} (old={})",
                    PREFIX, id, removed, old == null ? "(absent)" : old);
            return removed;
        }
        ORIG.put(id, boot);
        final boolean changed = old == null || old.intValue() != boot.intValue();
        ShanhaiMod.LOGGER.info("{} duration_orig_reset id={} {} -> {} changed={}",
                PREFIX, id, old == null ? "(absent)" : old, boot, changed);
        return changed;
    }

    /** 一键恢复全部：把整张表回滚到开机快照。 */
    public static synchronized int resetAllOriginals() {
        int n = 0;
        for (Map.Entry<ResourceLocation, Integer> e : ORIG_BOOT.entrySet()) {
            final Integer cur = ORIG.get(e.getKey());
            if (cur == null || cur.intValue() != e.getValue().intValue()) {
                ORIG.put(e.getKey(), e.getValue());
                n++;
            }
        }
        ShanhaiMod.LOGGER.info("{} duration_orig_reset_all rolled_back={} of_boot_snapshot={}",
                PREFIX, n, ORIG_BOOT.size());
        return n;
    }

    // ------------------------------------------------------------------ 校准映射

    /**
     * 用底本快照校准每个配方类型的映射。幂等：同一份底本只校准一次。
     *
     * @param base id → 底本对象（{@link ShanhaiRecipeBase#pristine} 那套）
     */
    public static synchronized void calibrateIfNeeded(Map<ResourceLocation, GTRecipe> base) {
        if (calibrated || base == null || base.isEmpty()) {
            return;
        }
        calibrated = true;
        SCALES.clear();
        if (ORIG_BOOT.isEmpty()) {
            calibrateSummary = "无原始值缓存 ⇒ 全部按【原值 == 实际值】处理（本局不做任何换算）";
            ShanhaiMod.LOGGER.warn("{} duration_scale NONE —— {} ", PREFIX, calibrateSummary);
            return;
        }

        // 按类型收集 (orig, live) 配对
        // 🔴 用【开机快照】ORIG_BOOT，不用会被编辑改写的 ORIG —— 否则"改完再重抓底本"会把
        //    用户改过的值当成源声明去验证乘数（那是把噪声当基准，会判出假的 UNFITTED）。
        final Map<String, java.util.List<int[]>> pairs = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, GTRecipe> e : base.entrySet()) {
            final Integer o = ORIG_BOOT.get(e.getKey());
            if (o == null) {
                continue;
            }
            final GTRecipe r = e.getValue();
            if (r == null) {
                continue;
            }
            final String t = typeKey(r.getType());
            pairs.computeIfAbsent(t, x -> new java.util.ArrayList<>()).add(new int[]{o, r.duration});
        }

        final double k = configMultiplier();
        int identity = 0;
        int linear = 0;
        int unfitted = 0;
        int totalPairs = 0;
        for (Map.Entry<String, java.util.List<int[]>> e : pairs.entrySet()) {
            final Scale s = classify(e.getKey(), e.getValue(), k);
            SCALES.put(e.getKey(), s);
            totalPairs += e.getValue().size();
            switch (s.mode()) {
                case IDENTITY -> identity++;
                case LINEAR -> linear++;
                default -> unfitted++;
            }
            ShanhaiMod.LOGGER.info("{} duration_scale type={} mode={} k={} samples={} why={}",
                    PREFIX, e.getKey(), s.mode(), String.format(java.util.Locale.ROOT, "%.9f", s.k()),
                    s.samples(), s.why());
        }
        calibrateSummary = "k=" + fmt(k) + " types=" + SCALES.size() + " identity=" + identity
                + " linear=" + linear + " unfitted=" + unfitted + " pairs=" + totalPairs;
        ShanhaiMod.LOGGER.info("{} duration_scale DONE {}", PREFIX, calibrateSummary);

        // 有样本的类型之外还有一大堆类型：它们【没有任何证据】，一律按"没被乘"处理（保守 =
        // 与改动之前的行为逐字节相同）。这条必须显式说清楚，否则"没打印 = 没问题"就是假绿。
        ShanhaiMod.LOGGER.info("{} duration_scale NOTE 只有出现在上面那些行里的类型是【实测过的】；"
                        + "其余类型一律按 mode=UNFITTED（= 读写都用原值，与改动前行为相同）",
                PREFIX);
    }

    /**
     * <b>反射读 gtlcore 的乘数本身</b>（{@code org.gtlcore.gtlcore.config.ConfigHolder.durationMultiplier}）。
     *
     * <h4>🔴 为什么必须读它，而不是"从数据里拟合一个 k"</h4>
     * 2026-10-05 第 7 局实测到的现场：
     * <pre>
     *   冒烟装置  temp\smoke-rig\server\config\gtlcore.yaml : durationMultiplier: 1.0
     *   用户实例  GTL山海9.10test\config\gtlcore.yaml       : durationMultiplier: 0.001
     * </pre>
     * ⇒ <b>两个环境配的不是同一个乘数</b>。第一版是"用样本拟合 k"，于是在冒烟装置上
     * 得到 202 个类型里 199 个 {@code IDENTITY}（那是对的 —— 装置上确实没乘），
     * 而这条读数<b>对用户实例毫无参考价值</b>；更糟的是，拟合需要"每个类型 ≥8 个能解出比值的样本"，
     * 而 {@code k=0.001} 时只有 {@code 原始>1000} 的样本才解得出（{@code 1200×0.001=1.2→1} 就解不出）
     * ⇒ 一个本该生效的类型可能因为"样本不够"被判成不动手，<b>功能静默失效</b>。
     *
     * <p>正确做法 = <b>把 k 当已知量读进来，然后用数据去【验证】每个类型到底乘没乘</b>
     * （{@code live == clamp(orig*k)} 就乘了、{@code live == orig} 就没乘）。
     * 验证比拟合强：它不需要样本数下限，也不会把"一条配方自己的差异"当成乘数。
     *
     * <p>读不到（gtlcore 不在 / 字段改名）⇒ 返回 1.0 ⇒ 全表按恒等处理
     * = <b>与改动之前逐字节相同</b>（宁可不动手，也不猜一个乘数）。
     */
    private static double readConfigMultiplier() {
        for (String cls : new String[]{
                "org.gtlcore.gtlcore.config.ConfigHolder",
                "org.gtlcore.gtlcore.config.GTLConfigHolder"}) {
            try {
                final Class<?> ch = Class.forName(cls);
                final Object inst = ch.getField("INSTANCE").get(null);
                java.lang.reflect.Field f = null;
                try {
                    f = ch.getField("durationMultiplier");
                } catch (NoSuchFieldException nsf) {
                    f = ch.getDeclaredField("durationMultiplier");
                    f.setAccessible(true);
                }
                final double v = f.getDouble(inst);
                ShanhaiMod.LOGGER.info("{} duration_scale_config class={} durationMultiplier={} "
                                + "（反射读自 gtlcore 自己的配置；本类不依赖 gtlcore 编译期符号）",
                        PREFIX, cls, String.format(java.util.Locale.ROOT, "%.9f", v));
                return v;
            } catch (Throwable ignored) {
                // 试下一个候选
            }
        }
        ShanhaiMod.LOGGER.warn("{} duration_scale_config UNAVAILABLE reason=reflection_failed "
                + "⇒ 按 k=1.0 处理（读写都用原值，与改动前行为相同）", PREFIX);
        return 1.0;
    }

    private static double CONFIG_K = Double.NaN;

    /** 本局生效的乘数（只读一次）。 */
    public static synchronized double configMultiplier() {
        if (Double.isNaN(CONFIG_K)) {
            double v = 1.0;
            try {
                v = readConfigMultiplier();
            } catch (Throwable t) {
                v = 1.0;
            }
            CONFIG_K = (v == 0.0 || !Double.isFinite(v)) ? 1.0 : v;
        }
        return CONFIG_K;
    }

    /**
     * 用<b>已知的 k</b> 去验证一个类型到底乘没乘（不是拟合）。
     *
     * <pre>
     *   所有样本 live == orig          ⇒ IDENTITY （这个类型没乘；含 gtlcore 那 14 个"速率型"例外）
     *   所有样本 live == clamp(orig×k) ⇒ LINEAR   （乘了，按同一条式子换算是安全的）
     *   其余                            ⇒ UNFITTED（数据自相矛盾 ⇒ 一律不动手，并留一行日志）
     * </pre>
     */
    private static Scale classify(String type, java.util.List<int[]> samples, double k) {
        boolean allIdentity = true;
        boolean allScaled = true;
        int scaledSamples = 0;
        int differing = 0;
        for (int[] p : samples) {
            final int o = p[0];
            final int l = p[1];
            if (o != l) {
                allIdentity = false;
                differing++;
            }
            if (l != toLiveRaw(k, o)) {
                allScaled = false;
            } else if (o != l) {
                scaledSamples++;
            }
        }
        if (allIdentity) {
            return new Scale(Mode.IDENTITY, 1.0, samples.size(),
                    "所有样本 原始==实际（k=" + fmt(k) + " 上这个类型不乘）");
        }
        if (allScaled && scaledSamples > 0) {
            return new Scale(Mode.LINEAR, k, samples.size(),
                    "所有样本 实际==clamp(原始×" + fmt(k) + ")；其中 " + scaledSamples + " 个真的被乘了");
        }
        return new Scale(Mode.UNFITTED, 1.0, samples.size(),
                "数据对不上 k=" + fmt(k) + "（有差异样本 " + differing + "）⇒ 不猜、不动手");
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.6f", d);
    }

    private static String typeKey(GTRecipeType type) {
        return type == null || type.registryName == null ? "(null)" : type.registryName.toString();
    }

    public static synchronized Scale scaleOf(GTRecipeType type) {
        return SCALES.getOrDefault(typeKey(type),
                new Scale(Mode.UNFITTED, 1.0, 0, "该类型没有样本"));
    }

    public static synchronized String summary() {
        return calibrateSummary + " orig_cached=" + ORIG.size();
    }

    // ------------------------------------------------------------------ 读写两个口

    /**
     * <b>读</b>：这条配方在"源声明"里写的耗时。
     *
     * <p>没有原始值记录时返回 {@code r.duration}（= 实际值），并且<b>如实</b>由
     * {@link #hasOriginal} 告诉调用方"这个数不是原始值" —— 界面据此加一个后缀，
     * 而不是把实际值冒充成原始值。
     */
    public static int originalOf(GTRecipe r) {
        if (r == null) {
            return -1;
        }
        final Integer o = rawOriginal(r.id);
        return o != null ? o : r.duration;
    }

    /**
     * <b>写</b>：把"用户写的原始值"换算成<b>应当落在 {@code GTRecipe.duration} 上的实际值</b>。
     *
     * <p>复刻的是 gtlcore 自己的那条式子（见类注释的字节码）：
     * {@code (int) min(2.147483647E9, max(1.0, abs(orig * k)))}。
     * <b>不含</b>那张 14 类型的例外表 —— 例外由"实测出来的映射"表达（那个类型会判成 IDENTITY）。
     *
     * <p>映射判不出来时（IDENTITY / UNFITTED）<b>原样返回</b> ⇒ 与改动之前的行为逐字节相同。
     */
    public static int toLive(GTRecipeType type, int original) {
        final Scale s = scaleOf(type);
        if (s.identityOrUnknown()) {
            return original;
        }
        final double v = Math.abs((double) original * s.k());
        final double clamped = Math.min(2147483647.0, Math.max(1.0, v));
        return (int) clamped;
    }

    /** 无类型的换算口（自检/测试用）。 */
    public static int toLiveRaw(double k, int original) {
        final double v = Math.abs((double) original * k);
        return (int) Math.min(2147483647.0, Math.max(1.0, v));
    }

    /**
     * <b>这一条链的机器判据</b>（纯内存，不需要 server，不需要原始值缓存）。
     *
     * <p>它锁的是"换算函数与 gtlcore 同形"和"判不出来时不动手"这两条纪律：
     * <pre>
     *   D1 k=0.001, orig=1000  ⇒ 1      （1000×0.001 = 1，正好在下限）
     *   D2 k=0.001, orig=60    ⇒ 1      （0.06 被下限抬起 —— 这就是"不能反推"的现场）
     *   D3 k=0.001, orig=1200  ⇒ 1
     *   D4 k=0.001, orig=3000  ⇒ 3
     *   D5 k=1,     orig=1200  ⇒ 1200   （IDENTITY 通路不动手）
     *   D6 负对照：D2 的结果【必须不是】60（否则说明"原始值直接写进去了"，机器会慢 1000 倍）
     * </pre>
     */
    public static void selfcheck() {
        int pass = 0;
        int total = 0;
        final StringBuilder bad = new StringBuilder();
        final int[][] cases = {{1000, 1}, {60, 1}, {1200, 1}, {3000, 3}, {1200, 1200}};
        for (int i = 0; i < cases.length; i++) {
            total++;
            final int k = (i == 4) ? 1 : 0;   // 占位，下面分开算
            final int got = (i == 4)
                    ? toLiveRaw(1.0, cases[i][0])
                    : toLiveRaw(0.001, cases[i][0]);
            if (got == cases[i][1]) {
                pass++;
            } else {
                bad.append(" D").append(i + 1).append("(orig=").append(cases[i][0])
                        .append(" 期望=").append(cases[i][1]).append(" 实得=").append(got).append(')');
            }
            if (k == 0) {
                // no-op（保留变量以免误导）
            }
        }
        total++;
        if (toLiveRaw(0.001, 60) != 60) {
            pass++;
        } else {
            bad.append(" D6-NEG 反推没被排除：orig=60 被原样写进去");
        }
        // ── D7/D8/D9 分类判据（k 已知、拿数据去【验证】；这是用户实例那条真现场的形状） ──
        //    D7 = 用户实例的现场：源声明 20 / 实际 1（20×0.001=0.02 → 下限 1）⇒ 必须判 LINEAR，
        //         且 toLive(20) 必须回到 1（否则机器会跑 20 tick，而不是它该跑的 1 tick）。
        total++;
        final Scale s7 = classify("probe", java.util.List.of(new int[]{20, 1}), 0.001);
        if (s7.mode() == Mode.LINEAR && toLiveRaw(0.001, 20) == 1) {
            pass++;
        } else {
            bad.append(" D7 mode=").append(s7.mode()).append(" toLive(20)=").append(toLiveRaw(0.001, 20));
        }
        //    D8 = 反向：数据说"这个类型没被乘"（1200→1200）⇒ 判 IDENTITY，toLive 恒等。
        total++;
        final Scale s8 = classify("probe", java.util.List.of(new int[]{1200, 1200}), 0.001);
        if (s8.mode() == Mode.IDENTITY && toLiveRaw(0.001, 1200) != 1200) {
            // 注意：这里断言的是"分类器认出没被乘"，而不是"换算函数返回 1200" ——
            // 换算函数只在 LINEAR 通路上被调用（toLive 对 IDENTITY 直接原样返回）。
            pass++;
        } else {
            bad.append(" D8 mode=").append(s8.mode());
        }
        //    D9 = 自相矛盾（同一个类型里既有 20→1 又有 1200→1200）⇒ 必须 UNFITTED（不动手）。
        total++;
        final Scale s9 = classify("probe", java.util.List.of(new int[]{20, 1}, new int[]{1200, 1200}), 0.001);
        if (s9.mode() == Mode.UNFITTED) {
            pass++;
        } else {
            bad.append(" D9 mode=").append(s9.mode()).append("（自相矛盾的数据必须判不动手）");
        }

        final boolean ok = pass == total;
        ShanhaiMod.LOGGER.info("{} DURATION_SELFCHECK {}/{} PASS={}（判据 = 换算与 gtlcore 的 "
                        + "min(INT_MAX,max(1,abs(orig*k))) 同形；D6 = 负对照，禁止【把原始值直接写进 live】）",
                PREFIX, pass, total, ok);
        if (!ok) {
            ShanhaiMod.LOGGER.error("{} DURATION_SELFCHECK 未过：{}", PREFIX, bad);
        }
    }
}
