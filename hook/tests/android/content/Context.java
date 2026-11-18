/* SPDX-License-Identifier: Apache-2.0 */
package android.content;
public class Context {
    private final ClassLoader loader;
    public Object service;
    public final java.util.Map<Class<?>, Object> services = new java.util.HashMap<Class<?>, Object>();
    public Context(ClassLoader loader) { this.loader = loader; }
    public ClassLoader getClassLoader() { return loader; }
    public Object getSystemService(Class<?> type) { return services.containsKey(type) ? services.get(type) : service; }
}
