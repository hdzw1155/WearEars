package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper();
    private static volatile Thread mainThread = Thread.currentThread();
    private static final ThreadLocal<Looper> CURRENT = new ThreadLocal<>();

    private final Handler handler = new Handler();

    private Looper() { }

    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() {
        Looper l = CURRENT.get();
        if (l != null) return l;
        return Thread.currentThread() == mainThread ? MAIN : null;
    }
    public static void setMyLooperForTest(Looper looper) {
        if (looper == null) CURRENT.remove();
        else CURRENT.set(looper);
    }
    public Handler handler() { return handler; }
}
