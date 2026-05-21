# 裁定取引（アービトラージ）スクリプト詳細仕様

**前提条件**
- スケール: 少人数、低取引量
- 手数料: 自分に返ってくるため利益計算は単純化
- 既存バックエンド: Javalin 6 内蔵HTTPサーバー、StorageManager (単一グローバルロック)、Bukkit scheduler 使用

---

## 1. 概要と実行モデル

### 目的
- 複数のアイテムペアを自動監視
- 価格差（スプレッド）を検出して自動裁定取引を実行
- 管理者（Admin）が任意のタイミングで停止可能
- 不正な利益確定を避けるための多段階フィルタリング

### 実行形式
- **Paper MC `Bukkit.getScheduler().runTaskTimerAsynchronously()`** で実行
- **単一の監視ループ** が複数のペアを順次チェック
- **フレーム分散実行** でコンカレンシー問題を回避

---

## 2. 設定値（`config.yml` に追加）

```yaml
arbitrage:
  # 基本動作
  enabled: true                          # 裁定取引の有効化フラグ
  service_account: "svc:arbitrage"       # 発注に使うサービスアカウント
  check_interval_ticks: 300              # 監視ループの間隔（15秒 = 300 ticks）
  
  # 利益判定
  min_gross_spread_pct: 0.5              # 最小グロススプレッド (手数料抜き) [%]
  min_net_profit_pct: 0.30               # 最小ネット利益 (手数料抜き後) [%]
  
  # スリップ検知（市場操作検出）
  slip_detection:
    price_change_threshold_pct: 3.0      # 直近チェック間隔で3%以上変動 → スリップ判定
    volume_drop_threshold_pct: 35        # オーダーブックの35%以上喪失 → スリップ判定
    check_lookback_ticks: 60             # スリップ判定の過去参照期間 (3秒)
  
  # リミット価格チェック（ストレスハンドリング）
  limit_price_tolerance_pct: 1.2         # リミット価格が約定可能価格から1.2%超離れたらキャンセル
  
  # キャンセル判定（部分約定時）
  partial_fill_cancel_policy:
    spread_divergence_threshold_pct: 2.0 # 残注文が現スプレッド外に開いたら検討
    midprice_proximity_threshold_pct: 0.5 # ただし中央値から0.5%以内なら残す
  
  # マルチフレーム実行
  frame_distribution:
    enabled: true
    phase_count: 3                       # 3フェーズに分散実行
    # Phase 1: スリップ判定・キャンセル
    # Phase 2: 価格差監視（フェーズシフト）
    # Phase 3: 発注実行
  
  # ロギング
  log_file: "plugins/PaperFX/logs/arbitrage.log"
  log_level: "INFO"  # INFO | DEBUG | VERBOSE

trading:
  # 既存の手数料設定（確認用）
  fee:
    maker: 0.10      # Maker手数料 0.10%
    taker: 0.16      # Taker手数料 0.16%
```

---

## 3. Admin 制御API

### 3.1 有効化・無効化
**エンドポイント**: `PATCH /api/admin/arbitrage/toggle`

**リクエスト**:
```json
{
  "enabled": true,
  "service_account": "svc:arbitrage"
}
```

**レスポンス**:
```json
{
  "enabled": true,
  "current_service_account": "svc:arbitrage",
  "timestamp": "2025-05-22T10:30:00Z"
}
```

**実装内容**:
- `StorageManager` にシングルトン `ArbitrageConfig` を追加
- `enabled` フラグとアカウント情報をメモリに保持
- `config.yml` に非同期で反映（次回起動時に復帰可能）
- HTTP クライアント不要（内部呼び出しのみ）

### 3.2 状態取得
**エンドポイント**: `GET /api/admin/arbitrage/status`

**レスポンス**:
```json
{
  "enabled": true,
  "service_account": "svc:arbitrage",
  "pairs_under_watch": ["ITEM-A/ITEM-B", "ITEM-C/ITEM-D"],
  "last_check": "2025-05-22T10:29:45Z",
  "last_execution": {
    "pair": "ITEM-A/ITEM-B",
    "timestamp": "2025-05-22T10:29:00Z",
    "status": "executed",
    "order_ids": ["order-uuid-1", "order-uuid-2"]
  },
  "recent_skips": [
    {
      "pair": "ITEM-C/ITEM-D",
      "reason": "slip_detected",
      "timestamp": "2025-05-22T10:28:30Z"
    }
  ]
}
```

---

## 4. 監視と発注ロジック

### 4.1 実行条件

- **有効化フラグ**: `arbitrage.enabled == true`
- **ペア数**: 2個以上存在
- **アカウント存在**: `serviceAccounts` に `service_account` 名が定義済み

### 4.2 監視ループの構造（非同期タスク）

```
runTaskTimerAsynchronously(plugin, () -> {
  if (!arbitrageConfig.enabled) return;  // ガード句
  
  List<Pair> pairs = storageManager.getPairs();
  if (pairs.size() < 2) return;
  
  // フレーム分散実行
  int currentPhase = (tickCounter % 3);
  
  for (Pair pair : pairs) {
    switch (currentPhase) {
      case 0 -> checkAndCancelPartialFills(pair);
      case 1 -> detectSlippage(pair);
      case 2 -> executeArbitrage(pair);
    }
  }
  
  tickCounter++;
}, 0, CHECK_INTERVAL_TICKS);
```

### 4.3 Phase 1: 部分約定時のキャンセル判定

**実行タイミング**: 毎回、但し Phase 0（3回に1回）

**ロジック**:

```
for each order in pair.pending_orders {
  if (order.partially_filled) {
    remaining_amount = order.original_amount - order.filled_amount;
    
    // 現在の最良 bid / ask を取得
    best_bid = pair.orderbook.bids[0].price;
    best_ask = pair.orderbook.asks[0].price;
    mid_price = (best_bid + best_ask) / 2;
    
    // 残注文の価格が現在のスプレッド外か判定
    spread = best_ask - best_bid;
    spread_pct = (spread / mid_price) * 100;
    
    order_distance_from_mid = abs(order.price - mid_price) / mid_price * 100;
    
    // キャンセル条件:
    // 1. 残注文が現スプレッド外に開いている AND
    // 2. 中央値から0.5%以上離れている
    if (order_distance_from_mid > (spread_pct + SPREAD_DIVERGENCE_THRESHOLD)
        && order_distance_from_mid > MIDPRICE_PROXIMITY_THRESHOLD) {
      
      logArbitrage("PARTIAL_FILL_CANCEL", pair.id, order.id, {
        "remaining_amount": remaining_amount,
        "order_distance_pct": order_distance_from_mid,
        "mid_price": mid_price
      });
      
      cancel(order.id);  // OrderService.cancelOrder() を直接呼び出し
    }
  }
}
```

**設定値の例**:
- `SPREAD_DIVERGENCE_THRESHOLD`: 2.0%
- `MIDPRICE_PROXIMITY_THRESHOLD`: 0.5%

### 4.4 Phase 1b: スリップ検知（市場操作検出）

**実行タイミング**: 毎回、但し Phase 1（3回に1回）

**ロジック**:

```
// 過去3秒間（60 ticks）の価格・ボリュームの履歴を参照
price_history = pair.price_history.getLast(LOOKBACK_TICKS);

// 価格変動率チェック
current_price = pair.lastPrice;
old_price = price_history[0];
price_change_pct = abs((current_price - old_price) / old_price) * 100;

if (price_change_pct > PRICE_CHANGE_THRESHOLD) {
  logArbitrage("SLIP_DETECTED_PRICE", pair.id, null, {
    "current": current_price,
    "previous": old_price,
    "change_pct": price_change_pct
  });
  pair.slip_flag = true;
  return;  // このペアは今回スキップ
}

// オーダーブック減少チェック
ask_volume_old = sum(price_history[0].asks);
ask_volume_current = sum(pair.orderbook.asks);
volume_drop_pct = (ask_volume_old - ask_volume_current) / ask_volume_old * 100;

if (volume_drop_pct > VOLUME_DROP_THRESHOLD) {
  logArbitrage("SLIP_DETECTED_VOLUME", pair.id, null, {
    "old_volume": ask_volume_old,
    "current_volume": ask_volume_current,
    "drop_pct": volume_drop_pct
  });
  pair.slip_flag = true;
  return;
}

pair.slip_flag = false;
```

**設定値の例**:
- `PRICE_CHANGE_THRESHOLD`: 3.0%
- `VOLUME_DROP_THRESHOLD`: 35%
- `LOOKBACK_TICKS`: 60 (3秒)

### 4.5 Phase 2: 発注実行

**実行タイミング**: Phase 2（3回に1回）

**前提チェック**:
1. `slip_flag == false`
2. アカウント残高充分
3. ペア数 >= 2

**ロジック**:

```
// ロック獲得（StorageManager の単一グローバルロック）
storageManager.acquireLock(() -> {
  
  // 現在のスプレッド計算
  best_bid = pair.orderbook.bids[0].price;
  best_ask = pair.orderbook.asks[0].price;
  spread = best_ask - best_bid;
  spread_pct = (spread / best_bid) * 100;  // または mid_price
  
  // グロススプレッド判定
  if (spread_pct < MIN_GROSS_SPREAD) {
    logArbitrage("SKIP_LOW_SPREAD", pair.id, null, {
      "spread_pct": spread_pct,
      "min_required": MIN_GROSS_SPREAD
    });
    return;
  }
  
  // 手数料を考慮したネット利益計算
  // Maker: 0.10%, Taker: 0.16%
  // 買い: Taker 0.16%, 売り: Maker 0.10%
  fee_cost_pct = 0.16 + 0.10;  // 0.26%
  net_profit_pct = spread_pct - fee_cost_pct;
  
  if (net_profit_pct < MIN_NET_PROFIT) {
    logArbitrage("SKIP_LOW_PROFIT", pair.id, null, {
      "net_profit_pct": net_profit_pct,
      "min_required": MIN_NET_PROFIT
    });
    return;
  }
  
  // 発注可能な数量を決定（保守的に）
  account = getAccount(service_account);
  max_buy_qty = floor(account.hot_storage[pair.ask_item] / best_ask);
  max_sell_qty = account.hot_storage[pair.bid_item];
  trade_qty = min(max_buy_qty, max_sell_qty);
  
  if (trade_qty == 0) {
    logArbitrage("SKIP_INSUFFICIENT_BALANCE", pair.id, null);
    return;
  }
  
  // 発注（両サイド同時）
  try {
    buy_order = orderService.placeOrder(
      service_account,
      pair.id,
      OrderSide.BUY,
      OrderType.LIMIT,
      best_ask,
      trade_qty
    );
    
    sell_order = orderService.placeOrder(
      service_account,
      pair.id,
      OrderSide.SELL,
      OrderType.LIMIT,
      best_bid,
      trade_qty
    );
    
    logArbitrage("EXECUTED", pair.id, null, {
      "buy_order_id": buy_order.id,
      "sell_order_id": sell_order.id,
      "quantity": trade_qty,
      "spread_pct": spread_pct,
      "net_profit_pct": net_profit_pct
    });
    
  } catch (Exception e) {
    logArbitrage("EXECUTION_ERROR", pair.id, null, {
      "error": e.getMessage()
    });
    // ロールバック不要（発注は atomic）
  }
});
```

---

## 5. フレーム分散実行の詳細

### 目的
- **コンカレンシー問題の回避**: 同じペアで同時に複数の発注がかからない
- **Bukkit scheduler との同期**: 各フェーズが異なる async ティックで実行

### 実行パターン

```
ティック 0, 3, 6, 9...  → Phase 0 (部分約定キャンセル + スリップ判定)
ティック 1, 4, 7, 10... → Phase 1 (スリップ判定２ラウンド)
ティック 2, 5, 8, 11... → Phase 2 (発注実行)
```

### コード例

```java
private int tickCounter = 0;

public void startArbitrageLoop() {
  Bukkit.getScheduler().runTaskTimerAsynchronously(
    plugin,
    () -> {
      if (!arbitrageConfig.enabled) return;
      
      int phase = tickCounter % 3;
      List<Pair> pairs = storageManager.getPairs();
      
      if (pairs.size() < 2) return;
      
      for (Pair pair : pairs) {
        try {
          switch (phase) {
            case 0: // Cancel partial fills + Detect slip
              checkAndCancelPartialFills(pair);
              detectSlippage(pair);
              break;
            case 1: // Re-check slip (safety margin)
              detectSlippage(pair);
              break;
            case 2: // Execute arbitrage
              if (!pair.slip_flag) {
                executeArbitrage(pair);
              }
              break;
          }
        } catch (Exception e) {
          logArbitrage("LOOP_ERROR", pair.id, null, {
            "phase": phase,
            "error": e.getMessage()
          });
        }
      }
      
      tickCounter++;
    },
    0,                              // delay (ticks)
    CHECK_INTERVAL_TICKS            // period (300 ticks = 15 sec)
  );
}

public void stopArbitrageLoop(BukkitTask task) {
  if (task != null) {
    task.cancel();
  }
}
```

---

## 6. ロギング

### ログファイル: `plugins/PaperFX/logs/arbitrage.log`

### ログ形式

```
[TIMESTAMP] [LEVEL] [PAIR_ID] [ACTION] [ORDER_ID] [DETAILS]
```

### ログ例

```
2025-05-22T10:29:00.123Z [INFO] [ITEM-A/ITEM-B] [EXECUTED] [null] {buy_order_id=uuid-1, sell_order_id=uuid-2, quantity=64, spread_pct=0.52, net_profit_pct=0.26}

2025-05-22T10:28:30.456Z [WARN] [ITEM-C/ITEM-D] [SLIP_DETECTED_PRICE] [null] {current=100.5, previous=97.5, change_pct=3.08}

2025-05-22T10:28:15.789Z [DEBUG] [ITEM-A/ITEM-B] [SKIP_LOW_SPREAD] [null] {spread_pct=0.18, min_required=0.50}

2025-05-22T10:27:45.012Z [INFO] [ITEM-A/ITEM-B] [PARTIAL_FILL_CANCEL] [order-uuid-123] {remaining_amount=32, order_distance_pct=1.8, mid_price=100.25}

2025-05-22T10:29:15.234Z [ERROR] [ITEM-B/ITEM-C] [EXECUTION_ERROR] [null] {error=Insufficient balance for account}
```

### 実装（ロギングユーティリティ）

```java
public class ArbitrageLogger {
  private static final Logger logger = 
    LoggerFactory.getLogger(ArbitrageLogger.class);
  
  public static void log(String action, String pairId, String orderId, 
                         Map<String, Object> details) {
    String message = String.format(
      "[%s] [%s] [%s] %s",
      pairId,
      action,
      orderId != null ? orderId : "null",
      details
    );
    
    switch (action) {
      case "EXECUTED":
        logger.info(message);
        break;
      case "SLIP_DETECTED_PRICE", "SLIP_DETECTED_VOLUME":
        logger.warn(message);
        break;
      case "SKIP_LOW_SPREAD", "SKIP_LOW_PROFIT":
        logger.debug(message);
        break;
      case "EXECUTION_ERROR", "LOOP_ERROR":
        logger.error(message);
        break;
      default:
        logger.info(message);
    }
  }
}
```

---

## 7. エラーハンドリング

| エラー | 対応 | ログレベル |
|--------|------|-----------|
| アカウント残高不足 | 発注スキップ、続行 | DEBUG |
| 注文執行失敗 | ログ記録、ペア続行 | ERROR |
| ロック獲得タイムアウト | リトライなし、スキップ | WARN |
| 無効な price/quantity | 例外キャッチ、ロールバック | ERROR |
| Pair 存在なし | スキップ、続行 | DEBUG |

---

## 8. セキュリティ

### 認証
- `/api/admin/arbitrage/*` は **admin Bearer トークン必須**

### 検証
- `service_account` は `config.yml` の `serviceAccounts` に定義済みのみ許可
- 残高チェック・手数料計算は OrderService が一貫実行
- 発注は StorageManager ロック内で atomic

### コンカレンシー
- 全ての状態変更は StorageManager ロック内
- Bukkit scheduler の async タスク → ロック獲得で同期

---

## 9. 停止・シャットダウン

### Admin から停止

```
PATCH /api/admin/arbitrage/toggle
{
  "enabled": false
}
```

- 即座に `arbitrageConfig.enabled = false` に変更
- 実行中のループ：ガード句で自動停止
- 進行中の発注：atomic なため安全

### プラグイン停止時（onDisable）

```java
public void onDisable() {
  if (arbitrageTask != null) {
    arbitrageTask.cancel();  // Bukkit scheduler から削除
  }
  
  // StorageManager.shutdown() で pending データ同期
  storageManager.shutdown();
}
```

---

## 10. 今後の拡張ポイント

- [ ] 複数ペアの **相互依存関係** 検出（例: A→B→C の3点アービトラージ）
- [ ] **アルゴリズム学習**: スリップ閾値の自動調整
- [ ] **レート制限**: 1分あたりの最大発注数制限
- [ ] **利益ターゲット**: 目標利益達成で自動停止

---

## チェックリスト

- [ ] `config.yml` に arbitrage セクション追加
- [ ] `ArbitrageConfig` クラス実装
- [ ] `/api/admin/arbitrage/toggle` エンドポイント実装
- [ ] `/api/admin/arbitrage/status` エンドポイント実装
- [ ] `ArbitrageEngine` クラス（監視ループ）実装
- [ ] `checkAndCancelPartialFills()` 実装
- [ ] `detectSlippage()` 実装
- [ ] `executeArbitrage()` 実装
- [ ] `ArbitrageLogger` 実装
- [ ] 初期テスト（低スケール）
- [ ] ログ確認・チューニング