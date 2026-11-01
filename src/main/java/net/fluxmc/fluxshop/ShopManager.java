package net.fluxmc.fluxshop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public class ShopManager {

    public record ShopItem(Material material, double buy) {}

    public record Category(String id, String title, Material icon, int slot, List<ShopItem> items,
                           String accent, String displayName, String buttonName, String description,
                           String currency, int pageRows) {}

    private final FluxShop plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public ShopManager(FluxShop plugin) {
        this.plugin = plugin;
    }

    public void load() {
        categories.clear();
        File file = new File(plugin.getDataFolder(), "shops.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cats = yml.getConfigurationSection("categories");
        if (cats == null) {
            plugin.getLogger().warning("shops.yml has no 'categories' section.");
            return;
        }

        for (String id : cats.getKeys(false)) {
            ConfigurationSection c = cats.getConfigurationSection(id);
            if (c == null) continue;

            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            if (icon == null) icon = Material.CHEST;

            List<ShopItem> items = new ArrayList<>();
            ConfigurationSection is = c.getConfigurationSection("items");
            if (is != null) {
                for (String key : is.getKeys(false)) {
                    Material m = Material.matchMaterial(key);
                    if (m == null || !m.isItem()) {
                        plugin.getLogger().warning("Category '" + id + "': unknown item '" + key + "', skipped.");
                        continue;
                    }
                    items.add(new ShopItem(m, is.getDouble(key + ".buy", -1)));
                }
            }

            String display = c.getString("display-name", capitalize(id));
            String button = c.getString("button-name", display);
            int pageRows = Math.max(1, Math.min(4, c.getInt("page-rows", 4)));

            categories.put(id.toLowerCase(Locale.ROOT),
                    new Category(id, FluxShop.color(c.getString("title", id)), icon, c.getInt("slot", -1), items,
                            c.getString("color", "&a"), display, button, c.getString("description", ""),
                            c.getString("currency", "vault"), pageRows));
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public Collection<Category> categories() {
        return categories.values();
    }

    public Category get(String id) {
        return categories.get(id.toLowerCase(Locale.ROOT));
    }

    public Set<String> ids() {
        return categories.keySet();
    }
}
