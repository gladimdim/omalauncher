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

    /** A new display drawing into the surface; returns its id, or -1. */
    int createDisplay(in Surface surface, int width, int height, int dpi, String name);

    /** Null while the tile is out of sight: its apps stay, and stop drawing. */
    void setSurface(int displayId, in Surface surface);

    void resizeDisplay(int displayId, int width, int height, int dpi);

    /** Closes the display's tasks first: left on it, they would move to the main screen. */
    oneway void releaseDisplay(int displayId);

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
}
