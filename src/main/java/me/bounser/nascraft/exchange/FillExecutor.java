package me.bounser.nascraft.exchange;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.exchange.clob.ExchangeOrder;
import me.bounser.nascraft.exchange.clob.Fill;
import me.bounser.nascraft.exchange.clob.OrderSide;
import me.bounser.nascraft.exchange.clob.OrderType;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyManager;
import me.bounser.nascraft.exchange.shares.InsufficientSharesException;
import me.bounser.nascraft.exchange.shares.ShareRegistry;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Executes the economy and share-registry transfers that result from a matched fill.
 *
 * May be called from any thread (fill listener is invoked inside MatchingEngine.submitOrder).
 * All Vault economy operations are dispatched to the main thread via the Bukkit scheduler.
 *
 * Escrow model
 * ──────────────
 * LIMIT BUY:  funds (qty × price) are held in EscrowManager before order submission.
 *             On fill: consume held funds and deposit them to the seller.
 *             On cancel: refund remaining held funds.
 *
 * LIMIT SELL: shares are removed from ShareRegistry before submission and held in EscrowManager.
 *             On fill: consume held shares and credit them to the buyer.
 *             On cancel: return remaining held shares.
 *
 * MARKET orders: no escrow. Balance/share checks happen at fill time.
 */
public class FillExecutor {

    private static final DecimalFormat FMT = new DecimalFormat("#,##0.##");
    private static final String PREFIX = "§3[Exchange] §r";

    /** Called from the MatchingEngine fill listener. */
    public static void execute(Fill fill) {
        ExchangeOrder makerOrder = fill.getMakerOrder();
        ExchangeOrder takerOrder = fill.getTakerOrder();

        // Identify the buy and sell sides
        ExchangeOrder buyOrder  = makerOrder.getSide() == OrderSide.BUY  ? makerOrder : takerOrder;
        ExchangeOrder sellOrder = makerOrder.getSide() == OrderSide.SELL ? makerOrder : takerOrder;

        UUID buyerUuid  = buyOrder.getOwnerUuid();
        UUID sellerUuid = sellOrder.getOwnerUuid();
        BigDecimal filledQty = fill.getFilledQty();
        BigDecimal cost      = fill.getNotional(); // filledQty × fillPrice

        String ticker = makerOrder.getTickerSymbol();
        Company company = CompanyManager.getInstance().getCompanyByTicker(ticker).orElse(null);
        if (company == null) {
            Nascraft.getInstance().getLogger().warning("[FillExecutor] Unknown ticker: " + ticker);
            return;
        }
        UUID companyId = company.getId();

        EscrowManager escrow = EscrowManager.getInstance();
        boolean buyIsLimit  = buyOrder.getType()  == OrderType.LIMIT;
        boolean sellIsLimit = sellOrder.getType() == OrderType.LIMIT;

        // ── 1. Transfer shares ────────────────────────────────────────────────
        // Shares originate from seller's escrow (limit sell) or registry (market sell).
        if (sellIsLimit) {
            BigDecimal consumed = escrow.consumeShares(sellOrder.getOrderId(), filledQty);
            if (consumed.compareTo(filledQty) < 0) {
                Nascraft.getInstance().getLogger().warning(
                    "[FillExecutor] Share escrow underflow on order " + sellOrder.getOrderId());
            }
        } else {
            // Market sell: pull shares directly from registry
            try {
                ShareRegistry.getInstance().removeShares(sellerUuid, companyId, filledQty);
            } catch (InsufficientSharesException e) {
                Nascraft.getInstance().getLogger().warning(
                    "[FillExecutor] Market-sell insufficient shares for " + sellerUuid + ": " + e.getMessage());
                return;
            }
        }

        // Credit buyer's share position
        ShareRegistry.getInstance().addShares(buyerUuid, companyId, filledQty);

        // ── 2. Transfer money (main thread required for Vault) ────────────────
        BigDecimal fillPrice = fill.getPrice();

        Bukkit.getScheduler().runTask(Nascraft.getInstance(), () -> {
            Economy econ = Nascraft.getEconomy();
            if (econ == null) {
                Nascraft.getInstance().getLogger().warning("[FillExecutor] Vault economy unavailable.");
                return;
            }

            OfflinePlayer seller = Bukkit.getOfflinePlayer(sellerUuid);

            if (buyIsLimit) {
                // Funds are in escrow — consume and pay seller
                BigDecimal consumed = escrow.consumeFunds(buyOrder.getOrderId(), cost);
                econ.depositPlayer(seller, consumed.doubleValue());
            } else {
                // Market buy: withdraw from buyer's live balance
                OfflinePlayer buyer = Bukkit.getOfflinePlayer(buyerUuid);
                if (!econ.has(buyer, cost.doubleValue())) {
                    Nascraft.getInstance().getLogger()
                        .log(Level.WARNING, "[FillExecutor] Market-buy buyer " + buyerUuid + " cannot cover fill of " + cost);
                    // Rollback share transfer
                    try {
                        ShareRegistry.getInstance().removeShares(buyerUuid, companyId, filledQty);
                    } catch (InsufficientSharesException ignored) {}
                    ShareRegistry.getInstance().addShares(sellerUuid, companyId, filledQty);
                    return;
                }
                econ.withdrawPlayer(buyer, cost.doubleValue());
                econ.depositPlayer(seller, cost.doubleValue());
            }

            // ── 3. Update last-traded price ───────────────────────────────────
            company.setSharePrice(fillPrice.doubleValue());

            // ── 4. Notify online players ──────────────────────────────────────
            notify(buyerUuid,  "§aBought", filledQty, fillPrice, ticker);
            notify(sellerUuid, "§cSold",   filledQty, fillPrice, ticker);

            // ── 5. Clean up order tracking when fully filled ──────────────────
            if (!buyOrder.isActive())  OrderTracker.getInstance().remove(buyerUuid,  buyOrder.getOrderId());
            if (!sellOrder.isActive()) OrderTracker.getInstance().remove(sellerUuid, sellOrder.getOrderId());

            // ── 6. Persist position, company, and order status asynchronously ─
            Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
                try {
                    ExchangeDatabase db = ExchangeDatabase.getInstance();
                    db.savePosition(buyerUuid,  companyId, ShareRegistry.getInstance().getHolding(buyerUuid,  companyId));
                    db.savePosition(sellerUuid, companyId, ShareRegistry.getInstance().getHolding(sellerUuid, companyId));
                    db.saveCompany(company);
                    db.updateOrderStatus(makerOrder.getOrderId(), makerOrder.getStatus(), makerOrder.getFilledQuantity());
                    db.updateOrderStatus(takerOrder.getOrderId(), takerOrder.getStatus(), takerOrder.getFilledQuantity());
                } catch (IllegalStateException ignored) {
                    // ExchangeDatabase not initialised (SQLite-only setup)
                }
            });
        });
    }

    private static void notify(UUID uuid, String verb, BigDecimal qty, BigDecimal price, String ticker) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            p.sendMessage(PREFIX + verb + " §f" + FMT.format(qty) + "x §e" + ticker
                    + " §7@ §f" + FMT.format(price) + " §7each");
        }
    }
}
