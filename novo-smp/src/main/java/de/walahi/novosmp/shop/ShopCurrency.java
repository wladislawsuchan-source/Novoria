package de.walahi.novosmp.shop;

/**
 * Womit im Shop bezahlt wird.
 *
 * <p>Normale Kategorien verwenden Coins. Custom-Item-Kategorien können stattdessen
 * ein echtes Item aus dem Inventar als Währung verwenden.</p>
 */
public enum ShopCurrency {
    /** Kontostand aus dem Economy-System. */
    COINS,
    /** Ein Custom-Item aus der items.yml, das aus dem Inventar entfernt wird. */
    CUSTOM_ITEM
}
