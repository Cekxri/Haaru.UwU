/* SPDX-License-Identifier: Apache-2.0 */
package com.android.phone;
import android.os.PersistableBundle;
/** Host fixture: exposes precisely the Android 16 Binder signatures. */
public class CarrierConfigLoader {
    public PersistableBundle result;
    public Throwable failure;
    public Runnable onCall;
    public Object[] forwarded;
    public int calls;
    private PersistableBundle read(Object... args) throws Throwable {
        calls++; forwarded = args;
        if (onCall != null) onCall.run();
        if (failure != null) throw failure;
        return result;
    }
    public PersistableBundle getConfigForSubId(int id, String pkg) throws Throwable { return read(id, pkg); }
    public PersistableBundle getConfigForSubIdWithFeature(int id, String pkg, String feature) throws Throwable { return read(id, pkg, feature); }
    public PersistableBundle getConfigSubsetForSubIdWithFeature(int id, String pkg, String feature, String[] keys) throws Throwable { return read(id, pkg, feature, keys); }
}
