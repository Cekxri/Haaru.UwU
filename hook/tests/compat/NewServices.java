package compat;
import android.telephony.SubscriptionInfo;
import android.os.PersistableBundle;
import java.util.List;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
public final class NewServices {
    public interface ExtraAidl { SubscriptionInfo getRecentlyActiveSubscriptionInfo(int id, String caller); }
    public interface MissingAidl { SubscriptionInfo getNotImplementedSubscriptionInfo(int id); }
    public static class Service extends SubscriptionManagerService implements ExtraAidl {
        public SubscriptionInfo getRecentlyActiveSubscriptionInfo(int id, String caller) { return info; }
        public List<SubscriptionInfo> getFutureSubscriptionInfoList(String caller, int flags) { return list; }
    }
    public static class Carrier extends com.android.phone.CarrierConfigLoader {
        public PersistableBundle getConfigSubsetForSubId(int id, String[] keys, String caller) { return result; }
    }
}
