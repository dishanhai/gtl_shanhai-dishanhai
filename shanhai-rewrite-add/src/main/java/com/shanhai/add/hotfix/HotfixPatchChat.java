package com.shanhai.add.hotfix;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.guard.ShanhaiGuardEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 热改播报的<b>装配那一侧</b>（本模块里唯一认识 MC／Forge 的那个播报实现）。
 *
 * <h2>为什么引擎不自己发聊天</h2>
 * {@link HotfixAutoCounter} 是<b>纯 JDK（＋ASM）</b>的：一旦它 {@code import net.minecraft.*}，
 * 那条离线自检线就断了（{@code temp\hotfix\offline-autocounter\}）。所以"拼正文／查 modid／
 * 找到玩家／投递"全部留在本类，引擎只通过 {@link HotfixAutoCounter.PatchChat} 这个
 * <b>只认 String 和 List</b> 的接口把"事实"交出来。
 *
 * <h2>播报走哪条路（用户硬要求：不新开第二条通道）</h2>
 * <pre>
 *   正文拼装 : {@link HotfixPatchChatText}（纯 JDK，离线可跑同一份字节码）
 *   通道     : {@link ShanhaiGuardEvents#chatRawBodies} —— 本体守卫的【同一条】聊天通道：
 *              前缀 {@code [创始现实修改模块] } ＋ 同一个 {@code LogGate}（同类合并 ＋ 每秒最多一行）
 *              ＋ 同一个收件人规则（只有持有那件模块的受保护玩家，既不广播、也没有第二条 socket）
 *   线程     : 热改的工作线程会从这里进来 ⇒ 本体那边会把投递排到服务端线程，绝不在这里碰玩家连接
 * </pre>
 *
 * <h2>无玩家上下文 = 一条都不发</h2>
 * 控制台／专服没人／玩家名对不上时本类返回 0 —— <b>日志那一路照旧</b>（日志在引擎里打，与这里无关）。
 */
public final class HotfixPatchChat implements HotfixAutoCounter.PatchChat {

    /** 日志前缀（与 {@code HotfixAutoCounter.PREFIX} 同一个字面量；这里 deliberately 再写一遍，不为引用它而加载那个类）。 */
    private static final String PREFIX = "[SHANHAI-HOTFIX]";

    /** 目标类全名 ⇒ modId（一次算好，之后 O(1)；拿不到就记 {@code unknown}）。 */
    private static final Map<String, String> MODID_CACHE = new HashMap<>();

    /**
     * "某个 mod 的包前缀 ⇒ modId"这张表（从 {@code ModList} 取，与 {@code ShanhaiStackRadar} 的
     * {@code known_hosts} 同一套推法：拿每个 mod 的 {@code @Mod} 实例类所在包）。
     * {@code null} = 还没建过。
     */
    private static Map<String, String> namespaces;

    /**
     * 🔴 播报一批热改事实（引擎在工作线程上调用）。
     *
     * @param playerName 收件人玩家名（{@code null}/空 ⇒ 不发：控制台／没有玩家上下文）
     * @param notes      同一批（一次 patch 改了同类多个方法 ⇒ 多条 ⇒ 就发多行）
     * @return 真的交给通道几条（0 = 没发：没有玩家上下文／对不上玩家／正文不可播）
     */
    @Override
    public int announce(String playerName, List<HotfixAutoCounter.PatchNote> notes) {
        if (playerName == null || playerName.isEmpty() || notes == null || notes.isEmpty()) {
            return 0;   // 控制台／无玩家上下文 ⇒ 一条都不发（那一侧日志照旧）
        }
        try {
            final ServerPlayer holder = findPlayer(playerName);
            if (holder == null) {
                return 0;   // 玩家不在线（名字对不上）⇒ 不发，也不假装发了
            }
            final List<String> bodies = new ArrayList<>(notes.size());
            for (final HotfixAutoCounter.PatchNote note : notes) {
                if (note == null) {
                    continue;
                }
                final String modId = modIdOf(note.className);
                final String body = HotfixPatchChatText.build(note.word, modId, note.className,
                        note.method, note.arg, HotfixPatchChatText.WORD_PATCHED.equals(note.word));
                if (body == null) {
                    continue;   // 这一条拼不出来（非 ASCII／缺名字）⇒ 不发它，别的照发
                }
                bodies.add(body);
            }
            if (bodies.isEmpty()) {
                return 0;
            }
            // 🔴 走本体现成的守卫聊天通道（同一个闸门、同一份节流、同一个收件人规则）
            return ShanhaiGuardEvents.chatRawBodies(holder, bodies);
        } catch (Throwable t) {
            // 播报坏了绝不许影响热改本身：只记一行，返回 0
            warn("chat_announce failed detail=" + t.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 按玩家名找一个在线服务端玩家（<b>遍历在线名单比对名字</b> —— 不用任何"按名查"的冷门 API）。
     *
     * @return 那个玩家；服务端没起来／不在线 ⇒ {@code null}
     */
    private static ServerPlayer findPlayer(String playerName) {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;   // 专服没起／单机还没进世界 ⇒ 没有玩家上下文
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != null && player.getName() != null && playerName.equals(player.getName().getString())) {
                return player;
            }
        }
        return null;
    }

    /**
     * 目标类属于哪个 mod（<b>前缀最长匹配</b>；查不到 {@code unknown}，绝不猜）。
     *
     * @param className 目标类全名（例：{@code examplemod.util.EntityUtil}）
     */
    static String modIdOf(String className) {
        if (className == null || className.isEmpty()) {
            return HotfixPatchChatText.UNKNOWN_MODID;
        }
        synchronized (MODID_CACHE) {
            final String hit = MODID_CACHE.get(className);
            if (hit != null) {
                return hit;
            }
            if (namespaces == null) {
                namespaces = collectNamespaces();
            }
            final String modId = HotfixPatchChatText.modIdOf(className, namespaces);
            if (MODID_CACHE.size() >= 512) {
                MODID_CACHE.clear();   // 极端情况下重置；绝不无限涨
            }
            MODID_CACHE.put(className, modId);
            return modId;
        }
    }

    /**
     * 从 {@code ModList} 收集"包前缀 ⇒ modId"。
     *
     * <p>推法与 {@code ShanhaiStackRadar#collectKnownHosts} 完全一致：每个 mod 的 {@code @Mod} 实例类
     * 就在它自己的代码里 ⇒ 取那个类的包当命名空间；拿不到实例（库／虚拟 mod）⇒ 这个 mod 就<b>不在表里</b>
     * （它的帧仍会记 {@code unknown}，与雷达那条"不许猜"口径对齐）。
     */
    private static Map<String, String> collectNamespaces() {
        final Map<String, String> out = new LinkedHashMap<>();
        try {
            final ModList list = ModList.get();
            if (list == null) {
                return out;
            }
            for (final IModInfo info : list.getMods()) {
                if (info == null || info.getModId() == null || info.getModId().isEmpty()) {
                    continue;
                }
                try {
                    final Optional<?> mod = list.getModObjectById(info.getModId());
                    if (mod == null || !mod.isPresent() || mod.get() == null) {
                        continue;
                    }
                    final String className = mod.get().getClass().getName();
                    final int dot = className.lastIndexOf('.');
                    if (dot <= 0) {
                        continue;   // 默认包：推不出命名空间（不许猜）
                    }
                    out.put(className.substring(0, dot + 1), info.getModId());
                } catch (Throwable ignored) {
                    // 单个 mod 取不到实例 ⇒ 它不进表（记 unknown），不影响别的 mod
                }
            }
        } catch (Throwable t) {
            warn("chat_modid_table unavailable detail=" + t.getClass().getSimpleName());
        }
        return out;
    }

    /** 一行 WARN（播报链自己的失败只许 WARN，绝不许影响热改）。 */
    private static void warn(String text) {
        try {
            ShanhaiMod.LOGGER.warn("{} {}", PREFIX, text);
        } catch (Throwable ignored) {
            // 日志都打不出去时什么也别做
        }
    }
}
