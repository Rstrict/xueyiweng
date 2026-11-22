$ErrorActionPreference = 'SilentlyContinue'
$adb = "D:\AndroidBuild\sdk\platform-tools\adb.exe"
$pkg = "com.xueyiweng.app.debug"
$Out = "D:\AndroidBuild\shots\ui"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$pass = 0; $fail = 0

function Shot([string]$n) {
    & $adb shell screencap -p /sdcard/s.png
    & $adb pull /sdcard/s.png "$Out\$n.png" | Out-Null
    Write-Host "    [shot] $n"
}
function Dump {
    for ($i = 0; $i -lt 6; $i++) {
        & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
        $x = (& $adb shell cat /sdcard/ui.xml) -join ''
        if ($x.Length -gt 800) { return $x }
        Start-Sleep -Milliseconds 400
    }
    return ''
}
function Node([string]$id) {
    $x = Dump
    foreach ($n in ($x -split '><')) {
        if ($n -like "*id/$id*" -and $n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            return @{ cx = [int](([int]$matches[1] + [int]$matches[3]) / 2); cy = [int](([int]$matches[2] + [int]$matches[4]) / 2) }
        }
    }
    return $null
}
function Tap([string]$id) {
    $n = Node $id
    if ($null -eq $n) { Write-Host "    !! not found: $id"; return $false }
    & $adb shell input tap $n.cx $n.cy
    Write-Host "    tap $id @ $($n.cx),$($n.cy)"
    Start-Sleep -Milliseconds 1000
    return $true
}
function Check([string]$name, [bool]$ok) {
    if ($ok) { $script:pass++; Write-Host "    [PASS] $name" }
    else { $script:fail++; Write-Host "    [FAIL] $name" }
}
function Stage {
    $x = Dump
    foreach ($s in '1/4','2/4','3/4','4/4') { if ($x -match "stage $s") { return $s.Substring(0,1) } }
    if ($x -match '\d/4') { return 'x' }
    return '?'
}
function HasText([string]$t) { return ((Dump) -like "*$t*") }

Write-Host "`n##### 1. HOME #####"
& $adb shell am force-stop $pkg
Start-Sleep -Milliseconds 800
& $adb shell am start -n "$pkg/com.xueyiweng.app.ui.MainActivity" | Out-Null
Start-Sleep -Seconds 3
Shot "01-home"
Check "home has etQuestion" ($null -ne (Node "etQuestion"))
Check "home has btnStart" ($null -ne (Node "btnStart"))

Write-Host "`n##### 2. TYPE QUESTION #####"
Tap "etQuestion" | Out-Null
& $adb shell input text "Why%sis%ssample%svariance%sdivided%sby%sn-1"
Start-Sleep -Milliseconds 800
Shot "02-typed"

Write-Host "`n##### 3. HIDE KB AND START #####"
& $adb shell input keyevent 111
Start-Sleep -Milliseconds 1100
Shot "03-noKb"
Tap "btnStart" | Out-Null
Start-Sleep -Seconds 2
Shot "04-stage1"
Check "session has btnPrimary" ($null -ne (Node "btnPrimary"))
Check "session has btnPause" ($null -ne (Node "btnPause"))
Check "tabbar hidden in session" ($null -eq (Node "tabArchive"))

Write-Host "`n##### 4. STAGE 1 -> 2 #####"
Tap "btnPrimary" | Out-Null
Start-Sleep -Seconds 2
Shot "05-stage2"
Check "recall input appeared" ($null -ne (Node "etRecall"))

Write-Host "`n##### 5. TYPE BLIND ANSWER #####"
Tap "etRecall" | Out-Null
& $adb shell input text "Still%shalf%sbut%scannot%sexplain"
Start-Sleep -Milliseconds 800
Shot "06-recall-typed"

Write-Host "`n##### 6. STAGE 2 -> 3 #####"
& $adb shell input keyevent 111
Start-Sleep -Milliseconds 900
Tap "btnPrimary" | Out-Null
Start-Sleep -Seconds 2
Shot "07-stage3"
Check "verify shows last attempt" (HasText "Still half")
Check "verify has primary" ($null -ne (Node "btnPrimary"))

Write-Host "`n##### 7. STAGE 3 -> 4 #####"
Tap "btnPrimary" | Out-Null
Start-Sleep -Seconds 2
Shot "08-stage4"
Check "final note field exists" ($null -ne (Node "etFinalNote"))

Write-Host "`n##### 8. TYPE FINAL NOTE #####"
Tap "etFinalNote" | Out-Null
& $adb shell input text "Independent%sevents%shave%sno%smemory"
Start-Sleep -Milliseconds 800
& $adb shell input keyevent 111
Start-Sleep -Milliseconds 900
Shot "09-final-note"

Write-Host "`n##### 9. BRAIN DUMP #####"
Tap "btnDump" | Out-Null
Start-Sleep -Seconds 1
Shot "10-dump-dialog"
Check "brain dump dialog opened" (HasText "dump-input")

Write-Host "`n##### 10. FINISH ROUND #####"
& $adb shell input keyevent 4
Start-Sleep -Milliseconds 900
Tap "btnPrimary" | Out-Null
Start-Sleep -Seconds 1
Shot "11-mastery"
$m = Dump
Check "mastery dialog opened" ($m -like "*alertTitle*")
Check "has three options" (($m -like "*btn3*") -or ($m -like "*textButton*"))

Write-Host "`n##### 11. DATA PERSISTED #####"
& $adb shell input keyevent 4
Start-Sleep -Milliseconds 800
& $adb logcat -c
& $adb shell am force-stop $pkg
Start-Sleep -Milliseconds 600
& $adb shell am start -n "$pkg/com.xueyiweng.app.ui.MainActivity" --es drive queue | Out-Null
Start-Sleep -Seconds 3
& $adb logcat -d -s XYW_DRIVE:* | ForEach-Object { Write-Host "    $($_ -replace '^.*XYW_DRIVE: ', '')" }
Shot "12-archive-check"

Write-Host "`n##### RESULT: $pass pass / $fail fail #####"
