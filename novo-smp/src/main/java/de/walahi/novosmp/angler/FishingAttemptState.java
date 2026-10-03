package de.walahi.novosmp.angler;

/** Main-thread fishing transition gate. A catch can only resolve once. */
final class FishingAttemptState {
    enum Phase { CAST, BITTEN, PLAYING, RESOLVING }

    private Phase phase = Phase.CAST;
    private int startedTick = -1;
    private int confirmingTick = -1;
    private boolean confirmingInteractionPending;
    private int remainingGrayRetries;

    Phase phase() { return phase; }
    int startedTick() { return startedTick; }

    void bite() {
        if (phase == Phase.CAST) phase = Phase.BITTEN;
    }

    boolean expireBite() {
        if (phase != Phase.BITTEN) return false;
        phase = Phase.CAST;
        return true;
    }

    void confirmingInteract(int tick) {
        if (phase == Phase.BITTEN) confirmingTick = tick;
    }

    boolean startGame(int tick, int grayRetries) {
        if (phase != Phase.BITTEN) return false;
        startedTick = tick;
        remainingGrayRetries = Math.max(0, Math.min(2, grayRetries));
        // Normal Paper flow reports interact before the fishing catch. If a
        // retrieval source reports the catch first, consume its own interact
        // once without suppressing another click in the same server tick.
        confirmingInteractionPending = confirmingTick != tick;
        phase = Phase.PLAYING;
        return true;
    }

    boolean consumeGrayRetry(FishingGame.Quality quality, boolean timedOut) {
        if (phase != Phase.PLAYING || timedOut || quality != FishingGame.Quality.GRAY
                || remainingGrayRetries <= 0) return false;
        remainingGrayRetries--;
        return true;
    }

    boolean canStop(int tick) {
        if (phase != Phase.PLAYING) return false;
        if (tick == startedTick && confirmingInteractionPending) {
            confirmingInteractionPending = false;
            return false;
        }
        return true;
    }

    boolean beginResolve() {
        if (phase != Phase.PLAYING) return false;
        phase = Phase.RESOLVING;
        return true;
    }
}
