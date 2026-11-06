package ru.easyitsm.assets.api;

/** Состояние оборудования (AST-01): на складе, выдано сотруднику, в ремонте, списано. */
public enum AssetState {
    IN_STOCK, IN_USE, IN_REPAIR, RETIRED;

    public String code() {
        return name().toLowerCase();
    }

    public static AssetState fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
