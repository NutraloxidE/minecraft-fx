package com.gekiyabafx.listener;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.atm.AtmSessionManager;
import com.gekiyabafx.auth.OtpManager;
import com.gekiyabafx.config.PluginConfig;
import com.gekiyabafx.model.AtmData;
import com.gekiyabafx.model.PlayerData;
import com.gekiyabafx.model.StorageData;
import com.gekiyabafx.storage.StorageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.block.BlockFace;

import java.util.Collection;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * ATM 看板の右クリックと 3 ブロック離脱を処理する。
 */
public final class AtmSignListener implements Listener {

    private static final int MAX_ATMS_PER_OWNER = 5;
    private static final double MAX_DISTANCE_BLOCKS = 3.0;
    private static final long OCCUPY_TIMEOUT_MS = 600_000L;
    private static final String OCCUPIED_MARKER_TAG = "atm-occupied-marker";

    private final GekiyabaFXPlugin plugin;
    private final OtpManager playerOtpManager;
    private final AtmSessionManager atmSessionManager;

    public AtmSignListener(
            GekiyabaFXPlugin plugin,
            OtpManager playerOtpManager,
            AtmSessionManager atmSessionManager
    ) {
        this.plugin = plugin;
        this.playerOtpManager = playerOtpManager;
        this.atmSessionManager = atmSessionManager;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String line1 = event.getLine(0);
        if (line1 == null || !"[FX]".equalsIgnoreCase(line1.trim())) {
            return;
        }

        Player player = event.getPlayer();
        Block signBlock = event.getBlock();
        World world = signBlock.getWorld();
        int sx = signBlock.getX();
        int sy = signBlock.getY();
        int sz = signBlock.getZ();

        int signExclusionRadius = plugin.getPluginConfig().getAtmFxSignExclusionRadius();
        if (hasNearbyFxSign(world, sx, sy, sz, signExclusionRadius)) {
            player.sendMessage("§c[FX] Cannot place ATM sign: another [FX] sign exists within "
                    + signExclusionRadius + " blocks (XYZ).");
            event.setCancelled(true);
            return;
        }

        Block atmBaseBlock = getAtmBaseBlock(signBlock);
        if (atmBaseBlock == null) {
            player.sendMessage("§c[FX] Unable to detect ATM base block behind sign.");
            event.setCancelled(true);
            return;
        }

        PluginConfig.AtmFeeProfile feeProfile = plugin.getPluginConfig()
            .findAtmFeeProfileByBlockType(atmBaseBlock.getType().name());
        String grade = determineGrade(feeProfile);
        if ("none".equals(grade)) {
            player.sendMessage("§c[FX] Block behind sign is not allowed by config (atm.block-grades).");
            event.setCancelled(true);
            return;
        }

        if (!validateStructure(
                world,
                sx,
                sy,
                sz,
                atmBaseBlock.getType(),
                player,
                plugin.getPluginConfig().getAtmBlockScanRadius(),
                plugin.getPluginConfig().getAtmRequiredMatchingBlocks()
        )) {
            event.setCancelled(true);
            return;
        }

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            String ownerInput = event.getLine(1) == null ? "" : event.getLine(1).trim();
            if (ownerInput.isEmpty()) {
                player.sendMessage("§c[FX] Line 2 must contain owner name (player name or svc:account)");
                event.setCancelled(true);
                return;
            }

            StorageData data = sm.getData();
            ResolvedOwner owner = resolveOwner(ownerInput, data);
            if (owner == null) {
                player.sendMessage("§c[FX] Owner not found: " + ownerInput);
                event.setCancelled(true);
                return;
            }

            int currentAtmCount = sm.getAtmRegistry().getByOwner(owner.ownerId).size();
            if (currentAtmCount >= MAX_ATMS_PER_OWNER) {
                player.sendMessage("§c[FX] " + owner.ownerName + " has reached max ATMs (" + currentAtmCount + "/" + MAX_ATMS_PER_OWNER + ")");
                event.setCancelled(true);
                return;
            }

            String worldName = world.getName();
            AtmData existing = sm.getAtmBySignLocation(worldName, sx, sy, sz);
            if (existing != null && "active".equalsIgnoreCase(existing.getStatus())) {
                player.sendMessage("§e[FX] This sign is already registered as ATM.");
                return;
            }

            AtmData atm = new AtmData();
            atm.setId(UUID.randomUUID().toString());
            atm.setSignWorld(worldName);
            atm.setSignX(sx);
            atm.setSignY(sy);
            atm.setSignZ(sz);
            atm.setOwnerId(owner.ownerId);
            atm.setOwnerName(owner.ownerName);
            atm.setGrade(grade);
            atm.setBlockType(atmBaseBlock.getType().name());
            atm.setStatus("active");

            sm.registerAtm(atm);

            event.setLine(1, owner.ownerName + " ATM");
            event.setLine(2, "Maker: " + feeLabel(feeProfile.getMakerRate()));
            event.setLine(3, "Taker: " + feeLabel(feeProfile.getTakerRate()));

            player.sendMessage("§a[FX] ATM created! Grade: " + grade.toUpperCase(Locale.ROOT) + " | Owner: " + owner.ownerName);
            sm.markDirty();
        } finally {
            sm.unlock();
        }
    }

    @EventHandler
    public void onSignRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getState() instanceof Sign sign)) {
            return;
        }

        String line1 = sign.getLine(0);
        if (!"[FX]".equalsIgnoreCase(line1.trim())) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();
        String identity = player.getUniqueId().toString();

        Block b = clicked;
        String world = b.getWorld().getName();
        AtmData atm;
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            atm = sm.getAtmBySignLocation(world, b.getX(), b.getY(), b.getZ());
        } finally {
            sm.unlock();
        }

        if (atm == null || !"active".equalsIgnoreCase(atm.getStatus())) {
            player.sendMessage("§c[FX] This ATM is not registered or inactive.");
            logAtmEvent("REJECT", atm == null ? null : atm.getId(), identity, "INACTIVE_OR_UNREGISTERED");
            return;
        }

        StorageManager sm2 = StorageManager.getInstance();
        sm2.lock();
        try {
            // 10分超過の占有は自動解放
            if (atm.isOccupied() && (System.currentTimeMillis() - atm.getOccupiedSince()) > OCCUPY_TIMEOUT_MS) {
                releaseAtmOccupancy(atm, "OCCUPY_TIMEOUT");
            }

            // 他ユーザー占有中は利用不可
            if (atm.isOccupied() && atm.getOccupiedBy() != null && !identity.equals(atm.getOccupiedBy())) {
                player.sendMessage("§c[FX] This ATM is currently occupied by another player.");
                logAtmEvent("REJECT", atm.getId(), identity, "OCCUPIED_BY_OTHER:" + atm.getOccupiedBy());
                return;
            }

            atm.setOccupied(true);
            atm.setOccupiedBy(identity);
            atm.setOccupiedSince(System.currentTimeMillis());
            updateAtmSignStatus(atm, true);
            logAtmEvent("START", atm.getId(), identity, "RIGHT_CLICK");
            sm2.markDirty();
        } finally {
            sm2.unlock();
        }

        OtpManager.OtpEntry entry = playerOtpManager.generate(identity);
        String otp = entry.getOtp();

        String grade = atm.getGrade();
        String atmId = atm.getId();

        atmSessionManager.registerPendingOtp(otp, identity, atmId, clicked.getLocation(), grade);

        String serverIp = plugin.getPluginConfig().getServerIp();
        int webPort = plugin.getPluginConfig().getWebPort();
        long expireMinutes = plugin.getPluginConfig().getOtpExpireSeconds() / 60;

        String tradeUrl = "http://" + serverIp + ":" + webPort + "/trade?otp=" + otp;

        player.sendMessage("§a[FX] ATM session started. Grade: " + grade.toUpperCase(Locale.ROOT));
        player.sendMessage("§7Move within 3 blocks to keep using ATM features.");
        player.sendMessage(
            Component.text("[GekiyabaFX] ", NamedTextColor.GOLD)
                .append(Component.text("ログインURLを生成しました", NamedTextColor.WHITE))
                .append(Component.text("（有効期限: " + expireMinutes + "分）", NamedTextColor.GRAY))
        );
        player.sendMessage(
            Component.text("► ", NamedTextColor.GOLD)
                .append(
                    Component.text(tradeUrl, NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(tradeUrl))
                )
        );
        player.sendMessage(
            Component.text("  クリックでブラウザが開きます。", NamedTextColor.GRAY)
        );
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        String identity = player.getUniqueId().toString();
        boolean sessionCleared = atmSessionManager.clearIfOutOfRange(identity);
        boolean occupiedCleared = clearOccupiedIfOutOfRange(identity, event.getTo());
        if (occupiedCleared) {
            atmSessionManager.clearPendingByIdentity(identity);
        }
        if (sessionCleared || occupiedCleared) {
            player.sendMessage("§e[FX] You moved away from ATM (>3 blocks). ATM session ended.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        String identity = event.getPlayer().getUniqueId().toString();
        atmSessionManager.clearSession(identity);
        clearOccupiedByIdentity(identity, "PLAYER_QUIT");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Sign)) {
            return;
        }

        String line1 = ((Sign) block.getState()).getLine(0);
        if (line1 == null || !"[FX]".equalsIgnoreCase(line1.trim())) {
            return;
        }

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            AtmData atm = sm.getAtmBySignLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
            if (atm == null) {
                return;
            }

            String occupiedBy = atm.getOccupiedBy();
            if (occupiedBy != null) {
                atmSessionManager.clearSession(occupiedBy);
            }

            atm.setOccupied(false);
            atm.setOccupiedBy(null);
            atm.setOccupiedSince(0L);
            atm.setStatus("inactive");
            updateAtmSignStatus(atm, false);
            sm.markDirty();
            logAtmEvent("RELEASE", atm.getId(), occupiedBy, "SIGN_BROKEN");
        } finally {
            sm.unlock();
        }
    }

    public void releaseForIdentity(String identity, String reason) {
        if (identity == null || identity.isBlank()) {
            return;
        }
        atmSessionManager.clearSession(identity);
        clearOccupiedByIdentity(identity, reason);
    }

    public void releaseTimedOutOccupancy() {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Collection<AtmData> atms = sm.getAtmRegistry().getAtms().values();
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (AtmData atm : atms) {
                if (atm == null || !atm.isOccupied()) {
                    continue;
                }
                if ((now - atm.getOccupiedSince()) <= OCCUPY_TIMEOUT_MS) {
                    continue;
                }
                String occupiedBy = atm.getOccupiedBy();
                releaseAtmOccupancy(atm, "OCCUPY_TIMEOUT");
                if (occupiedBy != null) {
                    atmSessionManager.clearSession(occupiedBy);
                }
                changed = true;
            }
            if (changed) {
                sm.markDirty();
            }
        } finally {
            sm.unlock();
        }
    }

    public void releaseAllOccupancyForShutdown() {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            boolean changed = false;
            for (AtmData atm : sm.getAtmRegistry().getAtms().values()) {
                if (atm == null || !atm.isOccupied()) {
                    continue;
                }
                String occupiedBy = atm.getOccupiedBy();
                releaseAtmOccupancy(atm, "SHUTDOWN");
                if (occupiedBy != null) {
                    atmSessionManager.clearSession(occupiedBy);
                }
                changed = true;
            }
            if (changed) {
                sm.markDirty();
            }
        } finally {
            sm.unlock();
        }
    }

    public int removeAllAtmsForOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return 0;
        }

        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            Collection<AtmData> atms = sm.getAtmRegistry().getAllByOwner(ownerId);
            int removed = 0;
            for (AtmData atm : atms) {
                if (atm == null) {
                    continue;
                }

                String occupiedBy = atm.getOccupiedBy();
                if (occupiedBy != null) {
                    atmSessionManager.clearSession(occupiedBy);
                }

                updateAtmSignStatus(atm, false);
                clearAtmSignText(atm);
                sm.removeAtm(atm.getId());
                logAtmEvent("RELEASE", atm.getId(), occupiedBy, "COMMAND_REMOVE");
                removed++;
            }

            if (removed > 0) {
                sm.markDirty();
            }
            return removed;
        } finally {
            sm.unlock();
        }
    }

    private static String determineGrade(PluginConfig.AtmFeeProfile feeProfile) {
        if (feeProfile == null || feeProfile.getGrade() == null || feeProfile.getGrade().isBlank()) {
            return "none";
        }
        return feeProfile.getGrade();
    }

    private static boolean validateStructure(
            World world,
            int sx,
            int sy,
            int sz,
            Material targetType,
            Player player,
            int scanRadius,
            int requiredMatchingBlocks
    ) {
        int matchCount = 0;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    Block b = world.getBlockAt(sx + dx, sy + dy, sz + dz);
                    if (b.getType() == targetType) {
                        matchCount++;
                    }
                }
            }
        }

        if (matchCount < requiredMatchingBlocks) {
            int cubeSize = (scanRadius * 2) + 1;
            player.sendMessage("§c[FX] ATM requires at least " + requiredMatchingBlocks
                    + " matching blocks (" + targetType.name() + ") within "
                    + cubeSize + "x" + cubeSize + "x" + cubeSize + " area.");
            return false;
        }

        return true;
    }

    private static Block getAtmBaseBlock(Block signBlock) {
        BlockData data = signBlock.getBlockData();
        BlockFace facing = null;

        if (data instanceof Directional directional) {
            facing = directional.getFacing();
        } else if (data instanceof Rotatable rotatable) {
            facing = rotatable.getRotation();
        }

        if (facing == null) {
            return null;
        }

        return signBlock.getRelative(facing.getOppositeFace());
    }

    private static boolean hasNearbyFxSign(World world, int sx, int sy, int sz, int exclusionRadius) {
        for (int dx = -exclusionRadius; dx <= exclusionRadius; dx++) {
            for (int dy = -exclusionRadius; dy <= exclusionRadius; dy++) {
                for (int dz = -exclusionRadius; dz <= exclusionRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    Block block = world.getBlockAt(sx + dx, sy + dy, sz + dz);
                    if (!(block.getState() instanceof Sign nearbySign)) {
                        continue;
                    }
                    String line1 = nearbySign.getLine(0);
                    if (line1 != null && "[FX]".equalsIgnoreCase(line1.trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSolidBlock(Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        if (type == Material.WATER || type == Material.LAVA) {
            return false;
        }
        return type.isSolid();
    }

    private static String feeLabel(java.math.BigDecimal rate) {
        if (rate == null) {
            return "0.000%";
        }
        return rate.multiply(new java.math.BigDecimal("100"))
                .setScale(3, java.math.RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }

    private static ResolvedOwner resolveOwner(String ownerInput, StorageData data) {
        Map<String, PlayerData> players = data.getPlayers();
        if (ownerInput.startsWith("svc:")) {
            PlayerData svc = players.get(ownerInput);
            if (svc == null) {
                return null;
            }
            return new ResolvedOwner(ownerInput, ownerInput);
        }

        for (Map.Entry<String, PlayerData> e : players.entrySet()) {
            PlayerData pd = e.getValue();
            if (pd == null || pd.getName() == null) {
                continue;
            }
            if (ownerInput.equalsIgnoreCase(pd.getName())) {
                return new ResolvedOwner(e.getKey(), pd.getName());
            }
        }

        return null;
    }

    private static final class ResolvedOwner {
        private final String ownerId;
        private final String ownerName;

        private ResolvedOwner(String ownerId, String ownerName) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
        }
    }

    private void clearOccupiedByIdentity(String identity, String reason) {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            AtmData atm = findOccupiedAtmByIdentity(sm, identity);
            if (atm == null) {
                return;
            }
            releaseAtmOccupancy(atm, reason);
            sm.markDirty();
        } finally {
            sm.unlock();
        }
    }

    private boolean clearOccupiedIfOutOfRange(String identity, Location currentLocation) {
        StorageManager sm = StorageManager.getInstance();
        sm.lock();
        try {
            AtmData atm = findOccupiedAtmByIdentity(sm, identity);
            if (atm == null) {
                return false;
            }

            Location signLoc = getSignLocation(atm);
            boolean outOfRange = signLoc == null || currentLocation == null || currentLocation.getWorld() == null;
            if (!outOfRange) {
                Location center = signLoc.clone().add(0.5, 0.5, 0.5);
                if (center.getWorld() == null || !center.getWorld().equals(currentLocation.getWorld())) {
                    outOfRange = true;
                } else {
                    outOfRange = currentLocation.distance(center) > MAX_DISTANCE_BLOCKS;
                }
            }

            if (!outOfRange) {
                return false;
            }

            releaseAtmOccupancy(atm, "OUT_OF_RANGE");
            sm.markDirty();
            return true;
        } finally {
            sm.unlock();
        }
    }

    private static AtmData findOccupiedAtmByIdentity(StorageManager sm, String identity) {
        for (AtmData atm : sm.getAtmRegistry().getAtms().values()) {
            if (atm != null && atm.isOccupied() && identity.equals(atm.getOccupiedBy())) {
                return atm;
            }
        }
        return null;
    }

    private void releaseAtmOccupancy(AtmData atm, String reason) {
        String releasedBy = atm.getOccupiedBy();
        atm.setOccupied(false);
        atm.setOccupiedBy(null);
        atm.setOccupiedSince(0L);
        updateAtmSignStatus(atm, false);
        String kind = "OCCUPY_TIMEOUT".equals(reason) ? "TIMEOUT" : "RELEASE";
        logAtmEvent(kind, atm.getId(), releasedBy, reason);
    }

    private void logAtmEvent(String event, String atmId, String playerId, String detail) {
        plugin.getLogger().info(
                "[ATM_OCCUPANCY] event=" + event
                        + " atmId=" + (atmId == null ? "-" : atmId)
                        + " player=" + (playerId == null ? "-" : playerId)
                        + " detail=" + (detail == null ? "-" : detail)
        );
    }

    private void updateAtmSignStatus(AtmData atm, boolean occupied) {
        Location signLoc = getSignLocation(atm);
        if (signLoc == null || signLoc.getWorld() == null) {
            return;
        }

        if (occupied) {
            createOccupiedMarker(signLoc, atm.getId());
        } else {
            removeOccupiedMarker(signLoc, atm.getId());
        }
    }

    private static void clearAtmSignText(AtmData atm) {
        Location signLoc = getSignLocation(atm);
        if (signLoc == null || signLoc.getWorld() == null) {
            return;
        }

        Block block = signLoc.getBlock();
        if (!(block.getState() instanceof Sign sign)) {
            return;
        }

        sign.setLine(0, "");
        sign.setLine(1, "");
        sign.setLine(2, "");
        sign.setLine(3, "");
        sign.update(true, false);
    }

    private static Location getSignLocation(AtmData atm) {
        if (atm.getSignWorld() == null) {
            return null;
        }
        World world = org.bukkit.Bukkit.getWorld(atm.getSignWorld());
        if (world == null) {
            return null;
        }
        return new Location(world, atm.getSignX(), atm.getSignY(), atm.getSignZ());
    }

    private static void createOccupiedMarker(Location signLoc, String atmId) {
        removeOccupiedMarker(signLoc, atmId);

        Location markerLoc = signLoc.clone().add(0.5, 1.35, 0.5);
        ArmorStand stand = (ArmorStand) signLoc.getWorld().spawnEntity(markerLoc, EntityType.ARMOR_STAND);
        stand.setCustomName("§c[OCCUPIED]");
        stand.setCustomNameVisible(true);
        stand.setGravity(false);
        stand.setInvisible(true);
        stand.setCanPickupItems(false);
        stand.setMarker(true);
        stand.addScoreboardTag(OCCUPIED_MARKER_TAG);
        stand.addScoreboardTag("atm-id:" + atmId);
    }

    private static void removeOccupiedMarker(Location signLoc, String atmId) {
        for (Entity entity : signLoc.getWorld().getNearbyEntities(
                signLoc,
                2,
                3,
                2,
                e -> e instanceof ArmorStand
                        && ((ArmorStand) e).getScoreboardTags().contains(OCCUPIED_MARKER_TAG)
                        && ((ArmorStand) e).getScoreboardTags().contains("atm-id:" + atmId)
        )) {
            entity.remove();
        }
    }

}
