# 山海流程錯誤紀錄

## 2026-11-06 商店頻道交接文件被忽略

- 狀態：resolved
- 現象：新增 `TODO.md` 後，`git status --short` 仍顯示乾淨。
- 原因：本分支 `.gitignore` 的 `*.md` 規則排除了交接文件。
- 處理：提交前使用 `git add -f TODO.md .learnings/ERRORS.md`，再執行要求的 `git add -A`、檢查暫存區並提交。
- 後續：流程交接若使用 Markdown，應先以 `git check-ignore -v` 核對是否被排除，避免誤認為已交付。

## 2026-11-06 PowerShell 下的 rg 檔名模式

- 狀態：resolved
- 現象：將 `build.gradle*` 直接作為 `rg` 路徑參數時得到 Windows 檔名語法錯誤。
- 處理：改用 `rg -g 'build.gradle*' -g 'settings.gradle*'` 指定檔名模式，成功讀到 SH-Better-UI devJar 的依賴路徑。
