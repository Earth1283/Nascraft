package me.bounser.nascraft.web.controllers;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import me.bounser.nascraft.exchange.clob.ExchangeOrder;
import me.bounser.nascraft.exchange.clob.MatchingEngine;
import me.bounser.nascraft.exchange.clob.OrderBook;
import me.bounser.nascraft.exchange.clob.OrderSide;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyManager;
import me.bounser.nascraft.exchange.shares.ShareRegistry;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class ExchangeController {

    public static void register(Javalin app) {

        // GET /api/exchange/companies — all active companies
        app.get("/api/exchange/companies", ctx -> {
            List<Company> companies = CompanyManager.getInstance().getAllActiveCompanies();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Company c : companies) {
                OrderBook book = MatchingEngine.getInstance().getOrCreateBook(c.getTicker());
                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("ticker",           c.getTicker());
                dto.put("name",             c.getName());
                dto.put("status",           c.getStatus().name());
                dto.put("sharePrice",       c.getSharePrice());
                dto.put("marketCap",        c.getMarketCap());
                dto.put("outstandingShares", c.getOutstandingShares());
                dto.put("vaultValue",       c.getCurrentVaultValue());
                dto.put("bestBid",          book.getBestBid().map(BigDecimal::doubleValue).orElse(null));
                dto.put("bestAsk",          book.getBestAsk().map(BigDecimal::doubleValue).orElse(null));
                result.add(dto);
            }
            ctx.json(result);
        });

        // GET /api/exchange/companies/{ticker} — single company with order book depth
        app.get("/api/exchange/companies/{ticker}", ctx -> {
            String ticker = ctx.pathParam("ticker").toUpperCase();
            Company c = CompanyManager.getInstance().getCompanyByTicker(ticker).orElse(null);
            if (c == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Unknown ticker: " + ticker));
                return;
            }
            OrderBook book = MatchingEngine.getInstance().getOrCreateBook(ticker);

            List<Map<String, Object>> depth = new ArrayList<>();
            for (ExchangeOrder o : book.getAllBids()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("side",     "BUY");
                entry.put("price",    o.getPrice().doubleValue());
                entry.put("quantity", o.getRemainingQuantity().doubleValue());
                depth.add(entry);
            }
            for (ExchangeOrder o : book.getAllAsks()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("side",     "SELL");
                entry.put("price",    o.getPrice().doubleValue());
                entry.put("quantity", o.getRemainingQuantity().doubleValue());
                depth.add(entry);
            }

            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("ticker",            c.getTicker());
            dto.put("name",              c.getName());
            dto.put("status",            c.getStatus().name());
            dto.put("sharePrice",        c.getSharePrice());
            dto.put("marketCap",         c.getMarketCap());
            dto.put("outstandingShares", c.getOutstandingShares());
            dto.put("vaultValue",        c.getCurrentVaultValue());
            dto.put("bestBid",           book.getBestBid().map(BigDecimal::doubleValue).orElse(null));
            dto.put("bestAsk",           book.getBestAsk().map(BigDecimal::doubleValue).orElse(null));
            dto.put("depth",             depth);
            ctx.json(dto);
        });

        // GET /api/exchange/leaderboard — top 10 players by exchange portfolio value
        app.get("/api/exchange/leaderboard", ctx -> {
            List<Company> companies = CompanyManager.getInstance().getAllActiveCompanies();
            Map<UUID, Double> portfolioValue = new HashMap<>();
            Map<UUID, List<Map<String, Object>>> portfolioPositions = new HashMap<>();

            for (Company c : companies) {
                Map<UUID, BigDecimal> shareholders = ShareRegistry.getInstance().getShareholders(c.getId());
                shareholders.forEach((uuid, shares) -> {
                    double val = shares.doubleValue() * c.getSharePrice();
                    portfolioValue.merge(uuid, val, Double::sum);
                    portfolioPositions.computeIfAbsent(uuid, k -> new ArrayList<>())
                            .add(Map.of(
                                    "ticker", c.getTicker(),
                                    "shares", shares.doubleValue(),
                                    "value",  val));
                });
            }

            List<Map.Entry<UUID, Double>> sorted = portfolioValue.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                    .limit(10)
                    .collect(Collectors.toList());

            List<Map<String, Object>> result = new ArrayList<>();
            int rank = 1;
            for (Map.Entry<UUID, Double> entry : sorted) {
                UUID uuid = entry.getKey();
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                if (name == null) name = uuid.toString().substring(0, 8);

                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("rank",       rank++);
                dto.put("playerName", name);
                dto.put("totalValue", entry.getValue());
                dto.put("positions",  portfolioPositions.getOrDefault(uuid, Collections.emptyList()));
                result.add(dto);
            }
            ctx.json(result);
        });
    }
}
