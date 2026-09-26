#include <shadowhook.h>
#include <cassert>
#include <cstdio>
using Call = int (*)(int);
static Call nextA, nextB;
extern "C" __attribute__((noinline)) int chainTarget(int x) { return x + 1; }
static int proxyA(int x) { return nextA(x) + 10; }
static int proxyB(int x) { return nextB(x) + 100; }
int main() {
    int init = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (init != 0) { fprintf(stderr, "ShadowHook init: %d %s\n", init, shadowhook_to_errmsg(init)); return 1; }
    Call volatile call = chainTarget;
    for (int order = 0; order < 2; ++order) {
        auto a = shadowhook_hook_func_addr_2(reinterpret_cast<void*>(chainTarget), reinterpret_cast<void*>(proxyA), reinterpret_cast<void**>(&nextA), SHADOWHOOK_HOOK_WITH_MULTI_MODE);
        auto b = shadowhook_hook_func_addr_2(reinterpret_cast<void*>(chainTarget), reinterpret_cast<void*>(proxyB), reinterpret_cast<void**>(&nextB), SHADOWHOOK_HOOK_WITH_MULTI_MODE);
        assert(a && b && call(1) == 112);
        assert(shadowhook_unhook(order ? a : b) == 0);
        assert(call(1) == (order ? 102 : 12));
        assert(shadowhook_unhook(order ? b : a) == 0);
        assert(call(1) == 2);
    }
    puts("PASS: shared target, both detach orders, reinstallation");
}
