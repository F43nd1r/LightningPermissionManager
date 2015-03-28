package com.app.lukas.lightning_permission_manager;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.Filterable;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Lukas on 27.03.2015.
 * Manages items in a dropdown menu
 */
class DropdownAdapter extends ArrayAdapter<String> implements Filterable{

    private ArrayList<String> fullList;
    private ArrayList<String> originalValues;
    private final List<String> hide;
    private ArrayFilter filter;

    public DropdownAdapter(Context context, List<String> objects, List<String> hide) {
        super(context, android.R.layout.simple_list_item_1, objects);
        fullList = (ArrayList<String>) objects;
        originalValues = new ArrayList<>(fullList);
        this.hide = hide;
    }

    @Override
    public int getCount() {
        return fullList.size();
    }

    @Override
    public String getItem(int position) {
        return fullList.get(position);
    }

    @Override
    public Filter getFilter() {
        if (filter == null) {
            filter = new ArrayFilter();
        }
        return filter;
    }

    private class ArrayFilter extends Filter{

        @Override
        protected FilterResults performFiltering(CharSequence prefix) {
            FilterResults results = new FilterResults();

            if (originalValues == null) {
                    originalValues = new ArrayList<>(fullList);
            }

            if (prefix == null || prefix.length() == 0) {
                    ArrayList<String> list = new ArrayList<>(originalValues);
                    results.values = list;
                    results.count = list.size();
            } else {
                String prefixString = prefix.toString().toLowerCase();
                int count = originalValues.size();
                ArrayList<String> newValues = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    String item = originalValues.get(i);
                    if (item.toLowerCase().contains(prefixString) && !hide.contains(item)) {
                        newValues.add(item);
                    }

                }

                results.values = newValues;
                results.count = newValues.size();
            }

            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {

            if(results.values!=null){
                //noinspection unchecked
                fullList = (ArrayList<String>) results.values;
            }else{
                fullList = new ArrayList<>();
            }
            if (results.count > 0) {
                notifyDataSetChanged();
            } else {
                notifyDataSetInvalidated();
            }
        }
    }
}
