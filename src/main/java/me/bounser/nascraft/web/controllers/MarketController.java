package me.bounser.nascraft.web.controllers;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.managers.ImagesManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.limitorders.Duration;
import me.bounser.nascraft.market.limitorders.LimitOrdersManager;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.web.WebServerManager.StatusResponse;
import me.bounser.nascraft.web.dto.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MarketController {

    public static void register(Javalin webServer, MarketManager marketManager) {
        webServer.get("/api/items", ctx -> {
            List<ItemDTO> items = marketManager.getAllItemData();
            ctx.json(items != null ? items : new ArrayList<>());
        });
        webServer.get("/api/buy-projected/{quantity}/{identifier}", ctx -> {
            String identifier = ctx.pathParam("identifier");
            String quantityStr = ctx.pathParam("quantity");
            try {
                int quantity = Integer.parseInt(quantityStr);
                Item item = MarketManager.getInstance().getItem(identifier);
                if (item == null) {
                    ctx.status(HttpStatus.NOT_FOUND).json(new StatusResponse("Item not found: " + identifier));
                    return;
                }
                double cost = item.getPrice().getProjectedCost(-quantity, 1);
                ctx.json(cost);
            } catch (NumberFormatException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Invalid quantity format."));
            }
        });
        webServer.get("/api/sell-projected/{quantity}/{identifier}", ctx -> {
            String identifier = ctx.pathParam("identifier");
            String quantityStr = ctx.pathParam("quantity");
            try {
                int quantity = Integer.parseInt(quantityStr);
                Item item = MarketManager.getInstance().getItem(identifier);
                if (item == null) {
                    ctx.status(HttpStatus.NOT_FOUND).json(new StatusResponse("Item not found: " + identifier));
                    return;
                }
                double revenue = item.getPrice().getProjectedCost(quantity, 1);
                ctx.json(revenue);
            } catch (NumberFormatException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(new StatusResponse("Invalid quantity format."));
            }
        });
        webServer.get("/api/top-portfolios", ctx -> {
            List<PortfolioDTO> portfolios = marketManager.getTopPortfolios();
            ctx.json(portfolios != null ? portfolios : new ArrayList<>());
        });
        webServer.get("/api/categories", ctx -> {
            List<CategoryDTO> categories = marketManager.getCategoriesDTO();
            ctx.json(categories != null ? categories : new ArrayList<>());
        });
        webServer.get("/api/charts/cpi", ctx -> {
            List<TimeSeriesDTO> dataPoints = marketManager.getCPITimeSeries();
            ctx.json(dataPoints != null ? dataPoints : new ArrayList<>());
        });
        webServer.get("/api/charts/money-supply", ctx -> {
            List<TimeSeriesDTO> dataPoints = marketManager.getMoneySupply();
            ctx.json(dataPoints != null ? dataPoints : new ArrayList<>());
        });
        webServer.get("/api/popular-item", ctx -> {
            ItemDTO popularItem = marketManager.getPopularItem();
            if (popularItem != null) {
                ctx.json(popularItem);
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(new StatusResponse("Popular item data not available."));
            }
        });
        webServer.get("/api/charts/item/{identifier}", ctx -> {
            String identifier = ctx.pathParam("identifier");
            List<ItemTimeSeriesDTO> dataPoints = marketManager.getItemTimeSeries(identifier);
            ctx.json(dataPoints != null ? dataPoints : new ArrayList<>());
        });
        webServer.get("/api/charts/item-day/{identifier}", ctx -> {
            String identifier = ctx.pathParam("identifier");
            List<ItemTimeSeriesDTO> dataPoints = marketManager.getItemTimeSeriesDay(identifier);
            ctx.json(dataPoints != null ? dataPoints : new ArrayList<>());
        });
        webServer.get("/api/charts/item-month/{identifier}", ctx -> {
            String identifier = ctx.pathParam("identifier");
            List<ItemTimeSeriesDTO> dataPoints = marketManager.getItemTimeSeriesMonth(identifier);
            ctx.json(dataPoints != null ? dataPoints : new ArrayList<>());
        });
        webServer.get("/api/charts/taxes", ctx -> {
            List<TimeSeriesDTO> dataPoints = marketManager.getAllTaxesCollected();
            ctx.json(dataPoints != null ? dataPoints : new ArrayList<>());
        });
        webServer.get("/api/limits", ctx -> {
            List<Duration> durationOptions = LimitOrdersManager.getInstance().getDurationOptions();
            ctx.json(durationOptions != null ? durationOptions : new ArrayList<>());
        });
        webServer.get("/api/portfolio-limit", ctx -> {
            ctx.json(Config.getInstance().getPortfolioMaxStorage());
        });
        webServer.get("/api/taxes/buy/{identifier}", ctx -> {
            String identifier = ctx.pathParam("identifier");
            double taxRate = Config.getInstance().getTaxBuyPercentage(identifier);
            ctx.json(taxRate);
        });
        webServer.get("/api/taxes/sell/{identifier}", ctx -> {
            String identifier = ctx.pathParam("identifier");
            double taxRate = Config.getInstance().getTaxSellPercentage(identifier);
            ctx.json(taxRate);
        });
        webServer.get("/api/icons/{identifier}.png", ctx -> {
            String identifier = ctx.pathParam("identifier");
            BufferedImage image = ImagesManager.getInstance().getImage(identifier);
            if (image != null) {
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "png", baos);
                    byte[] imageBytes = baos.toByteArray();
                    ctx.header("Cache-Control", "public, max-age=" + TimeUnit.HOURS.toSeconds(1));
                    ctx.contentType("image/png").result(imageBytes);
                } catch (IOException e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(new StatusResponse("Error processing image."));
                }
            } else {
                ctx.status(HttpStatus.NOT_FOUND).json(new StatusResponse("Image not found."));
            }
        });
        webServer.get("/api/last-transactions", ctx -> {
            List<DetailedTransactionDTO> transactions = marketManager.getLastTransactions();
            ctx.json(transactions != null ? transactions : new ArrayList<>());
        });
        webServer.get("/api/lang", ctx -> {
            HashMap<String, Object> langMap = new HashMap<>();
            langMap.put("strings", me.bounser.nascraft.config.lang.Lang.get().getWebStrings());
            langMap.put("separator", me.bounser.nascraft.config.lang.Lang.get().getSeparator());
            ctx.json(langMap);
        });

        webServer.get("/api/server-time", ctx -> {
            HashMap<String, Long> timeMap = new HashMap<>();
            timeMap.put("time", System.currentTimeMillis() / 1000);
            ctx.json(timeMap);
        });
    }
}
