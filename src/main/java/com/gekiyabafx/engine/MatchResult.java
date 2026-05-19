package com.gekiyabafx.engine;

import com.gekiyabafx.model.Execution;

import java.util.List;

/**
 * {@link MatchingEngine#placeOrder} の戻り値。
 *
 * <p>今回のマッチングで発生した {@link Execution} のリストを保持する。
 * リストが空の場合は約定なし（板に追加されたか、即時キャンセルされた）。</p>
 */
public final class MatchResult {

    private final List<Execution> executions;

    /**
     * @param executions 今回の発注で発生した約定リスト（空可）
     */
    public MatchResult(List<Execution> executions) {
        this.executions = List.copyOf(executions);
    }

    /**
     * 今回の発注で発生した約定リストを返す。
     *
     * @return イミュータブルな {@link Execution} リスト
     */
    public List<Execution> getExecutions() {
        return executions;
    }

    /**
     * 約定が1件以上あった場合 {@code true} を返す。
     *
     * @return 約定あり: {@code true}
     */
    public boolean hasExecutions() {
        return !executions.isEmpty();
    }
}
