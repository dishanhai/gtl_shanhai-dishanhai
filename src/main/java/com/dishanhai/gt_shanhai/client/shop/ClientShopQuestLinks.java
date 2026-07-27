package com.dishanhai.gt_shanhai.client.shop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端侧的「任务 → 商店商品」手动绑定表（服务端权威，见
 * {@link com.dishanhai.gt_shanhai.common.shop.ShopQuestLinkConfig}）。登录时全量同步，编辑后广播覆盖。
 *
 * <p>只供任务详情页那条跳转入口用（显不显示、跳去哪），不参与任何购买判定。</p>
 */
public final class ClientShopQuestLinks {

    /** questHexId → 商品 stableId 列表。 */
    private static Map<String, List<String>> links = Map.of();

    private ClientShopQuestLinks() {}

    public static void apply(Map<String, List<String>> next) {
        if (next == null || next.isEmpty()) {
            links = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : next.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    copy.put(e.getKey(), List.copyOf(e.getValue()));
                }
            }
            links = Map.copyOf(copy);
        }
        refreshOpenQuestScreen();
    }

    /**
     * 绑定/解绑的回包到达时，若玩家正开着任务书就地重建控件，跳转按钮立刻出现/消失，
     * 不用关掉任务详情再打开一次（{@code addWidgets} 会重新跑，按钮显隐就是在那里算的）。
     *
     * <p>FTBQ 的 {@code QuestScreen} 是 ftblibrary 的 Panel 而非原版 Screen，
     * 挂在原版屏幕栈上的是包一层的 {@code ScreenWrapper}，得从它里面取。</p>
     */
    private static void refreshOpenQuestScreen() {
        net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof dev.ftb.mods.ftblibrary.ui.ScreenWrapper wrapper
                && wrapper.getGui() instanceof dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen questScreen) {
            questScreen.refreshWidgets();
        }
    }

    /** 该任务手动绑定的商品 stableId 列表（无绑定返回空表）。 */
    public static List<String> stableIdsOf(String questHexId) {
        if (questHexId == null || questHexId.isBlank()) return List.of();
        return links.getOrDefault(questHexId.trim(), List.of());
    }

    /** 登出/换服清空，避免把上一个服的绑定带到下一个服。 */
    public static void clear() {
        links = Map.of();
    }
}
