package com.app.lukas.lightning_permission_manager;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;

/**
 * Created by Lukas on 25.03.2015.
 */
public class Strings {

    static final String PKG = Strings.class.getPackage().getName();
    static final String LLX = "net.pierrox.lightning_launcher_extreme";
    static final String LL = "net.pierrox.lightning_launcher";
    static final String PREF_NAME = "Settings";
    static final String KEY = "Permissions";
    static final String ACTION_PERMISSIONS = "update";


    static public void write(ArrayList<String> list,SharedPreferences preferences) {
        preferences.edit().putString(KEY,new JSONArray(list).toString()).apply();
    }

    static public ArrayList<String> read(SharedPreferences preferences){
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
