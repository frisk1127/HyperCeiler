package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Parcel;
import android.os.Process;
import android.os.UserHandle;

import java.util.List;
import java.util.Map;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import static com.sevtinge.hyperceiler.libhook.base.BaseHook.*;

final class DarkModeAppsOS4 {
    private static final int GET_FORCE_DARK_APP_INFO = 16777210;

    static void install() {
        Class<?> list = findClass("com.android.server.ForceDarkAppListManager");
        Class<?> mode = findClass("com.android.server.ForceDarkUiModeModeManager");
        Class<?> settings = findClass("com.android.server.DarkModeAppSettingsInfo");

        findAndHookMethod(list, "getDarkModeAppList", long.class, int.class, new IMethodHook() {
            @Override public void after(HookParam param) {
                Object data = param.getResult();
                if (param.getHasThrowable() || data == null) return; // Unchanged list timestamp.
                addInstalledApps(param.getThisObject(), data, (int) param.getArgs()[1], settings);
            }
        });

        findAndHookMethod(mode, "onTransact", int.class, Parcel.class, Parcel.class, int.class, new IMethodHook() {
            @Override public void after(HookParam param) {
                Object[] args = param.getArgs();
                if ((int) args[0] != GET_FORCE_DARK_APP_INFO || param.getHasThrowable()
                        || !Boolean.TRUE.equals(param.getResult())) return;
                updateAppInfo(param.getThisObject(), (Parcel) args[1], (Parcel) args[2]);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void addInstalledApps(Object manager, Object data, int user, Class<?> settingsClass) {
        PackageManager pm = (PackageManager) getObjectField(manager, "mPackageManager");
        long identity = Binder.clearCallingIdentity();
        try {
            List<ApplicationInfo> apps = (List<ApplicationInfo>) callMethod(pm, "getInstalledApplicationsAsUser", 0, user);
            List<Object> rows = (List<Object>) callMethod(data, "getDarkModeAppDetailInfoList");
            Map<?, ?> settings = (Map<?, ?>) getObjectField(manager, "mForceDarkAppSettings");
            Object defaults = newInstance(settingsClass);
            List<Object> merged = DarkModeOverrides.withInstalledApps(rows, apps,
                    row -> (String) callMethod(row, "getPkgName"), app -> {
                        Object policy = settings.get(app.packageName);
                        return callMethod(manager, "getAppInfo", policy != null ? policy : defaults, app, user);
                    });
            callMethod(data, "setDarkModeAppDetailInfoList", merged);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static void updateAppInfo(Object service, Parcel request, Parcel reply) {
        int end = request.dataPosition();
        String pkg;
        int user;
        try {
            request.setDataPosition(0);
            request.enforceInterface("android.app.IUiModeManager");
            pkg = request.readString();
            user = request.readInt();
            if (pkg == null || request.readBoolean()) return; // System UI also requests splash policies.
        } finally {
            request.setDataPosition(end);
        }

        int uid = Binder.getCallingUid();
        if (uid >= Process.FIRST_APPLICATION_UID) {
            // The OS4 app client writes user 0 even when running in a secondary profile.
            user = (int) callMethod(UserHandle.getUserHandleForUid(uid), "getIdentifier");
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Object manager = getObjectField(service, "mForceDarkAppListManager");
            Object security = getObjectField(manager, "mSecurityManagerService");
            boolean enabled = (boolean) callMethod(security, "getAppDarkModeForUser", pkg, user);
            PackageManager pm = (PackageManager) getObjectField(manager, "mPackageManager");
            ApplicationInfo info = (ApplicationInfo) callMethod(pm, "getApplicationInfoAsUser", pkg, 0, user);
            if (DarkModeOverrides.isUserApp(info)) {
                DarkModeOverrides.applySelection(reply, pkg, enabled,
                        () -> (String) callMethod(service, "getForceDarkAppConfig", pkg));
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
