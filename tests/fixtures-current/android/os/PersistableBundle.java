package android.os;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class PersistableBundle {
    private final Map<String, Object> values = new LinkedHashMap<String, Object>();
    public PersistableBundle() {}
    public PersistableBundle(PersistableBundle other) { values.putAll(other.values); }
    public void putString(String key, String value) { values.put(key, value); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public String getString(String key) { return (String) values.get(key); }
    public boolean getBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public Object get(String key) { return values.get(key); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public Set<String> keySet() { return new LinkedHashSet<String>(values.keySet()); }
    public void putAll(PersistableBundle other) { values.putAll(other.values); }
}

