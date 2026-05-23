package com.gekiyabafx.model;

import java.util.HashMap;
import java.util.Map;

/**
 * ATM 1台分の永続データ。
 */
public final class AtmData {

    private String id;
    private String signWorld;
    private int signX;
    private int signY;
    private int signZ;

    private String ownerId;
    private String ownerName;

    private String grade;
    private String blockType;
    private String status;

    private boolean occupied;
    private String occupiedBy;
    private long occupiedSince;

    private Map<String, Double> totalFeesEarned;
    private Map<String, Double> pendingPayout;

    public AtmData() {
        this.status = "active";
        this.grade = "none";
        this.occupied = false;
        this.totalFeesEarned = new HashMap<>();
        this.pendingPayout = new HashMap<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSignWorld() {
        return signWorld;
    }

    public void setSignWorld(String signWorld) {
        this.signWorld = signWorld;
    }

    public int getSignX() {
        return signX;
    }

    public void setSignX(int signX) {
        this.signX = signX;
    }

    public int getSignY() {
        return signY;
    }

    public void setSignY(int signY) {
        this.signY = signY;
    }

    public int getSignZ() {
        return signZ;
    }

    public void setSignZ(int signZ) {
        this.signZ = signZ;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public void setOccupied(boolean occupied) {
        this.occupied = occupied;
    }

    public String getOccupiedBy() {
        return occupiedBy;
    }

    public void setOccupiedBy(String occupiedBy) {
        this.occupiedBy = occupiedBy;
    }

    public long getOccupiedSince() {
        return occupiedSince;
    }

    public void setOccupiedSince(long occupiedSince) {
        this.occupiedSince = occupiedSince;
    }

    public Map<String, Double> getTotalFeesEarned() {
        return totalFeesEarned;
    }

    public void setTotalFeesEarned(Map<String, Double> totalFeesEarned) {
        this.totalFeesEarned = totalFeesEarned;
    }

    public Map<String, Double> getPendingPayout() {
        return pendingPayout;
    }

    public void setPendingPayout(Map<String, Double> pendingPayout) {
        this.pendingPayout = pendingPayout;
    }

    public boolean matchesSignLocation(String world, int x, int y, int z) {
        return signWorld != null
                && signWorld.equals(world)
                && signX == x
                && signY == y
                && signZ == z;
    }
}
