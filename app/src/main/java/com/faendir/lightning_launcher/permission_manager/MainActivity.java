package com.faendir.lightning_launcher.permission_manager;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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

import java.lang.reflect.Field;
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
        preferences = getSharedPreferences(Strings.PREF_NAME, MODE_WORLD_READABLE);
        if (isHooked()) {
            //noinspection deprecation
            setupLayout();
            onNewIntent(getIntent());
        } else {
            notHookedDialog();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (getString(R.string.action_request).equals(intent.getAction()) && intent.hasExtra(getString(R.string.extra_permission))) {
            String permission = intent.getStringExtra(getString(R.string.extra_permission));
            add(permission);
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

        switch (id) {
            case R.id.action_save:
                if(list != null) {
                    saveAndApply();
                }
                return true;
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void delete(final String item) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.text_remove) + " \"" + item + "\" ?")
                .setNegativeButton(R.string.text_no, null)
                .setPositiveButton(R.string.text_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        list.remove(item);
                        updateAdapter();
                        save();
                    }
                })
                .show();
    }

    public void onAdd(View v) {
        if (list == null) return;
        add(textView.getText().toString());
    }

    private void add(String perm) {
        list.add(perm);
        updateAdapter();
        save();
        textView.setText("");
    }

    private void save() {
        Strings.write(list, preferences);
    }

    private void saveAndApply() {
        save();
        Intent applyIntent = new Intent(Strings.INTENT_UPDATE);
        applyIntent.putExtra(Strings.KEY_ACTION, Strings.ACTION_PERMISSIONS);
        sendBroadcast(applyIntent, null);
        updateAdapter();
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
    }

    private void updateAdapter() {
        adapter.sort(new Comparator<String>() {
            @Override
            public int compare(String lhs, String rhs) {
                return lhs.compareTo(rhs);
            }
        });
        adapter.notifyDataSetChanged();
    }

    @SuppressWarnings("SameReturnValue")
    private static Boolean isHooked() {
        return false;
    }

    private void notHookedDialog() {
        final PackageManager pm = getPackageManager();
        boolean installed = false;
        try {
            pm.getPackageInfo(getString(R.string.xposed), PackageManager.GET_ACTIVITIES);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        final boolean finalInstalled = installed;
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_error)
                .setMessage(installed ? R.string.msg_notActive : R.string.msg_notInstalled)
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
                        if (finalInstalled)
                            i = pm.getLaunchIntentForPackage(getString(R.string.xposed));
                        else {
                            i = new Intent(Intent.ACTION_VIEW);
                            i.setData(Uri.parse(getString(R.string.link_xposed)));
                        }
                        startActivity(i);
                        finish();
                    }
                })
                .show();
    }

    private void setupLayout() {
        setContentView(R.layout.activity_main);
        textView = (AutoCompleteTextView) findViewById(R.id.autoCompleteTextView);
        assert textView != null;
        textView.setDropDownAnchor(R.id.linearLayout);
        textView.setThreshold(0);
        ArrayList<String> permissions = new ArrayList<>();
        Field[] perms = android.Manifest.permission.class.getDeclaredFields();
        for (Field f : perms) {
            try {
                String permission = (String) f.get(null);
                permissions.add(permission);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        PackageManager packageManager = getPackageManager();
        for (PackageInfo info : packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            if (info.permissions != null) {
                for (PermissionInfo permissionInfo : info.permissions) {
                    if (!permissions.contains(permissionInfo.name)) {
                        permissions.add(permissionInfo.name);
                    }
                }
            }
        }
        list = Strings.read(preferences);
        DropdownAdapter permAdapter = new DropdownAdapter(this, permissions, list);
        textView.setAdapter(permAdapter);
        adapter = new ListAdapter(this, list);
        updateAdapter();
        ListView listView = (ListView) findViewById(R.id.listView);
        assert listView != null;
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                delete(adapter.getItem(position));
            }
        });
    }
}
