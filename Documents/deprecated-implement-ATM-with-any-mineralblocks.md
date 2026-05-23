# ATM（特殊看板）による手数料分配仕様

**前提条件**
- 既存システムの StorageManager、マッチングエンジン、PlayerData を流用
- ATM = Minecraft ワールドに配置されたブロック（ブロックタイプがグレード）＋看板
- 取引手数料をプレイヤーが設置したATMのオーナーと `svc:treasury-fee` で分配

---

## 1. ATM グレードと手数料体系

### 1.1 グレード定義

| グレード | ブロック | Maker手数料 | Taker手数料 | Treasury シェア | Owner シェア |
|---------|---------|-----------|-----------|-----------------|-----------|
| **none** | なし（デフォルト） | 0.10% | 0.16% | 100% | 0% |
| **iron** | Iron Block | 0.08% | 0.12% | 65% | 35% |
| **diamond** | Diamond Block | 0.05% | 0.08% | 25% | 75% |
| **netherite** | Netherite Block | 0.03% | 0.05% | 10% | 90% |

### 1.2 config.yml 設定例

```yaml
atm:
  enabled: true
  grades:
    none:
      block_type: ~  # なし（看板のみでもATMになる）
      maker_fee_pct: 0.0010
      taker_fee_pct: 0.0016
      treasury_share: 1.00
      owner_share: 0.00
    
    iron:
      block_type: "IRON_BLOCK"
      maker_fee_pct: 0.0008
      taker_fee_pct: 0.0012
      treasury_share: 0.65
      owner_share: 0.35
    
    diamond:
      block_type: "DIAMOND_BLOCK"
      maker_fee_pct: 0.0005
      taker_fee_pct: 0.0008
      treasury_share: 0.25
      owner_share: 0.75
    
    netherite:
      block_type: "NETHERITE_BLOCK"
      maker_fee_pct: 0.0003
      taker_fee_pct: 0.0005
      treasury_share: 0.10
      owner_share: 0.90

# ATM メタデータ
atm_metadata:
  sign_line_1_required: "[FX]"       # Line 1（1行目）の必須文字列
  owner_name_line: 2                 # Line 2（2行目）に所有者名を入力
  auto_format: true                  # Lines 3, 4 を自動生成
  max_atms_per_player: 5             # プレイヤーあたりの最大ATM数
```

---

## 2. データ構造（storage.json に追加）

### 2.1 ATM Registry

```json
{
  "atms": {
    "atm-uuid-1": {
      "id": "atm-uuid-1",
      "location": {
        "world": "world",
        "x": 100,
        "y": 64,
        "z": 200
      },
      "owner_uuid": "player-uuid-abc",
      "owner_name": "Alice",
      "grade": "diamond",
      "block_type": "DIAMOND_BLOCK",
      "sign_location": {
        "world": "world",
        "x": 100,
        "y": 65,
        "z": 200
      },
      "created_at": "2025-05-22T10:00:00Z",
      "total_fees_earned": {
        "ITEM_A": 125.50,
        "ITEM_B": 45.20
      },
      "last_payout_at": "2025-05-22T10:00:00Z",
      "pending_payout": {
        "ITEM_A": 10.50,
        "ITEM_B": 2.20
      },
      "status": "active"  # "active" | "destroyed" | "unregistered"
    }
  }
}
```

### 2.2 storageManager への追加メソッド

```java
public class StorageManager {
  // ATM Registry を管理
  public AtmRegistry getAtmRegistry();
  public void registerAtm(AtmData atmData);
  public void unregisterAtm(String atmId);
  public AtmData getAtmByLocation(Location location);
  public List<AtmData> getAtmsByOwner(UUID ownerUuid);
  public void updateAtmFees(String atmId, String itemId, double amount);
  public void payoutAtmOwner(String atmId);
}
```

---

## 3. ATM 設置・登録フロー

### 3.1 看板設置時の検验

**Bukkit Event**: `SignChangeEvent`

**フロー**:

```
1. プレイヤーが看板を配置・編集
2. Line 1 が "[FX]" か検験
3. YES → ATM登録候補
   NO  → 通常の看板として処理（何もしない）

4. Line 2 をプレイヤー入力（オーナー名指定）
   例: "Alice" または "svc:arbitrage"

5. Line 2 の妥当性チェック:
   - PlayerData に該当プレイヤー/サービスアカウントが存在するか
   - 存在しない → ❌ エラーメッセージ表示、看板設置キャンセル
   - 存在する → ✅ 続行

6. 看板の下ブロック（y-1）のタイプを確認
   - IRON_BLOCK → grade = "iron"
   - DIAMOND_BLOCK → grade = "diamond"
   - NETHERITE_BLOCK → grade = "netherite"
   - その他 → grade = "none"

7. オーナーのATM数チェック:
   - max_atms_per_player を超えていないか
   - 超えてたら❌ エラーメッセージ表示

8. ATMData オブジェクトを生成、StorageManager に登録

9. 看板の Line 3, 4 を自動生成・フォーマット
   Line 3: "Maker: 0.05%"
   Line 4: "Taker: 0.08%"
```

### 3.2 看板フォーマット例

設置後の看板表示（Line 1～4）:

```
[FX]
Alice's ATM
Maker: 0.05%
Taker: 0.08%
```

---

## 4. 手数料分配ロジック

### 4.1 マッチング時の分配

**マッチング実行時**（`MatchingEngine.execute()`）

```java
public class MatchingEngine {
  
  public Execution match(Order buyOrder, Order sellOrder) {
    
    // 既存の約定ロジック...
    Execution exec = new Execution(
      pair_id, 
      price, 
      quantity, 
      timestamp
    );
    
    // ↓ 新規処理：手数料分配
    
    // 1. 取引に関連する ATM を特定
    AtmData buyerAtm = storageManager.getAtmByContextOrNull(buyOrder.player_uuid);
    AtmData sellerAtm = storageManager.getAtmByContextOrNull(sellOrder.player_uuid);
    
    // 注: 同じATM/プレイヤーが買い手・売り手の場合は
    //     buyerAtm == sellerAtm → 分配は treasury のみ
    
    // 2. Maker手数料計算
    makerFeeAbs = quantity * price * GRADE_CONFIG[grade].maker_fee_pct / 100;
    
    // 3. Taker手数料計算
    takerFeeAbs = quantity * price * GRADE_CONFIG[grade].taker_fee_pct / 100;
    
    // 4. 買い手側の手数料分配（Taker）
    if (buyerAtm != null) {
      treasury_buyer_fee = takerFeeAbs * buyerAtm.treasuryShare;
      owner_buyer_fee = takerFeeAbs * buyerAtm.ownerShare;
      
      // Execution レコードに ATM情報を埋める
      exec.buyer_atm_id = buyerAtm.id;
      exec.buyer_atm_grade = buyerAtm.grade;
      exec.buyer_treasury_fee = treasury_buyer_fee;
      exec.buyer_owner_fee = owner_buyer_fee;
      
      // オーナーに pending_payout を積む
      buyerAtm.pending_payout[pair.ask_item] += owner_buyer_fee;
      
      // Treasury に加算（次のセクション）
      treasury_fee += treasury_buyer_fee;
    } else {
      // ATM経由でない → 全額 treasury
      treasury_fee += takerFeeAbs;
    }
    
    // 5. 売り手側の手数料分配（Maker）
    if (sellerAtm != null) {
      treasury_seller_fee = makerFeeAbs * sellerAtm.treasuryShare;
      owner_seller_fee = makerFeeAbs * sellerAtm.ownerShare;
      
      exec.seller_atm_id = sellerAtm.id;
      exec.seller_atm_grade = sellerAtm.grade;
      exec.seller_treasury_fee = treasury_seller_fee;
      exec.seller_owner_fee = owner_seller_fee;
      
      sellerAtm.pending_payout[pair.bid_item] += owner_seller_fee;
      
      treasury_fee += treasury_seller_fee;
    } else {
      treasury_fee += makerFeeAbs;
    }
    
    // 6. Treasury アカウント（svc:treasury-fee）に加算
    treasuryAccount.hot_storage[fee_currency] += treasury_fee;
    
    // 7. 約定を記録・永続化
    storageManager.recordExecution(exec);
    storageManager.markDirty();
    
    return exec;
  }
}
```

### 4.2 Execution テーブルに追加するカラム（H2 DB）

```sql
ALTER TABLE executions ADD COLUMN buyer_atm_id VARCHAR(36);
ALTER TABLE executions ADD COLUMN buyer_atm_grade VARCHAR(20);
ALTER TABLE executions ADD COLUMN buyer_treasury_fee DECIMAL(18,8);
ALTER TABLE executions ADD COLUMN buyer_owner_fee DECIMAL(18,8);
ALTER TABLE executions ADD COLUMN seller_atm_id VARCHAR(36);
ALTER TABLE executions ADD COLUMN seller_atm_grade VARCHAR(20);
ALTER TABLE executions ADD COLUMN seller_treasury_fee DECIMAL(18,8);
ALTER TABLE executions ADD COLUMN seller_owner_fee DECIMAL(18,8);
```

---

## 5. ATM オーナーへの支払い（Payout）

### 5.1 支払いトリガー

**3つのトリガー**:

1. **定期自動支払い**（オプション）
   - 1時間ごと、または pending_payout の合計が一定額以上
   - 設定値: `payout_trigger_interval_minutes`, `payout_trigger_amount`

2. **ATM破壊時**
   - ATM が破壊される瞬間に全額支払い（以下の項 5.2 参照）

3. **手動支払い（Admin コマンド）**
   - `/fx admin payout-atm <atm-id>`

### 5.2 ATM 破壊時の処理

**Bukkit Event**: `BlockBreakEvent`（看板破壊）

```java
@EventHandler
public void onSignBreak(BlockBreakEvent event) {
  Block block = event.getBlock();
  
  // 破壊されるブロックが看板か確認
  if (!(block.getState() instanceof Sign sign)) {
    return;
  }
  
  // 看板が ATM か確認
  String line1 = sign.getLine(0);
  if (!line1.equals("[FX]")) {
    return;  // 通常の看板
  }
  
  // ATM を特定
  AtmData atm = storageManager.getAtmBySignLocation(block.getLocation());
  if (atm == null) {
    return;  // 未登録のATM
  }
  
  // ロック獲得
  storageManager.acquireLock(() -> {
    
    // 1. pending_payout を全て支払う
    for (Map.Entry<String, Double> entry : atm.pending_payout.entrySet()) {
      String itemId = entry.getKey();
      double amount = entry.getValue();
      
      PlayerData ownerData = storageManager.getPlayerData(atm.owner_uuid);
      ownerData.hot_storage[itemId] += amount;
      
      logAtmPayout(atm.id, "BREAK", itemId, amount, atm.owner_name);
    }
    
    // 2. ATM ステータスを "destroyed" に更新
    atm.status = "destroyed";
    
    // 3. ブロック破壊を許可
    event.setCancelled(false);
    
    storageManager.markDirty();
  });
}
```

### 5.3 定期自動支払い（オプション機能）

**Bukkit Async Task**:

```java
private void startPayoutScheduler() {
  Bukkit.getScheduler().runTaskTimerAsynchronously(
    plugin,
    () -> {
      storageManager.acquireLock(() -> {
        List<AtmData> atms = storageManager.getAtmRegistry().getActiveAtms();
        
        for (AtmData atm : atms) {
          double totalPending = atm.pending_payout.values()
            .stream()
            .mapToDouble(Double::doubleValue)
            .sum();
          
          // トリガー条件チェック
          if (shouldPayout(atm, totalPending)) {
            payoutAtmOwner(atm);
          }
        }
      });
    },
    0,
    PAYOUT_CHECK_INTERVAL_TICKS  // 例: 20 * 60 * 60 = 3600 (1時間)
  );
}

private boolean shouldPayout(AtmData atm, double totalPending) {
  if (totalPending < PAYOUT_MIN_AMOUNT) {
    return false;  // 最小額未達
  }
  
  long timeSinceLastPayout = 
    Instant.now().getEpochSecond() - atm.last_payout_at.getEpochSecond();
  
  if (timeSinceLastPayout < PAYOUT_MIN_INTERVAL_SECONDS) {
    return false;  // 支払い頻度制限
  }
  
  return true;
}

private void payoutAtmOwner(AtmData atm) {
  PlayerData ownerData = storageManager.getPlayerData(atm.owner_uuid);
  
  for (Map.Entry<String, Double> entry : atm.pending_payout.entrySet()) {
    String itemId = entry.getKey();
    double amount = entry.getValue();
    
    ownerData.hot_storage[itemId] += amount;
    atm.total_fees_earned[itemId] += amount;
    atm.pending_payout[itemId] = 0.0;
    
    logAtmPayout(atm.id, "AUTO", itemId, amount, atm.owner_name);
  }
  
  atm.last_payout_at = Instant.now();
  storageManager.markDirty();
}

private void logAtmPayout(String atmId, String reason, String itemId, 
                           double amount, String ownerName) {
  String message = String.format(
    "[ATM_PAYOUT] %s | ATM: %s | Owner: %s | Item: %s | Amount: %.8f | Reason: %s",
    Instant.now(), atmId, ownerName, itemId, amount, reason
  );
  logger.info(message);
}
```

---

## 6. 看板右クリック→ FX UI 起動

**Bukkit Event**: `PlayerInteractEvent`

```java
@EventHandler
public void onSignClick(PlayerInteractEvent event) {
  
  if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
    return;
  }
  
  Block block = event.getClickedBlock();
  if (!(block.getState() instanceof Sign sign)) {
    return;
  }
  
  // Line 1 が [FX] か確認
  String line1 = sign.getLine(0);
  if (!line1.equals("[FX]")) {
    return;  // ATM ではない
  }
  
  event.setCancelled(true);  // 看板の通常編集を禁止
  
  // ATM 情報を取得
  AtmData atm = storageManager.getAtmBySignLocation(block.getLocation());
  if (atm == null || !atm.status.equals("active")) {
    event.getPlayer().sendMessage(
      "§c[FX] This ATM is not active."
    );
    return;
  }
  
  // プレイヤーに OTP を発行して `/trade?otp=...` へのリンクを表示
  Player player = event.getPlayer();
  String otp = otpManager.generateOtp(player.getUniqueId());
  
  // ATM グレード情報を含む welcome message
  player.sendMessage(
    String.format("§a[FX] Welcome to %s's ATM (Grade: %s)!",
      atm.owner_name,
      atm.grade.toUpperCase()
    )
  );
  
  player.sendMessage(
    String.format("§7Fee: Maker %.3f%% | Taker %.3f%%",
      GRADE_CONFIG[atm.grade].maker_fee_pct,
      GRADE_CONFIG[atm.grade].taker_fee_pct
    )
  );
  
  player.sendMessage(
    String.format("§e[CLICK] http://your-server-ip:8080/trade?otp=%s", otp)
  );
  
  // または clickable text component (Paper API)
  player.spigot().sendMessage(
    createClickableLink(
      "Click here to trade",
      String.format("http://your-server-ip:8080/trade?otp=%s", otp)
    )
  );
}
```

---

## 7. API: ATM 情報取得・管理

### 7.1 公開API（認証不要）

**GET `/api/atms`**

```json
{
  "atms": [
    {
      "id": "atm-uuid-1",
      "location": { "world": "world", "x": 100, "y": 64, "z": 200 },
      "owner_name": "Alice",
      "grade": "diamond",
      "maker_fee_pct": 0.05,
      "taker_fee_pct": 0.08,
      "status": "active"
    }
  ]
}
```

**GET `/api/atms/{atm-id}`**

```json
{
  "id": "atm-uuid-1",
  "owner_name": "Alice",
  "grade": "diamond",
  "maker_fee_pct": 0.05,
  "taker_fee_pct": 0.08,
  "location": { "world": "world", "x": 100, "y": 64, "z": 200 },
  "created_at": "2025-05-22T10:00:00Z"
}
```

### 7.2 プレイヤーAPI（Bearer 必須）

**GET `/api/my-atms`**

プレイヤーが所有する全 ATM を取得

```json
{
  "atms": [
    {
      "id": "atm-uuid-1",
      "grade": "diamond",
      "total_fees_earned": { "ITEM_A": 125.50, "ITEM_B": 45.20 },
      "pending_payout": { "ITEM_A": 10.50, "ITEM_B": 2.20 },
      "status": "active"
    }
  ]
}
```

**POST `/api/my-atms/{atm-id}/payout`**

ATM オーナーが手動支払いをリクエスト

```json
{
  "atm_id": "atm-uuid-1"
}
```

**Response**:

```json
{
  "status": "success",
  "payout_amount": 12.70,
  "currency": "ITEM_A",
  "timestamp": "2025-05-22T10:30:00Z"
}
```

### 7.3 管理者API（admin Bearer 必須）

**GET `/api/admin/atms`**

全 ATM の情報を取得（status 含む）

**PATCH `/api/admin/atms/{atm-id}/status`**

ATM ステータスを変更（例：無効化）

```json
{
  "status": "disabled"  // "active" | "disabled" | "destroyed"
}
```

**POST `/api/admin/atms/{atm-id}/force-payout`**

管理者が強制支払い

---

## 8. エラーハンドリングとバリデーション

| 場面 | エラー | 対応 |
|------|--------|------|
| 看板設置 | Line 2 に無効なプレイヤー名 | ❌ 看板設置キャンセル、メッセージ表示 |
| 看板設置 | ATM数上限到達 | ❌ キャンセル、メッセージ表示 |
| 看板設置 | 下ブロックが空気 | ✅ 許可（grade = "none"） |
| 看板右クリック | ATM 未登録 | ❌ メッセージ表示 |
| 看板右クリック | ATM ステータス != "active" | ❌ メッセージ表示 |
| マッチング | 不正な ATM ID | ⚠️ ログ記録、treasury のみに支払い |
| Payout | オーナーがオフライン | pending_payout 保留（ログイン時処理なし） |
| 破壊 | ブロック破壊者がオーナーではない | ✅ 破壊許可、支払い実行（権限制御は別途要） |

---

## 9. コンカレンシー・一貫性保証

**全ての ATM 関連処理は StorageManager ロック内で実行**

```java
storageManager.acquireLock(() -> {
  // ATM 取得・更新
  // PlayerData 更新
  // pending_payout の加算
  // 支払い実行
});
```

---

## 10. 設定値まとめ（config.yml）

```yaml
atm:
  enabled: true
  max_atms_per_player: 5
  
  # 自動支払い設定
  auto_payout:
    enabled: true
    check_interval_minutes: 60
    min_payout_amount: 5.0
    min_interval_hours: 1
  
  # グレード定義
  grades:
    none: { ... }
    iron: { ... }
    diamond: { ... }
    netherite: { ... }
  
  # ロギング
  log_file: "plugins/PaperFX/logs/atm.log"
  log_level: "INFO"
```

---

## 11. チェックリスト

- [ ] `AtmData` クラス実装
- [ ] `AtmRegistry` クラス実装
- [ ] `StorageManager.getAtmRegistry()` 追加
- [ ] `SignChangeEvent` リスナー実装
- [ ] `BlockBreakEvent` リスナー実装
- [ ] `PlayerInteractEvent` リスナー実装
- [ ] `MatchingEngine.match()` に手数料分配ロジック追加
- [ ] H2 DB に ATM カラム追加
- [ ] `/api/atms` エンドポイント実装
- [ ] `/api/my-atms` エンドポイント実装
- [ ] `/api/admin/atms` エンドポイント実装
- [ ] `PayoutScheduler` 実装
- [ ] `AtmLogger` 実装
- [ ] 初期テスト（単一 ATM）
- [ ] 複数 ATM 同時実行テスト
- [ ] ATM 破壊時の支払いテスト