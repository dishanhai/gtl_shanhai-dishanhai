package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · <b>玩家生命态的三字段（纯算术，不依赖 Minecraft）</b>。
 *
 * <h2>1. 它回答的是哪一条假设</h2>
 * 用户原话（2026-11-08）：「我感觉我把猫放出来时，<b>玩家可能已经死了，又或者没有血了</b>，
 * 所以才打不开」。
 *
 * <p>现有日志能反驳一半：同一时段里 {@code local_drawn=true} + {@code progress=1.000}
 * + {@code boards_in=1/5~2/5} 同时出现 ⇒ <b>面板确实在画</b>，只是只有 1 块进视野。
 * 但"<b>玩家在死亡态时是否还能看见 / 画面是否被压暗</b>"我们<b>没有字段</b>，所以只能猜。
 *
 * <h2>2. 判据（下次一测就能分开，不用再猜）</h2>
 * <pre>
 *   dead=true 且 local_drawn=true + boards_in&gt;0   ⇒ 死态【不是】阻断因素
 *   dead=true 时 local_drawn=false 或 boards_in=0   ⇒ 死态【确实】是主因
 * </pre>
 * 三个字段同时挂在 {@code visibility} / {@code holo_click} /
 * {@code holo_menu_toggled_by_item} / {@code holo_open_check} 四行上，
 * 以及 {@code /shanhai menu dump} 的 {@code visibility_dump} JSON 的 {@code "client"} 对象里
 * ⇒ 同一条时间线上"开没开 / 画没画 / 在不在视锥里 / 死没死"四个量可以对起来读。
 *
 * <h2>3. 🔴 取值口径（{@code LivingEntity} 的真实成员，javap 核过）</h2>
 * <pre>
 *   hp          = LivingEntity#getHealth()          →  public float getHealth()
 *   dead        = LivingEntity#isDeadOrDying()      →  public boolean isDeadOrDying()
 *   death_time  = LivingEntity#deathTime（字段）     →  public int deathTime
 * </pre>
 * {@code deathTime} 在 <b>{@code LivingEntity}</b> 上（不是 {@code Entity}）——
 * {@code LocalPlayer → Player → LivingEntity} 继承得到，所以客户端读它没有额外的空判。
 *
 * <h2>4. 为什么"没有玩家"要能表达出来</h2>
 * 本工程血规矩「活界面上不许放假数据」：拿不到玩家时<b>不许</b>打 {@code hp=20.00 dead=false}
 * （那会被读成"人好好的"）。此时三个字段写 {@code hp=nan death_time=-1}，
 * JSON 里写成 {@code null} —— "没有读数"与"读数是正常值"必须长得不一样。
 */
public record ShanhaiHoloVitals(float hp, boolean dead, int deathTime) {

    /** 没有玩家 / 没有读数。<b>不是</b>"满血活着"（见类注释 §4）。 */
    public static final ShanhaiHoloVitals NONE =
            new ShanhaiHoloVitals(Float.NaN, false, -1);

    /** 从客户端玩家的三个真实成员取值（唯一取值点；调用方不许自己再拼一遍）。 */
    public static ShanhaiHoloVitals of(float health, boolean deadOrDying, int deathTimeTicks) {
        return new ShanhaiHoloVitals(health, deadOrDying, deathTimeTicks);
    }

    /**
     * 日志里的三字段（<b>字段名固定</b>）：
     * {@code hp=<2 位小数> dead=<true|false> death_time=<int>}。
     *
     * <p>hp 不是有限值（没有玩家 / 读坏了）⇒ 打 {@code hp=nan}，<b>绝不</b>打成 0.00 或 20.00。
     */
    public String line() {
        return "hp=" + hpText() + " dead=" + dead + " death_time=" + deathTime;
    }

    /** hp 的日志文本：有限值 ⇒ 固定 2 位小数（{@code Locale.ROOT}）；否则 {@code nan}。 */
    public String hpText() {
        return Float.isFinite(hp) ? String.format(Locale.ROOT, "%.2f", hp) : "nan";
    }

    /**
     * {@code visibility_dump} 里的 {@code "client"} 对象（字段名固定：
     * {@code hp} / {@code dead} / {@code deathTime}）。
     *
     * <p>hp 非有限值 ⇒ {@code null}（口径与 {@code ShanhaiHoloViewMath#jsonNum} 一致：
     * "没有读数"不许伪装成一个数）。
     */
    public String json() {
        return "{\"hp\":" + (Float.isFinite(hp) ? String.format(Locale.ROOT, "%.2f", hp) : "null")
                + ",\"dead\":" + dead
                + ",\"deathTime\":" + deathTime + "}";
    }

    /**
     * <b>离线自检</b>。返回 {@code null} = 全过。
     *
     * <pre>
     *   V1 满血：hp=20.00 dead=false death_time=0（逐字）
     *   V2 两位小数：hp=0.5 ⇒ 0.50（不是 0.5，也不是 1）
     *   V3 【负对照】hp=19.994 ⇒ 19.99（证明真的在格式化，不是把入参原样拼）
     *   V4 死亡态：hp=0.00 dead=true death_time=20
     *   V5 没有读数：NONE ⇒ hp=nan dead=false death_time=-1（不许出现 0.00/20.00）
     *   V6 JSON：hp 有限 ⇒ 数值；hp=NaN ⇒ null（"没有读数"与"数值"必须可区分）
     *   V7 字段名钉住：line() 必须以 "hp=" 开头且含 " dead=" 与 " death_time="
     * </pre>
     */
    public static String selfTest() {
        final StringBuilder bad = new StringBuilder();
        if (!"hp=20.00 dead=false death_time=0".equals(of(20.0f, false, 0).line())) {
            bad.append("V1 满血格式=").append(of(20.0f, false, 0).line()).append(';');
        }
        if (!"hp=0.50".equals("hp=" + of(0.5f, false, 0).hpText())) {
            bad.append("V2 两位小数=").append(of(0.5f, false, 0).hpText()).append(';');
        }
        if (!"19.99".equals(of(19.994f, false, 0).hpText())) {
            bad.append("V3 负对照 19.994⇒").append(of(19.994f, false, 0).hpText()).append(';');
        }
        if (!"hp=0.00 dead=true death_time=20".equals(of(0.0f, true, 20).line())) {
            bad.append("V4 死亡态=").append(of(0.0f, true, 20).line()).append(';');
        }
        if (!"hp=nan dead=false death_time=-1".equals(NONE.line())) {
            bad.append("V5 无读数=").append(NONE.line()).append(';');
        }
        if (!"{\"hp\":null,\"dead\":false,\"deathTime\":-1}".equals(NONE.json())) {
            bad.append("V6a 无读数 JSON=").append(NONE.json()).append(';');
        }
        if (!"{\"hp\":20.00,\"dead\":true,\"deathTime\":7}".equals(of(20.0f, true, 7).json())) {
            bad.append("V6b JSON=").append(of(20.0f, true, 7).json()).append(';');
        }
        final String l = of(20.0f, false, 0).line();
        if (!l.startsWith("hp=") || !l.contains(" dead=") || !l.contains(" death_time=")) {
            bad.append("V7 字段名漂了=").append(l).append(';');
        }
        return bad.length() == 0 ? null : bad.toString();
    }
}
