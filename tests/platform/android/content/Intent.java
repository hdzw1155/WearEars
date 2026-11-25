package android.content;

import android.os.Parcelable;
import java.util.HashMap;
import java.util.Map;

public class Intent {
    private String action;
    private final Map<String, Object> extras = new HashMap<>();

    public Intent() { }
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent putExtra(String name, Parcelable value) { extras.put(name, value); return this; }
    public Intent putExtra(String name, int value) { extras.put(name, value); return this; }
    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T getParcelableExtra(String name) {
        Object v = extras.get(name);
        return v instanceof Parcelable ? (T) v : null;
    }
    public <T> T getParcelableExtra(String name, Class<T> clazz) {
        Object v = extras.get(name);
        return clazz != null && clazz.isInstance(v) ? clazz.cast(v) : null;
    }
    public int getIntExtra(String name, int defaultValue) {
        Object v = extras.get(name);
        return v instanceof Integer ? (Integer) v : defaultValue;
    }
}
