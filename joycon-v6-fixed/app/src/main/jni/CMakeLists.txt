cmake_minimum_required(VERSION 3.22.1)
project("joyconmerge")

# -----------------------------------------------------------------------
# uinput_setup — root binary bundled as APK asset.
# Static executable so it runs on device without external libs.
# Gradle task "copyUinputSetup" copies it to assets/uinput_setup_<abi>
# -----------------------------------------------------------------------
add_executable(
    uinput_setup
    uinput_setup.c
)

target_link_options(uinput_setup PRIVATE
    -static
    -pthread
)

# No JNI shared library needed anymore — all logic is in the binary.
