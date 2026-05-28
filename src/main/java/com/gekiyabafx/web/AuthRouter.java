package com.gekiyabafx.web;

import com.gekiyabafx.atm.AtmSessionManager;
import com.gekiyabafx.auth.OtpManager;
import com.gekiyabafx.auth.SessionManager;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 認証エンドポイントを Javalin アプリに登録するルーター。
 *
 * <h3>エンドポイント</h3>
 * <ul>
 *   <li>{@code POST /api/auth} — プレイヤー用OTPをセッショントークンに交換する。</li>
 *   <li>{@code POST /api/admin/auth} — 管理者用OTPをセッショントークンに交換する。</li>
 * </ul>
 *
 * <h3>リクエスト</h3>
 * <pre>{@code
 * POST /api/auth
 * Content-Type: application/json
 * { "otp": "ABCD1234EFGH5678" }
 * }</pre>
 *
 * <h3>レスポンス（成功 200）</h3>
 * <pre>{@code
 * {
 *   "token":      "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
 *   "identity":   "550e8400-e29b-41d4-a716-446655440000",
 *   "expires_at": 1716200000
 * }
 * }</pre>
 *
 * <h3>レスポンス（失敗 401）</h3>
 * <pre>{@code { "error": "invalid_otp" } }</pre>
 *
 * <h3>リクエストボディが不正（400）</h3>
 * <pre>{@code { "error": "bad_request" } }</pre>
 */
public final class AuthRouter {

    private static final long LONG_SESSION_EXPIRE_SECONDS = 24L * 60L * 60L;

    private final OtpManager     playerOtpManager;
    private final OtpManager     adminOtpManager;
    private final SessionManager playerSessionManager;
    private final SessionManager adminSessionManager;
    private final AtmSessionManager atmSessionManager;

    /**
     * @param playerOtpManager     プレイヤー用 {@link OtpManager}
     * @param adminOtpManager      管理者用 {@link OtpManager}
     * @param playerSessionManager プレイヤー用 {@link SessionManager}
     * @param adminSessionManager  管理者用 {@link SessionManager}
     */
    public AuthRouter(
            OtpManager     playerOtpManager,
            OtpManager     adminOtpManager,
            SessionManager playerSessionManager,
            SessionManager adminSessionManager,
            AtmSessionManager atmSessionManager
    ) {
        this.playerOtpManager     = playerOtpManager;
        this.adminOtpManager      = adminOtpManager;
        this.playerSessionManager = playerSessionManager;
        this.adminSessionManager  = adminSessionManager;
        this.atmSessionManager    = atmSessionManager;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ルート登録
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@link Javalin} アプリに認証ルートを登録する。
     * {@code WebServer} のコンストラクタ内ではなく {@code start()} 後に呼ぶこと。
     *
     * @param app {@link Javalin} インスタンス
     */
    public void register(Javalin app) {
        app.post("/api/auth",       this::handlePlayerAuth);
        app.post("/api/admin/auth", this::handleAdminAuth);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ハンドラ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@code POST /api/auth} — プレイヤー用 OTP → セッショントークン交換。
     *
     * <ol>
     *   <li>リクエストボディから {@code otp} フィールドを取得する。</li>
     *   <li>{@link OtpManager#consume(String)} で OTP を消費し {@link OtpManager.OtpEntry} を得る。</li>
     *   <li>有効な OTP であれば {@link SessionManager#create(String)} でセッションを生成する。</li>
     *   <li>トークン・identity・有効期限を JSON で返す。</li>
     * </ol>
     *
     * @param ctx Javalin コンテキスト
     */
    private void handlePlayerAuth(Context ctx) {
        exchangeOtp(ctx, playerOtpManager, playerSessionManager, atmSessionManager);
    }

    /**
     * {@code POST /api/admin/auth} — 管理者用 OTP → セッショントークン交換。
     *
     * <p>{@code /fx admin} で生成した OTP（identity = {@code "admin"}）を消費し、
     * 管理者セッショントークンを発行する。</p>
     *
     * @param ctx Javalin コンテキスト
     */
    private void handleAdminAuth(Context ctx) {
        exchangeOtp(ctx, adminOtpManager, adminSessionManager, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  内部ユーティリティ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * OTP 消費 → セッション発行の共通ロジック。
     *
     * @param ctx            Javalin コンテキスト
     * @param otpManager     消費に使う {@link OtpManager}
     * @param sessionManager 発行に使う {@link SessionManager}
     */
    private static void exchangeOtp(
            Context ctx,
            OtpManager otpManager,
            SessionManager sessionManager,
            AtmSessionManager atmSessionManager
    ) {
        // ── リクエストボディのパース ─────────────────────────────────────────
        String otp;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            Object otpObj = body.get("otp");
            if (otpObj == null) {
                ctx.status(400).json(Map.of("error", "bad_request"));
                return;
            }
            otp = otpObj.toString().strip();
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        // ── OTP 消費 ─────────────────────────────────────────────────────────
        OtpManager.OtpEntry otpEntry = otpManager.consume(otp);
        if (otpEntry == null) {
            // 存在しない・期限切れ・すでに使用済み
            ctx.status(401).json(Map.of("error", "invalid_otp"));
            return;
        }

        // ── セッション発行 ───────────────────────────────────────────────────
        SessionManager.SessionEntry session = otpEntry.isLongSession()
            ? sessionManager.create(otpEntry.getIdentity(), LONG_SESSION_EXPIRE_SECONDS)
            : sessionManager.create(otpEntry.getIdentity());

        AtmSessionManager.AtmSessionState atmState = AtmSessionManager.AtmSessionState.inactive();
        if (atmSessionManager != null) {
            atmState = atmSessionManager.activateByOtp(otp, otpEntry.getIdentity(), session.getToken());
        }

        Map<String, Object> atmJson = new LinkedHashMap<>();
        atmJson.put("active", atmState.isActive());
        atmJson.put("atm_id", atmState.getAtmId());
        atmJson.put("grade", atmState.getGrade());
        atmJson.put("max_distance", atmState.getMaxDistance());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", session.getToken());
        response.put("identity", session.getIdentity());
        response.put("expires_at", session.getExpiresAt());
        response.put("atm_session", atmJson);

        ctx.status(200).json(response);
    }
}
