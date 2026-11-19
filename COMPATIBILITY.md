# Framework compatibility

Haaru reads the device's own Android interfaces. Production code has no phone model, manufacturer, SIM country, carrier label, subscription ID, or process ID exceptions.

The native engine still requires a working Zygisk API 4 provider, usable ART/LSPlant hooks, the Android phone service process, and the guarded SystemProperties methods. Android 10 is the minimum API, rather than a promise that every vendor framework implements the same interfaces.

## Reader discovery

Initialisation probes the available CarrierConfigLoader, SubscriptionController, and SubscriptionManagerService implementations. It collects relevant public readers from their class hierarchy and the device's AIDL interfaces. Older attribution signatures, newer feature arguments, inherited readers, and additional SubscriptionInfo readers are discovered from reflection. A missing exposed implementation or failed hook prevents complete coverage.

CarrierConfig readers must expose a provable subscription scope. Subset key-array positions come from each method's signature. The engine does not guess a SIM scope for an unfamiliar CarrierConfig interface.

## Private response copies

CarrierConfig responses are copied before changing country/name keys. SubscriptionInfo uses an available copying Builder, or Parcelable/CREATOR serialisation followed by changes to the independent copy's framework fields. The copy must be a different object of the same class with unchanged subscription and slot identity. Requested getter values are checked after copying.

Copy capabilities are detected separately for country and carrier name. A requested field combination must have a complete copying path before global property publication. Unsupported fields remain unavailable; the engine does not mutate a subscription database, eSIM, or the original returned object to compensate.

## Confirmation

Native acknowledgements include `requiredHooks`, `coverageComplete`, `supportsCountry`, and `supportsCarrierName`. Installed hooks must exactly match the discovered reader contract. Controller status and the Rust card observer also require support for every requested field, current boot/configuration identity, and a fresh engine receipt. There is no fixed thirteen/fourteen-hook success rule.

## Validation limits

Host fixtures cover older one-attribution interfaces without a Builder, intermediate feature-aware interfaces with inherited readers, newer extended AIDL/class readers, invalid Parcelable copies, and partially supported fields. These exercise the real reflection and copying code. Host tests and compilation do not establish compatibility with every ROM's ART build, hidden APIs, or telephony process placement. Unsupported frameworks report a concrete initialisation or coverage failure.
