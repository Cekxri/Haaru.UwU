/* SPDX-License-Identifier: Apache-2.0 */
#include <sys/types.h>
#include <zygisk.hpp>
#include <lsplant.hpp>
#include <dobby.h>
#include <nlohmann/json.hpp>
#include "art_symbols.hpp"
#include "policy.hpp"
#include "global_properties.hpp"
#include "engine_diagnostics.hpp"
#include "companion_connection.hpp"
#include <android/api-level.h>
#include <android/log.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
using Json = nlohmann::json;
constexpr char kModule[] = "/data/adb/modules/haaru";
constexpr const char* kKeys[] = {"gsm.sim.operator.iso-country", "gsm.sim.operator.alpha"};
constexpr uint32_t kLimit = 65536, kDexLimit = 262144;
using GetString = jstring (*)(JNIEnv*, jclass, jstring, jstring);
using GetSingle = jstring (*)(JNIEnv*, jclass, jstring);
using GetHandle = jstring (*)(JNIEnv*, jclass, jlong);
using SetString = void (*)(JNIEnv*, jclass, jstring, jstring);
GetString originalGet = nullptr;
GetSingle originalSingle = nullptr;
GetHandle originalHandle = nullptr;
SetString originalSet = nullptr;
JavaVM* javaVm = nullptr;
jclass binderType = nullptr, hooksType = nullptr;
jmethodID binderUid = nullptr, initializeId = nullptr, configureId = nullptr, healthId = nullptr, healthErrorId = nullptr, notifyId = nullptr, validateId = nullptr;
jmethodID requiredId = nullptr, coverageId = nullptr, countrySupportId = nullptr, nameSupportId = nullptr, profileSupportId = nullptr;
jobject phoneContext = nullptr, dexLoader = nullptr;
std::vector<uint8_t> dex;
std::atomic<int> channel{-1};
haaru::CompanionSocketIdentity channelIdentity;
std::atomic<bool> fdExemptionReported{false}, fdIgnoreListAvailable{false}, connectionValidated{false};
haaru::ArtSymbols art;
std::atomic<bool> guardsReady{false}, engineReady{false}, baselineReady{false}, started{false};
std::atomic<bool> recoveryRequired{false};
std::atomic<int> hookCount{0};
std::atomic<int> requiredHookCount{0};
std::atomic<bool> coverageReady{false}, countrySupported{false}, nameSupported{false};
thread_local unsigned int internalDepth = 0;
std::mutex stateMutex, publisherMutex, channelMutex, companionMutex, diagnosticMutex;
std::condition_variable wake;
std::string baseCountry, baseName;
std::string verifiedPhoneBoot;
std::vector<haaru::PropertyProfile> published;
uint64_t baseRevision = 0;
bool baseVerified = false;
std::string bootstrapStage = "companion", bootstrapError;

std::string readProperty(const char* key);
int64_t timestampMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}
int deviceSdk() { return haaru::resolveDeviceSdk(android_get_device_api_level(), readProperty("ro.build.version.sdk")); }
void setStage(std::string_view stage) {
    std::lock_guard lock(diagnosticMutex);
    if (bootstrapError.empty()) bootstrapStage = haaru::boundedDiagnostic(stage, 96);
}
void recordFailure(std::string_view stage, std::string_view error) {
    std::lock_guard lock(diagnosticMutex);
    if (bootstrapError.empty()) {
        bootstrapStage = haaru::boundedDiagnostic(stage, 96);
        bootstrapError = haaru::boundedDiagnostic(error);
    }
    engineReady = false;
}
std::pair<std::string, std::string> diagnostic() {
    std::lock_guard lock(diagnosticMutex);
    return {bootstrapStage, bootstrapError};
}
std::string bootId();
const std::string& phoneBootId() { return verifiedPhoneBoot; }
Json acknowledgement(bool ok, bool initialized, bool applied, std::string_view hash = {}, std::string_view error = {}) {
    auto [stage, failure] = diagnostic();
    bool failed = !failure.empty();
    int sdk = deviceSdk();
    if (failure.empty()) failure = std::string(error);
    if (sdk <= 0 && failure.empty()) failure = "Cannot determine Android SDK: NDK API=" +
        std::to_string(android_get_device_api_level()) + ", ro.build.version.sdk=" + haaru::boundedDiagnostic(readProperty("ro.build.version.sdk"), 48);
    auto ack = haaru::engineAck(ok && !failed, initialized && !failed, applied && !failed, hookCount.load(), sdk, phoneBootId(), hash, timestampMs(), getpid(), stage, failure,
        requiredHookCount.load(), coverageReady && !failed, countrySupported.load(), nameSupported.load());
    ack["fdExemptionReported"] = fdExemptionReported.load();
    ack["fdIgnoreListAvailable"] = fdIgnoreListAvailable.load();
    ack["connected"] = channel >= 0 && connectionValidated.load();
    return ack;
}

struct OwnFd { int value; ~OwnFd() { if (value >= 0) close(value); } };
haaru::CompanionSocketIdentity socketIdentity(int fd, int* failure = nullptr) {
    if (failure) *failure = 0;
    struct stat info{};
    int result;
    do { result = fstat(fd, &info); } while (result < 0 && errno == EINTR);
    if (result < 0 || !S_ISSOCK(info.st_mode)) {
        if (failure) *failure = result < 0 ? errno : ENOTSOCK;
        return {};
    }
    int type = 0; socklen_t size = sizeof(type);
    do { result = getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &size); } while (result < 0 && errno == EINTR);
    if (result < 0 || type != SOCK_STREAM) {
        if (failure) *failure = result < 0 ? errno : EPROTOTYPE;
        return {};
    }
    sockaddr_un peer{}; size = sizeof(peer);
    do { result = getpeername(fd, reinterpret_cast<sockaddr*>(&peer), &size); } while (result < 0 && errno == EINTR);
    if (result < 0 || peer.sun_family != AF_UNIX) {
        if (failure) *failure = result < 0 ? errno : EAFNOSUPPORT;
        return {};
    }
    return {static_cast<uint64_t>(info.st_dev), static_cast<uint64_t>(info.st_ino), type, true};
}
void closeChannel() {
    int fd = channel.exchange(-1);
    if (fd < 0) return;
    if (!channelIdentity.valid) { close(fd); return; } // Before retention, still in pre-specialisation.
    haaru::closeOwnedCompanion(channelIdentity, [fd] { return socketIdentity(fd); }, [fd] { close(fd); });
}
struct Internal { Internal() { ++internalDepth; } ~Internal() { --internalDepth; } };
struct LocalFrame {
    JNIEnv* env;
    bool valid;
    explicit LocalFrame(JNIEnv* value, int count = 64) : env(value), valid(value->PushLocalFrame(count) == JNI_OK) {}
    ~LocalFrame() { if (valid) env->PopLocalFrame(nullptr); }
};
bool transfer(int fd, void* data, size_t size, bool writing) {
    auto* bytes = static_cast<unsigned char*>(data);
    while (size) {
        ssize_t count = writing ? send(fd, bytes, size, MSG_NOSIGNAL) : read(fd, bytes, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        bytes += count; size -= static_cast<size_t>(count);
    }
    return true;
}
void timeout(int fd, int seconds) {
    timeval duration{seconds, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &duration, sizeof(duration));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &duration, sizeof(duration));
}
bool sendJson(int fd, const Json& value) {
    auto text = value.dump(-1, ' ', false, Json::error_handler_t::replace); uint32_t count = static_cast<uint32_t>(text.size());
    return count && count <= kLimit && transfer(fd, &count, sizeof(count), true) && transfer(fd, text.data(), text.size(), true);
}
bool readJson(int fd, Json& value) {
    uint32_t count = 0;
    if (!transfer(fd, &count, sizeof(count), false) || !count || count > kLimit) return false;
    std::string bytes(count, '\0');
    if (!transfer(fd, bytes.data(), bytes.size(), false)) return false;
    if (!haaru_json_valid(reinterpret_cast<const uint8_t*>(bytes.data()), bytes.size())) return false;
    value = Json::parse(bytes); return value.is_object();
}
std::vector<uint8_t> readAt(int directory, const char* name, uint32_t limit) {
    OwnFd file{openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW)};
    struct stat info{};
    if (file.value < 0 || fstat(file.value, &info) || !S_ISREG(info.st_mode) || info.st_size < 0 || info.st_size > limit) return {};
    std::vector<uint8_t> bytes(static_cast<size_t>(info.st_size));
    size_t position = 0;
    while (position < bytes.size()) {
        ssize_t count = read(file.value, bytes.data() + position, bytes.size() - position);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return {};
        position += static_cast<size_t>(count);
    }
    return bytes;
}
bool marked(int directory, const char* name) { struct stat info{}; return fstatat(directory, name, &info, AT_SYMLINK_NOFOLLOW) == 0; }
std::string bootId() {
    OwnFd file{open("/proc/sys/kernel/random/boot_id", O_RDONLY | O_CLOEXEC)};
    char bytes[128]{};
    ssize_t count = file.value < 0 ? -1 : read(file.value, bytes, sizeof(bytes) - 1);
    if (count <= 0) return {};
    std::string value(bytes, static_cast<size_t>(count));
    while (!value.empty() && (value.back() == '\n' || value.back() == '\r')) value.pop_back();
    return value;
}
std::string hash(const std::vector<uint8_t>& bytes) {
    return std::to_string(haaru_fnv1a64(bytes.data(), bytes.size()));
}
bool atomicAt(int directory, const char* name, const Json& value) {
    auto bytes = value.dump(-1, ' ', false, Json::error_handler_t::replace);
    std::string temporary = std::string(".") + name + "." + std::to_string(getpid()) + ".tmp";
    OwnFd file{openat(directory, temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600)};
    if (file.value < 0) return false;
    size_t position = 0;
    while (position < bytes.size()) {
        ssize_t count = write(file.value, bytes.data() + position, bytes.size() - position);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        position += static_cast<size_t>(count);
    }
    bool ok = position == bytes.size() && fsync(file.value) == 0;
    if (ok) ok = renameat(directory, temporary.c_str(), directory, name) == 0;
    unlinkat(directory, temporary.c_str(), 0); return ok;
}
bool validConfig(const Json& value, const std::string& verifiedBoot) {
    return haaru::rustValidRuntime(value, verifiedBoot);
}
bool validShadow(const Json& value, const std::string& verifiedBoot) {
    return haaru::rustValidShadow(value, verifiedBoot);
}
void companion(int fd) {
    std::lock_guard singlePhone(companionMutex);
    OwnFd socket{fd}; timeout(fd, 12);
    OwnFd module{open(kModule, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    if (module.value < 0) return;
    const auto currentBoot = bootId();
    Json savedShadow, lastAck;
    int phonePid = 0, sdk = deviceSdk();
    bool identified = false;
    std::string transportError = "Phone engine disconnected";
    try {
        Json request;
        if (!readJson(fd, request) || request.value("protocol", 0) != 2 || request.value("uid", 0) != 1001 ||
                request.value("process", std::string()) != "com.android.phone") return;
        identified = true;
        phonePid = request.value("phonePid", 0);
        if (phonePid <= 0) throw std::runtime_error("Companion greeting has no phone PID");
        sdk = haaru::resolveDeviceSdk(request.value("sdk", 0), readProperty("ro.build.version.sdk"));
        // ART setup and Application.attach can take longer than one runtime poll.
        timeout(fd, 60);
        lastAck = haaru::engineAck(false, false, false, 0, sdk, bootId(), "", timestampMs(), phonePid, "hook-dex", "");
        OwnFd bin{openat(module.value, "bin", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
        auto bytes = bin.value < 0 ? std::vector<uint8_t>{} : readAt(bin.value, "hook.dex", kDexLimit);
        if (bytes.size() < 112 || std::memcmp(bytes.data(), "dex\n", 4)) throw std::runtime_error("Companion cannot read a valid bin/hook.dex");
        OwnFd initialData{openat(module.value, "data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
        auto cached = initialData.value < 0 ? std::vector<uint8_t>{} : readAt(initialData.value, "global-shadow.json", 4096);
        Json shadow = cached.empty() ? Json{} : Json::parse(cached, nullptr, false);
        if (shadow.is_discarded()) shadow = Json{};
        if (validShadow(shadow, currentBoot)) savedShadow = shadow;
        auto pendingBytes = initialData.value < 0 ? std::vector<uint8_t>{} : readAt(initialData.value, "global-transaction.json", 8192);
        Json pending = pendingBytes.empty() ? Json{} : Json::parse(pendingBytes, nullptr, false);
        bool recovering = !pending.is_discarded() && pending.is_object() && pending.value("bootId", std::string()) == bootId();
        if (!sendJson(fd, Json{{"bootId", bootId()}, {"shadow", shadow}, {"recoveryRequired", recovering}})) throw std::runtime_error("Initial response failed");
        uint32_t length = static_cast<uint32_t>(bytes.size());
        if (!transfer(fd, &length, sizeof(length), true) || !transfer(fd, bytes.data(), bytes.size(), true)) throw std::runtime_error("DEX response failed");
        while (readJson(fd, request)) {
            OwnFd data{openat(module.value, "data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
            bool saved = false;
            bool prepared = false;
            auto phase = request.value("phase", std::string("snapshot"));
            if (data.value >= 0 && request.contains("shadow") && validShadow(request["shadow"], currentBoot)) {
                if (phase == "prepare") {
                    prepared = atomicAt(data.value, "global-transaction.json", Json{{"bootId", bootId()},
                        {"prior", savedShadow}, {"candidate", request["shadow"]}});
                    if (prepared) recovering = true;
                } else if (phase == "commit" || phase == "abort" || (phase == "snapshot" && !recovering)) {
                    saved = atomicAt(data.value, "global-shadow.json", request["shadow"]);
                    if (saved && phase != "snapshot") {
                        unlinkat(data.value, "global-transaction.json", 0); recovering = false;
                    }
                }
            }
            if (saved) savedShadow = request["shadow"];
            if (request.contains("ack") && request["ack"].is_object()) {
                lastAck = request["ack"];
                if (data.value >= 0) atomicAt(data.value, "global-status.json", lastAck);
                if (lastAck.value("initialized", false)) timeout(fd, 12);
            }
            bool active = data.value >= 0 && !marked(module.value, "disable") && !marked(module.value, "remove") &&
                !marked(module.value, "update");
            auto runtime = active ? readAt(data.value, "runtime.json", kLimit) : std::vector<uint8_t>{};
            Json config = runtime.empty() || !haaru_json_valid(runtime.data(), runtime.size()) ? Json{} : Json::parse(runtime, nullptr, false);
            if (config.is_discarded() || !validConfig(config, currentBoot)) config = Json{};
            if (!sendJson(fd, Json{{"active", active && !config.empty()}, {"config", config}, {"hash", runtime.empty() ? "" : hash(runtime)},
                {"shadowSaved", saved}, {"shadowPrepared", prepared}, {"recoveryRequired", recovering}})) {
                transportError = "Companion response failed: " + std::string(std::strerror(errno));
                break;
            }
        }
    } catch (const std::exception& failure) { transportError = haaru::boundedDiagnostic(failure.what()); }
    catch (...) { transportError = "Unknown companion failure"; }
    // The root companion remains available when phone is killed. Restore the
    // last acknowledged originals before a replacement phone can reconnect.
    if (!savedShadow.empty()) {
        __system_property_set(kKeys[0], savedShadow["countryList"].get<std::string>().c_str());
        __system_property_set(kKeys[1], savedShadow["nameList"].get<std::string>().c_str());
    }
    OwnFd data{openat(module.value, "data", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)};
    if (data.value >= 0 && identified) atomicAt(data.value, "global-status.json",
        haaru::disconnectedAck(lastAck, sdk, bootId(), timestampMs(), phonePid, transportError));
}

bool exchange(const Json& shadow, const Json& ack, Json& response, const char* phase = "snapshot") {
    std::lock_guard lock(channelMutex);
    if (channel < 0) return false;
    // Checkpoints also run on bootstrap failures, including failures before the
    // normal post-specialisation validation. Do not use a recycled FD there.
    if (channelIdentity.valid && !channelIdentity.matches(socketIdentity(channel))) {
        channel = -1;
        return false;
    }
    try {
        if (sendJson(channel, Json{{"shadow", shadow}, {"ack", ack}, {"phase", phase}}) && readJson(channel, response)) return true;
    } catch (...) {}
    closeChannel();
    return false;
}
// Consume the reply so a diagnostic checkpoint cannot desynchronise runtime requests.
void checkpoint() {
    Json response;
    (void)exchange(Json{}, acknowledgement(false, false, false), response, "diagnostic");
}

std::string readProperty(const char* key) {
    std::string value;
    const prop_info* property = __system_property_find(key);
    if (property) __system_property_read_callback(property, [](void* out, const char*, const char* bytes, uint32_t) {
        *static_cast<std::string*>(out) = bytes ? bytes : "";
    }, &value);
    return value;
}
int keyIndex(std::string_view name) { for (int i = 0; i < 2; i++) if (name == kKeys[i]) return i; return -1; }
std::string utf8(JNIEnv* env, jstring value, jsize limit = std::numeric_limits<jsize>::max()) {
    if (!value) return {};
    const jchar* bytes = env->GetStringChars(value, nullptr);
    if (!bytes) throw std::runtime_error("String access failed");
    std::string out;
    jsize length = std::min(env->GetStringLength(value), limit);
    for (jsize i = 0; i < length; i++) {
        uint32_t c = bytes[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 == length && length == limit) break;
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length && bytes[i + 1] >= 0xdc00 && bytes[i + 1] <= 0xdfff)
            c = 0x10000 + ((c - 0xd800) << 10) + bytes[++i] - 0xdc00;
        if (c < 0x80) out += static_cast<char>(c);
        else if (c < 0x800) { out += static_cast<char>(0xc0 | (c >> 6)); out += static_cast<char>(0x80 | (c & 63)); }
        else if (c < 0x10000) { out += static_cast<char>(0xe0 | (c >> 12)); out += static_cast<char>(0x80 | ((c >> 6) & 63)); out += static_cast<char>(0x80 | (c & 63)); }
        else { out += static_cast<char>(0xf0 | (c >> 18)); out += static_cast<char>(0x80 | ((c >> 12) & 63)); out += static_cast<char>(0x80 | ((c >> 6) & 63)); out += static_cast<char>(0x80 | (c & 63)); }
    }
    env->ReleaseStringChars(value, bytes); return out;
}
// Get the original Throwable before clearing it; later JNI lookups must never
// replace a useful bootstrap exception with an unrelated pending exception.
std::string takeJavaFailure(JNIEnv* env) {
    if (!env || !env->ExceptionCheck()) return {};
    auto failure = env->ExceptionOccurred();
    env->ExceptionClear();
    std::string result = "Java exception (Throwable text unavailable)";
    jclass type = nullptr;
    jstring message = nullptr;
    try {
        type = env->FindClass("java/lang/Throwable");
        auto toString = type ? env->GetMethodID(type, "toString", "()Ljava/lang/String;") : nullptr;
        if (toString && !env->ExceptionCheck()) message = static_cast<jstring>(env->CallObjectMethod(failure, toString));
        if (message && !env->ExceptionCheck()) result = haaru::boundedDiagnostic(utf8(env, message, 1024));
    } catch (...) {}
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (message) env->DeleteLocalRef(message);
    if (type) env->DeleteLocalRef(type);
    if (failure) env->DeleteLocalRef(failure);
    return result;
}
void recordJniFailure(JNIEnv* env, std::string_view reason) {
    auto [stage, prior] = diagnostic();
    auto javaError = takeJavaFailure(env);
    std::string error(reason);
    if (!javaError.empty()) error += ": " + javaError;
    recordFailure(stage, error);
}
void requireJni(JNIEnv* env, bool accepted, std::string_view reason) {
    if (!accepted || env->ExceptionCheck()) {
        auto javaError = takeJavaFailure(env);
        throw std::runtime_error(std::string(reason) + (javaError.empty() ? "" : ": " + javaError));
    }
}
void retainCompanionFd(JNIEnv* env, zygisk::Api* api, zygisk::AppSpecializeArgs* args) {
    int fd = channel.load();
    channelIdentity = socketIdentity(fd);
    if (!channelIdentity.valid) throw std::runtime_error("Companion did not provide a connected Unix stream socket");
    // A provider that accepted the request manages the exemption itself. Only
    // preserve the optional list directly when the provider did not accept it.
    fdExemptionReported = api->exemptFd(fd);
    fdIgnoreListAvailable = args->fds_to_ignore != nullptr;
    if (fdExemptionReported || !args->fds_to_ignore) return;
    auto prior = *args->fds_to_ignore;
    jsize count = prior ? env->GetArrayLength(prior) : 0;
    requireJni(env, count >= 0 && count <= 4096, "Invalid zygote FD retention list");
    std::vector<int> values(static_cast<size_t>(count));
    if (count) env->GetIntArrayRegion(prior, 0, count, values.data());
    requireJni(env, true, "Cannot read the zygote FD retention list");
    auto retained = haaru::companionFdList(values, fd);
    if (retained.size() == values.size()) return;
    auto replacement = env->NewIntArray(static_cast<jsize>(retained.size()));
    requireJni(env, replacement != nullptr, "Cannot allocate the companion FD retention list");
    env->SetIntArrayRegion(replacement, 0, static_cast<jsize>(retained.size()), retained.data());
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(replacement);
        requireJni(env, false, "Cannot write the companion FD retention list");
    }
    *args->fds_to_ignore = replacement;
    // The provider/zygote still owns and uses both JNI arguments until post.
    // Do not delete the original or replacement local reference here.
}
void validateRetainedCompanion() {
    int fd = channel.load();
    Json response;
    bool sameSocket = false;
    int identityError = 0;
    bool connected = haaru::validateCompanionConnection(channelIdentity,
        [fd, &sameSocket, &identityError] {
            auto actual = fd >= 0 ? socketIdentity(fd, &identityError) : haaru::CompanionSocketIdentity{};
            sameSocket = channelIdentity.matches(actual);
            // Zygote may have closed this number and another component reused
            // it. Discard our number without closing or using that object.
            if (!sameSocket) channel = -1;
            return actual;
        }, [&response] {
            return exchange(Json{}, acknowledgement(false, false, false), response, "diagnostic") &&
                response.contains("active") && response["active"].is_boolean() &&
                response.contains("config") && response["config"].is_object() &&
                response.contains("hash") && response["hash"].is_string() &&
                response.contains("recoveryRequired") && response["recoveryRequired"].is_boolean();
        });
    if (!connected) {
        __android_log_print(ANDROID_LOG_WARN, "HaaruEngine", "Companion validation failed (identity errno=%d, same socket=%d)",
            identityError, sameSocket ? 1 : 0);
        throw std::runtime_error(identityError == EACCES || identityError == EPERM ?
            "Companion socket metadata access denied after process specialisation" :
            sameSocket ? "Companion protocol round-trip failed after process specialisation" :
            "Companion socket did not survive process specialisation");
    }
    connectionValidated = true;
}
std::string hookError(JNIEnv* env, std::string_view fallback) {
    if (!hooksType || !healthErrorId) return std::string(fallback);
    auto value = static_cast<jstring>(env->CallStaticObjectMethod(hooksType, healthErrorId));
    if (env->ExceptionCheck()) {
        auto failure = takeJavaFailure(env);
        return std::string(fallback) + (failure.empty() ? "" : ": " + failure);
    }
    std::string error;
    try { error = haaru::boundedDiagnostic(utf8(env, value, 1024)); } catch (...) { (void)takeJavaFailure(env); }
    if (value) env->DeleteLocalRef(value);
    return error.empty() ? std::string(fallback) : error;
}
jstring javaString(JNIEnv* env, std::string_view value) {
    std::vector<jchar> out;
    for (size_t i = 0; i < value.size();) {
        uint32_t c = static_cast<unsigned char>(value[i++]), minimum = 0;
        unsigned extra = 0;
        if (c >= 0xc2 && c <= 0xdf) { c &= 31; extra = 1; minimum = 0x80; }
        else if (c >= 0xe0 && c <= 0xef) { c &= 15; extra = 2; minimum = 0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { c &= 7; extra = 3; minimum = 0x10000; }
        else if (c >= 0x80) return nullptr;
        for (unsigned n = 0; n < extra; n++) {
            if (i >= value.size()) return nullptr;
            uint32_t next = static_cast<unsigned char>(value[i++]);
            if ((next & 0xc0) != 0x80) return nullptr;
            c = (c << 6) | (next & 63);
        }
        if (c < minimum || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) return nullptr;
        if (c < 0x10000) out.push_back(static_cast<jchar>(c));
        else { c -= 0x10000; out.push_back(static_cast<jchar>(0xd800 | (c >> 10))); out.push_back(static_cast<jchar>(0xdc00 | (c & 1023))); }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}
std::string naturalValue(int index) { std::lock_guard lock(stateMutex); return index == 0 ? baseCountry : baseName; }
jint callingUid(JNIEnv* env, jclass) {
    if (!binderType || !binderUid) return -1;
    jint uid = env->CallStaticIntMethod(binderType, binderUid);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    return uid;
}
bool internallyTrusted(JNIEnv* env) {
    if (internalDepth) return true;
    auto uid = callingUid(env, nullptr); return uid < 0 || uid == 0 || uid == 1001;
}
jboolean trusted(JNIEnv* env, jclass) { return internallyTrusted(env) ? JNI_TRUE : JNI_FALSE; }
jstring shadowResult(JNIEnv* env, jstring key, jstring original, jstring fallback) {
    if (!key || !baselineReady || !internallyTrusted(env) || env->ExceptionCheck()) return original;
    try {
        int index = keyIndex(utf8(env, key));
        if (index < 0) return original;
        auto value = naturalValue(index);
        if (value.empty() && fallback) return fallback;
        auto copy = javaString(env, value); return copy ? copy : original;
    } catch (...) { return original; }
}
jstring getString(JNIEnv* env, jclass type, jstring key, jstring fallback) {
    auto value = originalGet ? originalGet(env, type, key, fallback) : fallback;
    return shadowResult(env, key, value, fallback);
}
jstring getSingle(JNIEnv* env, jclass type, jstring key) {
    auto value = originalSingle ? originalSingle(env, type, key) : nullptr;
    return shadowResult(env, key, value, nullptr);
}
jstring getHandle(JNIEnv* env, jclass type, jlong handle) {
    auto value = originalHandle ? originalHandle(env, type, handle) : nullptr;
    if (!handle || !baselineReady || !internallyTrusted(env) || env->ExceptionCheck()) return value;
    try {
        std::string name;
        __system_property_read_callback(reinterpret_cast<const prop_info*>(static_cast<uintptr_t>(handle)),
            [](void* out, const char* name, const char*, uint32_t) { *static_cast<std::string*>(out) = name ? name : ""; }, &name);
        int index = keyIndex(name);
        if (index >= 0) { auto copy = javaString(env, naturalValue(index)); if (copy) return copy; }
    } catch (...) {}
    return value;
}
bool configure(JNIEnv* env, const Json& plan);
Json shadowSnapshot();
void setString(JNIEnv* env, jclass type, jstring key, jstring value) {
    if (!originalSet) return;
    int index = -1; std::string incoming;
    try { index = keyIndex(utf8(env, key)); incoming = utf8(env, value); }
    catch (...) { originalSet(env, type, key, value); return; }
    if (index < 0 || !baselineReady) { originalSet(env, type, key, value); return; }
    std::lock_guard transaction(publisherMutex);
    auto prior = shadowSnapshot();
    auto candidate = prior;
    candidate[index == 0 ? "countryList" : "nameList"] = incoming;
    candidate["revision"] = prior.at("revision").get<uint64_t>() + 1;
    candidate["published"] = false;
    Json reply;
    bool saved = validShadow(candidate, phoneBootId()) && exchange(candidate,
        acknowledgement(false, static_cast<bool>(engineReady), false, "", "Natural SIM update is in progress"), reply, "prepare") && reply.value("shadowPrepared", false);
    if (!saved) engineReady = false;
    // Save a natural SIM change before any configured overlay is reapplied.
    originalSet(env, type, key, value);
    if (!env->ExceptionCheck()) {
        std::string country, name;
        {
            std::lock_guard lock(stateMutex);
            (index == 0 ? baseCountry : baseName) = incoming;
            ++baseRevision; baseVerified = false; published.clear();
            country = baseCountry; name = baseName;
        }
        if (hooksType && configureId) {
            if (!configure(env, Json::array())) engineReady = false;
        }
        // A natural update invalidates the complete snapshot, including the other key.
        const bool countryWritten = __system_property_set(kKeys[0], country.c_str()) == 0;
        const bool nameWritten = __system_property_set(kKeys[1], name.c_str()) == 0;
        if (!countryWritten || !nameWritten || readProperty(kKeys[0]) != country || readProperty(kKeys[1]) != name)
            engineReady = false;
        Json committed = shadowSnapshot();
        bool acknowledged = saved && exchange(committed,
            acknowledgement(false, static_cast<bool>(engineReady), false, "", "Natural SIM update is waiting for publication"), reply, "commit") && reply.value("shadowSaved", false);
        { std::lock_guard lock(stateMutex); baseVerified = acknowledged; }
        if (!acknowledged) engineReady = false;
        wake.notify_all();
    } else if (saved) {
        // The framework rejected its proposed write: discard the write-ahead original.
        exchange(prior, acknowledgement(false, false, false, "", "Natural SIM property write was rejected"), reply, "abort");
        engineReady = false;
    }
}
jobject installMethod(JNIEnv* env, jobject target, jobject callback) {
    if (!target || !callback) return nullptr;
    auto type = env->GetObjectClass(callback);
    if (!type || env->ExceptionCheck()) { recordJniFailure(env, "Hook callback class is unavailable"); return nullptr; }
    auto method = env->GetMethodID(type, "callback", "([Ljava/lang/Object;)Ljava/lang/Object;");
    if (!method || env->ExceptionCheck()) {
        recordJniFailure(env, "Hook callback(Object[]) method is unavailable");
        env->DeleteLocalRef(type); return nullptr;
    }
    auto field = env->GetFieldID(type, "backup", "Ljava/lang/reflect/Method;");
    if (!field || env->ExceptionCheck()) {
        recordJniFailure(env, "Hook backup Method field is unavailable");
        env->DeleteLocalRef(type); return nullptr;
    }
    auto reflected = env->ToReflectedMethod(type, method, JNI_FALSE);
    env->MonitorEnter(callback);
    auto backup = lsplant::Hook(env, target, callback, reflected);
    if (backup && !env->ExceptionCheck()) env->SetObjectField(callback, field, backup);
    env->MonitorExit(callback);
    if (backup && !env->ExceptionCheck()) { ++hookCount; (void)lsplant::Deoptimize(env, target); }
    env->DeleteLocalRef(reflected); env->DeleteLocalRef(type);
    return backup; // LSPlant owns this global ref; never DeleteLocalRef it.
}
jboolean hookMethod(JNIEnv* env, jclass, jobject target, jobject callback) { return installMethod(env, target, callback) ? JNI_TRUE : JNI_FALSE; }
void enterOriginal(JNIEnv*, jclass, jboolean entering) {
    if (entering) ++internalDepth;
    else if (internalDepth) --internalDepth;
}
bool configure(JNIEnv* env, const Json& plan) {
    if (!hooksType || !configureId) return false;
    LocalFrame frame(env);
    if (!frame.valid) { recordJniFailure(env, "Cannot allocate central profile frame"); return false; }
    auto allocated = [&](jobject reference) {
        if (reference && !env->ExceptionCheck()) return true;
        recordJniFailure(env, "Cannot allocate central profile arrays");
        return false;
    };
    jsize size = static_cast<jsize>(plan.size());
    auto strings = env->FindClass("java/lang/String");
    if (!allocated(strings)) return false;
    auto ids = env->NewIntArray(size);
    if (!allocated(ids)) return false;
    auto slots = env->NewIntArray(size);
    if (!allocated(slots)) return false;
    auto countries = env->NewObjectArray(size, strings, nullptr);
    if (!allocated(countries)) return false;
    auto names = env->NewObjectArray(size, strings, nullptr);
    if (!allocated(names)) return false;
    std::vector<jint> idValues, slotValues;
    for (jsize i = 0; i < size; i++) {
        idValues.push_back(plan[i].at("subId").get<int>()); slotValues.push_back(plan[i].at("slot").get<int>());
        auto country = javaString(env, plan[i].at("country").get<std::string>());
        auto name = javaString(env, plan[i].at("carrierName").get<std::string>());
        if (!country || !name || env->ExceptionCheck()) { recordJniFailure(env, "Cannot encode central profile strings"); return false; }
        env->SetObjectArrayElement(countries, i, country); env->SetObjectArrayElement(names, i, name);
        env->DeleteLocalRef(country); env->DeleteLocalRef(name);
    }
    if (size > 0) {
        env->SetIntArrayRegion(ids, 0, size, idValues.data());
        if (env->ExceptionCheck()) { recordJniFailure(env, "Cannot encode central profile IDs"); return false; }
        env->SetIntArrayRegion(slots, 0, size, slotValues.data());
        if (env->ExceptionCheck()) { recordJniFailure(env, "Cannot encode central profile slots"); return false; }
    }
    env->CallStaticVoidMethod(hooksType, configureId, ids, slots, countries, names);
    if (env->ExceptionCheck()) { recordJniFailure(env, "Central profile configuration failed"); return false; }
    return true;
}
Json shadowSnapshot() {
    std::lock_guard lock(stateMutex);
    return Json{{"bootId", phoneBootId()}, {"countryList", baseCountry}, {"nameList", baseName}, {"published", !published.empty()}, {"revision", baseRevision}};
}
std::vector<haaru::PropertyProfile> propertyPlan(const Json& plan) {
    std::vector<haaru::PropertyProfile> values;
    for (const auto& p : plan) values.push_back({p.at("slot").get<int>(), p.at("country").get<std::string>(), p.at("carrierName").get<std::string>()});
    return values;
}
bool validate(JNIEnv* env, const Json& plan) {
    if (plan.empty()) return true;
    if (!hooksType || !validateId || !phoneContext) return false;
    LocalFrame frame(env);
    if (!frame.valid) return false;
    jsize count = static_cast<jsize>(plan.size());
    auto ids = env->NewIntArray(count), slots = env->NewIntArray(count);
    if (!ids || !slots || env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    std::vector<jint> idValues, slotValues;
    for (const auto& p : plan) { idValues.push_back(p.at("subId").get<int>()); slotValues.push_back(p.at("slot").get<int>()); }
    env->SetIntArrayRegion(ids, 0, count, idValues.data()); env->SetIntArrayRegion(slots, 0, count, slotValues.data());
    bool ok = env->CallStaticBooleanMethod(hooksType, validateId, phoneContext, ids, slots) == JNI_TRUE;
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return ok;
}
bool propertiesMatch(const Json& plan) {
    std::lock_guard transaction(publisherMutex);
    std::string country, name;
    { std::lock_guard lock(stateMutex); country = baseCountry; name = baseName; }
    try {
        auto values = propertyPlan(plan);
        return readProperty(kKeys[0]) == haaru::overlayProperty(country, values, true) &&
            readProperty(kKeys[1]) == haaru::overlayProperty(name, values, false);
    } catch (...) { return false; }
}
std::optional<uint64_t> restore(JNIEnv* env, haaru::RestorePhase phase = haaru::RestorePhase::Runtime) {
    std::lock_guard transaction(publisherMutex);
    std::string originals[2];
    uint64_t revision;
    {
        std::lock_guard lock(stateMutex);
        published.clear();
        originals[0] = baseCountry;
        originals[1] = baseName;
        revision = baseRevision;
    }
    const bool restored = haaru::restoreGlobalState(phase, baselineReady,
        [&] { return configure(env, Json::array()); },
        [&](size_t index) {
            return (phase == haaru::RestorePhase::Bootstrap && readProperty(kKeys[index]) == originals[index]) ||
                __system_property_set(kKeys[index], originals[index].c_str()) == 0;
        },
        [&](size_t index) { return readProperty(kKeys[index]) == originals[index]; });
    if (!restored) return std::nullopt;
    return revision;
}
void notifySimChanged(JNIEnv* env) {
    if (!notifyId || !phoneContext) return;
    env->CallStaticBooleanMethod(hooksType, notifyId, phoneContext);
    if (env->ExceptionCheck()) env->ExceptionClear();
}
bool apply(JNIEnv* env, const Json& plan, std::string& error, uint64_t& acceptedRevision) {
    if (!engineReady || !guardsReady || !baselineReady) { error = "Central phone engine is not initialised"; return false; }
    {
        LocalFrame frame(env);
        if (!frame.valid) { recordJniFailure(env, "Cannot allocate SIM capability validation frame"); error = "Cannot validate SIM field support"; return false; }
        for (const auto& profile : plan) {
            auto country = javaString(env, profile.at("country").get<std::string>());
            auto name = javaString(env, profile.at("carrierName").get<std::string>());
            if (!country || !name || env->ExceptionCheck()) { recordJniFailure(env, "Cannot encode requested SIM fields"); error = "Cannot validate SIM field support"; return false; }
            bool supported = env->CallStaticBooleanMethod(hooksType, profileSupportId, country, name) == JNI_TRUE;
            if (env->ExceptionCheck()) { recordJniFailure(env, "SIM field capability check failed"); error = "Cannot validate SIM field support"; return false; }
            if (!supported) { error = "This framework cannot safely copy the requested SIM fields"; return false; }
        }
    }
    if (!validate(env, plan)) { error = "SIM identity or readiness changed; refresh the SIM settings"; return false; }
    std::string country, name;
    uint64_t revision;
    { std::lock_guard lock(stateMutex); if (!baseVerified) { error = "Original property snapshot has not been saved"; return false; }
        country = baseCountry; name = baseName; revision = baseRevision; }
    auto propertyValues = propertyPlan(plan);
    std::string desiredCountry, desiredName;
    try { desiredCountry = haaru::overlayProperty(country, propertyValues, true); desiredName = haaru::overlayProperty(name, propertyValues, false); }
    catch (const std::exception& failure) { error = failure.what(); return false; }
    if (!configure(env, Json::array())) { error = "Cannot suspend central response overlay"; return false; }
    {
        std::lock_guard transaction(publisherMutex);
        if (__system_property_set(kKeys[0], desiredCountry.c_str()) || readProperty(kKeys[0]) != desiredCountry ||
                __system_property_set(kKeys[1], desiredName.c_str()) || readProperty(kKeys[1]) != desiredName) {
            __system_property_set(kKeys[0], country.c_str()); __system_property_set(kKeys[1], name.c_str());
            error = "System-global RAM property write/readback failed";
            std::lock_guard lock(stateMutex); published.clear(); return false;
        }
        { std::lock_guard lock(stateMutex);
            if (revision != baseRevision) { error = "SIM values changed during application; retrying"; return false; }
            published = propertyValues;
        }
        if (!configure(env, plan)) { error = "Central service snapshot was not accepted"; return false; }
        acceptedRevision = revision;
    }
    notifySimChanged(env);
    error.clear(); return true;
}
void engineLoop() {
    JNIEnv* env = nullptr;
    if (!javaVm || javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        recordFailure("engine-thread", "Cannot attach the central engine thread to the phone JavaVM");
        checkpoint();
        std::lock_guard lock(channelMutex);
        closeChannel();
        return;
    }
    Internal internal;
    std::string currentHash, error;
    uint64_t appliedRevision = UINT64_MAX;
    bool confirmed = false;
    try {
        while (channel >= 0) {
            std::unique_lock publication(publisherMutex);
            auto shadow = shadowSnapshot();
            uint64_t revision = shadow.at("revision").get<uint64_t>();
            bool current = confirmed && revision == appliedRevision && static_cast<bool>(engineReady);
            Json ack = acknowledgement(current, static_cast<bool>(engineReady), current, currentHash, error);
            Json response;
            if (!exchange(shadow, ack, response)) break;
            { std::lock_guard lock(stateMutex); if (response.value("shadowSaved", false) && revision == baseRevision) baseVerified = true; }
            if (response.value("recoveryRequired", false)) { recoveryRequired = true; engineReady = false; }
            publication.unlock();
            auto hashValue = response.value("hash", std::string());
            Json plan = Json::array();
            if (response.value("active", false) && response.contains("config") && validConfig(response["config"], phoneBootId())) {
                auto& config = response["config"];
                if (haaru::cacheAllowed(config.value("protocol", 0), config.value("readOnly", true),
                        config.value("enabled", false), config.value("bootId", std::string()) == phoneBootId())) plan = config["profiles"];
            }
            bool healthy = engineReady && guardsReady;
            if (healthy && healthId) {
                healthy = env->CallStaticBooleanMethod(hooksType, healthId) == JNI_TRUE;
                if (env->ExceptionCheck()) { recordJniFailure(env, "Central response health check failed"); healthy = false; }
                if (!healthy) recordFailure("central-service-health", hookError(env, "Central response cloning is unhealthy"));
            }
            bool changed;
            { std::lock_guard lock(stateMutex); changed = !baseVerified || appliedRevision != baseRevision; }
            if (!healthy) {
                auto [stage, failure] = diagnostic();
                restore(env); confirmed = false;
                error = !failure.empty() ? failure : (recoveryRequired ? "An interrupted SIM update requires a full phone reboot" : "Central response engine is unavailable or unhealthy");
            } else if (!validate(env, plan)) {
                restore(env); confirmed = false; error = "SIM identity or readiness changed; refresh the SIM settings";
            } else if (!confirmed || currentHash != hashValue || changed || !propertiesMatch(plan)) {
                if (plan.empty()) {
                    const auto restoredRevision = restore(env);
                    confirmed = restoredRevision.has_value();
                    error = confirmed ? "" : "Global restore was not verified";
                    if (restoredRevision) {
                        appliedRevision = *restoredRevision;
                        notifySimChanged(env);
                    }
                } else {
                    confirmed = apply(env, plan, error, appliedRevision);
                    if (!confirmed) restore(env);
                }
            }
            currentHash = hashValue;
            setStage("runtime");
            std::unique_lock lock(stateMutex); wake.wait_for(lock, std::chrono::seconds(2));
        }
    } catch (const std::exception& failure) { recordJniFailure(env, std::string("Global engine failed: ") + failure.what()); }
    catch (...) { recordJniFailure(env, "Unknown global engine failure"); }
    restore(env); engineReady = false;
    if (channel >= 0) {
        Json response;
        (void)exchange(shadowSnapshot(), acknowledgement(false, false, false, currentHash, error), response, "diagnostic");
    }
    { std::lock_guard lock(channelMutex); closeChannel(); }
    javaVm->DetachCurrentThread();
}
void attached(JNIEnv* env, jclass, jobject context) {
    if (started.exchange(true)) return;
    Internal internal;
    setStage("central-service-hooks");
    try {
        requireJni(env, context != nullptr, "Application.attach did not provide a phone Context");
        phoneContext = env->NewGlobalRef(context);
        requireJni(env, phoneContext != nullptr, "Cannot retain the phone Context");
        jint count = env->CallStaticIntMethod(hooksType, initializeId, context);
        requireJni(env, count > 0, "No discovered central service readers were installed");
        requiredHookCount = env->CallStaticIntMethod(hooksType, requiredId);
        requireJni(env, requiredHookCount >= 3, "No complete carrier/subscription reader coverage was discovered");
        coverageReady = env->CallStaticBooleanMethod(hooksType, coverageId) == JNI_TRUE;
        requireJni(env, coverageReady && hookCount.load() == requiredHookCount.load(), "Not every discovered carrier/subscription reader was hooked");
        countrySupported = env->CallStaticBooleanMethod(hooksType, countrySupportId) == JNI_TRUE;
        requireJni(env, true, "Cannot query SIM country copy support");
        nameSupported = env->CallStaticBooleanMethod(hooksType, nameSupportId) == JNI_TRUE;
        requireJni(env, true, "Cannot query carrier name copy support");
        requireJni(env, guardsReady && baselineReady && channel >= 0, "Central service hooks have no property guard or companion connection");
        engineReady = diagnostic().second.empty();
        setStage("runtime");
    } catch (const std::exception& failure) { recordJniFailure(env, failure.what()); }
    catch (...) { recordJniFailure(env, "Unknown central service initialisation failure"); }
    try { std::thread(engineLoop).detach(); }
    catch (const std::exception& failure) {
        recordFailure("engine-thread", std::string("Cannot start the central engine thread: ") + failure.what());
        checkpoint();
        std::lock_guard lock(channelMutex);
        closeChannel();
    }
    catch (...) {
        recordFailure("engine-thread", "Cannot start the central engine thread");
        checkpoint();
        std::lock_guard lock(channelMutex);
        closeChannel();
    }
}

class Module : public zygisk::ModuleBase {
    zygisk::Api* api = nullptr;
    JNIEnv* env = nullptr;
    bool selected = false;
public:
    void onLoad(zygisk::Api* value, JNIEnv* environment) override { api = value; env = environment; }
    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override { api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY); }
    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override;
    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override;
};

void Module::preAppSpecialize(zygisk::AppSpecializeArgs* args) {
    bool registered = false, protocolReady = false;
    try {
        if (!args || !haaru::uidAllowed(static_cast<unsigned int>(args->uid)) || !args->nice_name || utf8(env, args->nice_name) != "com.android.phone") {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY); return;
        }
        setStage("companion");
        channel = api->connectCompanion();
        if (channel < 0) throw std::runtime_error("Companion is unavailable");
        timeout(channel, 12);
        if (!sendJson(channel, Json{{"protocol", 2}, {"uid", 1001}, {"process", "com.android.phone"},
                {"phonePid", getpid()}, {"sdk", deviceSdk()}}))
            throw std::runtime_error("Companion greeting failed");
        Json initial;
        const auto localBoot = bootId();
        if (!readJson(channel, initial) || localBoot.empty() || initial.value("bootId", std::string()) != localBoot)
            throw std::runtime_error("Companion boot identity mismatch");
        verifiedPhoneBoot = localBoot;
        recoveryRequired = initial.value("recoveryRequired", false);
        uint32_t length = 0;
        if (!transfer(channel, &length, sizeof(length), false) || length < 112 || length > kDexLimit)
            throw std::runtime_error("Invalid hook DEX length");
        dex.resize(length);
        if (!transfer(channel, dex.data(), length, false) || std::memcmp(dex.data(), "dex\n", 4))
            throw std::runtime_error("Invalid hook DEX");
        protocolReady = true;
        timeout(channel, 3);
        setStage("property-baseline");
        baseCountry = readProperty(kKeys[0]); baseName = readProperty(kKeys[1]);
        if (initial.contains("shadow") && initial["shadow"].is_object() &&
                initial["shadow"].value("bootId", std::string()) == phoneBootId()) {
            if (!validShadow(initial["shadow"], phoneBootId())) throw std::runtime_error("Unsafe original-value shadow");
            baseCountry = initial["shadow"]["countryList"].get<std::string>();
            baseName = initial["shadow"]["nameList"].get<std::string>();
        }
        baselineReady = true;
        selected = true; // post must restore a cached baseline even if guard initialisation fails.
        requireJni(env, env->GetJavaVM(&javaVm) == JNI_OK, "JavaVM unavailable");
        setStage("property-guards");
        auto binder = env->FindClass("android/os/Binder");
        requireJni(env, binder != nullptr, "android.os.Binder is unavailable");
        binderType = static_cast<jclass>(env->NewGlobalRef(binder));
        env->DeleteLocalRef(binder);
        requireJni(env, binderType != nullptr, "Cannot retain android.os.Binder");
        binderUid = env->GetStaticMethodID(binderType, "getCallingUid", "()I");
        requireJni(env, binderUid != nullptr, "android.os.Binder.getCallingUid() is unavailable");
        JNINativeMethod methods[] = {
            {const_cast<char*>("native_get"), const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"), reinterpret_cast<void*>(getSingle)},
            {const_cast<char*>("native_get"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"), reinterpret_cast<void*>(getString)},
            {const_cast<char*>("native_get"), const_cast<char*>("(J)Ljava/lang/String;"), reinterpret_cast<void*>(getHandle)},
            {const_cast<char*>("native_set"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)V"), reinterpret_cast<void*>(setString)}
        };
        registered = true; // A partially installed JNI callback must remain mapped.
        api->hookJniNativeMethods(env, "android/os/SystemProperties", methods, 4);
        originalSingle = methods[0].fnPtr != reinterpret_cast<void*>(getSingle) ? reinterpret_cast<GetSingle>(methods[0].fnPtr) : nullptr;
        originalGet = methods[1].fnPtr != reinterpret_cast<void*>(getString) ? reinterpret_cast<GetString>(methods[1].fnPtr) : nullptr;
        originalHandle = methods[2].fnPtr != reinterpret_cast<void*>(getHandle) ? reinterpret_cast<GetHandle>(methods[2].fnPtr) : nullptr;
        originalSet = methods[3].fnPtr != reinterpret_cast<void*>(setString) ? reinterpret_cast<SetString>(methods[3].fnPtr) : nullptr;
        guardsReady = originalGet && originalHandle && originalSet && !env->ExceptionCheck();
        std::string missing;
        if (!originalGet) missing += " native_get(String,String)";
        if (!originalHandle) missing += " native_get(long)";
        if (!originalSet) missing += " native_set(String,String)";
        requireJni(env, guardsReady, "Required SystemProperties JNI guards unavailable:" + missing);
        setStage("connection-retention");
        retainCompanionFd(env, api, args);
        setStage("specialization");
        checkpoint();
    } catch (const std::exception& failure) {
        recordJniFailure(env, failure.what());
        if (protocolReady) checkpoint();
        closeChannel();
        guardsReady = false;
        if (!registered && !selected) api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    } catch (...) {
        recordJniFailure(env, "Unknown phone pre-specialization failure");
        if (protocolReady) checkpoint();
        closeChannel();
        guardsReady = false;
        if (!registered && !selected) api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }
}
void Module::postAppSpecialize(const zygisk::AppSpecializeArgs*) {
    if (!selected) return;
    try {
        setStage("connection-validation");
        if (!guardsReady || channel < 0) throw std::runtime_error("Required property guards or companion connection are unavailable");
        validateRetainedCompanion();
        setStage("property-restore");
        checkpoint();
        // The Java hook class has not been loaded at this stage.
        if (!restore(env, haaru::RestorePhase::Bootstrap)) throw std::runtime_error("Original property restoration failed");
        LocalFrame frame(env, 96);
        requireJni(env, frame.valid, "JNI frame unavailable");
        if (recoveryRequired) throw std::runtime_error("An interrupted SIM update requires a full phone reboot");
        setStage("art-symbols");
        checkpoint();
        if (!art.initialize()) throw std::runtime_error(art.error());
        lsplant::InitInfo info{
            .inline_hooker = [](void* target, void* replacement) -> void* {
                void* original = nullptr;
                return DobbyHook(target, replacement, &original) == 0 ? original : nullptr;
            },
            .inline_unhooker = [](void* target) { return DobbyDestroy(target) == 0; },
            .art_symbol_resolver = [](std::string_view name) { return art.exact(name); },
            .art_symbol_prefix_resolver = [](std::string_view name) { return art.prefix(name); },
            .generated_class_name = "HaaruGlobal_",
            .generated_source_name = "HaaruGlobal",
            .generated_field_name = "callback",
            .generated_method_name = "{target}",
            .executable_memory_allocator = {},
            .executable_memory_recycler = {}
        };
        setStage("dex-loader");
        auto loaderType = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        requireJni(env, loaderType != nullptr, "dalvik.system.InMemoryDexClassLoader is unavailable");
        auto baseType = env->FindClass("java/lang/ClassLoader");
        requireJni(env, baseType != nullptr, "java.lang.ClassLoader is unavailable");
        auto systemLoaderId = env->GetStaticMethodID(baseType, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        requireJni(env, systemLoaderId != nullptr, "ClassLoader.getSystemClassLoader() is unavailable");
        auto constructor = env->GetMethodID(loaderType, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        requireJni(env, constructor != nullptr, "InMemoryDexClassLoader(ByteBuffer,ClassLoader) is unavailable");
        auto loadClass = env->GetMethodID(baseType, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        requireJni(env, loadClass != nullptr, "ClassLoader.loadClass(String) is unavailable");
        auto parent = env->CallStaticObjectMethod(baseType, systemLoaderId);
        requireJni(env, parent != nullptr, "Cannot get the system classloader");
        auto buffer = env->NewDirectByteBuffer(dex.data(), static_cast<jlong>(dex.size()));
        requireJni(env, buffer != nullptr, "Cannot allocate the module DEX buffer");
        auto loader = env->NewObject(loaderType, constructor, buffer, parent);
        requireJni(env, loader != nullptr, "Hook DEX load failed");
        dexLoader = env->NewGlobalRef(loader);
        requireJni(env, dexLoader != nullptr, "Cannot retain the module DEX loader");
        // LSPlant requires these hidden field IDs to be resolved before Init.
        // Trust only this module's pinned DEX, never a system or unrelated loader.
        setStage("dex-cookie-access");
        auto dexBase = env->FindClass("dalvik/system/BaseDexClassLoader");
        requireJni(env, dexBase != nullptr, "BaseDexClassLoader is unavailable");
        auto pathListId = env->GetFieldID(dexBase, "pathList", "Ldalvik/system/DexPathList;");
        requireJni(env, pathListId != nullptr, "BaseDexClassLoader.pathList is unavailable");
        auto pathList = env->GetObjectField(loader, pathListId);
        requireJni(env, pathList != nullptr, "The module loader has no DexPathList");
        auto pathType = env->GetObjectClass(pathList);
        requireJni(env, pathType != nullptr, "DexPathList class is unavailable");
        auto elementsId = env->GetFieldID(pathType, "dexElements", "[Ldalvik/system/DexPathList$Element;");
        requireJni(env, elementsId != nullptr, "DexPathList.dexElements is unavailable");
        auto elements = static_cast<jobjectArray>(env->GetObjectField(pathList, elementsId));
        requireJni(env, elements != nullptr, "The module loader has no DEX elements");
        jsize elementCount = env->GetArrayLength(elements);
        requireJni(env, elementCount > 0 && elementCount <= 8, "Unexpected module DEX element count");
        std::vector<jobject> cookies;
        for (jsize i = 0; i < elementCount; ++i) {
            auto element = env->GetObjectArrayElement(elements, i);
            requireJni(env, element != nullptr, "The module loader contains an empty DEX element");
            auto elementType = env->GetObjectClass(element);
            requireJni(env, elementType != nullptr, "DexPathList.Element class is unavailable");
            auto fileId = env->GetFieldID(elementType, "dexFile", "Ldalvik/system/DexFile;");
            requireJni(env, fileId != nullptr, "DexPathList.Element.dexFile is unavailable");
            auto file = env->GetObjectField(element, fileId);
            requireJni(env, file != nullptr, "The module DEX element has no DexFile");
            auto fileType = env->GetObjectClass(file);
            requireJni(env, fileType != nullptr, "DexFile class is unavailable");
            auto cookieId = env->GetFieldID(fileType, "mCookie", "Ljava/lang/Object;");
            requireJni(env, cookieId != nullptr, "DexFile.mCookie is unavailable");
            auto cookie = env->GetObjectField(file, cookieId);
            requireJni(env, cookie != nullptr, "The module DexFile has no cookie");
            cookies.push_back(cookie);
        }
        setStage("lsplant-init");
        checkpoint();
        requireJni(env, lsplant::Init(env, info), "LSPlant initialisation failed");
        setStage("dex-trust");
        for (auto cookie : cookies) requireJni(env, lsplant::MakeDexFileTrusted(env, cookie), "Cannot trust the module hook DEX");
        setStage("hook-class");
        auto name = javaString(env, "org.haaru.hook.GlobalHooks");
        requireJni(env, name != nullptr, "Cannot encode the global hook class name");
        auto hookClass = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, name));
        requireJni(env, hookClass != nullptr, "org.haaru.hook.GlobalHooks is unavailable");
        hooksType = static_cast<jclass>(env->NewGlobalRef(hookClass));
        requireJni(env, hooksType != nullptr, "Cannot retain the global hook class");
        JNINativeMethod natives[] = {
            {const_cast<char*>("nativeHookMethod"), const_cast<char*>("(Ljava/lang/reflect/Method;Lorg/haaru/hook/GlobalHooks$Callback;)Z"), reinterpret_cast<void*>(hookMethod)},
            {const_cast<char*>("nativeAttached"), const_cast<char*>("(Ljava/lang/Object;)V"), reinterpret_cast<void*>(attached)},
            {const_cast<char*>("nativeCallingUid"), const_cast<char*>("()I"), reinterpret_cast<void*>(callingUid)},
            {const_cast<char*>("internallyTrusted"), const_cast<char*>("()Z"), reinterpret_cast<void*>(trusted)},
            {const_cast<char*>("nativeEnterOriginal"), const_cast<char*>("(Z)V"), reinterpret_cast<void*>(enterOriginal)}
        };
        setStage("native-registration");
        requireJni(env, env->RegisterNatives(hooksType, natives, 5) == JNI_OK, "Global JNI registration failed");
        setStage("hook-methods");
        configureId = env->GetStaticMethodID(hooksType, "configure", "([I[I[Ljava/lang/String;[Ljava/lang/String;)V");
        requireJni(env, configureId != nullptr, "GlobalHooks.configure is unavailable");
        initializeId = env->GetStaticMethodID(hooksType, "initialize", "(Ljava/lang/Object;)I");
        requireJni(env, initializeId != nullptr, "GlobalHooks.initialize is unavailable");
        healthId = env->GetStaticMethodID(hooksType, "isHealthy", "()Z");
        requireJni(env, healthId != nullptr, "GlobalHooks.isHealthy is unavailable");
        healthErrorId = env->GetStaticMethodID(hooksType, "getError", "()Ljava/lang/String;");
        requireJni(env, healthErrorId != nullptr, "GlobalHooks.getError is unavailable");
        requiredId = env->GetStaticMethodID(hooksType, "getRequiredHooks", "()I");
        requireJni(env, requiredId != nullptr, "GlobalHooks.getRequiredHooks is unavailable");
        coverageId = env->GetStaticMethodID(hooksType, "isCoverageComplete", "()Z");
        requireJni(env, coverageId != nullptr, "GlobalHooks.isCoverageComplete is unavailable");
        countrySupportId = env->GetStaticMethodID(hooksType, "supportsCountry", "()Z");
        requireJni(env, countrySupportId != nullptr, "GlobalHooks.supportsCountry is unavailable");
        nameSupportId = env->GetStaticMethodID(hooksType, "supportsCarrierName", "()Z");
        requireJni(env, nameSupportId != nullptr, "GlobalHooks.supportsCarrierName is unavailable");
        profileSupportId = env->GetStaticMethodID(hooksType, "supportsProfile", "(Ljava/lang/String;Ljava/lang/String;)Z");
        requireJni(env, profileSupportId != nullptr, "GlobalHooks.supportsProfile is unavailable");
        notifyId = env->GetStaticMethodID(hooksType, "notifyChanged", "(Ljava/lang/Object;)Z");
        requireJni(env, notifyId != nullptr, "GlobalHooks.notifyChanged is unavailable");
        validateId = env->GetStaticMethodID(hooksType, "validateProfiles", "(Ljava/lang/Object;[I[I)Z");
        requireJni(env, validateId != nullptr, "GlobalHooks.validateProfiles is unavailable");
        setStage("application-attach-hook");
        auto attachName = javaString(env, "org.haaru.hook.GlobalHooks$AttachCallback");
        requireJni(env, attachName != nullptr, "Cannot encode the attach callback class name");
        auto attachType = static_cast<jclass>(env->CallObjectMethod(loader, loadClass, attachName));
        requireJni(env, attachType != nullptr, "GlobalHooks.AttachCallback is unavailable");
        auto attachCtor = env->GetMethodID(attachType, "<init>", "()V");
        requireJni(env, attachCtor != nullptr, "GlobalHooks.AttachCallback() is unavailable");
        auto callback = env->NewObject(attachType, attachCtor);
        requireJni(env, callback != nullptr, "Cannot create the attach callback");
        auto application = env->FindClass("android/app/Application");
        requireJni(env, application != nullptr, "android.app.Application is unavailable");
        auto attachId = env->GetMethodID(application, "attach", "(Landroid/content/Context;)V");
        requireJni(env, attachId != nullptr, "Application.attach(Context) is unavailable");
        auto target = env->ToReflectedMethod(application, attachId, JNI_FALSE);
        requireJni(env, target != nullptr, "Cannot reflect Application.attach(Context)");
        requireJni(env, installMethod(env, target, callback) != nullptr, "Phone attach hook unavailable");
        setStage("awaiting-application-attach");
        checkpoint();
        return;
    } catch (const std::exception& failure) { recordJniFailure(env, failure.what()); }
    catch (...) { recordJniFailure(env, "Unknown phone post-specialization failure"); }
    if (!connectionValidated) {
        const auto stage = diagnostic().first;
        __android_log_print(ANDROID_LOG_WARN, "HaaruEngine", "Phone bootstrap stopped at %s", stage.c_str());
    }
    checkpoint();
    if (!started.exchange(true)) {
        try { std::thread(engineLoop).detach(); }
        catch (...) {
            std::lock_guard lock(channelMutex);
            closeChannel();
        }
    }
}
}
REGISTER_ZYGISK_MODULE(Module)
REGISTER_ZYGISK_COMPANION(companion)
