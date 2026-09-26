#include "motion_gait.h"
#include <jni.h>
#include <android/log.h>
#include <android/sensor.h>
#include <shadowhook.h>
#include <unordered_map>
#include <sys/types.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <iterator>
#include <unordered_set>
#include <vector>

namespace {

constexpr const char* kTag = "LS-SystemAccel";
// Resolve ABI signatures at runtime; never use firmware-specific code or object offsets.
constexpr const char* kSendEventsSymbol =
    "_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tmPS2_PKNS_2wpIKS1_EE";
constexpr const char* kConstructorSymbol =
    "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjNS_7String8EbRKNS_8String16ES9_";
constexpr const char* kDestructorSymbol =
    "_ZN7android13SensorService21SensorEventConnectionD2Ev";
// String8 is a non-trivial C++ value, passed indirectly under the arm64 C++ ABI.
using ConstructorFn = void (*)(void*, const void*, uint32_t, void*, bool, const void*, const void*);
using DestructorFn = void (*)(void*, const void*);
ConstructorFn originalConstructor = nullptr;
DestructorFn originalDestructor = nullptr;
std::unordered_map<void*, int> connectionUids;
struct InstalledHook { void* address; void* stub; };
std::vector<InstalledHook> installedHooks;

using Loaded = void (*)(const char*, void*);
struct NativeAPIEntries;

// status_t SensorEventConnection::sendEvents(
//     sensors_event_t const* buffer, size_t numEvents, sensors_event_t* scratch,
//     wp<SensorEventConnection const> const* mapFlushEventsToConnections)
// sensors_event_t and ASensorEvent are ABI-identical in SensorService.
using SendEventsFn = int32_t (*)(void*, const ASensorEvent*, size_t, ASensorEvent*, const void*);

SendEventsFn originalSendEvents = nullptr;
void* hookedSendEvents = nullptr;

std::atomic<bool> installed{false};
std::atomic<bool> enabled{false};
std::atomic<double> speedMs{0.0};
std::atomic<int64_t> simulatedSteps{2350};

std::mutex stateMutex;
std::mutex lifecycleMutex;
motion_gait::Parameters gaitParameters;
std::unordered_set<int> targetUids;
std::unordered_set<int> observedUids;

int64_t nowNs();

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

int connectionUid(void* connection) {
    std::lock_guard<std::mutex> lock(stateMutex);
    const auto found = connectionUids.find(connection);
    return found == connectionUids.end() ? -1 : found->second;
}

void constructorHook(void* self, const void* service, uint32_t uid, void* packageName,
                     bool wakeUp, const void* opPackageName, const void* attributionTag) {
    originalConstructor(self, service, uid, packageName, wakeUp, opPackageName, attributionTag);
    std::lock_guard<std::mutex> lock(stateMutex);
    if (installed.load()) connectionUids[self] = static_cast<int>(uid);
}

void destructorHook(void* self, const void* vtt) {
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        connectionUids.erase(self);
    }
    originalDestructor(self, vtt);
}

void observeUid(int uid) {
    if (uid < 0 || uid % 100000 < 10000 || uid % 100000 > 19999) return;
    std::lock_guard<std::mutex> lock(stateMutex);
    observedUids.insert(uid);
}

bool isTargetUid(int uid) {
    std::lock_guard<std::mutex> lock(stateMutex);
    return targetUids.find(uid) != targetUids.end();
}

void applyUpstreamVibration(ASensorEvent& event, const motion_gait::Parameters& params, int64_t now) {
    const int64_t timestamp = event.timestamp > 0 ? event.timestamp : now;
    // Keep distinct timestamps for batched events. Anchor is refreshed every 50 ms by Kotlin.
    const double steps = params.steps + double(timestamp - params.anchorNs) / 1e9 * params.cadence / 60.0;
    thread_local std::mt19937_64 random(static_cast<uint64_t>(now));
    thread_local std::normal_distribution<double> gaussian(0.0, 1.0);
    const auto values = motion_gait::sample(params, steps, gaussian(random), gaussian(random), gaussian(random));
    std::copy(values.begin(), values.end(), event.data);
}

int32_t sendEventsHook(
        void* self,
        const ASensorEvent* events,
        size_t count,
        ASensorEvent* scratch,
        const void* flushMap) {
    if (originalSendEvents == nullptr || events == nullptr || count == 0) {
        return originalSendEvents
            ? originalSendEvents(self, events, count, scratch, flushMap)
            : -1;
    }

    const int uid = connectionUid(self);
    observeUid(uid);

    bool hasRelevantSensor = false;
    for (size_t i = 0; i < count; ++i) {
        const int type = events[i].type;
        if (type == ASENSOR_TYPE_ACCELEROMETER) {
            hasRelevantSensor = true;
        }
    }
    if (!hasRelevantSensor) {
        return originalSendEvents(self, events, count, scratch, flushMap);
    }


    if (!enabled.load(std::memory_order_relaxed) || uid <= 1000 || !isTargetUid(uid)) {
        return originalSendEvents(self, events, count, scratch, flushMap);
    }

    const double speed = speedMs.load(std::memory_order_relaxed);
    if (!(speed > 0.1) || !std::isfinite(speed)) {
        return originalSendEvents(self, events, count, scratch, flushMap);
    }
    const int64_t now = nowNs();
    motion_gait::Parameters gait;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        gait = gaitParameters;
    }
    // A stalled config worker must not leave a permanently moving sensor stream.
    if (now - gait.anchorNs > 1500000000LL || !std::isfinite(gait.steps) ||
        !std::isfinite(gait.cadence) || gait.cadence <= 0) {
        return originalSendEvents(self, events, count, scratch, flushMap);
    }

    std::vector<ASensorEvent> copy(events, events + count);
    bool changed = false;
    for (ASensorEvent& event : copy) {
        if (event.type == ASENSOR_TYPE_ACCELEROMETER) {
            applyUpstreamVibration(event, gait, now);
            changed = true;
        }
    }
    if (changed) {
        // sendEvents() is already scoped to one SensorEventConnection, so the
        // private copy can only affect this target client. Other apps receive the
        // original sensor buffer through their own connection.
        return originalSendEvents(self, copy.data(), copy.size(), scratch, flushMap);
    }
    return originalSendEvents(self, events, count, scratch, flushMap);
}

bool removeInstalledHooks() {
    bool ok = true;
    for (auto it = installedHooks.rbegin(); it != installedHooks.rend();) {
        const bool removed = shadowhook_unhook(it->stub) == 0;
        if (removed) {
            it = decltype(it)(installedHooks.erase(std::next(it).base()));
        } else {
            ok = false;
            ++it;
        }
    }
    return ok;
}

bool addHook(void* address, void* replacement, void** original) {
    void* stub = shadowhook_hook_func_addr_2(address, replacement, original,
                                           SHADOWHOOK_HOOK_WITH_MULTI_MODE);
    if (stub == nullptr) return false;
    installedHooks.push_back({address, stub});
    return *original != nullptr;
}

bool installHooks() {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex);
    if (installed.load(std::memory_order_acquire)) return true;
#if !defined(__aarch64__)
    return false;
#endif
    // Do not layer another installation over hooks whose removal failed.
    if (!installedHooks.empty()) return false;
    // Both sensor libraries must use the same hook engine so either can detach safely.
    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) != 0) return false;

    void* library = shadowhook_dlopen("libsensorservice.so");
    if (library == nullptr) return false;
    hookedSendEvents = shadowhook_dlsym(library, kSendEventsSymbol);
    void* constructor = shadowhook_dlsym(library, kConstructorSymbol);
    void* destructor = shadowhook_dlsym(library, kDestructorSymbol);
    shadowhook_dlclose(library);
    if (hookedSendEvents == nullptr || constructor == nullptr || destructor == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
            "Native sensors disabled: required SensorEventConnection ABI symbols unavailable");
        return false;
    }
    // Existing connections without a captured UID pass through. New connections are tracked
    // without reading private object fields; destruction removes entries before address reuse.
    if (!addHook(destructor, reinterpret_cast<void*>(destructorHook),
                 reinterpret_cast<void**>(&originalDestructor)) ||
        !addHook(constructor, reinterpret_cast<void*>(constructorHook),
                 reinterpret_cast<void**>(&originalConstructor)) ||
        !addHook(hookedSendEvents, reinterpret_cast<void*>(sendEventsHook),
                 reinterpret_cast<void**>(&originalSendEvents))) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Native sensor hook installation failed");
        removeInstalledHooks();
        return false;
    }
    installed.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kTag, "Native sensor hooks installed using ABI symbols");
    return true;
}

void resetState() {
    enabled.store(false, std::memory_order_relaxed);
    speedMs.store(0.0, std::memory_order_relaxed);
    simulatedSteps.store(2350, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(stateMutex);
    targetUids.clear();
    observedUids.clear();
    connectionUids.clear();
}

}  // namespace

extern "C" [[gnu::visibility("default")]] [[gnu::used]] Loaded
native_init(const NativeAPIEntries* api) {
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemAccelNative_install(
        JNIEnv*, jclass) {
    return installHooks() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemAccelNative_configure(
        JNIEnv* env, jclass, jboolean active, jintArray uids, jdouble speed, jint cadence,
        jlong totalSteps, jdouble phaseSteps, jdouble phaseCadence,
        jlong anchorNs, jlong session, jint level, jfloatArray templateValues) {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex);
    if (!installed.load()) return;
    std::unordered_set<int> next;
    if (uids != nullptr) {
        const jsize count = env->GetArrayLength(uids);
        jint* values = env->GetIntArrayElements(uids, nullptr);
        if (values != nullptr) {
            for (jsize i = 0; i < count; ++i) {
                if (values[i] > 1000) next.insert(values[i]);
            }
            env->ReleaseIntArrayElements(uids, values, JNI_ABORT);
        }
    }
    motion_gait::Parameters gait;
    gait.steps = phaseSteps;
    gait.cadence = phaseCadence;
    gait.speed = speed;
    gait.anchorNs = anchorNs;
    gait.session = session;
    gait.level = level;
    if (templateValues != nullptr && env->GetArrayLength(templateValues) == 192) {
        gait.gait.resize(192);
        env->GetFloatArrayRegion(templateValues, 0, 192, gait.gait.data());
        if (!std::all_of(gait.gait.begin(), gait.gait.end(), [](float v) { return std::isfinite(v); })) gait.gait.clear();
    }
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        targetUids.swap(next);
        gaitParameters = std::move(gait);
    }
    speedMs.store(static_cast<double>(speed), std::memory_order_relaxed);
    simulatedSteps.store(static_cast<int64_t>(totalSteps), std::memory_order_relaxed);
    enabled.store(active == JNI_TRUE, std::memory_order_release);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemAccelNative_uids(
        JNIEnv* env, jclass) {
    std::vector<int> values;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        values.assign(observedUids.begin(), observedUids.end());
    }
    std::sort(values.begin(), values.end());
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemAccelNative_uninstall(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex);
    if (!installed.exchange(false, std::memory_order_acq_rel) && installedHooks.empty()) {
        resetState();
        return JNI_TRUE;
    }

    enabled.store(false, std::memory_order_release);
    const bool ok = removeInstalledHooks();
    // Keep trampolines valid if a backend could not detach a hook.
    resetState();
    return ok ? JNI_TRUE : JNI_FALSE;
}
