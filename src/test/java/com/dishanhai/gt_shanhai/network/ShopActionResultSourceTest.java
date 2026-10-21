package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopActionResultSourceTest {

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/" + relative));
    }

    @Test
    void buyAndSellReportStructuredResultsWithoutTouchingTheCartPath() throws Exception {
        String s = source("network/ShopActionPacket.java");
        String doBuy = extractBlock(s, "private static void doBuy(");
        assertFalse(doBuy.contains("sendCartResult("), "doBuy 统一经 sendResult，购物车与详情页回执一起发");
        assertTrue(doBuy.contains("sendResult(player, pkt,"));

        String doSell = extractBlock(s, "private static void doSell(ServerPlayer player, ShopEntry entry, ShopActionPacket pkt)");
        assertEquals(8, count(doSell, "sendResult(player, pkt,"), "出售的六个失败分支 + 全部/部分成交都要回执");

        assertTrue(extractBlock(s, "private static void sendCartResult(")
                        .contains("if (!pkt.fromCart || pkt.action != Action.BUY) return;"),
                "购物车回执的守卫不能动：出售与详情页购买不得改动购物车");
        assertTrue(extractBlock(s, "private static void sendActionResult(").contains("pkt.requestId == 0"),
                "没带请求号（购物车、自选奖励、删除、充值）不发详情页回执");

        String encode = extractBlock(s, "public void encode(FriendlyByteBuf buf)");
        assertTrue(encode.indexOf("buf.writeBoolean(fromCart);") < encode.indexOf("buf.writeVarInt(requestId);"));
    }

    @Test
    void resultPacketIsClientBoundAndBumpsTheProtocol() throws Exception {
        String net = source("network/ShanhaiNetwork.java");
        assertTrue(net.contains("PROTOCOL_VERSION = \"7\""));
        int cart = net.indexOf("ShopCartPurchaseResultPacket.class");
        int result = net.indexOf("ShopActionResultPacket.class");
        assertTrue(cart >= 0 && result > cart, "新包追加在后面，不打乱既有包号");
        String tail = net.substring(result, net.indexOf(");", result));
        assertTrue(tail.contains("NetworkDirection.PLAY_TO_CLIENT"));

        assertTrue(source("client/ClientInit.java").contains("ClientShopActionResults.clear()"),
                "登出清掉没消化的回执");
        assertTrue(source("network/ShopCartPurchaseResultPacket.java").contains("ClientShopCart.applyPurchaseResult("),
                "购物车回执路径保持原样");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openBrace, i + 1);
            }
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }
}
