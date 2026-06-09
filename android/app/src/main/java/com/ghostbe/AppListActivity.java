package com.ghostbe;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppListActivity extends AppCompatActivity {
    private ListView appListView;
    private EditText searchField;
    private AppAdapter adapter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        prefs = getSharedPreferences("ghostbe", MODE_PRIVATE);
        appListView = findViewById(R.id.app_list);
        searchField = findViewById(R.id.search_apps);

        loadApps();

        // Setup search
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        // Load saved selections
        Set<String> saved = loadSavedApps();

        List<AppItem> appItems = new ArrayList<>();
        for (ApplicationInfo info : apps) {
            // Filter out system apps
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }

            AppItem item = new AppItem();
            item.packageName = info.packageName;
            item.name = pm.getApplicationLabel(info).toString();
            item.isSelected = saved.contains(info.packageName);

            appItems.add(item);
        }

        // Sort by app name
        Collections.sort(appItems, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        adapter = new AppAdapter(this, appItems, (selected) -> saveSelectedApps(selected));
        appListView.setAdapter(adapter);
    }

    private Set<String> loadSavedApps() {
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

    private void saveSelectedApps(List<String> selected) {
        JSONArray arr = new JSONArray(selected);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("selected_apps", arr.toString());
        editor.apply();
    }

    public static class AppItem {
        public String packageName;
        public String name;
        public boolean isSelected;
    }
}
