package me.bounser.nascraft.web.controllers;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import jakarta.servlet.http.HttpSession;
import me.bounser.nascraft.managers.DebtManager;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.portfolio.Portfolio;
import me.bounser.nascraft.portfolio.PortfoliosManager;
import me.bounser.nascraft.web.WebServerManager.*;
import me.bounser.nascraft.web.dto.DebtDTO;
import me.bounser.nascraft.web.dto.PlayerStatsDTO;
import me.bounser.nascraft.web.dto.TransactionDTO;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class PlayerController {

    public static void register(Javalin webServer, JavaPlugin plugin, MarketManager marketManager, PortfoliosManager portfoliosManager, MoneyManager moneyManager) {

        Handler requireMinecraftLink = ctx -> {
            HttpSession session = ctx.req().getSession(false);
            if (session == null || session.getAttribute("minecraft-uuid") == null) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(new StatusResponse("Unauthorized: Minecraft account not logged in."));
                ctx.skipRemainingHandlers();
            }
        };

        String[] protectedPaths = {
                "/api/balance", "/api/portfolio-value", "/api/portfolio-capacity",
                "/api/history", "/api/stats", "/api/debt", "/api/portfolio", "/api/trade",
                "/api/buy-slot"
        };
        for (String path : protectedPaths) {
            webServer.before(path, requireMinecraftLink);
        }

        webServer.get("/api/balance", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");
            UUID uuid = UUID.fromString(uuidStr);
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            double balance = moneyManager.getBalance(player, CurrenciesManager.getInstance().getDefaultCurrency());
            ctx.json(balance);
        });

        webServer.get("/api/portfolio-value", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");
            UUID uuid = UUID.fromString(uuidStr);
            Portfolio portfolio = portfoliosManager.getPortfolio(uuid);
            double value = (portfolio != null) ? portfolio.getValueOfDefaultCurrency() : 0.0;
            ctx.json(value);
        });

        webServer.get("/api/portfolio-capacity", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");
            UUID uuid = UUID.fromString(uuidStr);
            Portfolio portfolio = portfoliosManager.getPortfolio(uuid);
            int slots = (portfolio != null) ? portfolio.getCapacity() : 0;
            ctx.json(slots);
        });

        webServer.get("/api/history", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String username = (String) session.getAttribute("minecraft-user-name");
            List<TransactionDTO> transactions = marketManager.getHistoryPlayer(username);
            ctx.json(transactions != null ? transactions : new ArrayList<>());
        });

        webServer.get("/api/stats", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");
            List<PlayerStatsDTO> stats = marketManager.getPlayerStats(uuidStr);
            ctx.json(stats != null ? stats : new ArrayList<>());
        });

        webServer.get("/api/debt", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String username = (String) session.getAttribute("minecraft-user-name");
            DebtDTO debt = marketManager.getDebtPlayer(username);
            if (debt == null) {
                ctx.json(new DebtDTO(0, 0, 0, 0, 0, 0, "00:00"));
            } else {
                ctx.json(debt);
            }
        });

        webServer.get("/api/next-slot-price", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");
            UUID uuid = UUID.fromString(uuidStr);
            Portfolio portfolio = portfoliosManager.getPortfolio(uuid);
            double price = (portfolio != null) ? portfolio.getNextSlotPrice() : 0;
            ctx.json(price);
        });

        webServer.get("/api/portfolio", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");
            UUID uuid = UUID.fromString(uuidStr);
            Portfolio portfolio = portfoliosManager.getPortfolio(uuid);
            HashMap<String, Integer> content = new HashMap<>();
            if (portfolio != null) {
                HashMap<Item, Integer> portfolioContent = portfolio.getContent();
                for (Item item : portfolioContent.keySet()) {
                    content.put(item.getIdentifier(), portfolioContent.get(item));
                }
            }
            ctx.json(content);
        });

        webServer.post("/api/trade", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");
            SimplifiedTradeRequest tradeReq;
            try {
                tradeReq = ctx.bodyAsClass(SimplifiedTradeRequest.class);
                if (tradeReq.getIdentifier() == null || tradeReq.getIdentifier().trim().isEmpty() ||
                        tradeReq.getQuantity() <= 0 ||
                        (tradeReq.getType() == null ||
                                (!tradeReq.getType().equalsIgnoreCase("BUY") && !tradeReq.getType().equalsIgnoreCase("SELL")))) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Invalid trade request parameters."));
                    return;
                }
                Item item = marketManager.getItem(tradeReq.getIdentifier());
                if (item == null) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Item not found: " + tradeReq.getIdentifier()));
                    return;
                }
            } catch (Exception e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Invalid request format."));
                return;
            }

            CompletableFuture<TradeResponse> compFuture = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                UUID playerUUID = UUID.fromString(uuidStr);
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
                try {
                    Item item = marketManager.getItem(tradeReq.getIdentifier());
                    if (item == null) { compFuture.complete(new TradeResponse(false, "Item not found (sync).", null)); return; }
                    Portfolio portfolio = portfoliosManager.getPortfolio(playerUUID);
                    if (portfolio == null) { compFuture.complete(new TradeResponse(false, "Portfolio not found.", null)); return; }

                    switch (tradeReq.getType().toLowerCase()) {
                        case "buy":
                            if (!MarketManager.getInstance().getActive()) { compFuture.complete(new TradeResponse(false, "Market closed.", null)); return; }
                            if (!portfolio.hasSpace(item, tradeReq.getQuantity())) { compFuture.complete(new TradeResponse(false, "Insufficient portfolio space.", null)); return; }
                            double requiredBalance = item.getPrice().getProjectedCost(-tradeReq.getQuantity(), item.getPrice().getBuyTaxMultiplier());
                            if (!moneyManager.hasEnoughMoney(player, item.getCurrency(), requiredBalance)) { compFuture.complete(new TradeResponse(false, "Insufficient funds.", null)); return; }
                            double buyResult = item.buy(tradeReq.getQuantity(), playerUUID, false);
                            if (buyResult != 0) {
                                portfolio.addItem(item, tradeReq.getQuantity());
                                compFuture.complete(new TradeResponse(true, "Buy successful.", buyResult));
                            } else {
                                compFuture.complete(new TradeResponse(false, "Buy failed.", null));
                            }
                            break;
                        case "sell":
                            if (!MarketManager.getInstance().getActive()) { compFuture.complete(new TradeResponse(false, "Market closed.", null)); return; }
                            if (DebtManager.getInstance().getDebtOfPlayer(playerUUID) > 0) { compFuture.complete(new TradeResponse(false, "Cannot sell with debt.", null)); return; }
                            if (!portfolio.hasItem(item, tradeReq.getQuantity())) { compFuture.complete(new TradeResponse(false, "Insufficient items.", null)); return; }
                            double sellResult = item.sell(tradeReq.getQuantity(), playerUUID, false);
                            if (sellResult != -1) {
                                portfolio.removeItem(item, tradeReq.getQuantity());
                                compFuture.complete(new TradeResponse(true, "Sell successful.", sellResult));
                            } else {
                                compFuture.complete(new TradeResponse(false, "Sell failed.", null));
                            }
                            break;
                        default: compFuture.complete(new TradeResponse(false, "Invalid trade type.", null));
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error during sync trade for " + playerUUID, e);
                    compFuture.complete(new TradeResponse(false, "Server error during trade.", null));
                }
            });

            ctx.future(() -> compFuture.thenAccept(tradeResult -> {
                ctx.status(tradeResult.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).json(tradeResult);
            }).exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "Error processing trade future for " + uuidStr, e);
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Trade processing error."));
                return null;
            }));
        });

        webServer.post("/api/buy-slot", ctx -> {
            HttpSession session = ctx.req().getSession(false);
            String uuidStr = (String) session.getAttribute("minecraft-uuid");

            CompletableFuture<BuySlotResponse> compFuture = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                UUID playerUUID = UUID.fromString(uuidStr);
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
                try {
                    Portfolio portfolio = portfoliosManager.getPortfolio(playerUUID);
                    if (portfolio == null) { compFuture.complete(new BuySlotResponse(false, "Portfolio not found.", null)); return; }

                    if (portfolio.getCapacity() >= 40) {
                        compFuture.complete(new BuySlotResponse(false, "Maximum portfolio size reached.", null)); return;
                    }

                    double price = portfolio.getNextSlotPrice();

                    if (!MoneyManager.getInstance().hasEnoughMoney(player, CurrenciesManager.getInstance().getDefaultCurrency(), price)) {
                        compFuture.complete(new BuySlotResponse(false, "You can't afford the expansion.", null)); return;
                    }

                    MoneyManager.getInstance().simpleWithdraw(player, CurrenciesManager.getInstance().getDefaultCurrency(), price);

                    if (!MoneyManager.getInstance().hasEnoughMoney(player, CurrenciesManager.getInstance().getDefaultCurrency(), price)) {
                        compFuture.complete(new BuySlotResponse(true, "You have expanded your portfolio.", price)); return;
                    }

                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error during sync buy slot for " + playerUUID, e);
                    compFuture.complete(new BuySlotResponse(false, "Server error.", null)); return;
                }
                compFuture.complete(new BuySlotResponse(false, "Error processing slot buy.", null));
            });

            ctx.future(() -> compFuture.thenAccept(result -> {
                ctx.status(result.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).json(result);
            }).exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "Error processing slot buy for " + uuidStr, e);
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Slot buy processing error."));
                return null;
            }));
        });
    }
}
