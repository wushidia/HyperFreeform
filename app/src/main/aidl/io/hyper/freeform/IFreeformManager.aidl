package io.hyper.freeform;

import android.content.ComponentName;
import android.graphics.Rect;

interface IFreeformManager {
    String getVersionName();
    int getVersionCode();
    boolean isReady();

    // Launch
    void startFreeform(in ComponentName component, int userId, int windowState);
    void startFreeformPackage(String packageName, int userId, int windowState);

    // Window ops (Xiaomi-like)
    void moveTask(int taskId, in Rect bounds);
    void resizeTask(int taskId, in Rect bounds, float scale);
    void closeTask(int taskId);
    void fullscreenTask(int taskId);
    void switchMini(int taskId, boolean mini);
    void pinTask(int taskId, boolean pin);
    void unpinTask(int taskId);

    int getOpenWindowCount();
    int[] getOpenTaskIds();
    String dumpState();

    void setEnabled(boolean enabled);
    boolean isEnabled();
    void collapseStatusBar();

    /** MuMu/debug only: simulate IME avoid path without soft keyboard. height px. */
    void debugSimulateIme(boolean visible, int height);

    /**
     * Xiaomi startSmallFreeformFromRecent equivalent:
     * convert an existing recents task into freeform (typically mini windowState=1).
     * Appended at end so existing service-call ordinals stay stable.
     */
    void startFreeformFromRecent(int taskId, int windowState);

    /**
     * Freeform → split (Xiaomi MulWinSwitch freeform-to-split).
     * position: 0=top/left, 1=bottom/right.
     * Appended for ordinal stability (service call code 21).
     */
    void splitTask(int taskId, int position);

    /**
     * Xiaomi MiuiFreeformModePinHandler.startPinToFullscreen:
     * maximize a pinned freeform task directly (bubble message / double-tap).
     * Appended for ordinal stability (service call code 22).
     */
    void startPinToFullscreen(int taskId);

    /**
     * Xiaomi MiuiFreeformModePinHandler.updatePinFloatingWindowPos lite.
     * Persist bubble edge + Y while pinned (service call code 23).
     * pinPos: 0=left, 1=right; y: bubble top px.
     */
    void updatePinFloatingWindowPos(int taskId, int pinPos, int y);

    /**
     * Adjust the densityDpi the freeform app renders at (Xiaomi-like in-window DPI zoom).
     * dpi <= 0 resets to the system default. Appended for ordinal stability (service call 24).
     */
    void setFreeformDpi(int taskId, int dpi);

    /** Current freeform dpi override for a task (0 = following system). Service call 25. */
    int getFreeformDpi(int taskId);

    /**
     * Set the GLOBAL in-window DPI as a percentage of system density (100 = follow system).
     * Persisted in Settings.Global from system_server (the app process lacks that permission)
     * and applied live to all open freeform windows. Service call 26.
     */
    void setGlobalDpiPercent(int percent);

    /** Current global in-window DPI percentage (100 = follow system). Service call 27. */
    int getGlobalDpiPercent();

    /**
     * Debug/MuMu: simulate the freeform app requesting [orientation] (drives the same
     * onAppRequestedOrientation path as the setRequestedOrientation hook) so landscape rotation
     * can be verified without hitting a video app's fullscreen button. Service call 28.
     * orientation: 0=landscape, 1=portrait (ActivityInfo.screenOrientation values).
     */
    void debugRequestOrientation(int taskId, int orientation);

    /**
     * Sidebar edge side: 0=left, 1=right. Persisted in Settings.Global from system_server
     * so SidebarController reattaches. Service call 29.
     */
    void setSidebarSide(int side);

    /** Current sidebar side (0=left, 1=right). Service call 30. */
    int getSidebarSide();

    /**
     * Persist the user's sidebar app selection (comma-separated package names) into
     * Settings.Global from system_server (the app process lacks WRITE permission). Empty
     * means "show every launchable app". Service call 31.
     */
    void setSidebarApps(in String packagesCsv);

    /** Current sidebar app selection CSV (empty = all launchable). Service call 32. */
    String getSidebarApps();

    /** Show app labels beside sidebar icons. Service call 33. */
    void setSidebarShowAppNames(boolean show);

    /** Whether app labels are shown beside sidebar icons. Service call 34. */
    boolean getSidebarShowAppNames();

    /**
     * Set the normal small-window width/height as percentages of the natural portrait display.
     * Existing windows are resized live. Appended for ordinal stability (service call 35).
     */
    void setGlobalWindowSizePercent(int widthPercent, int heightPercent);

    /** Current global normal-window width percentage. Service call 36. */
    int getGlobalWindowWidthPercent();

    /** Current global normal-window height percentage. Service call 37. */
    int getGlobalWindowHeightPercent();
}
