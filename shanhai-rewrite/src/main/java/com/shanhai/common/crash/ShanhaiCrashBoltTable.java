package com.shanhai.common.crash;

import com.shanhai.ShanhaiMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 16 件「运行时异常投掷物」的<b>唯一事实表</b>（序号 → 物品 id / 异常类 / 崩溃报告 Description / 真触发方式）。
 *
 * <h2>一、为什么要有这张表</h2>
 * 投掷物实体只带一个 {@code int}（异常序号，同步字段）—— 渲染要用它找到物品、崩端要用它找到
 * 「该用哪种异常真崩」。把三样东西（id / 异常类名 / Description 逐字文案）放在**一处**，
 * 是为了让「序号 ↔ 物品 ↔ 说法」的对应关系可以被一条 grep 核对，而不是散落在物品类、实体类、
 * 崩端类三个地方各写一遍（散着写必然会在第 N 次改动时错位）。
 *
 * <h2>二、序号的顺序 = {@code ShanhaiItems} 里 16 个字段的声明顺序</h2>
 * 也就是 {@code id-plan.json} 的原序（{@code ex_nullpointerexception} 在前、
 * {@code ex_outofmemoryerror} 在末）。好处：序号 0 到 15 与源码里 16 行
 * {@code = crashBolt("ex_…", n)} 逐行对齐，一眼能对；
 * <b>不</b>按异常的字母序排（那样序号与源码顺序无关，反而更容易错位）。
 *
 * <h2>三、Description 是「逐字」的</h2>
 * 这 16 句是需求里点名的文案，一个标点都不能改：它们会原样进
 * {@code crash-reports/crash-<时间>-server.txt} 的 {@code Description:} 一行
 * （取证见 {@code temp/reality-items/报告.md} §4.2：{@code ReportedException} 里的
 * {@code CrashReport} 会被 {@code MinecraftServer#constructOrExtractCrashReport} 原样提取）。
 * 唯一例外是 SQLException —— 它是<b>合成</b>的（本机没有 JDBC/数据库，做不到真触发），
 * 所以它的 Description 末尾按需求加 {@code (synthetic)}。
 */
public final class ShanhaiCrashBoltTable {

    /** 16 件（唯一事实：这个数字在两个地方被用到 —— 创造栏 16 行、本条数）。 */
    public static final int COUNT = 16;

    /** SQLException 那一件的序号（只有它的 Description 带 {@code (synthetic)} 后缀）。 */
    public static final int INDEX_SQL = 5;

    /** 合成异常的标注后缀（逐字，需求指定）。 */
    public static final String SYNTHETIC_SUFFIX = "(synthetic)";

    /**
     * 一行 = 一件。
     *
     * @param path           注册 id 的 path（{@code ex_nullpointerexception}），完整 id = {@code shanhai:} + 它
     * @param exceptionClass 崩溃报告里会出现的异常类（<b>不是</b>我们 new 出来的，除了 SQLException）
     * @param description    崩溃报告的 {@code Description:} 一行（逐字）
     * @param trigger        「真触发」方式的一句话说明 —— 同时进崩溃报告的自定义分类，方便事后核对
     */
    public record Row(String path, String exceptionClass, String description, String trigger) {}

    /** 16 行。顺序 = {@code ShanhaiItems} 字段声明顺序 = id-plan.json 原序。 */
    private static final Row[] ROWS = {
            new Row("ex_nullpointerexception", "java.lang.NullPointerException",
                    "这是你故意的。",
                    "真·空引用：读一个从未赋过值的静态字段的方法"),
            new Row("ex_noclassdeffounderror", "java.lang.NoClassDefFoundError",
                    "它连开始都不肯。",
                    "真·类初始化失败：先把同一类的 <clinit> 搞失败一次，再访问一次 ⇒ JVM 自己抛"),
            new Row("ex_exceptionininitializererror", "java.lang.ExceptionInInitializerError",
                    "第一口气就没喘上来。",
                    "真·加载一个 <clinit> 里就抛的类 ⇒ JVM 自己抛"),
            new Row("ex_ioexception", "java.io.IOException",
                    "文件在，读不出来。你早就知道会这样。",
                    "工作线程真读一个不存在的文件（NoSuchFileException 是 IOException）⇒ sneaky throw 搬回主线程"),
            new Row("ex_classnotfoundexception", "java.lang.ClassNotFoundException",
                    "你要找的东西不在这个宇宙里。",
                    "工作线程真 Class.forName 一个不存在的类名 ⇒ sneaky throw 搬回主线程"),
            new Row("ex_sqlexception", "java.sql.SQLException",
                    "这个宇宙没有数据库，但我还是给你准备了一句报错。",
                    "（合成）本机没有 JDBC/数据库，做不到真触发 ⇒ new 一个，Description 末尾注明 (synthetic)"),
            new Row("ex_nosuchelementexception", "java.util.NoSuchElementException",
                    "没了。你还伸手去拿。",
                    "真·空迭代器 next()"),
            new Row("ex_illegalargumentexception", "java.lang.IllegalArgumentException",
                    "这个参数从一开始就不合法，是你递过去的。",
                    "真·给原版 API 非法参数：IntegerProperty.create(name, -1, n) 的 min 必须 ≥ 0"),
            new Row("ex_illegalstateexception", "java.lang.IllegalStateException",
                    "时机不对——这你也知道。",
                    "真·错误状态调原版 API：往加载期结束后已冻结的 BuiltInRegistries.ITEM 里再注册"),
            new Row("ex_unsupportedoperationexception", "java.lang.UnsupportedOperationException",
                    "有些东西就是不能改。你改了。",
                    "真·改不可变集合：List.of(...).add(...)"),
            new Row("ex_midiunavailableexception", "javax.sound.midi.MidiUnavailableException",
                    "这台机器没有音乐，你偏要听。",
                    "工作线程真调 MidiSystem（getSequencer 本机成功，故继续试 getTransmitter，本机真抛 MUE）"),
            new Row("ex_concurrentmodificationexception", "java.util.ConcurrentModificationException",
                    "一边遍历一边改，教科书上写着别这么做。",
                    "真·for-each 遍历 ArrayList 时 add"),
            new Row("ex_indexoutofboundsexception", "java.lang.IndexOutOfBoundsException",
                    "越界了，因为你想看看边界外有什么。",
                    "真·越界取：int[6] 取下标 8"),
            new Row("ex_nosuchmethodexception", "java.lang.NoSuchMethodException",
                    "没有这个方法。你查过了，还是扔了。",
                    "工作线程真 getMethod 一个不存在的方法名 ⇒ sneaky throw 搬回主线程"),
            new Row("ex_classcastexception", "java.lang.ClassCastException",
                    "它从来就不是你以为的那个东西。",
                    "真·强转：把装 String 的 Object 强转成 Integer"),
            new Row("ex_outofmemoryerror", "java.lang.OutOfMemoryError",
                    "内存吃满了。这是你故意的，而且很成功。",
                    "真·吃满堆：循环 new byte[8MiB] 并持有，直到 JVM 自己抛 OOM；崩前先还「保命缓冲」"),
    };

    // ------------------------------------------------------------------ 只读访问

    /** 序号是否合法。 */
    public static boolean valid(int index) {
        return index >= 0 && index < COUNT;
    }

    /** 序号 → 注册 id 的 path（越界给 {@code ""}，不做异常）。 */
    public static String path(int index) {
        return valid(index) ? ROWS[index].path() : "";
    }

    /** 序号 → 完整物品 id（{@code shanhai:ex_…}）。 */
    public static String fullId(int index) {
        return ShanhaiMod.MOD_ID + ":" + path(index);
    }

    /** 序号 → 崩溃报告里会出现的异常类名（{@code java.lang.NullPointerException}）。 */
    public static String exceptionClass(int index) {
        return valid(index) ? ROWS[index].exceptionClass() : "";
    }

    /** 序号 → 「真触发」方式的一句话（进崩溃报告的自定义分类）。 */
    public static String trigger(int index) {
        return valid(index) ? ROWS[index].trigger() : "";
    }

    /**
     * 序号 → 崩溃报告 {@code Description:} 一行（逐字）。
     *
     * <p>只有 {@link #INDEX_SQL} 那一件在末尾多出 {@link #SYNTHETIC_SUFFIX}
     * —— 它是唯一合成出来的异常，必须让读报告的人一眼看出「这条不是真跑出来的」。
     */
    public static String description(int index) {
        if (!valid(index)) {
            return "";
        }
        return index == INDEX_SQL ? ROWS[index].description() + SYNTHETIC_SUFFIX : ROWS[index].description();
    }

    /** 不带后缀的逐字文案（自查器用它对需求原文，见 {@code stage3-audit.mjs}）。 */
    public static String rawDescription(int index) {
        return valid(index) ? ROWS[index].description() : "";
    }

    /**
     * 序号 → 物品本体。
     *
     * <p>⚠️ 走<b>注册表</b>按 id 取，<b>不</b>引用 {@code ShanhaiItems.EX_*} 字段：
     * 物品注册发生在 {@code ShanhaiItems} 的类初始化期，而本表可能在更早（或更晚）被触达；
     * 走注册表就没有任何类初始化顺序问题，也不需要在物品类里反向依赖这张表。
     */
    public static Item item(int index) {
        if (!valid(index)) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.get(new ResourceLocation(ShanhaiMod.MOD_ID, path(index)));
    }

    /** 序号 → 一个该物品的 {@link ItemStack}（投掷物渲染 / {@code getPickupItem} 用）。 */
    public static ItemStack stack(int index) {
        return new ItemStack(item(index));
    }

    private ShanhaiCrashBoltTable() {}
}
