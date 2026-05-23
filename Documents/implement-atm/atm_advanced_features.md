# ATM 追加機能仕様：占有・OTP・範囲制限

前のATM仕様に加えて、以下の詳細機能を追加します。

---

## 1. ATM 占有状態管理（Occupied Flag）

### 1.1 データ構造拡張

```java
public class AtmData {
  // ... 既存フィールド ...
  
  // 占有状態管理
  private boolean occupied = false;
  private UUID occupiedBy = null;              // 使用中のプレイヤーUUID
  private long occupiedSince = 0;              // 使用開始UNIXミリ秒
  private static final long OCCUPY_TIMEOUT_MS = 600_000;  // 10分でタイムアウト自動解放
}
```

### 1.2 占有状態の遷移

```
[未使用]
   ↓
[看板右クリック] → [占有中] (occupied = true, occupiedBy = player UUID)
   ↓
[10分経過 OR ブラウザを閉じた] → [未使用に戻す]
```

### 1.3 タイムアウト自動解放

```java
// 看板右クリック時にタイムアウトチェック
if (atm.occupied && (System.currentTimeMillis() - atm.occupiedSince) > OCCUPY_TIMEOUT_MS) {
  atm.occupied = false;
  atm.occupiedBy = null;
  updateAtmSignStatus(atm, false);  // 看板から "occupied" マーカー削除
  logAtmEvent("OCCUPY_TIMEOUT", atm.id, null);
}
```

---

## 2. 看板の "occupied" 表示（ArmorStand マーカー）

### 2.1 実装方法

占有状態を示すため、看板の上にArmorStandを出現させます。

```java
private void updateAtmSignStatus(AtmData atm, boolean occupied) {
  if (occupied) {
    createOccupiedMarker(atm.sign_location, atm.id);
  } else {
    removeOccupiedMarker(atm.sign_location, atm.id);
  }
}

private void createOccupiedMarker(Location signLoc, String atmId) {
  // 看板の上1ブロック分上に ArmorStand を出現させる
  Location markerLoc = signLoc.clone().add(0, 1.5, 0);  // Y+1.5で見える位置
  
  ArmorStand stand = (ArmorStand) signLoc.getWorld()
    .spawnEntity(markerLoc, EntityType.ARMOR_STAND);
  
  stand.setCustomName("§c[OCCUPIED]");      // 赤色で「占有中」表示
  stand.setCustomNameVisible(true);
  stand.setGravity(false);
  stand.setInvisible(true);                  // アーマースタンド本体は見えない
  stand.setCanPickupItems(false);
  stand.setMarker(true);                     // Marker モード（当たり判定なし）
  
  // 後で削除できるようにタグをつける
  stand.addScoreboardTag("atm-occupied-marker");
  stand.addScoreboardTag("atm-id:" + atmId);
}

private void removeOccupiedMarker(Location signLoc, String atmId) {
  // 看板周辺の ArmorStand を全削除
  for (Entity entity : signLoc.getWorld().getNearbyEntities(
    signLoc, 2, 3, 2,
    e -> e instanceof ArmorStand &&
         ((ArmorStand) e).hasScoreboardTag("atm-occupied-marker") &&
         ((ArmorStand) e).hasScoreboardTag("atm-id:" + atmId)
  )) {
    entity.remove();
  }
}
```

### 2.2 見た目

```
     [§c[OCCUPIED]]  ← ArmorStand のカスタムネーム
           ▲
        看板上部
     ┌─────────┐
     │   [FX]  │
     │Alice ATM│
     │Maker... │
     │Taker... │
     └─────────┘
```

---

## 3. OTP 管理の拡張（ATM紐付け）

### 3.1 OTPデータ構造

```java
public class OtpManager {
  
  public static class OtpData {
    public String token;                  // 16文字ランダムトークン
    public UUID playerUuid;               // OTP所有者
    public String atmId;                  // null = ATM経由ではない、有値 = 該当ATM経由
    public long createdAt;                // 生成時刻（UNIX ms）
    public boolean consumed;              // 1回消費で true
    public long expiresAt;                // 有効期限（UNIX ms）
  }
  
  private static final ConcurrentHashMap<String, OtpData> otpRegistry = 
    new ConcurrentHashMap<>();
}
```

### 3.2 OTP生成（2つの方法）

**方法1: /fx login コマンド（ATM経由ではない）**

```java
public String generateOtp(UUID playerUuid) {
  String token = generateRandomToken(16);
  OtpData data = new OtpData();
  data.token = token;
  data.playerUuid = playerUuid;
  data.atmId = null;                   // ← ATM経由ではない
  data.createdAt = System.currentTimeMillis();
  data.consumed = false;
  data.expiresAt = System.currentTimeMillis() + 600_000;  // 10分
  
  otpRegistry.put(token, data);
  
  return token;
}
```

**方法2: ATM看板右クリック（ATM経由）**

```java
public String generateOtpForAtm(UUID playerUuid, AtmData atm) {
  String token = generateRandomToken(16);
  OtpData data = new OtpData();
  data.token = token;
  data.playerUuid = playerUuid;
  data.atmId = atm.id;                 // ← ATM情報を記録！
  data.createdAt = System.currentTimeMillis();
  data.consumed = false;
  data.expiresAt = System.currentTimeMillis() + 600_000;  // 10分
  
  otpRegistry.put(token, data);
  
  return token;
}
```

### 3.3 OTP消費時のATM記録

```java
public OtpData consumeOtp(String token) {
  OtpData data = otpRegistry.get(token);
  
  if (data == null || data.consumed) {
    return null;  // 無効なOTP
  }
  
  // 有効期限チェック
  if (System.currentTimeMillis() > data.expiresAt) {
    return null;  // 期限切れ
  }
  
  data.consumed = true;
  return data;
}

// OTPからATMを特定
public AtmData getAtmByOtp(String token) {
  OtpData data = otpRegistry.get(token);
  if (data == null || data.atmId == null) {
    return null;  // ATM経由ではないOTP
  }
  return storageManager.getAtmRegistry().getAtmById(data.atmId);
}
```

---

## 4. ATM 範囲内操作の制限（3メートル以内）

### 4.1 範囲チェック API ミドルウェア

```java
public class AtmProximityValidator {
  
  public static class AtmProximityException extends Exception {
    public AtmProximityException(String message) {
      super(message);
    }
  }
  
  // リクエストコンテキストにATM情報を格納
  private static final ThreadLocal<AtmData> currentAtm = new ThreadLocal<>();
  
  public static void validateAtmProximity(
      String otpToken,
      Player player,
      String operation  // "trade", "deposit", "withdraw"
  ) throws AtmProximityException {
    
    // OTP から ATM を特定
    AtmData atm = otpManager.getAtmByOtp(otpToken);
    if (atm == null) {
      // ATM経由でないOTP → 範囲制限なし
      currentAtm.set(null);
      return;
    }
    
    // プレイヤーの現在位置とATMブロックの距離を測定
    double distance = player.getLocation()
      .distance(atm.getBlockLocation());
    
    if (distance > 3.0) {
      throw new AtmProximityException(
        String.format(
          "§c[FX] You must be within 3 meters of the ATM to %s. " +
          "Current distance: %.1f m",
          operation, distance
        )
      );
    }
    
    // コンテキストにATM情報を保存（手数料計算時に参照）
    currentAtm.set(atm);
  }
  
  public static AtmData getCurrentAtm() {
    return currentAtm.get();
  }
  
  public static void clearContext() {
    currentAtm.remove();
  }
}
```

### 4.2 API エンドポイントへの統合

**POST `/api/order` に範囲チェック追加**:

```java
@Post("/api/order")
public void placeOrder(Context ctx) {
  
  String bearerToken = extractBearerToken(ctx);
  OtpData otpData = otpManager.consumeOtp(bearerToken);
  
  if (otpData == null) {
    ctx.status(401).json(new ErrorResponse("Invalid or expired OTP"));
    return;
  }
  
  Player player = getPlayerByUuid(otpData.playerUuid);
  
  // ★ 新規: ATM範囲検証
  try {
    AtmProximityValidator.validateAtmProximity(
      bearerToken,
      player,
      "trade"
    );
  } catch (AtmProximityException e) {
    ctx.status(403).json(new ErrorResponse(e.getMessage()));
    return;
  }
  
  // 既存の注文処理...
  OrderRequest req = ctx.bodyAsClass(OrderRequest.class);
  
  storageManager.acquireLock(() -> {
    // マッチング実行時に、コンテキストから ATM を参照
    AtmData atmContext = AtmProximityValidator.getCurrentAtm();
    Execution execution = matchingEngine.execute(req, atmContext);
    
    ctx.json(execution);
  });
  
  // クリーンアップ
  AtmProximityValidator.clearContext();
}
```

**POST `/api/deposit` に範囲チェック追加**:

```java
@Post("/api/deposit")
public void deposit(Context ctx) {
  
  String bearerToken = extractBearerToken(ctx);
  OtpData otpData = otpManager.consumeOtp(bearerToken);
  
  if (otpData == null) {
    ctx.status(401).json(new ErrorResponse("Invalid or expired OTP"));
    return;
  }
  
  Player player = getPlayerByUuid(otpData.playerUuid);
  
  // ★ 新規: ATM範囲検証
  try {
    AtmProximityValidator.validateAtmProximity(
      bearerToken,
      player,
      "deposit"
    );
  } catch (AtmProximityException e) {
    ctx.status(403).json(new ErrorResponse(e.getMessage()));
    return;
  }
  
  // 既存の入金処理...
  DepositRequest req = ctx.bodyAsClass(DepositRequest.class);
  
  PlayerData playerData = storageManager.getPlayerData(otpData.playerUuid);
  
  // Bukkit メインスレッドでインベントリ操作
  Bukkit.getScheduler().runTask(plugin, () -> {
    try {
      ItemStack removeItem = new ItemStack(
        Material.matchMaterial(req.itemId),
        req.amount
      );
      
      int removed = removeItem.getAmount();
      for (ItemStack item : player.getInventory().removeItem(removeItem).values()) {
        removed -= item.getAmount();
      }
      
      if (removed > 0) {
        storageManager.acquireLock(() -> {
          playerData.hot_storage.put(
            req.itemId,
            playerData.hot_storage.getOrDefault(req.itemId, 0.0) + removed
          );
          storageManager.markDirty();
        });
        
        ctx.json(new DepositResponse("success", removed));
      } else {
        ctx.status(400).json(new ErrorResponse("Item not found in inventory"));
      }
    } catch (Exception e) {
      ctx.status(500).json(new ErrorResponse(e.getMessage()));
      logger.error("Deposit failed", e);
    } finally {
      AtmProximityValidator.clearContext();
    }
  });
}
```

**POST `/api/withdraw` に範囲チェック追加**:

```java
@Post("/api/withdraw")
public void withdraw(Context ctx) {
  
  String bearerToken = extractBearerToken(ctx);
  OtpData otpData = otpManager.consumeOtp(bearerToken);
  
  if (otpData == null) {
    ctx.status(401).json(new ErrorResponse("Invalid or expired OTP"));
    return;
  }
  
  Player player = getPlayerByUuid(otpData.playerUuid);
  
  // ★ 新規: ATM範囲検証
  try {
    AtmProximityValidator.validateAtmProximity(
      bearerToken,
      player,
      "withdraw"
    );
  } catch (AtmProximityException e) {
    ctx.status(403).json(new ErrorResponse(e.getMessage()));
    return;
  }
  
  // 既存の出金処理...
  WithdrawRequest req = ctx.bodyAsClass(WithdrawRequest.class);
  
  storageManager.acquireLock(() -> {
    PlayerData playerData = storageManager.getPlayerData(otpData.playerUuid);
    
    double balance = playerData.hot_storage.getOrDefault(req.itemId, 0.0);
    
    if (balance < req.amount) {
      ctx.status(400).json(new ErrorResponse("Insufficient balance"));
      return;
    }
    
    // Hot storage から出金要求に移す
    playerData.hot_storage.put(req.itemId, balance - req.amount);
    playerData.pending_withdraw.put(
      req.itemId,
      playerData.pending_withdraw.getOrDefault(req.itemId, 0.0) + req.amount
    );
    
    storageManager.markDirty();
    
    // ブロック内での出金処理（PlayerJoinListener で処理）
    ctx.json(new WithdrawResponse("success", req.amount));
  });
  
  AtmProximityValidator.clearContext();
}
```

---

## 5. マッチングエンジンへの ATM 情報渡し

### 5.1 改造版 execute() メソッド

```java
public class MatchingEngine {
  
  public Execution execute(OrderRequest req, AtmData atmContext) {
    
    // 既存のマッチング処理...
    Order buyOrder = createOrder(req);
    Order sellOrder = findMatchingOrder(buyOrder);
    
    if (sellOrder == null) {
      // マッチなし
      return null;
    }
    
    // 約定を作成
    Execution exec = new Execution(
      pair.id,
      buyOrder.price,
      buyOrder.quantity,
      System.currentTimeMillis()
    );
    
    // ★ ATMコンテキスト情報を記録
    if (atmContext != null) {
      exec.atmId = atmContext.id;
      exec.atmGrade = atmContext.grade;
    }
    
    // 既存の手数料分配処理（atm_sign_trigger_spec.md 参照）...
    
    return exec;
  }
}
```

### 5.2 Execution テーブルへの記録

H2 DB の `executions` テーブルに以下を追加（既出）:

```sql
ALTER TABLE executions ADD COLUMN atm_id VARCHAR(36);
ALTER TABLE executions ADD COLUMN atm_grade VARCHAR(20);
```

---

## 6. ATM 占有状態の解放タイミング

### 6.1 手動解放

プレイヤーがブラウザを閉じる/OTPが期限切れになった場合、API側で以下を実行:

```java
// ブラウザクローズを検知（FXページの beforeunload など）
public void releaseAtmOccupancy(String otpToken) {
  OtpData otpData = otpRegistry.get(otpToken);
  if (otpData == null || otpData.atmId == null) {
    return;
  }
  
  storageManager.acquireLock(() -> {
    AtmData atm = storageManager.getAtmRegistry()
      .getAtmById(otpData.atmId);
    
    if (atm != null && atm.occupiedBy.equals(otpData.playerUuid)) {
      atm.occupied = false;
      atm.occupiedBy = null;
      updateAtmSignStatus(atm, false);
      logAtmEvent("OCCUPY_RELEASED", atm.id, otpData.playerUuid);
    }
    
    storageManager.markDirty();
  });
}
```

### 6.2 自動タイムアウト解放

看板右クリック時にチェック（既出）:

```java
if (atm.occupied && (System.currentTimeMillis() - atm.occupiedSince) > OCCUPY_TIMEOUT_MS) {
  atm.occupied = false;
  atm.occupiedBy = null;
  updateAtmSignStatus(atm, false);
}
```

---

## 7. チェックリスト（追加機能）

- [ ] `AtmData` に `occupied`, `occupiedBy`, `occupiedSince` フィールド追加
- [ ] `OtpData` に `atmId`, `expiresAt` フィールド追加
- [ ] `OtpManager.generateOtpForAtm()` 実装
- [ ] `OtpManager.getAtmByOtp()` 実装
- [ ] `AtmProximityValidator` クラス実装
- [ ] 看板右クリックハンドラに占有状態チェック追加
- [ ] ArmorStand マーカー作成・削除関数実装
- [ ] `POST /api/order` に範囲検証追加
- [ ] `POST /api/deposit` に範囲検証追加
- [ ] `POST /api/withdraw` に範囲検証追加
- [ ] `MatchingEngine.execute()` に ATMコンテキスト対応
- [ ] FX ページに OTP期限切れ時のATM解放機能実装
- [ ] 10分タイムアウト監視スケジューラ実装
- [ ] ログ記録（占有・解放・タイムアウト）
