package com.ghostbe;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class ConfigActivity extends AppCompatActivity {
    private EditText hostInput;
    private EditText portInput;
    private Button saveBtn;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        prefs = getSharedPreferences("ghostbe", MODE_PRIVATE);

        hostInput = findViewById(R.id.host_input);
        portInput = findViewById(R.id.port_input);
        saveBtn = findViewById(R.id.save_btn);

        // Load saved values
        String savedHost = prefs.getString("host", "");
        int savedPort = prefs.getInt("port", 8877);

        hostInput.setText(savedHost);
        portInput.setText(String.valueOf(savedPort));

        saveBtn.setOnClickListener(v -> handleSave());
    }

    private void handleSave() {
        String host = hostInput.getText().toString().trim();
        String portStr = portInput.getText().toString().trim();

        if (host.isEmpty() || portStr.isEmpty()) {
            return;
        }

        int port = Integer.parseInt(portStr);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("host", host);
        editor.putInt("port", port);
        editor.apply();

        finish();
    }
}
