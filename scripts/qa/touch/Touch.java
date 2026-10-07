import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * A real touch stream for scripts/qa-smoke.sh and for reproducing gesture defects. `adb shell input` cannot make one:
 * its swipe has no initial hold (a long-press drag never starts) and its motionevent command loses the down time between
 * processes. This runs as the shell user under app_process, like the input command itself, and injects one finger with a
 * consistent down time, an optional hold before it moves, and a MOVE every 16 ms.
 *
 *   app_process -Djava.class.path=/data/local/tmp/uv-touch.jar /system/bin Touch x1 y1 x2 y2 holdMs moveMs [x3 y3 ...]
 *
 * The finger lands on x1,y1, waits holdMs (press without moving), glides to every following point in equal shares of
 * moveMs, rests 150 ms and lifts. Nothing here touches app data. Built by scripts/qa/touch/build.sh.
 */
public final class Touch {
    private static Object manager;
    private static Method inject;

    private static void init() throws Exception {
        Class<?> cls;
        Object instance;
        try {
            cls = Class.forName("android.hardware.input.InputManagerGlobal");
            instance = cls.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            cls = Class.forName("android.hardware.input.InputManager");
            instance = cls.getMethod("getInstance").invoke(null);
        }
        manager = instance;
        inject = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static void send(long downTime, int action, float x, float y) throws Exception {
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // 2 = INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        Object ok = inject.invoke(manager, e, 2);
        e.recycle();
        if (!(ok instanceof Boolean) || !((Boolean) ok)) throw new IllegalStateException("injection refused");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length % 2 != 0) {
            System.err.println("usage: Touch x1 y1 x2 y2 holdMs moveMs [x3 y3 ...]");
            System.exit(2);
        }
        float[] a = new float[args.length];
        for (int i = 0; i < args.length; i++) a[i] = Float.parseFloat(args[i]);
        int extra = (args.length - 6) / 2;
        float[][] pts = new float[2 + extra][];
        pts[0] = new float[] {a[0], a[1]};
        pts[1] = new float[] {a[2], a[3]};
        for (int i = 0; i < extra; i++) pts[2 + i] = new float[] {a[6 + 2 * i], a[7 + 2 * i]};
        long hold = (long) a[4];
        long move = (long) a[5];
        init();
        long down = SystemClock.uptimeMillis();
        send(down, MotionEvent.ACTION_DOWN, pts[0][0], pts[0][1]);
        SystemClock.sleep(hold);
        int legs = pts.length - 1;
        long steps = Math.max(1, move / legs / 16);
        for (int leg = 0; leg < legs; leg++) {
            for (long i = 1; i <= steps; i++) {
                float f = (float) i / steps;
                send(down, MotionEvent.ACTION_MOVE, pts[leg][0] + (pts[leg + 1][0] - pts[leg][0]) * f,
                        pts[leg][1] + (pts[leg + 1][1] - pts[leg][1]) * f);
                SystemClock.sleep(16);
            }
        }
        SystemClock.sleep(150);
        float[] last = pts[pts.length - 1];
        send(down, MotionEvent.ACTION_UP, last[0], last[1]);
    }
}
