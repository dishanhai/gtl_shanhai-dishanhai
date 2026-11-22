package com.shanhai.add.mixin;

import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.network.syncher.SynchedEntityData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 【创始现实修改模块】<b>写入点钳制</b>：任何一次"把血量写成 ≤0 / NaN / ±∞"的
 * 同步字段写入，都拦下来（受保护玩家）：出口 ① 改写成 {@code 1.0}，出口 ② 拉回 {@code getMaxHealth()}
 * （值已经落进字段了，直接按"回满"处理；两条出口的判据是同一套）。
 *
 * <h2>🔴 为什么必须挂在 {@code SynchedEntityData#set} 上（实机判据）</h2>
 * <pre>
 *   对手的杀人链【根本不经过 setHealth】—— 它直接裸写同步字段：
 *     LivingEntity#setHealth(float)  →  this.getEntityData().set(DATA_HEALTH_ID, Mth.clamp(v,0,max))
 *     对手                             →  this.getEntityData().set(DATA_HEALTH_ID, 0.0f)     ← 绕开 setHealth
 *   ⇒ 我们原有的 {@code ShanhaiSetHealthGuardMixin}（改 setHealth 的入参）<b>看不到这一笔</b>。
 *   实机（2026-11-20 20:3x）就是"服务端被 20/s 写成 0 ＋ 我们每 tick 补 20"打成平手，
 *   而每一拍那个 0 都已经同步给客户端 ⇒ 客户端每 tick 想弹死亡界面 ⇒ 20/s 的自锁环。
 * </pre>
 * <h2>🔴 1.20.1 的同步字段有<b>两条</b>写入出口，本类两条都挂（javap 证据见两个注入点各自那一段）</h2>
 * <pre>
 *   出口 ①（本地写）：setHealth ／ 任何 set(...) 派发
 *     public &lt;T&gt; void set(EntityDataAccessor&lt;T&gt;, T)          ← 只是转发
 *     public &lt;T&gt; void set(EntityDataAccessor&lt;T&gt;, T, boolean) ← 真正写 DataItem 的那一个
 *     ⇒ descriptor (Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V
 *     ⇒ 由本类第一个注入点（@ModifyVariable，下面第一个方法）钳。
 *
 *   出口 ②（网络同步写）：客户端元数据包把值直接灌进字段
 *     ClientPacketListener#handleSetEntityData → SynchedEntityData#assignValues(List)
 *       → assignValue → DataItem.setValue              ← 🔴 裸赋值，全程零 clamp
 *       → entity.onSyncedDataUpdated(accessor)
 *     ⇒ 这条路【不经过 set(…Z)V】⇒ 出口 ① 的 @ModifyVariable 一次都命中不了；
 *       而 LivingEntity#onSyncedDataUpdated 里没有 DATA_HEALTH_ID 分支 ⇒ 没有任何一处拦它。
 *     ⇒ 由本类第二个注入点（@Inject at RETURN，方法 m_135356_，下面第二个方法）钳。
 * </pre>
 * <p>⚠️ 出口 ② 在 1.20.1 上只有客户端会走（全 jar 扫字符串 {@code assignValues}：除声明处外只有
 * {@code ClientPacketListener} 一个引用点 —— 另一处 {@code AttributeMap} 是同名的另一个方法、无关），
 * 且 {@code handleSetEntityData} 开头是 {@code PacketUtils.ensureRunningOnSameThread} ⇒ 客户端主线程；
 * 服务端那条 {@code ServerPlayer#tick} 的血量包另由 {@code ShanhaiHealthPacketClampMixin} 管。
 *
 * <h2>🔴 判据里没有任何 mod 的名字</h2>
 * 只有两条：① 这次写的<b>是不是</b>一个会让活的实体"变成死的"的值（{@code <=0 / NaN / ±∞}）；
 * ② 这个实体<b>是不是</b>受保护玩家（{@code ShanhaiGuardEvents.isGuarded}，与客户端同源的那一份）。
 * 换个 mod、换个类名、换个方法名，判据一个字都不用改。
 *
 * <h2>为什么判据与动作都在普通类里</h2>
 * 本工程铁律：mixin 类体内<b>不许引用 Minecraft 成员</b>（@Shadow 字段/方法在 refmap 里没有条目）。
 * 所以这里只做三件事：形参对得上的 {@code Object value}、{@code (Object) this}、一次转发。
 * 第二个注入点同理：无参，只有 {@code (Object) this} ＋ 一次转发。
 *
 * <h2>为什么第一个注入点是 {@code require = 1}（第二个是 0）</h2>
 * 出口 ① 的目标方法在 1.20.1 上必然存在且<b>只有一个</b>（javap 已证）。找不到就该当场炸 ——
 * "钳制静默不存在、看起来像已保护"是这条线上最坏的失败形态。
 * 出口 ②（元数据同步）是<b>补漏的第二条腿</b>，且用 SRG 名写死（dev-run 里本就不命中）⇒ 用 {@code require = 0}：
 * 它不该有能力让整个客户端起不来，判读见第二个方法的注释。
 */
@Mixin(SynchedEntityData.class)
public abstract class ShanhaiHealthWriteClampMixin {

    /**
     * 把要写进同步字段的值交给我们判一遍。
     *
     * @param value 原值（{@code HEAD} 处还没写进 {@code DataItem}）
     * @return 真正要写进去的值：受保护玩家被写死 ⇒ {@code 1.0}；其余<b>逐字原样</b>
     */
    @ModifyVariable(
            method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2,
            require = 1)
    private Object shanhai$clampHealthWrite(Object value) {
        try {
            // 🆕 2026-11-20（触发源第 ② 类 health 的另一条腿）：<b>写同步血量字段的那一刻</b>抓一次栈。
            //   为什么必须在这里抓：对手可以 `entity.getEntityData().set(DATA_HEALTH_ID, 0.0f)`
            //   ——一次 setHealth 都不调，`ShanhaiSetHealthGuardMixin` 那条腿<b>看不到</b>它。
            //   判据（值会不会读成死的 / 宿主是不是受保护玩家 / 1 s 限流）全在普通类里，
            //   未命中时只花一次 instanceof ＋ 一次 float 比较，**不改任何行为**。
            com.shanhai.add.guard.stack.ShanhaiViolationProbe.onHealthDataWrite((Object) this, value);
        } catch (Throwable ignored) {
            // 抓栈坏了不许影响写同步字段
        }
        try {
            // (Object) this ⇒ 本类体内零 MC 成员引用（理由见类注释）。
            final Object clamped = ShanhaiGuardValueLayer.clampHealthDataWrite((Object) this, value);
            if (clamped != value && value instanceof Float) {
                // 🆕 2026-11-21（P2 ②）：<b>真的钳住了</b>（= 受保护玩家 ＋ 值会读成死的）
                //   ⇒ 同一拍抓一次栈喂给溯源。限流（同 tick 一条 ＋ 同类 200 tick 一条）在
                //   HotfixHealthClampStrike 里；喂证据这一步单独 try，绝不连累"钳制"本身
                //   （`clamped != value` 的身份比较是安全的：值层不钳时原样返回入参，
                //     钳时返回 Float.valueOf(...) —— 那是必然不同的一个对象）。
                try {
                    com.shanhai.add.guard.stack.ShanhaiViolationProbe
                            .onHealthClampDataWrite((Object) this, ((Float) value).floatValue());
                } catch (Throwable ignored) {
                    // 喂证据失败只意味着这一条没喂出去
                }
            }
            return clamped;
        } catch (Throwable ignored) {
            // 我们坏了也绝不许改变"写同步字段"这件原版行为
            return value;
        }
    }

    /**
     * 🔴 <b>出口 ②：元数据同步把值灌进字段【之后】</b>（{@code SynchedEntityData#assignValues(List)} 的 {@code RETURN}）。
     *
     * <h2>🔴 为什么 {@code set(…Z)V} 那条腿补不到这里（字节码实证，不是推测）</h2>
     * <pre>
     *   javap -p -c &lt;forge-1.20.1-47.4.16_mapped_parchment_2023.09.03-1.20.1.jar&gt;
     *     net.minecraft.client.multiplayer.ClientPacketListener#handleSetEntityData:
     *        6: invokestatic   PacketUtils.ensureRunningOnSameThread(…)      ← 客户端主线程
     *       26: invokevirtual  Entity.getEntityData:()LSynchedEntityData;
     *       30: invokevirtual  ClientboundSetEntityDataPacket.packedItems:()Ljava/util/List;
     *       33: invokevirtual  SynchedEntityData.assignValues:(Ljava/util/List;)V
     *     net.minecraft.network.syncher.SynchedEntityData#assignValues:
     *       14…82: for (DataValue) { itemsById.get(id); assignValue(item, value);
     *                                 entity.onSyncedDataUpdated(item.getAccessor()); }
     *       85…99: 写锁 unlock                                    ← 运行到 RETURN 时锁已释放
     *      121…126: entity.onSyncedDataUpdated(packedItems)
     *      129: return                                          ← 全方法【只有这一条 return】
     *   ⇒ {@code assignValue → DataItem.setValue} 是【裸赋值】（零 clamp），
     *     且这条路根本不经过 {@code set(EntityDataAccessor, T, Z)} ⇒ 第一个注入点看到 0 次。
     *   ⇒ LivingEntity#onSyncedDataUpdated(EntityDataAccessor) 里没有 DATA_HEALTH_ID 分支
     *     ⇒ 原版没有任何一处拦住 {@code -Infinity} 落进血量字段。
     * </pre>
     *
     * <h2>🔴 目标方法的 SRG 名与描述符（javap 实证，不是猜的）</h2>
     * <pre>
     *   javap -p -s -c -classpath &lt;joined-1.20.1-20230612.114412-srg.jar&gt;
     *     net.minecraft.network.syncher.SynchedEntityData
     *       public void m_135356_(java.util.List&lt;SynchedEntityData$DataValue&lt;?&gt;&gt;);
     *         descriptor: (Ljava/util/List;)V                    ← 运行时（SRG）就叫这个
     *       private final net.minecraft.world.entity.Entity f_135344_;  ← 持有实体的字段（开发名 entity）
     *   ⇒ 方法选择器 = "m_135356_(Ljava/util/List;)V"；@At("RETURN") 只命中上面那一条 return。
     *   ⇒ "属于谁"不在这里反射猜名字：走 ShanhaiGuardValueLayer 里那份已有的反射
     *     （entity / f_135344_ 两个名字都试，失败就明确降级）。
     * </pre>
     *
     * <h2>🔴 为什么 {@code remap = false} ＋ {@code require = 0}</h2>
     * <pre>
     *   · SRG 名 + remap=false：生产环境（运行时就是 SRG）按字面命中，不依赖 refmap
     *     （本工程既有做法：ShanhaiHealthPacketClampMixin 的 m_9240_ / ShanhaiHealthReadClampMixin 的 m_21223_）。
     *     ⚠ 代价：dev-run（官方映射，方法就叫 assignValues）里不会命中 —— 与上面那两个逐字相同。
     *   · require=0（fail-soft，与 shanhai_add.mixin.json 的 defaultRequire=0 同口径）：
     *     这一道是【补漏的第二条腿】，不该有能力让整个客户端起不来 ⇒ 命中不了时【没有效果、也不崩】。
     *     判读方式：日志里永远看不到 {@code src=sync_data} 的 health_clamp 行＝这一道没挂上
     *     （这一条不依赖 Mixin 自己打不打 WARN）。
     *   · at=RETURN（而不是 HEAD）：要判的就是"已经灌进字段的那个值"，
     *     而且此刻写锁已释放、onSyncedDataUpdated 已跑完 ⇒ 我们调 setHealth 不会重入本方法
     *     （setHealth 走的是 set(…Z)V = 出口 ①，不经过 assignValues）。
     * </pre>
     *
     * <h2>语义全部下沉到普通类（mixin 类体零 MC 成员引用，见类注释最后一节）</h2>
     * 判据与动作在 {@code ShanhaiGuardValueLayer#clampHealthAfterAssign}：
     * 只对<b>受保护玩家</b>、且<b>读到的血量已经会读成死的</b>（{@code <=0 / NaN / ±∞}）时才
     * {@code setHealth(getMaxHealth())}；
     * 未受保护 ／ 非玩家 ／ 正常值 ／ 任何 Throwable ⇒ <b>一个字节都不写</b>。
     *
     * @param ci 原版回调（本注入不取消、不改返回值，方法本身是 void）
     */
    @Inject(
            method = "m_135356_(Ljava/util/List;)V",
            at = @At("RETURN"),
            remap = false,
            require = 0)
    private void shanhai$clampHealthAfterAssign(CallbackInfo ci) {
        try {
            // 🆕 2026-11-21（P2 ②）：<b>修复之前</b>先抓一次现场 —— 此刻字段里还是那个坏值，
            //   等钳制把血写回 max 之后再读，{@code field_before} 就变成修好之后的值，证据没了。
            //   判据/限流全在那个普通类里；这一句自己 catch，绝不影响下面的钳制。
            com.shanhai.add.guard.stack.ShanhaiViolationProbe.onHealthClampAssign((Object) this);
        } catch (Throwable ignored) {
            // 喂证据失败只意味着这一条没喂出去
        }
        try {
            // (Object) this ⇒ 本类体内零 MC 成员引用（理由见类注释）。
            ShanhaiGuardValueLayer.clampHealthAfterAssign((Object) this);
        } catch (Throwable ignored) {
            // 我们坏了也绝不许改变"把同步数据灌进字段"这件原版行为
        }
    }
}
