package com.shanhai.common.saveedit;

import net.minecraft.nbt.CompoundTag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 第 ④ 页（实体）与其覆盖层的 <b>"纯 JDK 那一半"</b>：16 个输入项的表、覆盖层的状态机、
 * 预演（不写盘）、带闸的写 / 删、以及所有要显示的字。
 *
 * <h2>一、为什么要把这一半单独拎出来</h2>
 * 与 {@link ShanhaiSaveEditLayout} 同一个理由（见那个类的注释 §1.2）：
 * {@code ShanhaiSaveEditPanel} 继承 LDLib 的 {@code WidgetGroup}，<b>离线加载不了</b>
 * （一初始化就触发 {@code IConfigurableWidget.<clinit>}，要一个本机 mapped forge jar 里没有的
 * SRG 方法）。而"打开 → 编辑 → 关闭"这条状态机、以及"删除预期那几个字"必须能<b>离线判</b>
 * —— 所以逻辑在这一层，控件那一层（{@code ShanhaiSaveEditEntityOverlay}）只负责画。
 *
 * <h2>二、16 个输入框 ↔ {@link ShanhaiEntityEdit.Patch} 的 10 项</h2>
 * <pre>
 *   输入项（左列 0~7）       输入项（右列 8~15）      ⇒ Patch 字段（null = 这一项不动）
 *   Pos.x / Pos.y / Pos.z    yaw / pitch              pos / rotation
 *   vx / vy / vz             血量                      motion / health
 *   NoAI                     物品id / 物品数            noAi / itemId + itemCount
 *   无敌                     名字                      invulnerable / customName
 *                            名可见 / 持久              customNameVisible / persistenceRequired
 * </pre>
 * 🔴 <b>预填 = 现值；与预填值相同就不写</b>（用户原话：「我要修改实体时，它没有自动把这些填好」）：
 * 详情读出来之后这 16 个框会被填成<b>盘上现在的值</b>（{@link #prefillOf}），玩家只改他要改的那几项 ——
 * {@link Model#patch()} 按<b>项</b>与预填值比对，没动过的项不进请求 ⇒ 预填不会顺手把整条实体覆盖掉。
 * 读不到 / 类型不对 / 形状不对的项<b>留空</b>（绝不填 0、绝不填 "null"、绝不编一个值）。
 * 成组的三项（Pos / Motion / Rotation）必须一起填，否则 {@link PatchBuilder} 直接报错
 * —— 半组坐标没有任何合法语义。
 *
 * <h2>三、为什么不在这里做语义校验</h2>
 * "Pos 必须落在实体自己的区块里""pitch 要在 [-90,90]""Health 的 NBT 宽度是 short 还是 float"
 * 这些<b>只有数据层知道</b>，本类一条都不重复实现（重复就一定有一天两边不一致）。
 * 本类只做两件事：① 数字能不能解析；② 用
 * {@link ShanhaiEntityEdit#applyPatch(CompoundTag, ShanhaiEntityEdit.Patch,
 * ShanhaiEntityEdit.Hit, List, List, List)} 在一份<b>从盘上重新解出来、永不写回</b>的 NBT 上
 * <b>预演</b>一次 —— 于是"点写入之前就能看到 dangers"与"数据层的拒绝理由原样显示"同时成立。
 *
 * <h2>四、删除预期为什么是一段"算出来的字"</h2>
 * 用户点名要的是「删除前把预期显示出来，例如"该区块实体 4 → 3"」。所以
 * {@link #deleteExpectation(String, int, int, int, int)} 是<b>唯一</b>的那段字：
 * 删除前用"盘上现在几条 → 少一条"，删除后用
 * {@link ShanhaiEntityEdit.WriteOutcome#chunkEntitiesBefore()} / {@code chunkEntitiesAfter()}
 * 回读出来的<b>真读数</b>再调它一次。同一段字只有一个来源 ⇒ 判据可以拿 (4,3) 和 (7,6) 两组
 * 打它（写死的字通不过）。
 */
public final class ShanhaiSaveEditEntityUi {

    /** 日志/闸门上记的"谁在请求"（进 {@link ShanhaiSaveEditFlow.ApplyAttempt}）。 */
    public static final String WHO_WRITE = "panel:entity-write";
    public static final String WHO_DELETE = "panel:entity-delete";

    private ShanhaiSaveEditEntityUi() {}

    // ================================================================== 16 个输入项

    /** 一个输入格子：{@code key} 稳定（判据用它），{@code label} 上屏，{@code hint} 进 tooltip。 */
    public record Input(String key, String label, String hint) {}

    public static final String K_POS_X = "pos.x";
    public static final String K_POS_Y = "pos.y";
    public static final String K_POS_Z = "pos.z";
    public static final String K_VX = "mot.vx";
    public static final String K_VY = "mot.vy";
    public static final String K_VZ = "mot.vz";
    public static final String K_NOAI = "noai";
    public static final String K_INVULNERABLE = "invulnerable";
    public static final String K_YAW = "rot.yaw";
    public static final String K_PITCH = "rot.pitch";
    public static final String K_HEALTH = "health";
    public static final String K_ITEM_ID = "item.id";
    public static final String K_ITEM_COUNT = "item.count";
    public static final String K_NAME = "name";
    public static final String K_NAME_VISIBLE = "name.visible";
    public static final String K_PERSISTENT = "persistent";

    /**
     * 16 个输入项，<b>顺序就是版式</b>：下标 0~7 在左列（自上而下），8~15 在右列。
     * 覆盖层的槽位表（{@link ShanhaiSaveEditLayout#entityOverlaySlots()}）用的就是这套下标。
     */
    private static final List<Input> INPUTS = List.of(
            new Input(K_POS_X, "位置X", "坐标 x（Pos.x）。预填的是现值，不动它就不写；位置三项要一起填（x/z 必须落在实体自己的区块里，越界会被拒绝）"),
            new Input(K_POS_Y, "位置Y", "坐标 y（Pos.y）。预填的是现值，不动它就不写；位置三项要一起填"),
            new Input(K_POS_Z, "位置Z", "坐标 z（Pos.z）。预填的是现值，不动它就不写；位置三项要一起填"),
            new Input(K_VX, "运动X", "速度 x（Motion[0]）。预填的是现值，不动它就不写；运动三项要一起填"),
            new Input(K_VY, "运动Y", "速度 y（Motion[1]）。预填的是现值，不动它就不写；运动三项要一起填"),
            new Input(K_VZ, "运动Z", "速度 z（Motion[2]）。预填的是现值，不动它就不写；运动三项要一起填"),
            new Input(K_NOAI, "关闭AI", "填 是/否（也认 1/0、true/false）。预填 1/0 = 现在就是开/关，不动它就不写"),
            new Input(K_INVULNERABLE, "无敌", "填 是/否（也认 1/0、true/false）。预填 1/0 = 现在就是开/关，不动它就不写"),
            new Input(K_YAW, "朝向yaw", "朝向 yaw（Rotation[0]）。预填的是现值，不动它就不写；朝向两项要一起填"),
            new Input(K_PITCH, "俯仰pitch", "俯仰 pitch（Rotation[1]）。预填的是现值，不动它就不写；朝向两项要一起填（范围 [-90,90] 由数据层判）"),
            new Input(K_HEALTH, "血量", "预填的是现值，不动它就不写；上限跟着实体现有的 max_health（数据层会钳，并如实回显钳到多少）"),
            new Input(K_ITEM_ID, "物品id", "只有掉落物（minecraft:item）才有物品键；写成 namespace:path；预填的是现值，不动它就不写"),
            new Input(K_ITEM_COUNT, "物品数", "≥ 1；NBT 宽度跟着现有的 Count tag（byte/short/int）；预填的是现值，不动它就不写"),
            new Input(K_NAME, "自定义名", "JSON 文本组件，例如 {\"text\":\"名字\"}；预填的是现值原样（不是显示名），不动它就不写"),
            new Input(K_NAME_VISIBLE, "名字可见", "填 是/否。预填 1/0 = 现在就是开/关，不动它就不写"),
            new Input(K_PERSISTENT, "不刷掉", "填 是/否。预填 1/0 = 现在就是开/关，不动它就不写；是 ⇒ 这条实体不会被刷掉（PersistenceRequired）"));

    /** 16 个输入项（不可变）。 */
    public static List<Input> inputs() {
        return INPUTS;
    }

    /**
     * 第 {@code index} 个框最多能吃多少字（覆盖层建控件时就按它设 {@code maxStringLength}）。
     *
     * <p>🔴 为什么这份上限放在这一层：{@link #prefillOf} 必须知道<b>同一个</b>上限 —— 现值比框还长时
     * 只能<b>留空</b>（既不截断预填，也不假装它是可编辑的）。而 LDLib 的
     * {@code TextFieldWidget.handleClientAction} 会把客户端送来的串<b>截到 maxStringLength</b>，
     * 所以"预填了一个比框长的值"会让"没动过的框"与原始值对不上，改别处时顺手写出一个被截断的名字。
     */
    public static int maxChars(int index) {
        final String key = index < 0 || index >= INPUTS.size() ? "" : INPUTS.get(index).key();
        switch (key) {
            case K_NAME:
                return 512;
            case K_ITEM_ID:
                return 96;
            case K_ITEM_COUNT:
                return 12;
            case K_NAME_VISIBLE:
            case K_NOAI:
            case K_INVULNERABLE:
            case K_PERSISTENT:
                return 8;
            case K_HEALTH:
                return 20;
            default:
                return 32;
        }
    }

    // ================================================================== 预填（16 个框的现值）

    /**
     * 🔴 <b>16 个框在上屏时要预填的字</b>（下标同 {@link #inputs()}；空串 = 这一项没有可用的预填值）。
     *
     * <p>用户原话：「我要修改实体时，它没有自动把这些填好」。所以详情一读出来就把<b>盘上的现值</b>
     * 填进对应的框：玩家看到"现在是什么"，只改他要改的那几项。
     *
     * <p>🔴 三条硬规矩：
     * <ol>
     *   <li>填进去的必须是<b>能原样写回去</b>的形态（数字就是数字、开关是 1/0、物品 id 是
     *       {@code namespace:path}）—— 所以<b>不</b>直接把人看的读数串（{@code "x=1.5 y=64.0"}、
     *       {@code "1（是）"}）当预填值，否则 {@link Model#patch()} 连解析都过不去；</li>
     *   <li><b>读不到 / 键不存在 / 类型不对 / 形状不对 ⇒ 留空</b>（绝不填 0、绝不填 "null"、
     *       绝不编一个值）：那些项在读数行里仍然带 ⚠ 显示，只是不能靠预填"顺手写回去"；</li>
     *   <li>成组的三项要么都有、要么都空（半组坐标没有合法语义）。</li>
     * </ol>
     */
    public static String[] prefillOf(ShanhaiEntityEdit.Detail d) {
        final String[] out = new String[INPUTS.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = "";
        }
        if (d == null || !d.ok()) {
            return out;         // 读不出来 ⇒ 16 个框全空（上一条实体的残留绝不许带进来）
        }
        components(out, new String[] {K_POS_X, K_POS_Y, K_POS_Z}, d, "Pos", new String[] {"x=", "y=", "z="});
        components(out, new String[] {K_VX, K_VY, K_VZ}, d, "Motion", new String[] {"vx=", "vy=", "vz="});
        components(out, new String[] {K_YAW, K_PITCH}, d, "Rotation", new String[] {"yaw=", "pitch="});
        put(out, K_NOAI, flagPrefill(d, "NoAI"));
        put(out, K_INVULNERABLE, flagPrefill(d, "Invulnerable"));
        put(out, K_HEALTH, numberPrefill(d, "Health"));
        put(out, K_ITEM_ID, itemToken(d, "id="));
        put(out, K_ITEM_COUNT, itemCountPrefill(d));
        put(out, K_NAME, textPrefill(d, "CustomName"));
        put(out, K_NAME_VISIBLE, flagPrefill(d, "CustomNameVisible"));
        put(out, K_PERSISTENT, flagPrefill(d, "PersistenceRequired"));
        // 🔴 现值比框还长的项：留空（见 maxChars 的注释 —— 截断预填会写出被截断的值）
        for (int i = 0; i < out.length; i++) {
            if (out[i].length() > maxChars(i)) {
                out[i] = "";
            }
        }
        return out;
    }

    private static void put(String[] out, String key, String value) {
        final int i = indexOf(key);
        if (i >= 0) {
            out[i] = value == null ? "" : value;
        }
    }

    /** key → 在 {@link #INPUTS} 里的下标（没有 ⇒ -1）。 */
    private static int indexOf(String key) {
        for (int i = 0; i < INPUTS.size(); i++) {
            if (INPUTS.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 能写回去的那一项读数（{@code null} = 不要预填）。
     *
     * <p>为什么看 note 是否以 ⚠ 开头：那种项是"键在、但类型/形状不对，写回去会被数据层拒绝"
     * （见 {@link ShanhaiEntityEdit.Field#note()}）⇒ 预填它就等于把它伪装成可编辑。
     */
    private static ShanhaiEntityEdit.Field editable(ShanhaiEntityEdit.Detail d, String key) {
        final ShanhaiEntityEdit.Field f = d.field(key);
        if (f == null || !f.present() || f.value() == null) {
            return null;
        }
        final String note = f.note() == null ? "" : f.note();
        if (note.startsWith("⚠")) {
            return null;
        }
        final String v = f.value().trim();
        return v.isEmpty() || "-".equals(v) ? null : f;
    }

    /**
     * 从读数串里取一组分量填进几个框：{@code "x=1.5 y=64.0 z=2.5"} + 前缀 {@code {"x=","y=","z="}}。
     *
     * <p>严格：前缀必须一个不差、不许有多余分量、每段都要能解析成数字；任何一项不满足 ⇒
     * <b>整组留空</b>（半组坐标没有合法语义，预填半组只会让玩家一点写入就被拒）。
     */
    private static void components(String[] out, String[] keys, ShanhaiEntityEdit.Detail d,
                                   String fieldKey, String[] prefixes) {
        final ShanhaiEntityEdit.Field f = editable(d, fieldKey);
        if (f == null || keys.length != prefixes.length) {
            return;
        }
        final String[] tokens = f.value().trim().split("\\s+");
        if (tokens.length != prefixes.length) {
            return;
        }
        final String[] got = new String[prefixes.length];
        for (String token : tokens) {
            int at = -1;
            for (int k = 0; k < prefixes.length; k++) {
                if (token.startsWith(prefixes[k])) {
                    at = k;
                    break;
                }
            }
            if (at < 0 || got[at] != null) {
                return;         // 多余分量 / 同一个分量出现两次 ⇒ 不是我们认识的形状
            }
            final String num = token.substring(prefixes[at].length());
            if (!isNumber(num)) {
                return;
            }
            got[at] = num;
        }
        for (String key : keys) {
            if (indexOf(key) < 0) {
                return;
            }
        }
        for (int k = 0; k < got.length; k++) {
            if (got[k] == null) {
                return;
            }
        }
        for (int k = 0; k < keys.length; k++) {
            out[indexOf(keys[k])] = got[k];
        }
    }

    /** 4 个开关的预填：值 = {@code "1"} / {@code "0"}；读不到或类型不对 ⇒ {@code ""}（不填 0）。 */
    private static String flagPrefill(ShanhaiEntityEdit.Detail d, String key) {
        final ShanhaiEntityEdit.Field f = editable(d, key);
        if (f == null) {
            return "";
        }
        final String v = f.value().trim();
        if (v.startsWith("1")) {
            return "1";
        }
        if (v.startsWith("0")) {
            return "0";
        }
        return "";
    }

    /** 数字项（血量）的预填：读数本身就是数字串；读不到 / 不是数字 ⇒ {@code ""}。 */
    private static String numberPrefill(ShanhaiEntityEdit.Detail d, String key) {
        final ShanhaiEntityEdit.Field f = editable(d, key);
        if (f == null) {
            return "";
        }
        final String v = f.value().trim();
        return isNumber(v) ? v : "";
    }

    /** 文本项（自定义名）的预填：<b>原样</b>给回去（它是 JSON 文本组件，不是显示名）。 */
    private static String textPrefill(ShanhaiEntityEdit.Detail d, String key) {
        final ShanhaiEntityEdit.Field f = editable(d, key);
        return f == null ? "" : f.value();
    }

    /**
     * {@code Item} 读数（{@code "id=minecraft:stone Count=3"}）里取一段。
     * {@code "id=(无 id)"} / {@code "Count=(无 Count)"} / 根本不是 compound（读数是 {@code "-"}）⇒ {@code ""}。
     */
    private static String itemToken(ShanhaiEntityEdit.Detail d, String prefix) {
        final ShanhaiEntityEdit.Field f = d.field("Item");
        if (f == null || !f.present() || f.value() == null) {
            return "";
        }
        for (String token : f.value().trim().split("\\s+")) {
            if (token.startsWith(prefix)) {
                final String got = token.substring(prefix.length()).trim();
                return got.isEmpty() || got.startsWith("(") ? "" : got;
            }
        }
        return "";
    }

    /** 物品数的预填：只认 {@code "Count=<整数>"}；没有 / 不是整数 ⇒ {@code ""}。 */
    private static String itemCountPrefill(ShanhaiEntityEdit.Detail d) {
        final String raw = itemToken(d, "Count=");
        if (raw.isEmpty()) {
            return "";
        }
        try {
            return String.valueOf(Integer.parseInt(raw));
        } catch (NumberFormatException nfe) {
            return "";          // 宽度怪（不是 byte/short/int）⇒ 不预填，别装作能改
        }
    }

    /** 一个号能不能解析成 double（预填只认这种形态：它能原样写回去）。 */
    private static boolean isNumber(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(text.trim());
            return true;
        } catch (NumberFormatException nfe) {
            return false;
        }
    }

    // ================================================================== 纯文本（判据直接打这些）

    /** 维度显示名（去掉 {@code minecraft:}）。 */
    public static String shortDim(String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            return "?";
        }
        final int colon = dimension.indexOf(':');
        return colon < 0 || colon == dimension.length() - 1 ? dimension : dimension.substring(colon + 1);
    }

    /**
     * 🔴 <b>删除预期的唯一那段字</b>（删之前 / 删之后都走它）。
     *
     * @param before 这个区块现在有几条实体（{@code -1} = 算不出来，如实说，<b>不编数字</b>）
     * @param after  删完还剩几条
     */
    public static String deleteExpectation(String dimension, int chunkX, int chunkZ, int before, int after) {
        if (before < 0 || after < 0) {
            return "§8删除预期：算不出这个区块现在有几条实体（先点「列出」再点这一行）";
        }
        return "§c删除预期：该区块实体 §f" + before + " §7→ §f" + after
                + " §8(区块 " + chunkX + "," + chunkZ + " · " + shortDim(dimension) + ")";
    }

    /** 同 {@link #deleteExpectation(String, int, int, int, int)}，但读数直接取一次真实删除的 {@link ShanhaiEntityEdit.WriteOutcome}。 */
    public static String deleteExpectation(String dimension, ShanhaiEntityEdit.WriteOutcome outcome) {
        if (outcome == null) {
            return deleteExpectation(dimension, 0, 0, -1, -1);
        }
        return deleteExpectation(dimension, outcome.chunkX(), outcome.chunkZ(),
                outcome.chunkEntitiesBefore(), outcome.chunkEntitiesAfter());
    }

    /** 一次写字段的结果（一行，红/绿）。 */
    public static String writeResultLine(ShanhaiEntityEdit.WriteOutcome o) {
        if (o == null) {
            return "§c没有结果（数据层没返回）";
        }
        if (!o.ok()) {
            return "§c没写进去：" + (o.error() == null || o.error().isEmpty() ? "（没给原因）" : o.error());
        }
        return "§a已写 §f" + o.changed() + " §8· chunk 实体 "
                + o.chunkEntitiesBefore() + " → " + o.chunkEntitiesAfter();
    }

    /** 一次按 UUID 删除的结果（一行；区块实体数由读数算，不写死）。 */
    public static String deleteResultLine(String dimension, ShanhaiEntityEdit.WriteOutcome o) {
        if (o == null) {
            return "§c没有结果（数据层没返回）";
        }
        if (!o.ok()) {
            return "§c没删：" + (o.error() == null || o.error().isEmpty() ? "（没给原因）" : o.error());
        }
        return "§a已删 §f" + o.id() + " §8· " + deleteExpectation(dimension, o);
    }

    // ================================================================== 类型名（🔴 中文在前）

    /**
     * "把注册名翻成当前语言的名字"这个能力（由 UI 层注入）。
     *
     * <p>为什么要抽成接口：数据层只准 import {@code java.*}（它给的
     * {@link ShanhaiEntityEdit.Row#id()} 就是<b>纯注册名字符串</b>），所以本地化只能在这一层做；
     * 而离线判据需要一个"我喂什么就返回什么"的假实现来单测回退分支，
     * 面板需要一个真实现（{@link #REGISTRY_NAMER}）。两者都实现这个接口。
     */
    public interface Namer {
        /** 这个类型在注册表里存在吗（{@code false} ⇒ 模组被删/写错了）。 */
        boolean registered(String typeId);

        /** 当前语言下的名字；<b>取不到 ⇒ {@code null}</b>（不许返回空串、也不许返回翻译键）。 */
        String entityName(String typeId);
    }

    /**
     * 🔴 真·注册表查询：{@code BuiltInRegistries.ENTITY_TYPE} → {@code getDescription().getString()}。
     *
     * <p>两条踩过的坑，都写死在这里：
     * <ol>
     *   <li><b>{@code get()} 对未知 id 不返回 {@code null}</b>：它返回注册表的<b>默认元素</b>
     *       （实测 {@code BuiltInRegistries.ENTITY_TYPE.get("shanhai:nope")} 拿到的是
     *       {@code minecraft:pig}）——所以必须先 {@code containsKey}，否则模组被删的实体会
     *       被显示成「猪」；</li>
     *   <li><b>服务端没有语言表</b>：{@code getString()} 会原样返回翻译键
     *       （{@code entity.minecraft.xxx}）⇒ 那种情况按"没译名"处理，不许当成名字用。</li>
     * </ol>
     */
    public static boolean entityRegistered(String typeId) {
        final net.minecraft.resources.ResourceLocation rl = parseTypeId(typeId);
        return rl != null && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
    }

    /** 同 {@link #entityRegistered}，但取名字；取不到（未注册 / 没译名 / 空）⇒ {@code null}。 */
    public static String entityNameOrNull(String typeId) {
        final net.minecraft.resources.ResourceLocation rl = parseTypeId(typeId);
        if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
            return null;
        }
        try {
            final net.minecraft.world.entity.EntityType<?> type =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(rl);
            if (type == null) {
                return null;
            }
            final String name = type.getDescription().getString();
            if (name == null || name.isEmpty()) {
                return null;
            }
            // 服务端没有语言表 ⇒ 拿到的是翻译键本身（entity.minecraft.xxx）
            if (name.startsWith("entity.") || name.equals(typeId)) {
                return null;
            }
            return name;
        } catch (Throwable t) {
            return null;            // 注册表没起来（离线判据）/ 其它异常：一律当"没名字"，不崩
        }
    }

    /** 面板用的真实现（客户端调才有中文；服务端调只会得到"没译名"）。 */
    public static final Namer REGISTRY_NAMER = new Namer() {
        @Override
        public boolean registered(String typeId) {
            return entityRegistered(typeId);
        }

        @Override
        public String entityName(String typeId) {
            return entityNameOrNull(typeId);
        }
    };

    private static net.minecraft.resources.ResourceLocation parseTypeId(String typeId) {
        if (typeId == null) {
            return null;
        }
        final String s = typeId.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return net.minecraft.resources.ResourceLocation.tryParse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * <b>中文在前的类型名</b>：{@code 运输矿车（minecraft:chest_minecart）}。
     *
     * <p>查不到就回退（<b>绝不空白、绝不崩</b>）：未注册 ⇒ {@code chest_minecart〔未注册〕}；
     * 注册了但没译名 ⇒ {@code chest_minecart〔无译名〕}。
     */
    public static String typeLabel(String typeId, Namer namer) {
        final String id = typeId == null ? "" : typeId.trim();
        if (id.isEmpty()) {
            return "〔没有类型〕";
        }
        if (namer == null || !namer.registered(id)) {
            return id + "〔未注册〕";
        }
        final String name = namer.entityName(id);
        if (name == null || name.isEmpty()) {
            return id + "〔无译名〕";
        }
        return name + "（" + id + "）";
    }

    /** 只要名字那半（行按钮用，省宽度）；回退形态与 {@link #typeLabel} 逐字相同。 */
    public static String typeShort(String typeId, Namer namer) {
        final String id = typeId == null ? "" : typeId.trim();
        if (id.isEmpty()) {
            return "〔没有类型〕";
        }
        if (namer == null || !namer.registered(id)) {
            return id + "〔未注册〕";
        }
        final String name = namer.entityName(id);
        if (name == null || name.isEmpty()) {
            return id + "〔无译名〕";
        }
        return name;
    }

    // ================================================================== 翻页（纯下标运算）

    /** 一共几页（至少 1 页；{@code matched} 是过滤后的条数）。 */
    public static int pageCount(int matched, int perPage) {
        final int per = Math.max(1, perPage);
        return matched <= 0 ? 1 : (matched + per - 1) / per;
    }

    /** 把页号夹进 {@code [0, pageCount-1]}。 */
    public static int clampPage(int page, int matched, int perPage) {
        return Math.max(0, Math.min(pageCount(matched, perPage) - 1, page));
    }

    /**
     * 为了显示第 {@code page} 页，{@code listDimension} 至少要取多少条。
     *
     * <p>数据层只支持"取前 N 条"（没有 offset 参数），所以翻页的做法是：
     * <b>取前 (page+1)×每页 条，再切最后一段</b> —— 这样既不用改数据层，也不会漏行。
     */
    public static int fetchLimit(int page, int matched, int perPage) {
        return (clampPage(page, matched, perPage) + 1) * Math.max(1, perPage);
    }

    /** 从一次"取前 N 条"的列表里切出第 {@code page} 页（不读盘、不改列表）。 */
    public static List<ShanhaiEntityEdit.Row> pageRows(ShanhaiEntityEdit.DimensionListing listing,
                                                       int page, int perPage) {
        if (listing == null || !listing.ok() || listing.rows() == null) {
            return List.of();
        }
        final int per = Math.max(1, perPage);
        final int p = clampPage(page, listing.matched(), per);
        final int from = p * per;
        if (from >= listing.rows().size()) {
            return List.of();
        }
        final int to = Math.min(listing.rows().size(), from + per);
        return List.copyOf(listing.rows().subList(from, to));
    }

    /** 页码那一行（{@code 第 2/52 页}）。 */
    public static String pageText(int page, int matched, int perPage) {
        final int pages = pageCount(matched, perPage);
        return "第 " + (clampPage(page, matched, perPage) + 1) + "/" + pages + " 页";
    }

    /** 页码旁边那个计数（{@code 共 358 条 · 本页 8-14}）。 */
    public static String pageCountText(int page, int matched, int perPage) {
        if (matched <= 0) {
            return "§8没有匹配的实体";
        }
        final int per = Math.max(1, perPage);
        final int p = clampPage(page, matched, per);
        return "§8共 " + matched + " 条 · 本页 " + (p * per + 1) + "-" + Math.min(matched, (p + 1) * per);
    }

    // ================================================================== 行按钮上的字

    /**
     * 一行可点的实体：{@code 1. 运输矿车 @-358.5,85.5,-309.5 71a87045}（🔴 名字在前，id 进 tooltip）。
     */
    public static String rowText(int index, String typeId, double x, double y, double z,
                                 String uuid, boolean residue, Namer namer) {
        final String name = typeShort(typeId, namer);
        if (name.isEmpty() && uuid.isEmpty()) {
            return "";
        }
        final String shortUuid = uuid == null ? "" : (uuid.length() > 8 ? uuid.substring(0, 8) : uuid);
        return "§8" + (index + 1) + ". " + (residue ? "§8" : "§f") + name
                + " §8@" + num(x) + "," + num(y) + "," + num(z)
                + (shortUuid.isEmpty() ? "" : " §8" + shortUuid)
                + (residue ? " §8[残留]" : "");
    }

    /** 同 {@link #rowText}，直接吃数据层的一行（{@code Row.id()} 就是纯注册名）。 */
    public static String rowText(ShanhaiEntityEdit.Row row, int index, Namer namer) {
        if (row == null) {
            return "";
        }
        return rowText(index, row.id(), row.x(), row.y(), row.z(), row.uuid(), row.residue(), namer);
    }

    /** 「列出」之后"概况"那一行。 */
    public static String summaryLine(ShanhaiEntityEdit.DimensionListing listing) {
        if (listing == null) {
            return "§8还没列 · 点「列出」（只填维度就够，不用坐标）";
        }
        if (!listing.ok()) {
            return "§c列不出来：" + (listing.error() == null ? "（没给原因）" : listing.error());
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("§7文件 ").append(listing.files()).append(" 个 · 区块 ").append(listing.chunks())
                .append(" 个 · 实体 ").append(listing.total()).append(" 个 · 匹配 ").append(listing.matched());
        if (listing.residueHidden() > 0) {
            sb.append(" · 折叠残留 ").append(listing.residueHidden());
        }
        if (listing.truncated()) {
            sb.append(" §8(只显示前 ").append(listing.shown()).append(" 条：用搜索缩小范围)");
        }
        if (listing.filter() != null && !listing.filter().isEmpty()) {
            sb.append(" §8搜索「").append(listing.filter()).append("」");
        }
        return sb.toString();
    }

    /** 残留开关旁边那个计数（"已折叠 N 条"）。 */
    public static String residueNote(ShanhaiEntityEdit.DimensionListing listing) {
        if (listing == null) {
            return "§8先点「列出」";
        }
        if (!listing.ok()) {
            return "§c列不出来";
        }
        return listing.residueHidden() > 0
                ? "§8已折叠 " + listing.residueHidden() + " 条"
                : "§8没有残留";
    }

    /** 第 ④ 页底部那行提示（客户端自己知道的字；🔴 必须放得进 386 px，不许靠截断）。 */
    public static String hintLine() {
        return "§8· 点一行进详情；改 10 项 / 删这一条；落盘只在停服窗口里";
    }

    /** 覆盖层里 4 行"现在是什么"（第 5 行由 {@link Model#note(int)} 现算：删除预期 + 反馈）。 */
    public static String[] detailLines(ShanhaiEntityEdit.Detail d) {
        final String[] out = new String[ShanhaiSaveEditLayout.OV_NOTE_LINES];
        for (int i = 0; i < out.length; i++) {
            out[i] = "";
        }
        out[0] = "§8实体详情还没读出来（点一行，服务端会去盘上读）";
        if (d == null) {
            return out;
        }
        if (!d.ok()) {
            out[0] = "§c读不出来：" + (d.error() == null ? "（没给原因）" : d.error());
            return out;
        }
        out[0] = "§7位置 §f" + value(d, "Pos") + "   §7朝向 §f" + value(d, "Rotation");
        out[1] = "§7运动 §f" + value(d, "Motion") + "   §7血量 §f" + value(d, "Health")
                + " §8上限 " + num(d.healthMax()) + " §8(" + (d.healthMaxSource() == null ? "" : d.healthMaxSource()) + ")";
        out[2] = "§7自定义名 §f" + nameText(d) + " §7可见§f" + flagText(d, "CustomNameVisible")
                + " §7关AI§f" + flagText(d, "NoAI") + " §7无敌§f" + flagText(d, "Invulnerable")
                + " §7不刷掉§f" + flagText(d, "PersistenceRequired");
        out[3] = "§7物品 §f" + value(d, "Item") + " §8" + locateText(d)
                + (d.residue() ? " §8[视觉残留]" : "")
                + (d.notes() == null || d.notes().isEmpty() ? "" : " §c⚠ " + d.notes().get(0));
        return out;
    }

    private static String value(ShanhaiEntityEdit.Detail d, String key) {
        final ShanhaiEntityEdit.Field f = d.field(key);
        if (f == null) {
            return "?";
        }
        return f.present() ? f.value() : "§8(键不存在)";
    }

    private static String nameText(ShanhaiEntityEdit.Detail d) {
        final ShanhaiEntityEdit.Field f = d.field("CustomName");
        if (f == null || !f.present()) {
            return "§8(无)";
        }
        final String shown = ShanhaiEntityEdit.displayNameOf(f.value());
        return shown == null || shown.isEmpty() ? "§8(无)" : shown;
    }

    private static String flagText(ShanhaiEntityEdit.Detail d, String key) {
        final ShanhaiEntityEdit.Field f = d.field(key);
        if (f == null || !f.present()) {
            return "§8-";
        }
        final String v = f.value() == null ? "" : f.value();
        return v.startsWith("1") ? "是" : v.startsWith("0") ? "否" : "?";
    }

    private static String locateText(ShanhaiEntityEdit.Detail d) {
        final ShanhaiEntityEdit.Hit hit = d.hit();
        if (hit == null) {
            return "(没有定位信息)";
        }
        return hit.file() + " 槽 " + hit.slot() + " 区块(" + hit.chunkX() + "," + hit.chunkZ() + ") #" + hit.index();
    }

    /** 数字显示：整数就不带 {@code .0}。 */
    public static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1.0E15D) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * 🔴 「删除这条」被挡住时的<b>唯一</b>那句文案（{@code null} = 这条可以删）。
     *
     * <p>为什么要有它：详情<b>读不出来</b>（{@code ok=false}）时，旧提示写的是"还没选中一条实体"
     * —— 那是<b>误报</b>：玩家明明点了一行，是读盘失败了。真正的原因在覆盖层顶部那行红字里
     * （{@link #detailLines} 的失败分支），这里只补一句"所以不能删"。
     *
     * <p>本方法<b>只提供字、不改任何状态</b>：面板在 {@code !ok()} 分支上拿它上屏就好
     * （面板里不许再写死"还没选中一条实体"，见接线清单）。
     */
    public static String deleteBlockedReason(ShanhaiEntityEdit.Detail d) {
        if (d == null) {
            return "还没选中一条实体（先点一行）";
        }
        if (!d.ok()) {
            return "这条没读出来（原因见顶部红字），不能删";
        }
        return null;
    }

    /**
     * 🔴 「写入」被挡住时的那句文案（{@code null} = 这条可以写）。
     *
     * <p>与 {@link #deleteBlockedReason} 同一条理由：{@code !ok()} 时旧提示写的是"还没选中一条实体"
     * —— 玩家明明点了，是读盘失败。两个字面串<b>只有一处来源</b>（这里），面板别再各写一份。
     */
    public static String writeBlockedReason(ShanhaiEntityEdit.Detail d) {
        if (d == null) {
            return "还没选中一条实体（先点一行）";
        }
        if (!d.ok()) {
            return "这条没读出来（原因见顶部红字），不能写";
        }
        return null;
    }

    // ================================================================== 预演（不写一个字节）

    /** 一次"如果点写入会发生什么"的读数。 */
    public record Preview(boolean ok, String error, List<String> changed, List<String> notes,
                          List<String> dangers) {

        public static Preview fail(String why) {
            return new Preview(false, why == null ? "被拒绝" : why, List.of(), List.of(), List.of());
        }

        /** 一行摘要（"将改：…"）。 */
        public String changedLine() {
            if (!this.ok) {
                return "§c" + this.error;
            }
            return "§7将改：§f" + (this.changed.isEmpty() ? "(没有变化)" : String.join("／", this.changed));
        }
    }

    /**
     * 🔴 <b>预演</b>：只读地解一遍这条实体，把 patch 应用到一份<b>内存副本</b>上。
     *
     * <p>为什么必须走数据层的 {@code applyPatch}：那样 dangers（例如"Item.id 改成未知 id 可能被丢弃"）
     * 与拒绝理由（例如"Pos 必须与实体所在区块一致"）与真正写盘时<b>逐字相同</b>，
     * 我这边一个都不重复实现。这里也<b>不写盘</b>：{@code entityAt} 是"从盘上重新解一份"，
     * 改的是那份内存里的 {@link CompoundTag}。
     */
    public static Preview preview(ShanhaiEntityEdit.Detail detail, ShanhaiEntityEdit.Patch patch) {
        if (detail == null || !detail.ok()) {
            return Preview.fail("这条实体的详情还没读出来（先点一次这一行）");
        }
        if (detail.hit() == null) {
            return Preview.fail("这条实体没有定位信息（hit=null）");
        }
        if (patch == null || patch.isEmpty()) {
            return Preview.fail("这次没有要改的项（16 个框要么空着、要么还是预填的现值）");
        }
        final List<String> changed = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> dangers = new ArrayList<>();
        try {
            final CompoundTag entity = ShanhaiEntityEdit.entityAt(detail.hit());
            ShanhaiEntityEdit.applyPatch(entity, patch, detail.hit(), changed, notes, dangers);
        } catch (IllegalArgumentException iae) {
            return Preview.fail(iae.getMessage() == null ? "数据层拒绝了这次修改" : iae.getMessage());
        } catch (Throwable t) {
            return Preview.fail("预演失败（一个字节都没写）：" + t);
        }
        return new Preview(true, "", List.copyOf(changed), List.copyOf(notes), List.copyOf(dangers));
    }

    // ================================================================== 带闸的写 / 删

    /** 一次"带闸"尝试的结果：{@code permitted=false} ⇒ 一个字节都没写。 */
    public record Apply(boolean permitted, String refusal, ShanhaiEntityEdit.WriteOutcome write) {

        public boolean ok() {
            return this.permitted && this.write != null && this.write.ok();
        }

        public String line() {
            if (!this.permitted) {
                return "§e" + (this.refusal == null ? "被拒绝" : this.refusal);
            }
            return this.write == null ? "§c没有结果" : "§f" + this.write.line();
        }
    }

    /**
     * 写回 10 项里的若干项（<b>带闸</b>：{@code flow} 不在 STOPPED 就一个字节都不写）。
     *
     * <p>为什么闸在这里而不在调用方：与 {@code ShanhaiSaveEditAuditFix#drop} 同一条口径
     * —— 面板那一层只负责"把字念给玩家听"，闸必须紧贴落盘那一行。
     */
    public static Apply writeGated(Path worldDir, String dimension, String uuid,
                                   ShanhaiEntityEdit.Patch patch, Path backupRoot, String stamp,
                                   ShanhaiSaveEditFlow flow) {
        if (worldDir == null || uuid == null || uuid.isEmpty()) {
            return new Apply(false, "缺少存档路径或 UUID", null);
        }
        if (patch == null || patch.isEmpty()) {
            return new Apply(false, "没有要写的项（和预填的现值一样 / 全空）", null);
        }
        final ShanhaiSaveEditFlow.ApplyAttempt attempt = flow == null ? null : flow.tryApply(WHO_WRITE);
        if (attempt == null || !attempt.permitted()) {
            return new Apply(false, windowClosed(flow), null);
        }
        final ShanhaiEntityEdit.WriteOutcome o =
                ShanhaiEntityEdit.writeFields(worldDir, dimension, uuid, patch, backupRoot, stamp);
        if (o != null && o.ok()) {
            flow.markApplied();
        }
        return new Apply(true, "", o);
    }

    /** 按维度 + UUID 删一条（<b>带闸</b>，同 {@link #writeGated}）。 */
    public static Apply deleteGated(Path worldDir, String dimension, String uuid,
                                    Path backupRoot, String stamp, ShanhaiSaveEditFlow flow) {
        if (worldDir == null || uuid == null || uuid.isEmpty()) {
            return new Apply(false, "缺少存档路径或 UUID", null);
        }
        final ShanhaiSaveEditFlow.ApplyAttempt attempt = flow == null ? null : flow.tryApply(WHO_DELETE);
        if (attempt == null || !attempt.permitted()) {
            return new Apply(false, windowClosed(flow), null);
        }
        final ShanhaiEntityEdit.WriteOutcome o =
                ShanhaiEntityEdit.deleteByUuid(worldDir, dimension, uuid, backupRoot, stamp);
        if (o != null && o.ok()) {
            flow.markApplied();
        }
        return new Apply(true, "", o);
    }

    private static String windowClosed(ShanhaiSaveEditFlow flow) {
        return "停服窗口没开（" + (flow == null ? "当前没有 saveedit 运行" : "现在在 " + flow.phase() + " 阶段")
                + "）⇒ 一个字节都没写";
    }

    // ================================================================== 16 个输入 → Patch

    /**
     * 覆盖层的<b>状态机 + 输入缓冲</b>（两侧各一份；只有服务端那份会被喂进 {@code Detail}）。
     *
     * <pre>
     *   关闭 ──open()──▶ 打开（没有任何 detail）──openDetail(d)──▶ 打开（有读数，16 个框已预填现值）
     *     ▲                                                        │
     *     └──────────────────────── close() ───────────────────────┘
     *   打开时按「写入」= 预演（列 dangers / 拒绝理由）后立刻真写（writeGated）；
     *   「删除这条」= 直接删（deleteGated 或第四期的暂存通道）。
     *   🔴 <b>没有"再点一次才真写"那一步</b>（用户明确要求：除非他指定，否则都不加二次确认）。
     * </pre>
     *
     * <p>每个格子还记着<b>预填的原始值</b>（{@link #openDetail} 从 {@link #prefillOf} 灌进来）：
     * {@link #patch()} 只把<b>改过的</b>项交出去 ⇒ 预填不会顺手把没动过的字段一起覆盖。
     */
    public static final class Model {

        private boolean opened;
        private ShanhaiEntityEdit.Detail detail;
        private String summary = "";
        /**
         * 标题用的"原始料"：类型注册名 + UUID。
         *
         * <p>🔴 为什么不直接推拼好的标题：类型名要<b>在客户端查语言表</b>（服务端没有语言表，
         * 只能拿到 {@code entity.minecraft.xxx} 这种键）。所以服务端把这两样<b>原样</b>推过来
         * （覆盖层的 {@code UPDATE_TITLE}），标题那一行由客户端
         * {@link ShanhaiSaveEditEntityUi#typeLabel} 现拼 —— 中文在前。
         */
        private String titleId = "";
        private String titleUuid = "";
        private final String[] values = new String[INPUTS.size()];
        /** 16 个框在 {@link #openDetail} 时的预填值（= 盘上的现值；{@code ""} = 这一项没有预填）。 */
        private final String[] originals = new String[INPUTS.size()];
        private final List<String> dangers = new ArrayList<>();
        private final List<String> changed = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private String feedback = "";
        private int chunkBefore = -1;
        private int chunkAfter = -1;

        public Model() {
            clearValues();
            clearOriginals();
        }

        // ---------------------------------------------------------------- 开 / 关

        /** 打开（清空一切：上一条实体的残留输入绝不许带进来）。 */
        public void open() {
            reset();
            this.opened = true;
        }

        /** 关掉（同样清空）。 */
        public void close() {
            reset();
            this.opened = false;
        }

        private void reset() {
            this.detail = null;
            this.summary = "";
            this.titleId = "";
            this.titleUuid = "";
            this.feedback = "";
            this.dangers.clear();
            this.changed.clear();
            this.notes.clear();
            this.chunkBefore = -1;
            this.chunkAfter = -1;
            clearValues();
            clearOriginals();
        }

        /**
         * 服务端读完盘之后调：把这一条实体的 10 项读数灌进来，并把 16 个框的<b>预填值</b>
         * （{@link ShanhaiSaveEditEntityUi#prefillOf}）记成"原始值"。
         *
         * @param chunkEntityCount 这个区块现在有几条实体（{@code -1} = 算不出来；删除预期会如实说算不出来）
         */
        public void openDetail(ShanhaiEntityEdit.Detail d, int chunkEntityCount) {
            open();
            this.detail = d;
            this.summary = d == null ? "" : (d.id() == null ? "" : d.id());
            this.titleId = d == null || d.id() == null ? "" : d.id();
            this.titleUuid = d == null || d.uuid() == null ? "" : d.uuid();
            this.chunkBefore = chunkEntityCount;
            this.chunkAfter = chunkEntityCount < 0 ? -1 : Math.max(0, chunkEntityCount - 1);
            final String[] prefill = prefillOf(d);
            System.arraycopy(prefill, 0, this.originals, 0, this.originals.length);
            // 输入缓冲先跟预填对齐（面板每次点「写入」都会用 readInputs() 再覆盖一遍，两边口径相同）
            System.arraycopy(prefill, 0, this.values, 0, this.values.length);
        }

        /** 16 个框要预填的字（副本；下标同 {@link #inputs()}，{@code ""} = 没有可用的预填值）。 */
        public String[] prefill() {
            return this.originals.clone();
        }

        /** 第 {@code index} 个框的预填值（越界 ⇒ {@code ""}）。 */
        public String original(int index) {
            return index < 0 || index >= this.originals.length ? "" : this.originals[index];
        }

        /** 客户端在"点了一行"时先打开（字由服务端推过来）。 */
        public void openPlaceholder(String rowText) {
            open();
            this.summary = rowText == null ? "" : rowText;
        }

        /** 客户端收标题料（覆盖层的 {@code UPDATE_TITLE}）：类型注册名 + UUID，原样存着，画的时候再拼。 */
        public void setTitlePayload(String typeId, String uuid) {
            this.titleId = typeId == null ? "" : typeId;
            this.titleUuid = uuid == null ? "" : uuid;
        }

        /** 标题料里的类型注册名（判据/调试用）。 */
        public String titleId() {
            return this.titleId;
        }

        public boolean opened() {
            return this.opened;
        }

        public ShanhaiEntityEdit.Detail detail() {
            return this.detail;
        }

        // ---------------------------------------------------------------- 输入缓冲

        public void clearValues() {
            for (int i = 0; i < this.values.length; i++) {
                this.values[i] = "";
            }
        }

        /** 清掉"预填的原始值"（{@link #open()} / {@link #close()} 都会调）。 */
        public void clearOriginals() {
            for (int i = 0; i < this.originals.length; i++) {
                this.originals[i] = "";
            }
        }

        public void setValue(int index, String text) {
            if (index < 0 || index >= this.values.length) {
                return;
            }
            this.values[index] = text == null ? "" : text.trim();
        }

        public void setValues(String[] raw) {
            for (int i = 0; i < this.values.length; i++) {
                this.values[i] = raw != null && i < raw.length ? (raw[i] == null ? "" : raw[i].trim()) : "";
            }
        }

        public String value(int index) {
            return index < 0 || index >= this.values.length ? "" : this.values[index];
        }

        /** 按 key 取值（判据用）。 */
        public String valueOf(String key) {
            for (int i = 0; i < INPUTS.size(); i++) {
                if (INPUTS.get(i).key().equals(key)) {
                    return this.values[i];
                }
            }
            return "";
        }

        /** 哪些格子有字（不管合不合法；预填之后"有字"不等于"改过" —— 改没改看 {@link #patch()}）。 */
        public List<String> touched() {
            final List<String> out = new ArrayList<>();
            for (int i = 0; i < INPUTS.size(); i++) {
                if (!this.values[i].isEmpty()) {
                    out.add(INPUTS.get(i).key());
                }
            }
            return out;
        }

        // ---------------------------------------------------------------- 10 项 → Patch

        /**
         * 16 个格子 → {@link ShanhaiEntityEdit.Patch}（{@code null} = 这一项不动）。
         *
         * <p>🔴 <b>与预填值相同 ⇒ 当成没改</b>（按"项"判，不按"格"判）：
         * <ul>
         *   <li>单项（名字 / 4 个开关 / 血量 / 物品 id / 物品数）：格子里的字与预填值<b>语义相同</b>
         *       （{@code 20} 与 {@code 20.0}、{@code 是} 与 {@code 1} 都算相同）⇒ {@code null}；</li>
         *   <li>成组的项（Pos / Motion / Rotation）：<b>三项全都</b>与预填值相同 ⇒ {@code null}；
         *       只要有一项变了 ⇒ <b>三项一起</b>交出去（半组坐标没有合法语义，数据层会直接拒绝）。</li>
         * </ul>
         * ⇒ 预填不会顺手把没动过的字段覆盖掉；玩家只改 y 时 x/z 仍按现值原样写回。
         *
         * @throws IllegalArgumentException 格子里填了东西但不成组 / 不是数字 / 不是布尔
         */
        public ShanhaiEntityEdit.Patch patch() {
            final List<Double> pos = doubles(K_POS_X, K_POS_Y, K_POS_Z, "Pos");
            final List<Float> rotation = floats(K_YAW, K_PITCH, "Rotation（yaw/pitch）");
            final List<Double> motion = doubles(K_VX, K_VY, K_VZ, "Motion（vx/vy/vz）");
            final String name = changedText(K_NAME);
            final Boolean nameVisible = changedFlag(K_NAME_VISIBLE, "CustomNameVisible");
            final Boolean noAi = changedFlag(K_NOAI, "NoAI");
            final Boolean invulnerable = changedFlag(K_INVULNERABLE, "Invulnerable");
            final Boolean persistence = changedFlag(K_PERSISTENT, "PersistenceRequired");
            final Double health = changedNumber(K_HEALTH, "Health");
            final String itemId = changedText(K_ITEM_ID);
            final Integer itemCount = changedInteger(K_ITEM_COUNT, "Item.Count");
            return new ShanhaiEntityEdit.Patch(pos, rotation, motion, name, nameVisible, noAi,
                    invulnerable, persistence, health, itemId, itemCount);
        }

        private List<Double> doubles(String kx, String ky, String kz, String what) {
            final String x = valueOf(kx);
            final String y = valueOf(ky);
            final String z = valueOf(kz);
            final int filled = (x.isEmpty() ? 0 : 1) + (y.isEmpty() ? 0 : 1) + (z.isEmpty() ? 0 : 1);
            if (filled == 0) {
                return null;
            }
            if (filled != 3) {
                throw new IllegalArgumentException(what + " 三项要一起填（现在填了 " + filled + " 项："
                        + (x.isEmpty() ? "" : "x ") + (y.isEmpty() ? "" : "y ") + (z.isEmpty() ? "" : "z ")
                        + "）—— 半组坐标没有合法语义");
            }
            if (unchangedGroup(kx, ky, kz)) {
                return null;            // 三项都还是预填的现值 ⇒ 这一项没改
            }
            return List.of(parseDouble(x, kx), parseDouble(y, ky), parseDouble(z, kz));
        }

        private List<Float> floats(String ka, String kb, String what) {
            final String a = valueOf(ka);
            final String b = valueOf(kb);
            final int filled = (a.isEmpty() ? 0 : 1) + (b.isEmpty() ? 0 : 1);
            if (filled == 0) {
                return null;
            }
            if (filled != 2) {
                throw new IllegalArgumentException(what + " 要一起填（现在只填了一项）");
            }
            if (unchangedGroup(ka, kb)) {
                return null;
            }
            return List.of(parseFloat(a, ka), parseFloat(b, kb));
        }

        // ---------------------------------------------------------------- "改没改"（对着预填值比）

        /** 单项：与预填值语义相同（或又清空了）⇒ {@code null}（这一项不动）。 */
        private String changedText(String key) {
            final String cur = valueOf(key);
            return cur.isEmpty() || sameAsPrefill(key, cur) ? null : cur;
        }

        private Boolean changedFlag(String key, String what) {
            final String cur = valueOf(key);
            return cur.isEmpty() || sameAsPrefill(key, cur) ? null : flag(key, what);
        }

        private Double changedNumber(String key, String what) {
            final String cur = valueOf(key);
            return cur.isEmpty() || sameAsPrefill(key, cur) ? null : number(key, what);
        }

        private Integer changedInteger(String key, String what) {
            final String cur = valueOf(key);
            return cur.isEmpty() || sameAsPrefill(key, cur) ? null : integer(key, what);
        }

        /** 成组的三项是不是<b>全都</b>还是预填的现值。 */
        private boolean unchangedGroup(String... keys) {
            for (String key : keys) {
                if (!sameAsPrefill(key, valueOf(key))) {
                    return false;
                }
            }
            return true;
        }

        /** 这个格子里的字与它在 {@link #openDetail} 时的预填值是不是"语义相同"。 */
        private boolean sameAsPrefill(String key, String current) {
            final int i = indexOf(key);
            return i >= 0 && canonical(key, current).equals(canonical(key, this.originals[i]));
        }

        /**
         * 把格子里的字归一化，只为了比较：{@code "20"} 与 {@code "20.0"}、{@code "是"} 与 {@code "1"}
         * 要算成同一个值（否则玩家"把 1 改成 是"会被当成一次真改动）。解析不了的形态带 {@code "?"}
         * 前缀，保证它<b>不会</b>等于任何正常值。
         */
        private static String canonical(String key, String text) {
            final String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                return "";
            }
            switch (key) {
                case K_POS_X:
                case K_POS_Y:
                case K_POS_Z:
                case K_VX:
                case K_VY:
                case K_VZ:
                case K_YAW:
                case K_PITCH:
                case K_HEALTH:
                    return canonicalNumber(s);
                case K_ITEM_COUNT:
                    return canonicalInteger(s);
                case K_NOAI:
                case K_INVULNERABLE:
                case K_NAME_VISIBLE:
                case K_PERSISTENT: {
                    final Boolean b = parseFlag(s);
                    return b == null ? "?" + s : (b ? "1" : "0");
                }
                default:
                    return s;           // 文本项（自定义名 / 物品 id）逐字比
            }
        }

        private static String canonicalNumber(String s) {
            try {
                return String.valueOf(Double.parseDouble(s));
            } catch (NumberFormatException nfe) {
                return "?" + s;
            }
        }

        private static String canonicalInteger(String s) {
            try {
                return String.valueOf(Integer.parseInt(s));
            } catch (NumberFormatException nfe) {
                return "?" + s;
            }
        }

        private Double parseDouble(String text, String key) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(label(key) + " 不是数字：「" + text + "」");
            }
        }

        private Float parseFloat(String text, String key) {
            try {
                return Float.valueOf(text);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(label(key) + " 不是数字：「" + text + "」");
            }
        }

        private Double number(String key, String what) {
            final String text = valueOf(key);
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(what + " 不是数字：「" + text + "」");
            }
        }

        private Integer integer(String key, String what) {
            final String text = valueOf(key);
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(what + " 不是整数：「" + text + "」");
            }
        }

        private Boolean flag(String key, String what) {
            final String text = valueOf(key);
            if (text.isEmpty()) {
                return null;
            }
            final Boolean parsed = parseFlag(text);
            if (parsed == null) {
                throw new IllegalArgumentException(what + " 只能填 是 / 否（也认 1/0、true/false），现在是「"
                        + text + "」");
            }
            return parsed;
        }

        /** 是/否 的词表（{@code null} = 认不出来）。{@link #flag} 与 {@link #canonical} 共用这一份。 */
        private static Boolean parseFlag(String text) {
            switch (text.toLowerCase(Locale.ROOT)) {
                case "是":
                case "1":
                case "true":
                case "yes":
                case "on":
                case "开":
                    return Boolean.TRUE;
                case "否":
                case "0":
                case "false":
                case "no":
                case "off":
                case "关":
                    return Boolean.FALSE;
                default:
                    return null;
            }
        }

        private static String label(String key) {
            for (Input in : INPUTS) {
                if (in.key().equals(key)) {
                    return in.label();
                }
            }
            return key;
        }

        // ---------------------------------------------------------------- 预演结果 / 反馈

        public void setPreview(Preview p) {
            this.dangers.clear();
            this.changed.clear();
            this.notes.clear();
            if (p == null) {
                return;
            }
            if (p.ok()) {
                this.changed.addAll(p.changed());
                this.notes.addAll(p.notes());
                this.dangers.addAll(p.dangers());
            } else {
                this.feedback = "§c" + p.error();
            }
        }

        public List<String> dangers() {
            return List.copyOf(this.dangers);
        }

        public List<String> changed() {
            return List.copyOf(this.changed);
        }

        public String feedback() {
            return this.feedback == null ? "" : this.feedback;
        }

        public void setFeedback(String text) {
            this.feedback = text == null ? "" : text;
        }

        /**
         * 「删除这条」被挡住时的文案（{@code null} = 这条可以删）。
         *
         * <p>就是 {@link ShanhaiSaveEditEntityUi#deleteBlockedReason(ShanhaiEntityEdit.Detail)} 的
         * 模型版：面板在 {@code !ok()} 分支上直接用 {@code model.deleteBlockedReason()}，
         * <b>别再写死"还没选中一条实体"</b>（读盘失败时那是误报）。
         */
        public String deleteBlockedReason() {
            return ShanhaiSaveEditEntityUi.deleteBlockedReason(this.detail);
        }

        /** 「写入」挡住的文案（{@code null} = 可以写）；见 {@link ShanhaiSaveEditEntityUi#writeBlockedReason}。 */
        public String writeBlockedReason() {
            return ShanhaiSaveEditEntityUi.writeBlockedReason(this.detail);
        }

        public int chunkBefore() {
            return this.chunkBefore;
        }

        public int chunkAfter() {
            return this.chunkAfter;
        }

        /** 删除前/后那一段字（读数来自 {@code Detail} 与区块计数）。 */
        public String expectation() {
            if (this.detail == null || this.detail.hit() == null) {
                return deleteExpectation(null, 0, 0, -1, -1);
            }
            return deleteExpectation(this.detail.dimension(), this.detail.hit().chunkX(),
                    this.detail.hit().chunkZ(), this.chunkBefore, this.chunkAfter);
        }

        // ---------------------------------------------------------------- 覆盖层上的字

        /**
         * 标题那一行：<b>中文在前</b> —— {@code 运输矿车（minecraft:chest_minecart） <uuid>}。
         *
         * <p>🔴 这个名字必须在<b>客户端</b>现拼（服务端没有语言表）。所以本方法用的是
         * {@link #setTitlePayload(String, String)} 收到的"原始料"，而标题那个
         * {@code LabelWidget} 是 {@code setClientSideWidget()} 的（服务端不会把它的字推过来覆盖掉）。
         * 永远不返回 {@code null}（LDLib 的标签对 null 会 NPE）。
         */
        public String title() {
            if (this.titleId.isEmpty() && this.titleUuid.isEmpty()) {
                return "实体详情 §8" + this.summary;
            }
            return "§f" + typeLabel(this.titleId, REGISTRY_NAMER)
                    + (this.titleUuid.isEmpty() ? "" : " §8" + this.titleUuid);
        }

        /** 行按钮上的字（同一个"中文在前"口径；客户端现拼）。 */
        public static String rowText(ShanhaiEntityEdit.Row row, int index) {
            return ShanhaiSaveEditEntityUi.rowText(row, index, REGISTRY_NAMER);
        }

        /**
         * 第 {@code index} 行读数（0~3 来自 {@link ShanhaiSaveEditEntityUi#detailLines}；
         * 第 4 行 = 删除预期 + 上一次操作的反馈）。
         */
        public String note(int index) {
            if (index < 0) {
                return "";
            }
            final String[] lines = detailLines(this.detail);
            if (index < lines.length - 1) {
                return lines[index];
            }
            if (index == lines.length - 1) {
                final StringBuilder sb = new StringBuilder(expectation());
                if (!this.feedback.isEmpty()) {
                    sb.append(" §8· ").append(this.feedback);
                }
                if (!this.changed.isEmpty()) {
                    sb.append(" §8· §7将改：§f").append(String.join("／", this.changed));
                }
                return sb.toString();
            }
            return "";
        }

        /** 第 {@code index} 行红字 dangers（在「写入」按钮正上方）。 */
        public String dangerLine(int index) {
            final int max = ShanhaiSaveEditLayout.OV_DANGER_LINES;
            if (index < 0 || index >= max) {
                return "";
            }
            // 超过 max 条时，最后一行让给"还有 N 条"，红字只显示前 max-1 条 ——
            // 静默截断是这里最不能犯的错（玩家会以为只有两条危险）
            final boolean overflow = this.dangers.size() > max;
            final int shown = overflow ? max - 1 : Math.min(max, this.dangers.size());
            if (index < shown) {
                return "§c⚠ " + this.dangers.get(index);
            }
            if (overflow && index == max - 1) {
                return "§c⚠ …还有 " + (this.dangers.size() - shown) + " 条（见日志）";
            }
            return "";
        }

        /** 有没有 dangers（判据与面板共用）。 */
        public boolean hasDangers() {
            return !this.dangers.isEmpty();
        }
    }
}
