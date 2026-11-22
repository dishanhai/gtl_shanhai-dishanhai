package com.shanhai.backstage;

/**
 * 子进程/游戏侧共用的「一行日志」出口。
 *
 * <p>抽成接口只为一件事：<b>自证</b>。探针里换成「收集到 List」的实现，就能断言
 * <em>日志里到底有没有那一行</em>，而不必去翻真实文件；生产实现是 {@link BackstageFileLog}。
 */
public interface BackstageLog {

    /** 打一行（实现负责加时间戳/前缀/换行，调用方只给正文）。 */
    void line(String message);

    /** 什么都不做的日志（只在"连日志都开不出来"时用，保证调用方不必判空）。 */
    BackstageLog NOOP = message -> {
    };
}
