# 山海：雲庫玻璃互動第④步交接（4.9.0）

## 契約與操作方式

- `StellarCloudUiContract` 依第③步反射測試提供 records、`LibraryActions`、`LibraryController`，另提供 `ConfiguratorView/Actions/Controller`、`CoreView/Actions/Controller`。集合快照不可變；初始 `synchronizedReady=false`，未同步不能送業務動作。
- Controller 的 `state()` 只在服務端快照到達時更新；展開／收合只改 `localState()`。選取採可見列 UUID，篩選隱藏選取仍保留身份，取回要求重新顯示。
- `pendingRequestId()`、`control(ActionKind)`／`quickWithdrawControl(index)`、`toggleControl()`、`bindControl()`／`unbindControl()` 及 `feedbackKey()` 供呈現使用。`lastResult()` 只保存配對成功的服務端回執。等待期間不重送業務動作；五秒後只請求一次刷新，保留 pending，刷新可重送已完成回執。
- `StellarCloudUiWidgets.intent(...)` 供第⑤／⑥步建立使用 controller 的客戶端按鈕；復用既有煙色、ButtonFeedback、GuiMotion 及停用命中攔截。原 `action(...)` 供舊呈現保留使用，仍是伺服端執行。

## 入口與同步

- `StellarCloudLibraryUi.create` 的根控件已加入 `StellarCloudUiSync.Library(session, player, inbox)`。第⑤步取得該區域變量並用其 `controller` 重寫互動；快照／結果已由 bridge 自動接收，更新畫面時只讀 controller。原件、預覽、Component 與 tooltip 沿既有槽及 supplier 同步。
- `Session.uiSnapshot()` 提供當頁、選取、成員及可操作狀態。`Session.uiAction(...)` 再核對本機容器／授權、來源 ItemStack、取回 UUID／可見性或成員 UUID，再委派原有上傳、取回與權限流程；未改供應、配方、租約或 SavedData 格式。
- `StellarCloudConfiguratorUi.create(Function<Player, ConfiguratorView>, BiFunction<Player, Boolean, String>, patterns)` 已由星律內嵌 configurator 呼叫；`StellarCloudUiSync.Configurator` 已加入根控件。`cloudUiDisabledReason` 復用啟停校驗，實際動作仍進 `changeCloudEnabled`。
- `StellarCloudAECoreUi.create(Function<Player, CoreView>, BiFunction<Player, Boolean, String>)` 已由 AE 核心呼叫；`StellarCloudUiSync.Core` 已加入根控件。實際動作仍進 `changeBinding`，保留容器、距離、主人及 AE 身份校驗。
- 本步只建立橋接；既有三頁呈現仍可工作。第⑤／⑥步需將呈現操作切換為 controller，避免同一次點擊同時走舊回呼與新意圖。
- `StellarCloudUiRequests` 的去重限本次畫面，不宣稱跨存檔 exactly-once；重複 id 回送既有結果，改靶／過期 id 拒絕，沒有自動重送原件動作。

## 文件責任

- 第⑤步：只改 `StellarCloudLibraryUi.java`、`StellarCloudLibraryLayout.java`。
- 第⑥步：只改 `StellarCloudConfiguratorUi.java`、`StellarCloudAECoreUi.java`。
- 契約、同步橋、共用控件、三個機器入口及語言修正回到第④步。新增五鍵位於現有 `zh_cn.json`／`en_us.json`，簡體 tooltip。
- 第⑦步測試、第⑧步唯讀審查、第⑨步版本日誌與最終摘要。現有工作區的既有改動不應由呈現步驟順便整理或覆寫。

## 證據與待驗證

- 第③步原有 10 項測試：先重現 9 項契約缺失紅燈，補齊後全部執行至斷言並通過。本步另增 2 項去重／回執與 4 項 codec／次頁 controller 測試。
- 工作區第一輪定向回歸為 32 類 127 項通過。提交前將 Git 暫存內容完整輸出至 `tmp/cloud-step4-baseline-check/`，排除依賴未追蹤來源的可能，再擴充側欄命中與相關執行緒回歸：34 類 160 項通過，0 失敗／錯誤／略過；Java／Kotlin、`build`／`reobfJar`／`releaseJar` 通過。沒有排除本次選定的測試案例。
- 最終命令：本機 Gradle 8.8，`-p tmp/cloud-step4-baseline-check -PshBetterUiJar=C:/Users/dishanhai/Desktop/SH-Better-UI/build/libs/sh_better_ui-1.0.3-dev.jar build test --tests '*StellarCloud*' --tests '*RecipeTypePatternBufferVerticalSidebarTest' --tests '*RecipeTypePatternBufferStock*' --tests '*StellarSwitchNullTextureTest' --tests '*StellarSidebarHitBoundsTest' --tests '*PrimordialThreadedRecipeLogicTest' --no-daemon --console=plain`。隔離快照使用原有本機 libs 與唯讀 lib_source 參考，沒有新增依賴。
- 日誌：`tmp/cloud-step4-index-verified.log`；XML 封存：`tmp/cloud-step4-verification/final-test-results/`。隔離快照發行包及 reobf 輸出均為 16,633,848 bytes，SHA-256：`AA5F91253CC18E6705B5CB9193EB924682F43B6F554AF3A59E677BD0FC014054`。固定核驗包：`tmp/verified/cloud-ui-step4-AA5F9125/gt_shanhai-4.9.0.jar`；SH-Better-UI 仍為外部依賴。
- 唯一回歸曾是既有來源字串檢查要求保留 `changeBinding(player, bind)`；已恢復明確 lambda 委派，沒有更動既有測試斷言。
- 未執行全套測試、實機、部署、重啟或推送。指定 `D:\pcl\.minecraft\versions\GregTech Leisure-增强版` 不存在；三頁不同 GUI 縮放、遮擋、網路實際時序、原件守恆與兩人競態仍須第⑦步驗收，已記入本機 `TODO.md`。上傳來源快照與服務端拒絕的完整遊戲時序不能用純控制器及 codec 測試取代。
- 使用者後續明確授權「將變更納入目前管理一同寫」，因此納入既有雲庫前置來源、資源、相關側欄／空材質相容修復、原生雲庫替換外部適配的清理及第③步測試；基線與第④步分別提交。未改 Git ignore 規則、依賴版本或其他人的共用 UI 庫。
- 前置基線提交：`eeaffec4`，72 個文件；其隔離快照已確認 Java／Kotlin 與測試編譯成功，互動測試 10 項中 9 項為契約缺失的預期紅燈，證據 `tmp/cloud-step4-baseline-red.log`、`tmp/cloud-step4-verification/baseline-red.xml`。第④步提交僅包含其後 14 個來源／測試差異及本交接文件。第⑤／⑥步可從這兩筆提交之後的 HEAD 分支，三入口與業務依賴均已納管。
- 第④步獨立來源差異已封存於 `tmp/cloud-step4-delta/step4-only.patch`：9 個既有文件差異及 5 個新增來源／測試，沒有含入其他既有改動。`before/` 提供排除本步後的前置文件快照，`after/` 為本步來源快照，`manifest.json` 保存本步 14 個工作區文件的 SHA-256。已執行正向／反向 `git apply --check`，未改動來源或 Git index。此封存供前置基線負責人核對，並非已提交或已交付的替代品。
