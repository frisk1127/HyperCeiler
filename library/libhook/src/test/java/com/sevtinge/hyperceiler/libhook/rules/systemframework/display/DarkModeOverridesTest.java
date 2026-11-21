package com.sevtinge.hyperceiler.libhook.rules.systemframework.display;

import android.content.pm.ApplicationInfo;
import android.os.Parcel;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class DarkModeOverridesTest {
    private static final String TAOBAO = "com.taobao.taobao";
    private static final String PDD = "com.xunmeng.pinduoduo";
    private static final String FISH = "com.taobao.idlefish";

    @Test public void addsMissingThirdPartyAppsOnceAndKeepsStockRows() {
        List<String> stock = List.of("stock", TAOBAO);
        List<ApplicationInfo> apps = List.of(app(TAOBAO, 0), app(PDD, 0), app(FISH, 0), app(FISH, 0),
                app("system", ApplicationInfo.FLAG_SYSTEM), app("updated", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP));
        assertEquals(List.of("stock", TAOBAO, PDD, FISH),
                DarkModeOverrides.withInstalledApps(stock, apps, Function.identity(), app -> app.packageName));
        assertEquals(List.of("stock", TAOBAO), stock);
    }

    @Test public void enabledOriginAppReceivesItsExistingActivityRules() throws Exception {
        Parcel reply = stockReply(true, false, null);
        try {
            DarkModeOverrides.applySelection(reply, TAOBAO, true,
                    () -> "{\"mainRule\":1040,\"forceDarkActivityConfigList\":[{\"forceDark\":false}]}");
            JSONObject rules = readEnabledConfig(reply);
            assertEquals(TAOBAO, rules.getString("packageName"));
            assertEquals(1040, rules.getInt("mainRule"));
            assertFalse(rules.getJSONArray("forceDarkActivityConfigList").getJSONObject(0).getBoolean("forceDark"));
        } finally {
            reply.recycle();
        }
    }

    @Test public void overridesAnExplicitlyDisabledRendererAndHandlesMissingRules() throws Exception {
        for (String config : new String[]{null, "{\"forceDark\":false,\"secondaryRule\":4}"}) {
            String pkg = config == null ? FISH : PDD;
            Parcel reply = stockReply(false, true, config);
            try {
                DarkModeOverrides.applySelection(reply, pkg, true, () -> { throw new AssertionError(); });
                JSONObject rules = readEnabledConfig(reply);
                assertEquals(pkg, rules.getString("packageName"));
                if (config != null) assertEquals(4, rules.getInt("secondaryRule"));
            } finally {
                reply.recycle();
            }
        }
    }

    @Test public void uncheckedOriginAndAlreadyWorkingRulesKeepTheOriginalReply() {
        for (boolean origin : new boolean[]{true, false}) {
            Parcel reply = stockReply(origin, true, "{\"forceDark\":true}");
            try {
                byte[] original = reply.marshall();
                DarkModeOverrides.applySelection(reply, TAOBAO, !origin, () -> { throw new AssertionError(); });
                assertArrayEquals(original, reply.marshall());
                assertEquals(reply.dataSize(), reply.dataPosition());
            } finally {
                reply.recycle();
            }
        }
    }

    @Test public void uncheckedSwitchWinsOverAnEnabledVendorOverride() {
        Parcel reply = stockReply(false, true, "{\"forceDark\":true}");
        try {
            DarkModeOverrides.applySelection(reply, PDD, false, () -> { throw new AssertionError(); });
            reply.setDataPosition(0);
            reply.readException();
            assertFalse(reply.readBoolean());
            assertFalse(reply.readBoolean());
            assertEquals("{\"forceDark\":true}", reply.readString());
            assertTail(reply);
        } finally {
            reply.recycle();
        }
    }

    private static ApplicationInfo app(String pkg, int flags) {
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = pkg;
        app.uid = 10123;
        app.flags = flags;
        return app;
    }

    private static Parcel stockReply(boolean origin, boolean enabled, String config) {
        Parcel reply = Parcel.obtain();
        reply.writeNoException();
        reply.writeBoolean(origin);
        if (!origin) {
            reply.writeBoolean(enabled);
            reply.writeString(config);
        }
        reply.writeInt(2); // OS4 interceptRelaunchType
        reply.writeString("vendor extension");
        return reply;
    }

    private static JSONObject readEnabledConfig(Parcel reply) throws Exception {
        assertEquals(reply.dataSize(), reply.dataPosition());
        reply.setDataPosition(0);
        reply.readException();
        assertFalse(reply.readBoolean());
        assertTrue(reply.readBoolean());
        JSONObject rules = new JSONObject(reply.readString());
        assertTrue(rules.getBoolean("forceDark"));
        assertTail(reply);
        return rules;
    }

    private static void assertTail(Parcel reply) {
        assertEquals(2, reply.readInt());
        assertEquals("vendor extension", reply.readString());
        assertEquals(0, reply.dataAvail());
    }
}
