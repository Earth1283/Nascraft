package me.bounser.nascraft.web.dto;

public class TransactionDTO {

    private final String item;
    private final int amount;
    private final double value;
    private final long timestamp;

    public TransactionDTO(String item, int amount, double value, long timestamp) {
        this.item = item;
        this.amount = amount;
        this.value = value;
        this.timestamp = timestamp;
    }

    public String getItem() { return item; }
    public int getAmount() { return amount; }
    public double getValue() { return value; }
    public long getTimestamp() { return timestamp; }

}
