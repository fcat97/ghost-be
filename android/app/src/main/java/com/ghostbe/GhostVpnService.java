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
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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
        startForeground(1, createNotification());

        SharedPreferences prefs = getSharedPreferences("ghostbe", MODE_PRIVATE);
        hostIP = prefs.getString("host", "");
        hostPort = prefs.getInt("port", 8877);

        selectedApps = loadSelectedApps();

        if (hostIP.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;

        // Start mini proxy
        miniProxyThread = new Thread(() -> startMiniProxy());
        miniProxyThread.start();

        // Setup VPN
        setupVPN();

        return START_STICKY;
    }

    private void setupVPN() {
        try {
            Builder builder = new Builder();
            builder.setSession("GhostBe")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0);

            // Add allowed apps
            for (String pkg : selectedApps) {
                try {
                    builder.addAllowedApplication(pkg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            tunFd = builder.establish();
            if (tunFd == null) {
                return;
            }

            // Start reader and writer threads
            tunReadThread = new Thread(() -> tunReader());
            tunWriteThread = new Thread(() -> tunWriter());

            tunReadThread.start();
            tunWriteThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startMiniProxy() {
        try {
            miniProxySocket = new ServerSocket(8788);
            while (running) {
                Socket client = miniProxySocket.accept();
                new Thread(() -> handleMiniProxyClient(client)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleMiniProxyClient(Socket client) {
        try (
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream()
        ) {
            // Read first line to determine HTTP or HTTPS
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            String firstLine = new String(buffer, 0, read).split("\n")[0];

            // Determine app package (for now, use first selected app)
            String appPackage = selectedApps.isEmpty() ? "unknown" : selectedApps.iterator().next();

            // Forward to host proxy
            try (Socket server = new Socket(hostIP, hostPort)) {
                server.getOutputStream().write(buffer, 0, read);

                // Relay traffic
                Thread clientToServer = new Thread(() -> {
                    try {
                        relay(in, server.getOutputStream());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                Thread serverToClient = new Thread(() -> {
                    try {
                        relay(server.getInputStream(), out);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                clientToServer.start();
                serverToClient.start();

                clientToServer.join();
                serverToClient.join();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void relay(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void tunReader() {
        try {
            FileInputStream fis = new FileInputStream(tunFd.getFileDescriptor());
            byte[] packet = new byte[32767];
            int length;

            while (running && (length = fis.read(packet)) > 0) {
                // Parse IP packet and extract TCP destination
                // For now, just read packets
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void tunWriter() {
        try {
            FileOutputStream fos = new FileOutputStream(tunFd.getFileDescriptor());
            // Write packets back
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
        running = false;

        try {
            if (tunFd != null) tunFd.close();
            if (miniProxySocket != null) miniProxySocket.close();
            if (tunReadThread != null) tunReadThread.join(1000);
            if (tunWriteThread != null) tunWriteThread.join(1000);
            if (miniProxyThread != null) miniProxyThread.join(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        super.onDestroy();
    }

    public static boolean isRunning() {
        return running;
    }
}
