package me.bounser.nascraft.exchange.company;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CompanyManager {

    private static CompanyManager instance;
    private final Map<UUID, Company> companiesById = new ConcurrentHashMap<>();
    private final Map<String, Company> companiesByTicker = new ConcurrentHashMap<>();

    private CompanyManager() {}

    public static CompanyManager getInstance() {
        if (instance == null) {
            instance = new CompanyManager();
        }
        return instance;
    }

    public void registerCompany(Company company) {
        companiesById.put(company.getId(), company);
        companiesByTicker.put(company.getTicker().toUpperCase(), company);
    }

    public Optional<Company> getCompany(UUID companyId) {
        return Optional.ofNullable(companiesById.get(companyId));
    }

    public Optional<Company> getCompanyByTicker(String ticker) {
        return Optional.ofNullable(companiesByTicker.get(ticker.toUpperCase()));
    }

    public List<Company> getAllActiveCompanies() {
        return companiesById.values().stream()
                .filter(c -> c.getStatus() == CompanyStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    public void updateVaultValue(UUID companyId, double newValue) {
        getCompany(companyId).ifPresent(c -> c.setCurrentVaultValue(newValue));
    }

    public void haltCompany(UUID companyId, String reason) {
        getCompany(companyId).ifPresent(c -> {
            c.setStatus(CompanyStatus.HALTED);
            c.setHaltReason(reason);
        });
    }

    public void resumeCompany(UUID companyId) {
        getCompany(companyId).ifPresent(c -> {
            c.setStatus(CompanyStatus.ACTIVE);
            c.setHaltReason(null);
        });
    }

    public void checkMarketCapCompliance(UUID companyId) {
        getCompany(companyId).ifPresent(company -> {
            double marketCap  = company.getMarketCap();
            double vaultValue = company.getCurrentVaultValue();
            if (vaultValue <= 0) return;
            if (marketCap > vaultValue * 1.20) {
                haltCompany(companyId, "Market cap exceeds vault value by >20% (compliance halt)");
            } else if (marketCap > vaultValue * 1.05) {
                Nascraft.getInstance().getLogger().warning(
                    "[Compliance] " + company.getTicker() + " market cap exceeds vault value by >5%");
            }
        });
    }

    public List<Company> getCompaniesNeedingComplianceCheck() {
        return new ArrayList<>(companiesById.values());
    }
}
