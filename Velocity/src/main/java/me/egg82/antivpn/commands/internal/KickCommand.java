package me.egg82.antivpn.commands.internal;

import co.aikar.commands.CommandIssuer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import me.egg82.antivpn.api.VPNAPIProvider;
import me.egg82.antivpn.api.model.ip.IPManager;
import me.egg82.antivpn.api.model.player.PlayerManager;
import me.egg82.antivpn.config.CachedConfig;
import me.egg82.antivpn.config.ConfigUtil;
import me.egg82.antivpn.lang.Message;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class KickCommand extends AbstractCommand {
    private final String player;
    private final String type;

    public KickCommand(@NonNull ProxyServer proxy, @NonNull CommandIssuer issuer, @NonNull String player, @NonNull String type) {
        super(proxy, issuer);
        this.player = player;
        this.type = type;
    }

    public void run() {
        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();
        if (cachedConfig == null) {
            logger.error("Cached config could not be fetched.");
            issuer.sendError(Message.ERROR__INTERNAL);
            return;
        }

        Optional<Player> p = proxy.getPlayer(player);
        if (!p.isPresent()) {
            issuer.sendError(Message.KICK__NO_PLAYER);
            return;
        }

        String ip = getIp(p.get().getRemoteAddress());
        if (ip == null) {
            logger.error("Could not get IP for player " + p.get().getUsername());
            issuer.sendError(Message.ERROR__INTERNAL);
            return;
        }

        if (type.equalsIgnoreCase("vpn")) {
            IPManager ipManager = VPNAPIProvider.getInstance().getIPManager();

            if (cachedConfig.getVPNActionCommands().isEmpty() && cachedConfig.getVPNKickMessage().isEmpty()) {
                issuer.sendError(Message.KICK__API_MODE);
                return;
            }
            List<String> commands = ipManager.getVpnCommands(p.get().getUsername(), p.get().getUniqueId(), ip);
            for (String command : commands) {
                proxy.getCommandManager().executeImmediatelyAsync(proxy.getConsoleCommandSource(), command);
            }
            String kickMessage = ipManager.getVpnKickMessage(p.get().getUsername(), p.get().getUniqueId(), ip);
            if (kickMessage != null) {
                p.get().disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(kickMessage));
            }

            issuer.sendInfo(Message.KICK__END_VPN, "{player}", player);
        } else if (type.equalsIgnoreCase("mcleaks")) {
            PlayerManager playerManager = VPNAPIProvider.getInstance().getPlayerManager();

            if (cachedConfig.getMCLeaksActionCommands().isEmpty() && cachedConfig.getMCLeaksKickMessage().isEmpty()) {
                issuer.sendError(Message.KICK__API_MODE);
                return;
            }
            List<String> commands = playerManager.getMcLeaksCommands(p.get().getUsername(), p.get().getUniqueId(), ip);
            for (String command : commands) {
                proxy.getCommandManager().executeImmediatelyAsync(proxy.getConsoleCommandSource(), command);
            }
            String kickMessage = playerManager.getMcLeaksKickMessage(p.get().getUsername(), p.get().getUniqueId(), ip);
            if (kickMessage != null) {
                p.get().disconnect(LegacyComponentSerializer.legacyAmpersand().deserialize(kickMessage));
            }

            issuer.sendInfo(Message.KICK__END_MCLEAKS, "{player}", player);
        }
    }

    private @Nullable String getIp(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        InetAddress host = address.getAddress();
        if (host == null) {
            return null;
        }
        return host.getHostAddress();
    }
}
