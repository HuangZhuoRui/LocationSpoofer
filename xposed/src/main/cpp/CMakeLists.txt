cmake_minimum_required(VERSION 3.22.1)
project(location_accel LANGUAGES CXX)

find_package(shadowhook REQUIRED CONFIG)

add_library(location_accel SHARED location_accel.cpp)

target_compile_features(location_accel PRIVATE cxx_std_17)
target_compile_options(location_accel PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(location_accel log dl shadowhook::shadowhook)

add_library(location_steps SHARED system_steps.cpp)
target_compile_features(location_steps PRIVATE cxx_std_17)
target_compile_options(location_steps PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(location_steps log dl shadowhook::shadowhook)
