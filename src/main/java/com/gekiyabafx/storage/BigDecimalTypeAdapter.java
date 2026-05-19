package com.gekiyabafx.storage;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GSON 用 {@link BigDecimal} カスタムタイプアダプター。
 *
 * <h3>シリアライズ（Java → JSON）</h3>
 * {@code BigDecimal} を <b>ダブルクォーテーション囲みの文字列</b>として出力する。
 * 例: {@code 4.3} → {@code "4.3000"}
 * <p>
 * GSON のデフォルト動作では裸の数値型（{@code 4.3}）として出力されるが、
 * ブラウザの {@code JSON.parse()} 時に JavaScript の {@code number}（IEEE 754倍精度）へ
 * 暗黙キャストされ、$2^{53}-1$ を超えた値で端数が消失する。
 * 文字列として渡すことでフロントエンドが {@code new BigDecimal(string)} で安全に受け取れる。
 * </p>
 *
 * <h3>デシリアライズ（JSON → Java）</h3>
 * JSON 文字列・数値トークンの両方から {@link BigDecimal} を復元する。
 * {@code null} トークンは {@code null} を返す。
 * すべての値に {@code scale=4, RoundingMode.HALF_UP} を適用する。
 */
public final class BigDecimalTypeAdapter extends TypeAdapter<BigDecimal> {

    /** scale=4, HALF_UP を全演算に統一適用する定数。 */
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    @Override
    public void write(JsonWriter out, BigDecimal value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        // scale=4, HALF_UP を保証したうえで文字列として出力する
        out.value(value.setScale(SCALE, ROUNDING).toPlainString());
    }

    @Override
    public BigDecimal read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        // JSON 文字列トークン ("4.3000") と数値トークン (4.3) の両方に対応する
        String raw;
        if (in.peek() == JsonToken.STRING) {
            raw = in.nextString();
        } else {
            // NUMBER トークン — 精度を失わないために文字列として読む
            raw = in.nextString();
        }

        try {
            return new BigDecimal(raw).setScale(SCALE, ROUNDING);
        } catch (NumberFormatException e) {
            throw new IOException("BigDecimal のパースに失敗しました: '" + raw + "'", e);
        }
    }
}
