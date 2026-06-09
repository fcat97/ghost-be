package com.ghostbe;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private EditText hostInput;
    private EditText portInput;
    private Button connectBtn;
    private Button disconnectBtn;
    private TextView statusText;
    private TextView selectAppsLink;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ghostbe", MODE_PRIVATE);

        hostInput = findViewById(R.id.host_input);
        portInput = findViewById(R.id.port_input);
        connectBtn = findViewById(R.id.connect_btn);
        disconnectBtn = findViewById(R.id.disconnect_btn);
        statusText = findViewById(R.id.status_text);
        selectAppsLink = findViewById(R.id.select_apps_link);

        // Load saved values
        String savedHost = prefs.getString("host", "");
        int savedPort = prefs.getInt("port", 8877);

        hostInput.setText(savedHost);
        portInput.setText(String.valueOf(savedPort));

        connectBtn.setOnClickListener(v -> handleConnect());
        disconnectBtn.setOnClickListener(v -> handleDisconnect());
        selectAppsLink.setOnClickListener(v -> openAppList());

        updateStatus();
    }

    private void handleConnect() {
        String host = hostInput.getText().toString().trim();
        String portStr = portInput.getText().toString().trim();

        if (host.isEmpty() || portStr.isEmpty()) {
            statusText.setText("Please enter host and port");
            return;
        }

        int port = Integer.parseInt(portStr);

        // Save preferences
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("host", host);
        editor.putInt("port", port);
        editor.apply();

        // Start VPN service
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, 0);
        } else {
            startVPN();
        }
    }

    private void handleDisconnect() {
        Intent serviceIntent = new Intent(this, GhostVpnService.class);
        stopService(serviceIntent);
        updateStatus();
    }

    private void startVPN() {
        Intent serviceIntent = new Intent(this, GhostVpnService.class);
        startService(serviceIntent);
        updateStatus();
    }

    private void openAppList() {
        Intent intent = new Intent(this, AppListActivity.class);
        startActivity(intent);
    }

    private void updateStatus() {
        boolean isRunning = GhostVpnService.isRunning();
        statusText.setText(isRunning ? "Connected" : "Disconnected");
        connectBtn.setEnabled(!isRunning);
        disconnectBtn.setEnabled(isRunning);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            startVPN();
        }
    }
}
