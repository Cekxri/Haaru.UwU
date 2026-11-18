/* SPDX-License-Identifier: Apache-2.0 */
package android.telephony;
public final class TelephonyManager {
    public final int[] states = new int[8];
    public boolean fail;
    public int calls;
    public int getSimState(int slot) {
        calls++;
        if (fail) throw new IllegalStateException("SIM state unavailable");
        return states[slot];
    }
}
