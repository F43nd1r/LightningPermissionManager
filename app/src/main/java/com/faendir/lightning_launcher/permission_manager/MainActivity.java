package com.faendir.lightning_launcher.permission_manager;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


public class MainActivity extends AppCompatActivity {

    private ArrayAdapter<String> adapter;
    private List<String> list;
    private AutoCompleteTextView textView;
    private SharedPreferences preferences;

    @SuppressLint("WorldReadableFiles")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(!isHooked()){
            final PackageManager pm = getPackageManager();
            boolean installed = false;
            try {
                pm.getPackageInfo(getString(R.string.xposed),PackageManager.GET_ACTIVITIES);
                installed = true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            final boolean finalInstalled = installed;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.title_error)
                    .setMessage(installed?R.string.msg_notActive:R.string.msg_notInstalled)
                    .setNegativeButton(R.string.btn_close, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setPositiveButton(installed ? R.string.btn_xposed : R.string.btn_downloadXposed, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent i;
                            if(finalInstalled)i=pm.getLaunchIntentForPackage(getString(R.string.xposed));
                            else i=new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(getString(R.string.link_xposed)));
                            startActivity(i);
                            finish();
                        }
                    })
                    .show();
        }
        else {
            //noinspection deprecation
            preferences = getSharedPreferences(Strings.PREF_NAME, MODE_WORLD_READABLE);
            setContentView(R.layout.activity_main);
            textView = (AutoCompleteTextView) findViewById(R.id.autoCompleteTextView);
            textView.setDropDownAnchor(R.id.linearLayout);
            textView.setThreshold(0);
            ArrayList<String> permissions = new ArrayList<>();
            PackageManager pm = getPackageManager();

            List<PermissionGroupInfo> lstGroups = pm.getAllPermissionGroups(0);
            for (PermissionGroupInfo pgi : lstGroups) {
                List<PermissionInfo> lstPermissions;
                try {
                    lstPermissions = pm.queryPermissionsByGroup(pgi.name, 0);
                    for (PermissionInfo pi : lstPermissions) {
                        permissions.add(pi.name);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
            }
            list = Strings.read(preferences);
            DropdownAdapter permAdapter = new DropdownAdapter(this, permissions, list);
            textView.setAdapter(permAdapter);
            adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, list);
            updateAdapter();
            ListView listView = (ListView) findViewById(R.id.listView);
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    delete(adapter.getItem(position));
                }
            });
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_save) {
            save();
            Intent applyIntent = new Intent(Strings.PKG + ".UPDATE_PERMISSIONS");
            applyIntent.putExtra("action", Strings.ACTION_PERMISSIONS);
            applyIntent.putExtra("Kill", true);
            sendBroadcast(applyIntent, null);
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void delete(final String item){
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.text_remove)+" \""+item+"\" ?")
                .setNegativeButton(R.string.text_no,null)
                .setPositiveButton(R.string.text_yes,new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        list.remove(item);
                        updateAdapter();
                        save();
                        Toast.makeText(MainActivity.this, R.string.toast_remove, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    @SuppressLint("unused")
    public void onAdd(View v){
        if(list == null) return;
        list.add(textView.getText().toString());
        updateAdapter();
        save();
        textView.setText("");
    }

    private void save(){
        Strings.write(list, preferences);

    }

    private void updateAdapter(){
        adapter.sort(new Comparator<String>() {
            @Override
            public int compare(String lhs, String rhs) {
                return lhs.compareTo(rhs);
            }
        });
        adapter.notifyDataSetChanged();
    }

    @SuppressWarnings("SameReturnValue")
    private static Boolean isHooked(){
        return false;
    }
}
