package com.shanhai.api.machine;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「为什么一条样板都跑不起来」的原因来源（样板仓室侧）。</b>
 *
 * <h2>它解决什么（用户 2026-10-27 追加二）</h2>
 * 槽声明的类型本机不支持时，Jade 现在显示的是「配方失败原因：<b>输入材料不足</b>」——
 * 用户明确说<b>这是错的提示</b>。两类原因必须分开：
 * <pre>
 *   ① 类型不支持（{@link #shanhaiUnsupportedDeclaredType()} 非 null） ⇒ 「本机不支持该样板声明的配方类型」
 *   ② 类型支持但没料                                            ⇒ 才允许走材料类原因
 *   ③ 未声明类型 + 闸开（{@link #shanhaiUndeclaredBlocked()}）      ⇒ 「样板未声明类型」
 * </pre>
 *
 * <h2>实现方契约</h2>
 * <ul>
 *   <li><b>只读</b>：不写 NBT、不改机器状态、不触发重建；</li>
 *   <li><b>不许每 tick 重扫 NBT</b>：要缓存 + 脏标记（控制器在配方查找失败那一跳问它）；</li>
 *   <li>返回 null / false = 本机没有这一类被拦下的样板（<b>不许瞎报</b>）。</li>
 * </ul>
 */
public interface ShanhaiPatternTypeBlockSource {

    /**
     * 有没有「样板声明了、但本机一个可执行类型都不支持」的样板？
     *
     * @return 那个类型 id（多个时给第一个，稳定顺序）；没有 ⇒ {@code null}
     */
    @Nullable
    String shanhaiUnsupportedDeclaredType();

    /**
     * 有没有「没声明类型」的样板正被闸拦着？（{@code blockAmbiguousPatterns == true} 且有未声明样板）
     */
    boolean shanhaiUndeclaredBlocked();

    /** 一行式证据（进日志；{@code jade=} 段由控制器拼）。 */
    @NotNull
    String shanhaiTypeBlockDescribe();
}
