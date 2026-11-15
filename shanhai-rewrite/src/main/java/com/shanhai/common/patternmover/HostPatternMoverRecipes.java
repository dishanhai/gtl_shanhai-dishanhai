package com.shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「这台模块<b>支持</b>哪些配方类型」的<b>唯一口径</b>（纯核，不 import 任何 MC/GT 类）。
 *
 * <h2>一、为什么不能直接用 {@code IRecipeLogicMachine#getRecipeTypes()}</h2>
 * 用户实测原话（2026-11-08）：
 * <blockquote>
 *   在没有跨配方种类并行的老主机上使用时，它把模块没有选择的配方类型的配方也放进去了
 * </blockquote>
 * 根因：{@code getRecipeTypes()} 是模块<b>声明</b>的全部类型，而<b>老主机上的模块同一时刻只跑一个</b>
 * （{@code IRecipeLogicMachine#getRecipeType()} = {@code recipeTypes[activeRecipeType]}）。
 * 声明里其余那些类型，样板放进去了也永远不会被跑到。
 *
 * <h2>二、有效类型集（唯一判据）</h2>
 * <pre>
 *   授予跨配方种类并行（PrimordialOmegaEngineMachine#grantsCrossTypeParallel() == true，目前只有归墟）
 *       ⇒ "支持" = 声明的全部 getRecipeTypes()
 *   否则（老主机 / 认不出主机）
 *       ⇒ "支持" = 只有当前选中的那一个类型 getRecipeType()
 * </pre>
 * ⚠️ 读不到当前选中的类型（{@code getRecipeType()} 为 {@code null}，机器没成型 / 被锁）时：
 * <b>声明集恰好 1 个</b>才用它；否则返回空集 —— <b>宁可不放，也不猜</b>
 * （猜错就是"喂给用不上的类型"，与多义不写同一个红线）。
 *
 * <h2>三、这套集合同时用于两处（不许各写一份）</h2>
 * <ol>
 *   <li><b>放置分配</b>：{@link HostPatternMoverPlacer.Target#supportsType} 背后就是它
 *       ⇒ 类型不在集合里 ⇒ {@code SKIP_NO_MODULE}（面板上显示成「无对应模块/当前类型不符」）；</li>
 *   <li><b>通配符自动识别</b>：老主机那条「用它所属模块的配方类型」，用的就是这<b>一个</b>。</li>
 * </ol>
 */
public final class HostPatternMoverRecipes {

    private HostPatternMoverRecipes() {}

    /**
     * 算出「有效类型集」。
     *
     * @param crossTypeParallel 这台模块的宿主是否授予跨配方种类并行
     *                          （{@code PrimordialOmegaEngineMachine#grantsCrossTypeParallel()}）
     * @param declared          模块声明的全部类型 id（{@code getRecipeTypes()} 的 {@code registryName}）
     * @param active            模块当前选中的类型 id（{@code getRecipeType().registryName}；读不到传 {@code ""}）
     */
    public static Set<String> effective(boolean crossTypeParallel, Collection<String> declared, String active) {
        final Set<String> all = clean(declared);
        if (crossTypeParallel) {
            // 跨配方种类并行 ⇒ 声明里任何一个都能被跑到
            return all;
        }
        final String now = norm(active);
        // ⚠️ 选中的类型必须在【声明表】里才认：被锁 / 未成型时 getRecipeType() 可能给出
        //    一个不在声明表里的 dummy 类型（本工程在 ShanhaiQuickUploadMultiTypeMixin 里记过这个坑）。
        //    那种值拿去当"唯一支持的类型"会变成"什么都放不进" ⇒ 这里不算数（落回下面的兜底）。
        if (!now.isEmpty() && (all.isEmpty() || all.contains(now))) {
            return Set.of(now);
        }
        // 读不到（或在声明表之外）：只在"声明里恰好 1 个"时用它（这不是猜，是唯一的那个）
        return all.size() == 1 ? Set.of(all.iterator().next()) : Set.of();
    }

    /** 这一块样板能不能被这台模块跑（{@code ""} ⇒ 永远不能）。 */
    public static boolean supports(Collection<String> effectiveTypes, String type) {
        final String t = norm(type);
        if (t.isEmpty() || effectiveTypes == null) {
            return false;
        }
        for (String candidate : effectiveTypes) {
            if (t.equals(norm(candidate))) {
                return true;
            }
        }
        return false;
    }

    /** 一行式读数（日志/断言用）。 */
    public static String describe(boolean crossTypeParallel, Collection<String> declared, String active) {
        return "crossType=" + crossTypeParallel + " declared=" + clean(declared).size()
                + " active='" + norm(active) + "' effective=" + effective(crossTypeParallel, declared, active);
    }

    private static Set<String> clean(Collection<String> values) {
        final Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                final String s = norm(v);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim();
    }

    /** 便于把 {@code Set} 变回可同步的 {@code List}（面板/日志用，顺序 = 有效集顺序）。 */
    public static List<String> toList(Collection<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }
}
