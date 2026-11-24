---
navigation:
  title: 归墟克尔奇点引擎
  parent: machine/guixu_index.md
  position: 25
categories:
  - gt_shanhai
  - primordial
  - guixu
item_ids:
  - gt_shanhai:guixu_kerr_singularity_engine
  - gt_shanhai:kerr_orbit_blank
  - gt_shanhai:kerr_orbit_preform
  - gt_shanhai:kerr_oriented_core
  - gt_shanhai:kerr_spacetime_laminate
  - gt_shanhai:kerr_fractured_core
---

# 归墟克尔奇点引擎

将引擎装在已成型的归墟本机模块位，安装物品输入、输出总线，并让本机生效的[彭罗斯黑洞遏制场](guixu_penrose_containment_field.md)进入遏制中或超稳态。引擎使用归墟的输入能源仓。投入由视界物质解压制得的 <ItemLink id="gt_shanhai:kerr_orbit_blank" />，用面板按钮指定下一批的注入方向。

- **顺旋内轨**每批为主黑洞增加 12 点自旋。高自旋让内轨更贴近视界，稳定时回收 <ItemLink id="gt_shanhai:kerr_oriented_core" />。绕行时稳定度过低会累计偏移，可能回收 <ItemLink id="gt_shanhai:kerr_fractured_core" />。
- **逆旋外轨**每批降低 9 点自旋。高自旋让外轨向外分开，可回收 <ItemLink id="gt_shanhai:kerr_spacetime_laminate" />。两条轨道在低自旋时都可能只回收 <ItemLink id="gt_shanhai:kerr_orbit_preform" />。
- **共享自旋**保存在主机上，范围 0～100，初始为 20。每台引擎同时处理一批，可让两台引擎分别负责内外轨。面板会显示预计产物和回收余量。开启「仅成品」后，预计产物为晶核或层板时才投料。起步蓄积自旋时，可关闭此选项。
- **失稳或断电**会暂停绕行。输出总线满时保留已定型的产物，等总线清出空间再回收。黑洞坍缩会将主机自旋重置为 20。

引擎开始绕行后，主黑洞显示蓝色内轨或金色外轨。光弧随自旋改变半径，稳定度越低，内轨的扰动越明显。回收成功时亮弧向外散开。

预制件及待返工晶核可在归墟用额外中子素板和时空流体返工。定向晶核和时空层板可用于扩建克尔引擎，层板也可用于组装粒子束导向管道机械方块。

## 动画预览

管理员看向已成型的归墟控制器，输入 `/shanhai guixu blackhole_preview` 即可播放约 22 秒的演出。也可指定控制器坐标 `/shanhai guixu blackhole_preview <x> <y> <z>`。演出依次展示双轨绕行与回收、超稳态、稳定度下滑、失稳和坍缩，仅使用预览画面。末尾加 `stop` 可提前恢复实时画面。
