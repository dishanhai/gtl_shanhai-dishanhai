package com.shanhai.common.heat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 山海重构 · <b>「额外挂载槽 ×3」的判定核</b> —— <b>只 import {@code java.*}</b>，可离线 {@code javac} 驱动。
 *
 * <h2>1. 本类管什么（用户 2026-10-03 规格逐条）</h2>
 * <blockquote>
 * ① 机器上已有「额外挂载槽 ×3」（{@code PrimordialModuleMachine.extraMountSlots}）⇒ <b>让它生效</b>；<br>
 * ② 把 2026-09-30 加的「恒星热力槽（×1）」<b>删掉</b>，它的判定<b>改成读 {@code extraMountSlots}</b>；<br>
 * ③ <b>3 格【每格各自算】</b>：一格放满 64 个才算一个满足源；三格可以放三种不同的东西；<br>
 * ④ <b>一个条件占一格</b>：某条配方要几个条件，就得占几格。
 * </blockquote>
 * 用户给的「槽里放什么」规格（逐字）：
 * <pre>
 * · 超重/无重力 + 超净间（3 档）⇒ 都由【维护仓】提供，放入对应的 1 个维护仓就满足对应的效果。
 *   例：放一个「可配置重力绝对洁净维护仓」⇒ 同时满足超重/无重力 + 最高档超净间。
 * · 线圈 / 恒星热力容器 ⇒ 和以前一样，同一个槽放满 64 个。
 * · 维度要求 ⇒ 放入一个对应维度的碎片（例：主世界维度 ⇒ 放主世界碎片）。
 * · 研究要求 ⇒ 放一个创造模式数据访问仓满足所有研究要求。
 * </pre>
 *
 * <h2>2. 🔴 「一格顶几条」的最终口径（用户两句话有张力，这里定死）</h2>
 * 规格 ④ 说「一个条件占一格」，规格 ① 又给了「<b>一个维护仓同时满足重力 + 最高档超净间</b>」的例子。
 * <b>按例子实现</b>：
 * <ul>
 *   <li>一条需求只要<b>存在某一格</b>能满足它，就算满足（<b>并集覆盖</b>）；</li>
 *   <li>一格<b>可以同时顶掉多条</b>需求 —— 这正是那个维护仓例子的语义（物项自带多重能力）；</li>
 *   <li>但一条需求<b>不会</b>被"没有那一格的物项"凭空满足 ⇒ 3 格最多覆盖 3 组互不相交的能力。</li>
 * </ul>
 * 本包实测：GT 配方的条件数分布是 <b>1546 条 1 个条件 / 72 条 2 个条件 / 0 条 ≥3</b>
 * （扫描口径见交付报告 §2），其中 2 个条件的 GT 配方只有 4 条、全是「超净间 + 强重力」
 * ⇒ 按上面的口径<b>一格就够</b>，3 格有富余。
 *
 * <h2>3. 数量门槛（🔴 分工必须点明）</h2>
 * <ul>
 *   <li><b>线圈 / 恒星热力容器</b> ⇒ 该格 {@code count >= }{@value #REQUIRED_COUNT}（沿用 2026-09-30 恒星热力槽的旧口径）；</li>
 *   <li><b>维护仓 / 维度碎片 / 创造模式数据访问仓</b> ⇒ 该格 {@code count >= 1}
 *       —— 用户原话是「放入<b>一个</b>维护仓」「放入<b>一个</b>对应维度的碎片」「放<b>一个</b>创造模式数据访问仓」。
 *       对这些物品强求 64 个是做不到的（它们是机器方块/单件物品），所以不套 64。</li>
 * </ul>
 * ⚠️ 规格 ③「一格放满 64 个才算一个满足源」在本类里的落点 = <b>热力源必须放满 64</b>；
 * 其余三类按上面第 2 条（用户逐字写的「一个」）执行。<b>这是本实现对任务书的一处解释</b>，已写进交付报告。
 *
 * <h2>4. 生效判据表（每一项都有实证出处，逐条写在 {@link ShanhaiHeatSources} 的注释里）</h2>
 * <pre>
 *   需求            槽里放什么                                    数量      实证
 *   ─────────────  ────────────────────────────────────────────  ────────  ───────────────────────────────
 *   cleanroom      带 cleaning 的维护仓（3 档：超净/无菌/绝对）    ×1        A（ICleaningRoom 三档集合）
 *   gravity        带 gravity 的维护仓（重力控制仓一族）           ×1        A（IAutoConfiguratioGravityPart）
 *   dimension      对应维度的世界碎片                             ×1        A（6 个维度名直对；见 §5）
 *   research       创造模式数据访问仓                             ×1        A（id 已验；判定接管见 §6）
 *   ebf_temp       线圈（CoilBlock），放满 64                     ×64       A（旧热力槽口径）
 *   SCTier         恒星热力容器（3 种），放满 64                   ×64       A（旧热力槽口径）
 * </pre>
 *
 * <h2>5. 维度 ↔ 碎片对照（🔴 证据等级逐个标）</h2>
 * <pre>
 *   minecraft:overworld     → gtlcore:world_fragments_overworld   A（名字直对，且用户规格的例证就是它）
 *   minecraft:the_nether    → gtlcore:world_fragments_nether      A（名字直对）
 *   minecraft:the_end       → gtlcore:world_fragments_end         A（名字直对）
 *   kubejs:pluto            → gtlcore:world_fragments_pluto       A（名字直对）
 *   ad_astra:venus          → gtlcore:world_fragments_venus       A（名字直对）
 *   kubejs:barnarda         → gtlcore:world_fragments_barnarda    A（名字直对）
 *   ad_astra:*（10 个星球）  → 同名碎片                            A（名字直对，本包条件里没用到，一并收）
 *   kubejs:ancient_world    → gtlcore:world_fragments_reactor     🔴 C —— 【推断，不是实证】
 *   kubejs:create           → shanhai:world_fragments_creation    A —— 🟢 2026-10-03 新增的那一块
 *                                                                    （用户点单「创造维度碎片」，
 *                                                                     维度名 = dimension.kubejs.create「创造」）
 *   kubejs:flat / :void     → 无对应碎片                           D —— 17 块里没有任何一个对得上
 * </pre>
 * C 级那一条的依据只有「中文名『远古世界』↔ 物品中文名『远古世界碎片』」这一层语义对应；
 * 全量扫 56903 条配方<b>没有</b>找到任何一条同时引用这两个串的硬证据。**如实标注，不当 A 用。**
 *
 * <h2>6. 🔴 research 为什么由槽来判（一处<b>有意</b>的行为变化，必须让用户知情）</h2>
 * 实证（{@code javap -c com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition}）：
 * <pre>
 *   public boolean test(GTRecipe, RecipeLogic);
 *     Code:
 *          0: iconst_1
 *          1: ireturn        ← 【恒 true】
 * </pre>
 * 且全量扫 gtceu / gtlcore 的 jar：<b>没有任何 mixin 覆写 {@code ResearchCondition.test}</b>
 * （gtlcore 里命中的只有 {@code DataAccessHatchMachineMixin} 与 {@code ItemRecipeCapabilityMixin}，
 * 前者改的是 {@code IDataAccessHatch}，后者改的是 JEI 侧的研究槽显示）。
 * {@code ConfigHolder$MachineConfigs.enableResearch} 只被 {@code ResearchManager} 用来<b>生成</b>
 * 默认研究配方（datagen），<b>不参与</b>配方匹配。
 * <p>⇒ 若照「原版不满足才要槽」的规则，research 条件<b>永远自由</b>，
 * 用户要的「放创造模式数据访问仓满足研究要求」就永远看不见。
 * ⇒ 本类的决定：<b>research 一律要求槽提供</b>（无视原版的恒 true）——
 * 这让 356 条研究配方的门槛从"没有"变成"必须放一个创造模式数据访问仓"。
 * <p>⚠️ 这是<b>唯一</b>一条"槽接管后比原版更严"的条件；其余三类都是
 * 「原版判过了就过，原版没过才看槽」（宽松叠加，绝不比原版更严）。
 */
public final class ShanhaiHeatGate {

    private ShanhaiHeatGate() {}

    // ═══════════════════════════ 1. 数量与档位常量 ═══════════════════════════

    /** 热力源（线圈 / 恒星热力容器）生效所需数量。见类注释 §3 的取舍说明。 */
    public static final int REQUIRED_COUNT = 64;

    /** 额外挂载槽的格数（{@code PrimordialModuleMachine.extraMountSlots} 构造时传的 3）。 */
    public static final int SLOT_COUNT = 3;

    /** 不提供超净间。 */
    public static final int CLEANROOM_NONE = 0;
    /** 超净间（{@code cleanroom}）。 */
    public static final int CLEANROOM_PLAIN = 1;
    /** 无菌超净间（{@code sterile_cleanroom}）。 */
    public static final int CLEANROOM_STERILE = 2;
    /** 绝对超净间（{@code law_cleanroom}，gtlcore 加的）。 */
    public static final int CLEANROOM_LAW = 3;

    /** 配方 {@code data} 里的线圈炉温门槛键（GTCEu 原文拼写）。 */
    public static final String KEY_EBF_TEMP = "ebf_temp";

    /** 配方 {@code data} 里的恒星热力容器等级门槛键（gtlcore 原文拼写）。 */
    public static final String KEY_SC_TIER = "SCTier";

    /**
     * 受本槽（热力那一半）管控的 7 个配方类型 id —— 用户原话点名的 7 个，逐个从语言文件反查。
     * <pre>
     *   合金冶炼炉      → gtceu:alloy_blast_smelter
     *   电力高炉        → gtceu:electric_blast_furnace
     *   超维度熔炼      → gtceu:dimensionally_transcendent_plasma_forge
     *   混沌炼金        → gtceu:chaotic_alchemy
     *   星焰跃迁        → gtceu:stellar_lgnition
     *   恒星热能熔炼    → gtceu:stellar_forge
     *   深度化学扭曲仪  → gtceu:distort
     * </pre>
     */
    public static final Set<String> GATED_TYPE_IDS = Set.of(
            "gtceu:alloy_blast_smelter",
            "gtceu:electric_blast_furnace",
            "gtceu:dimensionally_transcendent_plasma_forge",
            "gtceu:chaotic_alchemy",
            "gtceu:stellar_lgnition",
            "gtceu:stellar_forge",
            "gtceu:distort");

    /** 这个配方类型受不受「热力那一半」管控。 */
    public static boolean isGated(String typeId) {
        return typeId != null && GATED_TYPE_IDS.contains(typeId);
    }

    /**
     * 哪几台机器**看得见热力那一半** —— 用户 2026-09-30 的选择题答案（逐字）：<b>「B. 只留那三台」</b>。
     *
     * <p>🔴 <b>2026-10-03 合并后这条白名单的现行语义</b>：那三台的<b>额外挂载槽</b>可以充当热力源
     * （线圈 / 恒星热力容器）。别的模块跑的配方类型压根不在 {@link #GATED_TYPE_IDS} 里 ⇒ 自动无效。
     * <p>（旧语义 = "这一格只在那三台上显示"；单独的热力槽已按任务书删除，槽位合并进额外挂载槽。）
     */
    public static final Set<String> HEAT_SLOT_MACHINE_IDS = Set.of(
            "shanhai:taixu_smelting_furnace",
            "shanhai:primordial_eternal_smelting_furnace",
            "shanhai:primordial_molecular_rift_core");

    /** 这台机器能不能用额外挂载槽充当热力源。 */
    public static boolean hasHeatSlot(String machineId) {
        return machineId != null && HEAT_SLOT_MACHINE_IDS.contains(machineId);
    }

    /**
     * <b>白名单自检</b>：三个 id 必须逐个都能在已注册的模块 id 列表里找到。
     *
     * <p>它是本工程"白名单写错 ⇒ 静默失效"那条老账的堵口：三个 id 只要有一个被改名 / 删掉，
     * 结果是玩家<b>看不到热力效果</b>而日志里一个字都没有。⇒ 一旦有白名单，就必须配一条注册期硬自检。
     * <p>🔴 <b>调用点不在本类的可控范围内</b>：现行调用在 {@code ModuleRegistry} 里
     * （{@code ShanhaiHeatGate.verifyMachineIds}，本任务不许改那个文件 ⇒ 该调用<b>原样保留、未动</b>）。
     *
     * @param registeredMachineIds 已注册的模块 id（形如 {@code "shanhai:" + SPECS.path()}）
     * @return <b>通过返回 {@code null}</b>；不通过返回完整的中文错误文本（调用方直接抛出去）
     */
    public static String verifyMachineIds(List<String> registeredMachineIds) {
        if (registeredMachineIds == null || registeredMachineIds.isEmpty()) {
            return "[SHANHAI-EXTRAMOUNT] 热力白名单自检失败：已注册模块 id 列表为空 / 为 null。"
                    + " 白名单 = " + HEAT_SLOT_MACHINE_IDS
                    + " ⇒ 多半是 ModuleRegistry.SPECS 没被读到；此时【任何一台机器都不会有热力效果】。";
        }
        final StringBuilder missing = new StringBuilder();
        int miss = 0;
        for (String wanted : HEAT_SLOT_MACHINE_IDS) {
            if (!registeredMachineIds.contains(wanted)) {
                miss++;
                if (missing.length() > 0) {
                    missing.append(" / ");
                }
                missing.append(wanted);
            }
        }
        if (miss == 0) {
            return null;
        }
        return "[SHANHAI-EXTRAMOUNT] 热力白名单里有 " + miss + " / " + HEAT_SLOT_MACHINE_IDS.size()
                + " 个 id 【不在已注册的模块里】：" + missing
                + " ⇒ 白名单 = " + HEAT_SLOT_MACHINE_IDS
                + "；已注册 " + registeredMachineIds.size() + " 台 = " + registeredMachineIds
                + " ⇒ 要么白名单写错了 id（后果 = 玩家【看不到热力效果】，而日志里本来一个字都不会有），"
                + "要么那台机器被改名 / 删掉了。两种都不许静默通过。";
    }

    // ═══════════════════════════ 2. 需求模型 ═══════════════════════════

    /** 一条「配方额外要求」的种类。 */
    public enum Kind {
        /** 超净间（3 档）。 */
        CLEANROOM,
        /** 无重力 / 强重力（gtlcore 的 {@code gravity} 条件）。 */
        GRAVITY,
        /** 维度。 */
        DIMENSION,
        /** 研究。 */
        RESEARCH,
        /** 线圈炉温（{@code recipe.data.ebf_temp}）。 */
        HEAT_TEMP,
        /** 恒星热力容器等级（{@code recipe.data.SCTier}）。 */
        SC_TIER,
    }

    /**
     * 一条需求。<b>不可变</b>；{@code equals/hashCode} 按三元组去重（同一条配方上重复的条件只算一条）。
     *
     * @param kind   种类
     * @param number 数值：CLEANROOM=档位 1/2/3；HEAT_TEMP=K；SC_TIER=级；其余 0
     * @param text   文本：DIMENSION=维度 id；其余 {@code null}
     */
    public static final class Requirement {

        public final Kind kind;
        public final int number;
        public final String text;

        private Requirement(Kind kind, int number, String text) {
            this.kind = kind;
            this.number = number;
            this.text = text;
        }

        public static Requirement cleanroom(int tier) {
            return new Requirement(Kind.CLEANROOM, tier, null);
        }

        public static Requirement gravity() {
            return new Requirement(Kind.GRAVITY, 0, null);
        }

        public static Requirement dimension(String dimensionId) {
            return new Requirement(Kind.DIMENSION, 0, dimensionId);
        }

        public static Requirement research() {
            return new Requirement(Kind.RESEARCH, 0, null);
        }

        public static Requirement heatTemp(int kelvin) {
            return new Requirement(Kind.HEAT_TEMP, kelvin, null);
        }

        public static Requirement scTier(int tier) {
            return new Requirement(Kind.SC_TIER, tier, null);
        }

        /** 热力那一半（要 64 个才生效的那两条）。 */
        public boolean isHeat() {
            return kind == Kind.HEAT_TEMP || kind == Kind.SC_TIER;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Requirement r)) {
                return false;
            }
            return kind == r.kind && number == r.number
                    && (text == null ? r.text == null : text.equals(r.text));
        }

        @Override
        public int hashCode() {
            return (kind.ordinal() * 31 + number) * 31 + (text == null ? 0 : text.hashCode());
        }

        /** 一行可 grep 的读数。 */
        public String describe() {
            return switch (kind) {
                case CLEANROOM -> "cleanroom=" + cleanroomName(number);
                case GRAVITY -> "gravity=任意（无重力/强重力皆可，维护仓可配置）";
                case DIMENSION -> "dimension=" + text;
                case RESEARCH -> "research";
                case HEAT_TEMP -> "ebf_temp=" + number + "K";
                case SC_TIER -> "SCTier=" + number;
            };
        }

        /** 让 {@code List<Requirement>} 打印出来就是读数（证据行直接可读，不用再解哈希码）。 */
        @Override
        public String toString() {
            return describe();
        }
    }

    /** 超净间档位 → {@code CleanroomType.getName()} 的原文口径（与 GTCEu/gtlcore 的注册名逐字一致）。 */
    public static String cleanroomName(int tier) {
        return switch (tier) {
            case CLEANROOM_PLAIN -> "cleanroom";
            case CLEANROOM_STERILE -> "sterile_cleanroom";
            case CLEANROOM_LAW -> "law_cleanroom";
            default -> "（未知档位 " + tier + "）";
        };
    }

    /**
     * {@code CleanroomType.getName()} → 档位；不认识的返回 0。
     *
     * <p>三档与层级关系是<b>实证</b>（{@code ICleaningRoom.<clinit>} 原文）：
     * <pre>
     *   CLEANROOM         = {cleanroom}
     *   STERILE_CLEANROOM = CLEANROOM + {sterile_cleanroom}
     *   LAW_CLEANROOM     = STERILE_CLEANROOM + {law_cleanroom}
     * </pre>
     * ⇒ 「绝对洁净」的集合<b>包含</b>「无菌」，再包含「超净」⇒ 档位大的天然满足档位小的。
     */
    public static int cleanroomTierOfName(String name) {
        if (name == null) {
            return CLEANROOM_NONE;
        }
        return switch (name) {
            case "cleanroom" -> CLEANROOM_PLAIN;
            case "sterile_cleanroom" -> CLEANROOM_STERILE;
            case "law_cleanroom" -> CLEANROOM_LAW;
            default -> CLEANROOM_NONE;
        };
    }

    // ═══════════════════════════ 3. 槽内容模型 ═══════════════════════════

    /**
     * <b>一格里的东西提供什么</b>。由 {@link ShanhaiHeatSources#slotContentOf} 现读出来（MC 侧）；
     * 本类只吃这份纯数据，所以整个判定可以离线驱动。
     */
    public static final class SlotContent {

        /** 什么都没有。 */
        public static final SlotContent EMPTY = new SlotContent(0, 0, 0, 0, false, false, Set.of());

        /** 这一格的物品数量。 */
        public final int count;
        /** 线圈额定炉温（K）；不是线圈 0。 */
        public final int coilTemperature;
        /** 恒星热力容器等级 1/2/3；不是容器 0。 */
        public final int containmentTier;
        /** 提供的超净间档位 0/1/2/3。 */
        public final int cleanroomTier;
        /** 是否提供重力控制。 */
        public final boolean gravity;
        /** 是否是「满足所有研究要求」的物项（创造模式数据访问仓）。 */
        public final boolean research;
        /** 该物品对应的维度（世界碎片）；不是碎片 = 空集。 */
        public final Set<String> dimensions;

        public SlotContent(int count, int coilTemperature, int containmentTier,
                           int cleanroomTier, boolean gravity, boolean research,
                           Set<String> dimensions) {
            this.count = count;
            this.coilTemperature = coilTemperature;
            this.containmentTier = containmentTier;
            this.cleanroomTier = cleanroomTier;
            this.gravity = gravity;
            this.research = research;
            this.dimensions = dimensions == null ? Set.of() : dimensions;
        }

        /** 这一格什么都没提供（空槽，或者放的东西不是任何一种挂载物）。 */
        public boolean isBlank() {
            return coilTemperature <= 0 && containmentTier <= 0 && cleanroomTier <= 0
                    && !gravity && !research && dimensions.isEmpty();
        }

        /** 一行读数（证据行与 tooltip 共用，避免两处口径漂移）。 */
        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append('×').append(count);
            if (coilTemperature > 0) {
                sb.append(" 线圈·炉温").append(coilTemperature).append('K');
            }
            if (containmentTier > 0) {
                sb.append(" 恒星热力容器·等级").append(containmentTier);
            }
            if (cleanroomTier > 0) {
                sb.append(' ').append(cleanroomName(cleanroomTier));
            }
            if (gravity) {
                sb.append(" 重力控制");
            }
            if (research) {
                sb.append(" 研究放行");
            }
            for (String d : dimensions) {
                sb.append(" 维度[").append(d).append(']');
            }
            if (isBlank()) {
                sb.append(" （不是任何一种挂载物）");
            }
            return sb.toString();
        }
    }

    // ═══════════════════════════ 4. 判定 ═══════════════════════════

    /** 拒绝原因。<b>顺序即优先级</b>（先空的、再放错的、再没放满的、最后才是不够的）。 */
    public enum Deny {
        /** 放行。 */
        NONE,
        /** 三格全空。 */
        SLOT_EMPTY,
        /** 有东西，但没有任何一格能提供这条需求要的物项。 */
        WRONG_ITEM,
        /** 物项对了，但热力源没放满 64 个。 */
        NOT_FULL,
        /** 超净间档位低于配方要求。 */
        CLEANROOM_TIER,
        /** 线圈炉温低于配方要求。 */
        HEAT_TEMP,
        /** 恒星热力容器等级低于配方要求。 */
        SC_TIER,
    }

    /** 一次判定的结果。不可变。<b>所有数字都原样带出来</b>，好让上层渲成具体文案。 */
    public static final class Outcome {

        /** 是否放行。 */
        public final boolean allowed;
        /** 原因（放行时 {@link Deny#NONE}）。 */
        public final Deny deny;
        /** 没被满足的那一条需求；放行时 {@code null}。 */
        public final Requirement blocked;
        /** 槽里实际提供的（最高）值 —— 按需求的种类给对应的那一个数字。 */
        public final int have;
        /** 三格现读的内容快照（顺序 = 槽序号）。 */
        public final List<SlotContent> slots;
        /** 被槽满足的需求（去重后）。 */
        public final List<Requirement> satisfied;
        /** 全部需求（去重后）。 */
        public final List<Requirement> needs;

        Outcome(boolean allowed, Deny deny, Requirement blocked, int have,
                List<SlotContent> slots, List<Requirement> satisfied, List<Requirement> needs) {
            this.allowed = allowed;
            this.deny = deny;
            this.blocked = blocked;
            this.have = have;
            this.slots = slots;
            this.satisfied = satisfied;
            this.needs = needs;
        }

        /** 一行可 grep 的读数（证据行用它）。 */
        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append("需求=").append(needs.isEmpty() ? "无" : needs.toString());
            sb.append(" 槽=");
            for (int i = 0; i < slots.size(); i++) {
                sb.append('[').append(i + 1).append(']').append(slots.get(i).describe()).append(' ');
            }
            sb.append("⇒ ").append(allowed ? "放行" : "拦下（" + deny + "，卡在 " + blocked.describe() + "）");
            return sb.toString();
        }
    }

    /**
     * 判定。
     *
     * @param needs 这条配方产出的需求（<b>调用方已经过滤过</b>：原版能自己满足的那几类不在这里）
     * @param slots 三格现读的内容（顺序 = 槽序号；少于 3 个也允许）
     */
    public static Outcome evaluate(List<Requirement> needs, List<SlotContent> slots) {
        final List<SlotContent> safeSlots = slots == null ? List.of() : slots;
        final List<Requirement> uniq = dedup(needs);
        final List<Requirement> satisfied = new ArrayList<>();
        Requirement blocked = null;
        for (Requirement r : uniq) {
            if (bestSlotFor(r, safeSlots) >= 0) {
                satisfied.add(r);
            } else if (blocked == null) {
                blocked = r;
            }
        }
        if (blocked == null) {
            return new Outcome(true, Deny.NONE, null, 0, safeSlots, satisfied, uniq);
        }
        return new Outcome(false, denyFor(blocked, safeSlots), blocked,
                haveFor(blocked, safeSlots), safeSlots, satisfied, uniq);
    }

    /** 去重但保序（同一条配方上可能重复出现同一种条件，重复的不该占两个名额）。 */
    private static List<Requirement> dedup(List<Requirement> needs) {
        final List<Requirement> out = new ArrayList<>();
        if (needs == null) {
            return out;
        }
        for (Requirement r : needs) {
            if (r != null && !out.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    // ── 单条需求的满足判据（**唯一的判据真源**） ──

    /** 这一格能不能满足这条需求。 */
    public static boolean slotSatisfies(Requirement r, SlotContent s) {
        if (r == null || s == null) {
            return false;
        }
        return switch (r.kind) {
            // 超净间：档位大的天然满足档位小的（ICleaningRoom 三级集合逐级包含，类注释 §4）。
            case CLEANROOM -> s.count >= 1 && s.cleanroomTier >= r.number;
            case GRAVITY -> s.count >= 1 && s.gravity;
            case DIMENSION -> s.count >= 1 && s.dimensions.contains(r.text);
            case RESEARCH -> s.count >= 1 && s.research;
            // 热力两条：必须放满 64（用户原话「和以前一样，同一个槽放满 64 个」）。
            case HEAT_TEMP -> s.count >= REQUIRED_COUNT && s.coilTemperature >= r.number;
            case SC_TIER -> s.count >= REQUIRED_COUNT && s.containmentTier >= r.number;
        };
    }

    /** 返回第一格能满足这条需求的槽序号；都不满足返回 −1。 */
    private static int bestSlotFor(Requirement r, List<SlotContent> slots) {
        for (int i = 0; i < slots.size(); i++) {
            if (slotSatisfies(r, slots.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 这条需求在槽里「实际达到了多少」—— 按种类给对应那个数字，用来渲「需要 X，当前 Y」。 */
    private static int haveFor(Requirement r, List<SlotContent> slots) {
        int best = 0;
        for (SlotContent s : slots) {
            final int v = switch (r.kind) {
                case CLEANROOM -> s.cleanroomTier;
                case HEAT_TEMP -> s.coilTemperature;
                case SC_TIER -> s.containmentTier;
                default -> 0;
            };
            best = Math.max(best, v);
        }
        return best;
    }

    /** 三格全空吗（用来把「空槽」与「放错东西」分开报）。 */
    private static boolean allEmpty(List<SlotContent> slots) {
        for (SlotContent s : slots) {
            if (s.count > 0) {
                return false;
            }
        }
        return true;
    }

    /** 给失败的这条需求挑一个**最具体**的原因。 */
    private static Deny denyFor(Requirement r, List<SlotContent> slots) {
        if (allEmpty(slots)) {
            return Deny.SLOT_EMPTY;
        }
        switch (r.kind) {
            case CLEANROOM -> {
                return haveFor(r, slots) > 0 ? Deny.CLEANROOM_TIER : Deny.WRONG_ITEM;
            }
            case HEAT_TEMP -> {
                for (SlotContent s : slots) {
                    if (s.coilTemperature > 0 && s.count < REQUIRED_COUNT) {
                        return Deny.NOT_FULL;
                    }
                }
                return haveFor(r, slots) > 0 ? Deny.HEAT_TEMP : Deny.WRONG_ITEM;
            }
            case SC_TIER -> {
                for (SlotContent s : slots) {
                    if (s.containmentTier > 0 && s.count < REQUIRED_COUNT) {
                        return Deny.NOT_FULL;
                    }
                }
                return haveFor(r, slots) > 0 ? Deny.SC_TIER : Deny.WRONG_ITEM;
            }
            default -> {
                return Deny.WRONG_ITEM;
            }
        }
    }

    // ═══════════════════════════ 5. 维度 ↔ 碎片对照表 ═══════════════════════════

    /**
     * 维度 id → 该维度的世界碎片物品 id。<b>证据等级见类注释 §5</b>（只有 {@code ancient_world} 那条是推断）。
     *
     * <p>⚠️ 本包条件里实际用到 10 个维度值。<b>2026-10-03 之前</b>其中 {@code kubejs:create} /
     * {@code kubejs:flat} / {@code kubejs:void} <b>没有</b>对应碎片 ⇒ 那三条维度需求槽路线覆盖不到、
     * 只能靠"把机器建在那个维度里"通过。🟢 <b>本轮起 {@code kubejs:create} 有碎片了</b>
     * （{@code shanhai:world_fragments_creation}，用户点单新增的「创造维度碎片」）；
     * {@code kubejs:flat} / {@code kubejs:void} <b>仍然没有</b> ——（17 个碎片里一个都对不上），
     * **如实写在这里，不编。**
     */
    private static final Map<String, String> DIMENSION_TO_FRAGMENT = buildDimensionTable();

    private static Map<String, String> buildDimensionTable() {
        final Map<String, String> m = new LinkedHashMap<>();
        // —— A 级：本包维度条件实际用到、且名字直对 ——
        m.put("minecraft:overworld", "gtlcore:world_fragments_overworld");
        m.put("minecraft:the_nether", "gtlcore:world_fragments_nether");
        m.put("minecraft:the_end", "gtlcore:world_fragments_end");
        m.put("kubejs:pluto", "gtlcore:world_fragments_pluto");
        m.put("ad_astra:venus", "gtlcore:world_fragments_venus");
        m.put("kubejs:barnarda", "gtlcore:world_fragments_barnarda");
        // —— A 级：名字直对，但本包条件里没用到（一并收，免得将来加了条件才发现做不到）——
        m.put("ad_astra:moon", "gtlcore:world_fragments_moon");
        m.put("ad_astra:mars", "gtlcore:world_fragments_mars");
        m.put("ad_astra:mercury", "gtlcore:world_fragments_mercury");
        m.put("ad_astra:glacio", "gtlcore:world_fragments_glacio");
        m.put("ad_astra:ceres", "gtlcore:world_fragments_ceres");
        m.put("ad_astra:enceladus", "gtlcore:world_fragments_enceladus");
        m.put("ad_astra:ganymede", "gtlcore:world_fragments_ganymede");
        m.put("ad_astra:io", "gtlcore:world_fragments_io");
        m.put("ad_astra:titan", "gtlcore:world_fragments_titan");
        m.put("ad_astra:pluto", "gtlcore:world_fragments_pluto");
        // —— 🔴 C 级：只有语义对应，没有硬证据（见类注释 §5）——
        m.put("kubejs:ancient_world", "gtlcore:world_fragments_reactor");
        // —— 🟢 2026-10-03 新增：gtlcore 那 16 块里缺的「创造」维度那一块（本工程自己造的物品）——
        //    · 维度名实证（A 级，原文）：实例 KubeJS 资产 `assets\kubejs\lang\zh_cn.json`
        //        "dimension.kubejs.create": "创造"
        //    · 物品实证（同轮入 jar）：`ShanhaiItems.WORLD_FRAGMENTS_CREATION`
        //        = shanhai:world_fragments_creation（贴图经用户审核通过后原样入包）
        //    · ⚠️ 反查用的 {@link #dimensionOfFragment} 是**按 value 找 key** ⇒ 这块碎片
        //        只认 kubejs:create，**不会**顺带满足别的维度（拿它去顶 ad_astra:mars 的配方仍被拦，
        //        离线读数见交付报告）。
        m.put("kubejs:create", "shanhai:world_fragments_creation");
        return Map.copyOf(m);
    }

    /** 这个维度该放哪块碎片；没有对应碎片返回 {@code null}。 */
    public static String fragmentForDimension(String dimensionId) {
        return dimensionId == null ? null : DIMENSION_TO_FRAGMENT.get(dimensionId);
    }

    /** 这块碎片对应哪个维度；不是碎片 / 没有对应返回 {@code null}。 */
    public static String dimensionOfFragment(String itemId) {
        if (itemId == null) {
            return null;
        }
        for (Map.Entry<String, String> e : DIMENSION_TO_FRAGMENT.entrySet()) {
            if (e.getValue().equals(itemId)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 对照表快照（离线判据与交付报告共用一份口径）。 */
    public static Map<String, String> dimensionTable() {
        return DIMENSION_TO_FRAGMENT;
    }
}
