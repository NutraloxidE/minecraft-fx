@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

:: ============================================================
::  GekiyabaFX Frontend-only Deploy Script
::  フロントエンドのみビルドしてデプロイする
:: ============================================================

set PROJECT_DIR=%~dp0
set FRONTEND_DIR=%PROJECT_DIR%frontend
set WWW_DIR=%PROJECT_DIR%src\main\resources\www

echo ============================================================
echo  GekiyabaFX Frontend Deploy
echo ============================================================
echo.

:: ── node_modules チェック ─────────────────────────────────────
if not exist "%FRONTEND_DIR%\node_modules" (
    echo [INFO] node_modules が見つかりません。npm install を実行します...
    pushd "%FRONTEND_DIR%"
    call npm install
    if !errorlevel! neq 0 (
        echo [ERROR] npm install に失敗しました。
        popd & pause & exit /b 1
    )
    popd
)

:: ── フロントエンドビルド ──────────────────────────────────────
echo [STEP 1/1] フロントエンド ビルド中...
pushd "%FRONTEND_DIR%"
call npm run build
if !errorlevel! neq 0 (
    echo [ERROR] フロントエンド ビルドに失敗しました。
    popd & pause & exit /b 1
)
popd
echo [OK] ビルド完了 → %WWW_DIR%

echo.
echo ============================================================
echo  フロントエンドデプロイ完了！
echo  Minecraft サーバーを再起動してください:
echo    stop → run-server.bat
echo ============================================================
echo.
pause
