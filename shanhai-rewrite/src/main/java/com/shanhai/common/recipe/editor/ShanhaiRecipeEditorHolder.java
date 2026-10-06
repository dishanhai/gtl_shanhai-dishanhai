package com.shanhai.common.recipe.editor;

import com.lowdragmc.lowdraglib.gui.modular.IUIHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * 面板的「持有者」—— 由服务端建立、序列化后送到客户端的那一小包数据。
 *
 * <h2>它为什么必须存在（而不是直接用玩家当 holder）</h2>
 * LDLib 的 {@code UIFactory} 要求 {@code T} 能被 {@code writeHolderToSyncData} /
 * {@code readHolderFromSyncData} 双向序列化，而 {@code createUITemplate} 在<b>两侧各跑一次</b>
 * （服务端直接建、客户端 {@code readHolderFromSyncData} 之后建）
 * ⇒ <b>两侧必须能从同一个字节流建出同一个东西</b>。
 *
 * <h2>🔴 第二刀改了什么：从"一个物品"改成"一个落点"</h2>
 * 第一刀的入口是"手里拿着的物品"（{@code /shanhai edit} 拿物品进来），所以只带 {@code itemId}。
 * 用户明确否掉了这个入口（原话：「入口也不是拿个物品就进入，而是先选择配方类型」）⇒ 本刀改成
 * <b>三段式</b>（类型 ⇒ 配方 ⇒ 编辑），于是这一小包数据也要跟着带"从哪一段进来"：
 * <pre>
 *   stage    0 = 第一屏（类型列表，默认）· 1 = 第二屏（该类型的配方）· 2 = 第三屏（编辑）
 *   typeId   选中的配方类型（可空）
 *   recipeId 选中的配方（可空）
 * </pre>
 * 这样以后要加 {@code /shanhai edit <配方id>} 这种"直接跳到某条配方"的入口，只改命令、不动本类。
 *
 * <h2>🔴 {@code isRemote()} 由【构造现场】决定，不查全局态</h2>
 * 两侧各建一次同一个类 ⇒ "我是哪一侧"必须是<b>每个实例自己的字段</b>：
 * <ul>
 *   <li>服务端：命令回调用 {@code player.level().isClientSide()}；</li>
 *   <li>客户端：{@link ShanhaiRecipeEditorFactory#readHolderFromSyncData} 里显式给 true。</li>
 * </ul>
 */
public final class ShanhaiRecipeEditorHolder implements IUIHolder {

    /** 三段式的段号（见类注释）。 */
    public static final int STAGE_TYPES = 0;
    public static final int STAGE_RECIPES = 1;
    public static final int STAGE_EDIT = 2;

    /** 目标物品（第一刀那条入口留下的字段，可空；现在的入口不用它）。 */
    public final ResourceLocation itemId;

    /** 进来时停在哪一段（默认第一屏）。 */
    public final int stage;

    /** 选中的配方类型（可空）。 */
    public final ResourceLocation typeId;

    /** 选中的配方（可空）。 */
    public final ResourceLocation recipeId;

    /** 这个实例在哪一侧（服务端 false / 客户端 true）。 */
    private final boolean remote;

    public ShanhaiRecipeEditorHolder(ResourceLocation itemId, boolean remote) {
        this(itemId, STAGE_TYPES, null, null, remote);
    }

    public ShanhaiRecipeEditorHolder(ResourceLocation itemId, int stage,
                                     ResourceLocation typeId, ResourceLocation recipeId, boolean remote) {
        this.itemId = itemId;
        this.stage = stage;
        this.typeId = typeId;
        this.recipeId = recipeId;
        this.remote = remote;
    }

    /** 空的可空 {@link ResourceLocation} 编码（{@code ""} = 空，不是"一个叫空的 id"）。 */
    public static void writeNullable(FriendlyByteBuf buf, ResourceLocation id) {
        buf.writeUtf(id == null ? "" : id.toString(), 512);
    }

    /** 与 {@link #writeNullable} 相配。 */
    public static ResourceLocation readNullable(FriendlyByteBuf buf) {
        final String s = buf.readUtf(512);
        return s.isEmpty() ? null : ResourceLocation.tryParse(s);
    }

    @Override
    public ModularUI createUI(Player player) {
        // 面板的真正装配在 ShanhaiRecipeEditorFactory.createUITemplate 里（UIFactory 那条路）。
        return ShanhaiRecipeEditorFactory.buildUI(this, player);
    }

    @Override
    public boolean isInvalid() {
        return false;
    }

    @Override
    public boolean isRemote() {
        return remote;
    }

    @Override
    public void markAsDirty() {
        // 无脏状态需要回写：本面板的权威状态全部由服务端那份实例持有（见面板类注释）。
    }

    @Override
    public String toString() {
        return "ShanhaiRecipeEditorHolder[stage=" + stage + " type=" + typeId + " recipe=" + recipeId
                + " item=" + itemId + " remote=" + remote + "]";
    }
}
