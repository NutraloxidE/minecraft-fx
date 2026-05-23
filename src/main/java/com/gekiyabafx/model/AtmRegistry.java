package com.gekiyabafx.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ATM 一覧の永続レジストリ。
 */
public final class AtmRegistry {

    private Map<String, AtmData> atms;

    public AtmRegistry() {
        this.atms = new LinkedHashMap<>();
    }

    public Map<String, AtmData> getAtms() {
        return atms;
    }

    public void setAtms(Map<String, AtmData> atms) {
        this.atms = atms;
    }

    public void register(AtmData atmData) {
        atms.put(atmData.getId(), atmData);
    }

    public AtmData getById(String atmId) {
        return atms.get(atmId);
    }

    public AtmData getBySignLocation(String world, int x, int y, int z) {
        for (AtmData atm : atms.values()) {
            if (atm != null && atm.matchesSignLocation(world, x, y, z)) {
                return atm;
            }
        }
        return null;
    }

    public List<AtmData> getByOwner(String ownerId) {
        List<AtmData> result = new ArrayList<>();
        for (AtmData atm : atms.values()) {
            if (atm != null && ownerId != null && ownerId.equals(atm.getOwnerId())) {
                result.add(atm);
            }
        }
        return result;
    }
}
