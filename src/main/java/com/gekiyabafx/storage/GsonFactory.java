package com.gekiyabafx.storage;

import com.gekiyabafx.model.OrderSide;
import com.gekiyabafx.model.OrderStatus;
import com.gekiyabafx.model.OrderType;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.math.BigDecimal;

/**
 * プロジェクト全体で使用する {@link Gson} インスタンスを生成するファクトリ。
 *
 * <p>設定内容:</p>
 * <ul>
 *   <li>{@link BigDecimalTypeAdapter} — {@code BigDecimal} を文字列としてシリアライズ/デシリアライズ</li>
 *   <li>{@link FieldNamingPolicy#LOWER_CASE_WITH_UNDERSCORES} —
 *       Javaの {@code camelCase} フィールドを JSON の {@code snake_case} キーへ自動変換。
 *       例: {@code orderBook} → {@code order_book}、{@code createdAt} → {@code created_at}</li>
 *   <li>{@code serializeNulls()} — {@code null} フィールド（例: {@code last_price}）も JSON に出力する</li>
 *   <li>{@code setPrettyPrinting()} — storage.json を人間が読みやすい整形フォーマットで保存する</li>
 *   <li>enum は名前（文字列）としてシリアライズされる（GSON のデフォルト動作）</li>
 * </ul>
 */
public final class GsonFactory {

    /** インスタンス化禁止（ユーティリティクラス）。 */
    private GsonFactory() {}

    /**
     * 設定済みの {@link Gson} インスタンスを生成して返す。
     *
     * <p>呼び出し元はこのインスタンスをキャッシュして再利用すること
     * （{@link GsonBuilder} のビルドコストは低いが、都度生成は不要）。</p>
     *
     * @return プロジェクト標準設定の {@link Gson}
     */
    public static Gson create() {
        BigDecimalTypeAdapter bigDecimalAdapter = new BigDecimalTypeAdapter();

        return new GsonBuilder()
                // BigDecimal → JSON文字列（"4.3000"）/ JSON文字列 → BigDecimal
                .registerTypeAdapter(BigDecimal.class, bigDecimalAdapter)

                // camelCase → snake_case の自動変換
                // 例: hotStorage → hot_storage, orderBook → order_book
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)

                // null フィールドも出力する（last_price: null など）
                .serializeNulls()

                // storage.json を人間が読みやすい整形フォーマットで保存する
                .setPrettyPrinting()

                .create();
    }
}
