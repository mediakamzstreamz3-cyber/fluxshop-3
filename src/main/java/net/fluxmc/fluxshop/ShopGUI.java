package net.fluxmc.fluxshop;

import net.fluxmc.fluxshop.Currencies.Currency;
import net.fluxmc.fluxshop.ShopManager.Category;
import net.fluxmc.fluxshop.ShopManager.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

import static net.fluxmc.fluxshop.FluxShop.color;

public class ShopGUI implements Listener {

    private static final int MAX_AMOUNT = 2304; // 36 stacks of 64

    // Confirm Purchase menu layout (3 rows)
    private static final int C_REM64 = 9, C_REM10 = 10, C_REM1 = 11;
    private static final int C_ITEM = 13;
    private static final int C_ADD1 = 15, C_ADD10 = 16, C_ADD64 = 17;
    private static final int C_CANCEL = 21, C_TOTAL = 22, C_CONFIRM = 23;

    private enum Type { MAIN, CATEGORY, CONFIRM }

    private final FluxShop plugin;

    public ShopGUI(FluxShop plugin) {
        this.plugin = plugin;
    }

    /** Marks our inventories so we can recognise them in click events. */
    static class Holder implements InventoryHolder {
        final Type type;
        final Category category; // null on the main menu
        final int page;
        final ShopItem item;     // confirm menu only
        int amount;              // confirm menu only
        final Map<Integer, Category> menuSlots = new HashMap<>();
        Inventory inventory;

        Holder(Type type, Category category, int page, ShopItem item, int amount) {
            this.type = type;
            this.category = category;
            this.page = page;
            this.item = item;
            this.amount = amount;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ================================================================ main menu

    public void openMain(Player p) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("main-menu.rows", 3)));
        Holder h = new Holder(Type.MAIN, null, 0, null, 0);
        Inventory inv = Bukkit.createInventory(h, rows * 9,
                color(plugin.getConfig().getString("main-menu.title", "&8▶ Shop")));
        h.inventory = inv;

        ItemStack filler = filler();
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler.clone());
        }

        List<Category> auto = new ArrayList<>();
        for (Category c : plugin.shops().categories()) {
            int s = c.slot();
            if (s >= 0 && s < inv.getSize() && !h.menuSlots.containsKey(s)) {
                h.menuSlots.put(s, c);
            } else {
                auto.add(c);
            }
        }
        int next = 0;
        for (Category c : auto) {
            while (next < inv.getSize() && h.menuSlots.containsKey(next)) next++;
            if (next >= inv.getSize()) break;
            h.menuSlots.put(next, c);
            next++;
        }

        for (Map.Entry<Integer, Category> e : h.menuSlots.entrySet()) {
            Category c = e.getValue();
            inv.setItem(e.getKey(), named(c.icon(),
                    color(c.accent() + "&l" + c.buttonName().toUpperCase(Locale.ROOT)), categoryLore(c)));
        }
        p.openInventory(inv);
    }

    private List<String> categoryLore(Category c) {
        Currency cur = plugin.currencies().get(c.currency());
        String a = c.accent();
        return lore(a, c.description(),
                List.of("This is the " + c.displayName() + " Shop", "You can buy things for " + cur.name()),
                List.of("Currency: " + a + cur.symbol()),
                List.of("CLICK|to view"));
    }

    // ================================================================ category menu

    public void openCategory(Player p, Category c, int page) {
        int perPage = c.pageRows() * 9;
        int total = c.items().size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) perPage));
        page = Math.max(0, Math.min(page, pages - 1));

        int itemRows = pages == 1 ? Math.max(1, (int) Math.ceil(total / 9.0)) : c.pageRows();
        int size = (itemRows + 2) * 9; // empty top row + item rows + navigation row

        Holder h = new Holder(Type.CATEGORY, c, page, null, 0);
        Inventory inv = Bukkit.createInventory(h, size, c.title());
        h.inventory = inv;

        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < total; i++) {
            inv.setItem(9 + i, display(c, c.items().get(start + i)));
        }

        int nav = size - 9;
        inv.setItem(nav + 3, prevButton(page, pages));
        inv.setItem(nav + 4, backButton());
        inv.setItem(nav + 5, nextButton(page, pages));

        p.openInventory(inv);
    }

    private ItemStack display(Category c, ShopItem item) {
        Currency cur = plugin.currencies().get(c.currency());
        String a = c.accent();

        List<String> info = List.of(item.buy() >= 0 ? "You can buy this item" : "This item can't be bought");

        List<String> extra = new ArrayList<>();
        if (item.buy() >= 0) extra.add("&a" + cur.symbol() + " &fPrice: &a" + cur.symbol() + fmt(item.buy()));

        List<String> clicks = new ArrayList<>();
        if (item.buy() >= 0) clicks.add("CLICK|to Buy");

        return named(item.material(), color(a + name(item.material())), lore(a, "", info, extra, clicks));
    }

    private ItemStack prevButton(int page, int pages) {
        return named(Material.RED_STAINED_GLASS_PANE, color("&c&lPREVIOUS PAGE"),
                lore("&c", "", List.of("Click on me to", "view the previous page"),
                        List.of("Page: &c" + (page + 1) + "/" + pages), List.of("CLICK|to go back")));
    }

    private ItemStack nextButton(int page, int pages) {
        return named(Material.LIME_STAINED_GLASS_PANE, color("&a&lNEXT PAGE"),
                lore("&a", "", List.of("Click on me to", "view the next page"),
                        List.of("Page: &a" + (page + 1) + "/" + pages), List.of("CLICK|to continue")));
    }

    private ItemStack backButton() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("buttons.back-item", "BRAIN_CORAL"));
        if (m == null) m = Material.BARRIER;
        return named(m, color("&c&lBACK"),
                lore("&c", "", List.of("Click me to go", "back to the main shop"),
                        List.of("action: &cSent back"), List.of("CLICK|to return")));
    }

    // ================================================================ confirm menu

    public void openConfirm(Player p, Category c, int page, ShopItem item, int amount) {
        Holder h = new Holder(Type.CONFIRM, c, page, item, clampAmount(amount));
        Inventory inv = Bukkit.createInventory(h, 27, color("&8Confirm Purchase"));
        h.inventory = inv;
        fillConfirm(h);
        p.openInventory(inv);
    }

    private void fillConfirm(Holder h) {
        Category c = h.category;
        ShopItem item = h.item;
        Inventory inv = h.inventory;
        String a = c.accent();
        Currency cur = plugin.currencies().get(c.currency());
        int amt = h.amount;
        double total = item.buy() * amt;

        inv.setItem(C_REM64, adjustButton(false, 64, amt));
        inv.setItem(C_REM10, adjustButton(false, 10, amt));
        inv.setItem(C_REM1, adjustButton(false, 1, amt));
        inv.setItem(C_ADD1, adjustButton(true, 1, amt));
        inv.setItem(C_ADD10, adjustButton(true, 10, amt));
        inv.setItem(C_ADD64, adjustButton(true, 64, amt));

        ItemStack preview = named(item.material(), color(a + name(item.material())),
                lore(a, "", List.of("You are buying this item"),
                        List.of("Amount: " + a + amt,
                                "Price each: &a" + cur.symbol() + fmt(item.buy())),
                        List.of()));
        preview.setAmount(Math.max(1, Math.min(amt, item.material().getMaxStackSize())));
        inv.setItem(C_ITEM, preview);

        inv.setItem(C_CANCEL, named(Material.RED_CONCRETE, color("&c&lCANCEL"),
                lore("&c", "", List.of("Click on me to cancel", "and go back"), List.of(), List.of("CLICK|to cancel"))));

        inv.setItem(C_TOTAL, named(Material.CLOCK, color("&e&lTOTAL"),
                lore("&e", "", List.of("Total for this purchase"),
                        List.of("Amount: &e" + amt, "Total: &a" + cur.symbol() + fmt(total)), List.of())));

        inv.setItem(C_CONFIRM, named(Material.LIME_CONCRETE, color("&a&lCONFIRM"),
                lore("&a", "", List.of("Click on me to", "complete the purchase"),
                        List.of("Total: &a" + cur.symbol() + fmt(total)), List.of("CLICK|to confirm"))));
    }

    private ItemStack adjustButton(boolean add, int n, int current) {
        String a = add ? "&a" : "&c";
        return named(add ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                color(a + "&l" + (add ? "ADD " : "REMOVE ") + n),
                lore(a, "", List.of("Click on me to", add ? "add more items" : "remove items"),
                        List.of((add ? "+ Increase: " : "- Decrease: ") + a + n,
                                "Current amount: " + a + current),
                        List.of("CLICK|" + (add ? "to add" : "to remove"))));
    }

    private int clampAmount(int amount) {
        return Math.max(1, Math.min(MAX_AMOUNT, amount));
    }

    // ================================================================ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;

        int slot = e.getSlot();
        ClickType click = e.getClick();

        switch (h.type) {
            case MAIN -> {
                Category c = h.menuSlots.get(slot);
                if (c != null) openCategory(p, c, 0);
            }
            case CATEGORY -> clickCategory(p, h, slot, click);
            case CONFIRM -> clickConfirm(p, h, slot);
        }
    }

    private void clickCategory(Player p, Holder h, int slot, ClickType click) {
        Category c = h.category;
        int size = h.inventory.getSize();
        int nav = size - 9;
        int perPage = c.pageRows() * 9;
        int pages = Math.max(1, (int) Math.ceil(c.items().size() / (double) perPage));

        if (slot == nav + 3) {
            if (h.page > 0) openCategory(p, c, h.page - 1);
            return;
        }
        if (slot == nav + 5) {
            if (h.page < pages - 1) openCategory(p, c, h.page + 1);
            return;
        }
        if (slot == nav + 4) {
            openMain(p);
            return;
        }
        if (slot < 9 || slot >= nav) return;

        int index = h.page * perPage + (slot - 9);
        if (index >= c.items().size()) return;
        ShopItem item = c.items().get(index);

        switch (click) {
            case LEFT -> {
                if (item.buy() < 0) p.sendMessage(plugin.msg("cannot-buy"));
                else openConfirm(p, c, h.page, item, 1);
            }
            case SHIFT_LEFT -> {
                if (item.buy() < 0) p.sendMessage(plugin.msg("cannot-buy"));
                else openConfirm(p, c, h.page, item, 64);
            }
            default -> { }
        }
    }

    private void clickConfirm(Player p, Holder h, int slot) {
        switch (slot) {
            case C_REM64 -> changeAmount(h, -64);
            case C_REM10 -> changeAmount(h, -10);
            case C_REM1 -> changeAmount(h, -1);
            case C_ADD1 -> changeAmount(h, 1);
            case C_ADD10 -> changeAmount(h, 10);
            case C_ADD64 -> changeAmount(h, 64);
            case C_CANCEL -> openCategory(p, h.category, h.page);
            case C_CONFIRM -> {
                buy(p, h.category, h.item, h.amount);
                fillConfirm(h);
            }
            default -> { }
        }
    }

    private void changeAmount(Holder h, int delta) {
        h.amount = clampAmount(h.amount + delta);
        fillConfirm(h);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ================================================================ buy / sell

    private void buy(Player p, Category c, ShopItem item, int amount) {
        if (item.buy() < 0) {
            p.sendMessage(plugin.msg("cannot-buy"));
            return;
        }
        Currency cur = plugin.currencies().get(c.currency());
        if (!cur.available()) {
            p.sendMessage(plugin.msg("currency-unavailable"));
            return;
        }

        double unit = item.buy();
        if (!cur.has(p, unit * amount)) {
            p.sendMessage(plugin.msg("not-enough-money", "price", cur.symbol() + fmt(unit * amount)));
            return;
        }

        int give = Math.min(amount, freeSpace(p, item.material()));
        if (give <= 0) {
            p.sendMessage(plugin.msg("inventory-full"));
            return;
        }

        double charge = unit * give;
        if (!cur.take(p, charge)) {
            p.sendMessage(plugin.msg("not-enough-money", "price", cur.symbol() + fmt(charge)));
            return;
        }

        Map<Integer, ItemStack> leftover = p.getInventory().addItem(new ItemStack(item.material(), give));
        int notGiven = 0;
        for (ItemStack s : leftover.values()) notGiven += s.getAmount();
        if (notGiven > 0) {
            cur.give(p, unit * notGiven);
            give -= notGiven;
            charge = unit * give;
        }

        if (give <= 0) {
            p.sendMessage(plugin.msg("inventory-full"));
            return;
        }
        p.sendMessage(plugin.msg("bought", "amount", String.valueOf(give),
                "item", name(item.material()), "price", cur.symbol() + fmt(charge)));
        if (give < amount) p.sendMessage(plugin.msg("inventory-full"));
    }

    private int freeSpace(Player p, Material m) {
        int max = m.getMaxStackSize();
        int space = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType() == Material.AIR) space += max;
            else if (s.getType() == m && !s.hasItemMeta()) space += Math.max(0, max - s.getAmount());
        }
        return space;
    }

    // ================================================================ helpers

    /** Builds the tooltip style: description, Information block, extra lines, then CLICK lines. */
    private List<String> lore(String a, String description, List<String> info, List<String> extra,
                              List<String> clicks) {
        List<String> l = new ArrayList<>();
        if (description != null && !description.isEmpty()) l.add(color("&7" + description));
        l.add("");
        l.add(color(a + "Information:"));
        for (String s : info) l.add(color(a + "| &f" + s));
        if (!extra.isEmpty()) {
            l.add("");
            for (String s : extra) l.add(color(a + "| &f" + s));
        }
        if (!clicks.isEmpty()) {
            l.add("");
            for (String s : clicks) {
                String[] parts = s.split("\\|", 2);
                l.add(color("&e&l\u25B6 &e&n&l" + parts[0] + "&r &e" + (parts.length > 1 ? parts[1] : "")));
            }
        }
        return l;
    }

    private ItemStack filler() {
        String s = plugin.getConfig().getString("main-menu.filler", "NONE");
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("NONE")) return null;
        Material m = Material.matchMaterial(s);
        if (m == null) return null;
        return named(m, " ", Collections.emptyList());
    }

    private ItemStack named(Material m, String name, List<String> lore) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RESET + name);
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(s.isEmpty() ? s : ChatColor.RESET + s);
            meta.setLore(l);
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String name(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
