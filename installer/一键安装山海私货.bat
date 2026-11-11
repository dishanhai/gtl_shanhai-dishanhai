@echo off
setlocal
set "SHANHAI_INSTALLER=%~f0"
powershell.exe -NoLogo -NoProfile -STA -ExecutionPolicy Bypass -Command "$s=[IO.File]::ReadAllText($env:SHANHAI_INSTALLER,[Text.Encoding]::UTF8); $marker='# POWERSHELL-BEGIN'; $i=$s.LastIndexOf($marker); if($i -lt 0){exit 1}; & ([ScriptBlock]::Create($s.Substring($i+$marker.Length)))"
if errorlevel 1 echo Installation was cancelled or failed. See the message above.
pause
exit /b
# POWERSHELL-BEGIN
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.IO.Compression.FileSystem
$source = [IO.Path]::GetDirectoryName($env:SHANHAI_INSTALLER)
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = '请选择游戏根目录（包含 mods 和 kubejs 的文件夹）'
if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return }
$target = $dialog.SelectedPath
if (-not (Test-Path -LiteralPath (Join-Path $target 'mods') -PathType Container) -or
    -not (Test-Path -LiteralPath (Join-Path $target 'kubejs') -PathType Container)) {
    [System.Windows.Forms.MessageBox]::Show('请选择同时包含 mods 和 kubejs 文件夹的游戏根目录。', '安装失败') | Out-Null
    exit 1
}
$optional = [System.Windows.Forms.MessageBox]::Show('是否安装非强制优化模组？', '可选模组', 'YesNo', 'Question') -eq 'Yes'
$kirin = [System.Windows.Forms.MessageBox]::Show('是否替换其他作者的 Kirin_startup.js？通常只有出现流体属性重复注册错误时才需要。', '可选脚本', 'YesNo', 'Question') -eq 'Yes'
$backup = Join-Path $target ('山海安装备份-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8))
$installStats = @{ Count = 0 }
function Install-File([string]$file, [string]$relative) {
    $dest = Join-Path $target $relative
    if (Test-Path -LiteralPath $dest -PathType Leaf) {
        $saved = Join-Path $backup $relative
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($saved)) -Force | Out-Null
        Copy-Item -LiteralPath $dest -Destination $saved -Force
    }
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($dest)) -Force | Out-Null
    Copy-Item -LiteralPath $file -Destination $dest -Force
    $installStats.Count++
}
function Install-Tree([string]$folder, [string]$destination) {
    $root = Join-Path $source $folder
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw "缺少安装来源：$root" }
    foreach ($file in Get-ChildItem -LiteralPath $root -File -Recurse -Force) {
        $relative = $file.FullName.Substring($root.Length).TrimStart('\')
        if (-not $kirin -and $relative -eq 'startup_scripts\Kirin_startup.js') { continue }
        $destinationFile = Join-Path $destination $relative
        Install-File $file.FullName $destinationFile
        $copied = Join-Path $target $destinationFile
        if ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $copied -Algorithm SHA256).Hash) {
            throw "复制校验失败：$destinationFile"
        }
    }
}
function Install-Archives([string]$folder, [string]$destination, [string]$extension, [string[]]$ExcludedFiles = @()) {
    $root = Join-Path $source $folder
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw "缺少安装来源：$root" }
    foreach ($file in Get-ChildItem -LiteralPath $root -File -Force | Where-Object { $_.Extension -ieq $extension }) {
        if ($file.Name -in $ExcludedFiles) { continue }
        Install-File $file.FullName (Join-Path $destination $file.Name)
    }
}
function Read-ZipText($archive, [string]$name) {
    $entry = $archive.GetEntry($name)
    if (-not $entry) { return '' }
    $reader = New-Object IO.StreamReader($entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Get-TomlValue([string]$text, [string]$key) {
    $pattern = '(?m)^\s*' + [regex]::Escape($key) + '\s*=\s*(?:"([^"\r\n]*)"|''([^''\r\n]*)''|(true|false))\s*(?:#.*)?$'
    $match = [regex]::Match($text, $pattern)
    if ($match.Success) { return ($match.Groups[1].Value + $match.Groups[2].Value + $match.Groups[3].Value) }
    return ''
}
function Get-ArchiveMods($archive, [string]$file, [int]$depth = 0) {
    if ($depth -gt 8) { throw "内嵌模组层级过深：$file" }
    $content = Read-ZipText $archive 'META-INF/mods.toml'
    $manifest = (Read-ZipText $archive 'META-INF/MANIFEST.MF') -replace '\r?\n ', ''
    $manifestVersion = ''
    if ($manifest -match '(?m)^Implementation-Version:\s*([^\r\n]+)') { $manifestVersion = $Matches[1].Trim() }
    # 排除多行说明文字，防止其中的示例表头被当作实际模组声明。
    $content = [regex]::Replace($content, '(?s)""".*?"""|''''''.*?''''''', '""')
    $sections = @([regex]::Matches($content, '(?ms)^\s*\[\[([^\]\r\n]+)\]\][^\r\n]*\r?\n(.*?)(?=^\s*\[|\z)'))
    $mods = @()
    foreach ($section in $sections) {
        if ($section.Groups[1].Value.Trim() -ne 'mods') { continue }
        $id = Get-TomlValue $section.Groups[2].Value 'modId'
        if (-not $id) { throw "模组声明缺少 ID：$file" }
        $version = Get-TomlValue $section.Groups[2].Value 'version'
        if ($version -eq '${file.jarVersion}') { $version = $manifestVersion }
        $mods += [pscustomobject]@{ Id = $id; Version = $version; File = $file; Embedded = ($depth -gt 0); Dependencies = @(); BundleRanges = @() }
    }
    foreach ($section in $sections) {
        $header = $section.Groups[1].Value.Trim()
        if ($header -notmatch '^(?:dependencies\.(.+)|mods\.dependencies)$') { continue }
        $owner = ([string]$Matches[1]).Trim('"', "'")
        if (-not $owner) {
            if ($mods.Count -ne 1) { throw "依赖所属模组不明确：$file" }
            $owner = $mods[0].Id
        }
        $data = $section.Groups[2].Value
        $id = Get-TomlValue $data 'modId'
        if (-not $id) { throw "依赖声明缺少 ID：$file" }
        $dependency = [pscustomobject]@{
            Id = $id; Range = (Get-TomlValue $data 'versionRange')
            Mandatory = ((Get-TomlValue $data 'mandatory') -eq 'true')
        }
        foreach ($mod in $mods | Where-Object { $_.Id -eq $owner }) { $mod.Dependencies += $dependency }
    }
    $mods
    $metadata = Read-ZipText $archive 'META-INF/jarjar/metadata.json'
    if ($metadata) {
        foreach ($jar in ($metadata | ConvertFrom-Json).jars) {
            $entry = $archive.GetEntry($jar.path)
            if (-not $entry) { throw "内嵌依赖文件缺失：$file -> $($jar.path)" }
            $stream = $entry.Open()
            $memory = New-Object IO.MemoryStream
            try {
                $stream.CopyTo($memory)
                $memory.Position = 0
                $nested = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
                try {
                    foreach ($mod in @(Get-ArchiveMods $nested "$file -> $($jar.path)" ($depth + 1))) {
                        if ($mod.Id -eq $jar.identifier.artifact -or $mod.Version -eq $jar.version.artifactVersion) {
                            $mod.BundleRanges += [string]$jar.version.range
                        }
                        $mod
                    }
                } finally { $nested.Dispose() }
            } finally { $stream.Dispose(); $memory.Dispose() }
        }
    }
    if (-not $content -and $depth -eq 0 -and -not $metadata -and
        $manifest -notmatch '(?im)^(FMLModType|MixinConfigs|TweakClass):') {
        throw "无法识别 Forge 模组或库：$file"
    }
}
function Get-Mods([string]$path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try { Get-ArchiveMods $archive ([IO.Path]::GetFileName($path)) } finally { $archive.Dispose() }
}
function Remove-JeiVersionPrefix([string]$text) {
    return $text -replace '(?<![\w.-])jei-\d+\.\d+(?:\.\d+)?-forge-(?=\d)', ''
}
function Compare-ModVersion([string]$left, [string]$right, [string]$id) {
    if ($id -eq 'jei') {
        $left = Remove-JeiVersionPrefix $left
        $right = Remove-JeiVersionPrefix $right
    }
    if ($left -ieq $right) { return 0 }
    # 数字段逐段比较，避免把 build.9 排在 build.14 之后。
    $pattern = '^(\d+(?:\.\d+)*)(.*)$'
    $a = [regex]::Match($left, $pattern)
    $b = [regex]::Match($right, $pattern)
    if (-not $a.Success -or -not $b.Success) { throw "无法可靠比较版本：$left / $right" }
    $numbersA = $a.Groups[1].Value.Split('.')
    $numbersB = $b.Groups[1].Value.Split('.')
    for ($i = 0; $i -lt [Math]::Max($numbersA.Count, $numbersB.Count); $i++) {
        $na = [decimal]0
        $nb = [decimal]0
        if ($i -lt $numbersA.Count) { $na = [decimal]$numbersA[$i] }
        if ($i -lt $numbersB.Count) { $nb = [decimal]$numbersB[$i] }
        if ($na -ne $nb) { return $na.CompareTo($nb) }
    }
    $tailA = $a.Groups[2].Value
    $tailB = $b.Groups[2].Value
    if ($tailA -eq $tailB) { return 0 }
    if ($tailA -match '^-\d' -and $tailB -match '^-\d') {
        return Compare-ModVersion $tailA.Substring(1) $tailB.Substring(1)
    }
    # 同一数字主版本下，复杂尾码不进行猜测。
    $suffixPattern = '^[.-]?([a-zA-Z]+)(?:[.-]?(\d+))?$'
    $suffixMatchA = [regex]::Match($tailA, $suffixPattern)
    $suffixMatchB = [regex]::Match($tailB, $suffixPattern)
    if (($tailA -and -not $suffixMatchA.Success) -or ($tailB -and -not $suffixMatchB.Success)) {
        if (-not $tailB -and $tailA.StartsWith('+')) { return 1 }
        if (-not $tailA -and $tailB.StartsWith('+')) { return -1 }
        throw "无法可靠比较版本尾码：$left / $right"
    }
    $aliases = @{ cr = 'rc'; ga = ''; final = ''; release = '' }
    $shortAliases = @{ a = 'alpha'; b = 'beta'; m = 'milestone' }
    $qualifiers = @('alpha', 'beta', 'milestone', 'rc', 'snapshot', '', 'sp')
    $qa = $suffixMatchA.Groups[1].Value.ToLowerInvariant()
    $qb = $suffixMatchB.Groups[1].Value.ToLowerInvariant()
    if ($suffixMatchA.Groups[2].Value -and $shortAliases.ContainsKey($qa)) { $qa = $shortAliases[$qa] }
    if ($suffixMatchB.Groups[2].Value -and $shortAliases.ContainsKey($qb)) { $qb = $shortAliases[$qb] }
    if ($aliases.ContainsKey($qa)) { $qa = $aliases[$qa] }
    if ($aliases.ContainsKey($qb)) { $qb = $aliases[$qb] }
    $rankA = [array]::IndexOf($qualifiers, $qa)
    $rankB = [array]::IndexOf($qualifiers, $qb)
    if ($rankA -lt 0) { $rankA = $qualifiers.Count }
    if ($rankB -lt 0) { $rankB = $qualifiers.Count }
    if ($rankA -ne $rankB) { return $rankA.CompareTo($rankB) }
    if ($qa -ne $qb) { return [string]::CompareOrdinal($qa, $qb) }
    $suffixA = [decimal]0
    $suffixB = [decimal]0
    if ($suffixMatchA.Groups[2].Value) { $suffixA = [decimal]$suffixMatchA.Groups[2].Value }
    if ($suffixMatchB.Groups[2].Value) { $suffixB = [decimal]$suffixMatchB.Groups[2].Value }
    return $suffixA.CompareTo($suffixB)
}
function Test-ModVersion([string]$version, [string]$range, [string]$id) {
    if ($id -eq 'jei') {
        $version = Remove-JeiVersionPrefix $version
        $range = Remove-JeiVersionPrefix $range
    }
    if (-not $version -or $version -match '\$\{') { throw "无法识别实际版本：$version" }
    if (-not $range -or $range -match '^[^\[\(]') { return $true }
    $parts = [regex]::Matches($range, '[\[\(][^\[\]\(\)]*[\]\)]')
    if (-not $parts.Count -or (($parts.Value -join ',') -replace '\s', '') -ne ($range -replace '\s', '')) {
        throw "无法识别版本范围：$range"
    }
    foreach ($part in $parts) {
        $bounds = $part.Value.Substring(1, $part.Length - 2).Split(',')
        if ($bounds.Count -eq 1) {
            if ($part.Value[0] -ne '[' -or $part.Value[$part.Length - 1] -ne ']') { throw "无效版本范围：$range" }
            if ((Compare-ModVersion $version $bounds[0].Trim()) -eq 0) { return $true }
            continue
        }
        if ($bounds.Count -ne 2) { throw "无效版本范围：$range" }
        $lower = $bounds[0].Trim()
        $upper = $bounds[1].Trim()
        $matchesRange = $true
        if ($lower) {
            $comparison = Compare-ModVersion $version $lower
            if ($comparison -lt 0 -or ($comparison -eq 0 -and $part.Value[0] -eq '(')) { $matchesRange = $false }
        }
        if ($upper) {
            $comparison = Compare-ModVersion $version $upper
            if ($comparison -gt 0 -or ($comparison -eq 0 -and $part.Value[$part.Length - 1] -eq ')')) { $matchesRange = $false }
        }
        if ($matchesRange) { return $true }
    }
    return $false
}
function Get-PlatformMods {
    $name = [IO.Path]::GetFileName($target.TrimEnd('\'))
    $path = Join-Path $target ($name + '.json')
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    $profile = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    $minecraft = ''
    $forge = ''
    foreach ($library in $profile.libraries) {
        if ($library.name -match '^net\.minecraftforge:forge:(\d+\.\d+(?:\.\d+)?)-(\d+(?:\.\d+)+)(?:$|:)') {
            $minecraft = $Matches[1]; $forge = $Matches[2]
        }
    }
    $arguments = @($profile.arguments.game)
    for ($i = 0; $i -lt $arguments.Count - 1; $i++) {
        if ($arguments[$i] -eq '--fml.mcVersion') { $minecraft = [string]$arguments[$i + 1] }
        if ($arguments[$i] -eq '--fml.forgeVersion') { $forge = [string]$arguments[$i + 1] }
    }
    foreach ($pair in @(@('minecraft', $minecraft), @('forge', $forge))) {
        if ($pair[1]) { [pscustomobject]@{ Id = $pair[0]; Version = $pair[1]; File = $path; Embedded = $false; Dependencies = @() } }
    }
}
function Get-InstallModFolders {
    'gt_shanhai(jar-mods-必加项)必加模组不加报错'
    '模组依赖(必加or更新-直接丢入mods即可更新)'
    if ($optional) { '非强制模组(优化)' }
}
function Get-ExistingModFiles([string[]]$folders) {
    foreach ($folder in $folders) {
        foreach ($file in Get-ChildItem -LiteralPath (Join-Path $source $folder) -File -Filter '*.jar') {
            if (Test-Path -LiteralPath (Join-Path (Join-Path $target 'mods') $file.Name) -PathType Leaf) {
                $file.Name
            }
        }
    }
}
function Assert-InstallationSources([string[]]$folders) {
    $missing = @(foreach ($folder in $folders) {
        $root = Join-Path $source $folder
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { $root }
    })
    if ($missing.Count) { throw "缺少安装来源目录：`n$($missing -join "`n")" }
}
function Get-InstallationCheck([string[]]$ExcludedFiles = @(), [string[]]$ExcludedIncoming = @()) {
    $strictVersionIds = @('gtlcore', 'gtladditions', 'jei')
    $incoming = @{}
    $incomingFiles = @{}
    foreach ($folder in Get-InstallModFolders) {
        $root = Join-Path $source $folder
        foreach ($file in Get-ChildItem -LiteralPath $root -File -Filter '*.jar') {
            if ($file.Name -in $ExcludedIncoming) { continue }
            if ($incomingFiles.ContainsKey($file.Name)) { throw "安装来源包含同名 JAR：$($file.Name)" }
            $incomingFiles[$file.Name] = $true
            foreach ($mod in @(Get-Mods $file.FullName)) {
                if (-not $incoming.ContainsKey($mod.Id)) { $incoming[$mod.Id] = @() }
                $incoming[$mod.Id] += $mod
            }
        }
    }
    if (-not $incoming.ContainsKey('gt_shanhai')) { throw '安装包缺少可识别的 gt_shanhai 模组。' }
    $installed = @{}
    $targetFiles = @{}
    $conflicts = @()
    $unreadable = @()
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $target 'mods') -File -Filter '*.jar') {
        # 同名目标会由安装来源覆盖，按来源版本检查。
        if ($incomingFiles.ContainsKey($file.Name) -or $file.Name -in $ExcludedFiles) { continue }
        try { $mods = @(Get-Mods $file.FullName) } catch { $unreadable += "$($file.Name)：$($_.Exception.Message)"; continue }
        $replaced = @($mods | Where-Object {
            -not $_.Embedded -and $incoming.ContainsKey($_.Id) -and
            @($incoming[$_.Id] | Where-Object { -not $_.Embedded }).Count -gt 0
        })
        if ($replaced.Count) { $conflicts += $file; continue }
        $targetFiles[$file.Name] = $file
        foreach ($mod in $mods) {
            if (-not $installed.ContainsKey($mod.Id)) { $installed[$mod.Id] = @() }
            $installed[$mod.Id] += $mod
        }
    }
    foreach ($id in $incoming.Keys) {
        if (-not $installed.ContainsKey($id)) { $installed[$id] = @() }
        $installed[$id] += $incoming[$id]
    }
    foreach ($mod in @(Get-PlatformMods)) { $installed[$mod.Id] = @($mod) }
    $problems = @()
    $warnings = @()
    $duplicates = @{}
    foreach ($id in @($installed.Keys)) {
        $candidates = @($installed[$id])
        $external = @($candidates | Where-Object { -not $_.Embedded })
        if ($external.Count -gt 1) {
            $files = @($external.File | Sort-Object -Unique)
            if ($files.Count -gt 1 -and @($files | Where-Object { -not $targetFiles.ContainsKey($_) }).Count -eq 0) {
                $duplicates[$id] = @($files | ForEach-Object { $targetFiles[$_] })
            } else {
                $problems += "安装来源中存在重复模组 ID ${id}：$($files -join '；')"
            }
        }
        if ($external.Count) { $installed[$id] = $external; continue }
        if ($id -notin $strictVersionIds) { continue }
        $ranges = @($candidates | ForEach-Object { $_.BundleRanges } | Select-Object -Unique)
        $selected = $null
        try {
            foreach ($candidate in $candidates) {
                $valid = $true
                foreach ($range in $ranges) {
                    if (-not (Test-ModVersion $candidate.Version $range $id)) { $valid = $false }
                }
                if ($valid -and (-not $selected -or (Compare-ModVersion $candidate.Version $selected.Version $id) -gt 0)) {
                    $selected = $candidate
                }
            }
            if (-not $selected) { throw '没有同时满足所有内嵌版本范围的候选' }
        } catch {
            $problems += "内嵌依赖 $id 无法选定版本，候选 $($candidates.Version -join ', ')，要求 $($ranges -join ' / ')：$($_.Exception.Message)"
            $selected = $candidates[0]
        }
        $installed[$id] = @($selected)
    }
    $checked = 0
    foreach ($mods in $installed.Values) {
        foreach ($mod in $mods) {
            foreach ($dependency in $mod.Dependencies) {
                # 旧版 gt_shanhai 的元数据仍将可选的 gtl_extend 标为必需。
                if ($mod.Id -eq 'gt_shanhai' -and $dependency.Id -eq 'gtl_extend') { continue }
                $strictVersion = $dependency.Id -in $strictVersionIds
                if (-not $installed.ContainsKey($dependency.Id)) {
                    if ($dependency.Mandatory) {
                        $requirement = if ($strictVersion -and $dependency.Range) { "，要求 $($dependency.Range)" } else { '' }
                        $problems += "缺失 $($dependency.Id)$requirement，来源 $($mod.Id) [$($mod.File)]"
                    }
                    continue
                }
                if (-not $strictVersion) { continue }
                foreach ($actual in $installed[$dependency.Id]) {
                    $checked++
                    try {
                        if (-not (Test-ModVersion $actual.Version $dependency.Range $dependency.Id)) {
                            $warnings += "版本不符 $($dependency.Id)，要求 $($dependency.Range)，实际 $($actual.Version) [$($actual.File)]，来源 $($mod.Id)"
                        }
                    } catch {
                        $warnings += "无法判定 $($dependency.Id)，要求 $($dependency.Range)，实际 $($actual.Version) [$($actual.File)]，来源 $($mod.Id)：$($_.Exception.Message)"
                    }
                }
            }
        }
    }
    return @{ Conflicts = @($conflicts); Duplicates = $duplicates; Problems = @($problems | Select-Object -Unique); Warnings = @($warnings | Select-Object -Unique); Unreadable = @($unreadable); Checked = $checked }
}
function Select-DuplicateRemovals([hashtable]$duplicates) {
    $keep = @{}
    $remove = @{}
    foreach ($id in ($duplicates.Keys | Sort-Object)) {
        $files = $duplicates[$id]
        Write-Host "发现重复模组 ${id}，请选择要保留的文件。"
        for ($i = 0; $i -lt $files.Count; $i++) {
            Write-Host ("  [{0}] {1}" -f ($i + 1), $files[$i].Name)
        }
        do {
            $answer = Read-Host '输入要保留的编号，或输入 0 取消安装'
            if ($null -eq $answer -or $answer -eq '0') { return }
            $number = 0
        } until ([int]::TryParse($answer, [ref]$number) -and $number -ge 1 -and $number -le $files.Count)
        $chosen = $files[$number - 1]
        $keep[$chosen.Name] = $true
        foreach ($file in $files) {
            if ($file.Name -ne $chosen.Name) { $remove[$file.Name] = $file }
        }
    }
    foreach ($name in $keep.Keys) {
        if ($remove.ContainsKey($name)) { throw "选择相互冲突：$name 同时被选为保留和移除。请重新运行并调整选择。" }
    }
    return @{ Remove = @($remove.Values) }
}
try {
    $modFolders = @(Get-InstallModFolders)
    $resourceFolder = 'resourcepacks(材质包)'
    $trees = [ordered]@{
        'kubejs(kjs主文件，加这里的)' = 'kubejs'
        'config(ftb任务和gt_shanhai模组配置文件)\ftbquests' = 'config\ftbquests'
        'config(ftb任务和gt_shanhai模组配置文件)\gt_shanhai' = 'config\gt_shanhai'
    }
    Assert-InstallationSources ($modFolders + @($resourceFolder) + @($trees.Keys))
    $existingMods = @(Get-ExistingModFiles $modFolders | Sort-Object -Unique)
    $repeatNotice = ''
    if ($existingMods.Count) {
        $shown = @($existingMods | Select-Object -First 10)
        $repeatNotice = "`n`n检测到 $($existingMods.Count) 个同名模组文件，可能是重复安装：`n$($shown -join "`n")"
        if ($existingMods.Count -gt $shown.Count) { $repeatNotice += "`n……另有 $($existingMods.Count - $shown.Count) 个文件" }
    }
    $confirm = [System.Windows.Forms.MessageBox]::Show("将安装到：`n$target$repeatNotice`n`n同名文件会先备份再覆盖。前置检查完成后才开始复制；取消安装不会复制文件。请先关闭游戏并备份存档。是否继续？", '确认安装', 'YesNo', 'Question')
    if ($confirm -ne 'Yes') { return }
    $excludedIncoming = @()
    $check = Get-InstallationCheck
    if ($check.Unreadable.Count -gt 0) {
        throw "无法读取以下模组，不能可靠检查冲突：`n$($check.Unreadable -join "`n")"
    }
    if ($check.Conflicts.Count -gt 0) {
        $list = ($check.Conflicts | ForEach-Object { $_.Name }) -join "`n"
        $answer = [System.Windows.Forms.MessageBox]::Show("检测到现有模组与安装包中的模组 ID 相同：`n`n$list`n`n选择「是」备份并替换这些现有文件；选择「否」保留这些现有文件并跳过安装包中对应的 JAR；选择「取消」退出安装。随后会按所选文件重新检查依赖。", '选择模组版本', 'YesNoCancel', 'Warning')
        if ($answer -eq 'Cancel') { return }
        if ($answer -eq 'No') {
            $keepIds = @{}
            foreach ($file in $check.Conflicts) {
                foreach ($mod in @(Get-Mods $file.FullName | Where-Object { -not $_.Embedded })) { $keepIds[$mod.Id] = $true }
            }
            foreach ($folder in $modFolders) {
                foreach ($file in Get-ChildItem -LiteralPath (Join-Path $source $folder) -File -Filter '*.jar') {
                    $matching = @(Get-Mods $file.FullName | Where-Object { -not $_.Embedded -and $keepIds.ContainsKey($_.Id) })
                    if ($matching.Count) { $excludedIncoming += $file.Name }
                }
            }
            $skipped = $excludedIncoming -join "`n"
            $answer = [System.Windows.Forms.MessageBox]::Show("将跳过以下安装包 JAR（文件中包含的其他模组也不会安装）：`n`n$skipped`n`n是否继续检查？", '确认跳过文件', 'OKCancel', 'Question')
            if ($answer -ne 'OK') { return }
            $check = Get-InstallationCheck -ExcludedIncoming $excludedIncoming
            if ($check.Conflicts.Count) { throw '选择保留现有模组后仍存在冲突，请手动整理模组后重试。' }
        }
    }
    $duplicateRemovals = @()
    if (-not $check.Unreadable.Count -and $check.Duplicates.Count) {
        $selection = Select-DuplicateRemovals $check.Duplicates
        if (-not $selection) { return }
        $duplicateRemovals = $selection.Remove
        $check = Get-InstallationCheck -ExcludedFiles $duplicateRemovals.Name -ExcludedIncoming $excludedIncoming
        if ($check.Duplicates.Count) { throw '复查仍发现重复模组，请重新运行安装程序。' }
    }
    if ($check.Unreadable.Count -gt 0) {
        throw "无法读取以下模组，不能可靠检查冲突：`n$($check.Unreadable -join "`n")"
    }
    if ($check.Problems.Count -gt 0) {
        $details = $check.Problems -join "`r`n"
        Write-Host $details
        throw "前置检查未通过，请补齐或调整模组后重新安装。`n$details"
    }
    if ($check.Warnings.Count -gt 0) {
        $details = $check.Warnings -join "`r`n"
        Write-Host $details
        $answer = [System.Windows.Forms.MessageBox]::Show("以下依赖版本不符或无法自动比较：`n`n$details`n`nForge 可能拒绝加载模组，游戏也可能无法正常运行。是否仍然安装？", '依赖版本警告', 'YesNo', 'Warning')
        if ($answer -ne 'Yes') { return }
    }
    Write-Host "前置检查完成，已检查必需依赖是否存在，并比对 $($check.Checked) 项强版本依赖。"
    $removals = @($check.Conflicts) + @($duplicateRemovals)
    if ($removals.Count) {
        $backupMods = Join-Path $backup 'mods'
        New-Item -ItemType Directory -Path $backupMods -Force | Out-Null
        foreach ($file in $removals) {
            Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $backupMods $file.Name) -Force
        }
        foreach ($file in $removals) {
            Remove-Item -LiteralPath $file.FullName -Force
        }
    }
    foreach ($folder in $modFolders) { Install-Archives $folder 'mods' '.jar' $excludedIncoming }
    Install-Archives $resourceFolder 'resourcepacks' '.zip'
    $treeSummary = @()
    foreach ($folder in $trees.Keys) {
        $before = $installStats.Count
        Install-Tree $folder $trees[$folder]
        $treeSummary += "$($trees[$folder])：$($installStats.Count - $before) 个文件"
    }
    $kirinNotice = if ($kirin) { 'Kirin_startup.js：已选择安装' } else { 'Kirin_startup.js：按选择跳过' }
    $message = "安装完成，共复制 $($installStats.Count) 个文件。`n$($treeSummary -join "`n")`n$kirinNotice`n旧文件备份位置（如有）：$backup`n`n请按说明手动检查冲突的旧脚本。"
    Write-Host $message
    [System.Windows.Forms.MessageBox]::Show($message, '安装完成') | Out-Null
} catch {
    $message = "安装未完成：$($_.Exception.Message)`n已复制的文件不会自动还原。备份位置（如有）：$backup"
    Write-Host $message
    [System.Windows.Forms.MessageBox]::Show($message, '安装失败') | Out-Null
    exit 1
}
