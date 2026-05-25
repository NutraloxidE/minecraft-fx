package com.gekiyabafx.web;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.arbitrage.ArbitrageService;
import com.gekiyabafx.auth.SessionManager;
import com.gekiyabafx.model.Pair;
import com.gekiyabafx.storage.StorageManager;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.bukkit.Bukkit;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 管理者向け ペア管理 API エンドポイントを Javalin アプリに登録するルーター。
 *
 * <h3>エンドポイント</h3>
 * <ul>
 *   <li>{@code GET  /api/admin/pairs}       — 全ペアを一覧する。</li>
 *   <li>{@code POST /api/admin/pairs}       — 新規ペアを追加する。</li>
 *   <li>{@code PATCH  /api/admin/pairs/:id} — 既存ペアを部分更新する（有効/無効切り替え等）。</li>
 *   <li>{@code DELETE /api/admin/pairs/:id} — ペアを削除する（注文・約定履歴ごと）。</li>
 * </ul>
 *
 * <h3>認証</h3>
 * <p>全エンドポイントで {@code Authorization: Bearer <token>} ヘッダーによる管理者セッション認証を要求する。</p>
 *
 * <h3>ペアID の規則</h3>
 * <p>ペアIDは {@code "BASE/QUOTE"} 形式（例: {@code "DIAMOND/EMERALD"}）の文字列。
 * URL パスパラメーターとして使う際は {@code %2F} エンコードが必要だが、
 * このルーターでは Javalin のワイルドカード {@code :id} で受け取り、
 * クライアント側のエンコードはフロントエンド層の責務とする。</p>
 */
public final class AdminApiRouter {

    private final SessionManager adminSessionManager;
    private final GekiyabaFXPlugin plugin;
    private final ArbitrageService arbitrageService;

    /**
     * @param adminSessionManager 管理者用 {@link SessionManager}
     * @param plugin             プラグイン本体
     * @param arbitrageService   裁定取引サービス
     */
    public AdminApiRouter(SessionManager adminSessionManager,
                          GekiyabaFXPlugin plugin,
                          ArbitrageService arbitrageService) {
        this.adminSessionManager = adminSessionManager;
        this.plugin = plugin;
        this.arbitrageService = arbitrageService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ルート登録
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * {@link Javalin} アプリに管理者ペア管理ルートを登録する。
     *
     * @param app {@link Javalin} インスタンス
     */
    public void register(Javalin app) {
        app.get   ("/api/admin/pairs",              this::handleListPairs);
        app.post  ("/api/admin/pairs",              this::handleCreatePair);
        app.patch ("/api/admin/pairs/order",        this::handleReorderPairs);
        app.patch ("/api/admin/pairs/{id}",         this::handlePatchPair);
        app.delete("/api/admin/pairs/{id}",         this::handleDeletePair);
        app.get   ("/api/admin/web-settings",       this::handleGetWebSettings);
        app.patch ("/api/admin/web-settings",       this::handlePatchWebSettings);
        app.get   ("/api/admin/service-accounts",   this::handleServiceAccounts);
        app.get   ("/api/admin/backup/download",    this::handleDownloadServerBackup);
        app.patch ("/api/admin/arbitrage/toggle",   this::handleArbitrageToggle);
        app.get   ("/api/admin/arbitrage/status",   this::handleArbitrageStatus);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PATCH /api/admin/pairs/order
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ペア一覧の表示順を並び替える。
     *
     * <h4>リクエストボディ</h4>
     * <pre>{@code
     * {
     *   "ordered_ids": ["DIAMOND/EMERALD", "GOLD/EMERALD", "IRON/EMERALD"]
     * }
     * }</pre>
     *
     * <p>{@code ordered_ids} は現在存在する全ペアIDを重複なく1回ずつ含む必要がある。</p>
     */
    private void handleReorderPairs(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        Map<String, Object> body = parseBody(ctx);
        if (body == null) return;

        List<String> orderedIds = getStringList(body, "ordered_ids");
        if (orderedIds == null || orderedIds.isEmpty()) {
            ctx.status(400).json(Map.of("error", "missing_ordered_ids"));
            return;
        }

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Map<String, Pair> currentPairs = sm.getData().getPairs();
            if (orderedIds.size() != currentPairs.size()) {
                ctx.status(400).json(Map.of("error", "pair_count_mismatch"));
                return;
            }

            Set<String> unique = new LinkedHashSet<>(orderedIds);
            if (unique.size() != orderedIds.size()) {
                ctx.status(400).json(Map.of("error", "duplicate_pair_id"));
                return;
            }

            if (!currentPairs.keySet().containsAll(unique) || !unique.containsAll(currentPairs.keySet())) {
                ctx.status(400).json(Map.of("error", "invalid_pair_ids"));
                return;
            }

            Map<String, Pair> reordered = new LinkedHashMap<>();
            for (String id : orderedIds) {
                reordered.put(id, currentPairs.get(id));
            }

            sm.getData().setPairs(reordered);
            sm.markDirty();

            List<Map<String, Object>> list = new ArrayList<>();
            reordered.forEach((id, pair) -> list.add(pairToMap(id, pair)));
            ctx.status(200).json(list);
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  認証ヘルパー
    // ─────────────────────────────────────────────────────────────────────────

    private boolean requireAdminAuth(Context ctx) {
        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return false;
        }
        String token = header.substring(7).trim();
        if (adminSessionManager.resolve(token) == null) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/admin/pairs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 全ペアを一覧する。
     *
     * <h4>レスポンス（200）</h4>
     * <pre>{@code
     * [
     *   {
     *     "id":         "DIAMOND/EMERALD",
     *     "base":       "diamond",
     *     "quote":      "emerald",
     *     "enabled":    true,
     *     "min_amount": "0.0001",
     *     "min_price":  "0.0001",
     *     "last_price": "4.3000"
     *   },
     *   ...
     * ]
     * }</pre>
     */
    private void handleListPairs(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            sm.getData().getPairs().forEach((id, pair) ->
                    list.add(pairToMap(id, pair)));
            ctx.json(list);
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  POST /api/admin/pairs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 新規ペアを追加する。
     *
     * <h4>リクエストボディ</h4>
     * <pre>{@code
     * {
     *   "id":         "DIAMOND/EMERALD",
     *   "base":       "diamond",
     *   "quote":      "emerald",
     *   "enabled":    false,
     *   "min_amount": "0.0001",
     *   "min_price":  "0.0001"
     * }
     * }</pre>
     *
     * <h4>レスポンス（201）</h4>
     * <pre>{@code { "id": "DIAMOND/EMERALD", "created": true } }</pre>
     *
     * <p>同一IDのペアが既に存在する場合は {@code 409 already_exists}。</p>
     */
    private void handleCreatePair(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        Map<String, Object> body = parseBody(ctx);
        if (body == null) return;

        String id = getString(body, "id");
        if (id == null || id.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_id"));
            return;
        }

        String base  = getString(body, "base");
        String quote = getString(body, "quote");
        if (base == null || base.isBlank() || quote == null || quote.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing_base_or_quote"));
            return;
        }

        BigDecimal minAmount = parseBigDecimal(body, "min_amount", new BigDecimal("0.0001"));
        BigDecimal minPrice  = parseBigDecimal(body, "min_price",  new BigDecimal("0.0001"));
        boolean enabled      = getBoolean(body, "enabled", false);

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            if (sm.getData().getPairs().containsKey(id)) {
                ctx.status(409).json(Map.of("error", "already_exists"));
                return;
            }
            Pair pair = new Pair(
                    base.toLowerCase(),
                    quote.toLowerCase(),
                    enabled,
                    minAmount.setScale(4, RoundingMode.HALF_UP),
                    minPrice.setScale(4, RoundingMode.HALF_UP)
            );
            sm.getData().getPairs().put(id, pair);
            sm.markDirty();

            ctx.status(201).json(Map.of("id", id, "created", true));
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PATCH /api/admin/pairs/:id
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 既存ペアを部分更新する。
     *
     * <h4>リクエストボディ（すべてオプション）</h4>
     * <pre>{@code
     * {
     *   "enabled":    true,
     *   "min_amount": "0.0010",
     *   "min_price":  "0.0010"
     * }
     * }</pre>
     *
     * <h4>レスポンス（200）</h4>
     * <p>更新後のペア情報（{@link #handleListPairs} の1要素と同形式）。</p>
     *
     * <p>{@code enabled} を {@code false} にしたとき、未約定の注文は <b>返金のみ</b>（板から除外）。
     * 返金処理は本メソッド内で同期的に実行する。</p>
     */
    private void handlePatchPair(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        String id = ctx.pathParam("id");

        Map<String, Object> body = parseBody(ctx);
        if (body == null) return;

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Pair pair = sm.getData().getPairs().get(id);
            if (pair == null) {
                ctx.status(404).json(Map.of("error", "pair_not_found"));
                return;
            }

            boolean wasEnabled = pair.isEnabled();

            // 部分更新
            if (body.containsKey("enabled")) {
                pair.setEnabled(getBoolean(body, "enabled", pair.isEnabled()));
            }
            if (body.containsKey("min_amount")) {
                BigDecimal v = parseBigDecimal(body, "min_amount", pair.getMinAmount());
                pair.setMinAmount(v.setScale(4, RoundingMode.HALF_UP));
            }
            if (body.containsKey("min_price")) {
                BigDecimal v = parseBigDecimal(body, "min_price", pair.getMinPrice());
                pair.setMinPrice(v.setScale(4, RoundingMode.HALF_UP));
            }

            // enabled → false になった場合、未約定注文を全返金
            if (wasEnabled && !pair.isEnabled()) {
                refundAllOpenOrders(id, pair, sm);
            }

            sm.markDirty();
            ctx.json(pairToMap(id, pair));
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DELETE /api/admin/pairs/:id
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * ペアを削除する。
     *
     * <p>削除前に未約定注文を全返金する。
     * 注文・約定履歴はペアごと削除される。</p>
     *
     * <h4>レスポンス（200）</h4>
     * <pre>{@code { "id": "DIAMOND/EMERALD", "deleted": true } }</pre>
     */
    private void handleDeletePair(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        String id = ctx.pathParam("id");

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Pair pair = sm.getData().getPairs().get(id);
            if (pair == null) {
                ctx.status(404).json(Map.of("error", "pair_not_found"));
                return;
            }

            // 未約定注文を全返金してから削除
            refundAllOpenOrders(id, pair, sm);
            sm.getData().getPairs().remove(id);
            sm.markDirty();

            ctx.json(Map.of("id", id, "deleted", true));
        } finally {
            sm.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  返金ヘルパー（ロック内で呼ぶこと）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 板上の全未約定注文を返金する。
     *
     * <ul>
     *   <li>Bid（買い）注文 → ロックされた quote 残高を hot_storage に戻す</li>
     *   <li>Ask（売り）注文 → ロックされた base 残高を hot_storage に戻す</li>
     * </ul>
     *
     * <p>このメソッドは {@link StorageManager} のロックが取得済みの状態で呼ぶこと。</p>
     *
     * @param pairId ペアID（例: {@code "DIAMOND/EMERALD"}）
     * @param pair   対象ペア
     * @param sm     {@link StorageManager} インスタンス
     */
    private void refundAllOpenOrders(String pairId, Pair pair, StorageManager sm) {
        String base  = pair.getBase();
        String quote = pair.getQuote();

        // Bid（買い注文）: ロックされた quote を返金
        for (com.gekiyabafx.model.Order order : new ArrayList<>(pair.getOrderBook().getBids())) {
            refundOrder(pairId, order, quote, sm);
        }
        // Ask（売り注文）: ロックされた base を返金
        for (com.gekiyabafx.model.Order order : new ArrayList<>(pair.getOrderBook().getAsks())) {
            refundOrder(pairId, order, base, sm);
        }

        // 板をクリア
        pair.getOrderBook().getBids().clear();
        pair.getOrderBook().getAsks().clear();
    }

    /**
     * 1注文分の残高を返金する。
     *
     * @param pairId   ペアID
     * @param order    返金対象の注文
     * @param lockItem ロック解除するアイテム名
     * @param sm       {@link StorageManager}（ロック取得済み）
     */
    private void refundOrder(String pairId, com.gekiyabafx.model.Order order,
                              String lockItem, StorageManager sm) {
        com.gekiyabafx.model.PlayerData data =
                sm.getData().getPlayer(order.getUuid());
        if (data == null) return;

        BigDecimal locked = data.getLockedBalance(pairId, lockItem);
        if (locked.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal hot = data.getHotBalance(lockItem);
            data.setHotBalance(lockItem, hot.add(locked));
            data.setLockedBalance(pairId, lockItem, BigDecimal.ZERO);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  レスポンス変換
    // ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> pairToMap(String id, Pair pair) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         id);
        m.put("base",       pair.getBase());
        m.put("quote",      pair.getQuote());
        m.put("enabled",    pair.isEnabled());
        m.put("min_amount", pair.getMinAmount() != null ? pair.getMinAmount().toPlainString() : "0.0001");
        m.put("min_price",  pair.getMinPrice()  != null ? pair.getMinPrice().toPlainString()  : "0.0001");
        m.put("last_price", pair.getLastPrice() != null ? pair.getLastPrice().toPlainString() : null);
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  パースヘルパー
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Context ctx) {
        try {
            return ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return null;
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s)  return Boolean.parseBoolean(s);
        return defaultVal;
    }

    private static Integer getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof List<?> list)) return null;

        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (!(e instanceof String s) || s.isBlank()) {
                return null;
            }
            out.add(s);
        }
        return out;
    }

    private static BigDecimal parseBigDecimal(Map<String, Object> map, String key, BigDecimal defaultVal) {
        Object v = map.get(key);
        if (v == null) return defaultVal;
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static String bdStr(BigDecimal bd) {
        if (bd == null) return "0.0000";
        return bd.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET/PATCH /api/admin/web-settings
    // ─────────────────────────────────────────────────────────────────────────

    private void handleGetWebSettings(Context ctx) {
        if (!requireAdminAuth(ctx)) return;
        ctx.status(200).json(Map.of(
                "server_ip", plugin.getPluginConfig().getServerIp(),
                "web_port", plugin.getPluginConfig().getWebPort(),
                "login_url_scheme", plugin.getPluginConfig().getLoginUrlScheme(),
                "login_url_include_port", plugin.getPluginConfig().isLoginUrlIncludePort()
        ));
    }

    private void handlePatchWebSettings(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        Map<String, Object> body = parseBody(ctx);
        if (body == null) return;

        String normalizedServerIp = plugin.getPluginConfig().getServerIp();
        String normalizedScheme = plugin.getPluginConfig().getLoginUrlScheme();
        boolean includePort = plugin.getPluginConfig().isLoginUrlIncludePort();

        if (body.containsKey("server_ip")) {
            String serverIp = getString(body, "server_ip");
            if (serverIp == null || serverIp.isBlank()) {
                ctx.status(400).json(Map.of("error", "invalid_server_ip"));
                return;
            }

            normalizedServerIp = serverIp.trim();
            if (normalizedServerIp.length() > 255 || normalizedServerIp.contains(" ")) {
                ctx.status(400).json(Map.of("error", "invalid_server_ip"));
                return;
            }
        }

        if (body.containsKey("login_url_scheme")) {
            String scheme = getString(body, "login_url_scheme");
            if (scheme == null) {
                ctx.status(400).json(Map.of("error", "invalid_login_url_scheme"));
                return;
            }

            normalizedScheme = scheme.trim().toLowerCase(Locale.ROOT);
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                ctx.status(400).json(Map.of("error", "invalid_login_url_scheme"));
                return;
            }
        }

        if (body.containsKey("login_url_include_port")) {
            Object includePortRaw = body.get("login_url_include_port");
            if (includePortRaw instanceof Boolean b) {
                includePort = b;
            } else if (includePortRaw instanceof String s) {
                if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) {
                    ctx.status(400).json(Map.of("error", "invalid_login_url_include_port"));
                    return;
                }
                includePort = Boolean.parseBoolean(s);
            } else {
                ctx.status(400).json(Map.of("error", "invalid_login_url_include_port"));
                return;
            }
        }

        try {
            plugin.getConfig().set("server-ip", normalizedServerIp);
            plugin.getConfig().set("login-url-scheme", normalizedScheme);
            plugin.getConfig().set("login-url-include-port", includePort);
            plugin.saveConfig();
            plugin.reloadPluginConfig();
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "invalid_web_settings", "message", e.getMessage()));
            return;
        } catch (Exception e) {
            plugin.getLogger().warning("web-settings の更新に失敗しました: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "web_settings_update_failed"));
            return;
        }

        ctx.status(200).json(Map.of(
                "server_ip", plugin.getPluginConfig().getServerIp(),
                "web_port", plugin.getPluginConfig().getWebPort(),
                "login_url_scheme", plugin.getPluginConfig().getLoginUrlScheme(),
                "login_url_include_port", plugin.getPluginConfig().isLoginUrlIncludePort(),
                "updated", true
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/admin/backup/download
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 管理者認証済みクライアント向けに、サーバー復元用 ZIP を生成して返す。
     *
     * <p>ZIP には以下を含める:</p>
     * <ul>
    *   <li>サーバールート直下の {@code world*} ディレクトリ群</li>
    *   <li>{@code plugins/GekiyabaFX}（プラグイン保存データ）</li>
    *   <li>{@code server.properties}</li>
    *   <li>{@code config/}（Paper 設定一式）</li>
     * </ul>
     *
     * <p>ディレクトリ構造はサーバールートからの相対パスをそのまま保持する。</p>
     */
    private void handleDownloadServerBackup(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        Path pluginDataDir = plugin.getDataFolder().toPath().normalize();
        Path serverRoot = resolveServerRoot(pluginDataDir);
        if (serverRoot == null || !Files.isDirectory(serverRoot)) {
            ctx.status(500).json(Map.of("error", "server_root_not_found"));
            return;
        }

        Path pluginDataSource = pluginDataDir;
        if (!pluginDataSource.startsWith(serverRoot)) {
            Path fallback = serverRoot.resolve("plugins").resolve("GekiyabaFX").normalize();
            if (Files.isDirectory(fallback)) {
                pluginDataSource = fallback;
            }
        }

        List<Path> worldDirs;
        try (Stream<Path> stream = Files.list(serverRoot)) {
            worldDirs = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("world"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            plugin.getLogger().warning("バックアップ対象ディレクトリの列挙に失敗しました: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "backup_scan_failed"));
            return;
        }

        Path serverProperties = serverRoot.resolve("server.properties");
        Path paperConfigDir = serverRoot.resolve("config");

        if (worldDirs.isEmpty()
            && !Files.isDirectory(pluginDataSource)
            && !Files.isRegularFile(serverProperties)
            && !Files.isDirectory(paperConfigDir)) {
            ctx.status(404).json(Map.of("error", "backup_source_not_found"));
            return;
        }

        Path tempZip = null;
        try {
            tempZip = Files.createTempFile("gekiyabafx-server-backup-", ".zip");

            boolean wroteSomething = false;
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tempZip)))) {
                for (Path worldDir : worldDirs) {
                    wroteSomething |= zipDirectoryTree(zos, serverRoot, worldDir);
                }
                if (Files.isDirectory(pluginDataSource)) {
                    wroteSomething |= zipDirectoryTree(zos, serverRoot, pluginDataSource);
                }
                if (Files.isRegularFile(serverProperties)) {
                    wroteSomething |= zipSingleFile(zos, serverRoot, serverProperties);
                }
                if (Files.isDirectory(paperConfigDir)) {
                    wroteSomething |= zipDirectoryTree(zos, serverRoot, paperConfigDir);
                }
            }

            if (!wroteSomething) {
                ctx.status(404).json(Map.of("error", "backup_source_not_found"));
                return;
            }

            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            String filename = "gekiyabafx-server-backup-" + timestamp + ".zip";

            ctx.status(200);
            ctx.contentType("application/zip");
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            ctx.header("Cache-Control", "no-store");
            ctx.res().setContentLengthLong(Files.size(tempZip));

            try (
                    InputStream in = new BufferedInputStream(Files.newInputStream(tempZip));
                    OutputStream out = ctx.res().getOutputStream()
            ) {
                in.transferTo(out);
                out.flush();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("バックアップZIPの生成または送信に失敗しました: " + e.getMessage());
            if (!ctx.res().isCommitted()) {
                ctx.status(500).json(Map.of("error", "backup_generation_failed"));
            }
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {
                    // 一時ファイルの削除失敗はリクエスト結果に影響させない。
                }
            }
        }
    }

    private Path resolveServerRoot(Path pluginDataDir) {
        try {
            Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            if (Files.isDirectory(worldContainer)) {
                return worldContainer;
            }
        } catch (Exception ignored) {
            // Bukkit から取得できない場合は plugins/GekiyabaFX から推定する。
        }

        Path pluginsDir = pluginDataDir.getParent();
        if (pluginsDir == null) {
            return null;
        }
        Path guessed = pluginsDir.getParent();
        if (guessed == null || !Files.isDirectory(guessed)) {
            return null;
        }
        return guessed.normalize();
    }

    private static boolean zipDirectoryTree(ZipOutputStream zos, Path serverRoot, Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }

        final boolean[] wroteSomething = {false};

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                try {
                    Path relative = serverRoot.relativize(dir);
                    String entryName = relative.toString().replace('\\', '/');
                    if (!entryName.isBlank()) {
                        if (!entryName.endsWith("/")) {
                            entryName += "/";
                        }
                        ZipEntry entry = new ZipEntry(entryName);
                        entry.setTime(attrs.lastModifiedTime().toMillis());
                        zos.putNextEntry(entry);
                        zos.closeEntry();
                        wroteSomething[0] = true;
                    }
                } catch (Exception ignored) {
                    // 読み取り不可ディレクトリはスキップして継続する。
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Path relative = serverRoot.relativize(file);
                    String entryName = relative.toString().replace('\\', '/');
                    if (entryName.isBlank()) {
                        return FileVisitResult.CONTINUE;
                    }

                    ZipEntry entry = new ZipEntry(entryName);
                    entry.setTime(attrs.lastModifiedTime().toMillis());
                    zos.putNextEntry(entry);
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                    wroteSomething[0] = true;
                } catch (Exception ignored) {
                    // 稼働中サーバーでロックされたファイルはスキップして継続する。
                    try {
                        zos.closeEntry();
                    } catch (Exception ignored2) {
                        // no-op
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        return wroteSomething[0];
    }

    private static boolean zipSingleFile(ZipOutputStream zos, Path serverRoot, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }

        Path relative = serverRoot.relativize(file);
        String entryName = relative.toString().replace('\\', '/');
        if (entryName.isBlank()) {
            return false;
        }

        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(Files.getLastModifiedTime(file).toMillis());
        zos.putNextEntry(entry);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            in.transferTo(zos);
        }
        zos.closeEntry();
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GET /api/admin/service-accounts
    // ─────────────────────────────────────────────────────────────────────────

    private void handleServiceAccounts(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        StorageManager sm = StorageManager.getInstance();
        List<Map<String, Object>> result = new ArrayList<>();

        sm.lock();
        try {
            for (String name : plugin.getPluginConfig().getServiceAccounts()) {
                String id = "svc:" + name;
                com.gekiyabafx.model.PlayerData pd = sm.getData().getPlayers().get(id);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("id",   id);
                if (pd != null) {
                    Map<String, String> hs = new LinkedHashMap<>();
                    pd.getHotStorage().forEach((k, v) -> hs.put(k, bdStr(v)));
                    entry.put("hot_storage", hs);
                } else {
                    entry.put("hot_storage", Map.of());
                }
                result.add(entry);
            }
        } finally {
            sm.unlock();
        }

        ctx.status(200).json(result);
    }

    private void handleArbitrageToggle(Context ctx) {
        if (!requireAdminAuth(ctx)) return;

        Map<String, Object> body = parseBody(ctx);
        if (body == null) return;

        Boolean enabled = null;
        if (body.containsKey("enabled")) {
            enabled = getBoolean(body, "enabled", false);
        }

        Integer checkIntervalTicks = null;
        if (body.containsKey("check_interval_ticks")) {
            checkIntervalTicks = getInt(body, "check_interval_ticks");
            if (checkIntervalTicks == null || checkIntervalTicks < 1) {
                ctx.status(400).json(Map.of("error", "invalid_check_interval_ticks"));
                return;
            }
        }

        BigDecimal minGrossSpreadPct = null;
        if (body.containsKey("min_gross_spread_pct")) {
            minGrossSpreadPct = parseStrictBigDecimal(body.get("min_gross_spread_pct"));
            if (minGrossSpreadPct == null || minGrossSpreadPct.compareTo(BigDecimal.ZERO) < 0) {
                ctx.status(400).json(Map.of("error", "invalid_min_gross_spread_pct"));
                return;
            }
        }

        BigDecimal minNetProfitPct = null;
        if (body.containsKey("min_net_profit_pct")) {
            minNetProfitPct = parseStrictBigDecimal(body.get("min_net_profit_pct"));
            if (minNetProfitPct == null || minNetProfitPct.compareTo(BigDecimal.ZERO) < 0) {
                ctx.status(400).json(Map.of("error", "invalid_min_net_profit_pct"));
                return;
            }
        }

        BigDecimal slipPriceChangeThresholdPct = null;
        if (body.containsKey("slip_price_change_threshold_pct")) {
            slipPriceChangeThresholdPct = parseStrictBigDecimal(body.get("slip_price_change_threshold_pct"));
            if (slipPriceChangeThresholdPct == null || slipPriceChangeThresholdPct.compareTo(BigDecimal.ZERO) < 0) {
                ctx.status(400).json(Map.of("error", "invalid_slip_price_change_threshold_pct"));
                return;
            }
        }

        BigDecimal slipVolumeDropThresholdPct = null;
        if (body.containsKey("slip_volume_drop_threshold_pct")) {
            slipVolumeDropThresholdPct = parseStrictBigDecimal(body.get("slip_volume_drop_threshold_pct"));
            if (slipVolumeDropThresholdPct == null || slipVolumeDropThresholdPct.compareTo(BigDecimal.ZERO) < 0) {
                ctx.status(400).json(Map.of("error", "invalid_slip_volume_drop_threshold_pct"));
                return;
            }
        }

        Integer slipCheckLookbackTicks = null;
        if (body.containsKey("slip_check_lookback_ticks")) {
            slipCheckLookbackTicks = getInt(body, "slip_check_lookback_ticks");
            if (slipCheckLookbackTicks == null || slipCheckLookbackTicks < 1) {
                ctx.status(400).json(Map.of("error", "invalid_slip_check_lookback_ticks"));
                return;
            }
        }

        String serviceAccount = getString(body, "service_account");
        if (serviceAccount != null && !serviceAccount.isBlank()) {
            if (!serviceAccount.startsWith("svc:")) {
                ctx.status(400).json(Map.of("error", "invalid_service_account"));
                return;
            }
            String name = serviceAccount.substring(4);
            if (!plugin.getPluginConfig().getServiceAccounts().contains(name)) {
                ctx.status(400).json(Map.of("error", "service_account_not_allowed"));
                return;
            }
        }

        arbitrageService.applyRuntimeConfig(enabled, serviceAccount, checkIntervalTicks);

        try {
            plugin.getConfig().set("arbitrage.enabled", arbitrageService.isEnabled());
            plugin.getConfig().set("arbitrage.service_account", arbitrageService.getServiceAccount());
            plugin.getConfig().set("arbitrage.check_interval_ticks", arbitrageService.getCheckIntervalTicks());
            if (minGrossSpreadPct != null) {
                plugin.getConfig().set("arbitrage.min_gross_spread_pct", minGrossSpreadPct.toPlainString());
            }
            if (minNetProfitPct != null) {
                plugin.getConfig().set("arbitrage.min_net_profit_pct", minNetProfitPct.toPlainString());
            }
            if (slipPriceChangeThresholdPct != null) {
                plugin.getConfig().set("arbitrage.slip_detection.price_change_threshold_pct", slipPriceChangeThresholdPct.toPlainString());
            }
            if (slipVolumeDropThresholdPct != null) {
                plugin.getConfig().set("arbitrage.slip_detection.volume_drop_threshold_pct", slipVolumeDropThresholdPct.toPlainString());
            }
            if (slipCheckLookbackTicks != null) {
                plugin.getConfig().set("arbitrage.slip_detection.check_lookback_ticks", slipCheckLookbackTicks);
            }
            plugin.saveConfig();
            plugin.reloadPluginConfig();
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "invalid_arbitrage_settings", "message", e.getMessage()));
            return;
        } catch (Exception e) {
            plugin.getLogger().warning("arbitrage settings の更新に失敗しました: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "arbitrage_settings_update_failed"));
            return;
        }

        var cfg = plugin.getPluginConfig();

        ctx.status(200).json(Map.of(
                "enabled", arbitrageService.isEnabled(),
                "current_service_account", arbitrageService.getServiceAccount(),
                "current_check_interval_ticks", arbitrageService.getCheckIntervalTicks(),
                "current_min_gross_spread_pct", cfg.getArbitrageMinGrossSpreadPct().toPlainString(),
                "current_min_net_profit_pct", cfg.getArbitrageMinNetProfitPct().toPlainString(),
                "current_slip_price_change_threshold_pct", cfg.getArbitrageSlipPriceChangeThresholdPct().toPlainString(),
                "current_slip_volume_drop_threshold_pct", cfg.getArbitrageSlipVolumeDropThresholdPct().toPlainString(),
                "current_slip_check_lookback_ticks", cfg.getArbitrageSlipLookbackTicks(),
                "timestamp", java.time.Instant.now().toString()
        ));
    }

    private void handleArbitrageStatus(Context ctx) {
        if (!requireAdminAuth(ctx)) return;
        Map<String, Object> status = new LinkedHashMap<>(arbitrageService.getStatusSnapshot());
        var cfg = plugin.getPluginConfig();
        status.put("min_gross_spread_pct", cfg.getArbitrageMinGrossSpreadPct().toPlainString());
        status.put("min_net_profit_pct", cfg.getArbitrageMinNetProfitPct().toPlainString());
        status.put("slip_price_change_threshold_pct", cfg.getArbitrageSlipPriceChangeThresholdPct().toPlainString());
        status.put("slip_volume_drop_threshold_pct", cfg.getArbitrageSlipVolumeDropThresholdPct().toPlainString());
        status.put("slip_check_lookback_ticks", cfg.getArbitrageSlipLookbackTicks());
        ctx.status(200).json(status);
    }

    private static BigDecimal parseStrictBigDecimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
