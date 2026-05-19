package com.gekiyabafx.storage;

import com.gekiyabafx.model.StorageData;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * {@code storage.json} のディスク I/O を担当するクラス。
 *
 * <h3>責務</h3>
 * <ul>
 *   <li>起動時に {@code storage.json} を読み込み {@link StorageData} を返す</li>
 *   <li>ファイルが存在しない場合は空の {@link StorageData} を返す（新規インストール対応）</li>
 *   <li>書き込みは <b>アトミック</b> に行う：
 *       一時ファイル（{@code storage.json.tmp}）へ書き出してから {@code storage.json} へリネームする。
 *       これにより書き込み途中のクラッシュでファイルが壊れることを防ぐ。</li>
 * </ul>
 *
 * <h3>スレッド安全性</h3>
 * このクラス自体はスレッドセーフでない。
 * 呼び出し元（{@code StorageManager}）が {@code ReentrantLock} でシリアライズすること。
 */
public final class StorageIO {

    /** {@code storage.json} のファイル名。 */
    private static final String FILE_NAME = "storage.json";

    /** アトミック書き込みに使う一時ファイルのファイル名。 */
    private static final String TMP_FILE_NAME = "storage.json.tmp";

    /** プロジェクト標準設定の GSON インスタンス（スレッドセーフ・再利用可能）。 */
    private static final Gson GSON = GsonFactory.create();

    /** プラグインデータフォルダ（{@code plugins/GekiyabaFX/}）。 */
    private final File dataFolder;

    /** ロガー（プラグインの java.util.logging.Logger を使う）。 */
    private final Logger logger;

    /**
     * コンストラクタ。
     *
     * @param dataFolder プラグインデータフォルダ（{@code JavaPlugin#getDataFolder()}）
     * @param logger     プラグインロガー（{@code JavaPlugin#getLogger()}）
     */
    public StorageIO(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger     = logger;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  読み込み
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code storage.json} を読み込み {@link StorageData} を返す。
     *
     * <ul>
     *   <li>ファイルが存在しない場合 → 空の {@link StorageData} を返し、ログに記録する。</li>
     *   <li>ファイルが空または JSON パース失敗 → 空の {@link StorageData} を返し、警告ログを出す。</li>
     *   <li>読み込み成功 → デシリアライズした {@link StorageData} を返す。</li>
     * </ul>
     *
     * @return ロードした {@link StorageData}（新規の場合は空インスタンス）
     */
    public StorageData load() {
        File storageFile = new File(dataFolder, FILE_NAME);

        if (!storageFile.exists()) {
            logger.info("storage.json が見つかりません。新規データで起動します。");
            return new StorageData();
        }

        if (storageFile.length() == 0) {
            logger.warning("storage.json が空ファイルです。新規データで起動します。");
            return new StorageData();
        }

        try (BufferedReader reader = new BufferedReader(
                new FileReader(storageFile, StandardCharsets.UTF_8))) {

            StorageData data = GSON.fromJson(reader, StorageData.class);

            if (data == null) {
                logger.warning("storage.json のパース結果が null でした。新規データで起動します。");
                return new StorageData();
            }

            // null フィールドのフォールバック（GSON が map フィールドを null にする場合がある）
            data = ensureNonNullCollections(data);

            logger.info("storage.json を読み込みました: " + data);
            return data;

        } catch (IOException e) {
            logger.severe("storage.json の読み込みに失敗しました: " + e.getMessage());
            logger.severe("新規データで起動します。旧ファイルは上書きされる可能性があります。");
            return new StorageData();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  書き込み（アトミック）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@link StorageData} を {@code storage.json} へアトミックに書き込む。
     *
     * <p>手順:</p>
     * <ol>
     *   <li>{@code plugins/GekiyabaFX/} ディレクトリが存在しない場合は作成する。</li>
     *   <li>{@code storage.json.tmp} へ JSON を書き出す。</li>
     *   <li>{@code storage.json.tmp} を {@code storage.json} へアトミックリネームする
     *       （{@link StandardCopyOption#ATOMIC_MOVE} — OS が対応していない場合は非アトミックな移動にフォールバック）。</li>
     * </ol>
     *
     * @param data 書き込む {@link StorageData}（{@code null} 不可）
     * @throws IOException 書き込みまたはリネームに失敗した場合
     */
    public void save(StorageData data) throws IOException {
        // データフォルダが存在しない場合は作成する
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("プラグインデータフォルダの作成に失敗しました: " + dataFolder.getAbsolutePath());
        }

        File tmpFile     = new File(dataFolder, TMP_FILE_NAME);
        File storageFile = new File(dataFolder, FILE_NAME);

        // ① 一時ファイルへ書き出す
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(tmpFile, StandardCharsets.UTF_8))) {
            GSON.toJson(data, writer);
        }

        // ② アトミックリネーム（tmp → storage.json）
        Path tmpPath     = tmpFile.toPath();
        Path storagePath = storageFile.toPath();

        try {
            Files.move(tmpPath, storagePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // ATOMIC_MOVE をサポートしていないファイルシステムへのフォールバック
            logger.warning("ATOMIC_MOVE がサポートされていません。通常の移動で代替します: " + e.getMessage());
            Files.move(tmpPath, storagePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部ユーティリティ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GSON デシリアライズ後に {@code null} になる可能性があるコレクションフィールドを
     * 空コレクションで補完する。
     *
     * <p>GSON は JSON に存在しないフィールドをデフォルトコンストラクタで初期化しないため、
     * 古い {@code storage.json} に新規フィールドが存在しない場合に {@code null} になる可能性がある。
     * このメソッドで安全に補完する。</p>
     *
     * @param data デシリアライズ済みの {@link StorageData}
     * @return null コレクションを補完した {@link StorageData}（同一インスタンス）
     */
    private StorageData ensureNonNullCollections(StorageData data) {
        if (data.getPairs() == null) {
            data.setPairs(new java.util.LinkedHashMap<>());
        }
        if (data.getPlayers() == null) {
            data.setPlayers(new java.util.LinkedHashMap<>());
        }

        // 各ペアのコレクションを補完する
        data.getPairs().forEach((pairId, pair) -> {
            if (pair == null) return;

            if (pair.getOrderBook() == null) {
                pair.setOrderBook(new com.gekiyabafx.model.OrderBook());
            } else {
                if (pair.getOrderBook().getBids() == null) {
                    pair.getOrderBook().setBids(new java.util.ArrayList<>());
                }
                if (pair.getOrderBook().getAsks() == null) {
                    pair.getOrderBook().setAsks(new java.util.ArrayList<>());
                }
            }

            if (pair.getOrderHistory() == null) {
                pair.setOrderHistory(new java.util.ArrayList<>());
            }
            if (pair.getExecutions() == null) {
                pair.setExecutions(new java.util.ArrayList<>());
            }
        });

        // 各プレイヤーのコレクションを補完する
        data.getPlayers().forEach((uuid, player) -> {
            if (player == null) return;

            if (player.getHotStorage() == null) {
                player.setHotStorage(new java.util.HashMap<>());
            }
            if (player.getLockedBalance() == null) {
                player.setLockedBalance(new java.util.HashMap<>());
            }
            if (player.getPendingWithdraw() == null) {
                player.setPendingWithdraw(new java.util.HashMap<>());
            }
            if (player.getPendingDeposit() == null) {
                player.setPendingDeposit(new java.util.HashMap<>());
            }
        });

        return data;
    }
}
