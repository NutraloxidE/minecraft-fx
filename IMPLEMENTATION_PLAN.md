Service Account 仕様案
1. 目的
Serviceアカウントは、以下の用途で使う通常口座である。

システム的に自動化された取引
将来的な手数料の集積
管理者による取引介入
ただし、取引システム上は通常のプレイヤー口座と同じとして扱う。

残高制約あり
注文制約あり
通常のマッチング対象
約定処理も同一
特別な優遇や例外は持たない
2. 基本方針
2.1 Serviceアカウントは「特別な取引主体」ではない
Serviceアカウントは管理用の導線でログインできるだけであり、
トレード画面に入った後は通常アカウントと同じである。

2.2 特別扱いは「導線」と「権限」のみ
特別なのは以下だけ。

作成方法
ログイン方法
管理者のみアクセス可能であること
それ以外の取引処理では特別扱いしない。

2.3 /trade は既存の通常ログイン導線を使う
Serviceアカウントも最終的には通常のプレイヤーセッションとして /trade に入る。
/trade 側に admin mode や special mode は追加しない。

3. 識別子
3.1 ID形式
Serviceアカウントは通常プレイヤーと衝突しない文字列IDを使う。

例:

svc:treasury-fee
svc:intervention-main
svc:market-bot-1
3.2 保存先
既存の PlayerData をそのまま使い、players に保存する。
つまり Serviceアカウントも内部的には既存の口座データ構造を使う。

3.3 表示名
UI表示用に name を持たせる。

例:

[SERVICE] treasury-fee
[SERVICE] intervention-main
4. ログイン導線
4.1 通常プレイヤー
従来通り:

/fx login
OTP発行
/trade?otp=...
/api/auth
通常セッション開始
4.2 管理者画面
従来通り:

/fx admin
OTP発行
/admin?otp=...
/api/admin/auth
管理者セッション開始
4.3 Serviceアカウントログイン
新規コマンドで行う。

例:

Text
/fx login-as treasury-fee
処理:

実行者が管理者権限を持つことを確認
treasury-fee が許可された Serviceアカウント名か確認
内部ID svc:treasury-fee を作る
Serviceアカウントの PlayerData がなければ自動作成
プレイヤー用OTPを svc:treasury-fee に対して発行
通常と同じ /trade?otp=... URL を返す
つまり、入口だけ特別で、ログイン後は通常の /trade。

5. 取引上の扱い
5.1 通常口座と同じ
Serviceアカウントは以下すべて通常口座と同じ。

残高確認
発注
キャンセル
ロック残高
約定
返金
履歴反映
5.2 特別ルールを持たない
以下は禁止。

Serviceアカウントだけ残高制約を無視する
Serviceアカウントだけ優先約定する
Serviceアカウントだけ手数料免除にする
Serviceアカウントだけ特別APIを使う
6. 作成と管理
6.1 初期版では固定定義でよい
最初は config.yml などで許可リスト管理する。

例:

YAML
serviceAccounts:
  - treasury-fee
  - intervention-main
  - market-bot-1
6.2 自動作成
/fx login-as <id> 実行時に、対象 svc:<id> の PlayerData がなければ自動作成する。

6.3 初期残高
初期残高は自動付与しない。必要なら別途管理操作で入れる。

理由:

特別扱いを減らすため
「作った瞬間に資産を持つ」挙動を避けるため
7. 権限制御
7.1 実行権限
/fx login-as <serviceAccount> は管理者のみ実行可能。

例:

gekiyabafx.admin を要求
もしくは gekiyabafx.service-login を別追加してもよい
初期版では gekiyabafx.admin 共有で十分。

7.2 通常プレイヤーからは入れない
通常の /fx login から Serviceアカウントに入ることはできない。

7.3 対象制限
/fx login-as は任意文字列を受け付けず、許可済みの Serviceアカウント名のみに限定する。

8. フロントエンド方針
8.1 /trade は変更最小
Serviceアカウントでも通常の player token で入るため、
TradePage は通常通り動作する。

8.2 admin mode を作らない
以下は作らない。

admin_trade_mode
sessionStorage の特殊フラグ
/admin へ戻す専用ロジック
管理者専用 TradePage
8.3 見分けだけは可能にする
必要なら表示上だけ:

name が [SERVICE] ...
identity が svc:...
で判別できればよい。

9. 入出金について
9.1 Serviceアカウントは実プレイヤーではない
そのため Minecraft プレイヤー参加イベントを前提とする処理とは相性が悪い。

9.2 初期版の扱い
初期版では以下を前提とする。

Serviceアカウントは主にWeb取引用
通常プレイヤーJoin時に行う pending deposit / pending withdraw 処理は対象外
必要な残高は別手段で調整する
9.3 将来拡張
将来的に必要なら、Serviceアカウント向けの残高調整手段を別で作る。
ただしそれは取引ロジックとは分離する。

10. 手数料集積への適用
将来、約定時の手数料を導入する場合は、
手数料受け取り先として Serviceアカウントを使う。

例:

手数料受取口座: svc:treasury-fee
処理:

約定時に fee を計算
fee 分を svc:treasury-fee の残高へ加算
この口座自体も通常口座として保持される。

11. 管理者介入への適用
管理者が市場介入したい場合は、管理者自身が特別注文を出すのではなく、
Serviceアカウントにログインして通常注文を出す。

例:

Text
/fx login-as intervention-main
その後は通常の /trade で発注する。

12. 実装対象
Backend
/fx login-as <serviceAccount> コマンド追加
Serviceアカウント許可リストの導入
svc:<id> 用 PlayerData の自動作成
通常の player OTP 発行導線への接続
Frontend
原則変更不要
必要なら表示名だけ調整
やらないこと
/api/admin/trade-session
Admin UUID
Admin Mode
特殊な TradePage 分岐
Serviceアカウント専用API
13. 最小フロー図
Text
[管理者]
   ↓
/fx login-as treasury-fee
   ↓
権限チェック
   ↓
svc:treasury-fee を確認 / 自動作成
   ↓
OTP発行
   ↓
/trade?otp=...
   ↓
POST /api/auth
   ↓
player session 発行
   ↓
[TradePage]
   - 通常の取引画面
   - 通常の残高制約
   - 通常の注文処理
14. 設計上の結論
Serviceアカウントは、

通常口座として保存され
特別なコマンドからログインでき
ログイン後は完全に通常口座として振る舞う
ものとする。