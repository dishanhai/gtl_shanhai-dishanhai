package com.shanhai.common.droplet;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 爆炸的<b>拥有者豁免</b>核心：从 {@code ExplosionEvent.Detonate#getAffectedEntities()} 里摘掉拥有者。
 *
 * <h2>一、为什么单独抽成"零 Minecraft 依赖"的纯函数</h2>
 * 这一行是本轮唯一的行为改动（拥有者不再吃爆炸伤害与击退），必须能<b>离线断言</b>：
 * 本类只 import {@code java.util}，所以可以用一个不启动游戏的
 * {@code javac + java} 小断言直接验证"拥有者被摘掉 / 别人被保留"，
 * 并且<b>注掉这里的 {@code removeIf} 那一行</b>时断言必须变红（负例自证）。
 * 处理器 {@link ShanhaiDropletExplosionProtection} 调的就是<b>这一个</b>函数（不是复制一份）。
 *
 * <h2>二、语义</h2>
 * <ul>
 *   <li>{@code ownerUuid == null}（没有拥有者 / 不是水滴炸的）⇒ <b>一个都不摘</b>
 *       （别的爆炸、别的玩家、生物、方块全部照旧）。</li>
 *   <li>否则把 id 等于 {@code ownerUuid} 的条目从<b>原列表</b>里移除（{@code removeIf} 原地改，
 *       因为 {@code Detonate} 持有的就是 {@code Explosion} 随后要遍历的那张表）。</li>
 *   <li>返回真正移除的条数（0 = 拥有者本来就不在受击表里）。</li>
 * </ul>
 */
public final class ShanhaiDropletExplosionOwnership {

    /**
     * 从受爆炸影响的实体表里移除拥有者。
     *
     * @param affected  原列表（{@code ExplosionEvent.Detonate#getAffectedEntities()} 返回的那个实例）
     * @param idOf      取实体的 UUID（生产代码传 {@code Entity::getUUID}）
     * @param ownerUuid 要豁免的拥有者 UUID；{@code null} ⇒ 什么都不做
     * @param <T>       实体类型（纯逻辑测试里是任意可映射到 UUID 的类型）
     * @return 实际移除的条数
     */
    public static <T> int removeOwner(List<T> affected, Function<? super T, UUID> idOf, UUID ownerUuid) {
        if (affected == null || affected.isEmpty() || ownerUuid == null) {
            return 0;
        }
        final int before = affected.size();
        affected.removeIf(candidate -> candidate != null && ownerUuid.equals(idOf.apply(candidate)));
        return before - affected.size();
    }

    private ShanhaiDropletExplosionOwnership() {}
}
