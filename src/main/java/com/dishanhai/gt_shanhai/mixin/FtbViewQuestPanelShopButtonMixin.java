package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.gui.shop.ShopQuestJumpButton;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.client.shop.ClientShopQuestLinks;

import dev.ftb.mods.ftblibrary.ui.BlankPanel;
import dev.ftb.mods.ftblibrary.ui.VerticalSpaceWidget;
import dev.ftb.mods.ftbquests.client.gui.quests.ViewQuestPanel;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海商店 × FTBQ 任务书反向跳转（山海署名，仅客户端）：商品把某个任务配成前置
 * （{@code ShopEntry#getPrerequisiteQuestId}）后，该任务的详情页自动多出一条「前往商店兑换」入口，
 * 无需在任务侧额外配置。正方向（商店详情页 →「前置任务」跳到任务书）见 {@code ShopScreen} 的 prereqLink。
 *
 * <p><b>布局选点</b>：按钮加进 {@code panelText}（描述文本面板）而不是顶栏那排图标按钮，理由是顶栏
 * 横向已经排满且是硬编码绝对坐标——左侧 [4,20] 是任务图标、[20,36] 是跨章节时才出现的
 * {@code GotoLinkedQuestButton}、标题字段从 x=27 起；右侧 [w-52,w-2] 依次是任务链接/置顶/关闭三个按钮。
 * 往里插图标必然跟其中某个重叠。{@code panelText} 走的是 {@code WidgetLayout.Vertical} 自动排版，
 * 加进去只会把内容往下顺延，面板高度由 FTBQ 自己在后面重算，零坐标冲突。</p>
 *
 * <p><b>注入点</b>：{@code buildPageIndices()} 之后——此时 {@code panelText} 已经 setPosAndSize（宽度确定，
 * 按钮才能算居中 X），副标题已入列，而描述文本和最后的 align/setHeight 都还没跑，加进去能被正常排版。
 * 注入 TAIL 就晚了：那时 align 已经算完，后加的控件会叠在面板左上角。</p>
 */
@Mixin(value = ViewQuestPanel.class, remap = false)
public abstract class FtbViewQuestPanelShopButtonMixin {

    @Shadow private Quest quest;
    @Shadow private BlankPanel panelText;

    @Inject(method = "addWidgets", at = @At(value = "INVOKE",
            target = "Ldev/ftb/mods/ftbquests/client/gui/quests/ViewQuestPanel;buildPageIndices()V",
            shift = At.Shift.AFTER))
    private void shanhai$addShopJumpButton(CallbackInfo ci) {
        if (quest == null || panelText == null) return;
        List<Long> entryKeys = shanhai$linkedEntryKeys(QuestObjectBase.getCodeString(quest));
        if (entryKeys.isEmpty()) return;
        panelText.add(new VerticalSpaceWidget(panelText, 3));
        panelText.add(new ShopQuestJumpButton(panelText, entryKeys));
        panelText.add(new VerticalSpaceWidget(panelText, 3));
    }

    /**
     * 两条来源合并去重，手动绑定排前面：
     * <ol>
     *   <li><b>手动绑定</b>（编辑者在「编辑 ▼」菜单里指的，见 {@code ShopQuestLinkMenu}）——纯导航关系，
     *       商品没配前置也能被指过来，是主要用法；</li>
     *   <li><b>被动反查</b>（商品把本任务配成了购买前置）——顺带给出一条「去买它」的路，
     *       商品那边本来就写了这层关系，不用再手工重复指一遍。</li>
     * </ol>
     *
     * <p>两者都只依赖全量 stub（不要求商品所在 chunk 已加载）。玩家从没打开过商店、目录清单还没同步时
     * 自然拿到空表 = 不显示按钮，等登录同步的清单到货后下次打开任务详情就有了。</p>
     */
    private static List<Long> shanhai$linkedEntryKeys(String questHexId) {
        List<Long> keys = new ArrayList<>();
        for (String stableId : ClientShopQuestLinks.stableIdsOf(questHexId)) {
            long key = ClientShopCatalog.keyOfStableId(stableId);
            // 商品被删/改到隐藏后 stableId 解析不到，这条死链接直接跳过（解绑入口仍在编辑菜单里）
            if (key >= 0L && ClientShopCatalog.stub(key) != null && !keys.contains(key)) keys.add(key);
        }
        for (Long key : ClientShopCatalog.keysOfPrerequisiteQuest(questHexId)) {
            if (!keys.contains(key)) keys.add(key);
        }
        return keys;
    }
}
