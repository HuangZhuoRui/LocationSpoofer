#include "../../main/cpp/location_accel.cpp"
#include <cassert>
#include <cstdio>

namespace {
int forwarded = 0;
float deliveredAcceleration = 0;
void fakeConstructor(void*, const void*, uint32_t, void*, bool, const void*, const void*) {}
void fakeDestructor(void*, const void*) {}
int32_t fakeSend(void*, const ASensorEvent* events, size_t, ASensorEvent*, const void*) {
    ++forwarded;
    deliveredAcceleration = events[0].data[0];
    return 17;
}
}
int main() {
    installed.store(true);
    originalConstructor = fakeConstructor;
    originalDestructor = fakeDestructor;
    originalSendEvents = fakeSend;
    int connection = 0;
    assert(connectionUid(&connection) == -1);
    constructorHook(&connection, nullptr, 10042, nullptr, false, nullptr, nullptr);
    assert(connectionUid(&connection) == 10042);
    constructorHook(&connection, nullptr, 99910042, nullptr, false, nullptr, nullptr);
    assert(connectionUid(&connection) == 99910042);
    observeUid(99910042);
    assert(observedUids.count(99910042) == 1);
    observeUid(99901000);
    assert(observedUids.count(99901000) == 0);
    ASensorEvent event{};
    event.type = ASENSOR_TYPE_ACCELEROMETER;
    event.data[0] = 123.0f;
    event.timestamp = nowNs();
    enabled.store(true);
    speedMs.store(2.0);
    gaitParameters.anchorNs = nowNs();
    gaitParameters.steps = 1.0;
    gaitParameters.cadence = 120.0;
    gaitParameters.speed = 2.0;
    targetUids.insert(99910042);
    assert(sendEventsHook(&connection, &event, 1, nullptr, nullptr) == 17);
    assert(deliveredAcceleration != 123.0f);
    assert(event.data[0] == 123.0f);
    destructorHook(&connection, nullptr);
    assert(connectionUid(&connection) == -1);
    assert(sendEventsHook(&connection, &event, 1, nullptr, nullptr) == 17);
    assert(deliveredAcceleration == 123.0f);
    // Reused object addresses must acquire the new owner's UID.
    constructorHook(&connection, nullptr, 10043, nullptr, false, nullptr, nullptr);
    assert(connectionUid(&connection) == 10043);
    assert(sendEventsHook(&connection, &event, 1, nullptr, nullptr) == 17);
    assert(deliveredAcceleration == 123.0f);
    destructorHook(&connection, nullptr);
    assert(forwarded == 3);
    puts("PASS: UID lifecycle, cloned users, target-only delivery, buffer isolation, address reuse");
}
