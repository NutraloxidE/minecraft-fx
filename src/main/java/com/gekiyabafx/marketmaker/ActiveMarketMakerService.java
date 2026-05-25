package com.gekiyabafx.marketmaker;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.engine.InsufficientBalanceException;
import com.gekiyabafx.engine.MatchResult;
import com.gekiyabafx.engine.MatchingEngine;
import com.gekiyabafx.model.Execution;
import com.gekiyabafx.model.Order;
import com.gekiyabafx.model.OrderSide;
import com.gekiyabafx.model.OrderStatus;
import com.gekiyabafx.model.OrderType;
import com.gekiyabafx.model.Pair;
import com.gekiyabafx.model.PlayerData;
import com.gekiyabafx.model.StorageData;
import com.gekiyabafx.storage.ExecutionRepository;
import com.gekiyabafx.storage.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 過疎時の板流動性維持を目的とした自律マーケットメイクサービス。
 */
public final class ActiveMarketMakerService {

    public static final String SERVICE_ACCOUNT_ID = "svc:gekiyaba_mm";
    private static volatile String activeServiceAccountId = SERVICE_ACCOUNT_ID;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final int PASSIVE_OFFSET_PCT = 2; // center +-2%
    private static final int SPREAD_WIDE_THRESHOLD_PCT = 5;
    private static final int SPREAD_TIGHT_THRESHOLD_BPS = 20; // 0.2%
    private static final int MAX_SQUEEZE_SEC = 300;

    private static final int MIN_RANDOM_INTERVAL_SEC = 3;
    private static final int MAX_RANDOM_INTERVAL_SEC = 8;
    private static final int BACKSTEP_PROB_PCT = 5;

    private static final BigDecimal MIN_SQUEEZE_STEP_PCT = new BigDecimal("0.05");
    private static final BigDecimal MAX_SQUEEZE_STEP_PCT = new BigDecimal("0.18");

    private static final BigDecimal BASE_LOW_BIAS = new BigDecimal("0.30");
    private static final BigDecimal BASE_HIGH_BIAS = new BigDecimal("0.70");

    private static final int MAX_RECENT_LOGS = 30;
    private static final int DEFAULT_LOOP_INTERVAL_TICKS = 20;

    private final GekiyabaFXPlugin plugin;
    private final ExecutionRepository executionRepo;

    private final Map<String, PairState> states = new ConcurrentHashMap<>();
    private final Deque<LogEntry> recentLogs = new LinkedList<>();

    private volatile int loopIntervalTicks = DEFAULT_LOOP_INTERVAL_TICKS;
    private volatile BukkitTask task;

    public ActiveMarketMakerService(GekiyabaFXPlugin plugin, ExecutionRepository executionRepo) {
        this.plugin = plugin;
        this.executionRepo = executionRepo;
    }

    public synchronized void start() {
        stop();
        logInfo("START", "ActiveMM を開始しました");
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::runSafe,
                0L,
                loopIntervalTicks
        );
    }

    public synchronized void stop() {
        clearOwnOrders();
        logInfo("STOP", "ActiveMM を停止しました");
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public synchronized void setLoopIntervalTicks(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("loopIntervalTicks must be at least 1");
        }
        loopIntervalTicks = ticks;
    }

    public synchronized void setServiceAccountId(String serviceAccountId) {
        if (serviceAccountId == null || serviceAccountId.isBlank()) {
            throw new IllegalArgumentException("serviceAccountId must not be blank");
        }
        activeServiceAccountId = serviceAccountId;
    }

    public String getServiceAccountId() {
        return activeServiceAccountId;
    }

    public static boolean isMarketMakerAccount(String uuid) {
        return uuid != null && uuid.equals(activeServiceAccountId);
    }

    public int getLoopIntervalTicks() {
        return loopIntervalTicks;
    }

    public boolean isRunning() {
        return task != null;
    }

    public Map<String, Object> getStatusSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("running", isRunning());
        snapshot.put("service_account", getServiceAccountId());
        snapshot.put("current_loop_interval_ticks", loopIntervalTicks);

        int trackedPairs = 0;
        int passive = 0;
        int squeezing = 0;
        int matching = 0;
        int ownedOrders = 0;

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();
            for (Map.Entry<String, Pair> entry : data.getPairs().entrySet()) {
                Pair pair = entry.getValue();
                if (pair == null || !pair.isEnabled() || pair.getOrderBook() == null) {
                    continue;
                }
                trackedPairs++;

                PairState state = states.get(entry.getKey());
                if (state == null) {
                    passive++;
                } else {
                    switch (state.state) {
                        case PASSIVE -> passive++;
                        case SQUEEZING -> squeezing++;
                        case MATCHING -> matching++;
                    }
                }

                ownedOrders += countOwnOrders(pair.getOrderBook().getBids());
                ownedOrders += countOwnOrders(pair.getOrderBook().getAsks());
            }
        } finally {
            sm.unlock();
        }

        snapshot.put("tracked_pairs", trackedPairs);
        snapshot.put("passive_pairs", passive);
        snapshot.put("squeezing_pairs", squeezing);
        snapshot.put("matching_pairs", matching);
        snapshot.put("owned_orders", ownedOrders);
        snapshot.put("recent_logs", snapshotRecentLogs());
        snapshot.put("timestamp", Instant.now().toString());
        return snapshot;
    }

    private void runSafe() {
        try {
            runLoop();
        } catch (Exception e) {
            logWarn(null, "LOOP_ERROR", Map.of("error", e.getMessage()));
        }
    }

    private void runLoop() {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();
            long now = Instant.now().getEpochSecond();

            for (Map.Entry<String, Pair> entry : data.getPairs().entrySet()) {
                String pairId = entry.getKey();
                Pair pair = entry.getValue();
                if (pair == null || !pair.isEnabled() || pair.getOrderBook() == null) {
                    continue;
                }

                PairState state = states.computeIfAbsent(pairId, k -> new PairState());
                ensureServiceAccount(data, pair);

                switch (state.state) {
                    case PASSIVE -> handlePassive(pairId, pair, data, state, now, sm);
                    case SQUEEZING -> handleSqueezing(pairId, pair, data, state, now, sm);
                    case MATCHING -> handleMatching(pairId, pair, data, state, now, sm);
                }
            }
        } finally {
            sm.unlock();
        }
    }

    private void handlePassive(String pairId,
                               Pair pair,
                               StorageData data,
                               PairState st,
                               long now,
                               StorageManager sm) {
        BigDecimal center = resolveCenterPrice(pair);
        if (center == null || center.compareTo(ZERO) <= 0) {
            return;
        }
        st.centerPrice = center;

        BigDecimal qty = resolveAdaptiveAmount(pairId, pair, center, data);
        if (qty.compareTo(pair.getMinAmount()) < 0) {
            return;
        }

        BigDecimal bidPrice = applyPct(center, BigDecimal.valueOf(-PASSIVE_OFFSET_PCT));
        BigDecimal askPrice = applyPct(center, BigDecimal.valueOf(PASSIVE_OFFSET_PCT));

        maintainTwoSidedOrders(pairId, pair, data, bidPrice, askPrice, qty, sm);
        logInfo("PASSIVE_REFRESH", pairId, Map.of(
                "center", center.toPlainString(),
                "bid", bidPrice.toPlainString(),
                "ask", askPrice.toPlainString(),
                "qty", qty.toPlainString()
        ));

        BigDecimal marketSpreadPct = externalSpreadPct(pair);
        if (marketSpreadPct != null && marketSpreadPct.compareTo(BigDecimal.valueOf(SPREAD_WIDE_THRESHOLD_PCT)) > 0) {
            if (st.wideStartSec == 0L) {
                st.wideStartSec = now;
            }
            if (now - st.wideStartSec >= 20) {
                st.state = MMState.SQUEEZING;
                st.phaseStartedSec = now;
                st.nextSqueezeActionSec = now;
                logInfo(pairId, "PHASE_SQUEEZE", Map.of("spread_pct", marketSpreadPct.toPlainString()));
            }
        } else {
            st.wideStartSec = 0L;
        }
    }

    private void handleSqueezing(String pairId,
                                 Pair pair,
                                 StorageData data,
                                 PairState st,
                                 long now,
                                 StorageManager sm) {
        BigDecimal center = st.centerPrice != null ? st.centerPrice : resolveCenterPrice(pair);
        if (center == null || center.compareTo(ZERO) <= 0) {
            st.state = MMState.PASSIVE;
            return;
        }
        st.centerPrice = center;

        if (now < st.nextSqueezeActionSec) {
            if (isTightEnough(pair)) {
                st.state = MMState.MATCHING;
            }
            return;
        }

        BigDecimal qty = resolveAdaptiveAmount(pairId, pair, center, data);
        if (qty.compareTo(pair.getMinAmount()) < 0) {
            st.state = MMState.PASSIVE;
            return;
        }

        List<Order> ownBids = ownOrders(pair.getOrderBook().getBids());
        List<Order> ownAsks = ownOrders(pair.getOrderBook().getAsks());

        BigDecimal currentBid = ownBids.isEmpty() ? applyPct(center, BigDecimal.valueOf(-PASSIVE_OFFSET_PCT)) : ownBids.get(0).getPrice();
        BigDecimal currentAsk = ownAsks.isEmpty() ? applyPct(center, BigDecimal.valueOf(PASSIVE_OFFSET_PCT)) : ownAsks.get(0).getPrice();

        BigDecimal stepPct = randomStepPct();
        boolean backstep = ThreadLocalRandom.current().nextInt(100) < BACKSTEP_PROB_PCT;

        BigDecimal baseBias = baseInventoryBias(pair, data.getPlayers().get(getServiceAccountId()), center);
        boolean baseTooLow = baseBias.compareTo(BASE_LOW_BIAS) < 0;
        boolean baseTooHigh = baseBias.compareTo(BASE_HIGH_BIAS) > 0;

        BigDecimal nextBid;
        BigDecimal nextAsk;
        if (backstep) {
            nextBid = applyPct(currentBid, stepPct.negate());
            nextAsk = applyPct(currentAsk, stepPct);
        } else {
            nextBid = applyPct(currentBid, stepPct);
            nextAsk = applyPct(currentAsk, stepPct.negate());
        }

        if (baseTooLow && nextBid.compareTo(currentBid) > 0) {
            nextBid = currentBid;
        }
        if (baseTooHigh && nextAsk.compareTo(currentAsk) < 0) {
            nextAsk = currentAsk;
        }

        if (nextBid.compareTo(center) >= 0) {
            nextBid = applyPct(center, new BigDecimal("-0.02"));
        }
        if (nextAsk.compareTo(center) <= 0) {
            nextAsk = applyPct(center, new BigDecimal("0.02"));
        }

        maintainTwoSidedOrders(pairId, pair, data, nextBid, nextAsk, qty, sm);
        logInfo("SQUEEZE_STEP", pairId, Map.of(
                "bid", nextBid.toPlainString(),
                "ask", nextAsk.toPlainString(),
                "qty", qty.toPlainString(),
                "backstep", String.valueOf(backstep)
        ));

        st.nextSqueezeActionSec = now + ThreadLocalRandom.current().nextInt(MIN_RANDOM_INTERVAL_SEC, MAX_RANDOM_INTERVAL_SEC + 1);

        if (isTightEnough(pair) || now - st.phaseStartedSec >= MAX_SQUEEZE_SEC) {
            st.state = MMState.MATCHING;
            logInfo(pairId, "PHASE_MATCH", Map.of("center", center.toPlainString()));
        }
    }

    private void handleMatching(String pairId,
                                Pair pair,
                                StorageData data,
                                PairState st,
                                long now,
                                StorageManager sm) {
        BigDecimal center = st.centerPrice != null ? st.centerPrice : resolveCenterPrice(pair);
        if (center == null || center.compareTo(ZERO) <= 0) {
            st.state = MMState.PASSIVE;
            return;
        }

        cancelAllOwnOrders(pairId, pair, data, sm);

        BigDecimal qty = resolveAdaptiveAmount(pairId, pair, center, data);
        if (qty.compareTo(pair.getMinAmount()) < 0) {
            st.state = MMState.PASSIVE;
            return;
        }

        try {
            Order buy = newOrder(OrderSide.BUY, center, qty, now);
            MatchResult br = MatchingEngine.placeOrder(pairId, pair, buy, data, plugin.getPluginConfig());
            for (Execution ex : br.getExecutions()) {
                executionRepo.insert(pairId, ex);
            }

            Order sell = newOrder(OrderSide.SELL, center, qty, now);
            MatchResult sr = MatchingEngine.placeOrder(pairId, pair, sell, data, plugin.getPluginConfig());
            for (Execution ex : sr.getExecutions()) {
                executionRepo.insert(pairId, ex);
            }
            sm.markDirty();
            logInfo(pairId, "MATCH_SELF_TRADE", Map.of(
                    "price", center.toPlainString(),
                    "qty", qty.toPlainString(),
                    "buy_executions", String.valueOf(br.getExecutions().size()),
                    "sell_executions", String.valueOf(sr.getExecutions().size())
            ));
        } catch (InsufficientBalanceException e) {
            logWarn(pairId, "MATCH_SKIP", Map.of("reason", e.getMessage()));
        }

        st.state = MMState.PASSIVE;
        st.wideStartSec = 0L;
        st.phaseStartedSec = 0L;
        st.nextSqueezeActionSec = 0L;
    }

    private void ensureServiceAccount(StorageData data, Pair pair) {
        PlayerData pd = data.getPlayers().get(getServiceAccountId());
        if (pd == null) {
            pd = new PlayerData("[SERVICE] gekiyaba_mm");
            data.getPlayers().put(getServiceAccountId(), pd);
            logInfo(pairIdForLog(pair), "SERVICE_ACCOUNT_CREATED", Map.of("account", getServiceAccountId()));
        }
    }

    private void maintainTwoSidedOrders(String pairId,
                                        Pair pair,
                                        StorageData data,
                                        BigDecimal bidPrice,
                                        BigDecimal askPrice,
                                        BigDecimal amount,
                                        StorageManager sm) {
        cancelAllOwnOrders(pairId, pair, data, sm);

        long now = Instant.now().getEpochSecond();
        try {
            Order buy = newOrder(OrderSide.BUY, bidPrice, amount, now);
            MatchResult buyResult = MatchingEngine.placeOrder(pairId, pair, buy, data, plugin.getPluginConfig());
            for (Execution ex : buyResult.getExecutions()) {
                executionRepo.insert(pairId, ex);
            }

            Order sell = newOrder(OrderSide.SELL, askPrice, amount, now);
            MatchResult sellResult = MatchingEngine.placeOrder(pairId, pair, sell, data, plugin.getPluginConfig());
            for (Execution ex : sellResult.getExecutions()) {
                executionRepo.insert(pairId, ex);
            }
            sm.markDirty();
        } catch (InsufficientBalanceException e) {
            logWarn(pairId, "PLACE_SKIP", Map.of("reason", e.getMessage()));
        }
    }

    private void cancelAllOwnOrders(String pairId, Pair pair, StorageData data, StorageManager sm) {
        List<Order> bids = pair.getOrderBook().getBids();
        List<Order> asks = pair.getOrderBook().getAsks();

        bids.removeIf(o -> cancelOwnOrder(pairId, pair, o, data, sm));
        asks.removeIf(o -> cancelOwnOrder(pairId, pair, o, data, sm));
    }

    private void clearOwnOrders() {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();
            for (Map.Entry<String, Pair> entry : data.getPairs().entrySet()) {
                Pair pair = entry.getValue();
                if (pair == null || pair.getOrderBook() == null) {
                    continue;
                }
                cancelAllOwnOrders(entry.getKey(), pair, data, sm);
            }
            sm.markDirty();
        } finally {
            sm.unlock();
        }
    }

    private List<Map<String, Object>> snapshotRecentLogs() {
        synchronized (recentLogs) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (LogEntry entry : recentLogs) {
                out.add(entry.toMap());
            }
            return out;
        }
    }

    private void logInfo(String action, String message) {
        addRecentLog("INFO", action, message);
    }

    private void logInfo(String pairId, String action, Map<String, Object> details) {
        String message = format(pairId, action, details);
        addRecentLog("INFO", action, message);
    }

    private void logWarn(String pairId, String action, Map<String, Object> details) {
        String message = format(pairId, action, details);
        addRecentLog("WARN", action, message);
    }

    private void addRecentLog(String level, String action, String message) {
        synchronized (recentLogs) {
            recentLogs.addLast(new LogEntry(Instant.now().toString(), level, action, message));
            while (recentLogs.size() > MAX_RECENT_LOGS) {
                recentLogs.removeFirst();
            }
        }
    }

    private static String format(String pairId, String action, Map<String, Object> details) {
        return "[ActiveMM] [" + (pairId == null ? "-" : pairId) + "] [" + action + "] "
                + (details == null ? "{}" : details.toString());
    }

    private static String pairIdForLog(Pair pair) {
        if (pair == null || pair.getBase() == null || pair.getQuote() == null) {
            return "-";
        }
        return pair.getBase().toUpperCase() + "/" + pair.getQuote().toUpperCase();
    }

    private static int countOwnOrders(List<Order> orders) {
        int count = 0;
        for (Order order : orders) {
            if (isMarketMakerAccount(order.getUuid())
                    && (order.getStatus() == OrderStatus.OPEN || order.getStatus() == OrderStatus.PARTIALLY_FILLED)) {
                count++;
            }
        }
        return count;
    }

    private boolean cancelOwnOrder(String pairId, Pair pair, Order o, StorageData data, StorageManager sm) {
        if (!isMarketMakerAccount(o.getUuid())) {
            return false;
        }
        if (o.getStatus() == OrderStatus.OPEN || o.getStatus() == OrderStatus.PARTIALLY_FILLED) {
            MatchingEngine.cancelOrder(pairId, pair, o, data, plugin.getPluginConfig());
            sm.markDirty();
        }
        return true;
    }

    private static List<Order> ownOrders(List<Order> src) {
        List<Order> out = new ArrayList<>();
        for (Order o : src) {
            if (isMarketMakerAccount(o.getUuid())) {
                out.add(o);
            }
        }
        out.sort(Comparator.comparing(Order::getCreatedAt));
        return out;
    }

    private static boolean isTightEnough(Pair pair) {
        BigDecimal spread = externalSpreadPct(pair);
        return spread != null && spread.multiply(new BigDecimal("100")).compareTo(BigDecimal.valueOf(SPREAD_TIGHT_THRESHOLD_BPS)) <= 0;
    }

    private static BigDecimal externalSpreadPct(Pair pair) {
        Order bestBid = null;
        for (Order o : pair.getOrderBook().getBids()) {
            if (!isMarketMakerAccount(o.getUuid()) && o.getPrice() != null) {
                bestBid = o;
                break;
            }
        }
        Order bestAsk = null;
        for (Order o : pair.getOrderBook().getAsks()) {
            if (!isMarketMakerAccount(o.getUuid()) && o.getPrice() != null) {
                bestAsk = o;
                break;
            }
        }
        if (bestBid == null || bestAsk == null) return null;

        BigDecimal mid = bestBid.getPrice().add(bestAsk.getPrice()).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
        if (mid.compareTo(BigDecimal.ZERO) <= 0) return null;
        return bestAsk.getPrice().subtract(bestBid.getPrice()).multiply(HUNDRED).divide(mid, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveCenterPrice(Pair pair) {
        if (pair.getLastPrice() != null && pair.getLastPrice().compareTo(ZERO) > 0) {
            return pair.getLastPrice().setScale(4, RoundingMode.HALF_UP);
        }

        Order bestBid = null;
        for (Order o : pair.getOrderBook().getBids()) {
            if (!isMarketMakerAccount(o.getUuid()) && o.getPrice() != null) {
                bestBid = o;
                break;
            }
        }
        Order bestAsk = null;
        for (Order o : pair.getOrderBook().getAsks()) {
            if (!isMarketMakerAccount(o.getUuid()) && o.getPrice() != null) {
                bestAsk = o;
                break;
            }
        }

        if (bestBid == null || bestAsk == null) return null;
        return bestBid.getPrice().add(bestAsk.getPrice()).divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveAdaptiveAmount(String pairId, Pair pair, BigDecimal center, StorageData data) {
        long since = Instant.now().minusSeconds(24L * 60 * 60).getEpochSecond();
        List<Execution> execs = executionRepo.findByPairSince(pairId, since);

        BigDecimal vol24h = ZERO;
        for (Execution ex : execs) {
            if (ex.getAmount() != null) {
                vol24h = vol24h.add(ex.getAmount()).setScale(4, RoundingMode.HALF_UP);
            }
        }

        BigDecimal target = vol24h.multiply(new BigDecimal("0.01")).setScale(4, RoundingMode.HALF_UP);
        if (target.compareTo(pair.getMinAmount()) < 0) {
            target = pair.getMinAmount().setScale(4, RoundingMode.HALF_UP);
        }

        PlayerData pd = data.getPlayers().get(getServiceAccountId());
        if (pd == null) return ZERO;

        BigDecimal maxSell = pd.getHotBalance(pair.getBase()).setScale(4, RoundingMode.DOWN);
        BigDecimal maxBuy = pd.getHotBalance(pair.getQuote())
                .divide(center, 4, RoundingMode.DOWN)
                .setScale(4, RoundingMode.DOWN);

        BigDecimal qty = target.min(maxSell).min(maxBuy).setScale(4, RoundingMode.DOWN);
        return qty.max(ZERO);
    }

    private static BigDecimal baseInventoryBias(Pair pair, PlayerData pd, BigDecimal centerPrice) {
        if (pd == null || centerPrice == null || centerPrice.compareTo(ZERO) <= 0) {
            return new BigDecimal("0.5");
        }

        BigDecimal baseQty = pd.getHotBalance(pair.getBase()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal quoteQty = pd.getHotBalance(pair.getQuote()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal baseValueInQuote = baseQty.multiply(centerPrice).setScale(8, RoundingMode.HALF_UP);

        BigDecimal total = baseValueInQuote.add(quoteQty);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.5");
        }
        return baseValueInQuote.divide(total, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal randomStepPct() {
        BigDecimal span = MAX_SQUEEZE_STEP_PCT.subtract(MIN_SQUEEZE_STEP_PCT);
        BigDecimal r = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0.0, 1.0));
        return MIN_SQUEEZE_STEP_PCT.add(span.multiply(r)).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal applyPct(BigDecimal price, BigDecimal pct) {
        return price
                .multiply(BigDecimal.ONE.add(pct.divide(HUNDRED, 8, RoundingMode.HALF_UP)))
                .setScale(4, RoundingMode.HALF_UP)
                .max(new BigDecimal("0.0001"));
    }

    private static Order newOrder(OrderSide side, BigDecimal price, BigDecimal amount, long now) {
        Order o = new Order();
        o.setOrderId(UUID.randomUUID().toString());
        o.setUuid(activeServiceAccountId);
        o.setType(OrderType.LIMIT);
        o.setSide(side);
        o.setPrice(price.setScale(4, RoundingMode.HALF_UP));
        o.setAmount(amount.setScale(4, RoundingMode.HALF_UP));
        o.setFilled(ZERO);
        o.setStatus(OrderStatus.OPEN);
        o.setCreatedAt(now);
        return o;
    }

    private enum MMState {
        PASSIVE,
        SQUEEZING,
        MATCHING
    }

    private static final class PairState {
        private MMState state = MMState.PASSIVE;
        private long phaseStartedSec;
        private long wideStartSec;
        private long nextSqueezeActionSec;
        private BigDecimal centerPrice;
    }

    private record LogEntry(String timestamp, String level, String action, String message) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", timestamp);
            m.put("level", level);
            m.put("action", action);
            m.put("message", message);
            return m;
        }
    }
}
