package android.os;

import java.util.HashMap;
import java.util.Map;

/** Android BaseBundle.getInt returns the supplied default for absent/wrong-typed values. */
public final class Bundle {
    private final Map<String, Object> values = new HashMap<String, Object>();
    public void putInt(String key, int value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public int getInt(String key, int fallback) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback; }
    public Bundle copy() { Bundle out = new Bundle(); out.values.putAll(values); return out; }
}
