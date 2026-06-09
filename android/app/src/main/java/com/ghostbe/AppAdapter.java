package com.ghostbe;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class AppAdapter extends ArrayAdapter<AppListActivity.AppItem> {
    interface OnSelectionChange {
        void onChanged(List<String> selected);
    }

    private OnSelectionChange listener;
    private List<AppListActivity.AppItem> items;
    private List<AppListActivity.AppItem> filteredItems;
    private PackageManager packageManager;

    public AppAdapter(Context context, List<AppListActivity.AppItem> items, OnSelectionChange listener) {
        super(context, 0, items);
        this.items = items;
        this.filteredItems = new ArrayList<>(items);
        this.listener = listener;
        this.packageManager = context.getPackageManager();
    }

    @Override
    public int getCount() {
        return filteredItems.size();
    }

    @Override
    public AppListActivity.AppItem getItem(int position) {
        return filteredItems.get(position);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(
                    R.layout.item_app,
                    parent,
                    false
            );
        }

        AppListActivity.AppItem item = getItem(position);
        if (item == null) return convertView;

        ImageView appIcon = convertView.findViewById(R.id.app_icon);
        TextView appNameText = convertView.findViewById(R.id.app_name);
        TextView packageText = convertView.findViewById(R.id.package_name);
        CheckBox checkBox = convertView.findViewById(R.id.app_checkbox);

        // Load app icon
        try {
            Drawable icon = packageManager.getApplicationIcon(item.packageName);
            appIcon.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            appIcon.setImageDrawable(null);
        }

        appNameText.setText(item.name);
        packageText.setText(item.packageName);
        checkBox.setChecked(item.isSelected);

        checkBox.setOnCheckedChangeListener((btn, isChecked) -> {
            item.isSelected = isChecked;
            notifySelectionChanged();
        });

        convertView.setOnClickListener(v -> {
            checkBox.setChecked(!checkBox.isChecked());
        });

        return convertView;
    }

    public void filter(String query) {
        filteredItems.clear();
        if (query == null || query.isEmpty()) {
            filteredItems.addAll(items);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AppListActivity.AppItem item : items) {
                if (item.name.toLowerCase().contains(lowerQuery) ||
                    item.packageName.toLowerCase().contains(lowerQuery)) {
                    filteredItems.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    private void notifySelectionChanged() {
        List<String> selected = new ArrayList<>();
        for (AppListActivity.AppItem item : items) {
            if (item.isSelected) {
                selected.add(item.packageName);
            }
        }
        if (listener != null) {
            listener.onChanged(selected);
        }
    }
}
