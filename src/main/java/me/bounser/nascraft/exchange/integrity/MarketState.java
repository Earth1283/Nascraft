package me.bounser.nascraft.exchange.integrity;

/**
 * Exchange-wide market state machine.
 * Transitions are synchronous (no scheduler lag on halts).
 *
 *  OPEN ──► HALTED ──► OPEN       (circuit breaker auto-resume after review)
 *  OPEN ──► SUSPENDED             (manual, requires incident report)
 *  OPEN ──► CLOSED                (scheduled close)
 */
public enum MarketState {
    OPEN,
    HALTED,
    SUSPENDED,
    CLOSED
}
