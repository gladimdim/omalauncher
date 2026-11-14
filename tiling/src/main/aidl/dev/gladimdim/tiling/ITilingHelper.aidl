package dev.gladimdim.tiling;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * What the launcher asks of the helper process, which runs as the shell user (helper/HelperMain.kt) and so may
 * create trusted displays, start apps on them and send them input.
 */
interface ITilingHelper {
    int version();

    /** The launcher's process: when it dies, the helper lets go of every display and offers itself again. */
    void attach(IBinder app);

    /**
     * A new display drawing into the surface; returns its id, or -1. `refreshRate` is the screen's: left to itself a
     * virtual display runs at 60 Hz, and its apps scroll at half the speed of the screen they are shown on.
     */
    int createDisplay(in Surface surface, int width, int height, int dpi, String name, float refreshRate);

    /** Null while the tile is out of sight: its apps stay, and stop drawing. */
    void setSurface(int displayId, in Surface surface);

    void resizeDisplay(int displayId, int width, int height, int dpi);

    /** Closes the display's tasks first: left on it, they would move to the main screen. */
    oneway void releaseDisplay(int displayId);

    /**
     * Lets go of displays and leaves their apps running: Android moves them to the main screen as ordinary apps, each
     * in front of the one before, so the last display's app ends up on top. `thenFront` is a task to bring in front
     * of them all afterwards, or -1.
     */
    oneway void dropDisplays(in int[] displayIds, int thenFront);

    /** `am start` arguments, without the display. */
    oneway void startActivity(int displayId, in String[] args);

    oneway void injectMotion(int displayId, in MotionEvent event);

    oneway void injectKey(int displayId, in KeyEvent event);

    /** Gives the keys to the top task of a display. */
    oneway void focusDisplay(int displayId);

    oneway void focusTask(int taskId);

    /** One task a line, top first: `taskId displayId package/activity`. */
    String tasks();

    oneway void removeTask(int taskId);

    oneway void moveTaskToDisplay(int taskId, int displayId);

    oneway void expandNotifications();

    /**
     * Switches an accessibility service on in Android's settings, off first when `again`: that makes Android bind
     * one it has given up on after its process was killed.
     */
    oneway void enableAccessibilityService(String component, boolean again);

    /** A key pressed and let go, as from no keyboard in particular, to whatever has the keys. */
    oneway void tapKey(int keyCode);
}
