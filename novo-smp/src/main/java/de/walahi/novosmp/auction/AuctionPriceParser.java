package de.walahi.novosmp.auction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Parses compact player price input such as 2500, 2.5k and 1m without floating-point drift. */
public final class AuctionPriceParser {
    private AuctionPriceParser() {
    }

    public static Long parse(String input) {
        if (input == null) return null;
        String text = input.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace(',', '.');
        if (text.isEmpty()) return null;

        BigDecimal multiplier = BigDecimal.ONE;
        if (text.endsWith("k")) {
            multiplier = BigDecimal.valueOf(1_000L);
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("m")) {
            multiplier = BigDecimal.valueOf(1_000_000L);
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("b")) {
            multiplier = BigDecimal.valueOf(1_000_000_000L);
            text = text.substring(0, text.length() - 1);
        }
        if (text.isBlank()) return null;

        try {
            BigDecimal value = new BigDecimal(text).multiply(multiplier);
            if (value.signum() <= 0) return null;
            return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }
}
