package com.faendir.lightning_launcher.permission_manager;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
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
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
            boolean enable = doLog && !log.doesOutput();
            log.append("Prefs request to disable logs: " + !doLog);
            log.setDoOutput(doLog);
            if (enable) log.append("Enabled Logs");
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals("android")) {
            log.append("Hooked android package");
            try {
                Class<?> packageManagerService = findClass("com.android.server.pm.PackageManagerService", loadPackageParam.classLoader);
                setupReceiver(packageManagerService);
                setupGranter(packageManagerService, loadPackageParam.classLoader);
                setupExceptionPreventive(packageManagerService);
            } catch (XposedHelpers.ClassNotFoundError e) {
                log.error("---FAILED TO FIND PACKAGE_MANAGER_SERVICE -> HOOK IS INACTIVE---", e);
            }
        } else if (loadPackageParam.packageName.equals(Strings.PKG)) {
            log.append("Hooked own package");
            setupSelfHook(loadPackageParam.classLoader);
        }
    }

    private void setupReceiver(@NonNull Class packageManagerService) {
        try {
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
        } catch (NoSuchMethodError | IllegalArgumentException e) {
            log.error("---FAILED TO HOOK SYSTEM_READY -> LIVE APPLYING WILL NOT WORK---", e);
        }
    }

    private void setupGranter(@NonNull Class packageManagerService, @NonNull final ClassLoader classLoader) {
        XC_MethodHook hookGrantPermissions = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    String pkgName = (String) getObjectField(param.args[0], "packageName");
                    if (!(pkgName.equals(Strings.LLX) || pkgName.equals(Strings.LL))) return;
                    installed = pkgName;
                    log.append("Granting Permissions to: " + installed);
                    Object extras = getObjectField(param.args[0], "mExtras");
                    Object settings = getObjectField(param.thisObject, "mSettings");
                    Object permissions = getObjectField(settings, "mPermissions");
                    Collection<String> requestedPerms = null;
                    try {
                        requestedPerms = (Collection<String>) getObjectField(param.args[0], "requestedPermissions");
                    } catch (NoSuchFieldError | ClassCastException e) {
                        log.append("Failed to get requested permissions. Old permissions won't be removed until reboot.", e);
                    }
                    List<String> newPerms = Strings.read(pref);
                    log.append("Adding permissions: " + Arrays.toString(newPerms.toArray()));
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        Object sharedUser = null;
                        try {
                            sharedUser = getObjectField(extras, "sharedUser");
                        } catch (NoSuchFieldError ignored) {
                        }
                        log.append("Shared User exists: " + (sharedUser != null));
                        Collection<String> grantedPerms;
                        if (sharedUser == null) {
                            grantedPerms = (Collection<String>) getObjectField(extras, "grantedPermissions");
                        } else {
                            grantedPerms = (Collection<String>) getObjectField(sharedUser, "grantedPermissions");
                        }
                        grantPermsPreM(pkgName, newPerms, grantedPerms, param.thisObject, permissions, extras, sharedUser);
                        if (requestedPerms != null) {
                            revokePermsPreM(newPerms, grantedPerms, requestedPerms, param.thisObject, permissions, extras, sharedUser);
                        }
                    } else {
                        Class userManagerService = findClass("com.android.server.pm.UserManagerService", classLoader);
                        Object permissionState = callMethod(extras, "getPermissionsState");
                        Object userService = callStaticMethod(userManagerService, "getInstance");
                        int[] userIds = (int[]) callMethod(userService, "getUserIds");
                        for (int id : userIds) {
                            log.append("user: " + id);
                            grantPermsPostM(pkgName, newPerms, param.thisObject, permissions, permissionState, id);
                            if (requestedPerms != null) {
                                revokePermsPostM(newPerms, requestedPerms, param.thisObject, permissions, permissionState, id);
                            }
                        }
                    }
                } catch (NoSuchFieldError | ClassCastException e) {
                    log.error("---NOT ALL FIELDS FOUND OR INVALID FIELDS -> HOOK IS BROKEN---", e);
                } catch (NoSuchMethodError | IllegalArgumentException e) {
                    log.error("---NOT ALL METHODS COULD BE CALLED -> HOOK IS BROKEN---", e);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                findAndHookMethod(packageManagerService, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, hookGrantPermissions);
            } else {
                findAndHookMethod(packageManagerService, "grantPermissionsLPw", "android.content.pm.PackageParser$Package", boolean.class, String.class, hookGrantPermissions);
            }
            log.append("Hooked grantPermissionsLPw");
        } catch (NoSuchMethodError e) {
            log.error("---FAILED TO HOOK GRANT_PERMISSIONS_LPW -> HOOK IS INACTIVE---", e);
        }
    }

    private void grantPermsPreM(@NonNull String pkgName, @NonNull Collection<String> newPerms, @NonNull Collection<String> grantedPerms,
                                @NonNull Object packageManagerService, @NonNull Object permissions, @NonNull Object extras, @Nullable Object sharedUser) {
        for (String perm : newPerms) {
            try {
                Object permission = callMethod(permissions, "get", perm);
                log.append("Permission " + perm + ": " + permission);
                if (permission == null) continue;
                if (callMethod(packageManagerService, "checkPermission", perm, pkgName) == PackageManager.PERMISSION_GRANTED) {
                    log.append("Permission already present");
                    continue;
                }
                grantedPerms.add(perm);
                int[] gpGIds = (int[]) getObjectField(sharedUser != null ? sharedUser : extras, "gids");
                int[] bpGIds = (int[]) getObjectField(permission, "gids");
                callStaticMethod(packageManagerService.getClass(),
                        "appendInts", gpGIds, bpGIds);
                log.append("Permission added");
            } catch (NoSuchMethodError | IllegalArgumentException | NoSuchFieldError | ClassCastException e) {
                log.append("Failed to grant Permission " + perm);
            }
        }
    }

    private void revokePermsPreM(@NonNull Collection<String> newPerms, @NonNull Collection<String> grantedPerms, @NonNull Collection<String> requestedPerms,
                                 @NonNull Object packageManagerService, @NonNull Object permissions, @NonNull Object extras, @Nullable Object sharedUser) {
        for (Iterator<String> it = grantedPerms.iterator(); it.hasNext(); ) {
            String granted = it.next();
            if (!requestedPerms.contains(granted) && !newPerms.contains(granted)) {
                try {
                    it.remove();
                    Object permission = callMethod(permissions, "get", granted);
                    log.append("Permission " + granted + ": " + permission);
                    int[] gpGIds = (int[]) getObjectField(sharedUser != null ? sharedUser : extras, "gids");
                    int[] bpGIds = (int[]) getObjectField(permission, "gids");
                    callStaticMethod(packageManagerService.getClass(),
                            "removeInts", gpGIds, bpGIds);
                    log.append("Permission removed");
                } catch (NoSuchMethodError | IllegalArgumentException | NoSuchFieldError | ClassCastException e) {
                    log.append("Failed to revoke Permission " + granted);
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void grantPermsPostM(@NonNull String pkgName, @NonNull Collection<String> newPerms,
                                 @NonNull Object packageManagerService, @NonNull Object permissions, @NonNull Object permissionState, int userId) {
        for (String perm : newPerms) {
            try {
                Object permission = callMethod(permissions, "get", perm);
                log.append("Permission " + perm + ": " + permission);
                if (permission == null) continue;
                if (callMethod(packageManagerService, "checkPermission", perm, pkgName, userId) == PackageManager.PERMISSION_GRANTED) {
                    log.append("Permission already present");
                    continue;
                }
                int protectionLevel = getIntField(permission, "protectionLevel");
                if (protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                    log.append("Dangerous permission");
                    preventExceptionFor = perm;
                    callMethod(packageManagerService, "grantRuntimePermission", installed, perm, userId);
                } else {
                    log.append("Try to treat as normal permission");
                    callMethod(permissionState, "grantInstallPermission", permission);
                }
                log.append("Permission added");
            } catch (NoSuchMethodError | IllegalArgumentException | NoSuchFieldError | ClassCastException | SecurityException e) {
                log.append("Failed to grant Permission " + perm);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void revokePermsPostM(@NonNull Collection<String> newPerms, @NonNull Collection<String> requestedPerms,
                                  @NonNull Object packageManagerService, @NonNull Object permissions, @NonNull Object permissionState, int userId) {
        Collection<String> grantedPerms = (Collection<String>) callMethod(permissionState, "getPermissions", userId);
        for (String granted : grantedPerms) {
            if (!requestedPerms.contains(granted) && !newPerms.contains(granted)) {
                try {
                    Object permission = callMethod(permissions, "get", granted);
                    log.append("Permission " + granted + ": " + permission);
                    int protectionLevel = getIntField(permission, "protectionLevel");
                    if (protectionLevel == PermissionInfo.PROTECTION_DANGEROUS) {
                        log.append("Dangerous permission");
                        preventExceptionFor = granted;
                        callMethod(packageManagerService, "revokeRuntimePermission", installed, granted, userId);
                    } else {
                        log.append("Try to treat as normal permission");
                        callMethod(permissionState, "revokeInstallPermission", permission);
                    }
                    log.append("Permission removed");

                } catch (NoSuchMethodError | IllegalArgumentException | NoSuchFieldError | ClassCastException | SecurityException e) {
                    log.append("Failed to revoke Permission " + granted);
                }
            }
        }

    }

    private void setupExceptionPreventive(@NonNull Class packageManagerService) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
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
            } catch (NoSuchMethodError e) {
                log.error("---FAILED TO HOOK ENFORCE_DECLARED_AS_USED_AND_RUNTIME_OR_DEVELOPMENT_PERMISSION -> WON'T BE ABLE TO GRANT DANGEROUS PERMISSIONS---", e);
            }
        }
    }

    private void setupSelfHook(@NonNull ClassLoader classLoader) {
        try {
            Class mainActivity = findClass(MainActivity.class.getName(), classLoader);
            findAndHookMethod(mainActivity, "isHooked", XC_MethodReplacement.returnConstant(Boolean.TRUE));
            log.append("Hooked self");
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            log.error("---FAILED TO HOOK SELF -> INSTALLATION IS BROKEN---", e);
        }
    }

    public class PermissionUpdateReceiver extends BroadcastReceiver {

        private final Object packageManagerService;
        private final Map<String, Object> mPackages;
        private final Object mSettings;

        public PermissionUpdateReceiver(@NonNull Object packageManagerService) {
            try {
                this.packageManagerService = packageManagerService;
                this.mPackages = (Map<String, Object>) getObjectField(packageManagerService, "mPackages");
                this.mSettings = getObjectField(packageManagerService, "mSettings");
            } catch (NoSuchFieldError | ClassCastException e) {
                log.append("Failed to create PermissionUpdateReceiver");
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            log.append("Received Broadcast");
            if (!Strings.ACTION_PERMISSIONS.equals(intent.getExtras().getString(Strings.KEY_ACTION)))
                return;
            try {
                pref.reload();
                boolean killApp = pref.getBoolean(Strings.KEY_KILL, false);
                synchronized (mPackages) {
                    Object pkgInfo = mPackages.get(installed);
                    log.append("Calling grantPermissionsLPw for: " + installed + " (" + pkgInfo + ")");
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        callMethod(packageManagerService, "grantPermissionsLPw", pkgInfo, true);
                    } else {
                        callMethod(packageManagerService, "grantPermissionsLPw", pkgInfo, true, installed);
                    }
                    log.append("Calling writeLPr");
                    callMethod(mSettings, "writeLPr");
                    if (killApp) {
                        log.append("Killing app");
                        ApplicationInfo appInfo = (ApplicationInfo) getObjectField(pkgInfo, "applicationInfo");
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                            callMethod(packageManagerService, "killApplication", installed, appInfo.uid);
                        } else {
                            callMethod(packageManagerService, "killApplication", installed, appInfo.uid, "apply App Settings");
                        }
                    }
                }
            } catch (NoSuchMethodError | IllegalArgumentException | NoSuchFieldError | ClassCastException e) {
                log.append("BroadcastReceiver failed with exception:", e);
            }
        }
    }

    private static class Log {
        private boolean doOutput;

        public Log() {
            this.doOutput = BuildConfig.DEBUG;
        }


        public void append(String s) {
            if (doOutput) {
                log("[LLPermission] " + s);
            }
        }

        private void log(String s) {
            XposedBridge.log(s);
        }

        public void append(String s, Throwable t) {
            append(throwableToString(s, t));
        }

        public void error(String s, Throwable t) {
            log(throwableToString(s, t));
        }

        private String throwableToString(String s, Throwable t) {
            return s + "\n" + t.getMessage() + "\n" + TextUtils.join("\n", t.getStackTrace());
        }

        public void setDoOutput(boolean doOutput) {
            this.doOutput = doOutput;
        }

        public boolean doesOutput() {
            return doOutput;
        }
    }
}
