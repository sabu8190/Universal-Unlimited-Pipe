package com.uup.core.network;

public enum TransferChannel {
    ITEM("Item"),
    FLUID("Fluid"),
    ENERGY("Energy"),
    GAS("Gas");

    private final String displayName;

    TransferChannel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
