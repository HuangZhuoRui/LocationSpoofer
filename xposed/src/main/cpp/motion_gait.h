#pragma once

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <random>
#include <vector>

// ProceduralGait / GaitTemplate.sample from upstream main 9581904 MotionRealism.kt.
// Slow phase/cadence variables are computed by the shared Kotlin upstream Session.
// Evaluate each sample here: no Java callback or JVM allocation on SensorService's delivery thread.
namespace motion_gait {
constexpr double pi = 3.14159265358979323846;
using Table = std::array<double, 256>;

inline double bump(double p, double center, double width) {
    double d = std::abs(p - center);
    if (d > 0.5) d = 1 - d;
    return std::exp(-0.5 * (d / width) * (d / width));
}
template<class F> Table zeroMean(F shape) {
    Table table{};
    double mean = 0;
    for (size_t i = 0; i < table.size(); ++i) mean += table[i] = shape(double(i) / table.size());
    mean /= table.size();
    for (double& v : table) v -= mean;
    return table;
}
inline double lookup(const Table& table, double phase) {
    const double pos = (phase - std::floor(phase)) * table.size();
    const size_t i = size_t(pos) % table.size();
    const double f = pos - std::floor(pos);
    return table[i] * (1 - f) + table[(i + 1) % table.size()] * f;
}
inline double hashUnit(uint64_t seed, int64_t n) {
    uint64_t x = seed ^ (uint64_t(n) * uint64_t(-0x61c8864680b583ebLL));
    x = (x ^ (x >> 33)) * uint64_t(-0xae502812aa7333LL);
    x = (x ^ (x >> 33)) * uint64_t(-0x3b314601e57a13adLL);
    x ^= x >> 33;
    return double(x >> 11) / double(uint64_t(1) << 53) * 2 - 1;
}

struct Parameters {
    double steps = 2350;
    double cadence = 165;
    double speed = 0;
    int64_t anchorNs = 0;
    int64_t session = 0;
    int level = 0;
    // Optional upstream template, 64 samples each for x/y/z.
    std::vector<float> gait;
};

inline std::array<float, 3> sample(const Parameters& p, double steps, double gaussianX = 0,
                                 double gaussianY = 0, double gaussianZ = 0) {
    static const Table walkZ = zeroMean([](double t) {
        return bump(t, 0, .045) - .45 * bump(t, .13, .07) + .55 * bump(t, .42, .09) - .65 * bump(t, .72, .13);
    });
    static const Table walkY = zeroMean([](double t) {
        return -.5 * bump(t, .03, .05) + .45 * bump(t, .40, .09) - .15 * bump(t, .75, .15);
    });
    static const Table runZ = zeroMean([](double t) {
        return bump(t, 0, .035) + .55 * bump(t, .16, .09) - .85 * bump(t, .62, .18);
    });
    static const Table runY = zeroMean([](double t) {
        return -.6 * bump(t, .02, .04) + .5 * bump(t, .25, .08);
    });
    const int level = p.level >= 0 && p.level <= 3 ? p.level : 2;
    const double variations[] = {0, .05, .10, .18};
    const double noise[] = {0, .03, .06, .10};
    const int64_t index = int64_t(std::floor(steps));
    const double phase = steps - double(index);
    const double strength = 1 + variations[level] * hashUnit(uint64_t(p.session) ^ 0x57E9057EULL, index);
    std::array<float, 3> out{};
    if (p.gait.size() == 192) {
        const double pos = (steps / 2 - std::floor(steps / 2)) * 64;
        const size_t i = size_t(pos) % 64;
        const float f = float(pos - std::floor(pos));
        for (size_t a = 0; a < 3; ++a) {
            double sum = 0;
            for (size_t k = 0; k < 64; ++k) sum += p.gait[a * 64 + k];
            const float mean = float(sum / 64);
            const float v = p.gait[a * 64 + i] * (1 - f) + p.gait[a * 64 + (i + 1) % 64] * f;
            out[a] = mean + float((v - mean) * strength);
        }
    } else {
        const bool running = p.speed >= 2.2;
        const double amplitude = running ? 6 + 1.5 * p.speed : 2 + p.speed;
        const double side = index % 2 == 0 ? 1 : -1;
        const double lateral = .12 * amplitude * std::sin(pi * steps) + .1 * amplitude * side * bump(phase, .02, .05);
        out = {float(strength * lateral),
               float(strength * .35 * amplitude * lookup(running ? runY : walkY, phase)),
               float(std::max(0.0, double(9.80665f) + strength * amplitude * lookup(running ? runZ : walkZ, phase)))};
    }
    const double gaussian[] = {gaussianX, gaussianY, gaussianZ};
    for (size_t a = 0; a < 3; ++a) out[a] += float(gaussian[a] * noise[level]);
    return out;
}
} // namespace motion_gait
