package com.shanhai.machine.stellar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「这块样板<b>是什么配方类型</b>」的<b>只读</b>读取层。
 *
 * <h2>一、四个来源（全部读，全部归一化，最后才判断）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>来源</th><th>位置/工具</th><th>值的形状</th></tr>
 *   <tr><td>①</td><td>{@code gtlcore.patternQuickUploadRecipeTypes}</td>
 *       <td>gtlcore 公开工具 {@link PatternQuickUploadMetadata#readRecipeTypeIds(ItemStack)}</td>
 *       <td>{@code ListTag<String>}（**可多值**）</td></tr>
 *   <tr><td>②</td><td>{@code gt_shanhai_recipe_type}</td><td>样板 NBT 根</td>
 *       <td>String（防御式也认 {@code ListTag<String>}）</td></tr>
 *   <tr><td>③</td><td>{@code gt_shanhai_recipe_id}</td><td>样板 NBT 根</td>
 *       <td>String，取第一个 {@code '/'} 之前那段当类型</td></tr>
 *   <tr><td>④</td><td>{@code gt_shanhai_recipe_type_authoritative}</td><td>样板 NBT 根</td>
 *       <td>若为 String ⇒ 当类型 id；若为数字/布尔 ⇒ <b>只记「有这个标记」，不产生类型值</b></td></tr>
 * </table>
 *
 * <h2>二、判定：去重后<b>只剩 1 个</b>才采信（不猜）</h2>
 * <pre>
 *   0 个 ⇒ NONE        （= 自动识别，交回调用方）
 *   1 个 ⇒ UNIQUE      （唯一采信）
 *   ≥2 个 ⇒ AMBIGUOUS  （**不采信**，原样把全部候选交出去；不做「优先级最高那个」这种猜测）
 * </pre>
 * ⚠️ ④「权威声明」<b>也走同一条去重规则</b>，没有特殊优先权 —— 这是刻意的：
 * 一旦让 ④ 覆盖其它来源，就变成「谁写的键赢」而不是「样板自己说的是什么」，
 * 而阶段三的三道闸需要的正是「样板自己说的是什么」。若将来确实要 ④ 一票否决，
 * 那是一个<b>显式的设计变更</b>，必须同时改本节文档与阶段三的闸，不许悄悄加。
 *
 * <h2>三、🔴 本类是<b>只读</b>的：不写 NBT、不改机器状态、不做拦截</h2>
 * 唯一的「写」在 {@link ShanhaiRecipeTypeIds} 的常量（键名）与
 * {@code StellarSampleAssemblerPartMachine#applyWildcardRecipeType}（母槽配置，阶段二新增，
 * 写的是 ② 那个键 —— 也就是本类的一个<b>输入</b>）。本类本身<b>一个 setter 都没有</b>。
 *
 * <h2>四、异常与缺键</h2>
 * 样板没 NBT / 键不存在 / 值形状不认识 / gtlcore 工具抛异常 ⇒ 该来源按<b>空</b>处理，全部包在
 * {@code try/catch (Throwable)} 里；本类<b>永不抛异常</b>（它会在 UI 与服务端 tick 路径上被调用）。
 */
public final class ShanhaiPatternTypeReader {

    /** 四个来源。枚举顺序 = 日志里的打印顺序（= 上表 ①→④）。 */
    public enum Source {
        QUICK_UPLOAD("gtlcore.patternQuickUploadRecipeTypes"),
        GT_SHANHAI_TYPE(ShanhaiRecipeTypeIds.KEY_RECIPE_TYPE),
        GT_SHANHAI_RECIPE_ID(ShanhaiRecipeTypeIds.KEY_RECIPE_ID),
        AUTHORITATIVE(ShanhaiRecipeTypeIds.KEY_AUTHORITATIVE);

        private final String nbtKey;

        Source(String nbtKey) {
            this.nbtKey = nbtKey;
        }

        /** 这个来源对应的 NBT 键（① 的键在 {@code gtlcore} 复合标签里，这里是字面路径，仅供日志阅读）。 */
        public String nbtKey() {
            return this.nbtKey;
        }
    }

    /** 判定结果。 */
    public enum Verdict {
        /** 四个来源都没给出可用的类型 ⇒ 交回自动识别。 */
        NONE,
        /** 归一化去重后正好 1 个 ⇒ 采信。 */
        UNIQUE,
        /** 去重后 ≥2 个 ⇒ <b>不采信</b>（歧义），只把候选交出去。 */
        AMBIGUOUS
    }

    /**
     * <b>防串配方闸的唯一判定点</b>（阶段七扩展；纯函数，探针实跑真值表）。
     *
     * <pre>
     *   闸开（默认）        UNIQUE ⇒ 放行；NONE ⇒ 拦；AMBIGUOUS ⇒ 拦
     *   闸关（排查/临时）    一律放行
     * </pre>
     *
     * <h2>🔴 为什么 NONE 也要拦（阶段七的用户要求）</h2>
     * 一块<b>没填配方类型</b>的样板，机器不可能知道它属于哪类配方 ⇒ 放它跑就等于「猜」，
     * 而猜错正是需求①「串配方」最典型的入口。旧口径（NONE 放行）当年是为了「绝大多数样板没人
     * 写类型键，禁掉会让全机停摆」，现在改成<b>默认拦、并让用户一眼看见</b>
     * （机器 GUI 计数 + Jade 两处提示 + 带槽号的 WARN）—— 拦得可见，就不是「静默停摆」。
     *
     * @param verdict    该槽样板的判定
     * @param gateEnabled {@code blockAmbiguousPatterns}（保留的老开关，本阶段语义<b>扩展到 NONE</b>）
     * @return true = 该槽<b>不参与推送</b>
     */
    public static boolean gateBlocks(@NotNull Verdict verdict, boolean gateEnabled) {
        if (!gateEnabled) {
            return false;
        }
        return verdict != Verdict.UNIQUE;
    }

    /**
     * 一次读取的完整结果。
     *
     * @param verdict     判定
     * @param ids         归一化去重后的候选（NONE 时为空表；顺序 = 首次出现的顺序）
     * @param rawBySource 每个来源读到的<b>原始</b>值（没读到 = 空表；用于日志断言与排查）
     * @param unverified  {@code ids} 里在 GTCEu 注册表中<b>查不到</b>的那些（只用于诊断）
     */
    public record Resolution(@NotNull Verdict verdict, @NotNull List<String> ids,
                             @NotNull Map<Source, List<String>> rawBySource,
                             @NotNull List<String> unverified) {

        /** 唯一采信的值；不是 UNIQUE 时 {@code ""}。 */
        public @NotNull String uniqueId() {
            return this.verdict == Verdict.UNIQUE ? this.ids.get(0) : "";
        }

        /** 有没有任何来源写了东西（哪怕写错/歧义）。 */
        public boolean hasAnyRaw() {
            for (List<String> values : this.rawBySource.values()) {
                if (!values.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /** 一行式描述（日志用，稳定格式，方便 grep）。 */
        public @NotNull String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("verdict=").append(this.verdict);
            sb.append(" ids=").append(this.ids);
            sb.append(" raw={");
            boolean first = true;
            for (Map.Entry<Source, List<String>> e : this.rawBySource.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey().name()).append('=').append(e.getValue());
            }
            sb.append('}');
            if (!this.unverified.isEmpty()) {
                sb.append(" unverified=").append(this.unverified);
            }
            return sb.toString();
        }
    }

    private static final Resolution EMPTY = new Resolution(
            Verdict.NONE, List.of(), emptyRaw(), List.of());

    private ShanhaiPatternTypeReader() {}

    private static Map<Source, List<String>> emptyRaw() {
        final Map<Source, List<String>> map = new EnumMap<>(Source.class);
        for (Source source : Source.values()) {
            map.put(source, List.of());
        }
        return map;
    }

    /** 读完四个来源并给出判定。<b>永不为 null、永不抛</b>。 */
    public static @NotNull Resolution resolve(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        // ① 需要 ItemStack（走 gtlcore 的公开工具），②③④ 只需要 NBT —— 分开取，再交给同一个 decide。
        return decide(Map.of(Source.QUICK_UPLOAD, readQuickUpload(stack)), stack.getTag());
    }

    /**
     * <b>只读 NBT 的入口</b>（来源 ②③④，① 视为没有）：给「拿不到 ItemStack」的场景用。
     *
     * <p>存在的理由有两个，都是硬需求：
     * <ol>
     *   <li><b>可运行的自检</b>：{@code ItemStack} 要 Item 实例，构造它需要注册表就绪（无头跑不起来）；
     *       而 {@link CompoundTag} 是纯数据类 ⇒ 判定逻辑能在 JVM 里<b>实跑</b>正/负对照
     *       （见 {@code temp\stellar-p1\probe2\TypeProbe.java}）；</li>
     *   <li>阶段三的命令/诊断路径可能只拿到一份 NBT（比如从样板箱里拷出来的 tag）。</li>
     * </ol>
     * <p>⚠️ 本方法<b>不</b>读 ①：那是 gtlcore 的键，必须走它的公开工具，自己拼键名会在它改键时静默读空。
     */
    public static @NotNull Resolution resolveTagOnly(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return EMPTY;
        }
        return decide(Map.of(), tag);
    }

    /**
     * 唯一判定点：把 ①（调用方已读好的原始值）+ ②③④（从 NBT 读）归一化去重后定级。
     *
     * @param quickUploadRaw 来源 ① 的原始值（没有就传空 map 或空表）
     * @param tag            样板 NBT（可为 null）
     */
    private static @NotNull Resolution decide(@NotNull Map<Source, List<String>> quickUploadRaw,
                                              @Nullable CompoundTag tag) {
        final Map<Source, List<String>> raw = new EnumMap<>(Source.class);
        final List<String> quickUpload = quickUploadRaw.getOrDefault(Source.QUICK_UPLOAD, List.of());
        raw.put(Source.QUICK_UPLOAD, quickUpload);
        raw.put(Source.GT_SHANHAI_TYPE, readStringOrStringList(tag, Source.GT_SHANHAI_TYPE.nbtKey()));
        final List<String> recipeIds = readStringOrStringList(tag, Source.GT_SHANHAI_RECIPE_ID.nbtKey());
        raw.put(Source.GT_SHANHAI_RECIPE_ID, recipeIds);
        raw.put(Source.AUTHORITATIVE, readStringOrStringList(tag, Source.AUTHORITATIVE.nbtKey()));

        // ---- 归一化 + 去重（LinkedHashSet：保住「首次出现」的顺序，日志才可复现）
        final Set<String> ids = new LinkedHashSet<>();
        for (String value : quickUpload) {
            add(ids, value);
        }
        for (String value : raw.get(Source.GT_SHANHAI_TYPE)) {
            add(ids, value);
        }
        for (String recipeId : recipeIds) {
            add(ids, ShanhaiRecipeTypeIds.typeIdFromRecipeId(recipeId));
        }
        for (String value : raw.get(Source.AUTHORITATIVE)) {
            add(ids, value);
        }

        final List<String> idList = List.copyOf(ids);
        final List<String> unverified = new ArrayList<>();
        for (String id : idList) {
            if (!ShanhaiRecipeTypeIds.exists(id)) {
                unverified.add(id);
            }
        }
        final Verdict verdict;
        if (idList.isEmpty()) {
            verdict = Verdict.NONE;
        } else if (idList.size() == 1) {
            verdict = Verdict.UNIQUE;
        } else {
            verdict = Verdict.AMBIGUOUS;
        }
        return new Resolution(verdict, idList, raw, List.copyOf(unverified));
    }

    /** 归一化后非空才加入。 */
    private static void add(Set<String> ids, @Nullable String rawValue) {
        final String canonical = ShanhaiRecipeTypeIds.canonical(rawValue);
        if (!canonical.isEmpty()) {
            ids.add(canonical);
        }
    }

    /**
     * ① gtlcore 的快速上传元数据。
     *
     * <p>🔴 走的是 gtlcore 的<b>公开</b>读法 {@code PatternQuickUploadMetadata.readRecipeTypeIds(ItemStack)}，
     * 不自己拼键名（那个类的常量池里 {@code ROOT_KEY="gtlcore"}、{@code RECIPE_TYPES_KEY="patternQuickUploadRecipeTypes"}，
     * 自己拼会在 gtlcore 改键时静默读空）。返回类型是 {@code Set<ResourceLocation>}（javap 实证）。
     */
    private static List<String> readQuickUpload(ItemStack stack) {
        try {
            final Set<ResourceLocation> types = PatternQuickUploadMetadata.readRecipeTypeIds(stack);
            if (types == null || types.isEmpty()) {
                return List.of();
            }
            final List<String> out = new ArrayList<>(types.size());
            for (ResourceLocation id : types) {
                if (id != null) {
                    out.add(id.toString());
                }
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            // gtlcore 缺席 / NBT 畸形 / 该 stack 不是样板 —— 一律当「这个来源没写东西」。
            return List.of();
        }
    }

    /**
     * 读一个根级键，接受 {@code String} 或 {@code ListTag<String>}（防御式）。
     *
     * <p>⚠️ 数字/布尔型值（④ 很可能是这种）<b>不产生类型值</b>，直接返回空表 ——
     * 「有这个标记」这件事由调用方的日志另行说明（{@link #hasAuthoritativeFlag(CompoundTag)}）。
     */
    private static List<String> readStringOrStringList(@Nullable CompoundTag tag, String key) {
        try {
            if (tag == null || !tag.contains(key)) {
                return List.of();
            }
            if (tag.contains(key, Tag.TAG_STRING)) {
                final String value = tag.getString(key);
                return value == null || value.isBlank() ? List.of() : List.of(value);
            }
            if (tag.contains(key, Tag.TAG_LIST)) {
                final ListTag list = tag.getList(key, Tag.TAG_STRING);
                final List<String> out = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    final String value = list.getString(i);
                    if (value != null && !value.isBlank()) {
                        out.add(value);
                    }
                }
                return List.copyOf(out);
            }
            return List.of();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * ④ 那个键是不是以「数字/布尔」形式存在的（即：只表达「权威」，没带类型 id）。
     *
     * <p>只给日志用：{@code authoritativeFlag=true} 这一项能解释「为什么 ④ 读到了却没有候选」。
     */
    public static boolean hasAuthoritativeFlag(@Nullable CompoundTag tag) {
        try {
            if (tag == null || !tag.contains(ShanhaiRecipeTypeIds.KEY_AUTHORITATIVE)) {
                return false;
            }
            return tag.contains(ShanhaiRecipeTypeIds.KEY_AUTHORITATIVE, Tag.TAG_BYTE)
                    || tag.contains(ShanhaiRecipeTypeIds.KEY_AUTHORITATIVE, Tag.TAG_INT)
                    || tag.contains(ShanhaiRecipeTypeIds.KEY_AUTHORITATIVE, Tag.TAG_LONG);
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ 阶段三：整机快照（③ 的对外口径）

    /**
     * 一个槽的读取结论（<b>不含</b> NBT 原文，只留判定与候选）。
     *
     * <p>存在的理由：机器要「把全机槽位聚合成一份类型集合」，而聚合逻辑必须是<b>纯函数</b>
     * 才能被实跑验证（探针 {@code temp\stellar-p1\probe3\SnapshotProbe.java}）。
     */
    public record SlotType(int apiSlot, @NotNull Verdict verdict, @NotNull List<String> ids) {}

    /**
     * 整机快照 —— ③ 的对外答案。
     *
     * <h2>🔴 三个不变量（阶段三的要求「零类型与歧义都不得静默当默认类型跑」就落在这）</h2>
     * <ol>
     *   <li>{@link #machineTypeIds()} <b>只</b>含「唯一采信」的类型：未标注（NONE）与歧义（AMBIGUOUS）
     *       <b>一律不进</b>集合 ⇒ 调用方拿不到一个「看起来像默认类型」的假值；</li>
     *   <li>未标注与歧义<b>分开记账</b>（{@link #untypedSlots()} / {@link #ambiguousSlots()}），
     *       并且各自带<b>槽号</b> ⇒ 出问题能一眼定位到格子，而不是只知道「有多少块」；</li>
     *   <li>{@link #slotToType()} <b>只</b>含有唯一类型的槽；查不到的槽（未标注/歧义）
     *       <b>不</b>回落到任何默认值 —— 用 {@link #slotToType()} 拿不到就等于「不知道」。</li>
     * </ol>
     */
    public record Snapshot(@NotNull List<String> machineTypeIds,
                           @NotNull Map<Integer, String> slotToType,
                           @NotNull List<Integer> untypedSlots,
                           @NotNull List<Integer> ambiguousSlots,
                           int slotCount) {

        /** 有唯一类型的槽数。 */
        public int typedCount() {
            return this.slotToType.size();
        }

        /** 未标注（NONE）的槽数。 */
        public int untypedCount() {
            return this.untypedSlots.size();
        }

        /** 歧义（AMBIGUOUS）的槽数。 */
        public int ambiguousCount() {
            return this.ambiguousSlots.size();
        }

        /** 一行式摘要（日志/命令用，格式稳定）。 */
        public @NotNull String describe() {
            return "slots=" + this.slotCount
                    + " typed=" + this.typedCount()
                    + " untyped=" + this.untypedCount()
                    + " ambiguous=" + this.ambiguousCount()
                    + " types=" + this.machineTypeIds
                    + " untypedAt=" + this.untypedSlots
                    + " ambiguousAt=" + this.ambiguousSlots;
        }
    }

    /**
     * 把「每个有样板的槽的读取结论」聚合成整机快照（<b>纯函数</b>，不读 NBT、不碰机器）。
     *
     * @param slots 只传<b>有样板</b>的槽（空槽不该出现在里面；空槽混进来会被算成「未标注」，那是假数据）
     */
    public static @NotNull Snapshot summarize(@NotNull List<SlotType> slots) {
        final java.util.LinkedHashSet<String> machineTypes = new java.util.LinkedHashSet<>();
        final Map<Integer, String> slotToType = new java.util.LinkedHashMap<>();
        final List<Integer> untyped = new ArrayList<>();
        final List<Integer> ambiguous = new ArrayList<>();
        for (SlotType slot : slots) {
            switch (slot.verdict()) {
                case UNIQUE -> {
                    final String id = slot.ids().isEmpty() ? "" : slot.ids().get(0);
                    if (id.isEmpty()) {
                        // 判定说唯一、却给不出 id ⇒ 这是上游传入的脏数据，按「未标注」记账，绝不编一个类型。
                        untyped.add(slot.apiSlot());
                    } else {
                        slotToType.put(slot.apiSlot(), id);
                        machineTypes.add(id);
                    }
                }
                case AMBIGUOUS -> ambiguous.add(slot.apiSlot());
                default -> untyped.add(slot.apiSlot());
            }
        }
        return new Snapshot(List.copyOf(machineTypes), Map.copyOf(slotToType),
                List.copyOf(untyped), List.copyOf(ambiguous), slots.size());
    }
}
