/* SPDX-License-Identifier: Apache-2.0 */
package android.app;
/** Host-only shape for application publication; never included in hook.dex. */
public class ActivityThread {
    public static Object application;
    public static Object currentApplication() { return application; }
}
