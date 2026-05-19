package com.gekiyabafx.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 1人のプレイヤーの全データを保持するモデル。
 *
 * <p>{@code storage.json} の {@code players.<UUID>} オブジェクトに対応する。
 * プレイヤー UUID は {@code StorageData.players} マップのキーとして管理される。</p>
 *
 * <h3>残高フィールドの取り扱い規則</h3>
 * <ul>
 *   <li><b>存在しないキーへのアクセスは常に {@code "0.0000"} として扱う（オンデマンド初期化）。</b>
 *       呼び出し側は {@link #getHotBalance(String)}・{@link #getLockedBalance(String, String)} を使用すること。</li>
 *   <li>キーはデポジット等で初めて値が生じた時点で自動生成される。</li>
 * </ul>
 */
public final class PlayerData {

    /** プレイヤー名（最後にログインした時点の名前を保持）。 */
    private String name;

    /**
     * ホットストレージ残高。
     * キー: Minecraft Material 名（小文字スネークケース）、値: 残高（BigDecimal 文字列）。
     * 例: {@code {"diamond": "120.5025", "emerald": "4500.0000"}}
     * GSON シリアライズ時のフィールド名: {@code hot_storage}
     */
    private Map<String, BigDecimal> hotStorage;

    /**
     * ロック中の残高（注文中に拘束されているアイテム量）。
     * 外側キー: ペアID（例: {@code "DIAMOND/EMERALD"}）。
     * 内側キー: Minecraft Material 名。
     * 内側値: ロック量（BigDecimal 文字列）。
     * GSON シリアライズ時のフィールド名: {@code locked_balance}
     */
    private Map<String, Map<String, BigDecimal>> lockedBalance;

    /**
     * オフライン中の引き出し保留数（整数個）。
     * キー: Minecraft Material 名、値: 保留数量（整数）。
     * 次回ログイン時に {@code player.getInventory().addItem()} で付与される。
     * GSON シリアライズ時のフィールド名: {@code pending_withdraw}
     */
    private Map<String, Integer> pendingWithdraw;

    /**
     * オフライン中の預け入れ保留数（整数個）。
     * キー: Minecraft Material 名、値: 保留数量（整数）。
     * 次回ログイン時に {@code player.getInventory().removeItem()} で回収される。
     * GSON シリアライズ時のフィールド名: {@code pending_deposit}
     */
    private Map<String, Integer> pendingDeposit;

    // ─── コンストラクタ ────────────────────────────────────────────────────────

    /** GSON デシリアライズ用のデフォルトコンストラクタ。全マップを空で初期化する。 */
    public PlayerData() {
        this.hotStorage     = new HashMap<>();
        this.lockedBalance  = new HashMap<>();
        this.pendingWithdraw = new HashMap<>();
        this.pendingDeposit  = new HashMap<>();
    }

    /**
     * 新規プレイヤー作成用コンストラクタ（{@code /fx login} 初回実行時に呼ばれる）。
     *
     * @param name プレイヤー名
     */
    public PlayerData(String name) {
        this.name           = name;
        this.hotStorage     = new HashMap<>();
        this.lockedBalance  = new HashMap<>();
        this.pendingWithdraw = new HashMap<>();
        this.pendingDeposit  = new HashMap<>();
    }

    // ─── ゲッター / セッター ──────────────────────────────────────────────────

    public String getName()                                         { return name; }
    public Map<String, BigDecimal> getHotStorage()                  { return hotStorage; }
    public Map<String, Map<String, BigDecimal>> getLockedBalance()  { return lockedBalance; }
    public Map<String, Integer> getPendingWithdraw()                { return pendingWithdraw; }
    public Map<String, Integer> getPendingDeposit()                 { return pendingDeposit; }

    public void setName(String name)                                            { this.name = name; }
    public void setHotStorage(Map<String, BigDecimal> hotStorage)               { this.hotStorage = hotStorage; }
    public void setLockedBalance(Map<String, Map<String, BigDecimal>> lb)       { this.lockedBalance = lb; }
    public void setPendingWithdraw(Map<String, Integer> pendingWithdraw)        { this.pendingWithdraw = pendingWithdraw; }
    public void setPendingDeposit(Map<String, Integer> pendingDeposit)          { this.pendingDeposit = pendingDeposit; }

    // ─── ユーティリティメソッド ────────────────────────────────────────────────

    /**
     * 指定アイテムのホットストレージ残高を返す。
     * マップにキーが存在しない場合は {@code BigDecimal("0.0000")} を返す（オンデマンド初期化）。
     *
     * @param item Minecraft Material 名（小文字スネークケース）
     * @return 残高（scale=4, HALF_UP）
     */
    public BigDecimal getHotBalance(String item) {
        return hotStorage.getOrDefault(item,
                new BigDecimal("0.0000").setScale(4, java.math.RoundingMode.HALF_UP));
    }

    /**
     * 指定ペア・アイテムのロック残高を返す。
     * マップにキーが存在しない場合は {@code BigDecimal("0.0000")} を返す（オンデマンド初期化）。
     *
     * @param pairId ペアID（例: {@code "DIAMOND/EMERALD"}）
     * @param item   Minecraft Material 名（小文字スネークケース）
     * @return ロック残高（scale=4, HALF_UP）
     */
    public BigDecimal getLockedBalance(String pairId, String item) {
        Map<String, BigDecimal> pairLocked = lockedBalance.get(pairId);
        if (pairLocked == null) {
            return new BigDecimal("0.0000").setScale(4, java.math.RoundingMode.HALF_UP);
        }
        return pairLocked.getOrDefault(item,
                new BigDecimal("0.0000").setScale(4, java.math.RoundingMode.HALF_UP));
    }

    /**
     * 指定アイテムのホットストレージ残高を設定する。
     * 値が {@code "0.0000"} になっても、キーはマップに残す（ゼロ残高も明示的に保持）。
     *
     * @param item   Minecraft Material 名
     * @param amount 新しい残高（{@code null} 不可）
     */
    public void setHotBalance(String item, BigDecimal amount) {
        hotStorage.put(item, amount.setScale(4, java.math.RoundingMode.HALF_UP));
    }

    /**
     * 指定ペア・アイテムのロック残高を設定する。
     * ペアのエントリが存在しない場合は自動生成する。
     *
     * @param pairId ペアID
     * @param item   Minecraft Material 名
     * @param amount 新しいロック残高（{@code null} 不可）
     */
    public void setLockedBalance(String pairId, String item, BigDecimal amount) {
        lockedBalance
                .computeIfAbsent(pairId, k -> new HashMap<>())
                .put(item, amount.setScale(4, java.math.RoundingMode.HALF_UP));
    }

    /**
     * 指定アイテムの pending_withdraw 数量を返す。存在しない場合は {@code 0}。
     *
     * @param item Minecraft Material 名
     * @return 保留引き出し数量
     */
    public int getPendingWithdrawAmount(String item) {
        return pendingWithdraw.getOrDefault(item, 0);
    }

    /**
     * 指定アイテムの pending_deposit 数量を返す。存在しない場合は {@code 0}。
     *
     * @param item Minecraft Material 名
     * @return 保留預け入れ数量
     */
    public int getPendingDepositAmount(String item) {
        return pendingDeposit.getOrDefault(item, 0);
    }

    @Override
    public String toString() {
        return "PlayerData{name='" + name + '\''
                + ", hotStorage=" + hotStorage
                + ", pendingWithdraw=" + pendingWithdraw
                + ", pendingDeposit=" + pendingDeposit
                + '}';
    }
}
