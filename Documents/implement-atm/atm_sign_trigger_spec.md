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

### 3.3 看板設置時のブロック範囲検証

**Bukkit Event**: `SignChangeEvent`

ATM として機能させるために、看板周辺のブロック配置をチェックします。

**ブロック配置パターン**（看板位置を基準に）:

```
Y座標（上下）
    Y+2    Y+1     Y（看板レベル）
    
1段目（Y）: 3×3ブロック必須
  ■■■
  ■■■
  ■■■

2段目（Y+1）: 中央ブロックのみ必須
  □□□
  □■□
  □□□

3段目（Y+2）: 中央ブロックのみ必須
  □□□
  □■□
  □▽□  ← 看板位置（右奥）
```

**座標計算**:

看板位置を `(sign_x, sign_y, sign_z)` とした場合：

```
1段目（Y = sign_y）:
  検証対象: (sign_x-1 to sign_x+1, sign_y, sign_z-1 to sign_z+1)
  要件: 全9ブロック、全て solid ブロック必須

2段目（Y+1）:
  検証対象: (sign_x, sign_y+1, sign_z)
  要件: solid ブロック必須

3段目（Y+2）:
  検証対象: (sign_x, sign_y+2, sign_z)
  要件: solid ブロック必須
```

**実装**:

```java
@EventHandler
public void onSignChange(SignChangeEvent event) {
  
  Player player = event.getPlayer();
  Block signBlock = event.getBlock();
  
  // Line 1 が [FX] か確認
  String line1 = event.getLine(0);
  if (!line1.equals("[FX]")) {
    return;  // 通常の看板、処理スキップ
  }
  
  // ========== ATM ブロック配置チェック ==========
  Location signLoc = signBlock.getLocation();
  int sx = signLoc.getBlockX();
  int sy = signLoc.getBlockY();
  int sz = signLoc.getBlockZ();
  World world = signLoc.getWorld();
  
  // ロック獲得
  storageManager.acquireLock(() -> {
    
    // 1段目: 3×3 ブロック検証
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        Block b = world.getBlockAt(sx + dx, sy, sz + dz);
        
        if (!isSolidBlock(b)) {
          player.sendMessage(
            String.format(
              "§c[FX] ATM requires solid block structure. " +
              "Missing at (%d, %d, %d)",
              sx + dx, sy, sz + dz
            )
          );
          event.setCancelled(true);
          return;
        }
      }
    }
    
    // 2段目: 中央ブロック検証
    Block b2 = world.getBlockAt(sx, sy + 1, sz);
    if (!isSolidBlock(b2)) {
      player.sendMessage(
        String.format(
          "§c[FX] ATM requires center block at Y+1 (%d, %d, %d)",
          sx, sy + 1, sz
        )
      );
      event.setCancelled(true);
      return;
    }
    
    // 3段目: 中央ブロック検証
    Block b3 = world.getBlockAt(sx, sy + 2, sz);
    if (!isSolidBlock(b3)) {
      player.sendMessage(
        String.format(
          "§c[FX] ATM requires center block at Y+2 (%d, %d, %d)",
          sx, sy + 2, sz
        )
      );
      event.setCancelled(true);
      return;
    }
    
    // ========== Line 2 の妥当性チェック ==========
    String line2 = event.getLine(1);  // オーナー名入力行
    
    if (line2 == null || line2.trim().isEmpty()) {
      player.sendMessage(
        "§c[FX] Line 2 must contain owner name or service account."
      );
      event.setCancelled(true);
      return;
    }
    
    PlayerData ownerData = null;
    String ownerName = line2.trim();
    UUID ownerUuid = null;
    
    // プレイヤー名またはサービスアカウント名で検索
    if (ownerName.startsWith("svc:")) {
      // サービスアカウント
      ownerData = storageManager.getPlayerDataByName(ownerName);
    } else {
      // プレイヤー UUID で検索
      ownerUuid = Bukkit.getPlayerUniqueId(ownerName);
      if (ownerUuid != null) {
        ownerData = storageManager.getPlayerData(ownerUuid);
      }
    }
    
    if (ownerData == null) {
      player.sendMessage(
        String.format(
          "§c[FX] Owner not found: %s", ownerName
        )
      );
      event.setCancelled(true);
      return;
    }
    
    if (ownerUuid == null && ownerName.startsWith("svc:")) {
      ownerUuid = UUID.nameUUIDFromBytes(ownerName.getBytes());
    }
    
    // ========== ATM 数制限チェック ==========
    int currentAtmCount = storageManager.getAtmRegistry()
      .getAtmsByOwner(ownerUuid)
      .size();
    
    if (currentAtmCount >= MAX_ATMS_PER_PLAYER) {
      player.sendMessage(
        String.format(
          "§c[FX] Owner has reached max ATMs (%d/%d)",
          currentAtmCount, MAX_ATMS_PER_PLAYER
        )
      );
      event.setCancelled(true);
      return;
    }
    
    // ========== ブロックタイプからグレードを判定 ==========
    Block baseBlock = world.getBlockAt(sx, sy, sz);  // 看板直下
    String grade = "none";
    
    // 下のブロック（1段目の中央ブロック）でグレード判定
    Block centerBase = world.getBlockAt(sx, sy, sz);
    if (centerBase.getType() == Material.IRON_BLOCK) {
      grade = "iron";
    } else if (centerBase.getType() == Material.DIAMOND_BLOCK) {
      grade = "diamond";
    } else if (centerBase.getType() == Material.NETHERITE_BLOCK) {
      grade = "netherite";
    }
    
    // ========== ATM を登録 ==========
    AtmData newAtm = new AtmData();
    newAtm.id = UUID.randomUUID().toString();
    newAtm.location = signLoc;  // 看板位置
    newAtm.sign_location = signLoc;
    newAtm.owner_uuid = ownerUuid;
    newAtm.owner_name = ownerName;
    newAtm.grade = grade;
    newAtm.block_type = centerBase.getType().toString();
    newAtm.created_at = Instant.now();
    newAtm.status = "active";
    newAtm.occupied = false;
    
    storageManager.registerAtm(newAtm);
    
    // ========== 看板の Line 3, 4 を自動生成 ==========
    double makerFee = GRADE_CONFIG[grade].maker_fee_pct * 100;
    double takerFee = GRADE_CONFIG[grade].taker_fee_pct * 100;
    
    event.setLine(2, String.format("Maker: %.3f%%", makerFee));
    event.setLine(3, String.format("Taker: %.3f%%", takerFee));
    
    player.sendMessage(
      String.format(
        "§a[FX] ATM created! Grade: %s | Owner: %s",
        grade.toUpperCase(),
        ownerName
      )
    );
    
    storageManager.markDirty();
  });
}

// solid ブロック判定
private boolean isSolidBlock(Block block) {
  Material type = block.getType();
  
  // 空気、植物、水など = solid ではない
  if (type == Material.AIR || type.isTransparent() || 
      type.isOccluding() == false) {
    return false;
  }
  
  // 液体 = solid ではない
  if (type == Material.WATER || type == Material.LAVA) {
    return false;
  }
  
  return true;
}
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

### 6.0 ATMセッションの基本ルール（明示）

- ATMセッション開始条件: プレイヤーが `[FX]` 看板を右クリックして OTP を発行した時点で開始
- 取引（order）は従来どおりブラウザから実行可能
- ただし ATM セッション中の約定は、クリックした ATM のグレード設定に従って手数料分配（treasury / owner）を実行
- ATMセッションが無効な場合の手数料は `none` グレード相当（従来どおり treasury 100%）
- 入金・出金は ATM セッション有効時のみ許可（詳細は 7.0）

### 6.1 ATM 使用中・占有状態の定義

```java
public class AtmData {
  // ... 既存フィールド ...
  
  // 使用中状態管理
  private boolean occupied = false;
  private UUID occupiedBy = null;           // 誰が使用中か
  private long occupiedSince = 0;           // 使用開始時刻
  private static final long OCCUPY_TIMEOUT_MS = 600_000;  // 10分でタイムアウト
}
```

### 6.2 看板右クリック処理（ATM アクセス）

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
  
  Player player = event.getPlayer();
  
  // ATM 情報を取得
  AtmData atm = storageManager.getAtmBySignLocation(block.getLocation());
  if (atm == null || !atm.status.equals("active")) {
    player.sendMessage(
      "§c[FX] This ATM is not active."
    );
    return;
  }
  
  // ========== 距離チェック ==========
  double distance = player.getLocation().distance(atm.getBlockLocation());
  
  if (distance > 3.0) {
    player.sendMessage(
      String.format("§c[FX] You must be within 3 meters. (Current: %.1f m)", distance)
    );
    return;
  }
  
  // ========== 占有状態チェック ==========
  storageManager.acquireLock(() -> {
    
    // Timeout チェック（10分以上占有 → 強制解放）
    if (atm.occupied && (System.currentTimeMillis() - atm.occupiedSince) > OCCUPY_TIMEOUT_MS) {
      atm.occupied = false;
      atm.occupiedBy = null;
      updateAtmSignStatus(atm, false);  // 看板から "occupied" 削除
      logAtmEvent("OCCUPY_TIMEOUT", atm.id, atm.occupiedBy);
    }
    
    // 既に使用中か確認
    if (atm.occupied && !atm.occupiedBy.equals(player.getUniqueId())) {
      player.sendMessage(
        String.format("§c[FX] This ATM is currently occupied by another player.")
      );
      return;
    }
    
    // ========== ATM を占有状態に ==========
    atm.occupied = true;
    atm.occupiedBy = player.getUniqueId();
    atm.occupiedSince = System.currentTimeMillis();
    updateAtmSignStatus(atm, true);  // 看板に "occupied" 表示
    
    storageManager.markDirty();
  });
  
  // ========== OTP 生成＆取引ページ表示 ==========
  String otp = otpManager.generateOtp(player.getUniqueId());
  
  // Welcome メッセージ
  player.sendMessage(
    String.format("§a[FX] Welcome to %s's ATM (Grade: %s)!",
      atm.owner_name,
      atm.grade.toUpperCase()
    )
  );
  
  player.sendMessage(
    String.format("§7Fee: Maker %.3f%% | Taker %.3f%%",
      GRADE_CONFIG[atm.grade].maker_fee_pct * 100,
      GRADE_CONFIG[atm.grade].taker_fee_pct * 100
    )
  );
  
  // 取引ページへのリンク（同じく /fx login のようにOTP表示）
  player.sendMessage(
    String.format("§e[TRADE] http://your-server-ip:8080/trade?otp=%s", otp)
  );
  
  // 入金・出金ページ
  player.sendMessage(
    String.format("§6[DEPOSIT] http://your-server-ip:8080/deposit?otp=%s", otp)
  );
  
  player.sendMessage(
    String.format("§6[WITHDRAW] http://your-server-ip:8080/withdraw?otp=%s", otp)
  );
  
  // clickable text component (Paper API)
  player.spigot().sendMessage(
    createClickableLink(
      "§e[CLICK] Start Trading",
      String.format("http://your-server-ip:8080/trade?otp=%s", otp),
      "Click to open FX trading page"
    )
  );
}

// 看板ステータス表示の更新
private void updateAtmSignStatus(AtmData atm, boolean occupied) {
  Sign sign = (Sign) atm.sign_location.getBlock().getState();
  
  if (occupied) {
    // 看板の上 1行に "occupied" を追加表示
    // 既存の Line 0-3 を保持して、パーティクル or ボスバーで表示する方法もある
    // ここでは簡単に、上部に Armor Stand で表示
    createOccupiedMarker(atm.sign_location);
  } else {
    // "occupied" マーカー削除
    removeOccupiedMarker(atm.sign_location);
  }
}

private void createOccupiedMarker(Location signLoc) {
  Location markerLoc = signLoc.clone().add(0, 1, 0);
  ArmorStand stand = (ArmorStand) signLoc.getWorld().spawnEntity(markerLoc, EntityType.ARMOR_STAND);
  stand.setCustomName("§c[OCCUPIED]");
  stand.setCustomNameVisible(true);
  stand.setGravity(false);
  stand.setInvisible(true);
  stand.setCanPickupItems(false);
  
  // Marker にタグをつけて後で削除可能に
  stand.addScoreboardTag("atm-occupied-marker");
  stand.addScoreboardTag("atm-id:" + atmId);
}

private void removeOccupiedMarker(Location signLoc) {
  for (Entity entity : signLoc.getWorld().getNearbyEntities(
    signLoc, 1, 2, 1, e -> e instanceof ArmorStand && 
    ((ArmorStand) e).hasScoreboardTag("atm-occupied-marker")
  )) {
    entity.remove();
  }
}

// 3ブロック離脱時に ATM 利用を終了する
@EventHandler
public void onPlayerMove(PlayerMoveEvent event) {
  Player player = event.getPlayer();

  storageManager.acquireLock(() -> {
    AtmData atm = storageManager.getOccupiedAtmByPlayer(player.getUniqueId());
    if (atm == null) {
      return;
    }

    double distance = player.getLocation().distance(atm.getBlockLocation());
    if (distance <= 3.0) {
      return;
    }

    // 3ブロック超過 = 利用終了（非利用状態へ戻す）
    atm.occupied = false;
    atm.occupiedBy = null;
    atm.occupiedSince = 0;
    updateAtmSignStatus(atm, false);

    // OTP と ATM セッション紐付けを破棄
    otpManager.revokeAtmSession(player.getUniqueId(), atm.id);

    player.sendMessage("§e[FX] You moved away from the ATM (>3m). ATM session ended.");
    storageManager.markDirty();
  });
}
```

---

## 7. API: ATM 情報取得・管理

### 7.0 ATM 範囲内での操作制限

### 7.0.0 動作方針（明示）

- `POST /api/order` は従来どおり利用可能（通常ログイン OTP でも実行可）
- ただし ATM OTP での注文時は、紐づく ATM グレードで手数料率・分配を適用
- ATMセッションが 3ブロック超過で失効した後の注文は、通常手数料（`none` 相当）で処理
- `POST /api/deposit` / `POST /api/withdraw` は ATM OTP かつ ATM 3ブロック以内のみ許可
- これにより「入出金はブラウザから可能」だが「ATMに行かなければ実行不可」を保証

**ATMの「アクティブ範囲」**:

ATMを右クリックしてOTPを取得してから、以下の操作はATMから **3メートル以内** に いなければならない：

- `POST /api/deposit` （入金）
- `POST /api/withdraw` （出金）

`POST /api/order` は通常利用も許可するが、ATM起点の手数料分配を有効にする場合は ATM OTP + 3m 以内を必須とする。

**実装**（API ミドルウェア）:

```java
public class AtmRangeMiddleware {
  
  // リクエストコンテキストに OTP → 対応する ATM を紐付ける
  private static final ThreadLocal<AtmData> currentAtm = new ThreadLocal<>();
  
  public static void validateAtmProximity(
      String otpToken,
      Player player,
      String operation  // "trade", "deposit", "withdraw"
  ) throws AtmProximityException {
    
    // OTP から ATM を特定
    AtmData atm = otpManager.getAtmByOtp(otpToken);
    if (atm == null) {
      // 入出金は ATM 経由 OTP 必須
      if ("deposit".equals(operation) || "withdraw".equals(operation)) {
        throw new AtmProximityException(
          "Deposit/Withdraw requires ATM session. Right-click [FX] sign first."
        );
      }

      // trade は従来どおり許可（ATM手数料分配は適用しない）
      currentAtm.remove();
      return;
    }
    
    // プレイヤーの現在位置を確認
    double distance = player.getLocation().distance(atm.getBlockLocation());
    
    if (distance > 3.0) {
      throw new AtmProximityException(
        String.format(
          "You must be within 3 meters of the ATM to perform '%s'. Current distance: %.1f m",
          operation, distance
        )
      );
    }
    
    // コンテキストに ATM を保存（手数料計算時に参照）
    currentAtm.set(atm);
  }
  
  public static AtmData getCurrentAtm() {
    return currentAtm.get();
  }
  
  public static void clear() {
    currentAtm.remove();
  }
}

public class AtmProximityException extends Exception {
  public AtmProximityException(String message) {
    super(message);
  }
}
```

### 7.0.1 フロントエンド要件（入出金ロック）

- `/deposit` と `/withdraw` ページは、ATMセッション情報（`atmId`, `distanceOk`, `expiresAt`）を起動時に取得
- `distanceOk = false` または `atmId = null` の場合、フォームを disabled にして送信不可
- ロック時の表示文言:
  - `ATMの近くで右クリックしてから利用してください（3ブロック以内）`
  - `最寄りATMへ移動してください`
- CTA ボタン: `ATMへ行く`（説明モーダル or ガイド表示）
- API が 403 (`AtmProximityException`) を返した場合も同様にロック状態へ遷移
- `/trade` はロックしない（通常取引可能）。ただし ATMセッション有効時のみ ATM手数料表示バッジを出す

**POST `/api/order`** に ATM 範囲チェックを追加:

```java
@Post("/api/order")
public void placeOrder(Context ctx) {
  
  String otpToken = extractBearerToken(ctx);
  Player player = getPlayerByToken(otpToken);
  
  // ↓ 新規: ATM 範囲検証
  try {
    AtmRangeMiddleware.validateAtmProximity(
      otpToken, 
      player, 
      "trade"
    );
  } catch (AtmProximityException e) {
    ctx.status(403).json(new ErrorResponse(e.getMessage()));
    return;
  }
  
  // 既存の order 処理...
  OrderRequest req = ctx.bodyAsClass(OrderRequest.class);
  
  // ↓ マッチング実行時にATM情報を自動参照
  Execution execution = matchingEngine.execute(req);
  
  ctx.json(execution);
  
  // クリーンアップ
  AtmRangeMiddleware.clear();
}
```

**POST `/api/deposit`** に ATM 範囲チェックを追加:

```java
@Post("/api/deposit")
public void deposit(Context ctx) {
  String otpToken = extractBearerToken(ctx);
  Player player = getPlayerByToken(otpToken);
  
  try {
    AtmRangeMiddleware.validateAtmProximity(
      otpToken,
      player,
      "deposit"
    );
  } catch (AtmProximityException e) {
    ctx.status(403).json(new ErrorResponse(e.getMessage()));
    return;
  }
  
  // 既存の deposit 処理...
  DepositRequest req = ctx.bodyAsClass(DepositRequest.class);
  
  // Bukkit インベントリ操作
  Bukkit.getScheduler().runTask(plugin, () -> {
    try {
      player.getInventory().removeItem(
        new ItemStack(Material.matchMaterial(req.itemId), req.amount)
      );
      playerData.hot_storage[req.itemId] += req.amount;
      storageManager.markDirty();
    } catch (Exception e) {
      logger.error("Deposit failed", e);
    }
  });
  
  ctx.json(new DepositResponse("success", req.amount));
  AtmRangeMiddleware.clear();
}
```

**POST `/api/withdraw`** に ATM 範囲チェックを追加:

```java
@Post("/api/withdraw")
public void withdraw(Context ctx) {
  String otpToken = extractBearerToken(ctx);
  Player player = getPlayerByToken(otpToken);
  
  try {
    AtmRangeMiddleware.validateAtmProximity(
      otpToken,
      player,
      "withdraw"
    );
  } catch (AtmProximityException e) {
    ctx.status(403).json(new ErrorResponse(e.getMessage()));
    return;
  }
  
  // 既存の withdraw 処理...
  WithdrawRequest req = ctx.bodyAsClass(WithdrawRequest.class);
  
  storageManager.acquireLock(() -> {
    PlayerData playerData = storageManager.getPlayerData(player.getUniqueId());
    
    if (playerData.hot_storage[req.itemId] < req.amount) {
      ctx.status(400).json(new ErrorResponse("Insufficient balance"));
      return;
    }
    
    playerData.hot_storage[req.itemId] -= req.amount;
    playerData.pending_withdraw[req.itemId] += req.amount;
    
    storageManager.markDirty();
  });
  
  ctx.json(new WithdrawResponse("success", req.amount));
  AtmRangeMiddleware.clear();
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
| ATM利用中 | プレイヤーが3ブロック超過 | ✅ ATM利用終了（occupied解除、ATMセッション無効化） |
| マッチング | 不正な ATM ID | ⚠️ ログ記録、treasury のみに支払い |
| 入金/出金 API | ATMセッションなし | ❌ 403（ATM右クリックを要求） |
| 入金/出金 API | ATMから3ブロック超過 | ❌ 403（ATM範囲外） |
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
- [ ] `StorageData` に ATM Registry 永続フィールド追加（storage.json 読み書き）
- [ ] `PluginConfig` に `atm.*` 設定のロード・バリデーション追加
- [ ] `SignChangeEvent` リスナー実装
- [ ] `BlockBreakEvent` リスナー実装
- [ ] `PlayerInteractEvent` リスナー実装
- [ ] `PlayerMoveEvent` リスナー実装（3ブロック離脱で利用終了）
- [ ] プラグイン起動時に ATM 関連 Listener / Scheduler を登録
- [ ] OTP/Session と ATM セッションの紐付け管理追加（生成・失効・強制解除）
- [ ] `MatchingEngine.match()` に手数料分配ロジック追加
- [ ] H2 DB に ATM カラム追加
- [ ] H2 Repository の insert/select を ATM カラム対応へ更新
- [ ] `/api/atms` エンドポイント実装
- [ ] `/api/my-atms` エンドポイント実装
- [ ] `/api/admin/atms` エンドポイント実装
- [ ] `/api/auth` 応答に ATM セッション情報（任意）を返却
- [ ] `/api/atm-session` などフロント用セッション状態APIを追加
- [ ] 入出金APIで ATM セッション必須化（403制御）
- [ ] フロントエンド入出金UI（現行 `TradePage` 内 `DepositPanel`）のATM誘導ロック実装
- [ ] `PayoutScheduler` 実装
- [ ] `AtmLogger` 実装
- [ ] セッション失効・ログアウト時の ATM occupied 解放処理
- [ ] 初期テスト（単一 ATM）
- [ ] 複数 ATM 同時実行テスト
- [ ] ATM 破壊時の支払いテスト
- [ ] 入出金403時のフロント表示テスト（ATM誘導表示）
