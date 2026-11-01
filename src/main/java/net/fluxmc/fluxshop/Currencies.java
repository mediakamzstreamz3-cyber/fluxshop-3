package net.fluxmc.fluxshop;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Money (Vault), Shards (Shards plugin via placeholder + commands) and PlayerPoints behind one interface. */
public class Currencies {

    public interface Currency {
        String name();
        String symbol();
        boolean available();
        boolean has(Player p, double amount);
        boolean take(Player p, double amount);
        void give(Player p, double amount);
    }

    private final FluxShop plugin;
    /** Shards taken but whose console command hasn't run yet (stops double spending). */
    private final Map<UUID, Double> pending = new ConcurrentHashMap<>();

    public Currencies(FluxShop plugin) {
        this.plugin = plugin;
    }

    public Currency get(String id) {
        String key = (id == null || id.isEmpty()) ? "vault" : id.toLowerCase(Locale.ROOT);
        boolean isVault = key.equals("vault");
        String base = "currencies." + key + ".";
        String name = plugin.getConfig().getString(base + "name", isVault ? "Money" : key);
        String symbol = plugin.getConfig().getString(base + "symbol", "$");
        String defProvider = isVault ? "vault" : (key.equals("shards") ? "shards" : "playerpoints");
        String provider = plugin.getConfig().getString(base + "provider", defProvider);
        if (provider.equalsIgnoreCase("shards")) {
            return new ShardsCurrency(name, symbol,
                    plugin.getConfig().getString(base + "balance-placeholder", "%shards%"),
                    plugin.getConfig().getString(base + "take-command", "shards take {player} {amount}"),
                    plugin.getConfig().getString(base + "give-command", "shards give {player} {amount}"));
        }
        if (provider.equalsIgnoreCase("playerpoints")) return new PlayerPointsCurrency(name, symbol);
        return new VaultCurrency(name, symbol);
    }

    /** Runs a task on Folia's global region (needed for console commands), or the main thread on Paper. */
    private void runGlobal(Runnable task) {
        try {
            Object sched = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            Class<?> iface = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Consumer<Object> c = t -> task.run();
            iface.getMethod("run", Plugin.class, Consumer.class).invoke(sched, plugin, c);
        } catch (ReflectiveOperationException e) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    // ------------------------------------------------------------------ Vault

    private class VaultCurrency implements Currency {
        private final String name;
        private final String symbol;

        VaultCurrency(String name, String symbol) {
            this.name = name;
            this.symbol = symbol;
        }

        public String name() { return name; }
        public String symbol() { return symbol; }
        public boolean available() { return plugin.economy() != null; }

        public boolean has(Player p, double amount) {
            return plugin.economy().has(p, amount);
        }

        public boolean take(Player p, double amount) {
            return plugin.economy().withdrawPlayer(p, amount).transactionSuccess();
        }

        public void give(Player p, double amount) {
            plugin.economy().depositPlayer(p, amount);
        }
    }

    // ------------------------------------------------------------------ Shards plugin

    /**
     * The Shards plugin has no public API, so the balance is read from its PlaceholderAPI
     * placeholder and changes are made with its console commands (all configurable).
     */
    private class ShardsCurrency implements Currency {
        private final String name;
        private final String symbol;
        private final String placeholder;
        private final String takeCmd;
        private final String giveCmd;

        ShardsCurrency(String name, String symbol, String placeholder, String takeCmd, String giveCmd) {
            this.name = name;
            this.symbol = symbol;
            this.placeholder = placeholder;
            this.takeCmd = takeCmd;
            this.giveCmd = giveCmd;
        }

        public String name() { return name; }
        public String symbol() { return symbol; }

        private String resolve(Player p) {
            try {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Object r = papi.getMethod("setPlaceholders", OfflinePlayer.class, String.class)
                        .invoke(null, p, placeholder);
                if (r == null) return null;
                String s = ChatColor.stripColor(String.valueOf(r));
                return s.equals(placeholder) ? null : s; // unchanged = placeholder didn't resolve
            } catch (Exception e) {
                return null;
            }
        }

        private double parse(String s) {
            if (s == null) return -1;
            s = s.replace(",", "").trim();
            double mult = 1;
            if (!s.isEmpty()) {
                char last = Character.toUpperCase(s.charAt(s.length() - 1));
                if (last == 'K') mult = 1e3;
                else if (last == 'M') mult = 1e6;
                else if (last == 'B') mult = 1e9;
            }
            StringBuilder sb = new StringBuilder();
            for (char ch : s.toCharArray()) {
                if (Character.isDigit(ch) || ch == '.') sb.append(ch);
            }
            try {
                return Double.parseDouble(sb.toString()) * mult;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private double balance(Player p) {
            double b = parse(resolve(p));
            if (b < 0) return -1;
            return b - pending.getOrDefault(p.getUniqueId(), 0.0);
        }

        public boolean available() {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            return papi != null && papi.isEnabled();
        }

        public boolean has(Player p, double amount) {
            return balance(p) >= Math.ceil(amount);
        }

        public boolean take(Player p, double amount) {
            long amt = (long) Math.ceil(amount);
            if (balance(p) < amt) return false;
            UUID id = p.getUniqueId();
            String cmd = takeCmd.replace("{player}", p.getName()).replace("{amount}", String.valueOf(amt));
            pending.merge(id, (double) amt, Double::sum);
            runGlobal(() -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } finally {
                    pending.merge(id, -(double) amt, Double::sum);
                }
            });
            return true;
        }

        public void give(Player p, double amount) {
            long amt = (long) Math.floor(amount);
            if (amt <= 0) return;
            String cmd = giveCmd.replace("{player}", p.getName()).replace("{amount}", String.valueOf(amt));
            runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
        }
    }

    // ------------------------------------------------------------------ PlayerPoints (optional)

    /** Talks to PlayerPoints through reflection so it is an optional dependency. */
    private class PlayerPointsCurrency implements Currency {
        private final String name;
        private final String symbol;

        PlayerPointsCurrency(String name, String symbol) {
            this.name = name;
            this.symbol = symbol;
        }

        public String name() { return name; }
        public String symbol() { return symbol; }

        private Object api() {
            Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pp == null || !pp.isEnabled()) return null;
            try {
                return pp.getClass().getMethod("getAPI").invoke(pp);
            } catch (Exception e) {
                return null;
            }
        }

        public boolean available() {
            return api() != null;
        }

        public boolean has(Player p, double amount) {
            Object api = api();
            if (api == null) return false;
            try {
                Object bal = api.getClass().getMethod("look", UUID.class).invoke(api, p.getUniqueId());
                return ((Number) bal).intValue() >= (int) Math.ceil(amount);
            } catch (Exception e) {
                return false;
            }
        }

        public boolean take(Player p, double amount) {
            Object api = api();
            if (api == null) return false;
            try {
                Object ok = api.getClass().getMethod("take", UUID.class, int.class)
                        .invoke(api, p.getUniqueId(), (int) Math.ceil(amount));
                return Boolean.TRUE.equals(ok);
            } catch (Exception e) {
                return false;
            }
        }

        public void give(Player p, double amount) {
            Object api = api();
            if (api == null) return;
            try {
                api.getClass().getMethod("give", UUID.class, int.class)
                        .invoke(api, p.getUniqueId(), (int) Math.floor(amount));
            } catch (Exception ignored) {
            }
        }
    }
}
