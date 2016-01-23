package com.faendir.lightning_launcher.permission_manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
    private String preventExceptionFor = null;

    @SuppressWarnings("WeakerAccess")
    public Hook() {
        log = new Log();
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        pref = new XSharedPreferences(this.getClass().getPackage().getName(), Strings.PREF_NAME);
        pref.makeWorldReadable();
        log.append("Prefs now WorldReadable");
        if (pref.contains(Strings.KEY_LOG)) {
            boolean doLog = pref.getBoolean(Strings.KEY_LOG, log.doesOutput());
            log.append("Prefs request to disable logs: " + !doLog);
            log.setDoOutput(doLog);
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
            setupSelfHook(findClass(MainActivity.class.getName(), loadPackageParam.classLoader));
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

    private void setupGranter(final Class packageManagerService, final Class userManagerService) {
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
                Collection<String> requestedPerms = (Collection<String>) getObjectField(param.args[0], "requestedPermissions");
                List<String> newPerms = Strings.read(pref);
                log.append("Adding permissions: " + Arrays.toString(newPerms.toArray()));
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    Object sharedUser = getObjectField(extras, "sharedUser");
                    log.append("Shared User exists: " + (sharedUser != null));
                    Collection<String> grantedPerms;
                    if (sharedUser == null)
                        grantedPerms = (Collection<String>) getObjectField(extras, "grantedPermissions");
                    else
                        grantedPerms = (Collection<String>) getObjectField(sharedUser, "grantedPermissions");

                    for (String perm : newPerms) {
                        Object permission = callMethod(permissions, "get", perm);
                        log.append("Permission " + perm + ": " + permission);
                        if (permission == null) continue;
                        if (callMethod(param.thisObject, "checkPermission", perm, pkgName) == PackageManager.PERMISSION_GRANTED) {
                            log.append("Permission already present");
                            continue;
                        }
                        grantedPerms.add(perm);
                        int[] gpGids = (int[]) getObjectField(sharedUser != null ? sharedUser : extras, "gids");
                        int[] bpGids = (int[]) getObjectField(permission, "gids");
                        callStaticMethod(param.thisObject.getClass(),
                                "appendInts", gpGids, bpGids);
                        log.append("Permission added");
                    }
                    for (Iterator<String> it = grantedPerms.iterator(); it.hasNext(); ) {
                        String granted = it.next();
                        if (!requestedPerms.contains(granted) && !newPerms.contains(granted)) {
                            it.remove();
                            Object permission = callMethod(permissions, "get", granted);
                            int[] gpGids = (int[]) getObjectField(sharedUser != null ? sharedUser : extras, "gids");
                            int[] bpGids = (int[]) getObjectField(permission, "gids");
                            callStaticMethod(param.thisObject.getClass(),
                                    "removeInts", gpGids, bpGids);
                            log.append("Permission removed");
                        }
                    }
                } else {
                    final Object permissionState = callMethod(extras, "getPermissionsState");
                    Object userService = callStaticMethod(userManagerService, "getInstance");
                    int[] userIds = (int[]) callMethod(userService, "getUserIds");
                    for (int id : userIds) {
                        log.append("user: " + id);
                        for (String perm : newPerms) {
                            Object permission = callMethod(permissions, "get", perm);
                            log.append("Permission " + perm + ": " + permission);
                            if (permission == null) continue;
                            if (callMethod(param.thisObject, "checkPermission", perm, pkgName, id) == PackageManager.PERMISSION_GRANTED) {
                                log.append("Permission already present");
                                continue;
                            }
                            int protectionLevel = getIntField(permission, "protectionLevel");
                            if (protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                                log.append("Dangerous permission");
                                preventExceptionFor = perm;
                                try {
                                    callMethod(param.thisObject, "grantRuntimePermission", installed, perm, id);
                                } catch (Throwable t) {
                                    log.append("Failed with exception:", t);
                                }
                            } else {
                                log.append("Try to treat as normal permission");
                                callMethod(permissionState, "grantInstallPermission", permission);
                            }
                            log.append("Permission added");
                        }
                        Collection<String> grantedPerms = (Collection<String>) callMethod(permissionState, "getPermissions", id);
                        for (String granted : grantedPerms) {
                            if (!requestedPerms.contains(granted) && !newPerms.contains(granted)) {
                                Object permission = callMethod(permissions, "get", granted);
                                log.append("Permission " + granted + ": " + permission);
                                int protectionLevel = getIntField(permission, "protectionLevel");
                                if (protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                                    log.append("Dangerous permission");
                                    preventExceptionFor = granted;
                                    try {
                                        callMethod(param.thisObject, "revokeRuntimePermission", installed, granted, id);
                                    } catch (Throwable t) {
                                        log.append("Failed with exception:", t);
                                    }
                                } else {
                                    log.append("Try to treat as normal permission");
                                    callMethod(permissionState, "revokeInstallPermission", permission);
                                }
                                log.append("Permission removed");
                            }
                        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            findAndHookMethod(packageManagerService, "enforceDeclaredAsUsedAndRuntimeOrDevelopmentPermission",
                    "android.content.pm.PackageParser$Package", "com.android.server.pm.BasePermission", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String name = (String) getObjectField(param.args[1], "name");
                            if (preventExceptionFor.equals(name)) {
                                log.append("Prevented SecurityException for " + name);
                                param.setResult(null);
                                preventExceptionFor = null;
                            }
                        }
                    });
            log.append("Hooked enforceDeclaredAsUsedAndRuntimeOrDevelopmentPermission");
        }
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
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
                            callMethod(pmSvc, "killApplication", installed, appInfo.uid);
                        else
                            callMethod(pmSvc, "killApplication", installed, appInfo.uid, "apply App Settings");
                    } catch (Throwable t) {
                        log.append("killing failed with exception:", t);
                    }
                }
            } catch (Throwable t) {
                log.append("BroadcastReceiver failed with exception:", t);
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
                log("[LLPermission] " + s);
            }
        }

        public void append(String s, Throwable t) {
            append(s + "\n" + t.getMessage() + "\n" + TextUtils.join("\n", t.getStackTrace()));
        }

        public void setDoOutput(boolean doOutput) {
            this.doOutput = doOutput;
        }

        public boolean doesOutput() {
            return doOutput;
        }
    }
}
