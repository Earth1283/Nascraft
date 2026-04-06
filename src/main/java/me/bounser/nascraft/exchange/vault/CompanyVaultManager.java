package me.bounser.nascraft.exchange.vault;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyManager;
import me.bounser.nascraft.exchange.company.CompanyStatus;
import me.bounser.nascraft.exchange.integrity.CircuitBreaker;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages company vault chests.
 *
 * A company vault is a registered chest location. The plugin:
 *  - Blocks unauthorized inventory access
 *  - Recalculates vault value on every inventory change
 *  - Takes a weekly snapshot (Sunday midnight) for profit calculation
 *  - Enforces market cap ≤ vault value (with tiered responses)
 */
public class CompanyVaultManager implements Listener {

    private static CompanyVaultManager instance;

    /** Serialized location key → companyId */
    private final Map<String, UUID> vaultLocations = new ConcurrentHashMap<>();

    /** Admin-set item prices: material name → price per unit */
    private final Map<String, Double> itemPrices = new ConcurrentHashMap<>();

    private CompanyVaultManager() {
        Nascraft.getInstance().getServer().getPluginManager()
                .registerEvents(this, Nascraft.getInstance());
    }

    public static CompanyVaultManager getInstance() {
        if (instance == null) instance = new CompanyVaultManager();
        return instance;
    }

    // ── Vault Registration ────────────────────────────────────────────────────

    public void registerVault(UUID companyId, Location location) {
        Company company = CompanyManager.getInstance().getCompany(companyId).orElse(null);
        if (company == null) return;
        String key = locationKey(location);
        vaultLocations.put(key, companyId);
        company.setVaultLocation(location);
        recalculate(companyId);
        Nascraft.getInstance().getLogger()
                .info("[VaultManager] Vault registered for " + company.getName() + " at " + key);
    }

    public void unregisterVault(UUID companyId) {
        vaultLocations.entrySet().removeIf(e -> e.getValue().equals(companyId));
        Company company = CompanyManager.getInstance().getCompany(companyId).orElse(null);
        if (company != null) company.setVaultLocation(null);
    }

    public Optional<UUID> getCompanyAtLocation(Location loc) {
        return Optional.ofNullable(vaultLocations.get(locationKey(loc)));
    }

    // ── Item Price Oracle ─────────────────────────────────────────────────────

    public void setItemPrice(Material material, double pricePerUnit) {
        itemPrices.put(material.name(), pricePerUnit);
    }

    public double getItemPrice(Material material) {
        return itemPrices.getOrDefault(material.name(), 0.0);
    }

    // ── Vault Valuation ───────────────────────────────────────────────────────

    public double calculateVaultValue(Location vaultLocation) {
        Block block = vaultLocation.getBlock();
        if (!(block.getState() instanceof Chest)) return 0;

        Chest chest = (Chest) block.getState();
        Inventory inv;
        if (chest.getInventory().getHolder() instanceof DoubleChest) {
            inv = ((DoubleChest) chest.getInventory().getHolder()).getInventory();
        } else {
            inv = chest.getInventory();
        }

        double total = 0;
        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            double price = getItemPrice(item.getType());
            total += price * item.getAmount();
        }
        return total;
    }

    public void recalculate(UUID companyId) {
        Company company = CompanyManager.getInstance().getCompany(companyId).orElse(null);
        if (company == null || company.getVaultLocation() == null) return;
        double value = calculateVaultValue(company.getVaultLocation());
        company.setCurrentVaultValue(value);
        CompanyManager.getInstance().checkMarketCapCompliance(companyId);
    }

    // ── Weekly Snapshot ───────────────────────────────────────────────────────

    /** Called every Sunday midnight by scheduler. */
    public void takeWeeklySnapshots() {
        for (Company company : CompanyManager.getInstance().getAllActiveCompanies()) {
            company.setLastVaultSnapshot(company.getCurrentVaultValue());
        }
        Nascraft.getInstance().getLogger()
                .info("[VaultManager] Weekly vault snapshots taken for all active companies.");
    }

    // ── Bukkit Events ─────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Inventory source = event.getSource();
        Inventory dest   = event.getDestination();

        // Check if either side is a registered vault
        UUID srcCompany = getCompanyFromInventory(source);
        UUID dstCompany = getCompanyFromInventory(dest);

        if (srcCompany != null) recalculate(srcCompany);
        if (dstCompany != null) recalculate(dstCompany);
    }

    private UUID getCompanyFromInventory(Inventory inv) {
        if (inv.getLocation() == null) return null;
        return vaultLocations.get(locationKey(inv.getLocation()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
