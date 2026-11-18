/* SPDX-License-Identifier: Apache-2.0 */
package android.os;
import java.util.LinkedHashMap;
import java.util.Map;
/** Host fixture: put methods mutate only this returned Java object. */
public final class PersistableBundle {
    public final Map<String, Object> values = new LinkedHashMap<String, Object>();
    public int puts;
    public PersistableBundle() {}
    public PersistableBundle(PersistableBundle source) { values.putAll(source.values); }
    public boolean isEmpty() { return values.isEmpty(); }
    public void putString(String key, String value) { values.put(key, value); puts++; }
    public void putBoolean(String key, boolean value) { values.put(key, value); puts++; }
}
