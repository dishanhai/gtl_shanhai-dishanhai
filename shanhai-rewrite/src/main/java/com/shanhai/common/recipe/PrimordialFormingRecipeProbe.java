package com.shanhai.common.recipe;

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.shanhai.ShanhaiMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.event.server.ServerStartedEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 「原初物质定型」的运行期探针 —— <b>把"配方到底进没进配方表"变成一条可 grep 的现查读数</b>。
 *
 * <h2>为什么需要它</h2>
 * 本类型的 2457 条配方原本是<b>数据包配方</b>（{@code data/shanhai/recipes/primordial_forming/*.json}），
 * 2026-10-01 按用户点单<b>迁到了 KubeJS</b>
 * （{@code kubejs\server_scripts\[server_scripts]shanhai_primordial_forming.js}）——
 * <b>本探针不受迁移影响</b>：它读的是 {@code RecipeManager}，数据包配方与 KJS 配方落进的是同一个桶。
 * 这条链上有三个"静默失败"点：
 * <ol>
 *   <li>{@code type} 字段解析不到 serializer ⇒ 该文件<b>被静默丢弃</b>（日志里只留一条 warn）；</li>
 *   <li>{@code gtceu:circuit} / {@code gtceu:sized} 反序列化失败 ⇒ 同上；</li>
 *   <li>配方类型注册没生效 ⇒ 全部落不进这个桶。</li>
 * </ol>
 * 三者都不抛异常，所以"没有报错"与"配方全进来了"在日志上长得一模一样 —— 本工程为此付过账。
 * ⇒ 这里在 {@code ServerStartedEvent} 上<b>现数一遍</b>并打出可比对的数字。
 *
 * <h2>判据（报告 §5 的读数就是这几行）</h2>
 * <pre>
 *   [SHANHAI-PFORM] 原初物质定型 现查=2457 期望=2457 …       ← 新类型条数（双向核的一半）
 *   [SHANHAI-PFORM] 原有两类型 … 压模器=… 流体固化器=…         ← 原有两类型条数（另一一半）
 *   [SHANHAI-PFORM] 模头/模具残留检查：带电路=1129 仍留着模头=0   ← "模头真的被换成电路了"
 * </pre>
 *
 * <h2>⚠️ 口径（别过度解读这条读数）</h2>
 * <ul>
 *   <li>触发时机 = {@code ServerStartedEvent}，用不带优先级的 {@code addListener}（与本工程另两条探针同款），
 *       <b>不保证</b>排在 KubeJS 的 {@code ServerEvents.loaded} 之后 ⇒
 *       <b>原有两类型的条数只作参考口径</b>；新类型那一条不受影响（没有任何脚本会动它）。</li>
 *   <li>探针只<b>读</b>配方表，不写、不改、不注册 —— 与"配方静默消失防线"同款纪律。</li>
 *   <li>本类<b>不被 KubeJS 调用</b>（只由 Forge 总线调），所以"不许重载"那条铁律对它不适用；
 *       但 {@link #onServerStarted(ServerStartedEvent)} 仍然是唯一入口。</li>
 * </ul>
 */
public final class PrimordialFormingRecipeProbe {

    /** 可 grep 的探针前缀。 */
    public static final String PREFIX = "[SHANHAI-PFORM]";

    /** 编程电路（{@code .circuit(n)} 背后那个物品）的注册路径。 */
    private static final String PROGRAMMED_CIRCUIT = "programmed_circuit";

    /**
     * 【游戏内配方编辑器】新建配方时用的 id 片段。
     *
     * <p>实测形态（2026-11-01，实例 {@code config\shanhai\recipe_overrides.json}）：
     * {@code shanhai:primordial_matter_forming/new_recipe_1} —— 也就是「类型路径 + {@code /new_recipe_N}」。
     * 只看路径里有没有这一段，<b>不看类型</b>（本探针只对本类型调用它）。
     *
     * <p>🔴 万一以后编辑器换了命名，这个判据会失效 ⇒ 那时表现为"又误报 ERROR"，
     * 而不是"漏报"（本探针剩下的检查照常跑）—— 这是有意选的失败方向。
     */
    private static final String EDITOR_NEW_RECIPE_MARK = "/new_recipe_";

    /** 这条配方是不是【编辑器新建】的（判据 = id 路径里含 {@code /new_recipe_}）。 */
    private static boolean isEditorAdded(net.minecraft.resources.ResourceLocation id) {
        return id != null && id.getPath().contains(EDITOR_NEW_RECIPE_MARK);
    }

    /**
     * 模头/模具的注册名后缀 —— 两个用途：①检查输入槽"有没有漏换"（模具没被换成电路）；
     * ②检查产出里"有没有产出模具/模头的配方"。<b>不参与任何配方匹配。</b>
     * 两种就够：GTCEu 的模头叫 {@code *_extruder_mold}、模具叫 {@code *_casting_mold}。
     */
    private static final String[] MOLD_SUFFIXES = {"_extruder_mold", "_casting_mold"};

    private PrimordialFormingRecipeProbe() {}

    /** 一条配方的扫描结果。 */
    private static final class Scan {
        int circuitSlots;
        int moldSlots;
        String firstMold = "";
    }

    /** Forge 事件入口（在 {@code ShanhaiMod} 里显式挂到 FORGE 总线）。 */
    public static void onServerStarted(ServerStartedEvent event) {
        int forming = 0;
        int formingWithCircuit = 0;
        int formingWithMoldLeft = 0;
        int formingMakingMoldHead = 0;
        // 2026-11-01（用户选 A）：本类型现在会被【游戏内配方编辑器】追加配方
        //   （实测 2026-11-01：用户在编辑器里加了 3 条测试配方 ⇒ 现查 2463、声明 2460 ⇒ 误报 ERROR）。
        //   所以期望值改成【声明值 + 编辑器追加条数】，不再写死声明值。
        //   判据：编辑器新建的配方 id 形如 shanhai:<类型路径>/new_recipe_N（见实例 config\shanhai\recipe_overrides.json）。
        //   ⚠️ 只认 new_recipe 这个既有命名约定；将来若编辑器改命名，这里要同步（否则又会误报）。
        int formingEditorAdded = 0;
        int press = 0;
        int solidifier = 0;
        final List<String> moldLeftSample = new ArrayList<>();
        final List<String> makeMoldSample = new ArrayList<>();
        final List<String> idSample = new ArrayList<>();
        final List<String> editorAddedSample = new ArrayList<>();
        int total = -1;
        String err = "";
        try {
            final var recipes = event.getServer().getRecipeManager().getRecipes();
            total = recipes.size();
            for (Recipe<?> r : recipes) {
                final RecipeType<?> t = r.getType();
                if (t == ShanhaiRecipeTypes.PRIMORDIAL_MATTER_FORMING) {
                    forming++;
                    if (isEditorAdded(r.getId())) {
                        formingEditorAdded++;
                        if (editorAddedSample.size() < 8) {
                            editorAddedSample.add(String.valueOf(r.getId()));
                        }
                    }
                    if (idSample.size() < 3) {
                        idSample.add(String.valueOf(r.getId()));
                    }
                    if (r instanceof GTRecipe gr) {
                        final Scan s = scanMolds(gr);
                        if (s.circuitSlots > 0) {
                            formingWithCircuit++;
                        }
                        if (s.moldSlots > 0) {
                            formingWithMoldLeft++;
                            if (moldLeftSample.size() < 5) {
                                moldLeftSample.add(r.getId() + "->" + s.firstMold);
                            }
                        }
                        if (outputsMold(gr)) {
                            formingMakingMoldHead++;
                            if (makeMoldSample.size() < 5) {
                                makeMoldSample.add(String.valueOf(r.getId()));
                            }
                        }
                    }
                } else if (t == GTRecipeTypes.FORMING_PRESS_RECIPES) {
                    press++;
                } else if (t == GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES) {
                    solidifier++;
                }
            }
        } catch (Throwable e) {
            err = " (" + e + ")";
        }

        final int declared = ShanhaiRecipeTypes.PRIMORDIAL_MATTER_FORMING_DECLARED_RECIPES;
        final int expected = declared + formingEditorAdded;
        ShanhaiMod.LOGGER.info("{} 原初物质定型 现查={} 期望={}（声明 {} + 编辑器追加 {}） 配方表总条数={}{}；样例 id = {}",
                PREFIX, forming, expected, declared, formingEditorAdded, total, err, idSample);
        if (formingEditorAdded > 0) {
            ShanhaiMod.LOGGER.info("{} 其中【游戏内编辑器追加】{} 条：{}", PREFIX, formingEditorAdded, editorAddedSample);
        }
        ShanhaiMod.LOGGER.info("{} 原有两类型（参考口径，KubeJS 可能晚于本行改动配方表）压模器={} 流体固化器={}",
                PREFIX, press, solidifier);
        ShanhaiMod.LOGGER.info("{} 模头/模具残留检查：带电路={} 仍留着模头={} 产出模头/模具的配方={}{}",
                PREFIX, formingWithCircuit, formingWithMoldLeft, formingMakingMoldHead,
                formingWithMoldLeft > 0 ? (" 残留样例 = " + moldLeftSample) : "");

        if (forming != expected) {
            ShanhaiMod.LOGGER.error("{} 新类型条数与声明值不符：现查={} 期望={}（声明 {} + 编辑器追加 {}） ⇒ "
                            + "配方没有全部进表。本类型现在是【KubeJS 配方】⇒ 优先按这个顺序查："
                            + "① kubejs\\logs\\server.log 里有没有 [SHANHAI-NEWTYPE] 原初物质定型 那一行、"
                            + "它的 failed 是多少（配方脚本报错会让该文件里的配方【一条都建不出来】）；"
                            + "② 那行 ok 数是不是就是这里的现查数（不等 = 部分条建失败）；"
                            + "③ 脚本文件有没有被部署到实例的 kubejs\\server_scripts\\；"
                            + "④ 类型注册有没有生效。"
                            + "逐条对账见 handoff\\outbound\\原初物质定型-迁到KJS.md。",
                    PREFIX, forming, expected, declared, formingEditorAdded);
        } else {
            ShanhaiMod.LOGGER.info("{} 双向核对①：新类型条数 == 声明值+编辑器追加（{} = {} + {}）",
                    PREFIX, expected, declared, formingEditorAdded);
        }
        if (formingWithMoldLeft > 0) {
            ShanhaiMod.LOGGER.error("{} 有配方【没把模头/模具换成电路】：{} 条。样例 = {}",
                    PREFIX, formingWithMoldLeft, moldLeftSample);
        } else {
            ShanhaiMod.LOGGER.info("{} 双向核对②：新类型里没有一条还留着模头/模具（0 条残留）", PREFIX);
        }
        if (formingMakingMoldHead > 0) {
            ShanhaiMod.LOGGER.error("{} 🔴 新类型里还有【产出模头/模具】的配方 {} 条 —— "
                            + "判据 = 产出是 gtceu:*_extruder_mold 或 gtceu:*_casting_mold（与离线生成器同口径），"
                            + "用户拍板「做模头/模具的配方不放进来」（v3 换源后应剔除 0 条）⇒ 这是剔除判据漏了。样例 = {}",
                    PREFIX, formingMakingMoldHead, makeMoldSample);
        } else {
            ShanhaiMod.LOGGER.info("{} 双向核对③：新类型里没有任何【产出模头/模具】的配方（0 条）", PREFIX);
        }
    }

    /**
     * 这条配方的<b>产出</b>里有没有 GTCEu 的模头/模具
     * （{@code gtceu:*_extruder_mold} 或 {@code gtceu:*_casting_mold}，判据 = {@link #MOLD_SUFFIXES}）。
     *
     * <p>🔴 2026-10-01 收紧（只补漏掉的后缀，判据方向不变）：原实现只认 {@code _extruder_mold}，
     * <b>拦不住"产出 {@code *_casting_mold}"</b>——而离线生成器
     * （{@code kubejs\_generators\gen_pf_forming.js}，闸门 ②）用的剔除法则是 {@code /_mold$/}，
     * <b>两个族都算</b> ⇒ 运行期探针比离线闸门松一档，两边口径不一致。
     * 这里改成复用同一个后缀表 ⇒ <b>只会更严，不会更松</b>（多报 <=> 离线闸门本来就会剔除的那种配方）。
     * 只读；异常就地吞掉。
     */
    private static boolean outputsMold(GTRecipe recipe) {
        try {
            final List<Content> contents = recipe.getOutputContents(ItemRecipeCapability.CAP);
            if (contents == null) {
                return false;
            }
            for (Content c : contents) {
                if (!(c.content instanceof Ingredient ing)) {
                    continue;
                }
                for (ItemStack st : ing.getItems()) {
                    if (st == null || st.isEmpty()) {
                        continue;
                    }
                    final ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
                    if (id == null) {
                        continue;
                    }
                    final String path = id.getPath();
                    for (String suf : MOLD_SUFFIXES) {
                        if (path.endsWith(suf)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            return false;
        }
        return false;
    }

    /**
     * 扫一条配方的<b>不消耗物品输入</b>：数出几个是编程电路、几个还是模头/模具。
     * 只读；任何一步抛异常都就地吞掉（探针不许把服务端拖下水）。
     */
    private static Scan scanMolds(GTRecipe recipe) {
        final Scan out = new Scan();
        List<Content> contents;
        try {
            contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        } catch (Throwable e) {
            return out;
        }
        if (contents == null) {
            return out;
        }
        for (Content c : contents) {
            if (c.chance != 0) {
                continue;                                   // 只看不消耗槽 —— 那才是"模头/模具"的位置
            }
            if (!(c.content instanceof Ingredient ing)) {
                continue;
            }
            final ItemStack[] stacks;
            try {
                stacks = ing.getItems();
            } catch (Throwable e) {
                continue;
            }
            for (ItemStack st : stacks) {
                if (st == null || st.isEmpty()) {
                    continue;
                }
                final ResourceLocation id = BuiltInRegistries.ITEM.getKey(st.getItem());
                if (id == null) {
                    continue;
                }
                final String path = id.getPath();
                if (PROGRAMMED_CIRCUIT.equals(path)) {
                    out.circuitSlots++;
                    continue;
                }
                for (String suf : MOLD_SUFFIXES) {
                    if (path.endsWith(suf)) {
                        out.moldSlots++;
                        if (out.firstMold.isEmpty()) {
                            out.firstMold = id.toString();
                        }
                        break;
                    }
                }
            }
        }
        return out;
    }
}
