package com.ghostbe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

public class GhostVpnService extends VpnService {
    private static final String TAG = "GhostVpnService";
    private static final String CHANNEL_ID = "ghostbe_vpn";
    private static volatile boolean running = false;

    private ParcelFileDescriptor tunFd;
    private Thread tunReadThread;
    private Thread tunWriteThread;
    private Thread miniProxyThread;
    private ServerSocket miniProxySocket;

    private String hostIP;
    private int hostPort;
    private Set<String> selectedApps;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "=== VPN Service Starting ===");
        startForeground(1, createNotification());

        SharedPreferences prefs = getSharedPreferences("ghostbe", MODE_PRIVATE);
        hostIP = prefs.getString("host", "");
        hostPort = prefs.getInt("port", 8877);

        selectedApps = loadSelectedApps();
        Log.d(TAG, "Backend: " + hostIP + ":" + hostPort);
        Log.d(TAG, "Selected apps: " + selectedApps.size());
        for (String app : selectedApps) {
            Log.d(TAG, "  - " + app);
        }

        if (hostIP.isEmpty()) {
            Log.e(TAG, "Error: No backend host configured!");
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;

        // Start mini proxy
        Log.d(TAG, "Starting mini proxy on port 8788...");
        miniProxyThread = new Thread(() -> startMiniProxy());
        miniProxyThread.start();

        // Setup VPN
        Log.d(TAG, "Setting up VPN...");
        setupVPN();

        return START_STICKY;
    }

    private void setupVPN() {
        try {
            Log.d(TAG, "setupVPN: Creating VPN interface...");
            Builder builder = new Builder();
            builder.setSession("GhostBe")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0);

            // Add allowed apps
            for (String pkg : selectedApps) {
                try {
                    builder.addAllowedApplication(pkg);
                    Log.d(TAG, "setupVPN: Added app " + pkg);
                } catch (Exception e) {
                    Log.w(TAG, "setupVPN: Failed to add app " + pkg, e);
                }
            }

            tunFd = builder.establish();
            if (tunFd == null) {
                Log.e(TAG, "setupVPN: Failed to establish TUN interface!");
                return;
            }

            Log.d(TAG, "setupVPN: TUN interface established successfully");

            // Start reader and writer threads
            tunReadThread = new Thread(() -> tunReader());
            tunWriteThread = new Thread(() -> tunWriter());

            tunReadThread.start();
            tunWriteThread.start();

            Log.d(TAG, "setupVPN: TUN reader and writer threads started");

        } catch (Exception e) {
            Log.e(TAG, "setupVPN: Error", e);
        }
    }

    private void startMiniProxy() {
        try {
            Log.d(TAG, "startMiniProxy: Starting server on port 8788...");
            miniProxySocket = new ServerSocket(8788);
            Log.i(TAG, "startMiniProxy: Server listening on port 8788");
            
            while (running) {
                Log.d(TAG, "startMiniProxy: Waiting for client connection...");
                Socket client = miniProxySocket.accept();
                Log.i(TAG, "startMiniProxy: Client connected from " + client.getInetAddress());
                new Thread(() -> handleMiniProxyClient(client)).start();
            }
        } catch (Exception e) {
            Log.e(TAG, "startMiniProxy: Error", e);
        }
    }

    private void handleMiniProxyClient(Socket client) {
        try (
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream()
        ) {
            Log.d(TAG, "handleMiniProxyClient: Processing request...");
            
            // Read first line to determine HTTP or HTTPS
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            String firstLine = new String(buffer, 0, read).split("\n")[0];
            Log.d(TAG, "handleMiniProxyClient: First line: " + firstLine);

            // Determine app package (for now, use first selected app)
            String appPackage = selectedApps.isEmpty() ? "unknown" : selectedApps.iterator().next();
            Log.d(TAG, "handleMiniProxyClient: App package: " + appPackage);

            // Forward to host proxy
            Log.d(TAG, "handleMiniProxyClient: Connecting to backend " + hostIP + ":" + hostPort);
            try (Socket server = new Socket(hostIP, hostPort)) {
                Log.i(TAG, "handleMiniProxyClient: Connected to backend");
                server.getOutputStream().write(buffer, 0, read);

                // Relay traffic
                Thread clientToServer = new Thread(() -> {
                    try {
                        Log.d(TAG, "handleMiniProxyClient: Starting client-to-server relay...");
                        relay(in, server.getOutputStream());
                        Log.d(TAG, "handleMiniProxyClient: Client-to-server relay completed");
                    } catch (Exception e) {
                        Log.e(TAG, "handleMiniProxyClient: Client-to-server relay error", e);
                    }
                });
                Thread serverToClient = new Thread(() -> {
                    try {
                        Log.d(TAG, "handleMiniProxyClient: Starting server-to-client relay...");
                        relay(server.getInputStream(), out);
                        Log.d(TAG, "handleMiniProxyClient: Server-to-client relay completed");
                    } catch (Exception e) {
                        Log.e(TAG, "handleMiniProxyClient: Server-to-client relay error", e);
                    }
                });

                clientToServer.start();
                serverToClient.start();

                clientToServer.join();
                serverToClient.join();
                
                Log.d(TAG, "handleMiniProxyClient: Request completed");
            } catch (Exception e) {
                Log.e(TAG, "handleMiniProxyClient: Failed to connect to backend", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleMiniProxyClient: Error", e);
        }
    }

    private void relay(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[4096];
            int read;
            int totalBytes = 0;
            
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
                totalBytes += read;
            }
            
            Log.d(TAG, "relay: Relay completed, total bytes: " + totalBytes);
        } catch (Exception e) {
            Log.e(TAG, "relay: Error", e);
        }
    }

    private void tunReader() {
        try {
            Log.d(TAG, "tunReader: Starting packet reader...");
            FileInputStream fis = new FileInputStream(tunFd.getFileDescriptor());
            byte[] packet = new byte[32767];
            int length;
            int packetCount = 0;

            while (running) {
                Log.d(TAG, "tunReader: Waiting for packet from TUN device...");
                length = fis.read(packet);
                
                if (length == -1) {
                    Log.w(TAG, "tunReader: TUN device returned EOF (length=-1)");
                    break;
                }
                
                if (length == 0) {
                    Log.w(TAG, "tunReader: TUN device returned 0 bytes");
                    continue;
                }
                
                packetCount++;
                Log.d(TAG, "tunReader: Received packet " + packetCount + ", length=" + length);
                
                // Parse IP packet and route to proxy
                routePacket(packet, length);
            }
            Log.d(TAG, "tunReader: Stopped after " + packetCount + " packets (running=" + running + ")");
        } catch (Exception e) {
            Log.e(TAG, "tunReader: Error", e);
        }
    }

    private void routePacket(byte[] packet, int length) {
        try {
            Log.d(TAG, "routePacket: Processing " + length + " byte packet");
            ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length);
            
            // Parse IPv4 header
            int version = (buffer.get(0) >> 4) & 0xf;
            Log.d(TAG, "routePacket: IP version=" + version);
            
            if (version != 4) {
                Log.d(TAG, "routePacket: Ignoring non-IPv4 packet (version " + version + ")");
                return; // Only handle IPv4
            }

            int headerLength = (buffer.get(0) & 0xf) * 4;
            int protocol = buffer.get(9) & 0xff;
            Log.d(TAG, "routePacket: Protocol=" + protocol);

            // Extract source and destination IPs (4 bytes each)
            byte[] srcIpBytes = new byte[4];
            byte[] dstIpBytes = new byte[4];
            buffer.position(12);
            buffer.get(srcIpBytes);
            buffer.get(dstIpBytes);

            String srcIp = getIpString(srcIpBytes);
            String dstIp = getIpString(dstIpBytes);

            if (protocol == 6) { // TCP
                buffer.position(headerLength);
                
                // Extract port info
                int srcPort = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);
                int dstPort = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);

                Log.i(TAG, "routePacket: TCP " + srcIp + ":" + srcPort + " -> " + dstIp + ":" + dstPort);

                // Route TCP traffic to local proxy on port 8788
                if (dstPort == 80 || dstPort == 443) {
                    Log.d(TAG, "routePacket: Routing to proxy (port " + dstPort + ")");
                    // Forward to local proxy server
                    try (Socket proxySocket = new Socket("127.0.0.1", 8788)) {
                        // Send the packet payload to proxy
                        int payloadLength = length - headerLength - 20; // TCP header is 20 bytes min
                        if (payloadLength > 0) {
                            byte[] payload = new byte[payloadLength];
                            buffer.position(headerLength + 20);
                            buffer.get(payload);
                            proxySocket.getOutputStream().write(payload);
                            proxySocket.getOutputStream().flush();
                            Log.d(TAG, "routePacket: Sent " + payloadLength + " bytes to proxy");
                        } else {
                            Log.d(TAG, "routePacket: No payload in TCP packet (length=" + payloadLength + ")");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "routePacket: Failed to connect to proxy", e);
                    }
                } else {
                    Log.d(TAG, "routePacket: TCP port " + dstPort + " not intercepted (only 80/443)");
                }
            } else if (protocol == 17) { // UDP
                Log.d(TAG, "routePacket: UDP packet - not intercepted");
            } else {
                Log.d(TAG, "routePacket: Protocol " + protocol + " not handled");
            }
        } catch (Exception e) {
            Log.e(TAG, "routePacket: Error", e);
        }
    }

    private String getIpString(byte[] ip) {
        return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
    }

    private void tunWriter() {
        try {
            FileOutputStream fos = new FileOutputStream(tunFd.getFileDescriptor());
            // Write packets back (stub for now)
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Set<String> loadSelectedApps() {
        SharedPreferences prefs = getSharedPreferences("ghostbe", MODE_PRIVATE);
        String json = prefs.getString("selected_apps", "[]");
        Set<String> result = new HashSet<>();

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getString(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return result;
    }

    private Notification createNotification() {
        createNotificationChannel();

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GhostBe VPN")
                .setContentText("Active")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "GhostBe VPN",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "=== VPN Service Stopping ===");
        running = false;

        try {
            if (tunFd != null) {
                tunFd.close();
                Log.d(TAG, "onDestroy: TUN file descriptor closed");
            }
            if (miniProxySocket != null) {
                miniProxySocket.close();
                Log.d(TAG, "onDestroy: Mini proxy socket closed");
            }
            if (tunReadThread != null) {
                tunReadThread.join(1000);
                Log.d(TAG, "onDestroy: TUN reader thread stopped");
            }
            if (tunWriteThread != null) {
                tunWriteThread.join(1000);
                Log.d(TAG, "onDestroy: TUN writer thread stopped");
            }
            if (miniProxyThread != null) {
                miniProxyThread.join(1000);
                Log.d(TAG, "onDestroy: Mini proxy thread stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroy: Error", e);
        }

        super.onDestroy();
        Log.i(TAG, "VPN Service destroyed");
    }

    public static boolean isRunning() {
        return running;
    }
}
