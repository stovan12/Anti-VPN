package me.egg82.antivpn.events;

import co.aikar.commands.CommandIssuer;
import inet.ipaddr.IPAddressString;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import me.egg82.antivpn.AntiVPN;
import me.egg82.antivpn.api.VPNAPIProvider;
import me.egg82.antivpn.api.model.ip.AlgorithmMethod;
import me.egg82.antivpn.api.model.ip.IPManager;
import me.egg82.antivpn.api.model.player.PlayerManager;
import me.egg82.antivpn.api.platform.BukkitPlatform;
import me.egg82.antivpn.config.CachedConfig;
import me.egg82.antivpn.config.ConfigUtil;
import me.egg82.antivpn.hooks.LuckPermsHook;
import me.egg82.antivpn.hooks.VaultHook;
import me.egg82.antivpn.utils.BukkitCommandUtil;
import me.egg82.antivpn.utils.ExceptionUtil;
import me.egg82.antivpn.utils.ValidationUtil;
import ninja.egg82.events.BukkitEvents;
import ninja.egg82.service.ServiceLocator;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class PlayerEvents extends EventHolder {
    private final Plugin plugin;
    private final CommandIssuer console;

    public PlayerEvents(@NonNull Plugin plugin, @NonNull CommandIssuer console) {
        this.plugin = plugin;
        this.console = console;

        events.add(
                BukkitEvents.subscribe(plugin, AsyncPlayerPreLoginEvent.class, EventPriority.HIGH)
                        .handler(this::checkPerms)
        );

        events.add(
                BukkitEvents.subscribe(plugin, PlayerLoginEvent.class, EventPriority.LOWEST)
                        .filter(e -> !Bukkit.hasWhitelist() || e.getPlayer().isWhitelisted())
                        .handler(this::checkPlayer)
        );

        events.add(
                BukkitEvents.subscribe(plugin, PlayerLoginEvent.class, EventPriority.MONITOR)
                        .filter(e -> e.getResult() == PlayerLoginEvent.Result.ALLOWED)
                        .filter(e -> !Bukkit.hasWhitelist() || e.getPlayer().isWhitelisted())
                        .handler(e -> {
                            BukkitPlatform.addUniquePlayer(e.getPlayer().getUniqueId());
                            String ip = getIp(e.getAddress());
                            if (ip != null) {
                                try {
                                    BukkitPlatform.addUniqueIp(InetAddress.getByName(ip));
                                } catch (UnknownHostException ex) {
                                    logger.warn("Could not create InetAddress for " + ip);
                                }
                            }
                        })
        );
    }

    private void checkPerms(@NonNull AsyncPlayerPreLoginEvent event) {
        if (Bukkit.hasWhitelist() && !isWhitelisted(event.getUniqueId())) {
            if (ConfigUtil.getDebugOrFalse()) {
                console.sendMessage("<c1>" + event.getName() + " (" + event.getUniqueId() + ")</c1><c2>" + " is not whitelisted while the server is in whitelist mode. Ignoring.</c2>");
            }
            return;
        }

        Optional<LuckPermsHook> luckPermsHook;
        try {
            luckPermsHook = ServiceLocator.getOptional(LuckPermsHook.class);
        } catch (InstantiationException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
            luckPermsHook = Optional.empty();
        }

        if (luckPermsHook.isPresent()) {
            // LuckPerms is available, run through entire check gambit
            Boolean val;
            try {
                val = luckPermsHook.get().hasPermission(event.getUniqueId(), "avpn.bypass").get();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                val = null;
            } catch (ExecutionException | CancellationException ex) {
                ExceptionUtil.handleException(ex, logger);
                val = null;
            }
            checkPermsPlayer(event, Boolean.TRUE.equals(val));
        } else {
            // LuckPerms is not available, check for Vault
            Optional<VaultHook> vaultHook;
            try {
                vaultHook = ServiceLocator.getOptional(VaultHook.class);
            } catch (InstantiationException | IllegalAccessException ex) {
                logger.error(ex.getMessage(), ex);
                vaultHook = Optional.empty();
            }

            if (vaultHook.isPresent() && vaultHook.get().getPermission() != null) {
                // Vault is available, run through entire check gambit
                checkPermsPlayer(event, vaultHook.get().getPermission().playerHas(null, Bukkit.getOfflinePlayer(event.getUniqueId()), "avpn.bypass"));
            } else {
                // Vault is not available, only cache data
                cachePlayer(event);
            }
        }
    }

    private void checkPermsPlayer(@NonNull AsyncPlayerPreLoginEvent event, boolean hasBypass) {
        if (hasBypass) {
            if (ConfigUtil.getDebugOrFalse()) {
                console.sendMessage("<c1>" + event.getName() + "</c1> <c2>bypasses pre-check. Ignoring.</c2>");
            }
            return;
        }

        String ip = getIp(event.getAddress());
        if (ip == null || ip.isEmpty()) {
            return;
        }

        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();
        if (cachedConfig == null) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        // Check ignored IP addresses/ranges
        for (String testAddress : cachedConfig.getIgnoredIps()) {
            if (ValidationUtil.isValidIp(testAddress) && ip.equalsIgnoreCase(testAddress)) {
                if (ConfigUtil.getDebugOrFalse()) {
                    console.sendMessage("<c1>" + event.getName() + "</c1> <c2>is using an ignored IP</c2> <c1>" + ip + "</c1><c2>. Ignoring.</c2>");
                }
                return;
            } else if (ValidationUtil.isValidIpRange(testAddress) && rangeContains(testAddress, ip)) {
                if (ConfigUtil.getDebugOrFalse()) {
                    console.sendMessage("<c1>" + event.getName() + "</c1> <c2>is under an ignored range</c2> <c1>" + testAddress + " (" + ip + ")" + "</c1><c2>. Ignoring.</c2>");
                }
                return;
            }
        }

        cacheData(ip, event.getUniqueId(), cachedConfig);

        if (isVpn(ip, event.getName(), cachedConfig)) {
            AntiVPN.incrementBlockedVPNs();
            IPManager ipManager = VPNAPIProvider.getInstance().getIPManager();
            BukkitCommandUtil.dispatchCommands(ipManager.getVpnCommands(event.getName(), event.getUniqueId(), ip), Bukkit.getConsoleSender(), plugin, true);
            String kickMessage = ipManager.getVpnKickMessage(event.getName(), event.getUniqueId(), ip);
            if (kickMessage != null) {
                event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
                event.setKickMessage(kickMessage);
            }
        }

        if (isMcLeaks(event.getName(), event.getUniqueId(), cachedConfig)) {
            AntiVPN.incrementBlockedMCLeaks();
            PlayerManager playerManager = VPNAPIProvider.getInstance().getPlayerManager();
            BukkitCommandUtil.dispatchCommands(playerManager.getMcLeaksCommands(event.getName(), event.getUniqueId(), ip), Bukkit.getConsoleSender(), plugin, true);
            String kickMessage = playerManager.getMcLeaksKickMessage(event.getName(), event.getUniqueId(), ip);
            if (kickMessage != null) {
                event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
                event.setKickMessage(kickMessage);
            }
        }
    }

    private void cachePlayer(@NonNull AsyncPlayerPreLoginEvent event) {
        String ip = getIp(event.getAddress());
        if (ip == null || ip.isEmpty()) {
            return;
        }

        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();
        if (cachedConfig == null) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        // Check ignored IP addresses/ranges
        for (String testAddress : cachedConfig.getIgnoredIps()) {
            if (
                    ValidationUtil.isValidIp(testAddress) && ip.equalsIgnoreCase(testAddress)
                    || ValidationUtil.isValidIpRange(testAddress) && rangeContains(testAddress, ip)
            ) {
                return;
            }
        }

        cacheData(ip, event.getUniqueId(), cachedConfig);
    }

    private void cacheData(@NonNull String ip, @NonNull UUID uuid, @NonNull CachedConfig cachedConfig) {
        // Cache IP data
        if ((!cachedConfig.getVPNKickMessage().isEmpty() || !cachedConfig.getVPNActionCommands().isEmpty())) {
            IPManager ipManager = VPNAPIProvider.getInstance().getIPManager();
            if (cachedConfig.getVPNAlgorithmMethod() == AlgorithmMethod.CONSESNSUS) {
                try {
                    ipManager.consensus(ip, true).get(); // Calling this will cache the result internally, even if the value is unused
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | CancellationException ex) {
                    ExceptionUtil.handleException(ex, logger);
                }
            } else {
                try {
                    ipManager.cascade(ip, true).get(); // Calling this will cache the result internally, even if the value is unused
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | CancellationException ex) {
                    ExceptionUtil.handleException(ex, logger);
                }
            }
        }

        // Cache MCLeaks data
        if (!cachedConfig.getMCLeaksKickMessage().isEmpty() || !cachedConfig.getMCLeaksActionCommands().isEmpty()) {
            PlayerManager playerManager = VPNAPIProvider.getInstance().getPlayerManager();
            try {
                playerManager.checkMcLeaks(uuid, true).get(); // Calling this will cache the result internally, even if the value is unused
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | CancellationException ex) {
                ExceptionUtil.handleException(ex, logger);
            }
        }
    }

    private void checkPlayer(@NonNull PlayerLoginEvent event) {
        Optional<LuckPermsHook> luckPermsHook;
        try {
            luckPermsHook = ServiceLocator.getOptional(LuckPermsHook.class);
        } catch (InstantiationException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
            luckPermsHook = Optional.empty();
        }

        if (luckPermsHook.isPresent()) {
            return;
        }

        Optional<VaultHook> vaultHook;
        try {
            vaultHook = ServiceLocator.getOptional(VaultHook.class);
        } catch (InstantiationException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
            vaultHook = Optional.empty();
        }

        if (vaultHook.isPresent() && vaultHook.get().getPermission() != null) {
            return;
        }

        String ip = getIp(event.getAddress());
        if (ip == null || ip.isEmpty()) {
            return;
        }

        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();
        if (cachedConfig == null) {
            logger.error("Cached config could not be fetched.");
            return;
        }

        if (event.getPlayer().hasPermission("avpn.bypass")) {
            if (ConfigUtil.getDebugOrFalse()) {
                console.sendMessage("<c1>" + event.getPlayer().getName() + "</c1> <c2>bypasses actions. Ignoring.</c2>");
            }
            return;
        }

        for (String testAddress : cachedConfig.getIgnoredIps()) {
            if (ValidationUtil.isValidIp(testAddress) && ip.equalsIgnoreCase(testAddress)) {
                if (ConfigUtil.getDebugOrFalse()) {
                    console.sendMessage("<c1>" + event.getPlayer().getName() + "</c1> <c2>is using an ignored IP</c2> <c1>" + ip + "</c1><c2>. Ignoring.</c2>");
                }
                return;
            } else if (ValidationUtil.isValidIpRange(testAddress) && rangeContains(testAddress, ip)) {
                if (ConfigUtil.getDebugOrFalse()) {
                    console.sendMessage("<c1>" + event.getPlayer().getName() + "</c1> <c2>is under an ignored range</c2> <c1>" + testAddress + " (" + ip + ")" + "</c1><c2>. Ignoring.</c2>");
                }
                return;
            }
        }

        if (isVpn(ip, event.getPlayer().getName(), cachedConfig)) {
            AntiVPN.incrementBlockedVPNs();
            IPManager ipManager = VPNAPIProvider.getInstance().getIPManager();
            BukkitCommandUtil.dispatchCommands(ipManager.getVpnCommands(event.getPlayer().getName(), event.getPlayer().getUniqueId(), ip), Bukkit.getConsoleSender(), plugin, false);
            String kickMessage = ipManager.getVpnKickMessage(event.getPlayer().getName(), event.getPlayer().getUniqueId(), ip);
            if (kickMessage != null) {
                event.setResult(PlayerLoginEvent.Result.KICK_OTHER);
                event.setKickMessage(kickMessage);
            }
        }

        if (isMcLeaks(event.getPlayer().getName(), event.getPlayer().getUniqueId(), cachedConfig)) {
            AntiVPN.incrementBlockedMCLeaks();
            PlayerManager playerManager = VPNAPIProvider.getInstance().getPlayerManager();
            BukkitCommandUtil.dispatchCommands(playerManager.getMcLeaksCommands(event.getPlayer().getName(), event.getPlayer().getUniqueId(), ip), Bukkit.getConsoleSender(), plugin, false);
            String kickMessage = playerManager.getMcLeaksKickMessage(event.getPlayer().getName(), event.getPlayer().getUniqueId(), ip);
            if (kickMessage != null) {
                event.setResult(PlayerLoginEvent.Result.KICK_OTHER);
                event.setKickMessage(kickMessage);
            }
        }
    }

    private boolean isVpn(@NonNull String ip, @NonNull String name, @NonNull CachedConfig cachedConfig) {
        if (!cachedConfig.getVPNKickMessage().isEmpty() || !cachedConfig.getVPNActionCommands().isEmpty()) {
            boolean isVPN;

            IPManager ipManager = VPNAPIProvider.getInstance().getIPManager();
            if (cachedConfig.getVPNAlgorithmMethod() == AlgorithmMethod.CONSESNSUS) {
                try {
                    Double val = ipManager.consensus(ip, true).get();
                    isVPN = val != null && val >= cachedConfig.getVPNAlgorithmConsensus();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    isVPN = false;
                } catch (ExecutionException | CancellationException ex) {
                    ExceptionUtil.handleException(ex, logger);
                    isVPN = false;
                }
            } else {
                try {
                    isVPN = Boolean.TRUE.equals(ipManager.cascade(ip, true).get());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    isVPN = false;
                } catch (ExecutionException | CancellationException ex) {
                    ExceptionUtil.handleException(ex, logger);
                    isVPN = false;
                }
            }

            if (isVPN) {
                if (cachedConfig.getDebug()) {
                    console.sendMessage("<c1>" + name + "</c1> <c9>found using a VPN. Running required actions.</c9>");
                }
            } else {
                if (cachedConfig.getDebug()) {
                    console.sendMessage("<c1>" + name + "</c1> <c4>passed VPN check.</c4>");
                }
            }
            return isVPN;
        } else {
            if (cachedConfig.getDebug()) {
                console.sendMessage("<c2>Plugin set to API-only. Ignoring VPN check for</c2> <c1>" + name + "</c1>");
            }
        }

        return false;
    }

    private boolean isMcLeaks(@NonNull String name, @NonNull UUID uuid, @NonNull CachedConfig cachedConfig) {
        if (!cachedConfig.getMCLeaksKickMessage().isEmpty() || !cachedConfig.getMCLeaksActionCommands().isEmpty()) {
            boolean isMCLeaks;

            PlayerManager playerManager = VPNAPIProvider.getInstance().getPlayerManager();
            try {
                isMCLeaks = Boolean.TRUE.equals(playerManager.checkMcLeaks(uuid, true).get());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                isMCLeaks = false;
            } catch (ExecutionException | CancellationException ex) {
                ExceptionUtil.handleException(ex, logger);
                isMCLeaks = false;
            }

            if (isMCLeaks) {
                if (cachedConfig.getDebug()) {
                    console.sendMessage("<c1>" + name + "</c1> <c9>found using an MCLeaks account. Running required actions.</c9>");
                }
            } else {
                if (cachedConfig.getDebug()) {
                    console.sendMessage("<c1>" + name + "</c1> <c4>passed MCLeaks check.</c4>");
                }
            }
            return isMCLeaks;
        } else {
            if (cachedConfig.getDebug()) {
                console.sendMessage("<c2>Plugin set to API-only. Ignoring MCLeaks check for</c2> <c1>" + name + "</c1>");
            }
        }

        return false;
    }

    private @Nullable String getIp(InetAddress address) {
        if (address == null) {
            return null;
        }

        return address.getHostAddress();
    }

    private boolean isWhitelisted(UUID playerID) {
        for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
            if (playerID.equals(p.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    private boolean rangeContains(@NonNull String range, @NonNull String ip) { return new IPAddressString(range).contains(new IPAddressString(ip)); }
}
