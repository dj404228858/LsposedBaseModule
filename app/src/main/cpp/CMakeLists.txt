cmake_minimum_required(VERSION 3.22.1)
project(youzengccb)

find_library(log-lib log)

add_library(youzengccb SHARED bangcle_bypass.cpp)
target_link_libraries(youzengccb ${log-lib})
target_compile_features(youzengccb PRIVATE cxx_std_17)
