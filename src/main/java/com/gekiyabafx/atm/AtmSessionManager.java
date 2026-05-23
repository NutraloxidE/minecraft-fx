package com.gekiyabafx.atm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ATM 起点の OTP とブラウザセッションを紐付けて管理する。
 */
public final class AtmSessionManager {

    private static final double MAX_DISTANCE_BLOCKS = 3.0;

    private static final class PendingAtmOtp {
        private final String identity;
        private final ActiveAtmSession session;

        private PendingAtmOtp(String identity, ActiveAtmSession session) {
            this.identity = identity;
            this.session = session;
        }
    }

    private static final class ActiveAtmSession {
        private final String atmId;
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private final String grade;

        private ActiveAtmSession(String atmId, String world, double x, double y, double z, String grade) {
            this.atmId = atmId;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.grade = grade;
        }
    }

    public static final class AtmSessionState {
        private final boolean active;
        private final String atmId;
        private final String grade;
        private final double maxDistance;

        private AtmSessionState(boolean active, String atmId, String grade, double maxDistance) {
            this.active = active;
            this.atmId = atmId;
            this.grade = grade;
            this.maxDistance = maxDistance;
        }

        public static AtmSessionState inactive() {
            return new AtmSessionState(false, null, null, MAX_DISTANCE_BLOCKS);
        }

        public static AtmSessionState active(String atmId, String grade) {
            return new AtmSessionState(true, atmId, grade, MAX_DISTANCE_BLOCKS);
        }

        public boolean isActive() {
            return active;
        }

        public String getAtmId() {
            return atmId;
        }

        public String getGrade() {
            return grade;
        }

        public double getMaxDistance() {
            return maxDistance;
        }
    }

    public static final class AtmSessionException extends Exception {
        private final String code;

        public AtmSessionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final Map<String, PendingAtmOtp> pendingByOtp = new ConcurrentHashMap<>();
    private final Map<String, ActiveAtmSession> activeByIdentity = new ConcurrentHashMap<>();
    private final Map<String, String> identityByToken = new ConcurrentHashMap<>();

    public void registerPendingOtp(String otp, String identity, String atmId, Location atmLocation, String grade) {
        if (otp == null || identity == null || atmLocation == null || atmLocation.getWorld() == null) {
            return;
        }

        ActiveAtmSession session = new ActiveAtmSession(
                atmId,
                atmLocation.getWorld().getName(),
                atmLocation.getX(),
                atmLocation.getY(),
                atmLocation.getZ(),
                grade
        );
        pendingByOtp.put(otp, new PendingAtmOtp(identity, session));
    }

    public AtmSessionState activateByOtp(String otp, String identity, String token) {
        PendingAtmOtp pending = pendingByOtp.remove(otp);
        if (pending == null) {
            return AtmSessionState.inactive();
        }
        if (!pending.identity.equals(identity)) {
            return AtmSessionState.inactive();
        }

        activeByIdentity.put(identity, pending.session);
        identityByToken.put(token, identity);
        return AtmSessionState.active(pending.session.atmId, pending.session.grade);
    }

    public AtmSessionState getStateByToken(String token, String identity) {
        ActiveAtmSession session = resolveActiveSession(token, identity);
        if (session == null) {
            return AtmSessionState.inactive();
        }
        return AtmSessionState.active(session.atmId, session.grade);
    }

    public void requireActiveInRange(String token, String identity, String operation) throws AtmSessionException {
        ActiveAtmSession session = resolveActiveSession(token, identity);
        if (session == null) {
            throw new AtmSessionException(
                    "atm_session_required",
                    "Deposit/Withdraw requires ATM session. Right-click [FX] sign first."
            );
        }

        Player player = resolveOnlinePlayer(identity);
        if (player == null) {
            clearByIdentity(identity);
            throw new AtmSessionException(
                    "atm_session_required",
                    "Deposit/Withdraw requires ATM session. Right-click [FX] sign first."
            );
        }

        double distance = distanceToAtm(player.getLocation(), session);
        if (distance > MAX_DISTANCE_BLOCKS) {
            clearByIdentity(identity);
            throw new AtmSessionException(
                    "atm_out_of_range",
                    String.format("You must be within %.1f blocks of ATM to perform '%s'.", MAX_DISTANCE_BLOCKS, operation)
            );
        }
    }

    public boolean clearIfOutOfRange(String identity) {
        ActiveAtmSession session = activeByIdentity.get(identity);
        if (session == null) {
            return false;
        }

        Player player = resolveOnlinePlayer(identity);
        if (player == null) {
            clearByIdentity(identity);
            return true;
        }

        double distance = distanceToAtm(player.getLocation(), session);
        if (distance <= MAX_DISTANCE_BLOCKS) {
            return false;
        }

        clearByIdentity(identity);
        return true;
    }

    public boolean clearSession(String identity) {
        if (identity == null || !activeByIdentity.containsKey(identity)) {
            return false;
        }
        clearByIdentity(identity);
        return true;
    }

    public boolean clearPendingByIdentity(String identity) {
        if (identity == null || identity.isBlank()) {
            return false;
        }
        return pendingByOtp.entrySet().removeIf(e -> identity.equals(e.getValue().identity));
    }

    private ActiveAtmSession resolveActiveSession(String token, String identity) {
        if (token != null) {
            String mappedIdentity = identityByToken.get(token);
            if (mappedIdentity != null && mappedIdentity.equals(identity)) {
                return activeByIdentity.get(identity);
            }
        }
        return activeByIdentity.get(identity);
    }

    private void clearByIdentity(String identity) {
        activeByIdentity.remove(identity);
        identityByToken.entrySet().removeIf(e -> identity.equals(e.getValue()));
    }

    private Player resolveOnlinePlayer(String identity) {
        try {
            UUID uuid = UUID.fromString(identity);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return null;
            }
            return player;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static double distanceToAtm(Location playerLocation, ActiveAtmSession session) {
        World world = Bukkit.getWorld(session.world);
        if (world == null || playerLocation.getWorld() == null || !playerLocation.getWorld().getName().equals(session.world)) {
            return Double.MAX_VALUE;
        }

        Location atm = new Location(world, session.x, session.y, session.z);
        return playerLocation.distance(atm);
    }
}
