package me.bounser.nascraft.web.dto;

public class DebtDTO {

    private final double debt;
    private final double nextPayment;
    private final double lifeTimeInterests;
    private final double maximumLoan;
    private final double dailyInterest;
    private final double minimumInterest;
    private final String nextPaymentTime;

    public DebtDTO(double debt, double nextPayment, double lifeTimeInterests, double maximumLoan, double dailyInterest, double minimumInterest, String nextPaymentTime) {
        this.debt = debt;
        this.nextPayment = nextPayment;
        this.lifeTimeInterests = lifeTimeInterests;
        this.maximumLoan = maximumLoan;
        this.dailyInterest = dailyInterest;
        this.minimumInterest = minimumInterest;
        this.nextPaymentTime = nextPaymentTime;
    }

    public double getDebt() { return debt; }
    public double getNextPayment() { return nextPayment; }
    public double getLifeTimeInterests() { return lifeTimeInterests; }
    public double getMaximumLoan() { return maximumLoan; }
    public double getDailyInterest() { return dailyInterest; }
    public double getMinimumInterest() { return minimumInterest; }
    public String getNextPaymentTime() { return nextPaymentTime; }

}
