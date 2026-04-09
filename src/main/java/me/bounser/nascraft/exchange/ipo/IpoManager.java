package me.bounser.nascraft.exchange.ipo;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.exchange.ExchangeDatabase;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyManager;
import me.bounser.nascraft.exchange.company.CompanyStatus;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class IpoManager {

    private static IpoManager instance;
    private final Map<UUID, IpoPending> pendingIpos = new ConcurrentHashMap<>();

    private IpoManager() {}

    public static IpoManager getInstance() {
        if (instance == null) {
            instance = new IpoManager();
        }
        return instance;
    }

    public IpoPending submitIpo(UUID founderUuid, String name, String ticker, double sharePrice, double sharesOffered, double ipoFee, String prospectus) throws IpoException {
        double feeMin = Config.getInstance().getExchangeIpoFeeMin();
        double feeMax = Config.getInstance().getExchangeIpoFeeMax();
        if (ipoFee < feeMin || ipoFee > feeMax) {
            throw new IpoException(Lang.get().message(Message.EXCHANGE_IPO_FEE_INVALID)
                    .replace("[MIN]", String.format("%.0f", feeMin))
                    .replace("[MAX]", String.format("%.0f", feeMax)));
        }

        if (Nascraft.getEconomy().getBalance(Bukkit.getOfflinePlayer(founderUuid)) < ipoFee) {
            throw new IpoException(Lang.get().message(Message.EXCHANGE_IPO_INSUFFICIENT_FUNDS)
                    .replace("[AMOUNT]", String.format("%.2f", ipoFee)));
        }

        Nascraft.getEconomy().withdrawPlayer(Bukkit.getOfflinePlayer(founderUuid), ipoFee);

        UUID companyId = UUID.randomUUID();
        Company company = new Company(companyId, name, ticker, sharePrice, BigDecimal.valueOf(sharesOffered));
        CompanyManager.getInstance().registerCompany(company);
        Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
            try { ExchangeDatabase.getInstance().saveCompany(company); }
            catch (IllegalStateException ignored) {}
        });

        IpoPending pending = new IpoPending(companyId, ipoFee, sharePrice, sharesOffered, prospectus);
        pending.status = IpoStatus.FEE_PAID;
        pendingIpos.put(companyId, pending);

        return pending;
    }

    public void approveIpo(UUID companyId) {
        IpoPending pending = pendingIpos.get(companyId);
        if (pending != null) {
            pending.status = IpoStatus.OPEN;
            CompanyManager.getInstance().getCompany(companyId).ifPresent(company -> {
                company.setStatus(CompanyStatus.ACTIVE);
                company.setLastVaultSnapshot(company.getCurrentVaultValue());
                Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
                    try { ExchangeDatabase.getInstance().saveCompany(company); }
                    catch (IllegalStateException ignored) {}
                });
            });
        }
    }

    public void rejectIpo(UUID companyId, String reason) {
        IpoPending pending = pendingIpos.get(companyId);
        if (pending != null) {
            pending.status = IpoStatus.REJECTED;
            CompanyManager.getInstance().getCompany(companyId).ifPresent(company -> {
                company.setStatus(CompanyStatus.DELISTED);
                company.setHaltReason(reason);
                Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
                    try { ExchangeDatabase.getInstance().saveCompany(company); }
                    catch (IllegalStateException ignored) {}
                });
            });
        }
    }

    public Optional<IpoPending> getPendingIpo(UUID companyId) {
        return Optional.ofNullable(pendingIpos.get(companyId));
    }

    public List<IpoPending> getAllPending() {
        return new ArrayList<>(pendingIpos.values());
    }

    public static class IpoPending {
        public UUID companyId;
        public double ipoFee;
        public double sharePrice;
        public double sharesOffered;
        public String prospectusText;
        public long submittedAt;
        public IpoStatus status;

        public IpoPending(UUID companyId, double ipoFee, double sharePrice, double sharesOffered, String prospectusText) {
            this.companyId = companyId;
            this.ipoFee = ipoFee;
            this.sharePrice = sharePrice;
            this.sharesOffered = sharesOffered;
            this.prospectusText = prospectusText;
            this.submittedAt = System.currentTimeMillis();
            this.status = IpoStatus.PENDING_FEE;
        }
    }
}
