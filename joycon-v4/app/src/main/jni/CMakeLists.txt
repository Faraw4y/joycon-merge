cmake_minimum_required(VERSION 3.22.1)
project("joyconmerge")

# Library JNI utama (dimuat oleh Java via System.loadLibrary)
add_library(
    joyconmerge
    SHARED
    joycon_jni.c
)

find_library(log-lib log)

target_link_libraries(
    joyconmerge
    ${log-lib}
)

# -----------------------------------------------------------------------
# uinput_setup — binary executable yang di-bundle sebagai asset APK
#
# Dikompilasi sebagai static executable sehingga bisa dijalankan
# langsung oleh root shell tanpa dependensi library eksternal.
#
# Setelah build, Gradle task "copyUinputSetup" menyalin binary
# ke src/main/assets/uinput_setup_<abi> agar ikut dalam APK.
# -----------------------------------------------------------------------
add_executable(
    uinput_setup
    uinput_setup.c
)

# Static link: tidak ada .so yang perlu ada di device
target_link_options(uinput_setup PRIVATE -static)

# Tidak butuh log lib — binary ini standalone
