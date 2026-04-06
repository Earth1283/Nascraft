package me.bounser.nascraft.web.dto;

public class DetailedTransactionDTO {

    private final String playerName;
    private final String item;
    private final int amount;
    private final long timestamp;

    public DetailedTransactionDTO(String playerName, String item, int amount, long timestamp) {
        this.playerName = playerName;
        this.item = item;
        this.amount = amount;
        this.timestamp = timestamp;
    }

    public String getPlayerName() { return playerName; }
    public String getItem() { return item; }
    public int getAmount() { return amount; }
    public long getTimestamp() { return timestamp; }

}
