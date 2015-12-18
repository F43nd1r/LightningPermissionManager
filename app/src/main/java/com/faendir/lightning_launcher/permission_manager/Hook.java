package com.faendir.lightning_launcher.permission_manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PermissionInfo;
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
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedBridge.log;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getIntField;
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
    public void initZygote(StartupParam startupParam) throws Throwable {
        log.append("initZygote");
        pref = new XSharedPreferences(this.getClass().getPackage().getName(), Strings.PREF_NAME);
        pref.makeWorldReadable();
        log.append("prefs now WorldReadable");
        if (pref.contains(Strings.KEY_LOG)) {
            log.setDoOutput(pref.getBoolean(Strings.KEY_LOG, log.doesOutput()));
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals("android")) {
            log.append("Hooked Package android");
            Class<?> packageManagerService = findClass("com.android.server.pm.PackageManagerService", loadPackageParam.classLoader);
            Class userManagerService = findClass("com.android.server.pm.UserManagerService", loadPackageParam.classLoader);
            setupReceiver(packageManagerService);
            setupGranter(packageManagerService, userManagerService);
            setupExceptionPreventive(packageManagerService);
        } else if (loadPackageParam.packageName.equals(Strings.PKG)) {
            setupSelfHook(findClass(MainActivity.class.getName(),loadPackageParam.classLoader));
        }
    }

    private void setupReceiver(Class packageManagerService) {
        findAndHookMethod(packageManagerService, "systemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param)
                    throws Throwable {
                Context context = (Context) getObjectField(param.thisObject, "mContext");
                context.registerReceiver(new PermissionUpdateReceiver(param.thisObject),
                        new IntentFilter(Strings.INTENT_UPDATE));
                log.append("Registered Receiver");
            }
        });
        log.append("Hooked systemReady");
    }

    private void setupGranter(Class packageManagerService, final Class userManagerService) {
        XC_MethodHook hookGrantPermissions = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                String pkgName = (String) getObjectField(param.args[0], "packageName");
                if (!(pkgName.equals(Strings.LLX) || pkgName.equals(Strings.LL))) return;
                installed = pkgName;
                log.append("Granting Permissions to: " + installed);
                Object extras = getObjectField(param.args[0], "mExtras");
                Object settings = getObjectField(param.thisObject, "mSettings");
                Object permissions = getObjectField(settings, "mPermissions");
                List<String> newPerms = Strings.read(pref);
                log.append("Adding permissions: " + Arrays.toString(newPerms.toArray()));
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP) {
                    Object sharedUser = getObjectField(extras, "sharedUser");
                    log.append("Shared User exists: " + (sharedUser != null));
                    Set<String> grantedPerms;
                    if (sharedUser == null)
                        grantedPerms = (Set<String>) getObjectField(extras, "grantedPermissions");
                    else
                        grantedPerms = (Set<String>) getObjectField(sharedUser, "grantedPermissions");

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
                } else {
                    final Object permissionState = callMethod(extras, "getPermissionsState");
                    for (String perm : newPerms) {
                        Object permission = callMethod(permissions, "get", perm);
                        log.append("Permission " + perm + ": " + permission);
                        if (permission == null) continue;
                        int protectionLevel = getIntField(permission, "protectionLevel");
                        if (protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                            log.append("Dangerous permission");
                            Object userService = callStaticMethod(userManagerService, "getInstance");
                            int[] userIds = (int[]) callMethod(userService, "getUserIds");
                            for (int id : userIds) {
                                log.append("Granting for user " + id);
                                try {
                                    callMethod(param.thisObject, "grantRuntimePermission", installed, perm, id);
                                } catch (Throwable t) {
                                    log.append("Failed for user " + id);
                                }
                            }
                        } else {
                            log.append("try to treat as normal permission");
                            callMethod(permissionState, "grantInstallPermission", permission);
                        }
                        log.append("Permission added: " + permission);
                    }
                }
            }
        };
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            findAndHookMethod(packageManagerService, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, hookGrantPermissions);
        } else {
            findAndHookMethod(packageManagerService, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, String.class, hookGrantPermissions);
        }
        log.append("Hooked grantPermissionsLPw");
    }

    private void setupExceptionPreventive(Class packageManagerService) {
        findAndHookMethod(packageManagerService, "enforceDeclaredAsUsedAndRuntimeOrDevelopmentPermission",
                "android.content.pm.PackageParser$Package", "com.android.server.pm.BasePermission", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        List<String> newPerms = Strings.read(pref);
                        String name = (String) getObjectField(param.args[1], "name");
                        if (newPerms.contains(name)) {
                            log.append("Prevented SecurityException for " + name);
                            param.setResult(null);
                        }
                    }
                });
        log.append("Hooked enforceDeclaredAsUsedAndRuntimeOrDevelopmentPermission");
    }

    private void setupSelfHook(Class mainActivity) {
        findAndHookMethod(mainActivity, "isHooked", XC_MethodReplacement.returnConstant(Boolean.TRUE));
        log.append("hooked self");
    }

    public class PermissionUpdateReceiver extends BroadcastReceiver {

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
                if (!Strings.ACTION_PERMISSIONS.equals(intent.getExtras().getString(Strings.KEY_ACTION)))
                    return;
                boolean killApp = intent.getExtras().getBoolean(Strings.KEY_KILL, false);
                pref.reload();
                Object pkgInfo;
                synchronized (mPackages) {
                    pkgInfo = mPackages.get(installed);
                    log.append("Calling grantPermissionsLPw for: " + installed + " (" + pkgInfo + ")");
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
                        log.append("killing failed: " + Arrays.toString(t.getStackTrace()));
                    }
                }
            } catch (Throwable t) {
                log.append("BroadcastReceiver failed: " + Arrays.toString(t.getStackTrace()));
            }
        }
    }

    private static class Log {
        private boolean doOutput;

        public Log() {
            this(BuildConfig.DEBUG);
        }

        public Log(boolean doOutput) {
            this.doOutput = doOutput;
        }

        public void append(String s) {
            if (doOutput) {
                log("LLPermission: " + s);
            }
        }

        public void setDoOutput(boolean doOutput) {
            this.doOutput = doOutput;
        }

        public boolean doesOutput() {
            return doOutput;
        }
    }
}
