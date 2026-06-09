package com.ghostbe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

public class GhostVpnService extends VpnService {
    private static final String TAG = "GhostVpnService";
    private static final String CHANNEL_ID = "ghostbe_vpn";
    private static volatile boolean running = false;

    private ParcelFileDescriptor tunFd;
    private FileOutputStream tunOut;
    private FileInputStream tunIn;
    private Socket backendSocket;
    private OutputStream backendOut;
    private InputStream backendIn;
    private Thread tunReadThread;
    private Thread backendReadThread;

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

        if (hostIP.isEmpty()) {
            Log.e(TAG, "Error: No backend host configured!");
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        setupVPN();
        return START_STICKY;
    }

    private void setupVPN() {
        try {
            Builder builder = new Builder();
            builder.setSession("GhostBe")
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4");

            for (String pkg : selectedApps) {
                try {
                    builder.addAllowedApplication(pkg);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to add app " + pkg, e);
                }
            }

            tunFd = builder.establish();
            if (tunFd == null) {
                Log.e(TAG, "Failed to establish TUN!");
                return;
            }

            tunIn = new FileInputStream(tunFd.getFileDescriptor());
            tunOut = new FileOutputStream(tunFd.getFileDescriptor());

            try {
                Os.fcntlInt(tunFd.getFileDescriptor(), OsConstants.F_SETFL,
                        Os.fcntlInt(tunFd.getFileDescriptor(), OsConstants.F_GETFL, 0)
                                & ~OsConstants.O_NONBLOCK);
            } catch (ErrnoException e) {
                Log.w(TAG, "Could not set blocking mode", e);
            }

            int tunPort = hostPort - 1;
            Log.d(TAG, "Connecting to backend " + hostIP + ":" + tunPort);
            backendSocket = new Socket(hostIP, tunPort);
            backendOut = backendSocket.getOutputStream();
            backendIn = backendSocket.getInputStream();

            String appPkg = selectedApps.isEmpty() ? "unknown" : selectedApps.iterator().next();
            byte[] appPkgBytes = appPkg.getBytes("UTF-8");
            backendOut.write(ByteBuffer.allocate(4).putInt(appPkgBytes.length).array());
            backendOut.write(appPkgBytes);
            backendOut.flush();
            Log.d(TAG, "Sent app package: " + appPkg);

            tunReadThread = new Thread(() -> tunReader());
            backendReadThread = new Thread(() -> backendReader());
            tunReadThread.start();
            backendReadThread.start();

        } catch (Exception e) {
            Log.e(TAG, "setupVPN error", e);
        }
    }

    private void tunReader() {
        try {
            byte[] packet = new byte[32767];
            while (running) {
                int length = tunIn.read(packet);
                if (length <= 0) break;

                byte[] framed = ByteBuffer.allocate(4 + length)
                        .putInt(length).put(packet, 0, length).array();
                synchronized (backendOut) {
                    backendOut.write(framed);
                    backendOut.flush();
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "tunReader error", e);
        }
    }

    private void backendReader() {
        try {
            byte[] headerBuf = new byte[4];
            while (running) {
                int read = 0;
                while (read < 4) {
                    int n = backendIn.read(headerBuf, read, 4 - read);
                    if (n < 0) { running = false; return; }
                    read += n;
                }
                int length = ((headerBuf[0] & 0xff) << 24) | ((headerBuf[1] & 0xff) << 16) |
                             ((headerBuf[2] & 0xff) << 8) | (headerBuf[3] & 0xff);
                if (length <= 0 || length > 65535) continue;

                byte[] packet = new byte[length];
                read = 0;
                while (read < length) {
                    int n = backendIn.read(packet, read, length - read);
                    if (n < 0) { running = false; return; }
                    read += n;
                }

                synchronized (tunOut) {
                    tunOut.write(packet);
                    tunOut.flush();
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "backendReader error", e);
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
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
                    CHANNEL_ID, "GhostBe VPN", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "=== VPN Service Stopping ===");
        running = false;
        try { if (backendSocket != null) backendSocket.close(); } catch (Exception ignored) {}
        try { if (tunOut != null) tunOut.close(); } catch (Exception ignored) {}
        try { if (tunIn != null) tunIn.close(); } catch (Exception ignored) {}
        try { if (tunFd != null) tunFd.close(); } catch (Exception ignored) {}
        if (tunReadThread != null) try { tunReadThread.join(1000); } catch (Exception ignored) {}
        if (backendReadThread != null) try { backendReadThread.join(1000); } catch (Exception ignored) {}
        super.onDestroy();
        Log.i(TAG, "VPN Service destroyed");
    }

    public static boolean isRunning() { return running; }
}
