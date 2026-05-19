package com.gekiyabafx.engine;

/**
 * 残高不足で発注できない場合にスローされる例外。
 *
 * <p>ロック内で呼ばれるため checked exception とし、
 * 呼び出し側に必ず処理を強制する。</p>
 */
public final class InsufficientBalanceException extends Exception {

    /**
     * @param message エラー詳細メッセージ（アイテム名・必要量・保有量を含めること）
     */
    public InsufficientBalanceException(String message) {
        super(message);
    }
}
