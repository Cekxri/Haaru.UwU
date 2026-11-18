package com.android.internal.telephony;

import android.os.IBinder;
import android.os.PersistableBundle;

/** Deliberately exposes the current public AIDL shape with a PRIVATE proxy implementation. */
public interface ICarrierConfigLoader {
    PersistableBundle getConfigForSubIdWithFeature(int subId, String callingPackage, String feature);
    PersistableBundle getConfigForSubId(int subId, String callingPackage);
    void overrideConfig(int subId, PersistableBundle values, boolean persistent);

    final class State {
        public PersistableBundle configuration = new PersistableBundle();
        public PersistableBundle lastWrite;
        public int featureReads, legacyReads, writes, lastReadSubId, lastWriteSubId;
        public String lastPackage = "not-called";
        public String lastFeature = "not-called";
        public boolean persistent = true;
        public RuntimeException readFailure, writeFailure;
        public boolean returnNull;
    }

    final class Stub {
        private Stub() {}
        public static IBinder newBinder(State state) { return new Handle(state); }
        public static ICarrierConfigLoader asInterface(IBinder binder) {
            return new Proxy(((Handle) binder).state);
        }
        private static final class Handle implements IBinder {
            final State state;
            Handle(State value) { state = value; }
        }
        private static final class Proxy implements ICarrierConfigLoader {
            final State state;
            Proxy(State value) { state = value; }
            @Override public PersistableBundle getConfigForSubIdWithFeature(
                    int subId, String callingPackage, String feature) {
                state.featureReads++;
                state.lastReadSubId = subId;
                state.lastPackage = callingPackage;
                state.lastFeature = feature;
                if (state.readFailure != null) throw state.readFailure;
                return state.returnNull ? null : new PersistableBundle(state.configuration);
            }
            @Override public PersistableBundle getConfigForSubId(int subId, String callingPackage) {
                state.legacyReads++;
                throw new AssertionError("Current adapter should prefer getConfigForSubIdWithFeature");
            }
            @Override public void overrideConfig(
                    int subId, PersistableBundle values, boolean persistent) {
                state.writes++;
                throw new AssertionError("Read-only Haaru must never invoke a platform setter");
            }
        }
    }
}

