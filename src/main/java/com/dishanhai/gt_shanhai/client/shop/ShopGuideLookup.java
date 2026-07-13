package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import guideme.Guide;
import guideme.Guides;
import guideme.GuidesCommon;
import guideme.PageAnchor;
import guideme.indices.ItemIndex;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 商店 × GuideME 指南集成（山海署名，仅客户端）：商品引用的物品若在任意已装 GuideME 指南里登记了
 * {@code item_ids}（就是 tooltip 上"按 G 打开指南"那个），商店详情页自动出现跳转入口——不需要
 * 编辑者手动指定，检索到就给，编辑器那边完全无感知。
 *
 * <p>GuideME 在本模组是强制依赖（见 mods.toml），不需要软依赖判空守卫，直接引用其 API 即可。
 * 指南索引只在客户端构建（{@link ItemIndex} 靠资源重载解析 Markdown frontmatter），故本类只在
 * 客户端调用，服务端读不到任何东西（不需要也不应该在服务端调用）。</p>
 */
public final class ShopGuideLookup {

    private ShopGuideLookup() {}

    /** 一条指南命中：代表物品（多物品命中同一页时取第一个）+ 所属指南 ID + 页面锚点。 */
    public record GuideHit(ItemStack item, ResourceLocation guideId, PageAnchor anchor) {}

    /**
     * 按商品清单查每项是否有 GuideME 指南页，按 (指南ID, 页面ID) 去重——一套多物品的商品（比如成套配件）
     * 如果都指向同一份指南页，只算一条，不重复列出；不同页面各自保留。
     */
    public static List<GuideHit> findGuideHits(List<ShopEntry.GoodsStack> goodsList) {
        if (goodsList == null || goodsList.isEmpty()) return List.of();
        List<GuideHit> hits = new ArrayList<>();
        Set<String> seenPages = new HashSet<>();
        for (ShopEntry.GoodsStack gs : goodsList) {
            ResourceLocation itemId = gs.id();
            Item item = itemId == null ? null : ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null || itemId == null) continue;
            for (Guide guide : Guides.getAll()) {
                ItemIndex idx = guide.getIndex(ItemIndex.class);
                PageAnchor anchor = idx == null ? null : idx.get(itemId);
                if (anchor == null) continue;
                String dedupKey = guide.getId() + "|" + anchor.pageId();
                if (!seenPages.add(dedupKey)) continue;
                hits.add(new GuideHit(gs.makeStack(), guide.getId(), anchor));
            }
        }
        return hits;
    }

    /** 打开某条命中的指南页（客户端调用）。 */
    public static void open(Player player, GuideHit hit) {
        if (player == null || hit == null) return;
        GuidesCommon.openGuide(player, hit.guideId(), hit.anchor());
    }
}
