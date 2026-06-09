package com.ghostbe;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class AppAdapter extends ArrayAdapter<AppListActivity.AppItem> {
    interface OnSelectionChange {
        void onChanged(List<String> selected);
    }

    private OnSelectionChange listener;
    private List<AppListActivity.AppItem> items;

    public AppAdapter(Context context, List<AppListActivity.AppItem> items, OnSelectionChange listener) {
        super(context, 0, items);
        this.items = items;
        this.listener = listener;
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

        TextView appNameText = convertView.findViewById(R.id.app_name);
        TextView packageText = convertView.findViewById(R.id.package_name);
        CheckBox checkBox = convertView.findViewById(R.id.app_checkbox);

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
