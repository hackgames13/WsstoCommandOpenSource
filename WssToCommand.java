package org.igorgames.wssToCommand;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import okhttp3.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class WssToCommand extends JavaPlugin {

    private final List<WebSocket> activeSockets = new ArrayList<>();
    private OkHttpClient client;

    @Override
    public void onEnable() {
        getLogger().info("WssToCommand plugin starting...");

        // Ensure folders
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File subfolder = new File(getDataFolder(), "wsstocommand");
        if (!subfolder.exists() && subfolder.mkdirs()) {
            getLogger().info("Created folder: " + subfolder.getPath());
        }

        // Ensure config file
        File configFile = new File(subfolder, "config.xml");
        if (!configFile.exists()) {
            try {
                String defaultConfig = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<config>\n" +
                        "  <send>ping</send>\n" +
                        "  <loops>5</loops>\n" +
                        "  <wss_list>\n" +
                        "    <wss>\n" +
                        "      <host>ws://localhost:</host>\n" +
                        "      <port>8080</port>\n" +
                        "      <send_if_connect>\n" +
                        "        <send>test</send>\n" +
                        "        <send>test123</send>\n" +
                        "        <send>GG</send>\n" +
                        "      </send_if_connect>\n" +
                        "      <if_send_message>\n" +
                        "        <command>say %message%</command>\n" +
                        "        <command>say 123%message%</command>\n" +
                        "        <command>say GG%message%</command>\n" +
                        "      </if_send_message>\n" +
                        "    </wss>\n" +
                        "  </wss_list>\n" +
                        "</config>";
                Files.writeString(configFile.toPath(), defaultConfig);
                getLogger().info("Created default config.xml");
            } catch (IOException e) {
                getLogger().severe("Failed to create config.xml: " + e.getMessage());
            }
        }

        // Read and connect
        readConfig(configFile);
    }

    @Override
    public void onDisable() {
        getLogger().info("WssToCommand shutting down...");
        for (WebSocket ws : activeSockets) {
            ws.close(1000, "Server stopping");
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
        }
    }

    private void readConfig(File xmlFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            String send = root.getElementsByTagName("send").item(0).getTextContent();
            long loops = Long.parseLong(root.getElementsByTagName("loops").item(0).getTextContent());

            NodeList wssList = root.getElementsByTagName("wss");
            for (int i = 0; i < wssList.getLength(); i++) {
                Element wss = (Element) wssList.item(i);

                String host = wss.getElementsByTagName("host").item(0).getTextContent();
                String port = wss.getElementsByTagName("port").item(0).getTextContent();

                // Read <send_if_connect>
                NodeList sendNodes = ((Element) wss.getElementsByTagName("send_if_connect").item(0)).getElementsByTagName("send");
                String[] sendIfConnect = new String[sendNodes.getLength()];
                for (int j = 0; j < sendNodes.getLength(); j++) {
                    sendIfConnect[j] = sendNodes.item(j).getTextContent();
                }

                // Read <if_send_message>
                NodeList commandNodes = ((Element) wss.getElementsByTagName("if_send_message").item(0)).getElementsByTagName("command");
                String[] ifSendMessage = new String[commandNodes.getLength()];
                for (int j = 0; j < commandNodes.getLength(); j++) {
                    ifSendMessage[j] = commandNodes.item(j).getTextContent();
                }

                // Connect
                connectWebSocket(host, port, loops, send, sendIfConnect, ifSendMessage);
            }

        } catch (Exception e) {
            getLogger().severe("Error reading config.xml: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void connectWebSocket(String ip, String port, long loops, String periodicSend, String[] onConnectSend, String[] commands) {
        if (client == null) {
            client = new OkHttpClient();
        }

        String url = ip+port;

        Request request = new Request.Builder().url(url).build();

        getLogger().info("Connecting to: " + url);

        client.newWebSocket(request, new WebSocketListener() {
            private WebSocket thisSocket;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                thisSocket = ws;
                activeSockets.add(ws);

                getLogger().info("WebSocket connected to " + url);

                // Send messages immediately on connect
                getServer().getScheduler().runTask(WssToCommand.this, () -> {
                    for (String msg : onConnectSend) {
                        ws.send(msg);
                    }
                });

                // Schedule periodic sends
                getServer().getScheduler().runTaskTimer(WssToCommand.this, () -> {
                    if (thisSocket != null) {
                        thisSocket.send(periodicSend);
                    }
                }, 0L, 20L * loops);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                getLogger().info("Received message: " + text);
                CommandSender console = getServer().getConsoleSender();

                getServer().getScheduler().runTask(WssToCommand.this, () -> {
                    for (String cmd : commands) {
                        String finalCmd = cmd.replace("%message%", text);
                        Bukkit.dispatchCommand(console, finalCmd);
                    }
                });
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                getLogger().severe("WebSocket failure: " + t.getMessage());
                t.printStackTrace();
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                getLogger().info("WebSocket closing: " + reason);
                ws.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                getLogger().info("WebSocket closed: " + reason);
                activeSockets.remove(ws);
            }
        });
    }
}
