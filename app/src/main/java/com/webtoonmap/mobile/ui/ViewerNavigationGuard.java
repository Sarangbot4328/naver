package com.webtoonmap.mobile.ui;

/** Serializes episode changes and rejects callbacks belonging to an older document. */
public final class ViewerNavigationGuard {
    private static final long TRANSITION_COOLDOWN_MS = 750L;
    private int generation;
    private boolean loading = true;
    private long nextAllowedAt;

    public void loadStarted(int generation) {
        this.generation = generation;
        loading = true;
    }

    public boolean documentReady(int generation) {
        if (this.generation != generation || !loading) return false;
        loading = false;
        return true;
    }

    public boolean tryAdvance(int generation, long nowMs) {
        if (this.generation != generation || loading || nowMs < nextAllowedAt) return false;
        loading = true;
        nextAllowedAt = nowMs + TRANSITION_COOLDOWN_MS;
        return true;
    }
}
