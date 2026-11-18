package com.dishanhai.gt_shanhai.client.saveedit;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.saveedit.ShanhaiSaveEditAdvancementUi;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.Optional;

/**
 * 成就名字的<b>客户端</b>来源：真 {@code display} → 配方产物 → 交给下一层（语言表 → {@code id〔技术成就〕}）。
 *
 * <h2>一、为什么必须换掉"纯 lang 键猜测"</h2>
 * 旧实现是「成就 id → 猜翻译键 {@code advancements.<path>.title} → 查语言表」。
 * 但数据包型成就（{@code <mod>:recipes/…}，原版 1161 条里绝大多数、mods 1820 条）
 * <b>根本没有 {@code display}</b>：它们的 JSON 里只有 {@code criteria} + {@code rewards}，
 * 语言表里也不存在 {@code advancements.recipes.*.title}（0 条）⇒ 旧实现对这些行<b>必然</b>
 * 拼出 {@code id〔无译名〕}，而它们占列表的 93.3%。
 *
 * <h2>二、三条回退顺序（每一条都有自己的 javap 证据）</h2>
 * <ol>
 *   <li><b>真 display</b>：{@code Minecraft#getConnection()} → {@code ClientPacketListener#getAdvancements()}
 *       （{@code ClientAdvancements}）→ {@code getAdvancements()}（{@code AdvancementList}）→
 *       {@code get(ResourceLocation)} → {@code Advancement#getDisplay()}（<b>可 null</b>）→
 *       {@code DisplayInfo#getTitle()} → {@code getString()}。</li>
 *   <li><b>配方产物</b>（display 为 null，或标题只是裸键）：{@code Advancement#getRewards()} →
 *       {@code AdvancementRewards#getRecipes()}（{@code ResourceLocation[]}）→
 *       {@code RecipeManager#byKey(ResourceLocation)} → {@code Recipe#getResultItem(RegistryAccess)} →
 *       {@code ItemStack#getHoverName()} → {@code getString()}。
 *       客户端那份 {@code RecipeManager} 来自 {@code ClientLevel#getRecipeManager()}（原版在
 *       {@code ClientPacketListener#handleUpdateRecipes} 里灌满，所以配方在本机就有）。</li>
 *   <li><b>两条都不行 ⇒ 返回 {@code null}</b>，由 {@link ShanhaiSaveEditAdvancementUi} 落到
 *       「语言表 → {@code id〔技术成就〕}」。本类<b>不</b>自己拼记号 —— 记号只有一处定义。</li>
 * </ol>
 *
 * <h2>三、🔴 服务端不会加载本类</h2>
 * 本类<b>不被任何公共代码引用</b>（只有 {@code @Mod.EventBusSubscriber} 这条注解登记它），
 * 而 Forge 的分发门禁在<b>字节码</b>里：{@code AutomaticEventSubscriber.lambda$inject$6}
 * 先做 {@code EnumSet.contains(FMLEnvironment.dist)}，不匹配就 {@code ifeq} 直接跳过，
 * 之后的 {@code Class.forName(clazz, true, loader)}（{@code initialize=true}）才真正加载类。
 * 专服上本类的 dist 集合是 {@code {CLIENT}} ⇒ 根本走不到 {@code forName}。
 *
 * <h2>四、为什么不缓存</h2>
 * 行按钮的 {@code TextTexture} supplier 每帧调一次（一页最多 7 行）。这里刻意<b>不</b>缓存：
 * 缓存会让 {@code /reload} 之后显示旧名字，而"名字对了没有"正是这次要修的东西。
 * 每帧的实际开销 = 一次 {@code HashMap} 查 {@code Advancement} + 一次 {@code byKey}
 * （冷却/没名字时才会走到），不计入渲染热路径的任何别的东西。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiSaveEditClientTitleSource implements ShanhaiSaveEditAdvancementUi.TitleSource {

    public static final String PREFIX = "[SHANHAI-SAVEEDIT-TITLE]";

    private static final ShanhaiSaveEditClientTitleSource INSTANCE = new ShanhaiSaveEditClientTitleSource();

    private ShanhaiSaveEditClientTitleSource() {}

    /**
     * 客户端初始化处注册（{@code Bus.MOD} + {@code value = Dist.CLIENT} ⇒ 专服上本类不被加载）。
     *
     * <p>只在<b>客户端</b>跑一次；注册的是"查名字的能力"，不读盘、不碰任何世界状态。
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ShanhaiSaveEditAdvancementUi.register(INSTANCE);
        GTDishanhaiMod.LOGGER.info("{} 客户端：成就名字源已注册（真 display → 配方产物 → id〔技术成就〕）", PREFIX);
    }

    /**
     * 当前语言下的名字；<b>三条路都不通 ⇒ {@code null}</b>（绝不返回空串、绝不返回翻译键）。
     *
     * <p>实现顺序就是类注释 §二 那三条；每一步都单独 catch（一个 mod 的坏配方不许把整页名字弄崩）。
     */
    @Override
    public String titleOrNull(String advancementId) {
        final Advancement advancement = advancementOrNull(advancementId);
        if (advancement == null) {
            return null;        // 客户端还没有这条成就（没进世界 / 服务端没同步）⇒ 交给下一层
        }
        final String display = displayTitleOrNull(advancement);
        if (display != null) {
            return display;
        }
        return recipeProductNameOrNull(advancement);
    }

    /** ① 客户端手里那条真 {@code Advancement}（{@code null} = 没有 / 还没同步 / id 不像）。 */
    private static Advancement advancementOrNull(String advancementId) {
        final String id = advancementId == null ? "" : advancementId.trim();
        if (id.isEmpty()) {
            return null;
        }
        try {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return null;
            }
            final ClientPacketListener connection = minecraft.getConnection();
            if (connection == null) {
                return null;        // 不在世界内（标题/加载屏）：没有成就表
            }
            final ClientAdvancements clientAdvancements = connection.getAdvancements();
            if (clientAdvancements == null) {
                return null;
            }
            final ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return null;
            }
            return clientAdvancements.getAdvancements().get(rl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ① 真 display 的标题（{@code null} = 没有 display，或标题只是没译出来的裸键）。 */
    private static String displayTitleOrNull(Advancement advancement) {
        try {
            final DisplayInfo display = advancement.getDisplay();
            if (display == null) {
                return null;        // 🔴 数据包型成就：这里就是 93.3% 的那些行
            }
            final Component title = display.getTitle();
            if (title == null) {
                return null;
            }
            final String text = title.getString();
            if (text == null || text.isEmpty()) {
                return null;
            }
            // 没译文时 getString() 原样返回键（advancements.<path>.title）⇒ 当成"没有名字"
            if (text.startsWith("advancements.")) {
                return null;
            }
            return text;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ② 配方产物名：{@code rewards.recipes} 里的第一条能解的 → 主产物 → {@code getHoverName()}。
     *
     * <p>依次试而不是只看第 0 条：客户端那份 {@code RecipeManager} 可能因数据包/条件没同步到某一条，
     * 第 0 条解不出时不该整行退回 id（实测 2980/2981 条 {@code recipes/*} 带 {@code rewards.recipes}，
     * 多数就 1 条，少数几条 —— 试完这几条几乎不花时间）。
     */
    private static String recipeProductNameOrNull(Advancement advancement) {
        try {
            final AdvancementRewards rewards = advancement.getRewards();
            if (rewards == null) {
                return null;
            }
            final ResourceLocation[] recipes = rewards.getRecipes();
            if (recipes == null || recipes.length == 0) {
                return null;
            }
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return null;
            }
            final ClientLevel level = minecraft.level;
            if (level == null) {
                return null;        // 不在世界内：没有 registryAccess，也算不出主产物
            }
            final RecipeManager recipeManager = level.getRecipeManager();
            if (recipeManager == null) {
                return null;
            }
            for (ResourceLocation recipeId : recipes) {
                if (recipeId == null) {
                    continue;
                }
                final Optional<? extends Recipe<?>> hit = recipeManager.byKey(recipeId);
                if (hit == null || hit.isEmpty()) {
                    continue;       // 客户端没有这条配方（条件没满足 / 数据包不同步）
                }
                final ItemStack product = hit.get().getResultItem(level.registryAccess());
                if (product == null || product.isEmpty()) {
                    continue;       // 特殊配方（isSpecial）常常给空产物
                }
                final String name = product.getHoverName().getString();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                // 与第 ① 页物品栏<b>同一套判据</b>（{@code ShanhaiSaveEditInventoryUi#nameOrNull}）：
                // getString() 在没有译文时原样返回翻译键 —— 最可靠的判据是"返回的串 == 它自己的描述键"，
                // 再加两个前缀兜底（方块的物品描述键是 block.…）。命中 ⇒ 这个产物也没有名字。
                final String describeKey = product.getDescriptionId();
                if (name.equals(describeKey) || name.startsWith("item.") || name.startsWith("block.")) {
                    continue;       // 产物也没译文 ⇒ 不如如实回退成 id〔技术成就〕
                }
                return name;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
