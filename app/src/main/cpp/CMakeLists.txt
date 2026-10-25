cmake_minimum_required(VERSION 3.22.1)
project(orbitcore LANGUAGES CXX)

add_library(orbitcore SHARED orbitcore.cpp)
target_compile_features(orbitcore PRIVATE cxx_std_17)
target_compile_options(orbitcore PRIVATE -Wall -Wextra -Werror=return-type -fvisibility=hidden)
find_library(log-lib log)
target_link_libraries(orbitcore ${log-lib})
