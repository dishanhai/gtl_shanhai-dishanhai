package com.shanhai.backstage;

/**
 * 游戏给后台进程看的四个阶段（状态文件的 {@code phase} 字段）。
 *
 * <p>只有四个值，<b>没有第五个</b>：子进程要判断的东西越少，它就越不可能因为游戏侧的新花样而算错。
 * 状态由主线程置位（{@code ShanhaiWindowTitle#publishState()}），子进程只读。
 *
 * <ul>
 *   <li>{@link #IDLE} —— 人待在界面里干等（标题页 / 选存档 / 选世界 / 多人列表）。
 *       这段时间<b>不计</b>加载时间，标题只显示基础信息。</li>
 *   <li>{@link #LOADING} —— 从「进入加载」到「世界就绪」：初始启动（模组加载 + 资源加载）与
 *       载入存档都落在这里，{@code startEpochMs} 记的就是这一段的起点。</li>
 *   <li>{@link #INGAME} —— 已经在存档里，标题显示从进世界那一刻起的游玩时长。</li>
 *   <li>{@link #STOP} —— 游戏正在关闭：子进程看到它立刻收摊退出（不写标题、不再等待）。</li>
 * </ul>
 *
 * <p>解析<b>永不抛异常</b>（文件可能来自旧版本/写坏的行）：认不出的值一律当 {@link #IDLE}。
 * 这是刻意的——一个坏字段最多让标题少走一段，绝不能把子进程搞崩。
 */
public enum BackstagePhase {

    IDLE,
    LOADING,
    INGAME,
    STOP;

    /** 状态文件里的文本；写文件与日志都用它（大写、稳定，别改）。 */
    public String id() {
        return name();
    }

    /**
     * 解析 {@code phase=} 的值：忽略大小写与首尾空白，认不出就 {@link #IDLE}。
     *
     * @param raw 文件里读到的原始值，可为 {@code null}
     */
    public static BackstagePhase parse(String raw) {
        if (raw == null) {
            return IDLE;
        }
        final String v = raw.trim();
        if (v.isEmpty()) {
            return IDLE;
        }
        for (BackstagePhase p : values()) {
            if (p.name().equalsIgnoreCase(v)) {
                return p;
            }
        }
        return IDLE;
    }
}
