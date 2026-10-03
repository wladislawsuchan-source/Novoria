package de.walahi.novosmp.king;

import java.time.LocalDate;
import java.util.UUID;

public record DragonEggKingState(UUID token, KingLocationKind kind, UUID holder, String holderName,
                                 String world, Integer x, Integer y, Integer z, String detail,
                                 UUID reign, long reignStartedAt, LocalDate duelDate,
                                 int mandatoryUsed, long updatedAt) {
    public static DragonEggKingState initial(UUID token) {
        return new DragonEggKingState(token, KingLocationKind.UNKNOWN, null, null, null,
                null, null, null, null, null, 0L, null, 0, System.currentTimeMillis());
    }

    public DragonEggKingState located(KingLocationKind nextKind, UUID nextHolder, String nextHolderName,
                                      String nextWorld, Integer nextX, Integer nextY, Integer nextZ,
                                      String nextDetail, LocalDate today) {
        // DUEL is only a temporary location state. Returning the egg to the same holder
        // must not silently start a new reign and reset the already used mandatory duels.
        boolean newKing = nextKind == KingLocationKind.PLAYER
                && (reign == null || !java.util.Objects.equals(holder, nextHolder));
        return new DragonEggKingState(token, nextKind, nextHolder, nextHolderName, nextWorld,
                nextX, nextY, nextZ, nextDetail, newKing ? UUID.randomUUID() : reign,
                newKing ? System.currentTimeMillis() : reignStartedAt,
                newKing ? today : duelDate, newKing ? 0 : mandatoryUsed, System.currentTimeMillis());
    }

    public DragonEggKingState reset(UUID nextToken, String nextWorld, int nextX, int nextY, int nextZ) {
        return new DragonEggKingState(nextToken, KingLocationKind.PORTAL, null, null, nextWorld,
                nextX, nextY, nextZ, "End-Resetposition", null, 0L, null, 0, System.currentTimeMillis());
    }

    public DragonEggKingState useMandatory(LocalDate today) {
        int used = today.equals(duelDate) ? mandatoryUsed : 0;
        return new DragonEggKingState(token, kind, holder, holderName, world, x, y, z, detail,
                reign, reignStartedAt, today, used + 1, System.currentTimeMillis());
    }

    public DragonEggKingState refundMandatory() {
        return new DragonEggKingState(token, kind, holder, holderName, world, x, y, z, detail,
                reign, reignStartedAt, duelDate, Math.max(0, mandatoryUsed - 1), System.currentTimeMillis());
    }

    public DragonEggKingState normalizedDate(LocalDate today) {
        if (today.equals(duelDate)) return this;
        return new DragonEggKingState(token, kind, holder, holderName, world, x, y, z, detail,
                reign, reignStartedAt, today, 0, System.currentTimeMillis());
    }
}
