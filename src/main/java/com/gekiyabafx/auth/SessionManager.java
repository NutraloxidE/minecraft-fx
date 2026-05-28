package com.gekiyabafx.auth;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * セッショントークンの生成・管理クラス。
 *
 * <p>プレイヤー用セッションと管理者用セッションの両方を統一的に管理する。
 * それぞれ別の {@link SessionManager} インスタンスを使用することで分離する。</p>
 *
 * <h3>セッショントークンの仕様</h3>
 * <ul>
 *   <li>UUID v4 文字列（{@code java.util.UUID.randomUUID()}）</li>
 *   <li>有効期限切れのエントリは {@link #resolve(String)} または
 *       {@link #cleanup()} で自動破棄</li>
 * </ul>
 *
 * <h3>スレッド安全性</h3>
 * {@link ConcurrentHashMap} を使用しているためスレッドセーフ。
 */
public final class SessionManager {

    // ─── セッションエントリ ────────────────────────────────────────────────────

    /**
     * 1件のセッションエントリ。
     * セッショントークン・紐づく識別子（プレイヤーUUID または固定の管理者識別子）・有効期限を保持する。
     */
    public static final class SessionEntry {

        /** セッショントークン文字列（UUID v4）。 */
        private final String token;

        /**
         * このセッションに紐づく識別子。
         * プレイヤー用の場合はプレイヤーUUID文字列、
         * 管理者用の場合は固定の管理者識別子（例: {@code "admin"}）を格納する。
         */
        private final String identity;

        /** 有効期限のUnixタイムスタンプ（秒）。 */
        private final long expiresAt;

        SessionEntry(String token, String identity, long expiresAt) {
            this.token     = token;
            this.identity  = identity;
            this.expiresAt = expiresAt;
        }

        /** @return セッショントークン文字列 */
        public String getToken()    { return token; }

        /** @return 紐づく識別子 */
        public String getIdentity() { return identity; }

        /** @return 有効期限のUnixタイムスタンプ（秒） */
        public long getExpiresAt()  { return expiresAt; }

        /** @return セッションが現在有効かどうか */
        public boolean isValid() {
            return Instant.now().getEpochSecond() < expiresAt;
        }
    }

    // ─── フィールド ────────────────────────────────────────────────────────────

    /**
     * セッショントークン → SessionEntry のマップ。
     * トークン文字列をキーにすることで、APIリクエスト受信時に O(1) で検索できる。
     */
    private final Map<String, SessionEntry> tokenToEntry = new ConcurrentHashMap<>();

    /** セッショントークンの有効期限（秒）。コンストラクタで設定される。 */
    private final long expireSeconds;

    // ─── コンストラクタ ────────────────────────────────────────────────────────

    /**
     * @param expireSeconds セッショントークンの有効期限（秒）。
     *                      {@code PluginConfig#getSessionExpireSeconds()} から取得する。
     */
    public SessionManager(long expireSeconds) {
        this.expireSeconds = expireSeconds;
    }

    // ─── 公開メソッド ──────────────────────────────────────────────────────────

    /**
     * 新しいセッショントークンを生成して返す。
     *
     * @param identity 紐づける識別子（プレイヤーUUID文字列 または 管理者識別子）
     * @return 生成した {@link SessionEntry}
     */
    public SessionEntry create(String identity) {
        return create(identity, expireSeconds);
    }

    /**
     * 新しいセッショントークンを生成して返す（有効期限を個別指定）。
     *
     * @param identity            紐づける識別子
     * @param customExpireSeconds このセッションの有効期限（秒）
     * @return 生成した {@link SessionEntry}
     */
    public SessionEntry create(String identity, long customExpireSeconds) {
        String token   = UUID.randomUUID().toString();
        long effectiveExpire = Math.max(1L, customExpireSeconds);
        long expiresAt = Instant.now().getEpochSecond() + effectiveExpire;
        SessionEntry entry = new SessionEntry(token, identity, expiresAt);
        tokenToEntry.put(token, entry);
        return entry;
    }

    /**
     * セッショントークンを検証して有効な {@link SessionEntry} を返す。
     *
     * <p>期限切れのエントリはマップから自動削除し {@code null} を返す。</p>
     *
     * @param token 検証するセッショントークン文字列
     * @return 有効な {@link SessionEntry}、または無効・期限切れの場合 {@code null}
     */
    public SessionEntry resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        SessionEntry entry = tokenToEntry.get(token);
        if (entry == null) {
            return null;
        }

        if (!entry.isValid()) {
            // 期限切れ — マップから削除して null を返す
            tokenToEntry.remove(token);
            return null;
        }

        return entry;
    }

    /**
     * 有効期限切れのセッションエントリを全削除する。
     * 定期的に呼び出すことでメモリリークを防ぐ（必要に応じて Bukkit タスクで定期実行）。
     */
    public void cleanup() {
        long now = Instant.now().getEpochSecond();
        tokenToEntry.entrySet().removeIf(e -> e.getValue().getExpiresAt() <= now);
    }
}
