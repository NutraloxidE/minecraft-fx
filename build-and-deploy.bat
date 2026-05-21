@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

:: ============================================================
::  GekiyabaFX Build & Deploy Script
::  ビルド → テスト環境デプロイ用スクリプト
:: ============================================================

set JAVA_HOME=C:\Program Files\Java\jdk-21
set GRADLE=C:\Users\2mender\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat
set PROJECT_DIR=%~dp0
set PLUGIN_JAR=%PROJECT_DIR%build\libs\GekiyabaFX-1.0.0.jar
set PLUGINS_DIR=%PROJECT_DIR%paper-server\plugins
set FRONTEND_DIR=%PROJECT_DIR%frontend

echo ============================================================
echo  GekiyabaFX Build and Deploy
echo ============================================================
echo.

:: ── 引数チェック (--skip-frontend でフロント省略可能) ──
set SKIP_FRONTEND=0
if /I "%1"=="--skip-frontend" set SKIP_FRONTEND=1

:: ────────────────────────────────────────────────────
:: STEP 1: フロントエンド ビルド
:: ────────────────────────────────────────────────────
if !SKIP_FRONTEND!==0 (
    echo [STEP 1/3] フロントエンド ビルド中...
    if not exist "%FRONTEND_DIR%\node_modules" (
        echo         node_modules が見つかりません。npm install を実行します...
        pushd "%FRONTEND_DIR%"
        call npm install
        if !errorlevel! neq 0 (
            echo [ERROR] npm install に失敗しました。
            popd & pause & exit /b 1
        )
        popd
    )
    pushd "%FRONTEND_DIR%"
    call npm run build
    if !errorlevel! neq 0 (
        echo [ERROR] フロントエンド ビルドに失敗しました。
        popd & pause & exit /b 1
    )
    popd
    echo [OK] フロントエンド ビルド完了
) else (
    echo [SKIP] フロントエンド ビルドをスキップしました (--skip-frontend)
)

echo.

:: ────────────────────────────────────────────────────
:: STEP 2: Java (Gradle shadowJar) ビルド
:: ────────────────────────────────────────────────────
echo [STEP 2/3] Gradle shadowJar ビルド中...
if not exist "%GRADLE%" (
    echo [ERROR] Gradle が見つかりません:
    echo         %GRADLE%
    echo         パスを build-and-deploy.bat 内の GRADLE 変数に設定してください。
    pause & exit /b 1
)

pushd "%PROJECT_DIR%"
call "%GRADLE%" shadowJar
if !errorlevel! neq 0 (
    popd
    echo [ERROR] Gradle ビルドに失敗しました。
    pause & exit /b 1
)
popd
echo [OK] JAR ビルド完了: %PLUGIN_JAR%

echo.

:: ────────────────────────────────────────────────────
:: STEP 3: プラグイン JAR をデプロイ
:: ────────────────────────────────────────────────────
echo [STEP 3/3] プラグイン JAR をデプロイ中...
if not exist "%PLUGIN_JAR%" (
    echo [ERROR] JAR が見つかりません: %PLUGIN_JAR%
    pause & exit /b 1
)
if not exist "%PLUGINS_DIR%" (
    mkdir "%PLUGINS_DIR%"
)

copy /Y "%PLUGIN_JAR%" "%PLUGINS_DIR%\" >nul
if !errorlevel! neq 0 (
    echo [ERROR] JAR のコピーに失敗しました。
    pause & exit /b 1
)
echo [OK] デプロイ完了: %PLUGINS_DIR%

echo.
echo ============================================================
echo  Build and Deploy completed!
echo  Minecraft サーバーコンソールで以下を実行してください:
echo    reload confirm
echo  または stop → run-server.bat で再起動してください。
echo ============================================================
echo.