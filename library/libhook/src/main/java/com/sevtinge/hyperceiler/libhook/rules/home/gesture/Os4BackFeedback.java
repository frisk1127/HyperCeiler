/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.gesture;

import android.app.ActivityManager;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.util.List;

/** Preview and commit use the same task snapshot; native code owns back-event cancellation. */
public final class Os4BackFeedback {
    private static final String TAG = "HyperCeiler.BackFeedback";
    private static final BackHoldState state = new BackHoldState();
    private static volatile Handler main;
    private static Handler worker;
    private static Application app;
    private static volatile NativePreview nativePreview;
    private static boolean hapticSent;
    private static Target target, pending;
    private static long pendingTime;
    private static volatile long cancellation;
    private static boolean armed;
    private static int initializationAttempts;
    private static final Runnable expire = Os4BackFeedback::reset;

    private Os4BackFeedback() {}

    public static synchronized void install() {
        if (main != null) return;
        main = new Handler(Looper.getMainLooper());
        HandlerThread thread = new HandlerThread("HC-BackFeedback");
        thread.start(); worker = new Handler(thread.getLooper());
        main.post(Os4BackFeedback::initialize);
    }

    private static void initialize() {
        try {
            app = (Application) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
            if (app == null) {
                if (++initializationAttempts < 100) main.postDelayed(Os4BackFeedback::initialize, 50);
                return;
            }
            if (!"com.android.systemui".equals(app.getPackageName())) { app = null; return; }
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            app.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { reset(); }
            }, filter);
            Thread socket = new Thread(Os4BackFeedback::serve, "HC-BackSocket");
            socket.setDaemon(true); socket.start();
            Log.i(TAG, "feedback ready");
        } catch (Exception error) { Log.w(TAG, "feedback initialization failed", error); }
    }

    private static void serve() {
        try {
            int launcherUid = app.getPackageManager().getApplicationInfo("com.miui.home", 0).uid;
            LocalServerSocket server = new LocalServerSocket("hyperceiler.back_feedback." + android.os.Process.myUid() / 100000);
            for (;;) {
                try (LocalSocket socket = server.accept()) {
                    if (socket.getPeerCredentials().getUid() != launcherUid) continue;
                    Log.i(TAG, "launcher feedback connected");
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    OutputStream output = socket.getOutputStream();
                    for (;;) {
                        byte[] packet = new byte[64]; input.readFully(packet);
                        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
                        if (data.getInt() != 0x48434248) break;
                        int operation = data.getInt();
                        boolean accepted = false;
                        if (operation == 1) {
                            long owner = data.getLong(); int action = data.getInt(), pointers = data.getInt();
                            float x = data.getFloat(), y = data.getFloat(); long time = data.getLong();
                            float density = data.getFloat(), width = data.getFloat(); long revision = data.getLong();
                            if (action == 1) {
                                CountDownLatch done = new CountDownLatch(1);
                                AtomicBoolean result = new AtomicBoolean();
                                main.post(() -> {
                                    try { result.set(touch(owner, action, pointers, x, y, time, density, width, revision)); }
                                    finally { done.countDown(); }
                                });
                                accepted = done.await(40, TimeUnit.MILLISECONDS) && result.get();
                            } else main.post(() -> touch(owner, action, pointers, x, y, time, density, width, revision));
                        } else if (operation == 4) {
                            long known = data.getLong();
                            NativePreview snapshot = nativePreview;
                            long session = snapshot == null ? 0 : snapshot.session;
                            byte[] pixels = snapshot == null || known == session ? new byte[0] : snapshot.pixels;
                            int flags = snapshot == null ? 0 : 1 | (snapshot.left ? 2 : 0);
                            output.write(ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(0x48434248).putInt(flags).putLong(session)
                                .putInt(snapshot == null ? 0 : 96).putInt(snapshot == null ? 0 : 96)
                                .putInt(pixels.length).putInt(0).array());
                            if (pixels.length > 0) output.write(pixels);
                            continue;
                        } else if (operation == 5) {
                            long session = data.getLong();
                            main.post(() -> presented(session));
                        } else if (operation == 6) main.post(Os4BackFeedback::disarm);
                        else if (operation == 2) main.post(Os4BackFeedback::commit);
                        else if (operation == 3) cancel();
                        else break;
                        output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0x48434248).putInt(accepted ? 1 : 0).array());
                    }
                } catch (java.io.IOException disconnected) {
                    Log.i(TAG, "launcher feedback disconnected");
                } finally { cancel(); }
            }
        } catch (Exception error) { Log.w(TAG, "feedback transport unavailable", error); }
    }

    public static boolean touch(long owner, int action, int pointers, float x, float y,
                                long time, float density, float width, long revision) {
        if (main == null || app == null || Looper.myLooper() != Looper.getMainLooper()) return false;
        if (!state.update(owner, action, pointers, x, y, time, density, width, revision)) {
            reset(); return false;
        }
        if (action == 0) {
            cancellation++;
            hide(); target = pending = null;
            main.removeCallbacks(expire);
            main.postDelayed(expire, 10000);
            long serial = state.serial;
            worker.post(() -> prepare(serial));
            return false;
        }
        if (action == 1) {
            boolean commit = armed && target != null && nativePreview != null && state.geometry(true);
            pending = commit ? target : null;
            pendingTime = SystemClock.uptimeMillis();
            state.reset(); hide(); target = null;
            main.removeCallbacks(expire);
            return commit;
        }
        if (armed) {
            if (!state.geometry(true)) disarm();
        }
        return false;
    }

    public static void cancel() {
        Handler handler = main;
        if (handler == null) return;
        if (Looper.myLooper() == handler.getLooper()) reset();
        else handler.post(Os4BackFeedback::reset);
    }

    public static void commit() {
        if (main == null || Looper.myLooper() != main.getLooper()) return;
        Target selected = pending;
        long time = pendingTime;
        long generation = cancellation;
        pending = null;
        if (selected != null && SystemClock.uptimeMillis() - time <= 750)
            worker.post(() -> launch(selected, time, generation));
    }

    private static void prepare(long serial) {
        Target selected = null;
        try {
            if (interactive()) selected = selectTarget();
        } catch (Exception error) { Log.w(TAG, "target lookup failed", error); }
        Target result = selected;
        main.post(() -> {
            if (!state.active || state.serial != serial) return;
            target = result;
            if (result != null) {
                nativePreview = new NativePreview(state.start, state.left(), result.pixels);
                Log.i(TAG, "native target prepared task=" + result.id + " session=" + state.start
                    + " ageMs=" + (SystemClock.uptimeMillis() - state.start));
            }
        });
    }

    private static void presented(long session) {
        if (hapticSent || !state.active || state.start != session || target == null || nativePreview == null) return;
        armed = true;
        hapticSent = true;
        boolean requested = false;
        try {
            if (Settings.System.getInt(app.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                Vibrator vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
                    requested = true;
                }
            }
        } catch (Exception error) { Log.w(TAG, "ready haptic failed", error); }
        Log.i(TAG, "native icon confirmed task=" + target.id + " hapticRequested=" + requested);
    }

    private static void disarm() {
        armed = false; hapticSent = false;
    }

    private static void hide() {
        nativePreview = null; disarm();
    }

    private static void reset() {
        cancellation++;
        state.reset(); target = pending = null;
        if (main != null) { main.removeCallbacks(expire); }
        hide();
    }

    private static final class NativePreview {
        final long session;
        final boolean left;
        final byte[] pixels;
        NativePreview(long session, boolean left, byte[] pixels) {
            this.session = session; this.left = left; this.pixels = pixels;
        }
    }

    private static boolean interactive() {
        return ((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive()
            && !((KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked();
    }

    private static Object service() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }

    private static List<?> recent(Object service, int user) throws Exception {
        Method method = Class.forName("android.app.IActivityTaskManager")
            .getMethod("getRecentTasks", int.class, int.class, int.class);
        Object slice = method.invoke(service, 32, 2, user);
        return (List<?>) slice.getClass().getMethod("getList").invoke(slice);
    }

    private static int integer(Object object, String name) throws Exception {
        return object.getClass().getField(name).getInt(object);
    }

    private static ComponentName component(Object task) throws Exception {
        ComponentName name = (ComponentName) task.getClass().getField("baseActivity").get(task);
        if (name == null) name = (ComponentName) task.getClass().getField("topActivity").get(task);
        return name;
    }

    private static ActivityManager.RunningTaskInfo current() {
        List<ActivityManager.RunningTaskInfo> tasks = ((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(1);
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    private static Target selectTarget() throws Exception {
        ActivityManager.RunningTaskInfo current = current();
        if (current == null) return null;
        int user = integer(current, "userId");
        for (Object task : recent(service(), user)) {
            int id = integer(task, "taskId");
            if (id < 0 || id == current.taskId || integer(task, "userId") != user) continue;
            Object config = task.getClass().getField("configuration").get(task);
            Object window = config.getClass().getField("windowConfiguration").get(config);
            if (((Integer) window.getClass().getMethod("getActivityType").invoke(window)) != 1) continue;
            ComponentName component = component(task);
            if (component == null) continue;
            PackageManager pm = app.getPackageManager();
            try {
                android.content.pm.ApplicationInfo info = pm.getApplicationInfo(component.getPackageName(), 0);
                return new Target(current.taskId, id, user, component, pm.getApplicationIcon(info));
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    private static void launch(Target selected, long released, long generation) {
        try {
            if (generation != cancellation || SystemClock.uptimeMillis() - released > 750 || !interactive()) return;
            ActivityManager.RunningTaskInfo current = current();
            if (current == null || current.taskId != selected.source || integer(current, "userId") != selected.user) return;
            Object service = service();
            boolean valid = false;
            for (Object task : recent(service, selected.user)) {
                if (integer(task, "taskId") == selected.id && integer(task, "userId") == selected.user
                    && selected.component.equals(component(task))) { valid = true; break; }
            }
            if (!valid || generation != cancellation || SystemClock.uptimeMillis() - released > 750) return;
            Object result = Class.forName("android.app.IActivityTaskManager")
                .getMethod("startActivityFromRecents", int.class, Bundle.class).invoke(service, selected.id, null);
            Log.i(TAG, "commit task=" + selected.id + " app=" + selected.component.getPackageName() + " result=" + result);
        } catch (Exception error) { Log.w(TAG, "selected task launch failed", error); }
    }

    private static final class Target {
        final int source, id, user;
        final ComponentName component;
        final byte[] pixels;
        Target(int source, int id, int user, ComponentName component, Drawable icon) {
            this.source = source; this.id = id; this.user = user;
            this.component = component;
            Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            try {
                icon.setBounds(0, 0, 96, 96);
                icon.draw(new Canvas(bitmap));
                ByteBuffer buffer = ByteBuffer.allocate(96 * 96 * 4);
                bitmap.copyPixelsToBuffer(buffer);
                pixels = buffer.array();
            } finally { bitmap.recycle(); }
        }
    }
}
