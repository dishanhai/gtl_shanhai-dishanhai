---
enabled: true
version: 4.5.1
title: gt_shanhai 4.5.1 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)] - 【腾讯文档】gt_shanhai 模组问题与建议反馈表
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
link: [[https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link](https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link)] - 爱发电赞助链接！感谢您的赞助！
---

# gt_shanhai 4.5.1

发布日期：`2026 年 10 月 18 日`
此更新贡献者：**dishanhai**——`核心代码贡献者`

接在 4.5.0 后面。商店、货币、星律通配样板、原初模块和配方都有改动。

## 商店

- 商品编辑里，星火成本上方有`设为免费`。点开后清空星火、币种、物品、流体、无线电网 EU 五条通道，购买不扣任何东西。再点`免费中`可以重新填成本，刚才清掉的花费要自己填回去。
- 五条通道都空、又没点`设为免费`时，保存会被拦住，提示去点那个按钮。已经存成空成本的商品按免费商品生效。
- 免费商品可以购买。出售会被拦住，提示`免费商品不能出售`。详情和花费预览写`免费`，悬停写`免费商品不可出售`。
- 详情页按奖励表写出每一项的数量和百分比。随机模式标`百分比为单次抽取`。自选模式标`百分比为表内权重`，给哪一项仍由玩家点选。全部模式写`必得`。FTBQ 表里商店不发放的非物品奖励会标出来。表还没同步时写`奖励表未同步或不存在`。
- 选择奖励界面右侧也写出数量和表内权重百分比。
- 商店设置新增`获取预览模式`。方格是图标格，奖励表在格子下方写概率，获得和花费在格子下方写数量。条形是名称列表，概率或数量写在右侧。选择写进 `config/gt_shanhai/shop_client_ui.json` 的 `rewardPreview`。没改过的客户端仍是方格。
- 商店设置内容变多之后可以滚动，滚出视口的输入框会隐藏。
- 商品描述编辑区可以切换中文输入法。`ESC` 关闭、`Ctrl+回车` 确认仍可用。
- 新增`AE 注入限额`。商店设置和模组配置里都有，配置键是 `shop.aeInjectMin`，默认 64，最小 1。AE 模式下，单种物品这次数量低于这个数就进背包，达到才注入 AE。设 1 时，网络吃得下就注入。流体不受这项影响。`AE 禁止注入`打开时这项不生效。整批已经达到 SDA 打包阈值时，没进 AE 的仍随整批打包。

## 货币中心

- `currency_rates.json` 里的数字仍是面值，铜币为 1，往上按 4 的幂次排。文件不会被这次更新改写。
- 兑星火和币种互换改按兑星火价结算。白金及以下（铂金币，面值 ≤ 256）1 面值换 1 星火，铜币到铂金币买东西跟以前一样。
- 更高的币面值每 ×4，星火只 ×2。锇币面值 1024，兑星火 512。时空币面值 1048576，兑星火 16384。
- 币种互换用两边的兑星火价：`floor(源数量 × 源兑星火价 / 目标兑星火价)`。先把高阶币拆成铜币再转星火，换不到旧的满额面值。
- 转成星火和星火转出是同一价格，按这个价格来回换不亏不赚。高阶币换成星火比旧面值少，用星火换回高阶币也比旧面值便宜。
- 货币列表悬停和右侧详情同时写出面值和兑星火价。兑换、转星火、快速售出的提示同步改成兑星火价。特殊货币仍不参与比例兑换和转星火，去兑换中心。

## 星律通配样板

- 右键界面分成`自动生成`和`手动更改`。两组共用同一块区域，点哪组显示哪组。
- `自动生成`只展示样板生成工具写下的底稿。放进放入格、后来写进去的配方不出现在这一页。
- `手动更改`是 14 列 × 5 行，共 70 格。放入编码样板后，匹配到配方 ID 就写进这张通配样板。取出样板后撤下对应配方。旧存档放入格不足 70 格时，只读已经有的格子。
- 没有自动配方、放入格里已经有样板时，打开就停在`手动更改`。
- 顶栏计数改成`自动 x / y 张 · 手动 z 张`。
- 星律样板总成的物品说明改成 20 个通配符槽。格数和页数仍由模组配置决定，默认每页 10 格、2 页。

## 原初模块

- 原初生物演化协议的物品输入改为 1 格，物品输出改为 72 格。流体仍是 3 入 3 出。两条协议配方的物品产出分别是 39 和 57，催化剂各占 1 个物品输入，和配方类型界面一致。
- 原初生物演化协议新增温室高速培养。不消耗 `dishanhai:greenhouse_speed_cultivation_module`，没有流体。耗时 200，每刻 30 EU。39 项产出概率都是 100%：橡木、橡胶木、白桦、丛林木、金合欢、深色橡木、红树、樱花、云杉的原木各 64，对应树苗各 2（红树是繁殖体）；粘性树脂、苹果、植物球、南瓜、甜菜根、西瓜、甜浆果、发光浆果、小麦、胡萝卜、甘蔗、海带、仙人掌、马铃薯、可可豆、棕色蘑菇、红色蘑菇、下界疣、竹子、藤蔓、海泡菜各 64。配方 id 是 `shanhai:primordial_bio/greenhouse_speed_cultivation_use`。
- 原初生物演化协议新增高速生物数据。不消耗 `dishanhai:high_speed_bio_data_module`，没有流体。耗时 20，每刻 491520 EU。57 项概率产出，多数是 40 个，下界之星和龙蛋是 1 个。皮革、线、腐肉、铁锭等重复项各自单独判定。龙蛋 100%，下界之星 15%。完整清单在 JEI 里。配方 id 是 `shanhai:primordial_bio/high_speed_bio_data_use`。
- 两条协议配方写在整合包 `kubejs/server_scripts/[server_scripts]shanhai_primordial_bio.js`。催化剂由下面的压缩机配方做出。
- 各原初模块控制器正面改用自己的贴图。待机和工作中各一张，不再共用物质重组核心的正面。

## 原初逆熵构成

- 原初永恒熔炼炉新增配方类型`原初逆熵构成`。追加在星焰跃迁后面，已经保存的配方类型下标不变。
- 槽位是 1 个流体输入、1 个物品输出。把分子解构里「粉 → 流体」反过来：144 mB 流体构成 1 份粉。多份粉按同样比例生成，电压和耗时沿用原来的分子解构。
- 只处理恰好是粉、并且流体毫升数等于粉数 × 144 的分子解构。沙子、方块，以及比例对不上的配方不生成。
- 配方 id 是 `gt_shanhai:primordial_reverse_entropy_constitution/` 加上原配方的命名空间和路径。进服、数据包重载和 KubeJS 配方窗口都会按当时的分子解构重新生成。

## 配方数据

- 压缩机新增高速生物数据模块：2131233 台生物模拟实验室压成 1 个 `dishanhai:high_speed_bio_data_module`。耗时 100，每刻 8388608 EU。配方 id 是 `gt_shanhai:compressor/biological_simulation_laboratory_high_speed_bio_data_module`。默认开启。
- 压缩机新增终极世界碎片高速采集模块：1632333 台大型世界碎片收集机压成 1 个 `dishanhai:ultimate_world_fragment_miner_module`。耗时 100，每刻 524288 EU。配方 id 是 `gt_shanhai:compressor/large_fragment_world_collection_machine_ultimate_world_fragment_miner_module`。默认开启。
- 压缩机新增温室高速培养模块：3123433 台大型温室压成 1 个 `dishanhai:greenhouse_speed_cultivation_module`。耗时 100，每刻 2097152 EU。配方 id 是 `gt_shanhai:compressor/large_greenhouse`。默认开启。
- 量子操纵者新增 16 级纯化水：2147483647 mB 水，不消耗电路 3 和 `dishanhai:wzsw`，产出 2147483647 mB `gtceu:grade_16_purified_water`。耗时 4，每刻 33554432 EU。配方 id 是 `gtceu:qft/grade_16_purified_water`。默认开启。
- 反熵冷凝新增 16 级纯化水制蓝冰：2000 mB `gtceu:grade_16_purified_water` 做成 1 个蓝冰。耗时 100，每刻 32768 EU。配方 id 是 `gt_shanhai:antientropy_condensation/grade_16_purified_water_blue_ice`。默认开启。
- 维度扭曲新增时间逆转协议宇宙加强：不消耗电路 4、和谐之眼 ×64、`dishanhai:time_reversal_protocol`，再消耗氢 1310012 mB、氦 1310012 mB、16 级纯化水 131000 mB。耗时 240，每刻 140799765315555 EU。配方 id 是 `dishanhai:distort/distort_time_reversal_protocol_cosmos_plus`。默认开启。
- 这条扭曲产出 24 种流体和 120 种粉。时空 256000 mB。原始恒星物质等离子体、夸克胶子等离子体、重夸克简并物质等离子体各 1310720 mB。中子、重轻子混合物、氢到氡的单质气体、汞、氘、氚、氦-3、未知水和宇宙素各 13107200 mB。粉从碳、磷一直到超重元素，以及碧玉、硅岩系、铿铀、三钛、秘银、山铜、末影、精金、振金、注魔、塔兰、龙和星辉，每种 131072。完整清单在 JEI 里。
- 移除星焰跃迁的不稳定性等离子体：10000 mB `gtceu:instability` 做成 10000 mB `gtceu:instability_plasma`，耗时 20，每刻 7864320 EU，炉温 10000。配方文件 `stellar_lgnition/plasma/instability_plasma.json` 已删除。
