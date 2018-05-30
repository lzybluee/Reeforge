package forge.net;

import forge.match.LobbySlotType;
import org.apache.commons.lang3.StringUtils;

import forge.GuiBase;
import forge.assets.FSkinProp;
import forge.interfaces.IGuiGame;
import forge.interfaces.ILobbyListener;
import forge.interfaces.ILobbyView;
import forge.interfaces.IPlayerChangeListener;
import forge.interfaces.IUpdateable;
import forge.match.GameLobby.GameLobbyData;
import forge.model.FModel;
import forge.net.client.ClientGameLobby;
import forge.net.client.FGameClient;
import forge.net.event.IdentifiableNetEvent;
import forge.net.event.MessageEvent;
import forge.net.event.NetEvent;
import forge.net.event.UpdateLobbyPlayerEvent;
import forge.net.server.FServerManager;
import forge.net.server.ServerGameLobby;
import forge.player.GamePlayerUtil;
import forge.properties.ForgeProfileProperties;
import forge.properties.ForgePreferences.FPref;
import forge.util.gui.SOptionPane;

public class NetConnectUtil {
    private NetConnectUtil() { }

    public static String getServerUrl() {
        final String url = SOptionPane.showInputDialog("This feature is under active development.\nYou are likely to find bugs.\n\n - = * H E R E   B E   E L D R A Z I * = -\n\nEnter the URL of the server to join.\nLeave blank to host your own server.", "Connect to Server");
        if (url == null) { return null; }

        //prompt user for player one name if needed
        if (StringUtils.isBlank(FModel.getPreferences().getPref(FPref.PLAYER_NAME))) {
            GamePlayerUtil.setPlayerName();
        }
        return url;
    }

    public static ChatMessage host(final IOnlineLobby onlineLobby, final IOnlineChatInterface chatInterface) {
        final int port = ForgeProfileProperties.getServerPort();
        final FServerManager server = FServerManager.getInstance();
        final ServerGameLobby lobby = new ServerGameLobby();
        final ILobbyView view = onlineLobby.setLobby(lobby);

        server.startServer(port);
        server.setLobby(lobby);

        lobby.setListener(new IUpdateable() {
            @Override
            public final void update(final boolean fullUpdate) {
                view.update(fullUpdate);
                server.updateLobbyState();
            }
            @Override
            public final void update(final int slot, final LobbySlotType type) {return;}
        });
        view.setPlayerChangeListener(new IPlayerChangeListener() {
            @Override
            public final void update(final int index, final UpdateLobbyPlayerEvent event) {
                server.updateSlot(index, event);
                server.updateLobbyState();
            }
        });

        server.setLobbyListener(new ILobbyListener() {
            @Override
            public final void update(final GameLobbyData state, final int slot) {
                // NO-OP, lobby connected directly
            }
            @Override
            public final void message(final String source, final String message) {
                chatInterface.addMessage(new ChatMessage(source, message));
            }
            @Override
            public final void close() {
                // NO-OP, server can't receive close message
            }
            @Override
            public ClientGameLobby getLobby() {
                return null;
            }
        });
        chatInterface.setGameClient(new IRemote() {
            @Override
            public final void send(final NetEvent event) {
                if (event instanceof MessageEvent) {
                    final MessageEvent message = (MessageEvent) event;
                    chatInterface.addMessage(new ChatMessage(message.getSource(), message.getMessage()));
                    server.broadcast(event);
                }
            }
            @Override
            public final Object sendAndWait(final IdentifiableNetEvent event) {
                send(event);
                return null;
            }
        });

        view.update(true);

        return new ChatMessage(null, String.format("Hosting on port %d.", port));
    }

    public static void copyHostedServerUrl() {
        String internalAddress = FServerManager.getInstance().getLocalAddress();
        String externalAddress = FServerManager.getInstance().getExternalAddress();
        String internalUrl = internalAddress + ":" + ForgeProfileProperties.getServerPort();
        String externalUrl = null;
        if (externalAddress != null) {
            externalUrl = externalAddress + ":" + ForgeProfileProperties.getServerPort();
            GuiBase.getInterface().copyToClipboard(externalUrl);
        } else {
            GuiBase.getInterface().copyToClipboard(internalAddress);
        }

        String message = "Share the following URL with anyone who wishes to join your server. It has been copied to your clipboard for convenience.\n\n";
        if (externalUrl != null) {
            message += externalUrl + "\n\nFor internal games, use the following URL: " + internalUrl;
        } else {
            message = "Forge was unable to determine your external IP!\n\n" + message + internalUrl;
        }
        SOptionPane.showMessageDialog(message, "Server URL", SOptionPane.INFORMATION_ICON);
    }

    public static ChatMessage join(final String url, final IOnlineLobby onlineLobby, final IOnlineChatInterface chatInterface) {
        final IGuiGame gui = GuiBase.getInterface().getNewGuiGame();
        final FGameClient client = new FGameClient(FModel.getPreferences().getPref(FPref.PLAYER_NAME), "0", gui);
        onlineLobby.setClient(client);
        chatInterface.setGameClient(client);
        final ClientGameLobby lobby = new ClientGameLobby();
        final ILobbyView view =  onlineLobby.setLobby(lobby);
        lobby.setListener(view);
        client.addLobbyListener(new ILobbyListener() {
            @Override
            public final void message(final String source, final String message) {
                chatInterface.addMessage(new ChatMessage(source, message));
            }
            @Override
            public final void update(final GameLobbyData state, final int slot) {
                lobby.setLocalPlayer(slot);
                lobby.setData(state);
            }
            @Override
            public final void close() {
                SOptionPane.showMessageDialog("Your connection to the host (" + url + ") was interrupted.", "Error", FSkinProp.ICO_WARNING);
                onlineLobby.setClient(null);
            }
            @Override
            public ClientGameLobby getLobby() {
                return lobby;
            }
        });
        view.setPlayerChangeListener(new IPlayerChangeListener() {
            @Override
            public final void update(final int index, final UpdateLobbyPlayerEvent event) {
                client.send(event);
            }
        });

        String hostname = url;
        int port = ForgeProfileProperties.getServerPort();

        //see if port specified in URL
        int index = url.indexOf(':');
        if (index >= 0) {
            hostname = url.substring(0, index);
            String portStr = url.substring(index + 1);
            try {
                port = Integer.parseInt(portStr);
            }
            catch (Exception ex) {}
        }

        try {
            client.connect(hostname, port);
        }
        catch (Exception ex) {
            return null;
        }

        return new ChatMessage(null, String.format("Connected to %s:%d", hostname, port));
    }
}
