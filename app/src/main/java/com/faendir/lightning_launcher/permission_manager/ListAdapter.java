package com.faendir.lightning_launcher.permission_manager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * Created by Lukas on 23.01.2016.
 * Marks not granted permissions red.
 */
class ListAdapter extends ArrayAdapter<String> {
    private final Context context;

    public ListAdapter(Context context, List<String> objects) {
        super(context, android.R.layout.simple_list_item_1, objects);
        this.context = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView v = (TextView) super.getView(position, convertView, parent);
        if (!isGranted(position)) {
            v.setTextColor(Color.RED);
        }
        else {
            v.setTextColor(Color.BLACK);
        }
        return v;
    }

    private boolean isGranted(int position) {
        String perm = getItem(position);
        return context.getPackageManager().checkPermission(perm, Strings.LLX) == PackageManager.PERMISSION_GRANTED
                || context.getPackageManager().checkPermission(perm, Strings.LL) == PackageManager.PERMISSION_GRANTED;
    }
}
