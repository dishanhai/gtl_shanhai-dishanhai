# 山海商店頻道續辦交接（步驟 ①）

## 已確認的主任務

- #商店 原始要求：讓商店物品選擇器使用 `C:\Users\dishanhai\Desktop\SH-Better-UI` 的模糊玻璃效果，並參照 `gt_shanhai:recipe_type_pattern_buffer` 星律樣板總成介面，為商店本體加入中性煙灰玻璃外觀。頻道附圖分別是現有金框選擇器與中性煙灰玻璃參考畫面。
- 使用者已選定：新增第三種可選主題，保留 `classic` 經典金框與 `glass` 動態玻璃；新主題 ID 為 `smoke`。選擇器跟隨商店主題；`classic` 保持原樣，兩種玻璃主題使用各自對應的玻璃效果。
- 前一個「商店煙灰玻璃整合」流程只完成唯讀盤點；其後步驟未交付。頻道中的「繼續」指延續上述商店工作。`task_done` 缺失另由流程步驟 ② 調查。

## 共用接口與檔案邊界

| 責任 | 檔案／接口 | 目前可見狀態與交接要求 |
| --- | --- | --- |
| 共用玻璃 | SH-Better-UI 的 `GlassTheme`、`GlassColors`、`GlassSurface` | `GlassTheme` 目前只有 `CLASSIC`、`GLASS`；`GlassColors.SMOKE` 已有中性煙灰色盤，`GlassSurface.smokePanel` 先模糊再繪底板，`smokeControl` 不重複模糊。新增煙灰主題時需覆蓋根板、區域、頁籤及文字色，避免沿用 `GlassPalette` 的動態玻璃色。此專案主目錄有未提交變更，尤其 `GlassColors`、`GlassSurface` 尚未追蹤；本步未修改它。 |
| 本機設定與映射 | `ClientShopUiSettings`、`ShopTheme` | 設定以字串儲存；目前 `normalizeTheme` 僅接受 `glass`，其他值回退 `classic`；`ShopTheme.current()` 只分經典／動態玻璃。三態映射須保持既有設定相容，未知值回退 `classic`。 |
| 選擇器 | `MultiPickerScreen`，必要時 `ShopDialogUi` | 選擇器先繪父畫面，再繪根板、搜尋、側欄、網格、清單及按鈕；搜尋建議、排序選單、拖曳圖示及 tooltip 在延後頂層。網格與清單有裁切；模糊不可用時目前有暗色回退。根板模糊需先於內容，維持縮放、偏移、裁切與頂層順序。 |
| 商店與設定畫面 | `ShopScreen`、`ShopSettingsScreen`、`ShopWelcomeScreen`、`zh_cn.json`、`en_us.json` | 主畫面在每幀 `applyTheme(ShopTheme.current())`；設定與引導頁目前各用布林值表示兩態。第三選項、即時預覽、保存及重開還原需一起處理。新增介面文字放語言檔，不硬編碼在 Java／Kotlin；tooltip 中文使用簡體。 |

此流程的 `gt_shanhai` 副本位於 `pixelsky/flow-e5e292-a`，工作樹開始時乾淨；所需 SH-Better-UI 原始碼位於另一專案主目錄，並未包含在本分支。此副本的 `build.gradle` 預設從相對路徑 `../SH-Better-UI/build/libs/sh_better_ui-1.0.2-dev.jar` 取依賴；在流程副本中建構時須以 `-PshBetterUiJar=<實際 devJar 絕對路徑>` 指向已構建產物。後續改動只在各自授權的獨立副本內進行，保留兩專案既有未提交內容。

## 後續驗收清單

- 三主題切換：`classic`、`glass` 舊值外觀及儲存相容；`smoke` 可選、可保存並在重開後還原；未知值回退 `classic`；設定頁與引導頁的切換及預覽一致。
- 三主題下的選擇器：搜尋與建議、物品／流體選取、拖曳增刪及排序、數量編輯與滾輪、側欄與網格捲動、確認及取消仍正常。
- 視覺與回退：核對模糊範圍、縮放與裁切、父畫面遮罩、彈出選單與 tooltip 層次；模糊不可用時文字與操作仍清晰可用。
- 構建與驗證：先構建 SH-Better-UI devJar，再執行兩專案相關聚焦測試及 `gt_shanhai` 構建；區分自動測試與遊戲內視覺、操作驗收。步驟 ① 僅完成頻道閱讀及接口盤點，尚未執行測試、構建或遊戲驗證。

## 待辦

- 步驟 ③ 依上述契約在獨立副本推進商店主題、選擇器及畫面實作，交付檔案與可重現驗證結果。
- 步驟 ④、⑤ 分別做聚焦測試與代碼審查；步驟 ⑥ 處理發現並重驗；步驟 ⑦ 彙總交付及 `task_done` 調查結果。
