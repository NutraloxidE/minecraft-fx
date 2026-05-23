@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

:: ============================================================
::  GekiyabaFX Test Build Script
:: ============================================================

set JAVA_HOME=C:\Program Files\Java\jdk-21
set GRADLE=C:\Users\2mender\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat
set PROJECT_DIR=%~dp0
set BUILD_LOG=%PROJECT_DIR%build-log.txt

echo ============================================================
echo  GekiyabaFX Test Compilation
echo ============================================================
echo.

if not exist "%GRADLE%" (
  echo [ERROR] Gradle が見つかりません:
  echo         %GRADLE%
  pause & exit /b 1
)

echo [STEP 1/1] コンパイル中...
pushd "%PROJECT_DIR%"
call "%GRADLE%" compileJava > "%BUILD_LOG%" 2>&1
if !errorlevel! neq 0 (
  popd
  echo [ERROR] コンパイルに失敗しました。
  echo ログ: %BUILD_LOG%
  type "%BUILD_LOG%"
  pause & exit /b 1
)
popd

echo [OK] コンパイル完了
echo ログ: %BUILD_LOG%
echo.
