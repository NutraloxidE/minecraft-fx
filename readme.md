# GekiyabaFX

GekiyabaFX は、Minecraft Paper サーバー上で動作する Web 連携型の取引プラグインです。  
プレイヤーはブラウザから取引・入出金・送金を行え、管理者は Web からペア設定や運用設定を管理できます。  
ATM 看板のブロック種別ごとに、実際の手数料率と配分率を切り替えられます。

## プレイヤー向けの使い方

1. ゲーム内で `/fx login` を実行します。
2. 出てきた URL をブラウザで開きます。
3. そのまま取引画面に入って注文、入出金、送金を行います。
4. ATM 看板を使う場合は、看板を設置したときのブロック種別で手数料が決まります。

プレイヤー目線では、基本的に「ゲーム内で `/fx login` → ブラウザで操作」が入口です。  
ATM 看板を使う場合は、看板の背後ブロックに対応する `block-grades` が設定されている必要があります。

## まず何をすればいいか

最初に見るべき順番は次の通りです。

1. サーバーにプラグイン jar を入れて起動します。
2. `config.yml` の `server-ip` と `web-bind-ip` を環境に合わせて確認します。
3. 必要なら `atm.block-grades` を編集して、使いたい ATM ブロックを追加します。
4. サーバー内で `/fx login` を実行して、ブラウザ用のログイン URL を受け取ります。
5. ブラウザで取引画面を開き、注文・入出金・送金を行います。

迷ったら、まずはこの 3 つだけ見れば動きます。

- `server-ip`
- `web-port`
- `atm.block-grades`

プレイヤー目線では、基本的に「ゲーム内で `/fx login` → ブラウザで操作」が入口です。  
ATM 看板を使う場合は、看板の背後ブロックに対応する `block-grades` が設定されている必要があります。

## 主な機能

- ブラウザベースの板取引
- Maker / Taker 手数料
- ATM グレード別の手数料率と配分率
- 手数料のオーナー配分とトレジャリー配分
- プレイヤー OTP ログイン
- 管理者ログイン
- 入出金、送金、注文管理
- 管理 API によるペア管理、Web 設定、裁定取引切り替え、バックアップ取得

## 動作環境

- Java 21
- Paper 1.21.4 系
- Gradle 8.9 以降

## 導入手順

1. プラグインをビルドします。
2. 生成された jar を Paper サーバーの plugins フォルダへ配置します。
3. サーバーを起動または再起動します。
4. 必要に応じて config.yml を編集し、/fx reload で反映します。

初回導入時は、次の流れが安全です。

1. いったんデフォルト設定のまま起動します。
2. コンソールログで Web サーバーの起動とエラー有無を確認します。
3. `server-ip` を実環境の公開 IP またはドメインに直します。
4. `web-port` がファイアウォールで開いているか確認します。
5. `atm.block-grades` を必要なブロックだけに絞って設定します。

ローカルビルドや配置には、以下のバッチが使えます。

- build-and-deploy.bat
- run-tests.bat

## 実環境へのデプロイ

SSH と SCP を使う場合は、deploy-to-realenv.example.bat を基に設定してください。

編集する主な項目:

- SERVER_IP
- SERVER_PASSWORD
- TARGET_DIR
- SERVER_USER
- SERVER_HOSTKEY

このスクリプトは shadowJar をビルドしたあと、PuTTY の plink と pscp でサーバーへ転送します。

## コマンド

- /fx login  
  プレイヤー用のワンタイム URL を発行します。
- /fx login-as <name>  
  Service アカウント用のログイン URL を発行します。管理者権限が必要です。
- /fx admin  
  管理者用のワンタイム URL を発行します。
- /fx reload  
  config.yml を再読み込みします。管理者権限が必要です。

### 管理者向けの使い方

1. 管理者権限を持つアカウントで `/fx admin` を実行します。
2. 管理画面でペア設定、Web 設定、裁定取引設定を操作します。
3. `config.yml` を修正したら `/fx reload` で反映します。
4. 必要ならバックアップ取得や状態確認を行います。

## 設定ファイル

設定ファイルは src/main/resources/config.yml です。  
起動時に読み込まれ、/fx reload でも反映できます。

### 基本設定

- server-ip  
  ブラウザ向けに返す公開用の IP アドレスまたはドメイン名
- web-bind-ip  
  内蔵 Web サーバーの待受 IP
- web-port  
  内蔵 Web サーバーの待受ポート
- dev-mode  
  開発用 CORS 緩和の有無
- otp-expire-seconds  
  OTP の有効期限
- session-expire-seconds  
  セッショントークンの有効期限

### 手数料設定

- fee.maker  
  グローバル Maker 手数料率
- fee.taker  
  グローバル Taker 手数料率
- feeOverrides  
  通貨キーごとの手数料上書き

### Service アカウント設定

- serviceAccounts  
  /fx login-as でログイン可能なアカウント名

### ATM 設定

atm.block-grades で、ブロック種別ごとの ATM グレードを定義します。  
キーは Minecraft の Material 名を大文字で書きます。

たとえば `COPPER_BLOCK` と書けば、ゲーム内の `minecraft:copper_block` に対応する ATM ブロックとして扱われます。  
内部の判定は Material 名ベースなので、大文字のキーで合わせてください。

各定義には次の項目があります。

- grade  
  グレード名
- maker  
  Maker 手数料率
- taker  
  Taker 手数料率
- owner-share  
  手数料のオーナー配分率
- treasury-share  
  手数料のトレジャリー配分率

注意点:

- ブロックキーは Material 名を大文字で書きます
- owner-share と treasury-share の合計は 1.0 にしてください
- 未定義のブロックは ATM として認識されません
- 不正値は起動時または reload 時にエラーになります

### よくある設定ミス

- `COPPER_BLOCK` を `copper_block` のように小文字で書く
- `owner-share` と `treasury-share` の合計が 1.0 になっていない
- `server-ip` をローカルアドレスのままにしている
- `web-port` を開放していない
- 看板の背後ブロックが `atm.block-grades` に存在しない

#### 例

```yaml
atm:
  block-grades:
    COPPER_BLOCK:
      grade: "copper"
      maker: "0.0010"
      taker: "0.0015"
      owner-share: "0.80"
      treasury-share: "0.20"

    IRON_BLOCK:
      grade: "iron"
      maker: "0.0008"
      taker: "0.0012"
      owner-share: "0.65"
      treasury-share: "0.35"

    DIAMOND_BLOCK:
      grade: "diamond"
      maker: "0.0005"
      taker: "0.0008"
      owner-share: "0.25"
      treasury-share: "0.75"

```

### ATM 補助設定

- block-scan-radius  
  看板中心から判定に使う走査半径
- required-matching-blocks  
  ATM 構造として必要な同種ブロック数
- fx-sign-exclusion-radius  
  [FX] 看板の近接設置禁止半径

## 運用手順

### 設定変更

1. config.yml を編集します。
2. /fx reload を実行します。
3. 反映されない項目がある場合はサーバーを再起動します。

### 日常運用の流れ

1. プレイヤーは `/fx login` から取引画面に入ります。
2. ATM 看板を設置する場合は、対応ブロックを先に確認します。
3. 管理者は必要に応じて `/fx admin` で設定画面を開きます。
4. 変更後は Web UI と API の両方で動作確認します。

### デプロイ後の確認

- プラグイン jar が更新されているか確認します
- Web UI が起動するか確認します
- /api/pairs が返るか確認します
- /api/pairs/{id}/fee が期待する手数料を返すか確認します
- ATM 看板のブロック定義が反映されているか確認します

### まず確認する API

- `GET /api/pairs`
- `GET /api/pairs/{id}/fee`
- `GET /api/state`
- `GET /api/atm-session`

この 4 つが返れば、少なくとも認証・手数料・セッション周りの基本動作は確認しやすいです。

### トラブル時

- サーバーコンソールのログを確認します
- config.yml の数値フォーマットを確認します
- owner-share と treasury-share の合計を確認します
- ATM のブロック名が Material 名の大文字になっているか確認します

### 追加で見落としやすい点

- ブラウザ側で古いトークンが残っている
- `/fx reload` 後に Web UI を再読み込みしていない
- `server-ip` と実際の公開アドレスが一致していない
- サーバー側のポート開放が足りない

## 開発メモ

- バックエンドは src/main/java にあります
- リソースは src/main/resources にあります
- フロントエンドは frontend にあります

## ライセンス

このリポジトリのライセンスに従います。