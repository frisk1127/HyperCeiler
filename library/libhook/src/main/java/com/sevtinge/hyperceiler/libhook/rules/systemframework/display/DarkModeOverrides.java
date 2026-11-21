package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;

import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.Process;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

final class DarkModeOverrides {
    static boolean isUserApp(ApplicationInfo app) {
        return app != null && app.uid >= Process.FIRST_APPLICATION_UID
                && (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
    }

    static <T> List<T> withInstalledApps(List<T> stock, List<ApplicationInfo> installed,
                                         Function<T, String> packageName, Function<ApplicationInfo, T> makeRow) {
        List<T> rows = new ArrayList<>(stock);
        Set<String> seen = new HashSet<>();
        for (T row : stock) seen.add(packageName.apply(row));
        for (ApplicationInfo app : installed) {
            if (isUserApp(app) && seen.add(app.packageName)) rows.add(makeRow.apply(app));
        }
        return rows;
    }

    // OS4 puts origin first, then the per-user switch and JSON only for non-origin replies.
    // Keep the framework's exception header and its remaining fields verbatim.
    static void applySelection(Parcel reply, String pkg, boolean enabled, Supplier<String> stockConfig) {
        int end = reply.dataPosition();
        Parcel updated = Parcel.obtain();
        try {
            reply.setDataPosition(0);
            reply.readException();
            int headerEnd = reply.dataPosition();
            boolean origin = reply.readBoolean();
            boolean wasEnabled = !origin && reply.readBoolean();
            String config = origin ? null : reply.readString();
            if (!enabled && !wasEnabled) return;
            if (enabled) {
                if (origin) config = stockConfig.get();
                JSONObject rules = config == null || config.isEmpty() ? new JSONObject() : new JSONObject(config);
                if (wasEnabled && rules.optBoolean("forceDark")) return;
                rules.put("packageName", pkg);
                rules.put("forceDark", true);
                config = rules.toString();
            }
            updated.appendFrom(reply, 0, headerEnd);
            updated.writeBoolean(false);
            updated.writeBoolean(enabled);
            updated.writeString(config);
            updated.appendFrom(reply, reply.dataPosition(), reply.dataAvail());
            reply.setDataSize(0);
            reply.setDataPosition(0);
            reply.appendFrom(updated, 0, updated.dataSize());
            end = reply.dataSize();
        } catch (JSONException ignored) {
            // Leave an unrecognized vendor configuration intact.
        } finally {
            reply.setDataPosition(end);
            updated.recycle();
        }
    }
}
