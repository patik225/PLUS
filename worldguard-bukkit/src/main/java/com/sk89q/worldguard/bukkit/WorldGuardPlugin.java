/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.bukkit;

import com.google.common.collect.ImmutableList;
import com.sk89q.bukkit.util.CommandsManagerRegistration;
import com.sk89q.minecraft.util.commands.*;
import com.sk89q.wepif.PermissionsResolverManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitCommandSender;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.blacklist.Blacklist;
import com.sk89q.worldguard.bukkit.event.player.ProcessPlayerEvent;
import com.sk89q.worldguard.bukkit.listener.*;
import com.sk89q.worldguard.bukkit.session.BukkitSessionManager;
import com.sk89q.worldguard.bukkit.util.ClassSourceValidator;
import com.sk89q.worldguard.bukkit.util.Events;
import com.sk89q.worldguard.commands.GeneralCommands;
import com.sk89q.worldguard.commands.ProtectionCommands;
import com.sk89q.worldguard.commands.ToggleCommands;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.RegionDriver;
import com.sk89q.worldguard.protection.managers.storage.file.DirectoryYamlDriver;
import com.sk89q.worldguard.protection.managers.storage.sql.SQLDriver;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.util.logging.RecordMessagePrefixer;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sk89q.worldguard.commands.WorldGuardCommands.buildnumber;

/**
 * The main class for WorldGuard as a Bukkit plugin.
 */
public class WorldGuardPlugin extends JavaPlugin {

    private static WorldGuardPlugin inst;
    private static BukkitWorldGuardPlatform platform;
    private final CommandsManager<Actor> commands;
    private PlayerMoveListener playerMoveListener;

    private static final int BSTATS_PLUGIN_ID = 3283;

    /**
     * Construct objects. Actual loading occurs when the plugin is enabled, so
     * this merely instantiates the objects.
     */
    public WorldGuardPlugin() {
        inst = this;
        commands = new CommandsManager<Actor>() {
            @Override
            public boolean hasPermission(Actor player, String perm) {
                return player.hasPermission(perm);
            }
        };
    }

    public static HashMap<String, String> messageData = new HashMap<String, String>();

    private void setMessage(String name, String message) {
        File f = new File(getDataFolder()+File.separator+"messages.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(f);
        if (!config.isSet(name)) {
            config.set(name, message);
            try {
                config.save(f);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Get the current instance of WorldGuard
     * @return WorldGuardPlugin instance
     */
    public static WorldGuardPlugin inst() {
        return inst;
    }

    /**
     * Called on plugin enable.
     */
    @Override
    public void onEnable() {
        // Catch bad things being done by naughty plugins that include WorldGuard's classes
        ClassSourceValidator verifier = new ClassSourceValidator(this);
        verifier.reportMismatches(ImmutableList.of(WorldGuard.class, ProtectedRegion.class, Flag.class));

        configureLogger();

        getDataFolder().mkdirs(); // Need to create the plugins/WorldGuard folder

        PermissionsResolverManager.initialize(this);

        WorldGuard.getInstance().setPlatform(platform = new BukkitWorldGuardPlatform()); // Initialise WorldGuard
        WorldGuard.getInstance().setup();
        BukkitSessionManager sessionManager = (BukkitSessionManager) platform.getSessionManager();

        // Set the proper command injector
        commands.setInjector(new SimpleInjector(WorldGuard.getInstance()));

        // Register command classes
        final CommandsManagerRegistration reg = new CommandsManagerRegistration(this, commands);
        reg.register(ToggleCommands.class);
        reg.register(ProtectionCommands.class);

        getServer().getScheduler().scheduleSyncDelayedTask(this, () -> {
            if (!platform.getGlobalStateManager().hasCommandBookGodMode()) {
                reg.register(GeneralCommands.class);
            }
        }, 0L);

        getServer().getScheduler().scheduleSyncRepeatingTask(this, sessionManager,
                BukkitSessionManager.RUN_DELAY, BukkitSessionManager.RUN_DELAY);

        // Register events
        getServer().getPluginManager().registerEvents(sessionManager, this);
        (new WorldGuardPlayerListener(this)).registerEvents();
        (new WorldGuardBlockListener(this)).registerEvents();
        (new WorldGuardEntityListener(this)).registerEvents();
        (new WorldGuardWeatherListener(this)).registerEvents();
        (new WorldGuardVehicleListener(this)).registerEvents();
        (new WorldGuardServerListener(this)).registerEvents();
        (new WorldGuardHangingListener(this)).registerEvents();

        // Modules
        (playerMoveListener = new PlayerMoveListener(this)).registerEvents();
        (new BlacklistListener(this)).registerEvents();
        (new ChestProtectionListener(this)).registerEvents();
        (new RegionProtectionListener(this)).registerEvents();
        (new RegionFlagsListener(this)).registerEvents();
        (new WorldRulesListener(this)).registerEvents();
        (new BlockedPotionsListener(this)).registerEvents();
        (new EventAbstractionListener(this)).registerEvents();
        (new PlayerModesListener(this)).registerEvents();
        (new BuildPermissionListener(this)).registerEvents();
        (new InvincibilityListener(this)).registerEvents();
        if ("true".equalsIgnoreCase(System.getProperty("worldguard.debug.listener"))) {
            (new DebuggingListener(this, WorldGuard.logger)).registerEvents();
        }

        platform.getGlobalStateManager().updateCommandBookGodMode();

        if (getServer().getPluginManager().isPluginEnabled("CommandBook")) {
            getServer().getPluginManager().registerEvents(new WorldGuardCommandBookListener(this), this);
        }

        // handle worlds separately to initialize already loaded worlds
        WorldGuardWorldListener worldListener = (new WorldGuardWorldListener(this));
        for (World world : getServer().getWorlds()) {
            worldListener.initWorld(world);
        }
        worldListener.registerEvents();

        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                ProcessPlayerEvent event = new ProcessPlayerEvent(player);
                Events.fire(event);
            }
        });

        ((SimpleFlagRegistry) WorldGuard.getInstance().getFlagRegistry()).setInitialized(true);

        // Enable metrics
        final Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID); // bStats plugin id
        if (platform.getGlobalStateManager().extraStats) {
            setupCustomCharts(metrics);
        }

        // File messages.yml

        File f = new File(getDataFolder()+File.separator+"messages.yml");
        if (!f.exists()) {
            try {
                f.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        setMessage("messagePrefix", "&c&lStop!");

        FileConfiguration config = YamlConfiguration.loadConfiguration(f);
        for (String message : config.getConfigurationSection("").getKeys(false)) {
            messageData.put(message, config.getString(message));
        }

        //Kontrola verze překladu WorldGuardu

            try {
                String buildurl = "http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad-master/ws/build.number";
                URL url = new URL(buildurl);
                URLConnection con = url.openConnection();
                Pattern p = Pattern.compile("text/html;\\s+charset=([^\\s]+)\\s*");
                Matcher m = p.matcher(con.getContentType());

                String charset = m.matches() ? m.group(1) : "UTF-8";
                Reader r = new InputStreamReader(con.getInputStream(), charset);
                StringBuilder buf = new StringBuilder();

                while (true) {
                    int ch = r.read();
                    if (ch < 0)
                        break;
                    buf.append((char) ch);
                }
                String str = buf.toString();

                File cacheDir = new File("plugins/WorldGuard", "cache");
                File output = new File(cacheDir,"buildcheck.txt");
                FileWriter writer = new FileWriter(output);

                writer.write(str);
                writer.flush();
                writer.close();

                try {
                    BufferedReader br = new BufferedReader(new FileReader(output));
                    br.readLine();
                    br.readLine();
                    String line3 = br.readLine();

                    String target = line3.copyValueOf("build.number=".toCharArray());
                    String gbuild = line3.replace(target, "");
                    int buildn = Integer.parseInt(gbuild);

                    if (buildn == buildnumber) {
                        getLogger().info("Nainstalovaná verze překladu WorldGuardu je nejnovější!");
                        getLogger().info("Aktuální verze: WorldGuard_"
                                + WorldGuard.getVersion() + "-překlad_v"
                                    + WorldGuard.getTransVersion() + "-B" + buildnumber);
                        } else if (buildn > buildnumber){
                        getLogger().warning("Nová verze překladu WorldGuard CZ je dostupná na http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad-master/");
                        getLogger().info("Aktuální verze: WorldGuard_"
                                + WorldGuard.getVersion() + "-překlad_v"
                                + WorldGuard.getTransVersion() + "-B" + buildnumber);
                        getLogger().warning("Nová verze: WorldGuard_"
                                + WorldGuard.getLatestVersion() + "-překlad_v"
                                    + WorldGuard.getLatestTransVersion() + "-B" + buildn);
                    } else {
                        getLogger().severe("Nesprávná verze překladu WorldGuardu - " + buildnumber + " místo " + buildn + "! Koukni na http://jenkins.valleycube.cz/job/WorldGuard-CZ-preklad-master/");
                    }
                } catch (Exception e) {
                    getLogger().warning("Chyba při načítání updateru!");
                    getLogger().warning("Kód pro podporu: transVer01");
                    e.printStackTrace();
                }
            } catch (Exception e) {
                getLogger().warning("Chyba při načítání celého updateru!");
                getLogger().warning("Kód pro podporu: transVer02");
            }

        int pluginId = 15431; // <-- Replace with the id of your plugin!
        final Metrics stats = new Metrics(this, pluginId);
        stats.addCustomChart(new DrilldownPie("translate_version", () -> {
            Map<String, Map<String, Integer>> map = new HashMap<>();
            String transVersion = WorldGuard.getTransVersion();
            Map<String, Integer> entry = new HashMap<>();
            entry.put(transVersion, 1);
            if (transVersion.startsWith("0.5.1")) {
                map.put("Překlad WG v0.5.1-beta", entry);
            } else if (transVersion.startsWith("1.0")) {
                map.put("Překlad WG v1.0", entry);
            } else if (transVersion.startsWith("1.1")) {
                map.put("Překlad WG v1.1", entry);
            } else {
                map.put("Jiná", entry);
            }
            return map;
        }));
    }

    private void setupCustomCharts(Metrics metrics) {
        metrics.addCustomChart(new SingleLineChart("region_count", () ->
                platform.getRegionContainer().getLoaded().stream().mapToInt(RegionManager::size).sum()));
        metrics.addCustomChart(new SimplePie("region_driver", () -> {
            RegionDriver driver = platform.getGlobalStateManager().selectedRegionStoreDriver;
            return driver instanceof DirectoryYamlDriver ? "yaml" : driver instanceof SQLDriver ? "sql" : "unknown";
        }));
        metrics.addCustomChart(new DrilldownPie("blacklist", () -> {
            int empty = 0;
            Map<String, Integer> blacklistMap = new HashMap<>();
            Map<String, Integer> whitelistMap = new HashMap<>();
            for (BukkitWorldConfiguration worldConfig : platform.getGlobalStateManager().getWorldConfigs()) {
                Blacklist blacklist = worldConfig.getBlacklist();
                if (blacklist != null && !blacklist.isEmpty()) {
                    Map<String, Integer> target = blacklist.isWhitelist() ? whitelistMap : blacklistMap;
                    int floor = ((blacklist.getItemCount() - 1) / 10) * 10;
                    String range = floor >= 100 ? "101+" : (floor + 1) + " - " + (floor + 10);
                    target.merge(range, 1, Integer::sum);
                } else {
                    empty++;
                }
            }
            Map<String, Map<String, Integer>> blacklistCounts = new HashMap<>();
            Map<String, Integer> emptyMap = new HashMap<>();
            emptyMap.put("empty", empty);
            blacklistCounts.put("empty", emptyMap);
            blacklistCounts.put("blacklist", blacklistMap);
            blacklistCounts.put("whitelist", whitelistMap);
            return blacklistCounts;
        }));
        metrics.addCustomChart(new SimplePie("chest_protection", () ->
                "" + platform.getGlobalStateManager().getWorldConfigs().stream().anyMatch(cfg -> cfg.signChestProtection)));
        metrics.addCustomChart(new SimplePie("build_permissions", () ->
                "" + platform.getGlobalStateManager().getWorldConfigs().stream().anyMatch(cfg -> cfg.buildPermissions)));

        metrics.addCustomChart(new SimplePie("custom_flags", () ->
                "" + (WorldGuard.getInstance().getFlagRegistry().size() > Flags.INBUILT_FLAGS.size())));
        metrics.addCustomChart(new SimplePie("custom_handlers", () ->
                "" + (WorldGuard.getInstance().getPlatform().getSessionManager().customHandlersRegistered())));
    }

    @Override
    public void onDisable() {
        WorldGuard.getInstance().disable();
        this.getServer().getScheduler().cancelTasks(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            Actor actor = wrapCommandSender(sender);
            try {
                commands.execute(cmd.getName(), args, actor, actor);
            } catch (Throwable t) {
                Throwable next = t;
                do {
                    try {
                        WorldGuard.getInstance().getExceptionConverter().convert(next);
                    } catch (org.enginehub.piston.exception.CommandException pce) {
                        if (pce.getCause() instanceof CommandException) {
                            throw ((CommandException) pce.getCause());
                        }
                    }
                    next = next.getCause();
                } while (next != null);

                throw t;
            }
        } catch (CommandPermissionsException e) {
            sender.sendMessage(ChatColor.RED + "Nemáš dostatečná práva.");
        } catch (MissingNestedCommandException e) {
            sender.sendMessage(ChatColor.RED + e.getUsage());
        } catch (CommandUsageException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
            sender.sendMessage(ChatColor.RED + e.getUsage());
        } catch (WrappedCommandException e) {
            sender.sendMessage(ChatColor.RED + e.getCause().getMessage());
        } catch (CommandException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
        }

        return true;
    }

    /**
     * Check whether a player is in a group.
     * This calls the corresponding method in PermissionsResolverManager
     *
     * @param player The player to check
     * @param group The group
     * @return whether {@code player} is in {@code group}
     */
    public boolean inGroup(OfflinePlayer player, String group) {
        try {
            return PermissionsResolverManager.getInstance().inGroup(player, group);
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    /**
     * Get the groups of a player.
     * This calls the corresponding method in PermissionsResolverManager.
     * @param player The player to check
     * @return The names of each group the playe is in.
     */
    public String[] getGroups(OfflinePlayer player) {
        try {
            return PermissionsResolverManager.getInstance().getGroups(player);
        } catch (Throwable t) {
            t.printStackTrace();
            return new String[0];
        }
    }

    /**
     * Checks permissions.
     *
     * @param sender The sender to check the permission on.
     * @param perm The permission to check the permission on.
     * @return whether {@code sender} has {@code perm}
     */
    public boolean hasPermission(CommandSender sender, String perm) {
        if (sender.isOp()) {
            if (sender instanceof Player) {
                if (platform.getGlobalStateManager().get(BukkitAdapter.adapt(((Player) sender).getWorld())).opPermissions) {
                    return true;
                }
            } else {
                return true;
            }
        }

        // Invoke the permissions resolver
        if (sender instanceof Player) {
            Player player = (Player) sender;
            return PermissionsResolverManager.getInstance().hasPermission(player.getWorld().getName(), player, perm);
        }

        return false;
    }

    /**
     * Checks permissions and throws an exception if permission is not met.
     *
     * @param sender The sender to check the permission on.
     * @param perm The permission to check the permission on.
     * @throws CommandPermissionsException if {@code sender} doesn't have {@code perm}
     */
    public void checkPermission(CommandSender sender, String perm)
            throws CommandPermissionsException {
        if (!hasPermission(sender, perm)) {
            throw new CommandPermissionsException();
        }
    }

    /**
     * Gets a copy of the WorldEdit plugin.
     *
     * @return The WorldEditPlugin instance
     * @throws CommandException If there is no WorldEditPlugin available
     */
    public WorldEditPlugin getWorldEdit() throws CommandException {
        Plugin worldEdit = getServer().getPluginManager().getPlugin("WorldEdit");
        if (worldEdit == null) {
            throw new CommandException("Zdá se, že WorldEdit není nainstalován.");
        } else if (!worldEdit.isEnabled()) {
            throw new CommandException("Zdá se, že WorldEdit není povolen.");
        }

        if (worldEdit instanceof WorldEditPlugin) {
            return (WorldEditPlugin) worldEdit;
        } else {
            throw new CommandException("Detekce WorldEdit se nezdařila (nahlaš chybu).");
        }
    }

    /**
     * Wrap a player as a LocalPlayer.
     *
     * @param player The player to wrap
     * @return The wrapped player
     */
    public LocalPlayer wrapPlayer(Player player) {
        return new BukkitPlayer(this, player);
    }

    /**
     * Wrap a player as a LocalPlayer.
     *
     * @param player The player to wrap
     * @param silenced True to silence messages
     * @return The wrapped player
     */
    public LocalPlayer wrapPlayer(Player player, boolean silenced) {
        return new BukkitPlayer(this, player, silenced);
    }

    public Actor wrapCommandSender(CommandSender sender) {
        if (sender instanceof Player) {
            return wrapPlayer((Player) sender);
        }

        try {
            return new BukkitCommandSender(getWorldEdit(), sender);
        } catch (CommandException e) {
            e.printStackTrace();
        }
        return null;
    }

    public CommandSender unwrapActor(Actor sender) {
        if (sender instanceof BukkitPlayer) {
            return ((BukkitPlayer) sender).getPlayer();
        } else if (sender instanceof BukkitCommandSender) {
            return Bukkit.getConsoleSender(); // TODO Fix
        } else {
            throw new IllegalArgumentException("Neznámý typ hráče. Prosím o nahlášení");
        }
    }

    /**
     * Wrap a player as a LocalPlayer.
     *
     * <p>This implementation is incomplete -- permissions cannot be checked.</p>
     *
     * @param player The player to wrap
     * @return The wrapped player
     */
    public LocalPlayer wrapOfflinePlayer(OfflinePlayer player) {
        return new BukkitOfflinePlayer(this, player);
    }

    /**
     * Internal method. Do not use as API.
     */
    public BukkitConfigurationManager getConfigManager() {
        return platform.getGlobalStateManager();
    }

    /**
     * Return a protection query helper object that can be used by another
     * plugin to test whether WorldGuard permits an action at a particular
     * place.
     *
     * @return an instance
     */
    public ProtectionQuery createProtectionQuery() {
        return new ProtectionQuery();
    }

    /**
     * Configure WorldGuard's loggers.
     */
    private void configureLogger() {
        RecordMessagePrefixer.register(Logger.getLogger("com.sk89q.worldguard"), "[WorldGuard CZ] ");
    }

    /**
     * Create a default configuration file from the .jar.
     *
     * @param actual The destination file
     * @param defaultName The name of the file inside the jar's defaults folder
     */
    public void createDefaultConfiguration(File actual, String defaultName) {

        // Make parent directories
        File parent = actual.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        if (actual.exists()) {
            return;
        }

        try (InputStream stream = getResource("defaults/" + defaultName)){
            if (stream == null) throw new FileNotFoundException();
            copyDefaultConfig(stream, actual, defaultName);
        } catch (IOException e) {
            getLogger().severe("Nelze přečíst výchozí konfiguraci: " + defaultName);
        }

    }

    private void copyDefaultConfig(InputStream input, File actual, String name) {
        try (FileOutputStream output = new FileOutputStream(actual)) {
            byte[] buf = new byte[8192];
            int length;
            while ((length = input.read(buf)) > 0) {
                output.write(buf, 0, length);
            }
            getLogger().info("Zapsán výchozí konfigurační soubor: " + name);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Zápis výchozího konfiguračního souboru se nezdařil", e);
        }
    }

    public PlayerMoveListener getPlayerMoveListener() {
        return playerMoveListener;
    }

}
