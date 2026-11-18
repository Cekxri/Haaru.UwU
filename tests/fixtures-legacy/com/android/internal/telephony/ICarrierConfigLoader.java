package com.android.internal.telephony;
import android.os.IBinder;
import android.os.PersistableBundle;

/** Android 10 shape: no feature getter and no persistent boolean. */
public interface ICarrierConfigLoader {
    PersistableBundle getConfigForSubId(int subId, String callingPackage);
    void overrideConfig(int subId, PersistableBundle values);
    final class State {
        public final PersistableBundle configuration = new PersistableBundle();
        public PersistableBundle lastWrite;
        public int reads, writes;
        public RuntimeException failure;
    }
    final class Stub {
        public static IBinder newBinder(State state) { return new Handle(state); }
        public static ICarrierConfigLoader asInterface(IBinder binder) { return new Proxy(((Handle)binder).state); }
        private static final class Handle implements IBinder {
            final State state; Handle(State s) { state = s; }
        }
        private static final class Proxy implements ICarrierConfigLoader {
            final State state; Proxy(State s) { state = s; }
            public PersistableBundle getConfigForSubId(int id, String pkg) {
                if (pkg != null) throw new AssertionError("Root attribution must be null");
                state.reads++; if (state.failure != null) throw state.failure;
                return new PersistableBundle(state.configuration);
            }
            public void overrideConfig(int id, PersistableBundle bundle) {
                state.writes++; throw new AssertionError("Read-only Haaru must never invoke a legacy setter");
            }
        }
    }
}

