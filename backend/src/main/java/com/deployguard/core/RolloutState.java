package com.deployguard.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

// Rollout state machine. Every transition is validated here before it is persisted.
public enum RolloutState {
    RUNNING, PAUSED, COMPLETED, ROLLED_BACK, ABORTED;

    public enum Action { PROMOTE, PAUSE, RESUME, ABORT, ROLLBACK, COMPLETE }

    private static final Map<RolloutState, Set<RolloutState>> ALLOWED = Map.of(
            RUNNING, EnumSet.of(RUNNING, PAUSED, COMPLETED, ROLLED_BACK, ABORTED),
            PAUSED, EnumSet.of(RUNNING, COMPLETED, ROLLED_BACK, ABORTED),
            COMPLETED, EnumSet.noneOf(RolloutState.class),
            ROLLED_BACK, EnumSet.noneOf(RolloutState.class),
            ABORTED, EnumSet.noneOf(RolloutState.class));

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    public boolean isActive() {
        return this == RUNNING || this == PAUSED;
    }

    public boolean canMoveTo(RolloutState next) {
        return ALLOWED.get(this).contains(next);
    }

    // Resulting state for an action, or IllegalStateException if the action is not valid now.
    // PROMOTE from the last stage completes the rollout; PROMOTE while paused is a manual override.
    public RolloutState apply(Action action, boolean atLastStage) {
        RolloutState next = switch (action) {
            case PROMOTE -> atLastStage ? COMPLETED : RUNNING;
            case PAUSE -> this == RUNNING ? PAUSED : null;
            case RESUME -> this == PAUSED ? RUNNING : null;
            case ABORT -> ABORTED;
            case ROLLBACK -> ROLLED_BACK;
            case COMPLETE -> COMPLETED;
        };
        if (next == null || !canMoveTo(next)) {
            throw new IllegalStateException("Cannot " + action.name().toLowerCase() + " a rollout that is " + name());
        }
        return next;
    }
}
