package me.bounser.nascraft.exchange.shares;

public class InsufficientSharesException extends Exception {
    public InsufficientSharesException(String message) {
        super(message);
    }
}
