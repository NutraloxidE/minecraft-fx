package com.gekiyabafx.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code storage.json} のルートオブジェクト。
 *
 * <p>このクラスが GSON でシリアライズ/デシリアライズされる最上位モデル。
 * {@code StorageManager} がメモリ上にシングルトンとして保持し、
 * 変更のたびに非同期でディスクへ書き出す。</p>
 */
public final class StorageData {

    /**
     * 全取引ペアのデータ。
     * キー: ペアID（例: {@code "DIAMOND/EMERALD"}）、値: {@link Pair}。
     * 挿入順を保持するため {@link LinkedHashMap} を使用する。
     */
    private Map<String, Pair> pairs;

    /**
     * 全プレイヤーのデータ。
     * キー: プレイヤーUUID文字列（ハイフン区切り）、値: {@link PlayerData}。
     * 挿入順を保持するため {@link LinkedHashMap} を使用する。
     */
    private Map<String, PlayerData> players;

    /** GSON デシリアライズ用のデフォルトコンストラクタ。マップを空で初期化する。 */
    public StorageData() {
        this.pairs   = new LinkedHashMap<>();
        this.players = new LinkedHashMap<>();
    }

    // ─── ゲッター / セッター ──────────────────────────────────────────────────

    public Map<String, Pair> getPairs()           { return pairs; }
    public Map<String, PlayerData> getPlayers()   { return players; }

    public void setPairs(Map<String, Pair> pairs)         { this.pairs = pairs; }
    public void setPlayers(Map<String, PlayerData> players) { this.players = players; }

    // ─── ユーティリティ ────────────────────────────────────────────────────────

    /**
     * 指定IDの {@link Pair} を返す。存在しない場合は {@code null}。
     *
     * @param pairId ペアID（例: {@code "DIAMOND/EMERALD"}）
     * @return {@link Pair} または {@code null}
     */
    public Pair getPair(String pairId) {
        return pairs.get(pairId);
    }

    /**
     * 指定UUIDの {@link PlayerData} を返す。存在しない場合は {@code null}。
     *
     * @param uuid プレイヤーUUID文字列
     * @return {@link PlayerData} または {@code null}
     */
    public PlayerData getPlayer(String uuid) {
        return players.get(uuid);
    }

    @Override
    public String toString() {
        return "StorageData{pairs=" + pairs.size() + " pairs, players=" + players.size() + " players}";
    }
}
