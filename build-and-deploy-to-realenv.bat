@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

:: ============================================================
::  GekiyabaFX Real Environment Deploy Script
::  shadowJar ビルド → SSH/SCP で実環境へ配置
::
::  必要ツール:
::    - plink.exe
::    - pscp.exe
::  (PuTTY をインストールし、PATH を通すか、このファイル内で絶対パス指定)
:: ============================================================

:: ====== editable variables ======
set "SERVER_IP=162.43.19.7"
set "SERVER_PASSWORD=Gmcr1cefarmz;;"
set "TARGET_DIR=/opt/minecraft/server/plugins"

:: SSH ログインに必要です。必要なら書き換えてください。
set "SERVER_USER=root"
set "SERVER_HOSTKEY=ssh-ed25519 255 SHA256:JF1oKXgTvWYSc17VPgou2a95Q4EwDgnkpXxo0iuqWbM"

:: PuTTY ツールの絶対パスを使いたい場合はここを変更
set "PLINK=plink"
set "PSCP=pscp"

set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "GRADLE=C:\Users\2mender\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat"
set "PROJECT_DIR=%~dp0"
set "PLUGIN_JAR=%PROJECT_DIR%build\libs\GekiyabaFX-1.0.0.jar"

echo ============================================================
echo  GekiyabaFX Real Environment Deploy
echo ============================================================
echo  SERVER_IP   = %SERVER_IP%
echo  SERVER_USER = %SERVER_USER%
echo  TARGET_DIR  = %TARGET_DIR%
echo  HOSTKEY     = %SERVER_HOSTKEY%
echo.

if "%SERVER_IP%"=="" (
	echo [ERROR] SERVER_IP が未設定です。
	pause & exit /b 1
)

if "%SERVER_PASSWORD%"=="" (
	echo [ERROR] SERVER_PASSWORD が未設定です。
	pause & exit /b 1
)

if "%TARGET_DIR%"=="" (
	echo [ERROR] TARGET_DIR が未設定です。
	pause & exit /b 1
)

if "%SERVER_USER%"=="" (
	echo [ERROR] SERVER_USER が未設定です。
	pause & exit /b 1
)

if "%SERVER_HOSTKEY%"=="" (
	echo [ERROR] SERVER_HOSTKEY が未設定です。
	echo         初回接続時に表示された fingerprint を設定してください。
	pause & exit /b 1
)

where "%PLINK%" >nul 2>nul
if errorlevel 1 (
	echo [ERROR] plink が見つかりません: %PLINK%
	echo         PuTTY をインストールして PATH を通すか、PLINK 変数に絶対パスを設定してください。
	pause & exit /b 1
)

where "%PSCP%" >nul 2>nul
if errorlevel 1 (
	echo [ERROR] pscp が見つかりません: %PSCP%
	echo         PuTTY をインストールして PATH を通すか、PSCP 変数に絶対パスを設定してください。
	pause & exit /b 1
)

if not exist "%GRADLE%" (
	echo [ERROR] Gradle が見つかりません:
	echo         %GRADLE%
	pause & exit /b 1
)

echo [STEP 1/3] shadowJar をビルド中...
pushd "%PROJECT_DIR%"
call "%GRADLE%" shadowJar
if !errorlevel! neq 0 (
	popd
	echo [ERROR] Gradle ビルドに失敗しました。
	pause & exit /b 1
)
popd

if not exist "%PLUGIN_JAR%" (
	echo [ERROR] JAR が見つかりません: %PLUGIN_JAR%
	pause & exit /b 1
)
echo [OK] ビルド完了: %PLUGIN_JAR%
echo.

echo [STEP 2/3] リモート側の対象ディレクトリを準備中...
"%PLINK%" -batch -ssh -hostkey "%SERVER_HOSTKEY%" -pw "%SERVER_PASSWORD%" %SERVER_USER%@%SERVER_IP% "mkdir -p '%TARGET_DIR%'"
if !errorlevel! neq 0 (
	echo [ERROR] リモートディレクトリの作成に失敗しました。
	pause & exit /b 1
)
echo [OK] リモートディレクトリ準備完了
echo.

echo [STEP 3/3] JAR を実環境へ転送中...
"%PSCP%" -batch -hostkey "%SERVER_HOSTKEY%" -pw "%SERVER_PASSWORD%" "%PLUGIN_JAR%" %SERVER_USER%@%SERVER_IP%:"%TARGET_DIR%/"
if !errorlevel! neq 0 (
	echo [ERROR] JAR の転送に失敗しました。
	pause & exit /b 1
)

echo [OK] デプロイ完了
echo.
echo ============================================================
echo  完了: %SERVER_USER%@%SERVER_IP%:%TARGET_DIR%/
echo  必要に応じて実サーバー側で reload / restart を実行してください。
echo ============================================================
echo.
