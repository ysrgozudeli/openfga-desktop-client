package com.openfga.client.model;

public class StoreInfo {
    private final String id;
    private final String name;

    public StoreInfo(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
