package me.bounser.nascraft.web.dto;

public class PlayerStatsDTO {

    private final long time;
    private final double balance;
    private final double portfolio;
    private final double debt;

    public PlayerStatsDTO(long time, double balance, double portfolio, double debt) {
        this.time = time;
        this.balance = balance;
        this.portfolio = portfolio;
        this.debt = debt;
    }

    public long getTime() { return time; }
    public double getBalance() { return balance; }
    public double getPortfolio() { return portfolio; }
    public double getDebt() { return debt; }

}
