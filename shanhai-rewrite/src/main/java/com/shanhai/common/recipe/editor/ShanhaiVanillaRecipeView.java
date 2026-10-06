package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.shanhai.ShanhaiMod;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>非 GT 配方（原版合成台 / 熔炉 / 切石机 / 锻造台 …）的统一只读视图</b>。
 *
 * <h2>1. 🔴 为什么必须另起一套，不能复用现有代码</h2>
 * 编辑器现有那条线<b>从底本那一行就把非 GT 挡掉了</b>：
 * {@code ShanhaiRecipeBase.capture} 的第一句是
 * {@code if (!(r instanceof GTRecipe gt) || gt.id == null) continue;}
 * （{@code ShanhaiRecipeBase.java:209-221}），{@code ShanhaiRecipeReverseIndex} 里 5 处扫描循环同样如此。
 * ⇒ 非 GT 配方在编辑器里<b>一条都进不来</b>，而且它们的字段词汇表和 GT 完全不同：
 * <pre>
 *   GT   : inputs / outputs / tickInputs / duration / data.euTier / recipeConditions
 *   原版 : result / pattern / key / ingredients / cookingtime / experience
 * </pre>
 * 所以本视图是一层<b>只读翻译</b>：把一条 {@code Recipe<?>} 摊平成"类型 + 输入 + 产物 + 时间/经验"，
 * 让面板与账本都能用同一套读数说话。
 *
 * <h2>2. 关于"能不能编辑"</h2>
 * {@link #editable()} 为假的两类，<b>列表里照旧能看见，但面板上不给编辑</b>：
 * <ul>
 *   <li>{@code minecraft:crafting_special_*}（13 种）与 {@code minecraft:crafting_decorated_pot}：
 *       它们是 {@code CustomRecipe} 子类，{@code matches()} <b>恒假</b> ——
 *       改了产物数量也<b>不会</b>在工作台生效（它们由别的代码路径调）。假装能改会误导；</li>
 *   <li>认不出具体类的（{@code kind == UNSUPPORTED}）：我们不敢重建它，就不给编辑。</li>
 * </ul>
 */
public final class ShanhaiVanillaRecipeView {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 一条非 GT 配方在编辑上的分类。 */
    public enum Kind {
        /** 有形状的合成（{@code crafting_shaped}）：产物可改，输入 3×3。 */
        SHAPED,
        /** 无形状合成（{@code crafting_shapeless}）。 */
        SHAPELESS,
        /** 烧炼那一族（smelting / blasting / smoking / campfire_cooking）：产物 ＋ 时间 ＋ 经验都可改。 */
        COOKING,
        /** 切石机。 */
        STONECUTTING,
        /** 锻造台"变换"（有产物）。 */
        SMITHING_TRANSFORM,
        /** 锻造台"纹饰"（{@code result} 由模板决定，没有 result 字段）。 */
        SMITHING_TRIM,
        /** 原版"自定义合成"（{@code crafting_special_*} / {@code crafting_decorated_pot}）：<b>不可编辑</b>。 */
        SPECIAL,
        /** 认不出来的类型：<b>不可编辑</b>。 */
        UNSUPPORTED
    }

    /** 这条配方的 id。 */
    public final ResourceLocation id;
    /** 配方类型 id（{@code minecraft:crafting_shaped} 这种）。 */
    public final ResourceLocation typeId;
    /** 分类。 */
    public final Kind kind;
    /** 活配方对象本身（只读引用，绝不就地改 —— 原版配方字段全 final）。 */
    public final Recipe<?> raw;
    /**
     * 🆕 第 12 刀：这条配方的具体类<b>不是</b>原版那 9 个类之一，而是<b>别的 mod 自己写的类</b>
     * （多半继承自原版那几家）。
     *
     * <p>🔴 为什么它必须存在：{@link ShanhaiVanillaRecipeRebuild} 是按<b>原版的 public 构造器</b>重建的
     * —— 对一条"某个 mod 自己的 ShapedRecipe 子类"，重建出来的是<b>原版 ShapedRecipe</b>
     * ⇒ 类型从那个 mod 的类型<b>变成</b> {@code minecraft:crafting} ⇒
     * 用户以为只是改了个产物数量，实际上那条配方<b>被换成了另一种配方</b>（本工程最不能接受的那类静默改错）。
     * ⇒ 这种一律判 {@link Kind#UNSUPPORTED}（列表里看得见、标"只读"），<b>不硬塞</b>。
     */
    public final boolean modSubclass;

    /** 输入那一份（原样引用；shaped 的是 3×3 摊平后的格子，空格子也在里面）。 */
    public final List<Ingredient> inputs;
    /** 产物（没有产物的类型给 {@link ItemStack#EMPTY}）。 */
    public final ItemStack result;
    /** 烧炼时间（tick）。只有 {@link Kind#COOKING} 有意义。 */
    public final int cookingTime;
    /** 经验。只有 {@link Kind#COOKING} 有意义。 */
    public final double experience;

    /** shaped 的宽/高（其它类型 -1）。 */
    public final int width;
    public final int height;
    /** shaped 的形状行（其它类型空表）；一行一个字符串，空格用 ' '。 */
    public final List<String> pattern;

    private ShanhaiVanillaRecipeView(ResourceLocation id, ResourceLocation typeId, Kind kind, Recipe<?> raw,
                                     List<Ingredient> inputs, ItemStack result,
                                     int cookingTime, double experience,
                                     int width, int height, List<String> pattern) {
        this.id = id;
        this.typeId = typeId;
        this.kind = kind;
        this.raw = raw;
        this.modSubclass = !isExactVanillaClass(raw);
        this.inputs = inputs;
        this.result = result;
        this.cookingTime = cookingTime;
        this.experience = experience;
        this.width = width;
        this.height = height;
        this.pattern = pattern;
    }

    // ------------------------------------------------------------------ 构造函数

    /** 把一条 {@code Recipe<?>} 摊平成视图；认不出的类型给 {@link Kind#UNSUPPORTED}（仍然返回一个对象，不返回 null）。 */
    public static ShanhaiVanillaRecipeView of(Recipe<?> r) {
        if (r == null) {
            return null;
        }
        final ResourceLocation id = r.getId();
        final ResourceLocation typeId = r.getType() == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        final List<Ingredient> ins = new ArrayList<>();
        try {
            final NonNullList<Ingredient> list = r.getIngredients();
            if (list != null) {
                ins.addAll(list);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} vanilla_view_ingredients_failed id={} err={}", PREFIX, id, t.toString());
        }
        ItemStack out = ItemStack.EMPTY;
        final Kind kind = classify(r);
        // 🔴 第 12 刀：`getResultItem(null)` 对**没有固定产物**的那几类会直接 NPE
        //    （用户实例日志实测：smithing_trim 那 16 条每查一次就打 16 行 WARN
        //     `vanilla_view_result_failed … p_266948_ is null`）。
        //    ⇒ 只对"本来就有产物"的分类去取，别的如实留空。
        if (kind == Kind.SHAPED || kind == Kind.SHAPELESS || kind == Kind.COOKING
                || kind == Kind.STONECUTTING || kind == Kind.SMITHING_TRANSFORM) {
            try {
                final ItemStack st = r.getResultItem(null);
                if (st != null) {
                    out = st;
                }
            } catch (Throwable t) {
                // 有些类型的 getResultItem 需要 RegistryAccess；服务端上拿 null 也能过，
                // 拿不到就如实留空（绝不编一个假的产物）
                ShanhaiMod.LOGGER.debug("{} vanilla_view_result_failed id={} err={}", PREFIX, id, t.toString());
            }
        }
        int ct = -1;
        double xp = -1.0;
        if (r instanceof AbstractCookingRecipe c) {
            ct = c.getCookingTime();
            xp = c.getExperience();
        }
        int w = -1;
        int h = -1;
        List<String> pattern = List.of();
        if (r instanceof ShapedRecipe s) {
            w = s.getWidth();
            h = s.getHeight();
            pattern = toPattern(s);
        }
        return new ShanhaiVanillaRecipeView(id, typeId, kind, r, List.copyOf(ins), out,
                ct, xp, w, h, pattern);
    }

    /**
     * 按<b>具体类</b>分类（不是按 typeId —— mod 可以注册自己的 RecipeType 却用原版的 Recipe 类）。
     *
     * <p>🆕 第 12 刀：再加上一条 <b>{@link #isExactVanillaClass} 闸门</b>。
     * 只有"具体类就是原版那 9 个类之一"的才给出可编辑的分类 ——
     * 因为重建走的是<b>原版构造器</b>，对"mod 自己的子类"重建出的会是原版那个类，
     * 类型会从那个 mod 的类型<b>变成</b> {@code minecraft:crafting}。
     * 这正是任务书那句「表达不了的 ⇒ 保持只读，别硬塞」在代码里的落点。
     */
    private static Kind classify(Recipe<?> r) {
        final boolean exact = isExactVanillaClass(r);
        if (r instanceof ShapedRecipe) {
            return exact ? Kind.SHAPED : Kind.UNSUPPORTED;
        }
        if (r instanceof ShapelessRecipe) {
            return exact ? Kind.SHAPELESS : Kind.UNSUPPORTED;
        }
        if (r instanceof AbstractCookingRecipe) {
            return exact ? Kind.COOKING : Kind.UNSUPPORTED;
        }
        final String cn = r.getClass().getName();
        if (cn.endsWith("StonecutterRecipe")) {
            return exact ? Kind.STONECUTTING : Kind.UNSUPPORTED;
        }
        if (cn.endsWith("SmithingTransformRecipe")) {
            return exact ? Kind.SMITHING_TRANSFORM : Kind.UNSUPPORTED;
        }
        if (cn.endsWith("SmithingTrimRecipe")) {
            return exact ? Kind.SMITHING_TRIM : Kind.UNSUPPORTED;
        }
        // 🔴 原版"自定义合成"：`crafting_special_*` 都是 CustomRecipe 的子类，
        //    它们的 matches() 恒假 ⇒ 改了不会在工作台生效 ⇒ 列表里可见、标"不可编辑"。
        if (r.isSpecial()) {
            return Kind.SPECIAL;
        }
        return Kind.UNSUPPORTED;
    }

    /**
     * 🆕 第 12 刀：具体类是不是<b>原版那 9 个类本身</b>（不是它们的子类）。
     *
     * <p>用 {@code ==} 比类对象，不是 {@code instanceof} —— 目的恰恰是<b>把子类排除掉</b>。
     */
    public static boolean isExactVanillaClass(Recipe<?> r) {
        if (r == null) {
            return false;
        }
        final Class<?> c = r.getClass();
        return c == ShapedRecipe.class
                || c == ShapelessRecipe.class
                || c == net.minecraft.world.item.crafting.SmeltingRecipe.class
                || c == net.minecraft.world.item.crafting.BlastingRecipe.class
                || c == net.minecraft.world.item.crafting.SmokingRecipe.class
                || c == net.minecraft.world.item.crafting.CampfireCookingRecipe.class
                || c == net.minecraft.world.item.crafting.StonecutterRecipe.class
                || c == net.minecraft.world.item.crafting.SmithingTransformRecipe.class
                || c == net.minecraft.world.item.crafting.SmithingTrimRecipe.class;
    }

    private static List<String> toPattern(ShapedRecipe s) {
        try {
            final NonNullList<Ingredient> g = s.getIngredients();
            final int w = s.getWidth();
            final int h = s.getHeight();
            if (w <= 0 || h <= 0 || g == null || g.size() < w * h) {
                return List.of();
            }
            final List<String> out = new ArrayList<>(h);
            for (int y = 0; y < h; y++) {
                final StringBuilder sb = new StringBuilder(w);
                for (int x = 0; x < w; x++) {
                    // 键只用 a..i 这一组（原版的 pattern 键是给 key 表用的字符，这里只为"看得见形状"）
                    sb.append(g.get(y * w + x).isEmpty() ? ' ' : (char) ('a' + (y * w + x) % 9));
                }
                out.add(sb.toString());
            }
            return List.copyOf(out);
        } catch (Throwable t) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ 判定 / 读数

    /** 产物能不能改。 */
    public boolean canEditResult() {
        return kind == Kind.SHAPED || kind == Kind.SHAPELESS || kind == Kind.COOKING
                || kind == Kind.STONECUTTING || kind == Kind.SMITHING_TRANSFORM;
    }

    /** 烧炼时间 / 经验能不能改。 */
    public boolean canEditCooking() {
        return kind == Kind.COOKING;
    }

    /** 这条配方整体能不能编辑（面板据此决定要不要画编辑控件 / 直接给一句说明）。 */
    public boolean editable() {
        return canEditResult() || canEditCooking();
    }

    /**
     * 面板上显示的那一行说明（<b>不可编辑时说明为什么</b>，绝不静默地什么都不给）。
     */
    public String whyNotEditable() {
        if (editable()) {
            return "";
        }
        if (kind == Kind.SPECIAL) {
            return "§e这条是原版的「自定义合成」（crafting_special / 装饰陶罐）："
                    + "它由游戏代码直接判定，§c改了产物也不会生效§e，所以这里不提供编辑。";
        }
        if (kind == Kind.SMITHING_TRIM) {
            return "§e锻造台「纹饰」没有固定产物（产物由纹饰模板决定），本版不提供编辑。";
        }
        if (modSubclass) {
            // 🆕 第 12 刀：说清"为什么只读"，而不是一句含糊的"不支持"
            return "§e这是§f某个 mod 自己的配方类§e（不是原版那几种），"
                    + "重建会让它变成另一种配方 ⇒ §c本版只读§e。";
        }
        return "§e这个配方类型本版还不支持编辑（只读显示）。";
    }

    /** 类型的中文名（对话栏 / 列表用）。 */
    public String typeLabel() {
        return typeLabel(typeId);
    }

    /** 输入的代表性物品（每格取第一个候选；取不到给 {@code EMPTY}）。 */
    public List<ItemStack> representativeInputs() {
        final List<ItemStack> out = new ArrayList<>(inputs.size());
        for (Ingredient in : inputs) {
            out.add(representative(in));
        }
        return out;
    }

    /** 一个 {@link Ingredient} 的代表物品（取第一个非空的候选）。 */
    public static ItemStack representative(Ingredient in) {
        if (in == null || in.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            for (ItemStack st : in.getItems()) {
                if (st != null && !st.isEmpty()) {
                    return st.copy();
                }
            }
        } catch (Throwable ignored) {
            // getItems() 在标签没绑定时会抛 —— 如实返回空
        }
        return ItemStack.EMPTY;
    }

    /**
     * 配方类型 → <b>中文名</b>。
     *
     * <h4>🔴 名字是从游戏自己的语言表里取的，不是我编的</h4>
     * 原版<b>没有</b>"配方类型"的语言条目（{@code minecraft:crafting_shaped} 在 lang 里查不到），
     * 所以这里用<b>这个配方类型对应的那个工作台方块</b>的名字 ——
     * {@code block.minecraft.crafting_table} ⇒「工作台」、{@code block.minecraft.furnace} ⇒「熔炉」。
     * 这两条都在原版 {@code en_us.json / zh_cn.json} 里，<b>不是硬编码的中文串</b>。
     * 查不到就退回类型 id（本工程纪律：宁可缺，不可假）。
     */
    public static String typeLabel(ResourceLocation typeId) {
        if (typeId == null) {
            return "?";
        }
        final String key = stationLangKey(typeId);
        if (key != null) {
            try {
                final String live = Component.translatable(key).getString();
                if (live != null && !live.isEmpty() && !key.equals(live)) {
                    return ShanhaiTextParserStrip(live);
                }
            } catch (Throwable ignored) {
                // 语言表没就绪 ⇒ 走下面那条查表路
            }
            final String zh = com.shanhai.common.text.ShanhaiLangLookup.zhCn(key);
            if (zh != null && !zh.isEmpty()) {
                return ShanhaiTextParserStrip(zh);
            }
        }
        return typeId.toString();
    }

    /** 别名，方便调用方按"类型"取值。 */
    public static String typeLabelOf(String typeId) {
        return typeId == null ? "?" : typeId;
    }

    /** 剥掉本工程的 {@code &$…-} 风格码（那套码贴到 LabelWidget 上会被原样画出来）。 */
    private static String ShanhaiTextParserStrip(String s) {
        try {
            return com.shanhai.common.text.ShanhaiTextParser.stripStyleCode(s);
        } catch (Throwable t) {
            return s;
        }
    }

    /**
     * 这个配方类型对应的"在哪儿做"的方块名 key。
     * <p>只覆盖 {@code minecraft:} 自己的那 8 个基础类型；其它命名空间一律返回 {@code null}
     * （⇒ 退回类型 id），<b>不猜</b>。
     */
    public static String stationLangKey(ResourceLocation typeId) {
        if (typeId == null || !"minecraft".equals(typeId.getNamespace())) {
            return null;
        }
        return switch (typeId.getPath()) {
            case "crafting", "crafting_shaped", "crafting_shapeless", "crafting_special_armordye",
                 "crafting_special_bannerduplicate", "crafting_special_bookcloning",
                 "crafting_special_firework_rocket", "crafting_special_firework_star",
                 "crafting_special_firework_star_fade", "crafting_special_mapcloning",
                 "crafting_special_mapextending", "crafting_special_repairitem",
                 "crafting_special_shielddecoration", "crafting_special_shulkerboxcoloring",
                 "crafting_special_suspiciousstew", "crafting_special_tippedarrow",
                 "crafting_decorated_pot" -> "block.minecraft.crafting_table";
            case "smelting", "blasting" -> "block.minecraft.furnace";
            case "smoking" -> "block.minecraft.smoker";
            case "campfire_cooking" -> "block.minecraft.campfire";
            case "stonecutting" -> "block.minecraft.stonecutter";
            case "smithing", "smithing_transform", "smithing_trim" -> "block.minecraft.smithing_table";
            default -> null;
        };
    }

    /**
     * JEI 那个分类的 uid（<b>和配方类型 id 不是一回事</b>）。
     *
     * <p>javap 实证（JEI 15.49.0.188，{@code mezz.jei.api.constants.RecipeTypes} 静态初始化）：
     * 原版合成那一页是 {@code minecraft:crafting}、熔炉那一页是 {@code minecraft:furnace} ——
     * 而配方类型 id 分别是 {@code minecraft:crafting_shaped} / {@code minecraft:smelting}。
     * ⇒ 发给客户端的补丁必须带上<b>分类 uid</b>，否则 {@code jei_sync} 那行永远不会命中
     * （{@code ShanhaiJeiRecipePatches.logOnce} 是按 uid 比的）。
     */
    public static String jeiUidOf(ResourceLocation typeId) {
        if (typeId == null || !"minecraft".equals(typeId.getNamespace())) {
            return null;
        }
        final String p = typeId.getPath();
        if (p.startsWith("crafting")) {
            return "minecraft:crafting";
        }
        return switch (p) {
            case "smelting" -> "minecraft:furnace";
            case "blasting" -> "minecraft:blasting";
            case "smoking" -> "minecraft:smoking";
            case "campfire_cooking" -> "minecraft:campfire";
            case "stonecutting" -> "minecraft:stonecutting";
            case "smithing", "smithing_transform", "smithing_trim" -> "minecraft:smithing";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 覆盖层要用的小工具

    /**
     * 产物 → 覆盖层 {@code recipe.set("result", …)} 认的那个 JSON。
     *
     * <p>🔴 <b>第 12 刀订正：形状必须是数据包那种写法 {@code {"item":"…","count":N,"nbt":{…}}}</b>，
     * 不是 {@code {"id":"…","Count":N}}。
     *
     * <p>为什么（这一条是自检当场抓出来的）：原来这里走的是 {@code ItemStack.CODEC}，
     * 它编出来的是 <b>{@code {"id":…,"Count":…}}</b>（那是"存档/NBT 那一套"的键名）。
     * 而我们这份 JSON 有两个下游，<b>都按原版【配方 JSON】的键名读</b>：
     * <ul>
     *   <li>覆盖层脚本把它交给 KubeJS 的 {@code recipe.set("result", …)}（配方 JSON 的词汇表）；</li>
     *   <li>我们自己新建配方时的空壳 {@code newRecipeSkeleton} 写的也是 {@code {"item","count"}}；</li>
     *   <li>{@code ShanhaiRecipeOverrideStore} 里那份 {@code op=add} 的 {@code recipe} 也照抄数据包格式。</li>
     * </ul>
     * 实证：第 12 刀冒烟第一局 {@code case=vanilla_new_edit_kept_add ok=false … recipe.result.count=-1}
     * —— 条目还是 {@code op=add}（P0 那半条已经对了），但并进去的那份 {@code result} 里
     * <b>根本没有 {@code count} 这个键</b>，只有 {@code Count}。
     * <p>读取侧（{@link ShanhaiVanillaRecipeOps#parseResult}）两种形状都认，所以换形状不会让旧文件读不出来。
     */
    public static JsonObject resultJson(ItemStack stack) {
        final JsonObject out = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        final ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return out;
        }
        out.addProperty("item", key.toString());
        if (stack.getCount() != 1) {
            out.addProperty("count", stack.getCount());
        }
        if (stack.hasTag() && stack.getTag() != null) {
            try {
                // NBT → JSON：走 CompoundTag 自己的编解码器（不手拼、不用任何已弃用 API）
                final var enc = net.minecraft.nbt.CompoundTag.CODEC
                        .encodeStart(JsonOps.INSTANCE, stack.getTag());
                final var r = enc.result();
                if (r.isPresent() && r.get().isJsonObject()) {
                    out.add("nbt", r.get());
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} vanilla_result_nbt_failed item={} err={}", PREFIX,
                        key, t.toString());
            }
        }
        return out;
    }

    /** 输入那一份转成 JSON 数组（诊断 / 卡片用；本版不写回配方）。 */
    public JsonArray inputsJson() {
        final JsonArray arr = new JsonArray();
        for (Ingredient in : inputs) {
            if (in == null || in.isEmpty()) {
                arr.add(new JsonObject());
                continue;
            }
            try {
                arr.add(in.toJson());
            } catch (Throwable t) {
                arr.add(new JsonObject());
            }
        }
        return arr;
    }

    /** 一行诊断读数（日志/自检共用）。 */
    public String statsLine() {
        return "id=" + id + " type=" + typeId + " kind=" + kind
                + " in=" + inputs.size() + " out=" + (result.isEmpty() ? "(empty)"
                : BuiltInRegistries.ITEM.getKey(result.getItem()) + " x" + result.getCount())
                + " cook=" + cookingTime + " xp=" + experience
                + " editable=" + editable();
    }

    @Override
    public String toString() {
        return "VanillaView{" + statsLine() + "}";
    }
}
