package com.gekiyabafx.listener;

import com.gekiyabafx.GekiyabaFXPlugin;
import com.gekiyabafx.atm.AtmSessionManager;
import com.gekiyabafx.auth.OtpManager;
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
    private static final long OCCUPY_TIMEOUT_MS = 600_000L;
    private static final String OCCUPIED_MARKER_TAG = "atm-occupied-marker";
    private static final int ATM_BLOCK_SCAN_RADIUS = 3;
    private static final int REQUIRED_MATCHING_BLOCKS = 11;
    private static final int FX_SIGN_EXCLUSION_RADIUS = 3;

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

        if (hasNearbyFxSign(world, sx, sy, sz)) {
            player.sendMessage("§c[FX] Cannot place ATM sign: another [FX] sign exists within 3 blocks (XYZ).");
            event.setCancelled(true);
            return;
        }

        Block atmBaseBlock = getAtmBaseBlock(signBlock);
        if (atmBaseBlock == null) {
            player.sendMessage("§c[FX] Unable to detect ATM base block behind sign.");
            event.setCancelled(true);
            return;
        }

        String grade = determineGrade(atmBaseBlock);
        if ("none".equals(grade)) {
            player.sendMessage("§c[FX] Block behind sign must be IRON_BLOCK, DIAMOND_BLOCK, or NETHERITE_BLOCK.");
            event.setCancelled(true);
            return;
        }

        if (!validateStructure(world, sx, sy, sz, atmBaseBlock.getType(), player)) {
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
            event.setLine(2, "Maker: " + feeLabel(grade, true));
            event.setLine(3, "Taker: " + feeLabel(grade, false));

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
        boolean cleared = atmSessionManager.clearIfOutOfRange(player.getUniqueId().toString());
        if (cleared) {
            clearOccupiedByIdentity(player.getUniqueId().toString(), "OUT_OF_RANGE");
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

    private static String determineGrade(Block centerBlock) {
        Material type = centerBlock.getType();
        return switch (type) {
            case IRON_BLOCK -> "iron";
            case DIAMOND_BLOCK -> "diamond";
            case NETHERITE_BLOCK -> "netherite";
            default -> "none";
        };
    }

    private static boolean validateStructure(World world, int sx, int sy, int sz, Material targetType, Player player) {
        int matchCount = 0;
        for (int dx = -ATM_BLOCK_SCAN_RADIUS; dx <= ATM_BLOCK_SCAN_RADIUS; dx++) {
            for (int dy = -ATM_BLOCK_SCAN_RADIUS; dy <= ATM_BLOCK_SCAN_RADIUS; dy++) {
                for (int dz = -ATM_BLOCK_SCAN_RADIUS; dz <= ATM_BLOCK_SCAN_RADIUS; dz++) {
                    Block b = world.getBlockAt(sx + dx, sy + dy, sz + dz);
                    if (b.getType() == targetType) {
                        matchCount++;
                    }
                }
            }
        }

        if (matchCount < REQUIRED_MATCHING_BLOCKS) {
            player.sendMessage("§c[FX] ATM requires at least " + REQUIRED_MATCHING_BLOCKS
                    + " matching blocks (" + targetType.name() + ") within 7x7x7 area.");
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

    private static boolean hasNearbyFxSign(World world, int sx, int sy, int sz) {
        for (int dx = -FX_SIGN_EXCLUSION_RADIUS; dx <= FX_SIGN_EXCLUSION_RADIUS; dx++) {
            for (int dy = -FX_SIGN_EXCLUSION_RADIUS; dy <= FX_SIGN_EXCLUSION_RADIUS; dy++) {
                for (int dz = -FX_SIGN_EXCLUSION_RADIUS; dz <= FX_SIGN_EXCLUSION_RADIUS; dz++) {
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

    private static String feeLabel(String grade, boolean maker) {
        return switch (grade) {
            case "iron" -> maker ? "0.080%" : "0.120%";
            case "diamond" -> maker ? "0.050%" : "0.080%";
            case "netherite" -> maker ? "0.030%" : "0.050%";
            default -> maker ? "0.100%" : "0.160%";
        };
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
