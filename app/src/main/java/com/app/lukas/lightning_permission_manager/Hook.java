package com.app.lukas.lightning_permission_manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

/**
 * Created by Lukas on 25.03.2015.
 * Xposed Hook
 */
@SuppressWarnings("unchecked")
class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {


    private XSharedPreferences pref;
    private String installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.packageName.equals("android")) return;

        final Class<?> clsPMS = findClass("com.android.server.pm.PackageManagerService", loadPackageParam.classLoader);

        findAndHookMethod(clsPMS, "systemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param)
                    throws Throwable {
                Context context = (Context) getObjectField(param.thisObject, "mContext");
                context.registerReceiver(new PermissionUpdateReceiver(param.thisObject),
                        new IntentFilter(Strings.PKG + ".UPDATE_PERMISSIONS"));
            }
        });

        XC_MethodHook hookGrantPermissions = new XC_MethodHook() {

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                String pkgName = (String) getObjectField(param.args[0], "packageName");
                if (!(pkgName.equals(Strings.LLX) || pkgName.equals(Strings.LL))) return;
                installed = pkgName;
                Object extras = getObjectField(param.args[0], "mExtras");
                Object sharedUser = getObjectField(extras, "sharedUser");
                Set<String> grantedPerms;
                if (sharedUser == null)
                    grantedPerms = (Set<String>) getObjectField(extras, "grantedPermissions");
                else
                    grantedPerms = (Set<String>) getObjectField(sharedUser, "grantedPermissions");
                Object settings = getObjectField(param.thisObject, "mSettings");
                Object permissions = getObjectField(settings, "mPermissions");

                ArrayList<String> newPerms = Strings.read(pref);

                for (String perm : newPerms) {
                    Object permission = callMethod(permissions, "get", perm);
                    if (permission == null) continue;
                    grantedPerms.add(perm);
                    int[] gpGids = (int[]) getObjectField(sharedUser!=null?sharedUser:extras, "gids");
                    int[] bpGids = (int[]) getObjectField(permission, "gids");
                    callStaticMethod(param.thisObject.getClass(),
                            "appendInts", gpGids, bpGids);
                    if (BuildConfig.DEBUG) XposedBridge.log("Permission added: " + permission);
                }
            }
        };

        if (Build.VERSION.SDK_INT < 21) {
            findAndHookMethod(clsPMS, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, hookGrantPermissions);
        } else {
            findAndHookMethod(clsPMS, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, String.class, hookGrantPermissions);
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        pref = new XSharedPreferences(this.getClass().getPackage().getName(), Strings.PREF_NAME);
        pref.makeWorldReadable();
    }

    public class PermissionUpdateReceiver extends BroadcastReceiver{

        private final Object pmSvc;
        private final Map<String, Object> mPackages;
        private final Object mSettings;
        public PermissionUpdateReceiver(Object pmSvc) {
            this.pmSvc = pmSvc;
            this.mPackages = (Map<String, Object>) getObjectField(pmSvc, "mPackages");
            this.mSettings = getObjectField(pmSvc, "mSettings");
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (!Strings.ACTION_PERMISSIONS.equals(intent.getExtras().getString("action")))
                    return;
                boolean killApp = intent.getExtras().getBoolean("Kill", false);
                pref.reload();
                Object pkgInfo;
                synchronized (mPackages) {
                    pkgInfo = mPackages.get(installed);
                    if (Build.VERSION.SDK_INT < 21) {
                        callMethod(pmSvc, "grantPermissionsLPw", pkgInfo, true);
                    } else {
                        callMethod(pmSvc, "grantPermissionsLPw", pkgInfo, true, installed);
                    }
                    callMethod(mSettings, "writeLPr");
                }
                if (killApp) {
                    try {
                        ApplicationInfo appInfo = (ApplicationInfo) getObjectField(pkgInfo, "applicationInfo");
                        if (Build.VERSION.SDK_INT <= 18)
                            callMethod(pmSvc, "killApplication", installed, appInfo.uid);
                        else
                            callMethod(pmSvc, "killApplication", installed, appInfo.uid, "apply App Settings");
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }
}
