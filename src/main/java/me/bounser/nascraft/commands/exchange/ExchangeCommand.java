package me.bounser.nascraft.commands.exchange;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.exchange.EscrowManager;
import me.bounser.nascraft.exchange.ExchangeDatabase;
import me.bounser.nascraft.exchange.OrderTracker;
import me.bounser.nascraft.exchange.clob.*;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyManager;
import me.bounser.nascraft.exchange.company.CompanyStatus;
import me.bounser.nascraft.exchange.integrity.MarketStateManager;
import me.bounser.nascraft.exchange.ipo.IpoException;
import me.bounser.nascraft.exchange.ipo.IpoManager;
import me.bounser.nascraft.exchange.shares.InsufficientSharesException;
import me.bounser.nascraft.exchange.shares.ShareRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ExchangeCommand extends Command {

    private static final String PREFIX = "§3[EX] §r";
    private static final String ERR    = "§3[EX] §c";
    private static final DecimalFormat FMT = new DecimalFormat("#,##0.##");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final List<String> SUBS = Arrays.asList(
            "list", "info", "ipo", "buy", "sell", "orders", "cancel", "portfolio", "top");

    public ExchangeCommand() {
        super(
            "exchange",
            new String[]{ alias() },
            "Stock exchange — IPOs, buy/sell shares, portfolios",
            "nascraft.exchange"
        );
    }

    private static String alias() {
        String a = Config.getInstance().getCommandAlias("exchange");
        return a != null ? a : "ex";
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ERR + "Exchange commands are player-only.");
            return;
        }
        Player player = (Player) sender;

        if (!player.hasPermission("nascraft.exchange")) {
            player.sendMessage(ERR + "You don't have permission to use the exchange.");
            return;
        }

        if (args.length == 0) { sendHelp(player); return; }

        switch (args[0].toLowerCase()) {
            case "list":      cmdList(player);           break;
            case "info":      cmdInfo(player, args);     break;
            case "ipo":       cmdIpo(player, args);      break;
            case "buy":       cmdBuy(player, args);      break;
            case "sell":      cmdSell(player, args);     break;
            case "orders":    cmdOrders(player);         break;
            case "cancel":    cmdCancel(player, args);   break;
            case "portfolio": cmdPortfolio(player);      break;
            case "top":       cmdTop(player);            break;
            default:          sendHelp(player);          break;
        }
    }

    // ── /exchange (no args) — interactive help ────────────────────────────────

    private void sendHelp(Player player) {
        send(player, bar(" Exchange Help "));
        send(player, MM.deserialize("<color:#57c5c5>[Exchange]</color>  <white>Nascraft Stock Exchange</white>"));
        send(player, Component.empty());

        send(player, MM.deserialize("<yellow><bold>MARKET</bold></yellow>"));
        send(player, helpLine("[list]",
                ClickEvent.runCommand("/exchange list"),
                "View all companies currently listed on the exchange.",
                "View all listed companies"));
        send(player, helpLine("[top]",
                ClickEvent.runCommand("/exchange top"),
                "Top 10 players by total exchange portfolio value.\nHover over a name to see their holdings.",
                "Top 10 exchange portfolios"));
        send(player, helpLine("[info]",
                ClickEvent.suggestCommand("/exchange info "),
                "Usage: /exchange info <ticker>\nSee price, order book, and company details.",
                "Company details & order book"));

        send(player, Component.empty());
        send(player, MM.deserialize("<yellow><bold>TRADING</bold></yellow>"));
        send(player, helpLine("[buy]",
                ClickEvent.suggestCommand("/exchange buy "),
                "Usage: /exchange buy <ticker> <qty> [limitPrice]\n\nOmit limitPrice for a market order.\nWith a limit price, funds are held in escrow.",
                "Buy shares (market or limit order)"));
        send(player, helpLine("[sell]",
                ClickEvent.suggestCommand("/exchange sell "),
                "Usage: /exchange sell <ticker> <qty> [limitPrice]\n\nOmit limitPrice for a market order.\nWith a limit price, shares are held in escrow.",
                "Sell shares (market or limit order)"));
        send(player, helpLine("[orders]",
                ClickEvent.runCommand("/exchange orders"),
                "View all your currently open limit orders.\nClick an order's [✗] to cancel it.",
                "Your open limit orders"));
        send(player, helpLine("[cancel]",
                ClickEvent.suggestCommand("/exchange cancel "),
                "Usage: /exchange cancel <orderId>\nGet the order ID from /exchange orders.\nCancelling refunds your held funds or shares.",
                "Cancel an open order"));

        send(player, Component.empty());
        send(player, MM.deserialize("<gold><bold>COMPANIES</bold></bold></gold>"));
        send(player, helpLine("[ipo]",
                ClickEvent.runCommand("/exchange ipo help"),
                "List your own in-game company on the exchange.\nAdmin approval required before trading opens.",
                "List your company on the exchange"));
        send(player, helpLine("[portfolio]",
                ClickEvent.runCommand("/exchange portfolio"),
                "View all share positions you currently hold\nand their total estimated value.",
                "Your share holdings & total value"));

        send(player, bar(""));
    }

    // ── /exchange list ────────────────────────────────────────────────────────

    private void cmdList(Player player) {
        List<Company> active = CompanyManager.getInstance().getAllActiveCompanies();
        if (active.isEmpty()) {
            player.sendMessage(PREFIX + "No companies are currently listed on the exchange.");
            return;
        }
        send(player, bar(" Listed Companies "));
        for (Company c : active) {
            String halt = c.getStatus() == CompanyStatus.HALTED ? " <red>[HALTED]</red>" : "";
            Component ticker = clickable(
                    "<yellow>[" + c.getTicker() + "]</yellow>",
                    ClickEvent.runCommand("/exchange info " + c.getTicker()),
                    "<gray>Click to view details for <yellow>" + c.getTicker() + "</yellow></gray>");
            Component rest = MM.deserialize(
                    " <gray>│</gray> <white>" + c.getName() + "</white>"
                    + "  <gray>Price:</gray> <green>" + FMT.format(c.getSharePrice()) + "</green>"
                    + "  <gray>MCap:</gray> <aqua>" + FMT.format(c.getMarketCap()) + "</aqua>"
                    + halt);
            send(player, ticker.append(rest));
        }
        send(player, Component.text("  ").append(
                clickable("<gold>[+ List your company →]</gold>",
                        ClickEvent.runCommand("/exchange ipo help"),
                        "<gray>Start an IPO to list your own in-game company.</gray>")));
        send(player, bar(""));
    }

    // ── /exchange info <ticker> ───────────────────────────────────────────────

    private void cmdInfo(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(ERR + "Usage: /exchange info <ticker>"); return; }
        String ticker = args[1].toUpperCase();
        Company c = CompanyManager.getInstance().getCompanyByTicker(ticker).orElse(null);
        if (c == null) { player.sendMessage(ERR + "Unknown ticker: " + ticker); return; }

        OrderBook book = MatchingEngine.getInstance().getOrCreateBook(ticker);

        send(player, bar(" " + ticker + " — " + c.getName() + " "));
        player.sendMessage("§7Status:       " + fmtStatus(c.getStatus()));
        player.sendMessage("§7Last Price:   §a" + FMT.format(c.getSharePrice()));
        player.sendMessage("§7Best Bid:     " + book.getBestBid().map(b -> "§a" + FMT.format(b)).orElse("§8—"));
        player.sendMessage("§7Best Ask:     " + book.getBestAsk().map(a -> "§c" + FMT.format(a)).orElse("§8—"));
        player.sendMessage("§7Market Cap:   §b" + FMT.format(c.getMarketCap()));
        player.sendMessage("§7Vault Value:  §b" + FMT.format(c.getCurrentVaultValue()));
        player.sendMessage("§7Outstanding:  §f" + FMT.format(c.getOutstandingShares()) + " shares");
        if (c.getProspectusText() != null && !c.getProspectusText().isEmpty())
            player.sendMessage("§7Prospectus:   §f" + c.getProspectusText());
        if (c.getStatus() == CompanyStatus.HALTED && c.getHaltReason() != null)
            player.sendMessage("§cHalt reason:  §f" + c.getHaltReason());

        if (c.getStatus() == CompanyStatus.ACTIVE) {
            Component buyBtn = clickable(
                    " <green><bold>[ BUY ]</bold></green> ",
                    ClickEvent.suggestCommand("/exchange buy " + ticker + " "),
                    "<green>Place a buy order for " + ticker + "</green>\n"
                    + "<gray>/exchange buy " + ticker + " <qty> [limitPrice]</gray>");
            Component sellBtn = clickable(
                    " <red><bold>[ SELL ]</bold></red> ",
                    ClickEvent.suggestCommand("/exchange sell " + ticker + " "),
                    "<red>Place a sell order for " + ticker + "</red>\n"
                    + "<gray>/exchange sell " + ticker + " <qty> [limitPrice]</gray>");
            send(player, Component.text("  ").append(buyBtn).append(sellBtn));
        }
        send(player, bar(""));
    }

    // ── /exchange ipo <name> <ticker> <price> <shares> <fee> [prospectus] ────

    private void cmdIpo(Player player, String[] args) {
        // Show guided wizard when no args or explicitly asked for help
        if (args.length < 2 || (args.length == 2 && args[1].equalsIgnoreCase("help"))) {
            sendIpoWizard(player);
            return;
        }

        if (args.length < 6) {
            sendIpoWizard(player);
            return;
        }

        String name   = args[1];
        String ticker = args[2].toUpperCase();
        double price, shares, fee;
        try {
            price  = Double.parseDouble(args[3]);
            shares = Double.parseDouble(args[4]);
            fee    = Double.parseDouble(args[5]);
        } catch (NumberFormatException e) {
            player.sendMessage(ERR + "Price, shares, and fee must be valid numbers.");
            return;
        }

        String prospectus = args.length > 6
                ? String.join(" ", Arrays.copyOfRange(args, 6, args.length)) : "";

        if (price <= 0 || shares <= 0) {
            player.sendMessage(ERR + "Price and share count must be positive."); return;
        }
        if (ticker.isEmpty() || ticker.length() > 6) {
            player.sendMessage(ERR + "Ticker must be 1–6 uppercase characters."); return;
        }
        if (CompanyManager.getInstance().getCompanyByTicker(ticker).isPresent()) {
            player.sendMessage(ERR + "Ticker §e" + ticker + " §cis already taken."); return;
        }

        try {
            IpoManager.IpoPending pending = IpoManager.getInstance()
                    .submitIpo(player.getUniqueId(), name, ticker, price, shares, fee, prospectus);
            player.sendMessage(PREFIX + "IPO submitted for §e" + name + " §r(§e" + ticker + "§r).");
            player.sendMessage("§7Awaiting admin approval.  Company ID: §f" + pending.companyId);
            player.sendMessage("§7Fee paid: §c" + FMT.format(fee)
                    + "  §7Share price: §a" + FMT.format(price)
                    + "  §7Shares offered: §f" + FMT.format(shares));
        } catch (IpoException e) {
            player.sendMessage(ERR + e.getMessage());
        }
    }

    private void sendIpoWizard(Player player) {
        double feeMin = Config.getInstance().getExchangeIpoFeeMin();
        double feeMax = Config.getInstance().getExchangeIpoFeeMax();

        send(player, bar(" IPO Application Guide "));
        send(player, MM.deserialize("<white>List your in-game company on the Nascraft Exchange.</white>"));
        send(player, MM.deserialize("<gray>Your application requires admin approval before trading opens.</gray>"));
        send(player, Component.empty());
        send(player, MM.deserialize("<gray>Step 1:</gray>  <white>Company name</white>   <dark_gray>(e.g. Miner's Guild, Dragon Forge)</dark_gray>"));
        send(player, MM.deserialize("<gray>Step 2:</gray>  <white>Ticker symbol</white>  <dark_gray>(2–6 letters, e.g. MINE, DRGN)</dark_gray>"));
        send(player, MM.deserialize("<gray>Step 3:</gray>  <white>Share price</white>    <dark_gray>(e.g. 100.00)</dark_gray>"));
        send(player, MM.deserialize("<gray>Step 4:</gray>  <white>Shares to issue</white><dark_gray>(e.g. 10000)</dark_gray>"));
        send(player, MM.deserialize("<gray>Step 5:</gray>  <white>IPO listing fee</white>"
                + "  <dark_gray>(min: <green>" + FMT.format(feeMin)
                + "</green>  max: <red>" + FMT.format(feeMax) + "</red>)</dark_gray>"));
        send(player, MM.deserialize("<gray>Step 6:</gray>  <white>Description</white>   <dark_gray>(optional company pitch)</dark_gray>"));
        send(player, Component.empty());
        send(player, MM.deserialize("<gray>Template command (click to fill your chat bar):</gray>"));
        send(player, clickable(
                "<gold>/exchange ipo <name> <ticker> <price> <shares> " + (int) feeMin + " <description></gold>",
                ClickEvent.suggestCommand("/exchange ipo "),
                "<gray>Clicking opens your chat bar pre-filled with /exchange ipo\nso you can type your company's details.</gray>"));
        send(player, MM.deserialize("<dark_gray>  ↑ Click the line above, then fill in each field.</dark_gray>"));
        send(player, bar(""));
    }

    // ── /exchange buy <ticker> <qty> [limitPrice] ─────────────────────────────

    private void cmdBuy(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(ERR + "Usage: /exchange buy <ticker> <qty> [limitPrice]");
            return;
        }
        if (!MarketStateManager.getInstance().isOpen()) {
            player.sendMessage(ERR + "The exchange market is currently closed."); return;
        }

        Company company = requireActive(player, args[1]);
        if (company == null) return;

        BigDecimal qty = parsePositiveDecimal(player, args[2], "quantity");
        if (qty == null) return;

        if (orderLimitReached(player)) return;

        Economy econ = Nascraft.getEconomy();

        if (args.length >= 4) {
            // ── LIMIT BUY ──────────────────────────────────────────────────────
            BigDecimal limitPrice = parsePositiveDecimal(player, args[3], "price");
            if (limitPrice == null) return;

            BigDecimal required = qty.multiply(limitPrice);
            if (econ == null || !econ.has(player, required.doubleValue())) {
                player.sendMessage(ERR + "You need §f" + FMT.format(required) + " §cto place this limit order.");
                return;
            }

            econ.withdrawPlayer(player, required.doubleValue());

            ExchangeOrder order = ExchangeOrder.limitBuy(
                    player.getUniqueId(), company.getTicker(), limitPrice, qty);
            EscrowManager.getInstance().holdFunds(order.getOrderId(), required);
            OrderTracker.getInstance().track(player.getUniqueId(), order);

            List<Fill> fills = MatchingEngine.getInstance().submitOrder(order);
            Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
                try { ExchangeDatabase.getInstance().saveOrder(order); }
                catch (IllegalStateException ignored) {}
            });

            player.sendMessage(PREFIX + "Limit BUY: §a" + FMT.format(qty) + "x §e" + company.getTicker()
                    + " §7@ §a" + FMT.format(limitPrice) + " §8(id: " + shortId(order.getOrderId()) + ")");
            if (!fills.isEmpty())
                player.sendMessage("§7  → Immediately matched " + fills.size() + " fill(s).");

        } else {
            // ── MARKET BUY ─────────────────────────────────────────────────────
            ExchangeOrder order = ExchangeOrder.marketBuy(
                    player.getUniqueId(), company.getTicker(), qty);
            OrderTracker.getInstance().track(player.getUniqueId(), order);

            List<Fill> fills = MatchingEngine.getInstance().submitOrder(order);

            if (fills.isEmpty()) {
                player.sendMessage(ERR + "No matching sell orders available — market order unexecuted.");
                OrderTracker.getInstance().remove(player.getUniqueId(), order.getOrderId());
            } else {
                player.sendMessage(PREFIX + "Market BUY: §a" + fills.size() + " fill(s) for §e"
                        + company.getTicker() + "§r.");
            }
        }
    }

    // ── /exchange sell <ticker> <qty> [limitPrice] ────────────────────────────

    private void cmdSell(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(ERR + "Usage: /exchange sell <ticker> <qty> [limitPrice]");
            return;
        }
        if (!MarketStateManager.getInstance().isOpen()) {
            player.sendMessage(ERR + "The exchange market is currently closed."); return;
        }

        Company company = requireActive(player, args[1]);
        if (company == null) return;

        BigDecimal qty = parsePositiveDecimal(player, args[2], "quantity");
        if (qty == null) return;

        BigDecimal held = ShareRegistry.getInstance().getHolding(player.getUniqueId(), company.getId());
        if (held.compareTo(qty) < 0) {
            player.sendMessage(ERR + "You only hold §f" + FMT.format(held) + " §cshares of §e"
                    + company.getTicker() + "§c.");
            return;
        }

        if (orderLimitReached(player)) return;

        if (args.length >= 4) {
            // ── LIMIT SELL ─────────────────────────────────────────────────────
            BigDecimal limitPrice = parsePositiveDecimal(player, args[3], "price");
            if (limitPrice == null) return;

            try {
                ShareRegistry.getInstance().removeShares(player.getUniqueId(), company.getId(), qty);
            } catch (InsufficientSharesException e) {
                player.sendMessage(ERR + "Insufficient shares."); return;
            }

            ExchangeOrder order = ExchangeOrder.limitSell(
                    player.getUniqueId(), company.getTicker(), limitPrice, qty);
            EscrowManager.getInstance().holdShares(order.getOrderId(), company.getId(), qty);
            OrderTracker.getInstance().track(player.getUniqueId(), order);

            List<Fill> fills = MatchingEngine.getInstance().submitOrder(order);
            Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
                try { ExchangeDatabase.getInstance().saveOrder(order); }
                catch (IllegalStateException ignored) {}
            });

            player.sendMessage(PREFIX + "Limit SELL: §c" + FMT.format(qty) + "x §e" + company.getTicker()
                    + " §7@ §c" + FMT.format(limitPrice) + " §8(id: " + shortId(order.getOrderId()) + ")");
            if (!fills.isEmpty())
                player.sendMessage("§7  → Immediately matched " + fills.size() + " fill(s).");

        } else {
            // ── MARKET SELL ────────────────────────────────────────────────────
            ExchangeOrder order = ExchangeOrder.marketSell(
                    player.getUniqueId(), company.getTicker(), qty);
            OrderTracker.getInstance().track(player.getUniqueId(), order);

            List<Fill> fills = MatchingEngine.getInstance().submitOrder(order);

            if (fills.isEmpty()) {
                player.sendMessage(ERR + "No matching buy orders — market order unexecuted.");
                OrderTracker.getInstance().remove(player.getUniqueId(), order.getOrderId());
            } else {
                player.sendMessage(PREFIX + "Market SELL: §c" + fills.size() + " fill(s) for §e"
                        + company.getTicker() + "§r.");
            }
        }
    }

    // ── /exchange orders ──────────────────────────────────────────────────────

    private void cmdOrders(Player player) {
        List<ExchangeOrder> openOrders = OrderTracker.getInstance().getOpenOrders(player.getUniqueId());
        if (openOrders.isEmpty()) {
            player.sendMessage(PREFIX + "You have no open orders.");
            return;
        }
        send(player, bar(" Your Open Orders "));
        for (ExchangeOrder o : openOrders) {
            String side  = o.getSide() == OrderSide.BUY ? "§aBUY " : "§cSELL";
            String price = o.getType() == OrderType.MARKET ? "MARKET" : FMT.format(o.getPrice());
            String id    = shortId(o.getOrderId());

            Component cancelBtn = clickable(
                    " <dark_gray>[✗]</dark_gray>",
                    ClickEvent.runCommand("/exchange cancel " + id),
                    "<red>Cancel order " + id + "</red>\n<gray>Refunds held funds or shares.</gray>");

            Component row = MM.deserialize(
                    "<dark_gray>" + id + "</dark_gray> <gray>│</gray> "
                    + (o.getSide() == OrderSide.BUY ? "<green>BUY </green>" : "<red>SELL</red>")
                    + " <white>" + FMT.format(o.getRemainingQuantity()) + "x</white>"
                    + " <yellow>" + o.getTickerSymbol() + "</yellow>"
                    + " <gray>@</gray> <white>" + price + "</white>"
                    + " <dark_gray>[" + o.getStatus() + "]</dark_gray>");

            send(player, row.append(cancelBtn));
        }
        send(player, bar(""));
    }

    // ── /exchange cancel <orderId> ────────────────────────────────────────────

    private void cmdCancel(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(ERR + "Usage: /exchange cancel <orderId>"); return; }

        UUID targetId = resolveOrderId(player.getUniqueId(), args[1]);
        if (targetId == null) {
            player.sendMessage(ERR + "Order not found: §f" + args[1]); return;
        }

        ExchangeOrder order = OrderTracker.getInstance()
                .findOrder(player.getUniqueId(), targetId).orElse(null);
        if (order == null || !order.isActive()) {
            player.sendMessage(ERR + "Order not found or already inactive."); return;
        }

        MatchingEngine.getInstance().cancelOrder(order.getTickerSymbol(), targetId);
        order.cancel();
        OrderTracker.getInstance().remove(player.getUniqueId(), targetId);
        final BigDecimal cancelledFilled = order.getFilledQuantity();
        Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
            try { ExchangeDatabase.getInstance().updateOrderStatus(targetId, OrderStatus.CANCELLED, cancelledFilled); }
            catch (IllegalStateException ignored) {}
        });

        // Refund escrow
        BigDecimal refundFunds = EscrowManager.getInstance().returnFunds(targetId);
        EscrowManager.HeldShares refundShares = EscrowManager.getInstance().returnShares(targetId);

        if (refundFunds.compareTo(BigDecimal.ZERO) > 0) {
            Economy econ = Nascraft.getEconomy();
            if (econ != null) econ.depositPlayer(player, refundFunds.doubleValue());
            player.sendMessage(PREFIX + "Order §8" + shortId(targetId)
                    + "§r cancelled. Refunded §f" + FMT.format(refundFunds) + "§r.");
        } else if (refundShares != null && refundShares.qty.compareTo(BigDecimal.ZERO) > 0) {
            ShareRegistry.getInstance().addShares(player.getUniqueId(), refundShares.companyId, refundShares.qty);
            player.sendMessage(PREFIX + "Order §8" + shortId(targetId)
                    + "§r cancelled. Returned §f" + FMT.format(refundShares.qty) + " §rshares.");
        } else {
            player.sendMessage(PREFIX + "Order §8" + shortId(targetId) + "§r cancelled.");
        }
    }

    // ── /exchange portfolio ───────────────────────────────────────────────────

    private void cmdPortfolio(Player player) {
        List<Company> all = CompanyManager.getInstance().getAllActiveCompanies();
        double totalValue = 0;
        boolean hasHoldings = false;

        send(player, bar(" Exchange Portfolio "));
        for (Company c : all) {
            BigDecimal holding = ShareRegistry.getInstance().getHolding(player.getUniqueId(), c.getId());
            if (holding.compareTo(BigDecimal.ZERO) > 0) {
                hasHoldings = true;
                double value = holding.doubleValue() * c.getSharePrice();
                totalValue += value;

                Component sellBtn = clickable(
                        " <red>[Sell]</red>",
                        ClickEvent.suggestCommand("/exchange sell " + c.getTicker() + " "),
                        "<red>Sell your " + c.getTicker() + " shares</red>\n"
                        + "<gray>/exchange sell " + c.getTicker() + " <qty> [limitPrice]</gray>");

                Component row = MM.deserialize(
                        "<yellow>" + c.getTicker() + "</yellow>"
                        + " <gray>│</gray> <white>" + FMT.format(holding) + " shares</white>"
                        + " <gray>@</gray> <green>" + FMT.format(c.getSharePrice()) + "</green>"
                        + " <gray>=</gray> <aqua>" + FMT.format(value) + "</aqua>");

                send(player, row.append(sellBtn));
            }
        }

        if (!hasHoldings) {
            player.sendMessage(PREFIX + "You don't hold any shares on the exchange.");
            send(player, clickable(
                    "<gray>Browse listed companies →</gray>",
                    ClickEvent.runCommand("/exchange list"),
                    "<gray>View all companies you can invest in.</gray>"));
        } else {
            send(player, MM.deserialize("<gray>Total exchange value: <aqua>" + FMT.format(totalValue) + "</aqua></gray>"));
        }
        send(player, bar(""));
    }

    // ── /exchange top ─────────────────────────────────────────────────────────

    private void cmdTop(Player player) {
        List<Company> companies = CompanyManager.getInstance().getAllActiveCompanies();
        Map<UUID, Double> portfolioValue = new HashMap<>();
        Map<UUID, List<String>> portfolioLines = new HashMap<>();

        for (Company c : companies) {
            Map<UUID, BigDecimal> shareholders = ShareRegistry.getInstance().getShareholders(c.getId());
            shareholders.forEach((uuid, shares) -> {
                double val = shares.doubleValue() * c.getSharePrice();
                portfolioValue.merge(uuid, val, Double::sum);
                portfolioLines.computeIfAbsent(uuid, k -> new ArrayList<>())
                        .add(c.getTicker() + " " + FMT.format(shares)
                                + " shares @ " + FMT.format(c.getSharePrice())
                                + " = " + FMT.format(val));
            });
        }

        if (portfolioValue.isEmpty()) {
            player.sendMessage(PREFIX + "No shareholders on the exchange yet.");
            return;
        }

        List<Map.Entry<UUID, Double>> top = portfolioValue.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toList());

        send(player, bar(" Exchange Top 10 "));
        int rank = 1;
        for (Map.Entry<UUID, Double> entry : top) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(entry.getKey()).getName())
                    .orElse(entry.getKey().toString().substring(0, 8));
            String rankColor = rank == 1 ? "<gold>" : rank == 2 ? "<gray>" : rank == 3 ? "<#cd7f32>" : "<white>";

            String hoverText = String.join("\n",
                    portfolioLines.getOrDefault(entry.getKey(), Collections.emptyList()));
            Component row = MM.deserialize(rankColor + "#" + rank + "</color>  <white>" + name + "</white>"
                    + "  <gray>—</gray>  <aqua>" + FMT.format(entry.getValue()) + "</aqua>");
            if (!hoverText.isEmpty())
                row = row.hoverEvent(HoverEvent.showText(MM.deserialize("<gray>" + hoverText + "</gray>")));
            send(player, row);
            rank++;
        }
        send(player, bar(""));
    }

    // ── Interactive component helpers ─────────────────────────────────────────

    /** Sends an Adventure Component to the player via BukkitAudiences. */
    private void send(Player player, Component c) {
        Lang.get().getAudience().player(player).sendMessage(c);
    }

    /** Builds a clickable + hoverable Component from MiniMessage strings. */
    private Component clickable(String mm, ClickEvent click, String hoverMm) {
        return MM.deserialize(mm)
                .hoverEvent(HoverEvent.showText(MM.deserialize(hoverMm)))
                .clickEvent(click);
    }

    /**
     * Builds a help-menu line: "  [keyword]  description text"
     * The keyword is clickable; the description is plain gray.
     */
    private Component helpLine(String keyword, ClickEvent click, String hoverMm, String description) {
        Component btn = clickable(
                "<aqua>" + keyword + "</aqua>",
                click,
                hoverMm);
        Component desc = MM.deserialize("  <dark_gray>" + description + "</dark_gray>");
        return MM.deserialize("  ").append(btn).append(desc);
    }

    /** Renders a separator bar (MiniMessage-safe, returned as Component). */
    private Component bar(String label) {
        String dashes = "─".repeat(50);
        if (label.isEmpty()) {
            return MM.deserialize("<color:#57c5c5><strikethrough>" + dashes + "</strikethrough></color>");
        }
        int pad = Math.max(0, (50 - label.length()) / 2);
        String d = "─".repeat(pad);
        return MM.deserialize(
                "<color:#57c5c5><strikethrough>" + d + "</strikethrough></color>"
                + "<color:#57c5c5>" + label + "</color>"
                + "<color:#57c5c5><strikethrough>" + d + "</strikethrough></color>");
    }

    // ── Business logic helpers ────────────────────────────────────────────────

    /** Returns the Company if it exists and is ACTIVE, otherwise messages the player and returns null. */
    private Company requireActive(Player player, String tickerArg) {
        String ticker = tickerArg.toUpperCase();
        Company c = CompanyManager.getInstance().getCompanyByTicker(ticker).orElse(null);
        if (c == null) { player.sendMessage(ERR + "Unknown ticker: " + ticker); return null; }
        if (c.getStatus() != CompanyStatus.ACTIVE) {
            player.sendMessage(ERR + ticker + " is not currently tradeable (status: " + c.getStatus() + ")."); return null;
        }
        return c;
    }

    private BigDecimal parsePositiveDecimal(Player player, String raw, String label) {
        try {
            BigDecimal v = new BigDecimal(raw);
            if (v.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            player.sendMessage(ERR + "Invalid " + label + ": §f" + raw); return null;
        }
    }

    private boolean orderLimitReached(Player player) {
        int limit = Config.getInstance().getExchangeMaxOrdersPerPlayer();
        if (OrderTracker.getInstance().countOpenOrders(player.getUniqueId()) >= limit) {
            player.sendMessage(ERR + "You already have the maximum number of open orders (" + limit + ").");
            return true;
        }
        return false;
    }

    private String shortId(UUID id) { return id.toString().substring(0, 8); }

    private UUID resolveOrderId(UUID playerUuid, String prefix) {
        for (ExchangeOrder o : OrderTracker.getInstance().getOpenOrders(playerUuid)) {
            if (o.getOrderId().toString().startsWith(prefix)) return o.getOrderId();
        }
        return null;
    }

    private String fmtStatus(CompanyStatus s) {
        switch (s) {
            case ACTIVE:    return "§aACTIVE";
            case HALTED:    return "§cHALTED";
            case SUSPENDED: return "§4SUSPENDED";
            case DELISTED:  return "§8DELISTED";
            default:        return "§7" + s.name();
        }
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1)
            return StringUtil.copyPartialMatches(args[0], SUBS, new ArrayList<>());

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "info": case "buy": case "sell":
                    List<String> tickers = CompanyManager.getInstance().getAllActiveCompanies()
                            .stream().map(Company::getTicker).collect(Collectors.toList());
                    return StringUtil.copyPartialMatches(args[1], tickers, new ArrayList<>());
                case "cancel":
                    UUID uuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
                    if (uuid == null) return Collections.emptyList();
                    List<String> ids = OrderTracker.getInstance().getOpenOrders(uuid)
                            .stream().map(o -> shortId(o.getOrderId())).collect(Collectors.toList());
                    return StringUtil.copyPartialMatches(args[1], ids, new ArrayList<>());
                case "ipo":
                    return StringUtil.copyPartialMatches(args[1], Collections.singletonList("help"), new ArrayList<>());
            }
        }
        return Collections.emptyList();
    }
}
