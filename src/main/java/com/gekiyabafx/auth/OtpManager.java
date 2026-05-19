package com.gekiyabafx.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ワンタイムパスワード（OTP）の生成・管理クラス。
 *
 * <p>プレイヤー用OTPと管理者用OTPの両方を統一的に管理する。
 * それぞれ別の {@link OtpManager} インスタンスを使用することで分離する。</p>
 *
 * <h3>OTPの仕様</h3>
 * <ul>
 *   <li>英数字16文字（{@code [A-Z0-9]}）のランダム文字列</li>
 *   <li>1回使用したら即時無効化（消費型）</li>
 *   <li>有効期限切れのエントリは {@link #consume(String)} または
 *       {@link #cleanup()} で自動破棄</li>
 * </ul>
 *
 * <h3>スレッド安全性</h3>
 * {@link ConcurrentHashMap} を使用しているためスレッドセーフ。
 */
public final class OtpManager {

    /** OTPに使用する文字セット（英大文字＋数字）。 */
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** OTP の文字数。 */
    private static final int OTP_LENGTH = 16;

    /** 暗号学的に安全な乱数生成器。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    // ─── OTP エントリ ──────────────────────────────────────────────────────────

    /**
     * 1件の OTP エントリ。
     * OTP 文字列・紐づく識別子（プレイヤーUUID または管理者セッションID）・有効期限を保持する。
     */
    public static final class OtpEntry {

        /** 生成した OTP 文字列（16文字）。 */
        private final String otp;

        /**
         * この OTP に紐づく識別子。
         * プレイヤー用の場合はプレイヤーUUID文字列、
         * 管理者用の場合は管理者セッションIDを格納する。
         */
        private final String identity;

        /** 有効期限のUnixタイムスタンプ（秒）。 */
        private final long expiresAt;

        OtpEntry(String otp, String identity, long expiresAt) {
            this.otp       = otp;
            this.identity  = identity;
            this.expiresAt = expiresAt;
        }

        /** @return OTP 文字列 */
        public String getOtp()      { return otp; }

        /** @return 紐づく識別子（UUID または管理者セッションID） */
        public String getIdentity() { return identity; }

        /** @return 有効期限のUnixタイムスタンプ（秒） */
        public long getExpiresAt()  { return expiresAt; }

        /** @return OTP が現在有効かどうか */
        public boolean isValid() {
            return Instant.now().getEpochSecond() < expiresAt;
        }
    }

    // ─── フィールド ────────────────────────────────────────────────────────────

    /**
     * OTP文字列 → OtpEntry のマップ。
     * OTP文字列をキーにすることで、OTP受信時に O(1) で検索できる。
     */
    private final Map<String, OtpEntry> otpToEntry = new ConcurrentHashMap<>();

    /**
     * 識別子（UUID等）→ OTP文字列 のマップ。
     * 同一プレイヤーが再発行した際に古い OTP を即時無効化するために使用する。
     */
    private final Map<String, String> identityToOtp = new ConcurrentHashMap<>();

    /** OTP の有効期限（秒）。コンストラクタで設定される。 */
    private final long expireSeconds;

    // ─── コンストラクタ ────────────────────────────────────────────────────────

    /**
     * @param expireSeconds OTP の有効期限（秒）。{@code PluginConfig#getOtpExpireSeconds()} から取得する。
     */
    public OtpManager(long expireSeconds) {
        this.expireSeconds = expireSeconds;
    }

    // ─── 公開メソッド ──────────────────────────────────────────────────────────

    /**
     * 新しい OTP を生成して返す。
     *
     * <p>同一の識別子（UUID等）に対して既存の OTP が存在する場合は
     * 古い OTP を即時無効化してから新しい OTP を生成する。</p>
     *
     * @param identity 紐づける識別子（プレイヤーUUID文字列 または 管理者セッションID）
     * @return 生成した {@link OtpEntry}
     */
    public OtpEntry generate(String identity) {
        // 既存の OTP を無効化する（再発行時の古い OTP が残らないようにする）
        String existingOtp = identityToOtp.remove(identity);
        if (existingOtp != null) {
            otpToEntry.remove(existingOtp);
        }

        // 新しい OTP を生成する
        String otp = generateRandomOtp();
        long expiresAt = Instant.now().getEpochSecond() + expireSeconds;
        OtpEntry entry = new OtpEntry(otp, identity, expiresAt);

        otpToEntry.put(otp, entry);
        identityToOtp.put(identity, otp);

        return entry;
    }

    /**
     * OTP を消費してエントリを返す。
     *
     * <p>消費後は即時無効化される（1回限り有効）。
     * 無効・期限切れ・存在しない OTP に対しては {@code null} を返す。</p>
     *
     * @param otp 消費する OTP 文字列
     * @return 有効な {@link OtpEntry}、または無効・期限切れの場合 {@code null}
     */
    public OtpEntry consume(String otp) {
        if (otp == null || otp.isBlank()) {
            return null;
        }

        OtpEntry entry = otpToEntry.remove(otp);
        if (entry == null) {
            // 存在しない OTP
            return null;
        }

        // 逆引きマップからも削除する
        identityToOtp.remove(entry.getIdentity());

        if (!entry.isValid()) {
            // 期限切れ
            return null;
        }

        return entry;
    }

    /**
     * 有効期限切れの OTP エントリを全削除する。
     * 定期的に呼び出すことでメモリリークを防ぐ（必要に応じて Bukkit タスクで定期実行）。
     */
    public void cleanup() {
        long now = Instant.now().getEpochSecond();
        otpToEntry.entrySet().removeIf(entry -> {
            if (entry.getValue().getExpiresAt() <= now) {
                identityToOtp.remove(entry.getValue().getIdentity());
                return true;
            }
            return false;
        });
    }

    // ─── 内部ユーティリティ ────────────────────────────────────────────────────

    /**
     * 英数字16文字のランダム OTP を生成する。
     *
     * @return 生成した OTP 文字列（{@code [A-Z0-9]} 16文字）
     */
    private static String generateRandomOtp() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
