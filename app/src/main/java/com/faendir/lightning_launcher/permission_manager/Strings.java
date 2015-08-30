package com.faendir.lightning_launcher.permission_manager;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Lukas on 25.03.2015.
 * Common Strings and static Methods
 */
class Strings {
    private Strings(){}

    static final String PKG = Strings.class.getPackage().getName();
    static final String LLX = "net.pierrox.lightning_launcher_extreme";
    static final String LL = "net.pierrox.lightning_launcher";
    static final String PREF_NAME = "Settings";
    private static final String KEY = "Permissions";
    static final String ACTION_PERMISSIONS = "update";


    public static void write(List<String> list,SharedPreferences preferences) {
        preferences.edit().putString(KEY,new JSONArray(list).toString()).apply();
    }

    public static List<String> read(SharedPreferences preferences){
        ArrayList<String> list = new ArrayList<>();
        String s = preferences.getString(KEY,"");
        if (s.equals("") || s.equals("[]")) return list;
        try {
            JSONArray array = new JSONArray(s);
            for (int i = 0; i < array.length(); i++){
                list.add(array.get(i).toString());
            }
            return list;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }
}
