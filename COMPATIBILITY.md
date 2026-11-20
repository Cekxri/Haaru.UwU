# Framework compatibility

0.8.1 is a public preview that continues the native design published in 0.8.0. In the 0.8.0 checks, an Android API 33 emulator accelerated by an RTX 3060 compiled the scene shaders and rendered all four screens in a GLES 3.1 context. Handset frame timing and production phone engine validation are pending.

Haaru reads the device's own Android interfaces. Production code has no phone model, manufacturer, SIM country, carrier label, subscription ID, or process ID exceptions.

The native engine still requires a working Zygisk API 4 provider, usable ART/LSPlant hooks, the Android phone service process, and the guarded SystemProperties methods. Android 10 is the minimum API, rather than a promise that every vendor framework implements the same interfaces.

The engine preserves the original file-descriptor list and verifies the same socket and protocol after specialisation. An `exemptFd` API result alone does not establish whether the socket survived. A failed socket or protocol check remains a concrete engine failure.

## Native interface

The App uses Miuix controls, menus and dialogue boxes. Earlier `MATERIAL3` appearance values remain readable for compatibility, but the interface renders Miuix and saves `style=MIUIX`. MaterialKolor material-color-utilities 6.0.0 generates independent colour roles: Expressive, Vibrant, Tonal Spot and Neutral use `SPEC_2025`, while CMF uses `SPEC_2026`. This colour choice does not select a different set of controls.

Fresh installs use Violet with Expressive colours and follow the system’s light or dark appearance. On Android 12+, enabling Monet reads a seed from the system tonal palette and regenerates the roles using the selected style. An explicitly saved Monet switch retains its value. Older preference objects containing other settings keep the former on-by-default behaviour if they lack `dynamicColour`. An unavailable wallpaper seed falls back to the manual palette. Manual mode retains 15 palettes and an HSV colour picker, with the same five colour styles. Missing `colourStyle` preferences default to `EXPRESSIVE`; App encoding, Java validation and Rust migration preserve supported values. Glass navigation uses Backdrop and attributed public catalogue adaptations. CafeColours distributes the generated roles into low-chroma surfaces, readable text and restrained accents, then supplies Miuix, Backdrop, models and lighting. The navigation dock draws the selected text and icon above the glass effect to preserve legibility. Native pixel samples confirmed selected label contrast on all three compact tabs in light and dark themes. The emulator also exercised a manual Mint palette and the Vibrant style.

The App keeps plan controls separate from a persistent four-model GLES3 scene. TextureView/EGL holds procedural meshes, morph coefficients, spring input and depth-dependent scrolling across home, tools, settings and About. GGX/Smith/Schlick shading and scene and back-depth framebuffers provide the material pipeline. Sculptures refract their GL scene, while Backdrop samples the native interface. The high-quality emulator run reported active depth of field and surface emission.

About uses the current theme and scene, with versions, the author, real project links and credits. Bundled licence texts, including Manrope OFL, are read on background IO and remain available offline. Manrope is packaged locally for Latin text, with system CJK fallback. The revised dock build returned from About to Settings in the native preview. An earlier 0.8.0 build also opened the full bundled Manrope licence and returned to the credits list. The native preview report records the scope of layout and interaction checks.

## Plan-editing semantics

For an active profile belonging to the same SIM, empty `set` fields preserve the saved country/name. Restore SIM (`reset`) stops the whole profile and keeps its saved values; `forget` removes that profile. Loading an active plan or clearing an unsaved draft changes RAM only. `subscriptions.country` prefers the same-boot original shadow but may fall back to the system getter, so the UI calls it the reported SIM country rather than an unconditionally original value.

In 0.8.1, the app includes the observed SIM selection token when saving or reapplying a single SIM plan. The Controller checks it against the current boot, slot, subscription ID and SIM identity it can observe. If Android provides only a weak identity and reuses both the slot and subscription ID after a swap in the same boot, the module may be unable to distinguish the cards. Reapply Saved Plans also re-enables stopped plans. The engine checks each plan against the observed SIM before applying it.

## Rendering and scheduling

The scene requires a usable GLES 3 context. Choreographer follows the display vsync with no hard 120 fps cap. The app can request a higher refresh rate at the same resolution, subject to Android selecting a supported mode. Quality tiers reduce framebuffer, mesh and sampling costs. Power saving and thermal conditions may reduce the submission rate. Reduced motion holds the scene still, and the render loop pauses when the app is in the background, the stage is off screen, or its surface is lost. The 0.8.0 run on a 60 Hz emulator recorded background pause and resume, about 57.5–60 Hz warm submissions and roughly 0.63–2.99 ms of CPU submission time. Handset presentation timing and thermal behaviour need separate measurements.

## Reader discovery

Initialisation probes the available CarrierConfigLoader, SubscriptionController, and SubscriptionManagerService implementations. It collects relevant public readers from their class hierarchy and the device's AIDL interfaces. Older attribution signatures, newer feature arguments, inherited readers, and additional SubscriptionInfo readers are discovered from reflection. A missing exposed implementation or failed hook prevents complete coverage.

CarrierConfig readers must expose a provable subscription scope. Subset key-array positions come from each method's signature. The engine does not guess a SIM scope for an unfamiliar CarrierConfig interface.

## Private response copies

CarrierConfig responses are copied before changing country/name keys. SubscriptionInfo uses an available copying Builder, or Parcelable/CREATOR serialisation followed by changes to the independent copy's framework fields. The copy must be a different object of the same class with unchanged subscription and slot identity. Requested getter values are checked after copying.

Copy capabilities are detected separately for country and carrier name. A requested field combination must have a complete copying path before global property publication. Unsupported fields remain unavailable; the engine does not mutate a subscription database, eSIM, or the original returned object to compensate.

## Confirmation

Native acknowledgements include `requiredHooks`, `coverageComplete`, `supportsCountry`, and `supportsCarrierName`. Installed hooks must exactly match the discovered reader contract. Controller status and the Rust card observer also require support for every requested field, current boot/configuration identity, and a fresh engine receipt. The app refreshes status in the foreground and expires an old status or acknowledgement. An absent or unready SIM or a failed enabled plan prevents full confirmation.

For a runtime restore, the engine requires a successful clear of the Java response plan and readbacks of the restored values of both temporary properties. Failure in one part still allows the other restore steps to run. The app reports a combined carrier name byte limit separately when the resulting list of names exceeds the capacity of Android's shared operator property.

## Validation limits

Host fixtures cover older one-attribution interfaces without a Builder, intermediate feature-aware interfaces with inherited readers, newer extended AIDL/class readers, invalid Parcelable copies, and partially supported fields. These exercise the real reflection and copying code. Host tests and compilation do not establish compatibility with every ROM's ART build, hidden APIs, or telephony process placement. Unsupported frameworks report a concrete initialisation or coverage failure.
