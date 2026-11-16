package com.dishanhai.gt_shanhai.common.patternmover;

import com.dishanhai.gt_shanhai.common.patternmover.compat.ShanhaiRecipeTypeIds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 物品 NBT 里<b>一条样板</b>（它就是 {@code shanhai:mover.patterns[]} 的一个元素）。
 *
 * <h2>一、存的是什么（🔴 存的是 NBT，不是 {@code ItemStack}）</h2>
 * <pre>
 *   { pattern: &lt;ItemStack.serializeNBT() 的原样拷贝&gt;, type: "&lt;gtceu:xxx&gt;" | "" }
 *   { …, circuit: &lt;1..32&gt; }   ← 🔴 第六刀：只有【星律通配符母槽】读出来的那条才带这个键
 * </pre>
 * <b>为什么存 NBT 而不存 {@code ItemStack}</b>：
 * <ol>
 *   <li>{@code ItemStack.of(tag)} 需要物品注册表就绪 ⇒ 存 ItemStack 的类在本机<b>跑不了离线断言</b>；
 *       而 {@code CompoundTag} 是纯数据类（本工程既有先例：{@code ShanhaiPatternTypeReader#resolveTagOnly}）</li>
 *   <li>{@code ItemStack} 是「活的容器对象」，第 9 条纪律（{@code temp/pattern-mover/取证.md} §3.2 要点 5）
 *       要求「{@code ItemStack.copy()} 必做」⇒ 直接存 {@code serializeNBT()} 的拷贝天然满足</li>
 * </ol>
 *
 * <h2>二、🔴 去重键（「同一配方 id，新的覆盖旧的」到底按什么判）</h2>
 * 逐条优先级（<b>只认这两级，报告里也这么写</b>）：
 * <table border="1">
 *   <tr><th>级</th><th>键</th><th>形状</th><th>为什么</th></tr>
 *   <tr><td>①</td><td>样板 NBT 里的 {@code gt_shanhai_recipe_id}</td><td>{@code recipe:<id>}</td>
 *       <td>这就是用户说的「配方 id」。键名<b>不重复写字面量</b>，取
 *           {@link ShanhaiRecipeTypeIds#KEY_RECIPE_ID}（编译期常量，内联，不触发类加载）</td></tr>
 *   <tr><td>②</td><td>样板 ItemStack 序列化字节的 SHA-256</td><td>{@code nbt:<hex>}</td>
 *       <td>取不到配方 id 时用「这一块样板是不是同一块」当身份 —— 逐字节相同才算同一个，<b>不会误合并</b></td></tr>
 * </table>
 * <p>⛔ <b>刻意<b>不</b>用作键的三样东西</b>（这是判断，理由写在这里，报告里也写了）：
 * <ul>
 *   <li><b>gtlcore 的「类型键」</b>（{@code gtlcore.patternQuickUploadRecipeTypes}）和
 *       <b>从输出反查到的类型目录</b>：它们给的是<b>配方类型</b>，而<b>一个类型下有很多块不同配方</b>
 *       ⇒ 拿类型当键会把它们合并成一块，那是<b>丢数据</b>，比不去重严重得多。</li>
 *   <li><b>输出指纹</b>（星律 {@code shanhaiPatternIdentity} 那种写法）：两个不同输入产出同一物品
 *       是常见情形（如不同等级的同一产物），按输出合并同样会丢块。</li>
 *   <li><b>电路号</b>（第六刀加的 {@code circuit}）：它挂在机器母槽上，非零配置也是搬运去重身份的一部分。
 *       若把它算进键，「同一块样板从两个不同电路的母槽各读一次」就会变成两条，
 *       放回去时同一格被写两遍（第二遍还要再改一次电路）。现有语义是<b>新的覆盖旧的</b>
 *       ⇒ 同配方的两次读取只留一条（电路 = <b>后读到</b>的那个），这是与「同 id 覆盖」同一条口径。</li>
 * </ul>
 * ⇒ ① 与 ② <b>都是纯 NBT 可算的</b> ⇒ 同一个物品存档读出来重算，键<b>不变</b>
 *   （这条很要紧：每次右键都从 NBT 重建整份数据，键若依赖运行期对象，跨次去重就会失效）。
 *
 * <h2>三、字节数</h2>
 * {@link #standaloneBytes()} = 这一条<b>独立</b>写成根复合标签时 {@code NbtIo.write} 的字节数
 * （真的写一遍，见 {@link HostPatternMoverBytes#writeSize}）。它在构造时算一次并缓存 ——
 * 一块样板几百字节，重复序列化没有意义。列表内的实际增量见
 * {@link HostPatternMoverBytes#listElementBytes}。
 *
 * <p>实测（42 块真实存档样板，{@code temp/pattern-mover/取证.md} §5.3）：
 * 单块 <b>481–1315 B，p50 909 B</b> ⇒ 1.5 MiB ≈ <b>1730 块</b>。
 * <p>🔴 第六刀：带电路的那一条比同内容的不带电路版本 <b>＋14 B</b>
 * （{@code circuit} 键＝ 1 类型 ＋ 2 名字长 ＋ 7 "circuit" ＋ 4 int），
 * 因为它就写在 {@link #entryTag()} 里、跟着真的 {@code NbtIo.write} 走 —— <b>自动进闸</b>。
 */
public final class HostPatternMoverEntry {

    /** 一条样板里「样板本体」的键名。 */
    public static final String K_PATTERN = "pattern";

    /** 一条样板里的「配方类型 id」键名（{@code ""} = 类型未识别）。 */
    public static final String K_TYPE = "type";

    /**
     * 一条样板里的「**通配符母槽的编程电路**」键名（第六刀）。
     *
     * <p>⚠️ 键名<b>不写字面量</b>：与 {@link HostPatternMoverCircuit#K_CIRCUIT} 同一个常量。
     * 语义/值域/为什么"0 不写键"全部见 {@link HostPatternMoverCircuit} 的类注释。
     */
    public static final String K_CIRCUIT = HostPatternMoverCircuit.K_CIRCUIT;

    /** 类型未识别组在面板上的显示名（lang 键；见 {@code assets/shanhai/lang/zh_cn.json}）。 */
    public static final String LANG_UNKNOWN_TYPE = "shanhai.mover.type.unknown";

    /**
     * 配方 id 的 NBT 键（🔴 <b>不写字面量</b>：与星律/通配符读同一处常量）。
     *
     * <p>{@link ShanhaiRecipeTypeIds#KEY_RECIPE_ID} 是 {@code static final String} 编译期常量
     * ⇒ javac 会把字面量<b>内联</b>进本类，运行时<b>不会</b>去加载 {@code ShanhaiRecipeTypeIds}
     * （那个类引了 GTCEu / Component，离线断言里加载不了）—— 这一点由
     * {@code temp/pattern-mover/probe} 的离线运行证明（它跑的 classpath 里没有 GTCEu）。
     */
    public static final String KEY_RECIPE_ID = ShanhaiRecipeTypeIds.KEY_RECIPE_ID;

    private final CompoundTag pattern;
    private final String type;
    private final int circuit;
    private final String dedupKey;
    private final int standaloneBytes;

    private HostPatternMoverEntry(CompoundTag pattern, String type, int circuit, String dedupKey,
                                  int standaloneBytes) {
        this.pattern = pattern;
        this.type = type;
        this.circuit = circuit;
        this.dedupKey = dedupKey;
        this.standaloneBytes = standaloneBytes;
    }

    /**
     * 建一条（<b>不带</b>电路 —— 老调用点走这条，逐位不变）。
     *
     * @param patternNbt 样板物品的 {@code ItemStack.serializeNBT()}（本方法会<b>再拷一份</b>，调用方不必自己拷）
     * @param type       配方类型 id；{@code null}/空白 ⇒ {@code ""}（= 类型未识别）
     */
    public static HostPatternMoverEntry of(CompoundTag patternNbt, String type) {
        return of(patternNbt, type, HostPatternMoverCircuit.NONE);
    }

    /**
     * 建一条（可带<b>通配符母槽的编程电路</b>，第六刀）。
     *
     * <p>🔴 字节数<b>就是</b>这个方法的产物：{@code entryTag()}（可能含 {@code circuit} 键）
     * 真的 {@code NbtIo.write} 一遍 ⇒ 电路那 14 B <b>自动进</b> 1.5 MiB 闸，不需要另建算法。
     * 只有 {@code circuit != 0} 才会写那个键（见 {@link HostPatternMoverCircuit#putToTag}）。
     *
     * @param circuit 1..32 的电路号；{@link HostPatternMoverCircuit#NONE}（0）= 不设置 ⇒ <b>不写键</b>
     */
    public static HostPatternMoverEntry of(CompoundTag patternNbt, String type, int circuit) {
        final CompoundTag copy = patternNbt == null ? new CompoundTag() : patternNbt.copy();
        final String t = type == null ? "" : type.trim();
        final int c = HostPatternMoverCircuit.normalize(circuit);
        final String key = keyWithCircuit(keyOf(copy), c);
        final CompoundTag entryTag = new CompoundTag();
        entryTag.put(K_PATTERN, copy.copy());
        entryTag.putString(K_TYPE, t);
        HostPatternMoverCircuit.putToTag(entryTag, c);
        return new HostPatternMoverEntry(copy, t, c, key, HostPatternMoverBytes.writeSize(entryTag));
    }

    /**
     * 从物品 NBT 里读回一条（{@link #entryTag()} 的逆）。
     *
     * <p>读回来时<b>重新算</b>键与字节数，<b>不信任</b>任何存档里的缓存值 —— 手改过的 NBT /
     * 上游改了 {@code serializeNBT} 形状，都能被这一条兜住。
     * <p>🔴 老数据<b>没有</b> {@code circuit} 键 ⇒ 读成 {@link HostPatternMoverCircuit#NONE}（= 0），
     * 且字节数<b>不多一分</b>（向后兼容）。
     */
    public static HostPatternMoverEntry fromTag(CompoundTag entryTag) {
        final CompoundTag pattern = entryTag != null && entryTag.contains(K_PATTERN, Tag.TAG_COMPOUND)
                ? entryTag.getCompound(K_PATTERN).copy() : new CompoundTag();
        final String type = entryTag == null ? "" : entryTag.getString(K_TYPE);
        final int circuit = HostPatternMoverCircuit.ofTag(entryTag);
        return of(pattern, type, circuit);
    }

    // ------------------------------------------------------------------ 读

    /** 样板本体的拷贝（每次都给新对象，别让人改到我们内部那份）。 */
    public CompoundTag patternNbt() {
        return this.pattern.copy();
    }

    /** 配方类型 id；{@code ""} = 类型未识别。 */
    public String type() {
        return this.type;
    }

    /**
     * 这条从<b>星律通配符母槽</b>读出来的编程电路（1..32）；{@link HostPatternMoverCircuit#NONE} = 不设置。
     *
     * <p>非母槽 / 非星律总成读出来的条目<b>恒</b> {@code NONE}（放置时一个字都不写）。
     */
    public int circuit() {
        return this.circuit;
    }

    /** 这条带不带电路（{@code circuit != 0}）。 */
    public boolean hasCircuit() {
        return this.circuit != HostPatternMoverCircuit.NONE;
    }

    /** 去重键（见类注释 §二）。 */
    /** Zero preserves legacy identity; configured variants must never overwrite one another. */
    public static String keyWithCircuit(String key, int circuit) {
        int value = HostPatternMoverCircuit.normalize(circuit);
        return key == null || value == 0 ? key : key + "|circuit=" + value;
    }

    public String dedupKey() {
        return this.dedupKey;
    }

    /** 这一条独立序列化的字节数（实算，构造时算好）。 */
    public int standaloneBytes() {
        return this.standaloneBytes;
    }

    /** 这一条作为 ListTag 元素占的字节数（= {@link #standaloneBytes} - 3）。 */
    public int listElementBytes() {
        return HostPatternMoverBytes.listElementBytes(this.standaloneBytes);
    }

    /** 写进 {@code patterns} 列表的那个复合标签（{@code circuit != 0} 时才带 {@code circuit} 键）。 */
    public CompoundTag entryTag() {
        final CompoundTag entryTag = new CompoundTag();
        entryTag.put(K_PATTERN, this.pattern.copy());
        entryTag.putString(K_TYPE, this.type);
        HostPatternMoverCircuit.putToTag(entryTag, this.circuit);
        return entryTag;
    }

    @Override
    public String toString() {
        return "Entry{key=" + this.dedupKey + ", type='" + this.type + "', bytes=" + this.standaloneBytes
                + (this.hasCircuit() ? ", circuit=" + this.circuit : "")
                + ", item=" + this.pattern.getString("id") + "}";
    }

    // ------------------------------------------------------------------ 键

    /**
     * 去重键（priority 见类注释 §二）。
     *
     * <p>{@code gt_shanhai_recipe_id} 在<b>样板自己的 NBT 根</b>，而 {@code ItemStack.serializeNBT()}
     * 把它放在 {@code tag} 子复合里 ⇒ 先读 {@code tag}，读不到再兜底读外层根
     * （两种形状都真实存在过：AE 的 {@code GenericStack} 形态把 {@code tag} 拆出来了）。
     */
    static String keyOf(CompoundTag patternNbt) {
        final String rid = recipeIdOf(patternNbt);
        if (!rid.isEmpty()) {
            return "recipe:" + rid;
        }
        return "nbt:" + sha256Hex(canonical(patternNbt));
    }

    /** 读配方 id（读不到 ⇒ {@code ""}）。 */
    static String recipeIdOf(CompoundTag patternNbt) {
        if (patternNbt == null) {
            return "";
        }
        String rid = readId(patternNbt.contains("tag", Tag.TAG_COMPOUND)
                ? patternNbt.getCompound("tag") : null);
        if (rid.isEmpty()) {
            rid = readId(patternNbt);
        }
        return rid;
    }

    private static String readId(CompoundTag tag) {
        if (tag == null || !tag.contains(KEY_RECIPE_ID, Tag.TAG_STRING)) {
            return "";
        }
        final String v = tag.getString(KEY_RECIPE_ID);
        return v == null ? "" : v.trim();
    }

    /**
     * 把一个 NBT 标签渲染成<b>规范形式</b>（同内容 ⇒ 同串 ⇒ 同散列）。
     *
     * <p>为什么要自己渲染、不直接拿 {@code toString()} 或序列化字节：
     * <ul>
     *   <li>{@code CompoundTag} 内部是 {@code HashMap}，{@code toString()} 的键序<b>不是约定</b>；</li>
     *   <li>所以本方法<b>先按键名排序</b>再渲染，并对每个叶子带<b>类型 id 与长度前缀</b>
     *       （否则 {@code "1,2"} 这种字符串会和「两个元素」的列表撞形）。</li>
     * </ul>
     */
    static String canonical(CompoundTag tag) {
        final StringBuilder sb = new StringBuilder(256);
        render(sb, tag);
        return sb.toString();
    }

    private static void render(StringBuilder sb, Tag tag) {
        if (tag == null) {
            sb.append("<null>");
            return;
        }
        final byte id = tag.getId();
        if (tag instanceof CompoundTag c) {
            final List<String> keys = new ArrayList<>(c.getAllKeys());
            Collections.sort(keys);
            sb.append('{');
            for (String k : keys) {
                sb.append(k.length()).append(':').append(k).append('=');
                render(sb, c.get(k));
                sb.append(',');
            }
            sb.append('}');
        } else if (tag instanceof ListTag list) {
            sb.append('[').append(id).append(':');
            for (Tag e : list) {
                render(sb, e);
                sb.append(',');
            }
            sb.append(']');
        } else {
            final String s = tag.getAsString();
            sb.append(id).append(':').append(s.length()).append(':').append(s);
        }
    }

    /** SHA-256 十六进制（{@code MessageDigest} 是 JDK 标准库，不需要第三方）。 */
    static String sha256Hex(String text) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法；真走到这里说明 JVM 被裁过。退化成一个不会撞的串。
            return "nohash:" + text.length();
        }
    }
}
