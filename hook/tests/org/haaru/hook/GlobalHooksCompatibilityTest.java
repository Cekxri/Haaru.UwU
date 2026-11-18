/* SPDX-License-Identifier: Apache-2.0 */
package org.haaru.hook;

import compat.*;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;
import java.lang.reflect.Method;
import java.util.*;

/** Actual reflection/copy dispatch across independent old, middle, new and partial framework shapes. */
public final class GlobalHooksCompatibilityTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final class Loader extends ClassLoader {
        final Map<String, Class<?>> classes = new HashMap<String, Class<?>>();
        Loader() { super(GlobalHooksCompatibilityTest.class.getClassLoader()); }
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (classes.containsKey(name)) { Class<?> type=classes.get(name); if (type == null) throw new ClassNotFoundException(name); return type; }
            return super.loadClass(name);
        }
    }
    private static final class Installer implements GlobalHooks.Installer {
        final Map<String, GlobalHooks.Callback> callbacks = new LinkedHashMap<String, GlobalHooks.Callback>();
        String refused;
        public boolean install(Method target, GlobalHooks.Callback callback) {
            if (target.getName().equals(refused)) return false;
            callback.backup=target; callbacks.put(target.getName(),callback); return true;
        }
        Object invoke(String name, Object receiver, Object... values) throws Throwable {
            Object[] args=new Object[values.length+1]; args[0]=receiver; System.arraycopy(values,0,args,1,values.length);
            return callbacks.get(name).callback(args);
        }
    }
    private static Loader loader(Class<?> carrier, Class<?> service, Class<?> info, Class<?> carrierAidl, Class<?> subAidl, boolean legacy) {
        Loader loader=new Loader(); loader.classes.put("com.android.phone.CarrierConfigLoader",carrier);
        loader.classes.put("com.android.internal.telephony.subscription.SubscriptionManagerService",legacy ? null : service);
        loader.classes.put("com.android.internal.telephony.SubscriptionController",legacy ? service : null);
        loader.classes.put("android.telephony.SubscriptionInfo",info);
        loader.classes.put("com.android.internal.telephony.ICarrierConfigLoader",carrierAidl);
        loader.classes.put("com.android.internal.telephony.ISub",subAidl); return loader;
    }
    private static Installer initialize(Loader loader, int expectedReaders) throws Throwable {
        GlobalHooks.resetForTests(); Installer installer=new Installer(); GlobalHooks.installer=installer;
        check(GlobalHooks.initializeWithLoader(loader)==expectedReaders,"every exposed reader in the fixture is discovered");
        check(GlobalHooks.getRequiredHooks()==expectedReaders+1 && GlobalHooks.isCoverageComplete(),"adaptive coverage includes the attach hook");
        return installer;
    }
    private static void configure(String iso, String name) {
        GlobalHooks.configure(new int[]{10},new int[]{0},new String[]{iso},new String[]{name});
    }
    private static SubscriptionInfo modern() { return new SubscriptionInfo(10,0,"tw","Original","Display",4,"fixture-id",true,"466","01"); }
    private static void olderParcelCopies() throws Throwable {
        Installer installer=initialize(loader(LegacyServices.Carrier.class,LegacyServices.SubscriptionController.class,LegacyInfo.class,
                LegacyServices.CarrierAidl.class,LegacyServices.SubAidl.class,true),4);
        check(GlobalHooks.supportsCountry() && GlobalHooks.supportsCarrierName(),"older Parcelable/private-field shape supports both requested fields");
        configure("US","Cafe");
        LegacyInfo original=new LegacyInfo(10,0,"tw","Original","User name",7,"fixture-id",true,"466","01");
        LegacyServices.SubscriptionController service=new LegacyServices.SubscriptionController(); service.info=original;
        LegacyInfo copy=(LegacyInfo)installer.invoke("getActiveSubscriptionInfo",service,10,"caller");
        check(copy!=original && copy.getCountryIso().equals("us") && copy.getCarrierName().toString().equals("Cafe"),"legacy public reader receives a private Parcelable copy");
        check(copy.getDisplayName().toString().equals("Cafe") && copy.getNameSource()==7 && copy.isEmbedded()
                && copy.getIccId().equals("fixture-id") && copy.getMcc().equals("466") && copy.getMnc().equals("01"),"full serialization preserves unrelated metadata");
        check(original.getCountryIso().equals("tw") && original.getCarrierName().toString().equals("Original")
                && original.getDisplayName().toString().equals("User name"),"no setter or private field write touches the original SIM object");
        service.list=Arrays.asList(original,null);
        List<?> copies=(List<?>)installer.invoke("getAllSubInfoList",service,"caller");
        check(copies!=service.list && copies.get(0)!=original && copies.get(1)==null,"older one-string list API preserves null entries");
        LegacyInfo.returnOriginal=original;
        check(installer.invoke("getActiveSubscriptionInfo",service,10,"caller")==original && !GlobalHooks.isHealthy(),"a broken CREATOR returning its original is rejected before mutation");
        check(original.getCountryIso().equals("tw") && original.getDisplayName().toString().equals("User name"),"rejected Creator leaves all original fields untouched");
        LegacyInfo.returnOriginal=null;
    }
    private static void middleInheritedReaders() throws Throwable {
        Installer installer=initialize(loader(MidServices.Carrier.class,MidServices.Service.class,SubscriptionInfo.class,
                MidServices.CarrierAidl.class,MidServices.SubAidl.class,false),5);
        configure("GB",""); MidServices.Service service=new MidServices.Service(); service.info=modern(); service.list=Arrays.asList(service.info);
        List<?> copies=(List<?>)installer.invoke("getAllSubInfoList",service,"caller");
        check(((SubscriptionInfo)copies.get(0)).country.equals("gb"),"inherited public list reader is covered");
        copies=(List<?>)installer.invoke("getActiveSubscriptionInfoList",service,"caller","feature");
        check(((SubscriptionInfo)copies.get(0)).country.equals("gb"),"middle two-string list signature is discovered without a version table");
    }
    private static void newerExtraReaders() throws Throwable {
        Installer installer=initialize(loader(NewServices.Carrier.class,NewServices.Service.class,SubscriptionInfo.class,null,NewServices.ExtraAidl.class,false),16);
        configure("US","Cafe"); NewServices.Service service=new NewServices.Service(); service.info=modern(); service.list=Arrays.asList(service.info);
        check(((SubscriptionInfo)installer.invoke("getRecentlyActiveSubscriptionInfo",service,10,"caller")).country.equals("us"),"new exposed AIDL reader is covered automatically");
        check(((SubscriptionInfo)((List<?>)installer.invoke("getFutureSubscriptionInfoList",service,"caller",3)).get(0)).carrier.toString().equals("Cafe"),"new public class reader is covered automatically");
        NewServices.Carrier carrier=new NewServices.Carrier(); carrier.result=new PersistableBundle(); carrier.result.values.put("sim_country_iso_override_string","tw");
        PersistableBundle copy=(PersistableBundle)installer.invoke("getConfigSubsetForSubId",carrier,10,new String[]{"sim_country_iso_override_string"},"caller");
        check(copy.values.get("sim_country_iso_override_string").equals("us"),"subset key-array position follows the exposed signature");
        Loader missing=loader(NewServices.Carrier.class,NewServices.Service.class,SubscriptionInfo.class,null,NewServices.MissingAidl.class,false);
        GlobalHooks.resetForTests(); GlobalHooks.installer=new Installer();
        try { GlobalHooks.initializeWithLoader(missing); throw new AssertionError("Missing AIDL reader accepted"); }
        catch (NoSuchMethodException expected) { checks++; }
        check(!GlobalHooks.isCoverageComplete(),"missing exposed method cannot become complete coverage");
        GlobalHooks.resetForTests(); Installer refused=new Installer(); refused.refused="getFutureSubscriptionInfoList"; GlobalHooks.installer=refused;
        try { GlobalHooks.initializeWithLoader(loader(NewServices.Carrier.class,NewServices.Service.class,SubscriptionInfo.class,null,NewServices.ExtraAidl.class,false)); throw new AssertionError("Unhooked reader accepted"); }
        catch (IllegalStateException expected) { checks++; }
        check(!GlobalHooks.isCoverageComplete() && GlobalHooks.getError().contains("getFutureSubscriptionInfoList"),"partial installation retains the exact uncovered reader");
    }
    private static void unsupportedFieldsFailClosed() throws Throwable {
        Installer installer=initialize(loader(LegacyServices.Carrier.class,PartialInfo.Service.class,PartialInfo.class,LegacyServices.CarrierAidl.class,PartialInfo.Aidl.class,true),2);
        check(GlobalHooks.supportsCountry() && !GlobalHooks.supportsCarrierName(),"capabilities follow actual copy operations rather than SDK number");
        configure("JP",""); PartialInfo.Service service=new PartialInfo.Service(); service.info=new PartialInfo(10,0,"tw","Original","metadata");
        PartialInfo copy=(PartialInfo)installer.invoke("getActiveSubscriptionInfo",service,10,"caller");
        check(copy.getCountryIso().equals("jp") && copy.metadata().equals("metadata") && service.info.getCountryIso().equals("tw"),"supported country copy preserves unrelated fields and original object");
        try { configure("US","Cafe"); throw new AssertionError("Unsupported name accepted"); }
        catch (UnsupportedOperationException expected) { checks++; }
        check(!GlobalHooks.supportsProfile("US","Cafe"),"unsupported combination is rejected before a property overlay can be written");
    }
    public static void main(String[] args) throws Throwable {
        GlobalHooks.Installer savedInstaller=GlobalHooks.installer; GlobalHooks.Caller savedCaller=GlobalHooks.caller; GlobalHooks.OriginalScope savedScope=GlobalHooks.originalScope;
        try {
            GlobalHooks.caller=new GlobalHooks.Caller() { public GlobalHooks.Identity capture() { return new GlobalHooks.Identity(12345,false); } };
            GlobalHooks.originalScope=new GlobalHooks.OriginalScope() { public void enter(boolean active) {} };
            olderParcelCopies(); middleInheritedReaders(); newerExtraReaders(); unsupportedFieldsFailClosed();
            System.out.println("GlobalHooks adaptive framework contract: "+checks+" checks passed (no ART/device or universal-ROM claim)");
        } finally { LegacyInfo.returnOriginal=null; GlobalHooks.resetForTests(); GlobalHooks.installer=savedInstaller; GlobalHooks.caller=savedCaller; GlobalHooks.originalScope=savedScope; }
    }
}
