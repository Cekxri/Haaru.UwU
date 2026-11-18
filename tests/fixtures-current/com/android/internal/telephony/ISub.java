package com.android.internal.telephony;

import android.os.IBinder;
import android.telephony.SubscriptionInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ISub {
    int[] getActiveSubIdList(boolean visibleOnly);
    List<SubscriptionInfo> getActiveSubscriptionInfoList(
            String callingPackage, String feature, boolean allProfiles);
    SubscriptionInfo getActiveSubscriptionInfo(int subId, String callingPackage, String feature);
    int getSlotIndex(int subId);
    int getPhoneId(int subId);

    final class State {
        public int[] activeIds = new int[0];
        public final List<SubscriptionInfo> information = new ArrayList<SubscriptionInfo>();
        public final Map<Integer, Integer> slots = new HashMap<Integer, Integer>();
        public final Map<Integer, Integer> phones = new HashMap<Integer, Integer>();
        public RuntimeException phoneFailure, slotFailure;
        public int phoneReads;
        public boolean visibleOnly = true;
        public String lastPackage = "not-called", lastFeature = "not-called";
        public RuntimeException idFailure, informationFailure;
        public boolean nullInformation;
        public RuntimeException singleInformationFailure;
        public boolean nullSingleInformation;
        public SubscriptionInfo singleInformationOverride;
        public int listReads, singleReads;
    }

    final class Stub {
        private Stub() {}
        public static IBinder newBinder(State state) { return new Handle(state); }
        public static ISub asInterface(IBinder binder) { return new Proxy(((Handle) binder).state); }
        private static final class Handle implements IBinder {
            final State state;
            Handle(State value) { state = value; }
        }
        private static final class Proxy implements ISub {
            final State state;
            Proxy(State value) { state = value; }
            @Override public int[] getActiveSubIdList(boolean visibleOnly) {
                state.visibleOnly = visibleOnly;
                if (state.idFailure != null) throw state.idFailure;
                return state.activeIds == null ? null : state.activeIds.clone();
            }
            @Override public List<SubscriptionInfo> getActiveSubscriptionInfoList(
                    String callingPackage, String feature, boolean allProfiles) {
                state.lastPackage = callingPackage;
                state.lastFeature = feature;
                state.listReads++;
                if (state.informationFailure != null) throw state.informationFailure;
                return state.nullInformation ? null : new ArrayList<SubscriptionInfo>(state.information);
            }
            @Override public SubscriptionInfo getActiveSubscriptionInfo(
                    int id, String callingPackage, String feature) {
                state.lastPackage = callingPackage;
                state.lastFeature = feature;
                state.singleReads++;
                if (state.informationFailure != null) throw state.informationFailure;
                if (state.singleInformationFailure != null) throw state.singleInformationFailure;
                if (state.nullSingleInformation) return null;
                if (state.singleInformationOverride != null) return state.singleInformationOverride;
                for (SubscriptionInfo info : state.information) {
                    if (info.getSubscriptionId() == id) return info;
                }
                return null;
            }
            @Override public int getSlotIndex(int id) {
                if (state.slotFailure != null) throw state.slotFailure;
                Integer value = state.slots.get(id);
                if (value != null) return value.intValue();
                for (SubscriptionInfo info : state.information) {
                    if (info.getSubscriptionId() == id) return info.getSimSlotIndex();
                }
                return -1;
            }
            @Override public int getPhoneId(int id) {
                state.phoneReads++;
                if (state.phoneFailure != null) throw state.phoneFailure;
                Integer value = state.phones.get(id);
                return value == null ? getSlotIndex(id) : value.intValue();
            }
        }
    }
}

