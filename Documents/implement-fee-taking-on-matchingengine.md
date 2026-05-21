# 手数料の徴収をマッチングエンジンに追加

## 概要

約定時に Maker / Taker それぞれから手数料を徴収し、`svc:treasury-fee` のホット残高へ振り込む。

---

## 手数料の徴収構造

約定1回につき、**買い手・売り手の両側から独立して**手数料を徴収する。

| 立場 | 受け取るもの | 手数料として差し引かれるもの |
|---|---|---|
| 買い手（base 受取） | base | `execAmount × feeRate(base)` |
| 売り手（quote 受取） | quote | `cost × feeRate(quote)` |

- base 側の手数料 → `svc:treasury-fee` の base 残高へ加算
- quote 側の手数料 → `svc:treasury-fee` の quote 残高へ加算
- 約定と同一トランザクション内（`settle()` 内）で処理する

---

## Maker / Taker 手数料率

`config.yml` で設定可能。

```yaml
fee:
  maker: "0.0010"   # 0.10%
  taker: "0.0012"   # 0.12%
```

- 板に残っている注文の相手 = **Maker**
- `placeOrder()` で新たに入ってきた注文 = **Taker**
- 現エンジンでは `incoming` が Taker、板の相手注文が Maker として自然に識別できる

---

## 通貨キーごとの手数料率オーバーライド（TempKey 対応）

Minecraft に存在しないアイテム名（TempKey）を含むペアの手数料率を個別に設定できる。

```yaml
feeOverrides:
  TempKey: "0.0050"      # 0.50%
  SpecialCoin: "0.0000"  # 手数料なし
```

- ペアの base または quote が `feeOverrides` に登録されていた場合、その通貨側の手数料率をオーバーライド値で計算する
- 登録がない通貨はグローバルの `fee.maker` / `fee.taker` を使用する
- Maker/Taker の区別はオーバーライド後も維持する

---

## API 公開

```
GET /api/pairs/:id/fee
```

レスポンス例:
```json
{
  "pair": "DIAMOND/TEMPKEY",
  "maker_base": "0.0010",
  "taker_base": "0.0012",
  "maker_quote": "0.0050",
  "taker_quote": "0.0050"
}
```

---

## 初期実装スコープ

- [ ] `settle()` に手数料差し引きを追加
- [ ] `svc:treasury-fee` のホット残高へ振り込み
- [ ] `config.yml` に `fee.maker` / `fee.taker` を追加
- [ ] `feeOverrides` による通貨キー単位の手数料率オーバーライド
- [ ] `/api/pairs/:id/fee` エンドポイント追加

---

## 将来対応（未実装）

### ATM グレードによる手数料配分

Minecraft ワールド内に設置された ATM ブロックのグレードに応じて、
徴収した手数料を `svc:treasury-fee`（システム）と ATM 設置者で分配する。

設定例（config.yml イメージ）:
```yaml
atmGrades:
  none:
    makerFee: "0.0010"
    takerFee: "0.0014"
    treasuryShare: 1.00   # treasury-fee が 100% 取得
  iron_block:
    makerFee: "0.0010"
    takerFee: "0.0012"
    treasuryShare: 0.70   # treasury-fee 70%、設置者 30%
  diamond_block:
    makerFee: "0.0008"
    takerFee: "0.0010"
    treasuryShare: 0.30   # treasury-fee 30%、設置者 70%
```

- ATM 設置者への支払いは設置者の PlayerData ホット残高へ直接振り込み
- ATM の座標・設置者 UUID・グレードを別途管理するデータ構造が必要
- 現フェーズでは「ATM なし（treasury-fee 100%）」のみ実装し、ATM 連携は後で拡張する
