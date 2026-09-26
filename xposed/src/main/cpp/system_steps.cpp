#include <jni.h>
#include <android/log.h>
#include <android/sensor.h>
#include <shadowhook.h>
#include <dlfcn.h>
#include <link.h>
#include <time.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

static_assert(sizeof(ASensorEvent) == 104, "SensorService event ABI mismatch");
static_assert(offsetof(ASensorEvent, u64.step_counter) == 24, "Step counter payload ABI mismatch");

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LS-SystemSteps", __VA_ARGS__)
#define EXPORT extern "C" __attribute__((visibility("default"), used))
static std::atomic<bool> installed{false};
static std::vector<void*> hookStubs;
static std::unordered_map<void*, int> connectionUids;
using Constructor = void (*)(void*, const void*, uint32_t, void*, bool, const void*, const void*);
static Constructor originalConstructor;
static void (*originalDestructor)(void*, const void*);
using Send = int (*)(void*, const ASensorEvent*, size_t, ASensorEvent*, const void*);
using Enable = int (*)(void*, int, bool, int64_t, int64_t, int);
static Send originalSend;
static Enable originalEnable;
static void* (*createWeak)(void*, const void*);
static void (*decWeak)(void*, const void*);
static bool (*promote)(void*, const void*);
static void (*decStrong)(void*, const void*);

static int64_t nowNs() {
    timespec t{}; clock_gettime(CLOCK_BOOTTIME, &t);
    return int64_t(t.tv_sec) * 1000000000LL + t.tv_nsec;
}
static void* refBase(void* connection) {
    // arm64 Itanium ABI: first virtual base (RefBase) offset in the dynamic vtable.
    // This is not a firmware object-field offset. Unsupported layouts fail closed.
    auto table = *reinterpret_cast<ptrdiff_t**>(connection);
    auto offset = table[-3];
    if (offset < 0 || offset > 4096 || offset % alignof(void*) != 0) return nullptr;
    return static_cast<char*>(connection) + offset;
}
struct Connection {
    void* object;
    void* base;
    void* weak;
    int uid;
    std::unordered_set<int> handles;
    int64_t registeredNs;
    uint64_t realCounter = 0;
    bool counterKnown = false;
    uint64_t counter = 0;
    bool announceCounter = true;
    bool wasActive = false;
    int pendingRegistrations = 0;

    Connection(void* p, int u) : object(p), base(refBase(p)), uid(u), registeredNs(nowNs()) {
        weak = base ? createWeak(base, this) : nullptr;
    }
    ~Connection() { if (weak) decWeak(weak, this); }
};
static std::mutex stateMutex;
// Prevents a stop/target change from completing while an old tick is still delivering.
static std::mutex tickMutex;
// Changes on every uninstall; a registration already in the system may finish later.
static uint64_t generation = 0;
static std::unordered_map<void*, std::shared_ptr<Connection>> connections;
static std::unordered_map<int,int> sensorTypes;
static std::unordered_set<int> targets;
static bool enabled = false;
static int64_t deadlineNs = 0;
static uint64_t emitted = 0;
static uint64_t suppressed = 0;
static bool activeLocked(int uid) {
    return installed.load() && enabled && nowNs() < deadlineNs && targets.count(uid);
}

static void constructorHook(void* self, const void* service, uint32_t uid, void* packageName,
                            bool wakeUp, const void* opPackage, const void* attribution) {
    originalConstructor(self, service, uid, packageName, wakeUp, opPackage, attribution);
    std::lock_guard<std::mutex> lock(stateMutex);
    if (installed.load()) connectionUids[self] = int(uid);
}
static void destructorHook(void* self, const void* vtt) {
    std::shared_ptr<Connection> removed;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        connectionUids.erase(self);
        auto it = connections.find(self);
        if (it != connections.end()) { removed = std::move(it->second); connections.erase(it); }
    }
    removed.reset();
    originalDestructor(self, vtt);
}

static int enableHook(void* object, int handle, bool on, int64_t period, int64_t latency, int flags) {
    std::shared_ptr<Connection> registering;
    std::shared_ptr<Connection> removed;
    uint64_t registrationGeneration;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        registrationGeneration = generation;
        auto known = connectionUids.find(object);
        int uid = known == connectionUids.end() ? -1 : known->second;
        if (installed.load() && on && sensorTypes.count(handle) && uid % 100000 >= 10000 && uid % 100000 < 90000) {
            auto& entry = connections[object];
            if (!entry) entry = std::make_shared<Connection>(object, uid);
            registering = entry;
            ++entry->pendingRegistrations;
        }
    }
    // Always let the real service authorize and complete registration first.
    // The provisional entry only observes its initial real counter; no subscriptions yet.
    int result = originalEnable(object, handle, on, period, latency, flags);
    std::lock_guard<std::mutex> lock(stateMutex);
    if (!installed.load() || registrationGeneration != generation) return result;
    if (registering) {
        --registering->pendingRegistrations;
        if (result != 0 && registering->handles.empty() && registering->pendingRegistrations == 0)
            connections.erase(object);
    }
    if (result != 0) return result;
    if (!sensorTypes.count(handle)) return result;
    auto known = connectionUids.find(object);
    int uid = known == connectionUids.end() ? -1 : known->second;
    if (uid % 100000 < 10000 || uid % 100000 >= 90000) return result;
    if (on) {
        auto& entry = connections[object];
        if (!entry) entry = std::make_shared<Connection>(object, uid);
        if (entry->handles.insert(handle).second && sensorTypes.at(handle) == ASENSOR_TYPE_STEP_COUNTER)
            entry->announceCounter = true;
        entry->registeredNs = nowNs();
        LOGI("registered uid=%d handle=%d", uid, handle);
    } else {
        auto it = connections.find(object);
        if (it != connections.end()) {
            it->second->handles.erase(handle);
            if (it->second->handles.empty() && it->second->pendingRegistrations == 0) {
                removed = std::move(it->second);
                connections.erase(it);
            }
        }
    }
    return result;
}

static int sendHook(void* object, const ASensorEvent* input, size_t count,
                    ASensorEvent* scratch, const void* flushMap) {
    if (!input || count == 0 || count > 4096) return originalSend(object, input, count, scratch, flushMap);
    bool replace = false;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        auto it = connections.find(object);
        if (it != connections.end()) {
            auto& state = *it->second;
            replace = activeLocked(state.uid) && !state.handles.empty();
            for (size_t i=0; i<count; ++i) {
                if (input[i].type == ASENSOR_TYPE_STEP_COUNTER && (!replace || !state.counterKnown)) {
                    state.realCounter = input[i].u64.step_counter;
                    state.counterKnown = true;
                }
            }
        }
    }
    // Never change metadata, flush mapping indexes, other sensors or untargeted clients.
    // This path normally has scratch (service filtering). If it doesn't, pass through.
    if (!replace || !scratch || !input || count == 0 || count > 4096)
        return originalSend(object, input, count, scratch, flushMap);
    std::vector<ASensorEvent> filtered(input, input + count);
    uint64_t removed = 0;
    for (auto& event : filtered) {
        if (event.type == ASENSOR_TYPE_STEP_COUNTER || event.type == ASENSOR_TYPE_STEP_DETECTOR) {
            // The original subscription filter discards unknown handles. Keep array indexes intact.
            event.sensor = -1; ++removed;
        }
    }
    if (removed) { std::lock_guard<std::mutex> lock(stateMutex); suppressed += removed; }
    return originalSend(object, filtered.data(), count, scratch, flushMap);
}


EXPORT jboolean Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemStepNative_install(
        JNIEnv* env, jclass, jintArray handles, jintArray types) {
#if !defined(__aarch64__)
    (void)env; (void)handles; (void)types;
    return false;
#else
    std::lock_guard<std::mutex> tickLock(tickMutex);
    if (installed) return true;
    if (!hookStubs.empty()) return false;
    if (!handles || !types) return false;
    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) != 0) return false;
    void* library = shadowhook_dlopen("libsensorservice.so");
    void* utils = shadowhook_dlopen("libutils.so");
    if (!library || !utils) {
        if (library) shadowhook_dlclose(library);
        if (utils) shadowhook_dlclose(utils);
        return false;
    }
    void* send = shadowhook_dlsym(library, "_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tmPS2_PKNS_2wpIKS1_EE");
    void* enable = shadowhook_dlsym(library, "_ZN7android13SensorService21SensorEventConnection13enableDisableEiblli");
    void* ctor = shadowhook_dlsym(library, "_ZN7android13SensorService21SensorEventConnectionC1ERKNS_2spIS0_EEjNS_7String8EbRKNS_8String16ES9_");
    void* dtor = shadowhook_dlsym(library, "_ZN7android13SensorService21SensorEventConnectionD2Ev");
    createWeak = reinterpret_cast<decltype(createWeak)>(shadowhook_dlsym(utils, "_ZNK7android7RefBase10createWeakEPKv"));
    decWeak = reinterpret_cast<decltype(decWeak)>(shadowhook_dlsym(utils, "_ZN7android7RefBase12weakref_type7decWeakEPKv"));
    promote = reinterpret_cast<decltype(promote)>(shadowhook_dlsym(utils, "_ZN7android7RefBase12weakref_type16attemptIncStrongEPKv"));
    decStrong = reinterpret_cast<decltype(decStrong)>(shadowhook_dlsym(utils, "_ZNK7android7RefBase9decStrongEPKv"));
    shadowhook_dlclose(library); shadowhook_dlclose(utils);
    if (!send || !enable || !ctor || !dtor || !createWeak || !decWeak || !promote || !decStrong) {
        LOGI("disabled: required SensorService/RefBase ABI symbols unavailable");
        return false;
    }
    auto n = env->GetArrayLength(handles);
    if (n != env->GetArrayLength(types) || n == 0 || n > 16) {
        LOGI("invalid sensor arrays: handles=%d types=%d", n, env->GetArrayLength(types));
        return false;
    }
    std::vector<jint> h(n), t(n);
    env->GetIntArrayRegion(handles, 0, n, h.data());
    env->GetIntArrayRegion(types, 0, n, t.data());
    std::unordered_map<int, int> nextTypes;
    for (int i=0;i<n;++i) {
        if (h[i] < 0 || (t[i] != ASENSOR_TYPE_STEP_COUNTER && t[i] != ASENSOR_TYPE_STEP_DETECTOR)) return false;
        if (!nextTypes.emplace(h[i], t[i]).second) return false;
    }
    { std::lock_guard<std::mutex> lock(stateMutex); sensorTypes.swap(nextTypes); }
    auto hook = [](void* address, void* proxy, void** original) {
        void* stub = shadowhook_hook_func_addr_2(address, proxy, original, SHADOWHOOK_HOOK_WITH_MULTI_MODE);
        if (stub) hookStubs.push_back(stub);
        return stub && *original;
    };
    if (!hook(dtor, reinterpret_cast<void*>(destructorHook), reinterpret_cast<void**>(&originalDestructor)) ||
        !hook(ctor, reinterpret_cast<void*>(constructorHook), reinterpret_cast<void**>(&originalConstructor)) ||
        !hook(send, reinterpret_cast<void*>(sendHook), reinterpret_cast<void**>(&originalSend)) ||
        !hook(enable, reinterpret_cast<void*>(enableHook), reinterpret_cast<void**>(&originalEnable))) {
        for (auto it=hookStubs.begin(); it!=hookStubs.end();) {
            if (shadowhook_unhook(*it)==0) it=hookStubs.erase(it); else ++it;
        }
        LOGI("step hook installation failed; disabled");
        return false;
    }
    installed = true;
    LOGI("installed using SensorService ABI symbols; sensors=%d", n);
    return true;
#endif
}

EXPORT jintArray Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemStepNative_uids(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(stateMutex);
    std::unordered_set<int> unique;
    for (const auto& item : connections) unique.insert(item.second->uid);
    std::vector<jint> result(unique.begin(), unique.end());
    auto array = env->NewIntArray(result.size());
    env->SetIntArrayRegion(array, 0, result.size(), result.data());
    return array;
}

static void dispatchTick(bool on, const std::vector<jint>& uids, jlong total, const std::vector<jlong>& times) {
    std::lock_guard<std::mutex> tickLock(tickMutex);
    if (!installed.load()) return;
    const int64_t now = nowNs();
    if (times.size() > 8 || total < 0) return;
    std::vector<std::shared_ptr<Connection>> snapshot;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        enabled = on; targets = {uids.begin(), uids.end()}; deadlineNs = now + 1500000000LL;
        for (auto& item : connections) snapshot.push_back(item.second);
    }
    for (auto& state : snapshot) {
        // A weak reference never keeps a dead application's connection alive.
        if (!state->weak || !promote(state->weak, state.get())) {
            std::lock_guard<std::mutex> lock(stateMutex);
            auto it = connections.find(state->object);
            if (it != connections.end() && it->second == state) connections.erase(it);
            continue;
        }
        std::vector<ASensorEvent> events;
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            bool active = activeLocked(state->uid) && !state->handles.empty();
            if (active && !state->wasActive) {
                state->counter = std::max(state->counter, state->realCounter);
                state->announceCounter = true;
                state->registeredNs = now;
            }
            state->wasActive = active;
            auto append = [&](int type, int64_t stamp) {
                for (int handle : state->handles) if (sensorTypes.at(handle) == type) {
                    ASensorEvent event{}; event.version=sizeof(event); event.sensor=handle;
                    event.type=type; event.timestamp=stamp;
                    if (type == ASENSOR_TYPE_STEP_COUNTER) event.u64.step_counter=state->counter;
                    else event.data[0]=1.0f;
                    events.push_back(event);
                }
            };
            if (active) {
                bool crossed = false;
                // Count only emitted crossings, not elapsed paused time or a reset absolute phase.
                for (auto time : times) if (time > state->registeredNs && time <= now && now-time <= 2000000000LL) {
                    crossed = true;
                    ++state->counter;
                    append(ASENSOR_TYPE_STEP_COUNTER, time);
                    append(ASENSOR_TYPE_STEP_DETECTOR, time);
                    state->registeredNs = time; // Reject duplicate or out-of-order crossings.
                }
                if (state->announceCounter && !crossed) append(ASENSOR_TYPE_STEP_COUNTER, now);
                state->announceCounter = false;
            }
        }
        if (!events.empty()) {
            std::vector<ASensorEvent> scratch(events.size());
            // Non-null scratch preserves the original subscription, AppOps, first-flush,
            // sensor privacy, UID activity and back-pressure checks in sendEvents.
            int result = originalSend(state->object, events.data(), events.size(), scratch.data(), nullptr);
            if (result == 0) { std::lock_guard<std::mutex> lock(stateMutex); emitted += events.size(); }
        }
        // No module lock held: final release may invoke service cleanup/destruction.
        decStrong(state->base, state.get());
    }
}

EXPORT void Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemStepNative_tick(
        JNIEnv* env, jclass, jboolean on, jintArray allowed, jlong total, jlongArray timestamps) {
    if (!allowed || !timestamps || env->GetArrayLength(timestamps) > 8) return;
    std::vector<jint> uids(env->GetArrayLength(allowed));
    env->GetIntArrayRegion(allowed,0,uids.size(),uids.data());
    std::vector<jlong> times(env->GetArrayLength(timestamps));
    env->GetLongArrayRegion(timestamps,0,times.size(),times.data());
    dispatchTick(on, uids, total, times);
}

EXPORT jlongArray Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemStepNative_stats(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(stateMutex);
    jlong values[] = {jlong(installed.load()),jlong(connections.size()),jlong(emitted),jlong(suppressed)};
    auto result=env->NewLongArray(4); env->SetLongArrayRegion(result,0,4,values); return result;
}

EXPORT jboolean Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemStepNative_uninstall(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> tickLock(tickMutex);
    installed.store(false);
    std::unordered_map<void*, std::shared_ptr<Connection>> removed;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        ++generation;
        enabled=false; targets.clear(); deadlineNs=0;
        removed.swap(connections); connectionUids.clear();
    }
    for (auto it=hookStubs.begin(); it!=hookStubs.end();) {
        if (shadowhook_unhook(*it)==0) it=hookStubs.erase(it); else ++it;
    }
    removed.clear();
    return hookStubs.empty();
}
