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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class GhostVpnService extends VpnService {
    private static final String TAG = "GhostVpnService";
    private static final String CHANNEL_ID = "ghostbe_vpn";
    private static volatile boolean running = false;

    private ParcelFileDescriptor tunFd;
    private FileOutputStream tunOut;
    private Thread tunReadThread;
    private Thread tunWriteThread;
    private Thread miniProxyThread;
    private ServerSocket miniProxySocket;

    private String hostIP;
    private int hostPort;
    private Set<String> selectedApps;
    private final Map<String, TcpConnection> connections = new HashMap<>();
    private final Random random = new Random();

    private static class TcpConnection {
        long clientSeq, serverSeq;
        long clientAck, serverAck;
        boolean established;
    }

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
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4");

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

            tunOut = new FileOutputStream(tunFd.getFileDescriptor());

            try {
                Os.fcntlInt(tunFd.getFileDescriptor(), OsConstants.F_SETFL,
                        Os.fcntlInt(tunFd.getFileDescriptor(), OsConstants.F_GETFL, 0)
                                & ~OsConstants.O_NONBLOCK);
                Log.d(TAG, "setupVPN: TUN fd set to blocking mode");
            } catch (ErrnoException e) {
                Log.w(TAG, "setupVPN: Could not set TUN fd to blocking mode", e);
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
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
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
                return;
            }

            int ipHeaderLen = (buffer.get(0) & 0xf) * 4;
            int protocol = buffer.get(9) & 0xff;
            Log.d(TAG, "routePacket: Protocol=" + protocol);

            byte[] srcIpBytes = new byte[4];
            byte[] dstIpBytes = new byte[4];
            buffer.position(12);
            buffer.get(srcIpBytes);
            buffer.get(dstIpBytes);

            String srcIp = getIpString(srcIpBytes);
            String dstIp = getIpString(dstIpBytes);

            if (protocol == 6) { // TCP
                buffer.position(ipHeaderLen);

                int srcPort = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);
                int dstPort = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);

                Log.i(TAG, "routePacket: TCP " + srcIp + ":" + srcPort + " -> " + dstIp + ":" + dstPort);

                if (dstPort != 80 && dstPort != 443) {
                    Log.d(TAG, "routePacket: TCP port " + dstPort + " not intercepted (only 80/443)");
                } else {
                    int tcpHeaderLen = ((packet[ipHeaderLen + 12] >> 4) & 0xf) * 4;
                    int payloadOffset = ipHeaderLen + tcpHeaderLen;
                    int payloadLength = length - payloadOffset;
                    byte tcpFlags = packet[ipHeaderLen + 13];
                    String connKey = srcIp + ":" + srcPort + "-" + dstIp + ":" + dstPort;

                    if ((tcpFlags & 0x02) != 0 && (tcpFlags & 0x10) == 0) {
                        handleSyn(packet, length, ipHeaderLen, connKey, srcIpBytes, dstIpBytes, srcPort, dstPort);
                    } else if ((tcpFlags & 0x01) != 0 || (tcpFlags & 0x04) != 0) {
                        connections.remove(connKey);
                        Log.d(TAG, "routePacket: Connection closed: " + connKey);
                    } else if (payloadLength > 0) {
                        TcpConnection conn = connections.get(connKey);
                        if (conn == null) {
                            Log.d(TAG, "routePacket: No state for " + connKey + ", creating new");
                            conn = new TcpConnection();
                            conn.established = true;
                            byte[] b = packet;
                            conn.clientSeq = ((long)(b[ipHeaderLen + 4] & 0xff) << 24) |
                                             ((long)(b[ipHeaderLen + 5] & 0xff) << 16) |
                                             ((long)(b[ipHeaderLen + 6] & 0xff) << 8) |
                                             ((long)(b[ipHeaderLen + 7] & 0xff));
                            conn.serverSeq = random.nextLong() & 0xffffffffL;
                            connections.put(connKey, conn);
                        }
                        if (!conn.established) {
                            conn.established = true;
                            Log.d(TAG, "routePacket: Connection established for " + connKey);
                        }
                        byte[] payload = new byte[payloadLength];
                        System.arraycopy(packet, payloadOffset, payload, 0, payloadLength);
                        try (Socket proxySocket = new Socket("127.0.0.1", 8788)) {
                            proxySocket.setSoTimeout(15000);
                            proxySocket.getOutputStream().write(payload);
                            proxySocket.getOutputStream().flush();
                            Log.d(TAG, "routePacket: Sent " + payloadLength + " bytes to proxy, waiting for response...");
                            byte[] responseBuf = new byte[65535];
                            int responseLen = proxySocket.getInputStream().read(responseBuf);
                            if (responseLen > 0) {
                                Log.d(TAG, "routePacket: Received " + responseLen + " byte response from proxy");
                                writeTunResponse(packet, length, responseBuf, responseLen,
                                        ipHeaderLen, tcpHeaderLen, payloadOffset,
                                        srcIpBytes, dstIpBytes, srcPort, dstPort);
                            } else {
                                Log.d(TAG, "routePacket: Empty response from proxy");
                            }
                        } catch (SocketTimeoutException e) {
                            Log.w(TAG, "routePacket: Proxy response timed out after 15s");
                        } catch (Exception e) {
                            Log.w(TAG, "routePacket: Proxy communication error", e);
                        }
                    } else {
                        TcpConnection conn = connections.get(connKey);
                        if (conn != null && !conn.established) {
                            conn.established = true;
                            Log.d(TAG, "routePacket: Connection established via ACK: " + connKey);
                        }
                    }
                }
            } else if (protocol == 17) { // UDP
                buffer.position(ipHeaderLen);
                int srcPort = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);
                int dstPort = ((buffer.get() & 0xff) << 8) | (buffer.get() & 0xff);
                Log.d(TAG, "routePacket: UDP " + srcIp + ":" + srcPort + " -> " + dstIp + ":" + dstPort);

                if (dstPort == 53) {
                    int udpHeaderLen = 8;
                    int udpPayloadOffset = ipHeaderLen + udpHeaderLen;
                    int udpPayloadLen = length - udpPayloadOffset;
                    if (udpPayloadLen > 0) {
                        byte[] payload = new byte[udpPayloadLen];
                        System.arraycopy(packet, udpPayloadOffset, payload, 0, udpPayloadLen);
                        forwardDnsQuery(payload, udpPayloadLen, srcIpBytes, dstIpBytes, srcPort, dstPort,
                                packet, length, ipHeaderLen);
                    }
                } else {
                    Log.d(TAG, "routePacket: UDP port " + dstPort + " not intercepted");
                }
            } else {
                Log.d(TAG, "routePacket: Protocol " + protocol + " not handled");
            }
        } catch (Exception e) {
            Log.e(TAG, "routePacket: Error", e);
        }
    }

    private void handleSyn(byte[] packet, int length, int ipHeaderLen, String connKey,
                           byte[] srcIp, byte[] dstIp, int srcPort, int dstPort) {
        try {
            long clientSeq = ((long)(packet[ipHeaderLen + 4] & 0xff) << 24) |
                             ((long)(packet[ipHeaderLen + 5] & 0xff) << 16) |
                             ((long)(packet[ipHeaderLen + 6] & 0xff) << 8) |
                             ((long)(packet[ipHeaderLen + 7] & 0xff));

            TcpConnection conn = new TcpConnection();
            conn.clientSeq = clientSeq;
            conn.serverSeq = random.nextLong() & 0xffffffffL;
            conn.clientAck = clientSeq + 1;
            conn.serverAck = conn.serverSeq;
            conn.established = false;
            connections.put(connKey, conn);

            Log.d(TAG, "handleSyn: " + connKey + " clientSeq=" + clientSeq + " ourSeq=" + conn.serverSeq);

            int synAckLen = ipHeaderLen + 20;
            byte[] out = new byte[synAckLen];

            System.arraycopy(packet, 0, out, 0, ipHeaderLen);
            out[2] = (byte)(synAckLen >> 8);
            out[3] = (byte)(synAckLen);
            System.arraycopy(dstIp, 0, out, 12, 4);
            System.arraycopy(srcIp, 0, out, 16, 4);
            out[10] = 0;
            out[11] = 0;

            out[ipHeaderLen] = (byte)(dstPort >> 8);
            out[ipHeaderLen + 1] = (byte)(dstPort);
            out[ipHeaderLen + 2] = (byte)(srcPort >> 8);
            out[ipHeaderLen + 3] = (byte)(srcPort);
            out[ipHeaderLen + 4] = (byte)(conn.serverSeq >> 24);
            out[ipHeaderLen + 5] = (byte)(conn.serverSeq >> 16);
            out[ipHeaderLen + 6] = (byte)(conn.serverSeq >> 8);
            out[ipHeaderLen + 7] = (byte)(conn.serverSeq);
            out[ipHeaderLen + 8] = (byte)(conn.clientAck >> 24);
            out[ipHeaderLen + 9] = (byte)(conn.clientAck >> 16);
            out[ipHeaderLen + 10] = (byte)(conn.clientAck >> 8);
            out[ipHeaderLen + 11] = (byte)(conn.clientAck);
            out[ipHeaderLen + 12] = (byte)0x50;
            out[ipHeaderLen + 13] = (byte)0x12;
            out[ipHeaderLen + 14] = (byte)0xff;
            out[ipHeaderLen + 15] = (byte)0xff;
            out[ipHeaderLen + 16] = 0;
            out[ipHeaderLen + 17] = 0;
            out[ipHeaderLen + 18] = 0;
            out[ipHeaderLen + 19] = 0;

            int ipChk = calculateChecksum(out, 0, ipHeaderLen);
            out[10] = (byte)(ipChk >> 8);
            out[11] = (byte)(ipChk);
            int tcpChk = calculateTcpChecksum(out, ipHeaderLen, 20);
            out[ipHeaderLen + 16] = (byte)(tcpChk >> 8);
            out[ipHeaderLen + 17] = (byte)(tcpChk);

            synchronized (this) {
                if (tunOut != null) {
                    tunOut.write(out);
                    tunOut.flush();
                    Log.d(TAG, "handleSyn: Sent SYN-ACK to TUN for " + connKey);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "handleSyn: Error", e);
        }
    }

    private void writeTunResponse(byte[] origPacket, int origLen, byte[] responseData, int responseLen,
                                  int ipHeaderLen, int tcpHeaderLen, int payloadOffset,
                                  byte[] srcIp, byte[] dstIp, int srcPort, int dstPort) {
        try {
            int newTcpLen = tcpHeaderLen + responseLen;
            int newTotalLen = ipHeaderLen + newTcpLen;

            byte[] outPacket = new byte[newTotalLen];

            System.arraycopy(origPacket, 0, outPacket, 0, ipHeaderLen);

            outPacket[2] = (byte) (newTotalLen >> 8);
            outPacket[3] = (byte) (newTotalLen);

            System.arraycopy(dstIp, 0, outPacket, 12, 4);
            System.arraycopy(srcIp, 0, outPacket, 16, 4);

            outPacket[10] = 0;
            outPacket[11] = 0;

            System.arraycopy(origPacket, ipHeaderLen, outPacket, ipHeaderLen, tcpHeaderLen);

            outPacket[ipHeaderLen] = (byte) (dstPort >> 8);
            outPacket[ipHeaderLen + 1] = (byte) (dstPort);
            outPacket[ipHeaderLen + 2] = (byte) (srcPort >> 8);
            outPacket[ipHeaderLen + 3] = (byte) (srcPort);

            outPacket[ipHeaderLen + 13] = (byte) 0x18;

            long origSeq = ((long) (origPacket[ipHeaderLen + 4] & 0xff) << 24) |
                           ((long) (origPacket[ipHeaderLen + 5] & 0xff) << 16) |
                           ((long) (origPacket[ipHeaderLen + 6] & 0xff) << 8) |
                           ((long) (origPacket[ipHeaderLen + 7] & 0xff));
            long origAck = ((long) (origPacket[ipHeaderLen + 8] & 0xff) << 24) |
                           ((long) (origPacket[ipHeaderLen + 9] & 0xff) << 16) |
                           ((long) (origPacket[ipHeaderLen + 10] & 0xff) << 8) |
                           ((long) (origPacket[ipHeaderLen + 11] & 0xff));

            int origPayloadLen = origLen - payloadOffset;

            long respSeq = origAck;
            long respAck = origSeq + origPayloadLen;

            outPacket[ipHeaderLen + 4] = (byte) (respSeq >> 24);
            outPacket[ipHeaderLen + 5] = (byte) (respSeq >> 16);
            outPacket[ipHeaderLen + 6] = (byte) (respSeq >> 8);
            outPacket[ipHeaderLen + 7] = (byte) (respSeq);

            outPacket[ipHeaderLen + 8] = (byte) (respAck >> 24);
            outPacket[ipHeaderLen + 9] = (byte) (respAck >> 16);
            outPacket[ipHeaderLen + 10] = (byte) (respAck >> 8);
            outPacket[ipHeaderLen + 11] = (byte) (respAck);

            outPacket[ipHeaderLen + 16] = 0;
            outPacket[ipHeaderLen + 17] = 0;

            System.arraycopy(responseData, 0, outPacket, ipHeaderLen + tcpHeaderLen, responseLen);

            int ipChecksum = calculateChecksum(outPacket, 0, ipHeaderLen);
            outPacket[10] = (byte) (ipChecksum >> 8);
            outPacket[11] = (byte) (ipChecksum);

            int tcpChecksum = calculateTcpChecksum(outPacket, ipHeaderLen, newTcpLen);
            outPacket[ipHeaderLen + 16] = (byte) (tcpChecksum >> 8);
            outPacket[ipHeaderLen + 17] = (byte) (tcpChecksum);

            synchronized (this) {
                if (tunOut != null) {
                    tunOut.write(outPacket);
                    tunOut.flush();
                    Log.d(TAG, "writeTunResponse: Wrote " + newTotalLen + " bytes to TUN");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "writeTunResponse: Error", e);
        }
    }

    private int calculateChecksum(byte[] buf, int offset, int length) {
        int sum = 0;
        int i = offset;
        int end = offset + length;
        while (i < end - 1) {
            sum += ((buf[i] & 0xff) << 8) | (buf[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) {
            sum += (buf[i] & 0xff) << 8;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return ~sum & 0xffff;
    }

    private int calculateTcpChecksum(byte[] buf, int tcpOffset, int tcpLength) {
        int sum = 0;

        sum += ((buf[12] & 0xff) << 8) | (buf[13] & 0xff);
        sum += ((buf[14] & 0xff) << 8) | (buf[15] & 0xff);
        sum += ((buf[16] & 0xff) << 8) | (buf[17] & 0xff);
        sum += ((buf[18] & 0xff) << 8) | (buf[19] & 0xff);

        sum += 0x0006;
        sum += tcpLength;

        int end = tcpOffset + tcpLength;
        int i = tcpOffset;
        while (i < end - 1) {
            sum += ((buf[i] & 0xff) << 8) | (buf[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) {
            sum += (buf[i] & 0xff) << 8;
        }

        while ((sum >> 16) > 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return ~sum & 0xffff;
    }

    private String getIpString(byte[] ip) {
        return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
    }

    private void tunWriter() {
    }

    private void forwardDnsQuery(byte[] queryData, int queryLen, byte[] srcIp, byte[] dstIp,
                                  int srcPort, int dstPort, byte[] origPacket, int origLen, int ipHeaderLen) {
        try {
            InetAddress dnsServer = InetAddress.getByAddress(dstIp);
            Log.d(TAG, "forwardDnsQuery: Forwarding " + queryLen + " byte DNS query to " +
                    getIpString(dstIp) + ":" + dstPort);

            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(5000);
            DatagramPacket query = new DatagramPacket(queryData, queryLen, dnsServer, dstPort);
            socket.send(query);

            byte[] responseData = new byte[1500];
            DatagramPacket response = new DatagramPacket(responseData, responseData.length);
            socket.receive(response);
            socket.close();

            Log.d(TAG, "forwardDnsQuery: Received " + response.getLength() + " byte DNS response");
            writeUdpTunResponse(origPacket, origLen, response.getData(), response.getLength(),
                    ipHeaderLen, srcIp, dstIp, srcPort, dstPort);
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "forwardDnsQuery: DNS query timed out");
        } catch (Exception e) {
            Log.w(TAG, "forwardDnsQuery: Error", e);
        }
    }

    private void writeUdpTunResponse(byte[] origPacket, int origLen, byte[] responseData, int responseLen,
                                     int ipHeaderLen, byte[] srcIp, byte[] dstIp, int srcPort, int dstPort) {
        try {
            int udpHeaderLen = 8;
            int newTotalLen = ipHeaderLen + udpHeaderLen + responseLen;
            byte[] outPacket = new byte[newTotalLen];

            System.arraycopy(origPacket, 0, outPacket, 0, ipHeaderLen);

            outPacket[2] = (byte) (newTotalLen >> 8);
            outPacket[3] = (byte) (newTotalLen);

            System.arraycopy(dstIp, 0, outPacket, 12, 4);
            System.arraycopy(srcIp, 0, outPacket, 16, 4);

            outPacket[10] = 0;
            outPacket[11] = 0;

            outPacket[ipHeaderLen] = (byte) (dstPort >> 8);
            outPacket[ipHeaderLen + 1] = (byte) (dstPort);
            outPacket[ipHeaderLen + 2] = (byte) (srcPort >> 8);
            outPacket[ipHeaderLen + 3] = (byte) (srcPort);

            int udpLen = udpHeaderLen + responseLen;
            outPacket[ipHeaderLen + 4] = (byte) (udpLen >> 8);
            outPacket[ipHeaderLen + 5] = (byte) (udpLen);

            outPacket[ipHeaderLen + 6] = 0;
            outPacket[ipHeaderLen + 7] = 0;

            System.arraycopy(responseData, 0, outPacket, ipHeaderLen + udpHeaderLen, responseLen);

            int ipChecksum = calculateChecksum(outPacket, 0, ipHeaderLen);
            outPacket[10] = (byte) (ipChecksum >> 8);
            outPacket[11] = (byte) (ipChecksum);

            synchronized (this) {
                if (tunOut != null) {
                    tunOut.write(outPacket);
                    tunOut.flush();
                    Log.d(TAG, "writeUdpTunResponse: Wrote " + newTotalLen + " bytes to TUN");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "writeUdpTunResponse: Error", e);
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
            if (tunOut != null) {
                tunOut.close();
                Log.d(TAG, "onDestroy: TUN output stream closed");
            }
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
