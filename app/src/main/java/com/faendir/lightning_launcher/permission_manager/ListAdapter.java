package com.faendir.lightning_launcher.permission_manager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.LayoutInflater;
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
        super(context, 0, objects);
        this.context = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(getContext()).inflate(R.layout.list_item, parent, false);
        }
        TextView title = (TextView) v.findViewById(android.R.id.text1);
        TextView domain = (TextView) v.findViewById(android.R.id.text2);
        String perm = getItem(position);
        int index = perm.lastIndexOf('.');
        title.setText(perm.substring(index + 1));
        domain.setText(perm.substring(0, index));
        if (!isGranted(position)) {
            title.setTextColor(Color.RED);
        } else {
            title.setTextColor(Color.BLACK);
        }
        return v;
    }

    private boolean isGranted(int position) {
        String perm = getItem(position);
        return context.getPackageManager().checkPermission(perm, Strings.LLX) == PackageManager.PERMISSION_GRANTED
                || context.getPackageManager().checkPermission(perm, Strings.LL) == PackageManager.PERMISSION_GRANTED;
    }
}
