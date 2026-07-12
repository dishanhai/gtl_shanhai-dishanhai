package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端聊天镜像：把带商店/货币中心前缀（{@code [山海商店]} / {@code [商店]} / {@code [货币中心]}）
 * 的系统消息，按前缀路由镜像进对应屏幕（{@link ShopScreen} / {@link CurrencyAtmScreen}）的实时横幅。
 * 聊天框照常保留，横幅仅在对应界面打开时可见。
 *
 * <p>零改服务端：反馈消息本就带统一前缀，这里用前缀匹配统一接管，一处不漏。
 * 消息含 § 颜色码（literal 内嵌），横幅按原色渲染。</p>
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public final class ShopChatMirror {

    private ShopChatMirror() {}

    @SubscribeEvent
    public static void onClientChat(ClientChatReceivedEvent event) {
        Component msg = event.getMessage();
        if (msg == null) return;
        String plain = msg.getString();
        if (plain == null) return;
        // 前缀含 § 颜色码，故用 contains 匹配。"[商店]" 与 "[山海商店]" 互不为子串，可区分
        if (plain.contains("[山海商店]") || plain.contains("[商店]")) {
            ShopScreen.showMessage(msg);
        } else if (plain.contains("[货币中心]")) {
            CurrencyAtmScreen.showMessage(msg);
        }
    }
}
