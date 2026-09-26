#include <time.h>
#include <cassert>
#include <cstdio>
#include <future>
#include <condition_variable>
#include <thread>
#include <chrono>
static int64_t testTime = 10'000'000'000LL;
static int testClock(clockid_t, timespec* ts) {
    ts->tv_sec = testTime / 1'000'000'000LL;
    ts->tv_nsec = testTime % 1'000'000'000LL;
    return 0;
}
#define clock_gettime testClock
#include "../../main/cpp/system_steps.cpp"
#undef clock_gettime

static std::vector<ASensorEvent> delivered;
static constexpr int kMetaData = 0; // sensors.h HAL metadata type (not in the public NDK enum).
static bool canPromote = true;
static std::mutex gateMutex;
static std::condition_variable gate;
static bool blockSend = false, sendEntered = false, blockEnable = false, enableEntered = false;
static int registrationResult = 0;
static int capture(void*, const ASensorEvent* events, size_t count, ASensorEvent*, const void*) {
    std::unique_lock<std::mutex> lock(gateMutex);
    if (blockSend) { sendEntered = true; gate.notify_all(); gate.wait(lock, [] { return !blockSend; }); }
    delivered.insert(delivered.end(), events, events + count);
    return 0;
}
static int authorize(void*, int, bool, int64_t, int64_t, int) {
    std::unique_lock<std::mutex> lock(gateMutex);
    if (blockEnable) { enableEntered = true; gate.notify_all(); gate.wait(lock, [] { return !blockEnable; }); }
    return registrationResult;
}
static void clearEvents() { delivered.clear(); }
int main() {
    installed = true;
    createWeak = [](void* base, const void*) { return base; };
    decWeak = [](void*, const void*) {};
    promote = [](void*, const void*) { return canPromote; };
    decStrong = [](void*, const void*) {};
    originalSend = capture;
    originalEnable = authorize;
    originalConstructor = [](void*, const void*, uint32_t, void*, bool, const void*, const void*) {};
    originalDestructor = [](void*, const void*) {};
    ptrdiff_t table[4]{};
    ptrdiff_t* vtable = table + 3;
    void* object = &vtable;
    sensorTypes = {{1, ASENSOR_TYPE_STEP_COUNTER}, {2, ASENSOR_TYPE_STEP_DETECTOR}};
    constructorHook(object, nullptr, 99910042, nullptr, false, nullptr, nullptr);
    registrationResult = -1;
    assert(enableHook(object, 1, true, 0, 0, 0) == -1);
    assert(connections.empty());
    registrationResult = 0;
    assert(enableHook(object, 1, true, 0, 0, 0) == 0);
    assert(enableHook(object, 2, true, 0, 0, 0) == 0);
    auto state = connections.at(object);
    state->realCounter = 100;
    state->counterKnown = true;
    dispatchTick(true, {99910042}, 2350, {});
    assert(delivered.size() == 1 && delivered[0].u64.step_counter == 100);
    clearEvents();
    testTime += 1'000'000'000;
    const jlong a = testTime - 750'000'000, b = testTime - 250'000'000;
    dispatchTick(true, {99910042}, 2352, {a, b});
    assert(delivered.size() == 4);
    for (int i = 0; i < 4; i += 2) {
        assert(delivered[i].type == ASENSOR_TYPE_STEP_COUNTER);
        assert(delivered[i + 1].type == ASENSOR_TYPE_STEP_DETECTOR);
        assert(delivered[i].timestamp == delivered[i + 1].timestamp);
        assert(delivered[i].u64.step_counter == 101 + i / 2);
        assert(delivered[i + 1].data[0] == 1.0f);
    }
    clearEvents();
    dispatchTick(true, {99910042}, 2352, {a, b});
    assert(delivered.empty());
    dispatchTick(false, {}, 0, {});
    testTime += 3'000'000'000;
    dispatchTick(true, {99910042}, 9999, {a, b});
    assert(delivered.size() == 1 && delivered[0].u64.step_counter == 102);
    clearEvents();
    testTime += 500'000'000;
    dispatchTick(true, {99910042}, 10000, {testTime - 1});
    assert(delivered.size() == 2 && delivered[0].u64.step_counter == 103);
    clearEvents();
    // Metadata positions and the original input must survive hardware suppression.
    ASensorEvent input[3]{}, scratch[3]{};
    input[0].type = ASENSOR_TYPE_STEP_COUNTER; input[0].sensor = 1; input[0].u64.step_counter = 7;
    input[1].type = kMetaData; input[1].meta_data.sensor = 1;
    input[2].type = ASENSOR_TYPE_ACCELEROMETER; input[2].sensor = 3; input[2].data[0] = 42;
    sendHook(object, input, 3, scratch, nullptr);
    assert(input[0].sensor == 1 && delivered[0].sensor == -1);
    assert(delivered[1].type == kMetaData && delivered[1].meta_data.sensor == 1);
    assert(delivered[2].sensor == 3 && delivered[2].data[0] == 42);
    clearEvents();
    dispatchTick(true, {10043}, 10000, {});
    assert(delivered.empty());
    sendHook(object, input, 3, scratch, nullptr);
    assert(delivered[0].sensor == 1 && delivered[0].u64.step_counter == 7);
    clearEvents();
    dispatchTick(true, {99910042}, 10000, {});
    clearEvents();
    testTime += 500'000'000;
    blockSend = true;
    auto tick = std::async(std::launch::async, [] { dispatchTick(true, {99910042}, 10001, {testTime - 1}); });
    { std::unique_lock<std::mutex> lock(gateMutex); gate.wait(lock, [] { return sendEntered; }); }
    auto stop = std::async(std::launch::async, [] { dispatchTick(false, {}, 0, {}); });
    assert(stop.wait_for(std::chrono::milliseconds(30)) == std::future_status::timeout);
    { std::lock_guard<std::mutex> lock(gateMutex); blockSend = false; gate.notify_all(); }
    tick.get(); stop.get();
    assert(!enabled);
    clearEvents();
    // A registration that started before uninstall may not resurrect the state.
    blockEnable = true;
    auto registration = std::async(std::launch::async, [&] { return enableHook(object, 1, true, 0, 0, 0); });
    { std::unique_lock<std::mutex> lock(gateMutex); gate.wait(lock, [] { return enableEntered; }); }
    assert(Java_com_vincenthzr_locationspoofer_xposed_hooks_SystemStepNative_uninstall(nullptr, nullptr));
    { std::lock_guard<std::mutex> lock(gateMutex); blockEnable = false; gate.notify_all(); }
    assert(registration.get() == 0 && connections.empty());
    dispatchTick(true, {99910042}, 10010, {testTime});
    assert(delivered.empty() && !enabled);
    installed = true;
    constructorHook(object, nullptr, 10043, nullptr, false, nullptr, nullptr);
    enableHook(object, 1, true, 0, 0, 0);
    assert(connections.at(object)->uid == 10043);
    canPromote = false;
    dispatchTick(true, {10043}, 0, {});
    assert(connections.empty() && delivered.empty());
    destructorHook(object, nullptr);
    assert(connectionUids.empty());
    puts("PASS: resume 100->102->103, paired timestamps, duplicate rejection, target isolation, metadata, denied registration, stop serialization, uninstall race, stale ticks, dead connections");
}
