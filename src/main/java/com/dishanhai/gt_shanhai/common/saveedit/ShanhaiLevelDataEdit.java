package com.dishanhai.gt_shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "level.dat 想改成什么"（纯 JDK：不引用任何 NBT / Minecraft 类型）。
 *
 * <h2>一、空 = 不改</h2>
 * 面板第二页 21 个输入框，留空就是不动 —— 与一期 playerdata 面板同一条语义
 * （{@link ShanhaiPlayerDataEdit}）。
 *
 * <h2>二、🔴 白名单 + 显式拒绝，二者都不许静默</h2>
 * 每一条输入都过 {@link ShanhaiLevelDataSpec}：
 * <ul>
 *   <li>在白名单里 ⇒ 按类型/范围解析，不合法就进 {@link #errors}（并<b>不进</b> {@link #values}，
 *       绝不"报错了但其实改了一半"）；</li>
 *   <li>不在白名单里 ⇒ 把 {@link ShanhaiLevelDataSpec#rejectionReason(String)} 的原话写进
 *       {@link #errors}（例如 {@code fml.LoadingModList} 会得到"Forge 的额外世界数据…"）。
 *       这正是判据 ② 的负对照：改一个模组私有键必须<b>被拒并给出理由</b>。</li>
 * </ul>
 *
 * <h2>三、规范化（canonical）</h2>
 * 校验通过的值一律折成规范写法：布尔 → {@code true/false}；整数 → 十进制；小数 → 去尾零。
 * 这样 {@link #describe()}、日志、diff 三处看到的是同一个串，事后对账不用再猜。
 */
public final class ShanhaiLevelDataEdit {

    /** 原样输入（id → 去掉首尾空白后的非空串）。只给日志用。 */
    public final Map<String, String> raw = new LinkedHashMap<>();
    /** 校验通过后的规范值（id → canonical）。 */
    public final Map<String, String> values = new LinkedHashMap<>();
    /** 校验失败的原因（空 = 全部合法）。 */
    public final List<String> errors = new ArrayList<>();
    /** 不是错误的提醒（例如"改天气但 doWeatherCycle=true"）。 */
    public final List<String> notes = new ArrayList<>();

    public ShanhaiLevelDataEdit() {}

    /** 有没有任何一项要改。 */
    public boolean isEmpty() {
        return this.values.isEmpty();
    }

    /** 有没有输入（含非法输入）。面板判断"是不是什么都没填"用它。 */
    public boolean hasInput() {
        return !this.raw.isEmpty();
    }

    /**
     * 从面板文本解析。
     *
     * @param in 键 = {@link ShanhaiLevelDataSpec.Field#id()}，值 = 面板输入框里的字（空 = 不改）
     */
    public static ShanhaiLevelDataEdit parse(Map<String, String> in) {
        final ShanhaiLevelDataEdit edit = new ShanhaiLevelDataEdit();
        if (in == null || in.isEmpty()) {
            return edit;
        }
        for (Map.Entry<String, String> e : in.entrySet()) {
            final String id = e.getKey() == null ? "" : e.getKey().trim();
            final String value = e.getValue() == null ? "" : e.getValue().trim();
            if (id.isEmpty() || value.isEmpty()) {
                continue;                       // 空 = 不改
            }
            edit.raw.put(id, value);
            final ShanhaiLevelDataSpec.Field field = ShanhaiLevelDataSpec.field(id);
            if (field == null) {
                // 🔴 不许静默：把"为什么不让改"原样说出来
                edit.errors.add(ShanhaiLevelDataSpec.rejectionReason(id));
                continue;
            }
            final String canonical = canonical(field, value, edit);
            if (canonical != null) {
                edit.values.put(id, canonical);
            }
        }
        edit.crossChecks();
        return edit;
    }

    /** 按字段类型解析并规范化；失败返回 {@code null} 并往 {@code edit.errors} 里记一条。 */
    private static String canonical(ShanhaiLevelDataSpec.Field field, String value,
                                    ShanhaiLevelDataEdit edit) {
        switch (field.kind()) {
            case STRING: {
                if (value.length() > 128) {
                    edit.errors.add(field.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e0aa7ce81cd69895"));
                    return null;
                }
                return value;
            }
            case BOOL:
            case RULE_BOOL: {
                final Boolean b = parseBool(value);
                if (b == null) {
                    edit.errors.add(field.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.bd23f3c3db078bfb") + value + "」");
                    return null;
                }
                return b ? "true" : "false";
            }
            case INT:
            case LONG:
            case BYTE:
            case RULE_INT: {
                final long v;
                try {
                    v = Long.parseLong(value);
                } catch (NumberFormatException nfe) {
                    edit.errors.add(field.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.0587f4319740c067") + value + "」");
                    return null;
                }
                if (v < field.min() || v > field.max()) {
                    edit.errors.add(field.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ecd078d6a145a83b") + field.rangeText()
                            + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.3f2de5fa4f91c886") + value + "」");
                    return null;
                }
                return String.valueOf(v);
            }
            case FLOAT: {
                final float f;
                try {
                    f = Float.parseFloat(value);
                } catch (NumberFormatException nfe) {
                    edit.errors.add(field.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.47dc1c619bff958b") + value + "」");
                    return null;
                }
                if (Float.isNaN(f) || Float.isInfinite(f) || f < -1.0E6F || f > 1.0E6F) {
                    edit.errors.add(field.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.e4cbc51c6539a3bb") + value + "」");
                    return null;
                }
                return trimFloat(f);
            }
            default:
                edit.errors.add(field.label() + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.137f198575f3bfad") + field.kind());
                return null;
        }
    }

    /**
     * 跨字段的提醒（<b>不是</b>错误，不阻止写入）。
     *
     * <p>天气那三条最容易让人误判：改了 {@code raining} 但 {@code doWeatherCycle=true}，
     * 世界跑几 tick 后天气自己会变 —— 这不是我们写错了，但玩家会以为写错了。所以明说。
     */
    private void crossChecks() {
        if (this.values.containsKey("Data.raining") || this.values.containsKey("Data.thundering")) {
            this.notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.5a3374e86c203c9a")
                    + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ab25a18ed7f19223"));
        }
        if (this.values.containsKey("Data.Difficulty") && !this.values.containsKey("Data.DifficultyLocked")) {
            this.notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.c491139e26d48812"));
        }
        if (this.values.containsKey("Data.DayTime") && !this.values.containsKey("Data.GameRules.doDaylightCycle")) {
            this.notes.add(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.8fb38794936e546b"));
        }
    }

    /** 面板/日志上的一行"要改什么"。 */
    public String describe() {
        if (this.values.isEmpty()) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.d1be97bd9d18c4b8");
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : this.values.entrySet()) {
            sb.append(ShanhaiLevelDataSpec.label(e.getKey())).append('=')
                    .append(shorten(e.getValue(), 40)).append(' ');
        }
        return sb.toString().trim();
    }

    /** 日志用截断（纯 JDK，不依赖 UI 类；{@code §} 颜色码不计宽）。 */
    static String shorten(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars) + "…";
    }

    /** 日志用的逐条读数（id=值），比 {@link #describe()} 更适合事后对账。 */
    public String describeRaw() {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : this.values.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        }
        return sb.toString().trim();
    }

    /** 全部字段的 why（装配时打进日志；判据会检查每条都点名了"读"与"写回"）。 */
    public static List<String> whyLines() {
        final List<String> out = new ArrayList<>();
        for (ShanhaiLevelDataSpec.Field f : ShanhaiLevelDataSpec.FIELDS) {
            out.add(f.id() + " :: " + f.why());
        }
        return out;
    }

    // ------------------------------------------------------------------ 小工具

    /** {@code true/false/1/0}（大小写不敏感）。不认的返回 {@code null}。 */
    public static Boolean parseBool(String s) {
        if (s == null) {
            return null;
        }
        final String v = s.trim();
        if (v.equalsIgnoreCase("true") || v.equals("1")) {
            return Boolean.TRUE;
        }
        if (v.equalsIgnoreCase("false") || v.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 浮点数的稳定写法（去掉 {@code 20.0} 这种尾零，但保留小数位）。 */
    public static String trimFloat(float f) {
        if (f == Math.rint(f) && Math.abs(f) < 1.0E9F) {
            return String.valueOf((long) f);
        }
        return String.valueOf(f);
    }
}
