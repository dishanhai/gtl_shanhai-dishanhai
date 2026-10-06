package com.shanhai.common.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shanhai.ShanhaiMod;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.fml.ModList;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 山海重构 · <b>中文名查表（{@code assets/&lt;modid&gt;/lang/zh_cn.json}）</b>。
 *
 * <h2>1. 为什么需要它 —— 而不是直接 {@code Component.translatable(...)}</h2>
 * 面板的文字是<b>服务端产出</b>的（见 {@code ShanhaiRecipeEditorSession} 类注释：服务端权威，
 * 客户端那份 session 的所有 {@code xxxText()} 返回空串）。
 * 而 {@code Component.getString()} 走的是<b>当前的</b> {@code Language}：
 * <ul>
 *   <li><b>单机</b>（用户实际在玩的那种）：客户端与服务端同一个 JVM，{@code Language} 就是客户端选的语言
 *       ⇒ {@code gtceu.matter_module_casting} 直接得到「物质模块铸造」✓；</li>
 *   <li><b>无头专服</b>（本工程的验证装置）：{@code Language} 只有 minecraft 的 {@code en_us}
 *       （拿不到时甚至只有一张空表）⇒ 同一个 key 会<b>原样返回 key</b> ⇒ 那是一条会撒谎的读数
 *       （"查过了、查不到"与"根本没查"长得一样）✗。</li>
 * </ul>
 * ⇒ 本类补上第二条真值来源：<b>直接从各 mod 的 classpath 资源里读 zh_cn.json</b>。
 * 它在专服上照样能用（mod jar 全在 classpath 上），于是"中文名到底解析成什么"
 * 变成一条<b>无头也验得出来</b>的读数。
 *
 * <h2>2. 🔴 绝不编名字</h2>
 * 本类<b>只做查表</b>：查不到就返回 {@code null}，由调用方回落（回落成原始 id）。
 * 任何"猜一个中文名"的写法都会造出一条与游戏内不一致的假读数（本工程血账：宁可缺，不可假）。
 *
 * <h2>3. 口径</h2>
 * <ul>
 *   <li>索引的是 {@code assets/<modid>/lang/zh_cn.json}，<b>遍历 {@link ModList} 里所有已加载的 mod</b>；</li>
 *   <li>同名 key 出现在多个 mod 里时，<b>后遍历到的覆盖先遍历到的</b>（与 MC 资源包"后加载的赢"同向，
 *       但顺序取的是 {@code ModList} 的顺序 —— 这一点<b>如实记录为近似</b>，不是资源包顺序的严格复刻）；</li>
 *   <li>只收录<b>顶层字符串值</b>（lang 文件的形状就是 {@code {"k":"v"}} 的平整表）；</li>
 *   <li>整个索引<b>懒加载一次</b>并缓存；读某个 mod 失败只跳过它，绝不抛给调用方。</li>
 * </ul>
 *
 * <h2>4. 🔴 为什么用 {@code IModFile.findResource} 而不是 {@code Class.getResourceAsStream}</h2>
 * 2026-10-05 冒烟第 2 局实测：用 {@code ShanhaiLangLookup.class.getResourceAsStream("/assets/gtceu/lang/zh_cn.json")}
 * 去读别人的 mod 资源，<b>只有我们自己的 jar 读得到</b>
 * （读数原文：{@code mods_scanned=92 mods_with_lang=1}）—— Forge 的模块化类加载器不会把
 * 其它 mod 的资源暴露给我们的类加载器。
 * ⇒ 改成 Forge 自己的定位 API：{@code ModList.getModFileById(id).getFile().findResource("assets", id, "lang", "zh_cn.json")}
 * （{@code IModFile.findResource(String...)} 与 {@code IModFileInfo.getFile()} 都是 {@code javap} 实证过的签名）。
 */
public final class ShanhaiLangLookup {

    public static final String PREFIX = "[SHANHAI-LANG]";

    /** 索引缓存（key → zh_cn 文案）。{@code null} = 还没建。 */
    private static volatile Map<String, String> ZH_CN = null;

    /** 建的次数与耗时读数（自检打印用）。 */
    private static final AtomicInteger BUILD_COUNT = new AtomicInteger();
    private static volatile long lastBuildMs = -1;
    private static volatile int lastModsScanned = -1;
    private static volatile int lastModsWithLang = -1;

    /** 上一次建索引时，真正读到了 zh_cn.json 的那些 mod（读数用：证明"扫到了别人家的 lang"）。 */
    private static volatile List<String> lastLangMods = List.of();

    private ShanhaiLangLookup() {}

    /**
     * 查一个 key 的简体中文文案。
     *
     * @return 查到的文案；<b>查不到返回 {@code null}</b>（调用方负责回落，绝不返回 key 本身）
     */
    public static String zhCn(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        final Map<String, String> map = index();
        final String v = map.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    /** 索引是否已经建好（自检/读数用）。 */
    public static boolean indexBuilt() {
        return ZH_CN != null;
    }

    /** 一行机器可判的读数。 */
    public static String statsLine() {
        final Map<String, String> m = ZH_CN;
        final List<String> mods = lastLangMods;
        final String sample = mods == null ? "" :
                (mods.size() <= 12 ? String.join(",", mods)
                        : String.join(",", mods.subList(0, 12)) + ",…(+" + (mods.size() - 12) + ")");
        return "built=" + (m != null)
                + " keys=" + (m == null ? -1 : m.size())
                + " mods_scanned=" + lastModsScanned
                + " mods_with_lang=" + lastModsWithLang
                + " build_ms=" + lastBuildMs
                + " builds=" + BUILD_COUNT.get()
                + " lang_mods=[" + sample + "]";
    }

    private static Map<String, String> index() {
        Map<String, String> m = ZH_CN;
        if (m != null) {
            return m;
        }
        synchronized (ShanhaiLangLookup.class) {
            if (ZH_CN != null) {
                return ZH_CN;
            }
            final Map<String, String> built = build();
            // ⚠️ 先赋值再打读数：statsLine() 读的是 ZH_CN，在赋值之前打出来必然是 built=false（假读数）。
            ZH_CN = built;
            ShanhaiMod.LOGGER.info("{} index_loaded {}", PREFIX, statsLine());
            return ZH_CN;
        }
    }

    private static Map<String, String> build() {
        final long t0 = System.nanoTime();
        final Map<String, String> out = new HashMap<>(1 << 16);
        final List<String> withLangIds = new ArrayList<>();
        final java.util.Set<Path> seenFiles = new java.util.HashSet<>();
        int scanned = 0;
        try {
            // ModList.get() 在极早的加载阶段可能为 null ⇒ 整块兜住（拿不到就索引为空，回落成原 id）。
            for (IModInfo info : ModList.get().getMods()) {
                final String modId = info.getModId();
                if (modId == null || modId.isEmpty()) {
                    continue;
                }
                scanned++;
                final Path jar;
                try {
                    jar = info.getOwningFile().getFile().getFilePath();
                } catch (Throwable t) {
                    continue;
                }
                if (jar == null || !seenFiles.add(jar)) {
                    continue;   // 同一个 jar 里有多个 mod ⇒ 只读一次
                }
                if (scanJar(jar, out)) {
                    withLangIds.add(modId);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} index_build_degraded err={} (查表会回落到原 id)", PREFIX, t.toString());
        }
        lastModsScanned = scanned;
        lastModsWithLang = withLangIds.size();
        lastLangMods = withLangIds;
        lastBuildMs = (System.nanoTime() - t0) / 1_000_000L;
        BUILD_COUNT.incrementAndGet();
        return out;
    }

    /** {@code assets/<任意命名空间>/lang/zh_cn.json} —— 不假设命名空间等于 modid。 */
    private static final java.util.regex.Pattern LANG_ENTRY =
            java.util.regex.Pattern.compile("^assets/[^/]+/lang/zh_cn\\.json$");

    /**
     * 扫一个 mod jar 里<b>全部</b> {@code assets/<ns>/lang/zh_cn.json}。
     *
     * <h4>🔴 为什么是"扫 jar 里的全部命名空间"，而不是"按 modid 拼一个路径"</h4>
     * 实测：{@code gtladditions-…jar} 里放的是 {@code assets/gtceu/lang/zh_cn.json}
     * （命名空间 {@code gtceu} ≠ 那个 jar 的 modid）⇒ 按 modid 拼路径会漏掉它。
     * 而我们要查的 key 全是 {@code gtceu.<配方类型路径>} 这种形状，
     * 它可能出现在任何一个 GT 系 mod 的 {@code assets/gtceu/lang/} 里。
     *
     * @return 这个 jar 里读到了至少一份 zh_cn.json
     */
    private static boolean scanJar(Path jar, Map<String, String> out) {
        boolean any = false;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            final var entries = zf.entries();
            while (entries.hasMoreElements()) {
                final var e = entries.nextElement();
                if (e.isDirectory() || !LANG_ENTRY.matcher(e.getName()).matches()) {
                    continue;
                }
                try (InputStream in = zf.getInputStream(e);
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    final JsonElement el = JsonParser.parseReader(reader);
                    if (el == null || !el.isJsonObject()) {
                        continue;
                    }
                    final JsonObject obj = el.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> kv : obj.entrySet()) {
                        final JsonElement v = kv.getValue();
                        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                            continue;
                        }
                        out.put(kv.getKey(), v.getAsString());
                    }
                    any = true;
                }
            }
        } catch (Throwable t) {
            // 单个 jar 坏了不影响其它 jar
            ShanhaiMod.LOGGER.warn("{} scan_failed jar={} err={}", PREFIX, jar.getFileName(), t.toString());
        }
        return any;
    }

    /** 供自检用：把索引清掉（下次查表重建）。正常玩法路径不会调用。 */
    public static void invalidateForTest() {
        ZH_CN = null;
    }
}
