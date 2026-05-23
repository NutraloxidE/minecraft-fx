# ATM ブロック配置検証仕様

看板を設置する際のブロック配置検証ロジックの詳細です。

---

## 1. ブロック配置パターン（ATMの構造）

### 1.1 3段階のピラミッド構造

看板を設置する際、その周辺ブロックに特定のパターンが必要です。

看板位置を基準に：

```
    Y座標
     +2 (上から3段目)
     +1 (上から2段目)
      0 (看板レベル = 1段目)
     
1段目（Y = sign_y）: 3×3の正方形ブロック必須
  ┌─┬─┬─┐
  │■│■│■│  X軸
  ├─┼─┼─┤
  │■│■│■│
  ├─┼─┼─┤
  │■│■│■│
  └─┴─┴─┘
  Z軸

2段目（Y = sign_y+1）: 中央ブロックのみ必須
  ┌─┬─┬─┐
  │□│□│□│
  ├─┼─┼─┤
  │□│■│□│
  ├─┼─┼─┤
  │□│□│□│
  └─┴─┴─┘

3段目（Y = sign_y+2）: 中央ブロックのみ必須
  ┌─┬─┬─┐
  │□│□│□│
  ├─┼─┼─┤
  │□│■│□│
  ├─┼─┼─┤
  │□│▽│□│  ▽ = 看板位置
  └─┴─┴─┘
```

### 1.2 座標計算式

看板を右クリックして設置する時の看板位置を `(sign_x, sign_y, sign_z)` とした場合：

**1段目チェック**（Y = sign_y）:
```
(sign_x - 1, sign_y, sign_z - 1) ~ (sign_x + 1, sign_y, sign_z + 1)
計9ブロック全て必須
```

**2段目チェック**（Y = sign_y + 1）:
```
(sign_x, sign_y + 1, sign_z)
中央ブロックのみ必須
```

**3段目チェック**（Y = sign_y + 2）:
```
(sign_x, sign_y + 2, sign_z)
中央ブロックのみ必須
```

---

## 2. ブロック種別判定（グレード決定）

### 2.1 グレード判定の対象ブロック

**1段目の中央ブロック** `(sign_x, sign_y, sign_z)` の種別でATMグレードを判定：

| ブロック種別 | グレード | Maker手数料 | Taker手数料 | Treasury% |
|-------------|---------|-----------|-----------|----------|
| Iron Block | `iron` | 0.08% | 0.12% | 65% |
| Diamond Block | `diamond` | 0.05% | 0.08% | 25% |
| Netherite Block | `netherite` | 0.03% | 0.05% | 10% |
| その他 | `none` | 0.10% | 0.16% | 100% |

### 2.2 グレード判定コード

```java
private String determineGrade(Block centerBlock) {
  Material type = centerBlock.getType();
  
  switch (type) {
    case IRON_BLOCK:
      return "iron";
    case DIAMOND_BLOCK:
      return "diamond";
    case NETHERITE_BLOCK:
      return "netherite";
    default:
      return "none";
  }
}
```

---

## 3. 看板設置時の検証ロジック（完全版）

### 3.1 SignChangeEvent ハンドラ

```java
@EventHandler
public void onSignChange(SignChangeEvent event) {
  
  Player player = event.getPlayer();
  Block signBlock = event.getBlock();
  
  // ========== Line 1 チェック ==========
  String line1 = event.getLine(0);
  if (line1 == null || !line1.equals("[FX]")) {
    return;  // ATM ではない看板、処理スキップ
  }
  
  // ========== ブロック配置検証開始 ==========
  
  storageManager.acquireLock(() -> {
    
    Location signLoc = signBlock.getLocation();
    int sx = signLoc.getBlockX();
    int sy = signLoc.getBlockY();
    int sz = signLoc.getBlockZ();
    World world = signLoc.getWorld();
    
    // ─────────────────────────────
    // 1段目: 3×3 全ブロック検証
    // ─────────────────────────────
    
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        Block b = world.getBlockAt(sx + dx, sy, sz + dz);
        
        if (!isSolidBlock(b)) {
          player.sendMessage(
            String.format(
              "§c[FX] ATM requires 3×3 solid blocks at Y level." +
              " Missing at: X%+d Z%+d",
              dx, dz
            )
          );
          event.setCancelled(true);
          return;
        }
      }
    }
    
    // ─────────────────────────────
    // 2段目: 中央ブロック検証
    // ─────────────────────────────
    
    Block b2 = world.getBlockAt(sx, sy + 1, sz);
    if (!isSolidBlock(b2)) {
      player.sendMessage(
        "§c[FX] ATM requires solid block at Y+1 (center)"
      );
      event.setCancelled(true);
      return;
    }
    
    // ─────────────────────────────
    // 3段目: 中央ブロック検証
    // ─────────────────────────────
    
    Block b3 = world.getBlockAt(sx, sy + 2, sz);
    if (!isSolidBlock(b3)) {
      player.sendMessage(
        "§c[FX] ATM requires solid block at Y+2 (center)"
      );
      event.setCancelled(true);
      return;
    }
    
    // ─────────────────────────────
    // Line 2: オーナー名チェック
    // ─────────────────────────────
    
    String line2 = event.getLine(1);
    
    if (line2 == null || line2.trim().isEmpty()) {
      player.sendMessage(
        "§c[FX] Line 2 must contain owner name (player name or svc:account)"
      );
      event.setCancelled(true);
      return;
    }
    
    String ownerName = line2.trim();
    PlayerData ownerData = null;
    UUID ownerUuid = null;
    
    // オーナー情報を検索
    if (ownerName.startsWith("svc:")) {
      // サービスアカウント名で検索
      ownerData = storageManager.getPlayerDataByName(ownerName);
      ownerUuid = UUID.nameUUIDFromBytes(ownerName.getBytes());
    } else {
      // プレイヤー名で検索
      ownerUuid = Bukkit.getPlayerUniqueId(ownerName);
      if (ownerUuid != null) {
        ownerData = storageManager.getPlayerData(ownerUuid);
      }
    }
    
    if (ownerData == null) {
      player.sendMessage(
        String.format(
          "§c[FX] Owner not found: %s" +
          " (make sure player is online or account exists)",
          ownerName
        )
      );
      event.setCancelled(true);
      return;
    }
    
    // ─────────────────────────────
    // ATM数制限チェック
    // ─────────────────────────────
    
    int currentAtmCount = storageManager.getAtmRegistry()
      .getAtmsByOwner(ownerUuid)
      .size();
    
    int maxAtms = config.getInt("atm.max_atms_per_player", 5);
    
    if (currentAtmCount >= maxAtms) {
      player.sendMessage(
        String.format(
          "§c[FX] %s has reached max ATMs (%d/%d)",
          ownerName, currentAtmCount, maxAtms
        )
      );
      event.setCancelled(true);
      return;
    }
    
    // ─────────────────────────────
    // グレード判定
    // ─────────────────────────────
    
    Block centerBlock = world.getBlockAt(sx, sy, sz);
    String grade = determineGrade(centerBlock);
    String blockType = centerBlock.getType().toString();
    
    // ─────────────────────────────
    // ATM 登録
    // ─────────────────────────────
    
    AtmData newAtm = new AtmData();
    newAtm.id = UUID.randomUUID().toString();
    newAtm.location = signLoc;
    newAtm.sign_location = signLoc;
    newAtm.owner_uuid = ownerUuid;
    newAtm.owner_name = ownerName;
    newAtm.grade = grade;
    newAtm.block_type = blockType;
    newAtm.created_at = Instant.now();
    newAtm.status = "active";
    newAtm.occupied = false;
    newAtm.total_fees_earned = new HashMap<>();
    newAtm.pending_payout = new HashMap<>();
    
    storageManager.registerAtm(newAtm);
    
    // ─────────────────────────────
    // 看板の Line 3, 4 を自動生成
    // ─────────────────────────────
    
    Map<String, Object> gradeConfig = 
      config.getConfigurationSection("atm.grades." + grade);
    
    double makerFee = ((Number) gradeConfig.get("maker_fee_pct"))
      .doubleValue() * 100;
    double takerFee = ((Number) gradeConfig.get("taker_fee_pct"))
      .doubleValue() * 100;
    
    event.setLine(2, String.format("Maker: %.3f%%", makerFee));
    event.setLine(3, String.format("Taker: %.3f%%", takerFee));
    
    // ─────────────────────────────
    // 完成メッセージ＆ログ
    // ─────────────────────────────
    
    player.sendMessage(
      String.format(
        "§a[FX] ATM created!§r Grade: §6%s§r | Owner: §b%s",
        grade.toUpperCase(),
        ownerName
      )
    );
    
    player.sendMessage(
      String.format(
        "§7Maker: %.3f%% | Taker: %.3f%% | Treasury: %d%%",
        makerFee,
        takerFee,
        (int) (((Number) gradeConfig.get("treasury_share"))
          .doubleValue() * 100)
      )
    );
    
    logger.info(
      String.format(
        "[ATM] New ATM created: ID=%s, Owner=%s, Grade=%s, Location=%d,%d,%d",
        newAtm.id, ownerName, grade, sx, sy, sz
      )
    );
    
    storageManager.markDirty();
  });
}
```

### 3.2 isSolidBlock() 判定関数

```java
private boolean isSolidBlock(Block block) {
  Material type = block.getType();
  
  // 明示的に「solid ではない」ブロック
  if (type == Material.AIR ||
      type == Material.WATER ||
      type == Material.LAVA ||
      type == Material.CAVE_AIR ||
      type == Material.VOID_AIR) {
    return false;
  }
  
  // Bukkit API の透明判定を使う
  if (type.isTransparent() || !type.isOccluding()) {
    return false;
  }
  
  // 植物系（草、花など）
  if (type.toString().contains("PLANT") ||
      type.toString().contains("FLOWER") ||
      type.toString().contains("CROP") ||
      type == Material.TALL_GRASS ||
      type == Material.GRASS) {
    return false;
  }
  
  return true;
}
```

---

## 4. エラーメッセージリファレンス

| エラー内容 | メッセージ |
|-----------|----------|
| 1段目の3×3が不足 | `§c[FX] ATM requires 3×3 solid blocks at Y level. Missing at: X... Z...` |
| 2段目の中央ブロックなし | `§c[FX] ATM requires solid block at Y+1 (center)` |
| 3段目の中央ブロックなし | `§c[FX] ATM requires solid block at Y+2 (center)` |
| オーナー名が空 | `§c[FX] Line 2 must contain owner name (player name or svc:account)` |
| オーナー存在しない | `§c[FX] Owner not found: {name} (make sure player is online or account exists)` |
| ATM数上限 | `§c[FX] {owner} has reached max ATMs ({current}/{max})` |

---

## 5. チェックリスト（ブロック検証）

- [ ] `isSolidBlock()` 関数実装
- [ ] `determineGrade()` 関数実装
- [ ] `SignChangeEvent` ハンドラの1段目検証ロジック実装
- [ ] `SignChangeEvent` ハンドラの2段目検証ロジック実装
- [ ] `SignChangeEvent` ハンドラの3段目検証ロジック実装
- [ ] `SignChangeEvent` ハンドラのオーナー検証ロジック実装
- [ ] `SignChangeEvent` ハンドラのATM数制限チェック実装
- [ ] 看板の Line 3, 4 自動生成実装
- [ ] ログ出力実装
- [ ] 各エラーメッセージの日本語テキスト調整
