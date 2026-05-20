package com.gekiyabafx.storage;

import com.gekiyabafx.model.Execution;

import java.util.List;

/**
 * 約定履歴の永続化を抽象化するリポジトリインターフェース。
 *
 * <p>本番実装は {@link H2ExecutionRepository}（H2 ファイルDB）。
 * テスト用にインメモリ実装に差し替えることも可能。</p>
 */
public interface ExecutionRepository {

    /**
     * 1件の約定を永続化する。
     *
     * @param pairId ペアID（例: {@code "DIAMOND/EMERALD"}）
     * @param ex     約定レコード
     */
    void insert(String pairId, Execution ex);

    /**
     * 指定ペアの約定履歴を返す。
     *
     * @param pairId ペアID
     * @param since  この Unix 秒より大きいタイムスタンプを持つ約定のみ返す（{@code 0} で全件）
     * @return タイムスタンプ昇順の約定リスト
     */
    List<Execution> findByPairSince(String pairId, long since);

    /**
     * リソースを解放する。プラグインの {@code onDisable()} から呼ぶこと。
     */
    void close();
}
