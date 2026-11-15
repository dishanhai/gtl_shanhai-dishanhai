package com.shanhai.client.saveedit;

/**
 * 「重开存档」走哪条路的<b>纯判断函数</b>：零 Minecraft 依赖、无副作用、可注入。
 *
 * <p>存在的唯一理由是<b>离线可断言</b>：真机上 {@code access.getDataConfiguration()} 为 null 时
 * 到底该走哪条分支，必须能在一台不装游戏的机器上用 {@code expect(...)} 判死，而不是靠"进游戏看看"。
 *
 * <h2>真值表</h2>
 * <pre>
 *   dataConfig != null  ⇒ {@link Route#PACK_RELOAD}        路线 B：自己装好数据包 + loadWorldStem + doWorldLoad
 *   dataConfig == null  ⇒ {@link Route#VANILLA_LOAD_LEVEL} 兜底：整段交回原版 WorldOpenFlows#loadLevel
 * </pre>
 * ⚠️ 参数故意声明成 {@link Object}（调用方直接把 {@code WorldDataConfiguration} 传进来）：
 * 这样判据装置不需要构造任何 Minecraft 对象，也不会因为签名里出现 MC 类型而把整个类加载链拖进来。
 */
public final class ShanhaiSaveEditReopenRoute {

    /** 重开的两条路线。{@link #logName()} 就是日志里 {@code reopen_route=…} 那个值，别随便改。 */
    public enum Route {

        /** 路线 B：{@code createPackRepository} + {@code configurePackRepository} + {@code loadWorldStem} + {@code doWorldLoad}。 */
        PACK_RELOAD("packReload"),

        /** 兜底：{@code mc.createWorldOpenFlows().loadLevel(兜底屏, levelId)}（原版 QuickPlay 同一条路）。 */
        VANILLA_LOAD_LEVEL("vanilla_loadLevel");

        private final String logName;

        Route(String logName) {
            this.logName = logName;
        }

        /** 日志/判据用的稳定字符串（不许改成中文或临时措辞：真机判读靠它）。 */
        public String logName() {
            return this.logName;
        }
    }

    private ShanhaiSaveEditReopenRoute() {}

    /**
     * 真值表本体。
     *
     * @param dataConfigOrNull {@code access.getDataConfiguration()} 的返回值（可能为 null）
     * @return null ⇒ {@link Route#VANILLA_LOAD_LEVEL}；非 null ⇒ {@link Route#PACK_RELOAD}
     */
    public static Route of(Object dataConfigOrNull) {
        return dataConfigOrNull != null ? Route.PACK_RELOAD : Route.VANILLA_LOAD_LEVEL;
    }
}
