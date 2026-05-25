package com.gekiyabafx.arbitrage;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.engine.InsufficientBalanceException;
import com.gekiyabafx.engine.MatchResult;
import com.gekiyabafx.engine.MatchingEngine;
import com.gekiyabafx.model.*;
import com.gekiyabafx.storage.ExecutionRepository;
import com.gekiyabafx.storage.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin制御可能な裁定取引監視サービス。
 */
public final class ArbitrageService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final GekiyabaFXPlugin plugin;
    private final ExecutionRepository executionRepo;
    private final ArbitrageLogger arbLogger;

    private volatile boolean enabled;
    private volatile String serviceAccount;
    private volatile int checkIntervalTicks;

    private volatile long lastCheckEpochSec = 0L;
    private volatile LastExecution lastExecution;

    private final Deque<SkipRecord> recentSkips = new ArrayDeque<>();
    private final Deque<ExecutionRecord> recentExecutions = new ArrayDeque<>();
    private final Map<String, Boolean> slipFlags = new ConcurrentHashMap<>();
    private final Map<String, Deque<MarketSnapshot>> snapshots = new ConcurrentHashMap<>();

    private volatile BukkitTask task;
    private int tickCounter = 0;

    public ArbitrageService(GekiyabaFXPlugin plugin, ExecutionRepository executionRepo) {
        this.plugin = plugin;
        this.executionRepo = executionRepo;
        PluginConfig cfg = plugin.getPluginConfig();
        this.enabled = cfg.isArbitrageEnabled();
        this.serviceAccount = cfg.getArbitrageServiceAccount();
        this.checkIntervalTicks = cfg.getArbitrageCheckIntervalTicks();
        this.arbLogger = new ArbitrageLogger(cfg);
    }

    public synchronized void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::runLoopSafe,
                0L,
                Math.max(1, checkIntervalTicks)
        );
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    public int getCheckIntervalTicks() {
        return checkIntervalTicks;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized void setServiceAccount(String serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    public synchronized void setCheckIntervalTicks(int checkIntervalTicks) {
        if (checkIntervalTicks < 1) {
            throw new IllegalArgumentException("check_interval_ticks must be >= 1");
        }
        this.checkIntervalTicks = checkIntervalTicks;
        if (task != null) {
            start();
        }
    }

    public synchronized void applyRuntimeConfig(Boolean enabled, String serviceAccount, Integer checkIntervalTicks) {
        if (enabled != null) this.enabled = enabled;
        if (serviceAccount != null && !serviceAccount.isBlank()) this.serviceAccount = serviceAccount;
        if (checkIntervalTicks != null) setCheckIntervalTicks(checkIntervalTicks);
    }

    public List<String> getPairsUnderWatch() {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            return new ArrayList<>(sm.getData().getPairs().keySet());
        } finally {
            sm.unlock();
        }
    }

    public Map<String, Object> getStatusSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("service_account", serviceAccount);
        m.put("check_interval_ticks", checkIntervalTicks);
        m.put("pairs_under_watch", getPairsUnderWatch());
        m.put("last_check", lastCheckEpochSec == 0 ? null : Instant.ofEpochSecond(lastCheckEpochSec).toString());

        if (lastExecution != null) {
            Map<String, Object> le = new LinkedHashMap<>();
            le.put("pair", lastExecution.pairId());
            le.put("timestamp", Instant.ofEpochSecond(lastExecution.timestamp()).toString());
            le.put("status", lastExecution.status());
            le.put("order_ids", lastExecution.orderIds());
            m.put("last_execution", le);
        } else {
            m.put("last_execution", null);
        }

        List<Map<String, Object>> skips = new ArrayList<>();
        synchronized (recentSkips) {
            for (SkipRecord r : recentSkips) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pair", r.pairId());
                row.put("reason", r.reason());
                row.put("timestamp", Instant.ofEpochSecond(r.timestamp()).toString());
                skips.add(row);
            }
        }
        m.put("recent_skips", skips);

        List<Map<String, Object>> executions = new ArrayList<>();
        synchronized (recentExecutions) {
            for (ExecutionRecord r : recentExecutions) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pair", r.pairId());
                row.put("status", r.status());
                row.put("timestamp", Instant.ofEpochSecond(r.timestamp()).toString());
                row.put("order_ids", r.orderIds());
                row.put("quantity", r.quantity());
                row.put("spread_pct", r.spreadPct());
                row.put("net_profit_pct", r.netProfitPct());
                executions.add(row);
            }
        }
        m.put("recent_executions", executions);
        return m;
    }

    private void runLoopSafe() {
        try {
            runLoop();
        } catch (Exception e) {
            arbLogger.error("LOOP_ERROR", null, null, Map.of("error", e.getMessage()));
            plugin.getLogger().warning("[Arbitrage] LOOP_ERROR: " + e.getMessage());
        }
    }

    private void runLoop() {
        if (!enabled) return;

        List<String> pairIds = getPairsUnderWatch();
        if (pairIds.size() < 2) return;

        if (!isServiceAccountAllowed(serviceAccount)) {
            addSkip("-", "invalid_service_account");
            arbLogger.warn("SKIP_INVALID_ACCOUNT", "-", null, Map.of("service_account", serviceAccount));
            return;
        }

        int phaseCount = Math.max(1, plugin.getPluginConfig().getArbitragePhaseCount());
        int phase = plugin.getPluginConfig().isArbitrageFrameDistributionEnabled()
                ? (tickCounter % phaseCount)
                : 0;

        for (String pairId : pairIds) {
            switch (phase) {
                case 0 -> {
                    checkAndCancelPartialFills(pairId);
                    detectSlippage(pairId);
                }
                case 1 -> detectSlippage(pairId);
                default -> {
                    if (!slipFlags.getOrDefault(pairId, false)) {
                        executeArbitrage(pairId);
                    } else {
                        addSkip(pairId, "slip_detected");
                    }
                }
            }
        }

        tickCounter++;
        lastCheckEpochSec = Instant.now().getEpochSecond();
    }

    private void checkAndCancelPartialFills(String pairId) {
        PluginConfig cfg = plugin.getPluginConfig();

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Pair pair = sm.getData().getPairs().get(pairId);
            if (pair == null || pair.getOrderBook() == null) return;
            if (pair.getOrderBook().getBids().isEmpty() || pair.getOrderBook().getAsks().isEmpty()) return;

            BigDecimal bestBid = pair.getOrderBook().getBids().get(0).getPrice();
            BigDecimal bestAsk = pair.getOrderBook().getAsks().get(0).getPrice();
            if (bestBid == null || bestAsk == null) return;

            BigDecimal mid = bestBid.add(bestAsk).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
            if (mid.compareTo(BigDecimal.ZERO) <= 0) return;

            BigDecimal spreadPct = pct(bestAsk.subtract(bestBid), mid);

            cancelIfDiverged(pairId, pair, pair.getOrderBook().getBids(), mid, spreadPct,
                    cfg.getArbitragePartialSpreadDivergenceThresholdPct(),
                    cfg.getArbitragePartialMidpriceProximityThresholdPct(), sm);
            cancelIfDiverged(pairId, pair, pair.getOrderBook().getAsks(), mid, spreadPct,
                    cfg.getArbitragePartialSpreadDivergenceThresholdPct(),
                    cfg.getArbitragePartialMidpriceProximityThresholdPct(), sm);
        } finally {
            sm.unlock();
        }
    }

    private void cancelIfDiverged(String pairId,
                                  Pair pair,
                                  List<Order> orders,
                                  BigDecimal mid,
                                  BigDecimal spreadPct,
                                  BigDecimal divergenceThreshold,
                                  BigDecimal proximityThreshold,
                                  StorageManager sm) {
        Iterator<Order> it = orders.iterator();
        while (it.hasNext()) {
            Order o = it.next();
            if (!serviceAccount.equals(o.getUuid())) continue;
            if (o.getStatus() != OrderStatus.PARTIALLY_FILLED) continue;
            if (o.getPrice() == null) continue;

            BigDecimal distancePct = pct(o.getPrice().subtract(mid).abs(), mid);
            BigDecimal threshold = spreadPct.add(divergenceThreshold);

            if (distancePct.compareTo(threshold) > 0 && distancePct.compareTo(proximityThreshold) > 0) {
                it.remove();
                MatchingEngine.cancelOrder(pairId, pair, o, sm.getData(), plugin.getPluginConfig());
                sm.markDirty();
                arbLogger.info("PARTIAL_FILL_CANCEL", pairId, o.getOrderId(), Map.of(
                        "remaining_amount", o.getRemainingAmount().toPlainString(),
                        "order_distance_pct", distancePct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        "mid_price", mid.setScale(4, RoundingMode.HALF_UP).toPlainString()
                ));
            }
        }
    }

    private void detectSlippage(String pairId) {
        PluginConfig cfg = plugin.getPluginConfig();

        BigDecimal currentPrice;
        BigDecimal askVolume;
        long now = Instant.now().getEpochSecond();

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Pair pair = sm.getData().getPairs().get(pairId);
            if (pair == null || pair.getOrderBook() == null) return;
            currentPrice = pair.getLastPrice() != null ? pair.getLastPrice() : bestMid(pair);
            askVolume = sumAmount(pair.getOrderBook().getAsks());
        } finally {
            sm.unlock();
        }

        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            slipFlags.put(pairId, false);
            return;
        }

        Deque<MarketSnapshot> q = snapshots.computeIfAbsent(pairId, k -> new ArrayDeque<>());
        q.addLast(new MarketSnapshot(now, currentPrice, askVolume));

        int lookbackSec = Math.max(1, cfg.getArbitrageSlipLookbackTicks() / 20);
        while (!q.isEmpty() && now - q.peekFirst().timestamp() > Math.max(lookbackSec * 5L, 120L)) {
            q.removeFirst();
        }

        MarketSnapshot base = null;
        for (MarketSnapshot s : q) {
            if (now - s.timestamp() >= lookbackSec) {
                base = s;
                break;
            }
        }

        if (base == null) {
            slipFlags.put(pairId, false);
            return;
        }

        BigDecimal priceChangePct = pct(currentPrice.subtract(base.price()).abs(), base.price());
        if (priceChangePct.compareTo(cfg.getArbitrageSlipPriceChangeThresholdPct()) > 0) {
            slipFlags.put(pairId, true);
            addSkip(pairId, "slip_detected_price");
            arbLogger.warn("SLIP_DETECTED_PRICE", pairId, null, Map.of(
                    "current", currentPrice.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    "previous", base.price().setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    "change_pct", priceChangePct.setScale(4, RoundingMode.HALF_UP).toPlainString()
            ));
            return;
        }

        if (base.askVolume().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dropPct = base.askVolume().subtract(askVolume)
                    .multiply(HUNDRED)
                    .divide(base.askVolume(), 8, RoundingMode.HALF_UP);
            if (dropPct.compareTo(cfg.getArbitrageSlipVolumeDropThresholdPct()) > 0) {
                slipFlags.put(pairId, true);
                addSkip(pairId, "slip_detected_volume");
                arbLogger.warn("SLIP_DETECTED_VOLUME", pairId, null, Map.of(
                        "old_volume", base.askVolume().setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        "current_volume", askVolume.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        "drop_pct", dropPct.setScale(4, RoundingMode.HALF_UP).toPlainString()
                ));
                return;
            }
        }

        slipFlags.put(pairId, false);
    }

    private void executeArbitrage(String pairId) {
        PluginConfig cfg = plugin.getPluginConfig();

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            StorageData data = sm.getData();
            Pair pair = data.getPairs().get(pairId);
            if (pair == null || !pair.isEnabled() || pair.getOrderBook() == null) return;
            if (pair.getOrderBook().getBids().isEmpty() || pair.getOrderBook().getAsks().isEmpty()) return;

            Order bestBidOrder = pair.getOrderBook().getBids().get(0);
            Order bestAskOrder = pair.getOrderBook().getAsks().get(0);
            BigDecimal bestBid = bestBidOrder.getPrice();
            BigDecimal bestAsk = bestAskOrder.getPrice();
            if (bestBid == null || bestAsk == null) return;

            BigDecimal mid = bestBid.add(bestAsk).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
            if (mid.compareTo(BigDecimal.ZERO) <= 0) return;

            // 実際に利益が出る交差板条件: bid > ask
            BigDecimal grossSpreadPct = pct(bestBid.subtract(bestAsk), mid);
            if (grossSpreadPct.compareTo(cfg.getArbitrageMinGrossSpreadPct()) < 0) {
                arbLogger.debug("SKIP_LOW_SPREAD", pairId, null, Map.of(
                        "spread_pct", grossSpreadPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        "min_required", cfg.getArbitrageMinGrossSpreadPct().toPlainString()
                ));
                addSkip(pairId, "low_spread");
                return;
            }

            BigDecimal feeCostPct = cfg.getFeeTaker().add(cfg.getFeeMaker()).multiply(HUNDRED);
            BigDecimal netPct = grossSpreadPct.subtract(feeCostPct);
            if (netPct.compareTo(cfg.getArbitrageMinNetProfitPct()) < 0) {
                arbLogger.debug("SKIP_LOW_PROFIT", pairId, null, Map.of(
                        "net_profit_pct", netPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        "min_required", cfg.getArbitrageMinNetProfitPct().toPlainString()
                ));
                addSkip(pairId, "low_profit");
                return;
            }

            PlayerData acct = data.getPlayers().get(serviceAccount);
            if (acct == null) {
                acct = new PlayerData("[SERVICE] " + serviceAccount.substring(4));
                data.getPlayers().put(serviceAccount, acct);
            }

            BigDecimal quoteBal = acct.getHotBalance(pair.getQuote());
            BigDecimal baseBal = acct.getHotBalance(pair.getBase());
            BigDecimal maxBuyQty = quoteBal.divide(bestAsk, 4, RoundingMode.DOWN);
            BigDecimal maxSellQty = baseBal.setScale(4, RoundingMode.DOWN);
            BigDecimal depthAskQty = bestAskOrder.getRemainingAmount().setScale(4, RoundingMode.DOWN);
            BigDecimal depthBidQty = bestBidOrder.getRemainingAmount().setScale(4, RoundingMode.DOWN);

            BigDecimal tradeQty = min(maxBuyQty, maxSellQty, depthAskQty, depthBidQty);
            if (tradeQty.compareTo(BigDecimal.ZERO) <= 0) {
                addSkip(pairId, "insufficient_balance");
                arbLogger.debug("SKIP_INSUFFICIENT_BALANCE", pairId, null, Map.of());
                return;
            }

            if (tradeQty.compareTo(pair.getMinAmount()) < 0) {
                addSkip(pairId, "below_min_amount");
                return;
            }

            Order buy = new Order();
            buy.setOrderId(UUID.randomUUID().toString());
            buy.setUuid(serviceAccount);
            buy.setType(OrderType.LIMIT);
            buy.setSide(OrderSide.BUY);
            buy.setPrice(bestAsk);
            buy.setAmount(tradeQty);
            buy.setFilled(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            buy.setStatus(OrderStatus.OPEN);
            buy.setCreatedAt(Instant.now().getEpochSecond());

            Order sell = new Order();
            sell.setOrderId(UUID.randomUUID().toString());
            sell.setUuid(serviceAccount);
            sell.setType(OrderType.LIMIT);
            sell.setSide(OrderSide.SELL);
            sell.setPrice(bestBid);
            sell.setAmount(tradeQty);
            sell.setFilled(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            sell.setStatus(OrderStatus.OPEN);
            sell.setCreatedAt(Instant.now().getEpochSecond());

            try {
                MatchResult buyResult = MatchingEngine.placeOrder(pairId, pair, buy, data, cfg);
                for (Execution ex : buyResult.getExecutions()) {
                    executionRepo.insert(pairId, ex);
                }

                MatchResult sellResult = MatchingEngine.placeOrder(pairId, pair, sell, data, cfg);
                for (Execution ex : sellResult.getExecutions()) {
                    executionRepo.insert(pairId, ex);
                }

                sm.markDirty();
                lastExecution = new LastExecution(pairId, Instant.now().getEpochSecond(), "executed",
                        List.of(buy.getOrderId(), sell.getOrderId()));
                addExecution(pairId, "executed", List.of(buy.getOrderId(), sell.getOrderId()),
                    tradeQty, grossSpreadPct, netPct);

                arbLogger.info("EXECUTED", pairId, null, Map.of(
                        "buy_order_id", buy.getOrderId(),
                        "sell_order_id", sell.getOrderId(),
                        "quantity", tradeQty.toPlainString(),
                        "spread_pct", grossSpreadPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        "net_profit_pct", netPct.setScale(4, RoundingMode.HALF_UP).toPlainString()
                ));
            } catch (InsufficientBalanceException e) {
                addSkip(pairId, "insufficient_balance");
                arbLogger.error("EXECUTION_ERROR", pairId, null, Map.of("error", e.getMessage()));
            }
        } finally {
            sm.unlock();
        }
    }

    private void addSkip(String pairId, String reason) {
        synchronized (recentSkips) {
            recentSkips.addFirst(new SkipRecord(pairId, reason, Instant.now().getEpochSecond()));
            while (recentSkips.size() > 20) {
                recentSkips.removeLast();
            }
        }
    }

    private void addExecution(String pairId,
                              String status,
                              List<String> orderIds,
                              BigDecimal quantity,
                              BigDecimal spreadPct,
                              BigDecimal netProfitPct) {
        synchronized (recentExecutions) {
            recentExecutions.addFirst(new ExecutionRecord(
                    pairId,
                    Instant.now().getEpochSecond(),
                    status,
                    orderIds,
                    quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    spreadPct.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    netProfitPct.setScale(4, RoundingMode.HALF_UP).toPlainString()
            ));
            while (recentExecutions.size() > 20) {
                recentExecutions.removeLast();
            }
        }
    }

    private boolean isServiceAccountAllowed(String accountId) {
        if (accountId == null || !accountId.startsWith("svc:")) return false;
        String name = accountId.substring(4);
        return plugin.getPluginConfig().getServiceAccounts().contains(name);
    }

    private static BigDecimal bestMid(Pair pair) {
        if (pair.getOrderBook().getBids().isEmpty() || pair.getOrderBook().getAsks().isEmpty()) {
            return null;
        }
        BigDecimal bid = pair.getOrderBook().getBids().get(0).getPrice();
        BigDecimal ask = pair.getOrderBook().getAsks().get(0).getPrice();
        if (bid == null || ask == null) return null;
        return bid.add(ask).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumAmount(List<Order> orders) {
        BigDecimal s = BigDecimal.ZERO;
        for (Order o : orders) {
            if (o.getRemainingAmount() != null) {
                s = s.add(o.getRemainingAmount());
            }
        }
        return s.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return numerator.multiply(HUNDRED).divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal min(BigDecimal... values) {
        BigDecimal x = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i].compareTo(x) < 0) x = values[i];
        }
        return x;
    }

    private record MarketSnapshot(long timestamp, BigDecimal price, BigDecimal askVolume) {}
    private record SkipRecord(String pairId, String reason, long timestamp) {}
    private record ExecutionRecord(String pairId,
                                   long timestamp,
                                   String status,
                                   List<String> orderIds,
                                   String quantity,
                                   String spreadPct,
                                   String netProfitPct) {}
    private record LastExecution(String pairId, long timestamp, String status, List<String> orderIds) {}
}
