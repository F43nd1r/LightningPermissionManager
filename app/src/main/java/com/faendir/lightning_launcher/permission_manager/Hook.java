package com.faendir.lightning_launcher.permission_manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
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
@SuppressWarnings({"unchecked", "WeakerAccess"})
public class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {


    private XSharedPreferences pref;
    private String installed;
    private final Log log;

    @SuppressWarnings("WeakerAccess")
    public Hook() {
        log = new Log();
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals("android")) {
            log.append("Hooked Package android");

            final Class<?> clsPMS = findClass("com.android.server.pm.PackageManagerService", loadPackageParam.classLoader);

            findAndHookMethod(clsPMS, "systemReady", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param)
                        throws Throwable {
                    Context context = (Context) getObjectField(param.thisObject, "mContext");
                    context.registerReceiver(new PermissionUpdateReceiver(param.thisObject),
                            new IntentFilter(Strings.PKG + ".UPDATE_PERMISSIONS"));
                    log.append("Registered Receiver");
                }
            });
            log.append("Hooked systemReady");

            XC_MethodHook hookGrantPermissions = new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String pkgName = (String) getObjectField(param.args[0], "packageName");
                    if (!(pkgName.equals(Strings.LLX) || pkgName.equals(Strings.LL))) return;
                    installed = pkgName;
                    log.append("Granting Permissions to: " + installed);
                    Object extras = getObjectField(param.args[0], "mExtras");
                    Object sharedUser = getObjectField(extras, "sharedUser");
                    log.append("Shared User exists: " + (sharedUser != null));
                    Set<String> grantedPerms;
                    if (sharedUser == null)
                        grantedPerms = (Set<String>) getObjectField(extras, "grantedPermissions");
                    else
                        grantedPerms = (Set<String>) getObjectField(sharedUser, "grantedPermissions");
                    Object settings = getObjectField(param.thisObject, "mSettings");
                    Object permissions = getObjectField(settings, "mPermissions");

                    List<String> newPerms = Strings.read(pref);
                    log.append("Adding permissions: " + Arrays.toString(newPerms.toArray()));

                    for (String perm : newPerms) {
                        Object permission = callMethod(permissions, "get", perm);
                        log.append("Permission " + perm + ": " + permission);
                        if (permission == null) continue;
                        grantedPerms.add(perm);
                        int[] gpGids = (int[]) getObjectField(sharedUser != null ? sharedUser : extras, "gids");
                        int[] bpGids = (int[]) getObjectField(permission, "gids");
                        callStaticMethod(param.thisObject.getClass(),
                                "appendInts", gpGids, bpGids);
                        log.append("Permission added: " + permission);
                    }
                }
            };

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                findAndHookMethod(clsPMS, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, hookGrantPermissions);
            } else {
                findAndHookMethod(clsPMS, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, String.class, hookGrantPermissions);
            }
            log.append("Hooked grantPermissionsLPw");
        }
        else if(loadPackageParam.packageName.equals(Hook.class.getPackage().getName())){
            log.append("hooked self");
            /*findAndHookMethod(MainActivity.class, "isHooked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(true);
                }
            });*/
            findAndHookMethod("com.faendir.lightning_launcher.permission_manager.MainActivity",loadPackageParam.classLoader,  "isHooked",XC_MethodReplacement.returnConstant(Boolean.TRUE));
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        log.append("initZygote");
        pref = new XSharedPreferences(this.getClass().getPackage().getName(), Strings.PREF_NAME);
        pref.makeWorldReadable();
        log.append("prefs now WorldReadable");
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
            log.append("Received Broadcast");
            try {
                if (!Strings.ACTION_PERMISSIONS.equals(intent.getExtras().getString("action")))
                    return;
                boolean killApp = intent.getExtras().getBoolean("Kill", false);
                pref.reload();
                Object pkgInfo;
                synchronized (mPackages) {
                    pkgInfo = mPackages.get(installed);
                    log.append("Calling grantPermissionsLPw for: "+installed+" ("+pkgInfo+")");
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        callMethod(pmSvc, "grantPermissionsLPw", pkgInfo, true);
                    } else {
                        callMethod(pmSvc, "grantPermissionsLPw", pkgInfo, true, installed);
                    }
                    log.append("Calling writeLPr");
                    callMethod(mSettings, "writeLPr");
                }
                if (killApp) {
                    try {
                        log.append("Killing app");
                        ApplicationInfo appInfo = (ApplicationInfo) getObjectField(pkgInfo, "applicationInfo");
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2)
                            callMethod(pmSvc, "killApplication", installed, appInfo.uid);
                        else
                            callMethod(pmSvc, "killApplication", installed, appInfo.uid, "apply App Settings");
                    } catch (Throwable t) {
                        log.append("killing failed: "+Arrays.toString(t.getStackTrace()));
                    }
                }
            } catch (Throwable t) {
                log.append("BroadcastReceiver failed: "+Arrays.toString(t.getStackTrace()));
            }
        }
    }

    private class Log {

        public void append(String s){
            if(BuildConfig.DEBUG) {
                XposedBridge.log("LLPermission: " + s);
            }
        }
    }
}
