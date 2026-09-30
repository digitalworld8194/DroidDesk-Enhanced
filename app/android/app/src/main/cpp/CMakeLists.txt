cmake_minimum_required(VERSION 3.22.1)

project("droiddesk_compositor")

# Add the native JNI bridge source
add_library(
    droiddesk_compositor
    SHARED
    compositor_jni.cpp
)

# Search for standard Android libraries
find_library(log-lib log)
find_library(android-lib android)

# Link the libraries
target_link_libraries(
    droiddesk_compositor
    ${log-lib}
    ${android-lib}
)

# Preview builds: socket hook compiled for that applicationId's prefix.
if(DROIDDESK_HOOK_PREFIX)
    add_library(socket_hook_variant SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../assets/socket_hook.c)
    target_compile_definitions(socket_hook_variant PRIVATE NEW_PREFIX="${DROIDDESK_HOOK_PREFIX}")
    target_link_libraries(socket_hook_variant dl ${log-lib})
endif()
