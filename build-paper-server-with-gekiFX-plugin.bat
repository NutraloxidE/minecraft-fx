@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

set JAVA_HOME=C:\Program Files\Java\jdk-21
set JAVA="%JAVA_HOME%\bin\java.exe"
set PROJECT_DIR=%~dp0
set SERVER_DIR=%PROJECT_DIR%paper-server
set PLUGIN_JAR=%PROJECT_DIR%build\libs\GekiyabaFX-1.0.0.jar
set PAPER_VERSION=1.21.4

echo ============================================================
echo  GekiyabaFX Paper Server Setup
echo ============================================================
echo.

:: ── プラグイン JAR の存在確認 ──────────────────────────────────────
if not exist "%PLUGIN_JAR%" (
    echo [ERROR] プラグイン JAR が見つかりません:
    echo         %PLUGIN_JAR%
    echo.
    echo         先に Gradle ビルドを実行してください:
    echo         run-tests.bat  または  gradle shadowJar
    pause
    exit /b 1
)

:: ── サーバーディレクトリ作成 ────────────────────────────────────────
if not exist "%SERVER_DIR%" mkdir "%SERVER_DIR%"
if not exist "%SERVER_DIR%\plugins" mkdir "%SERVER_DIR%\plugins"

:: ── EULA 同意済みチェック ────────────────────────────────────────────
set EULA_AGREED=0
if exist "%SERVER_DIR%\eula.txt" (
    findstr /i "eula=true" "%SERVER_DIR%\eula.txt" >nul 2>&1
    if !errorlevel! == 0 (
        set EULA_AGREED=1
    )
)

if !EULA_AGREED! == 1 (
    echo [INFO] EULA 同意済みを確認しました。プラグインの配置のみ実施します。
    goto :deploy_plugin
)

:: ── Paper JAR のダウンロード ─────────────────────────────────────────
echo [STEP 1/4] Paper %PAPER_VERSION% の最新ビルドを取得中...

powershell -NoProfile -Command ^
    "$api = 'https://api.papermc.io/v2/projects/paper/versions/%PAPER_VERSION%/builds'; " ^
    "try { " ^
    "  $builds = (Invoke-RestMethod -Uri $api -UseBasicParsing).builds; " ^
    "  $latest = ($builds | Where-Object { $_.channel -eq 'default' } | Select-Object -Last 1).build; " ^
    "  if (-not $latest) { $latest = ($builds | Select-Object -Last 1).build }; " ^
    "  $jar = 'paper-%PAPER_VERSION%-' + $latest + '.jar'; " ^
    "  $url = $api + '/' + $latest + '/downloads/' + $jar; " ^
    "  $dest = '%SERVER_DIR%\paper.jar'; " ^
    "  if (-not (Test-Path $dest)) { " ^
    "    Write-Host '  ダウンロード中: ' $url; " ^
    "    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing; " ^
    "    Write-Host '  完了: Build ' $latest " ^
    "  } else { " ^
    "    Write-Host '  paper.jar は既に存在します。スキップします。' " ^
    "  } " ^
    "} catch { Write-Error $_; exit 1 }"

if errorlevel 1 (
    echo [ERROR] Paper のダウンロードに失敗しました。
    echo         ネットワーク接続を確認してください。
    pause
    exit /b 1
)

:: ── 初回起動（eula.txt 生成）────────────────────────────────────────
echo.
echo [STEP 2/4] Paper を初回起動して eula.txt を生成します...
echo            （数秒後に自動的に停止します）

pushd "%SERVER_DIR%"
start "PaperMC-Init" /wait %JAVA% -Xms512M -Xmx512M -jar paper.jar --nogui
popd

:: eula.txt が生成されるまで最大 30 秒待機
set WAIT=0
:wait_eula
if exist "%SERVER_DIR%\eula.txt" goto :eula_found
timeout /t 2 /nobreak >nul
set /a WAIT+=2
if !WAIT! lss 30 goto :wait_eula

echo [WARN] eula.txt の生成を確認できませんでした。手動で確認してください。
goto :deploy_plugin

:eula_found
echo [INFO] eula.txt を確認しました。

:: ── EULA 同意 ────────────────────────────────────────────────────────
echo.
echo [STEP 3/4] EULA に同意します (eula=true)...

powershell -NoProfile -Command ^
    "$f = '%SERVER_DIR%\eula.txt'; " ^
    "$c = Get-Content $f -Raw; " ^
    "$c = $c -replace 'eula=false','eula=true'; " ^
    "Set-Content -Path $f -Value $c -NoNewline"

echo [INFO] eula.txt を更新しました。

:: ── プラグイン配置 ───────────────────────────────────────────────────
:deploy_plugin
echo.
echo [STEP 4/4] プラグイン JAR を配置します...

copy /Y "%PLUGIN_JAR%" "%SERVER_DIR%\plugins\GekiyabaFX-1.0.0.jar" >nul
if errorlevel 1 (
    echo [ERROR] プラグインのコピーに失敗しました。
    pause
    exit /b 1
)

echo [INFO] 配置完了: %SERVER_DIR%\plugins\GekiyabaFX-1.0.0.jar

:: ── run-server.bat の生成 ─────────────────────────────────────────────
if not exist "%SERVER_DIR%\run-server.bat" (
    echo.
    echo [STEP 5/5] run-server.bat を生成します...
    (
        echo @echo off
        echo chcp 65001 ^>nul
        echo cd /d "%%~dp0"
        echo echo GekiyabaFX Paper Server を起動します...
        echo "C:\Program Files\Java\jdk-21\bin\java.exe" -Xms1G -Xmx2G -jar paper.jar --nogui
        echo pause
    ) > "%SERVER_DIR%\run-server.bat"
    echo [INFO] 生成完了: %SERVER_DIR%\run-server.bat
) else (
    echo [INFO] run-server.bat は既に存在します。スキップします。
)

:: ── 完了メッセージ ────────────────────────────────────────────────────
echo.
echo ============================================================
echo  セットアップ完了！
echo ============================================================
echo.
echo  サーバーを起動するには:
echo    %SERVER_DIR%\run-server.bat
echo    （または cd "%SERVER_DIR%" して run-server.bat を実行）
echo.
echo  プレイヤー操作:
echo    /fx login   ... トレード画面の OTP リンクを取得
echo    /fx admin   ... 管理者画面の OTP リンクを取得
echo.
pause
endlocal
