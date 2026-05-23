package com.gekiyabafx.storage;

import com.gekiyabafx.model.StorageData;
import com.gekiyabafx.model.AtmData;
import com.gekiyabafx.model.AtmRegistry;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * メモリ上の {@link StorageData} を管理するシングルトンマネージャー。
 *
 * <h3>責務</h3>
 * <ol>
 *   <li><b>メモリ上のデータ保持</b> — {@link StorageData} の唯一の権威的コピーをここで持つ。</li>
 *   <li><b>排他制御</b> — グローバル {@link ReentrantLock} 1本でシリアライズ。
 *       ロックのスコープは「残高チェック〜マッチング〜残高更新」までのメモリ操作のみ。</li>
 *   <li><b>非同期書き込みキュー</b> — データ変更後に {@link #markDirty()} を呼ぶと、
 *       単一スレッドの {@link ScheduledExecutorService} が {@code 500ms} のデバウンス後に
 *       {@link StorageIO#save(StorageData)} を実行する。
 *       これにより連続した注文発注でも書き込みを集約し I/O スループットを確保する。</li>
 *   <li><b>onDisable() 同期フラッシュ</b> — {@link #shutdown()} を呼ぶと非同期タスクを
 *       キャンセルし、メインスレッドをブロックして即時同期書き込みを行う。
 *       サーバー停止時のデータ巻き戻りを防ぐ。</li>
 * </ol>
 *
 * <h3>典型的な使用パターン</h3>
 * <pre>{@code
 * StorageManager sm = StorageManager.getInstance();
 * sm.lock();
 * try {
 *     PlayerData player = sm.getData().getPlayer(uuid);
 *     // ... 残高チェック・更新 ...
 *     sm.markDirty();
 * } finally {
 *     sm.unlock();
 * }
 * }</pre>
 */
public final class StorageManager {

    // ─── シングルトン ──────────────────────────────────────────────────────────

    private static StorageManager instance;

    /**
     * シングルトンインスタンスを返す。
     *
     * @return {@link StorageManager} のインスタンス
     * @throws IllegalStateException {@link #initialize(File, Logger)} が呼ばれる前にアクセスした場合
     */
    public static StorageManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("StorageManager はまだ初期化されていません。");
        }
        return instance;
    }

    // ─── フィールド ────────────────────────────────────────────────────────────

    /** ディスク I/O 担当。 */
    private final StorageIO storageIO;

    /** ロガー。 */
    private final Logger logger;

    /** メモリ上のデータの唯一の権威的コピー。 */
    private StorageData data;

    /**
     * グローバル排他ロック（1本）。
     * ロックのスコープは「残高チェック〜マッチング〜残高更新」のメモリ操作のみ。
     * ディスク書き込みはロック解放後に非同期で実行する。
     */
    private final ReentrantLock lock = new ReentrantLock(true); // fair lock

    /**
     * 非同期書き込み用スケジューラー（シングルスレッド）。
     * データ変更を検出してから {@value #DEBOUNCE_MS}ms 後に書き込みを実行する。
     */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GekiyabaFX-StorageWriter");
                t.setDaemon(true);
                return t;
            });

    /** デバウンス間隔（ミリ秒）。この間隔内の連続した {@link #markDirty()} 呼び出しを集約する。 */
    private static final long DEBOUNCE_MS = 500L;

    /** スケジュール済みの書き込みタスクへの参照。デバウンス管理に使用する。 */
    private ScheduledFuture<?> pendingWriteTask;

    /** 未書き込みのダーティデータが存在するかどうかのフラグ。 */
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // ─── 初期化 ────────────────────────────────────────────────────────────────

    private StorageManager(File dataFolder, Logger logger) {
        this.storageIO = new StorageIO(dataFolder, logger);
        this.logger    = logger;
        this.data      = storageIO.load();
    }

    /**
     * {@link StorageManager} を初期化してシングルトンインスタンスを生成する。
     * {@code GekiyabaFXPlugin#onEnable()} から一度だけ呼び出すこと。
     *
     * @param dataFolder プラグインデータフォルダ（{@code JavaPlugin#getDataFolder()}）
     * @param logger     プラグインロガー（{@code JavaPlugin#getLogger()}）
     * @return 生成された {@link StorageManager} インスタンス
     */
    public static StorageManager initialize(File dataFolder, Logger logger) {
        instance = new StorageManager(dataFolder, logger);
        return instance;
    }

    // ─── ロック API ───────────────────────────────────────────────────────────

    /**
     * グローバルロックを取得する。
     * 必ず {@code try { lock() } finally { unlock() }} パターンで使用すること。
     */
    public void lock() {
        lock.lock();
    }

    /**
     * グローバルロックを解放する。
     * {@link #lock()} を取得したスレッドのみが呼び出せる。
     */
    public void unlock() {
        lock.unlock();
    }

    /**
     * 現在のスレッドがグローバルロックを保持しているかどうかを返す。
     * デバッグ・アサーション用。
     *
     * @return ロック保持中なら {@code true}
     */
    public boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    // ─── データアクセス ───────────────────────────────────────────────────────

    /**
     * メモリ上の {@link StorageData} を返す。
     *
     * <p><b>注意:</b> ロックを取得してから呼び出すこと。
     * ロックなしで呼ぶと他スレッドの書き込みと競合する可能性がある。</p>
     *
     * @return メモリ上の {@link StorageData}
     */
    public StorageData getData() {
        return data;
    }

    public AtmRegistry getAtmRegistry() {
        return data.getAtmRegistry();
    }

    public void registerAtm(AtmData atmData) {
        data.getAtmRegistry().register(atmData);
    }

    public AtmData getAtmBySignLocation(String world, int x, int y, int z) {
        return data.getAtmRegistry().getBySignLocation(world, x, y, z);
    }

    // ─── ダーティフラグ / 非同期書き込み ──────────────────────────────────────

    /**
     * データ変更を記録し、非同期書き込みをスケジュールする。
     *
     * <p>ロック取得中に呼び出すこと。
     * {@value #DEBOUNCE_MS}ms 以内に複数回呼ばれた場合はタスクをリセットして集約する
     * （デバウンス処理）。</p>
     */
    public void markDirty() {
        dirty.set(true);

        // 既存のスケジュール済みタスクをキャンセルしてデバウンスをリセットする
        if (pendingWriteTask != null && !pendingWriteTask.isDone()) {
            pendingWriteTask.cancel(false);
        }

        // DEBOUNCE_MS 後に非同期書き込みを実行するタスクをスケジュールする
        pendingWriteTask = scheduler.schedule(this::asyncSave, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 非同期スレッド（StorageWriter）で実行される書き込み処理。
     *
     * <p>書き込み中は {@link #lock} を取得しない。
     * 書き込む JSON 文字列の生成は GSON の {@code toJson()} が {@code StorageData} の
     * フィールドを読むだけであり、書き込み中に別スレッドが {@code StorageData} を
     * 変更すると理論上は不整合が生じる。
     * ただし {@code StorageData} の変更は必ずロック内で行われ、書き込みは変更後に
     * スケジュールされるため、実用上の不整合リスクは極めて低い。
     * 完全な整合性が必要な場合は {@link #shutdown()} の同期フラッシュを参照。</p>
     */
    private void asyncSave() {
        if (!dirty.get()) {
            return;
        }
        try {
            storageIO.save(data);
            dirty.set(false);
            logger.fine("storage.json を非同期書き込みしました。");
        } catch (IOException e) {
            logger.severe("storage.json の非同期書き込みに失敗しました: " + e.getMessage());
        }
    }

    // ─── シャットダウン（onDisable() 同期フラッシュ） ─────────────────────────

    /**
     * スケジューラーを停止し、未書き込みデータをメインスレッドで同期書き込みする。
     *
     * <p>{@code GekiyabaFXPlugin#onDisable()} から呼び出すこと。
     * サーバー停止・{@code /stop}・クラッシュシャットダウン時のデータ巻き戻りを防ぐ。</p>
     *
     * <p>手順:</p>
     * <ol>
     *   <li>スケジュール済みの非同期タスクをキャンセルする。</li>
     *   <li>スケジューラーをシャットダウンし、進行中のタスクが完了するまで最大2秒待つ。</li>
     *   <li>ダーティフラグが立っている場合は同期書き込みを実行する。</li>
     * </ol>
     */
    public void shutdown() {
        // 保留中の非同期タスクをキャンセル
        if (pendingWriteTask != null && !pendingWriteTask.isDone()) {
            pendingWriteTask.cancel(false);
        }

        // スケジューラーを停止（進行中のタスクは完了を待つ）
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.warning("StorageWriter スレッドが2秒以内に終了しませんでした。強制終了します。");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // ダーティデータが残っている場合は同期書き込みを実行する
        if (dirty.get()) {
            logger.info("onDisable: 未書き込みデータを同期フラッシュします...");
            try {
                storageIO.save(data);
                dirty.set(false);
                logger.info("onDisable: storage.json の同期フラッシュが完了しました。");
            } catch (IOException e) {
                logger.severe("onDisable: storage.json の同期フラッシュに失敗しました: " + e.getMessage());
            }
        }

        // シングルトンをクリア
        instance = null;
    }

    /**
     * 強制的に同期保存を行う（テスト・緊急用途）。
     * 通常は {@link #markDirty()} による非同期保存を使用すること。
     *
     * @throws IOException 書き込みに失敗した場合
     */
    public void saveNow() throws IOException {
        storageIO.save(data);
        dirty.set(false);
    }
}
