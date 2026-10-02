package com.jarvis.newly;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lets JARVIS see what's on screen (as a flat indexed list of interactive/text
 * elements) and act on it: tap, type, scroll, press keys, or launch an app.
 * Must be turned on manually by the user in Settings > Accessibility, since
 * Android will not let an app request this via a runtime permission dialog.
 */
public class JarvisAccessibilityService extends AccessibilityService {
    public static JarvisAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* state is pulled on demand via dumpScreen() */ }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    private interface BoolCall { boolean run(); }
    private interface StringCall { String run(); }

    private boolean onMainBool(BoolCall call) {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.run();
        final boolean[] result = new boolean[1];
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try { result[0] = call.run(); } finally { latch.countDown(); }
        });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    private String onMainString(StringCall call) {
        if (Looper.myLooper() == Looper.getMainLooper()) return call.run();
        final String[] result = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try { result[0] = call.run(); } finally { latch.countDown(); }
        });
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0] == null ? "(no screen content available)" : result[0];
    }

    /** Captures the current display for visual fallback when accessibility data is incomplete. */
    public String getScreenshotBase64() {
        if (android.os.Build.VERSION.SDK_INT < 30) return null;
        final String[] result = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            main.post(() -> {
                try {
                    takeScreenshot(android.view.Display.DEFAULT_DISPLAY, command -> command.run(), new TakeScreenshotCallback() {
                        @Override public void onSuccess(ScreenshotResult screenshot) {
                            HardwareBuffer buffer = screenshot.getHardwareBuffer();
                            Bitmap bitmap = null;
                            try {
                                bitmap = Bitmap.wrapHardwareBuffer(buffer, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                                if (bitmap != null) {
                                    Bitmap scaled = bitmap;
                                    int maxWidth = 1280;
                                    if (bitmap.getWidth() > maxWidth) {
                                        int h = (int) (((float) bitmap.getHeight() / bitmap.getWidth()) * maxWidth);
                                        scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, h, true);
                                    }
                                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 65, out);
                                    result[0] = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                                    if (scaled != bitmap) scaled.recycle();
                                }
                            } catch (Exception ignored) { }
                            finally { if (bitmap != null) bitmap.recycle(); buffer.close(); latch.countDown(); }
                        }
                        @Override public void onFailure(int errorCode) { latch.countDown(); }
                    });
                } catch (Exception ignored) { latch.countDown(); }
            });
            latch.await(4, TimeUnit.SECONDS);
        } catch (Exception ignored) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    public boolean gesture(String action, int x1, int y1, int x2, int y2, int durationMs) {
        final int fx2 = x2 < 0 ? x1 : x2;
        final int fy2 = y2 < 0 ? y1 : y2;
        return onMainBool(() -> {
            if (x1 < 0 || y1 < 0) return false;
            String a = action == null ? "tap" : action.toLowerCase(Locale.US);
            if ("swipe".equals(a)) {
                return gestureSwipe(x1, y1, fx2, fy2, Math.max(100, Math.min(1500, durationMs)));
            }
            if ("long_press".equals(a)) return gestureLongPress(x1, y1, Math.max(300, Math.min(1500, durationMs)));
            return gestureTap(x1, y1);
        });
    }

    private boolean gestureLongPress(int x, int y, long durationMs) {
        Path p = new Path(); p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, durationMs);
        return dispatchGestureSync(new GestureDescription.Builder().addStroke(stroke).build());
    }

    /** Builds a numbered, LLM-readable list of the currently visible interactive/text elements. */
    private synchronized String dumpScreenInternal() {
        lastNodes = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(no screen content available)";
        StringBuilder out = new StringBuilder();
        collect(root, out, 180);
        if (out.length() == 0) return "(screen has no readable elements)";
        return out.toString();
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder out, int limit) {
        if (node == null || lastNodes.size() >= limit) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        boolean interesting = (text != null && text.length() > 0)
                || (desc != null && desc.length() > 0)
                || node.isClickable() || node.isEditable() || node.isScrollable() || node.isCheckable();
        if (interesting) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            String cls = node.getClassName() == null ? "View" : node.getClassName().toString();
            int dot = cls.lastIndexOf('.');
            if (dot >= 0) cls = cls.substring(dot + 1);
            int idx = lastNodes.size();
            lastNodes.add(node);
            out.append('[').append(idx).append("] ").append(cls);
            if (text != null && text.length() > 0) out.append(" text=\"").append(clip(text.toString())).append('"');
            if (desc != null && desc.length() > 0) out.append(" desc=\"").append(clip(desc.toString())).append('"');
            out.append(" clickable=").append(node.isClickable() ? 1 : 0);
            out.append(" editable=").append(node.isEditable() ? 1 : 0);
            out.append(" scrollable=").append(node.isScrollable() ? 1 : 0);
            out.append(" checked=").append(node.isChecked() ? 1 : 0);
            out.append(" bounds=[").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(']');
            out.append('\n');
        }
        for (int i = 0; i < node.getChildCount() && lastNodes.size() < limit; i++) {
            collect(node.getChild(i), out, limit);
        }
    }

    private String clip(String s) {
        s = s.replace("\n", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** Taps the element at this index from the most recent dumpScreen() call. */
    private boolean tapInternal(int index) {
        AccessibilityNodeInfo n = nodeAt(index);
        if (n == null) return false;
        AccessibilityNodeInfo clickable = findClickableSelfOrAncestor(n);
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        return gestureTap(b.centerX(), b.centerY());
    }

    public String dumpScreen() { return onMainString(this::dumpScreenInternal); }
    public boolean tap(int index) { return onMainBool(() -> tapInternal(index)); }
    public boolean typeText(int index, String text) { return onMainBool(() -> typeTextInternal(index, text)); }
    public boolean scroll(String direction, int index) { return onMainBool(() -> scrollInternal(direction, index)); }
    public boolean key(String action) { return onMainBool(() -> keyInternal(action)); }
    public boolean openApp(String name) { return onMainBool(() -> openAppInternal(name)); }

    private AccessibilityNodeInfo findClickableSelfOrAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        int hops = 0;
        while (cur != null && hops++ < 6) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    /** Types text into the editable element at this index (focuses it first). */
    private boolean typeTextInternal(int index, String text) {
        AccessibilityNodeInfo n = nodeAt(index);
        if (n == null) return false;
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** Scrolls a specific element (by index) if given, otherwise swipes the middle of the screen. */
    private boolean scrollInternal(String direction, int index) {
        AccessibilityNodeInfo n = index >= 0 ? nodeAt(index) : findScrollable(getRootInActiveWindow());
        boolean down = direction == null || direction.toLowerCase(Locale.US).startsWith("d");
        if (n != null) {
            int action = down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if (n.performAction(action)) return true;
        }
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        int w = 720, h = 1400;
        try {
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            w = p.x; h = p.y;
        } catch (Exception ignored) { }
        int cx = w / 2;
        int startY = down ? (int) (h * 0.75) : (int) (h * 0.25);
        int endY = down ? (int) (h * 0.25) : (int) (h * 0.75);
        return gestureSwipe(cx, startY, cx, endY, 300);
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** Global navigation keys. */
    private boolean keyInternal(String action) {
        if (action == null) return false;
        switch (action.toLowerCase(Locale.US)) {
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: return false;
        }
    }

    /** Opens an app by friendly name, package name, or best-effort label match. */
    private boolean openAppInternal(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String q = name.trim().toLowerCase(Locale.US);

        // Generic system roles: resolved via standard Android intents, not a guessed
        // package name, since dialer/camera/contacts/messages/settings package names
        // vary by OEM (Samsung, MIUI, etc. all ship their own).
        Intent generic = genericIntent(q);
        if (generic != null && launch(generic)) return true;

        // Named third-party apps: these ARE the same package everywhere (Play Store
        // publishes one package per app regardless of device brand), so a direct
        // lookup is the most precise match for "open chrome" meaning Chrome specifically.
        String pkg = alias(q);
        if (pkg == null && q.contains(".")) pkg = q;
        if (pkg != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && launch(i)) return true;
        }

        // That specific app isn't installed (e.g. a device without Google Play Services,
        // or the person just doesn't have it) — fall back to whatever app the OS
        // considers the default handler for that general category.
        Intent fallback = categoryFallback(q);
        if (fallback != null && launch(fallback)) return true;

        // Last resort: fuzzy-match against every installed app's visible label.
        String fuzzy = fuzzyFindPackage(getPackageManager(), q);
        if (fuzzy != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(fuzzy);
            if (i != null && launch(i)) return true;
        }
        return false;
    }

    private boolean launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Intent genericIntent(String a) {
        switch (a) {
            case "phone": case "dialer": case "call": return new Intent(Intent.ACTION_DIAL);
            case "camera": return new Intent("android.media.action.STILL_IMAGE_CAMERA");
            case "contacts": return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS);
            case "messages": case "sms": case "text": return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING);
            case "settings": return new Intent(Settings.ACTION_SETTINGS);
            default: return null;
        }
    }

    /** Standard "open whatever app handles this category" intents — work regardless of
     * which specific app the OS/OEM has set as the handler. */
    private Intent categoryFallback(String a) {
        switch (a) {
            case "chrome": case "browser": return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER);
            case "maps": case "google maps": return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS);
            case "gmail": case "email": return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL);
            case "spotify": case "music": return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC);
            default: return null;
        }
    }

    private String alias(String a) {
        switch (a) {
            case "whatsapp": return "com.whatsapp";
            case "youtube": return "com.google.android.youtube";
            case "chrome": return "com.android.chrome";
            case "maps": case "google maps": return "com.google.android.apps.maps";
            case "gmail": return "com.google.android.gm";
            case "spotify": return "com.spotify.music";
            case "instagram": return "com.instagram.android";
            default: return null;
        }
    }

    private String fuzzyFindPackage(PackageManager pm, String query) {
        try {
            for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                if (pm.getLaunchIntentForPackage(app.packageName) == null) continue;
                String label = pm.getApplicationLabel(app).toString().toLowerCase(Locale.US);
                if (label.contains(query) || query.contains(label)) return app.packageName;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private AccessibilityNodeInfo nodeAt(int index) {
        if (index < 0 || index >= lastNodes.size()) return null;
        return lastNodes.get(index);
    }

    private boolean gestureTap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 60);
        return dispatchGestureSync(new GestureDescription.Builder().addStroke(stroke).build());
    }

    private boolean gestureSwipe(int x1, int y1, int x2, int y2, long durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, durationMs);
        return dispatchGestureSync(new GestureDescription.Builder().addStroke(stroke).build());
    }

    private boolean dispatchGestureSync(GestureDescription gesture) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        main.post(() -> dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { ok[0] = true; latch.countDown(); }
            @Override public void onCancelled(GestureDescription g) { ok[0] = false; latch.countDown(); }
        }, null));
        try { latch.await(2500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return ok[0];
    }
}
