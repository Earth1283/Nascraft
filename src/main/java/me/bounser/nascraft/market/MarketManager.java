package me.bounser.nascraft.market;

import de.tr7zw.changeme.nbtapi.NBT;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.managers.ImagesManager;
import me.bounser.nascraft.managers.GraphManager;
import me.bounser.nascraft.managers.TasksManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.market.resources.Category;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.config.Config;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.logging.Logger;

public class MarketManager {

    private static final Logger LOGGER = Logger.getLogger("Nascraft");

    /** Item list that invalidates the cached parent view on any structural change. */
    private final class TrackedItemList extends ArrayList<Item> {
        @Override public boolean add(Item item) { invalidate(); return super.add(item); }
        @Override public void add(int index, Item item) { invalidate(); super.add(index, item); }
        @Override public boolean addAll(Collection<? extends Item> c) { invalidate(); return super.addAll(c); }
        @Override public Item remove(int index) { invalidate(); return super.remove(index); }
        @Override public boolean remove(Object o) { invalidate(); return super.remove(o); }
        @Override public void clear() { invalidate(); super.clear(); }
    }

    private void invalidate() { parentsCache = null; materialIndex = null; }

    public void invalidateLookups() { invalidate(); }

    private volatile List<Item> parentsCache;

    private volatile Map<Material, List<Item>> materialIndex;

    private final List<Item> items = new TrackedItemList();
    private final HashMap<String, Item> identifiers = new HashMap<>();
    private List<Category> categories = new ArrayList<>();

    private boolean active = true;

    private List<Float> marketChanges1h;
    private List<Float> marketChanges24h;

    private float lastChange;

    private int operationsLastHour = 0;

    private List<String> ignoredKeys = new ArrayList<>();

    ZoneOffset offset = ZonedDateTime.now(ZoneId.systemDefault()).getOffset();

    private static MarketManager instance = null;

    public static MarketManager getInstance() { return instance == null ? new MarketManager() : instance; }

    private MarketManager() {
        instance = this;
        setupItems();
        ignoredKeys = Config.getInstance().getIgnoredKeys();

        active = !Config.getInstance().isMarketClosed();
    }

    public void setupItems() {

        Config config = Config.getInstance();

        for (String categoryName : Config.getInstance().getCategories()) {
            Category category = new Category(categoryName);
            categories.add(category);
        }

        for (String identifier : Config.getInstance().getAllMaterials()) {

            ItemStack itemStack = config.getItemStackOfItem(identifier);

            if (itemStack == null) {
                LOGGER.warning("Error with the itemStack item: " + identifier);
                LOGGER.warning("Make sure the material is correct and exists in your version.");
                continue;
            }

            Category category = config.getCategoryFromMaterial(identifier);

            if (category == null) {
                LOGGER.warning("No category found for item: " + identifier);
                continue;
            }

            BufferedImage image = ImagesManager.getInstance().getImage(identifier);

            if (image == null) {
                LOGGER.info("No image yet for item: " + identifier + " (still tradeable; the icon loads once textures are available)");
                image = ImagesManager.placeholder();
            }

            Item item = new Item(
                    itemStack,
                    identifier,
                    config.getAlias(identifier),
                    category,
                    image
            );

            DatabaseManager.get().getDatabase().retrieveItem(item);

            items.add(item);
            identifiers.put(identifier, item);
            category.addItem(item);

            for (Item child : config.getChilds(identifier)) {
                item.addChildItem(child);
                items.add(child);
            }
        }

        LOGGER.info("Loaded " + categories.size() + " categories.");

        Plugin AGUI = null;
        try {
            if (Bukkit.getServer() != null) AGUI = Bukkit.getPluginManager().getPlugin("AdvancedGUI");
        } catch (Throwable ignored) { /* no server in test context */ }
        if (categories.size() < 4 && (AGUI != null)) {
            LOGGER.severe("You need to have at least 4 categories! Disabling plugin...");
            Nascraft instance = Nascraft.getInstance();
            if (instance != null) instance.getPluginLoader().disablePlugin(instance);
        }

        for (Item item : items)
            if (item.getCategory() == null && item.isParent()) LOGGER.warning("Item: " + item.getIdentifier() + " is not assigned to any category.");

        marketChanges1h = new ArrayList<>(Collections.nCopies(60, 0f));
        marketChanges24h = new ArrayList<>(Collections.nCopies(24, 0f));

        try {
            if (Bukkit.getServer() != null) {
                TasksManager.getInstance();
                GraphManager.getInstance();
            }
        } catch (Throwable ignored) { /* no server in test context */ }
    }

    public void reload() {
        items.clear();
        identifiers.clear();
        categories.clear();

        setupItems();
    }

    private Map<Material, List<Item>> materialIndex() {
        Map<Material, List<Item>> index = materialIndex;
        if (index != null) return index;

        index = new EnumMap<>(Material.class);
        for (Item item : items)
            index.computeIfAbsent(item.peekItemStack().getType(), k -> new ArrayList<>(2)).add(item);

        return materialIndex = index;
    }

    private Item findItem(ItemStack itemStack, boolean parentsOnly) {
        if (itemStack == null) return null;
        List<Item> candidates = materialIndex().get(itemStack.getType());
        if (candidates == null) return null;
        for (Item item : candidates) {
            if (parentsOnly && !item.isParent()) continue;
            if (isSimilarEnough(itemStack, item.peekItemStack())) return item;
        }
        return null;
    }

    public Item getItem(ItemStack itemStack) {
        return findItem(itemStack, false);
    }

    public Item getItem(String identifier) {
        return identifiers.get(identifier);
    }

    public List<Category> getCategories() { return categories; }

    public List<Item> getAllItems() { return items; }

    public List<Item> getAllParentItemsInAlphabeticalOrder() {

        List<Item> sorted = new ArrayList<>(getAllParentItems());

        sorted.sort(Comparator.comparing(Item::getName));

        return sorted;
    }

    public List<String> getAllItemsAndChildsIdentifiers() {

        List<String> identifiers = new ArrayList<>();

        for (Item item : getAllItems()) {
            identifiers.add(item.getIdentifier());
        }

        return identifiers;
    }

    /** Read-only snapshot, rebuilt only when the item list changes. */
    public List<Item> getAllParentItems() {

        List<Item> cached = parentsCache;
        if (cached != null) return cached;

        List<Item> parents = new ArrayList<>();

        for (Item item : items) {
            if (item.isParent()) parents.add(item);
        }

        return parentsCache = Collections.unmodifiableList(parents);
    }

    public void stop() { active = false; }
    public void resume() { active = true; }

    public boolean getActive() { return active; }

    public boolean isAValidItem(ItemStack itemStack) {
        return findItem(itemStack, false) != null;
    }

    public boolean isAValidParentItem(ItemStack itemStack) {
        return findItem(itemStack, true) != null;
    }

    /**
     * Strategy seam: how to strip a single NBT key from an ItemStack.
     * Production uses NBT.modify (relocated NBT-API). Tests inject a mock so the
     * inline mock-maker doesn't need to instrument the (uninstrumentable) NBT class.
     */
    @FunctionalInterface
    public interface KeyStripper {
        void strip(ItemStack stack, String key);
    }

    private KeyStripper keyStripper = (stack, key) -> NBT.modify(stack, (java.util.function.Consumer<de.tr7zw.changeme.nbtapi.iface.ReadWriteItemNBT>) nbt -> nbt.removeKey(key));

    /** Test seam — package-private. */
    void setKeyStripper(KeyStripper stripper) { this.keyStripper = stripper; }

    public boolean isSimilarEnough(ItemStack itemStack1, ItemStack itemStack2) {

        if (itemStack1 == null || itemStack2 == null) return false;

        if (!itemStack1.getType().equals(itemStack2.getType())) return false;

        if (ignoredKeys.isEmpty()) return itemStack1.isSimilar(itemStack2);

        ItemStack itemStackWithoutFlags1 = itemStack1.clone();
        ItemStack itemStackWithoutFlags2 = itemStack2.clone();

        for (String ignoredKey : ignoredKeys) {
            keyStripper.strip(itemStackWithoutFlags1, ignoredKey);
            keyStripper.strip(itemStackWithoutFlags2, ignoredKey);
        }

        return itemStackWithoutFlags1.isSimilar(itemStackWithoutFlags2);
    }

    private List<Item> topBy(int quantity, Comparator<Item> order) {
        List<Item> sorted = new ArrayList<>(getAllParentItems());
        sorted.sort(order);
        return new ArrayList<>(sorted.subList(0, Math.min(quantity, sorted.size())));
    }

    // Each item's hourly change is computed once per call rather than once per comparison.
    private List<Item> topByChange(int quantity, java.util.function.ToDoubleFunction<Float> key, boolean descending) {
        List<Item> parents = getAllParentItems();
        Map<Item, Float> change = new IdentityHashMap<>(parents.size() * 2);
        for (Item item : parents) change.put(item, item.getPrice().getValueChangeLastHour());

        Comparator<Item> order = Comparator.comparingDouble(item -> key.applyAsDouble(change.get(item)));
        return topBy(quantity, descending ? order.reversed() : order);
    }

    public List<Item> getTopGainers(int quantity) { return topByChange(quantity, v -> v, true); }

    public List<Item> getTopDippers(int quantity) { return topByChange(quantity, v -> v, false); }

    public List<Item> getMostMoved(int quantity) { return topByChange(quantity, v -> Math.abs(v), true); }

    public List<Item> getMostTraded(int quantity) {
        return topBy(quantity, Comparator.comparingInt(Item::getOperations).reversed());
    }

    public int getPositionByVolume(Item item) {

        List<Item> items = new ArrayList<>(getAllItems());

        items.sort(Comparator.comparingDouble(Item::getVolume));

        return items.size()-getIndexOf(item, items);
    }

    public int getIndexOf(Item item, List<Item> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == item) {
                return i;
            }
        }
        return -1;
    }

    public void updateMarketChange1h(float change) {
        lastChange = change;

        marketChanges1h.add(change);
        marketChanges1h.remove(0);
    }

    public List<Float> getBenchmark1h(float base) {

        List<Float> benchmark = new ArrayList<>();

        float value = base;

        for (float change : marketChanges1h) {
            value += value * change/100;
            benchmark.add(value);
        }

        return benchmark;
    }

    public float getChange1h(){

        float change = 0;

        for (Item item : getAllParentItems())
            change += item.getPrice().getValue()/item.getPrice().getValueAnHourAgo()-1;

        return change*100;
    }

    public float getLastChange() { return lastChange; }

    public int[] getBenchmarkX(int xSize, int offset) { return Plot.getXPositions(xSize, offset, false, 60); }

    public int[] getBenchmarkY(int ySize, int offset) {
        return Plot.getYPositions(ySize, offset, false, getBenchmark1h(100));
    }

    public int getOperationsLastHour() { return operationsLastHour; }

    public void addOperation() { operationsLastHour++; }

    public void setOperationsLastHour(int operations) { operationsLastHour = operations; }

    public void removeItem(Item item) { items.remove(item); }

    public void addItem(Item item) { items.add(item); }

    public void removeCategory(Category category) { categories.remove(category); }

    public void addCategory(Category category) { categories.add(category); }

    public void setCategories(List<Category> categories) { this.categories = categories; }

    public Category getCategoryFromIdentifier(String identifier) {

        for (Category category : categories)
            if (category.getIdentifier().equals(identifier)) return category;

        return null;
    }

    public float getConsumerPriceIndex() {

        float index = 0;
        int numOfItems = 0;

        for (Item item : getAllParentItems()) {
            if (!item.getCurrency().equals(CurrenciesManager.getInstance().getDefaultCurrency())) continue;

            if (Config.getInstance().includeInCPI(item)) {
                index += (float) (item.getPrice().getValue()/item.getPrice().getInitialValue());
                numOfItems++;
            }
        }

        if (numOfItems == 0) return 100;

        return (index/numOfItems)*100;
    }

}
