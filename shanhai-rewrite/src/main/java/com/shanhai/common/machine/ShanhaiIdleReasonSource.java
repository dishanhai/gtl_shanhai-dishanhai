package com.shanhai.common.machine;

/**
 * 「<b>这台机器为什么没跑</b>」的服务端权威出口 —— Jade 的服务端数据提供器只认这一个接口。
 *
 * <h2>为什么抽成接口（而不是在 Jade 插件里 {@code instanceof} 具体机器类）</h2>
 * 本工程这条信息的持有者<b>不是同一棵继承树</b>：
 * <pre>
 *   模块那一脉   {@code PrimordialModuleMachine}（25 台标准模块 + 创造编译矩阵…）
 *                └ 全部 extends WorkableElectricMultiblockMachine
 *   主机那一脉   {@code PrimordialOmegaEngineMachine}（原始终焉引擎）+ 归墟视界枢核（extends 它）
 *                └ 也 extends WorkableElectricMultiblockMachine，但与模块<b>没有公共子类</b>
 * </pre>
 * Jade 的 {@code registerBlockDataProvider} 只接受 BlockEntity / Block 类，粒度是
 * {@code MetaMachineBlockEntity} ⇒ 提供器内部只能做 {@code instanceof}
 * ⇒ 有一个接口，判断就是<b>一句 {@code instanceof ShanhaiIdleReasonSource}</b>，
 * 不用在插件里写两串强转、也不会因为将来多一脉机器而漏掉。
 *
 * <h2>契约（三条，全工程唯一一份）</h2>
 * <ol>
 *   <li>{@code ""}（或 {@code null}）＝ <b>这一拍没有原因</b> ⇒ Jade 那一行<b>完全不出现</b>；</li>
 *   <li>非空 ＝ 原因的 {@code Component} <b>JSON</b>（客户端反序列化后按玩家语言翻译，
 *       服务端不许提前 {@code getString()} —— 专用服务端没有模组的 lang 表）；</li>
 *   <li>判定/清空规则<b>只有一份</b>：{@link ShanhaiIdleReason#next(String, boolean)}
 *       与 {@link ShanhaiIdleReason.Store}。实现方不许自己另写一套「什么时候清」。比如</li>
 * </ol>
 */
public interface ShanhaiIdleReasonSource {

    /**
     * 服务端权威值：这条机器「为什么没跑」的原因（{@code Component} 的 JSON）。
     *
     * @return {@code ""} = 没有原因（Jade 那一行不出现）；非空 = 原因原文的 JSON
     */
    String shanhaiIdleReasonJson();
}
