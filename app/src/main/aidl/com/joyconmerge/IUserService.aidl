// IUserService.aidl — runs inside Shizuku's privileged process (UID 2000 / shell)
package com.joyconmerge;

interface IUserService {
    /** Scan /dev/input/event* dan kembalikan semua path + name sebagai "path|name" per baris */
    String scanDevices() = 1;

    /** Jalankan merge binary dengan argumen yang diberikan. Callback melalui IBinder? */
    void startMerge(in String[] args) = 2;

    /** Kirim STOP ke binary yang sedang berjalan */
    void stopMerge() = 3;

    /** Ambil status terakhir dari binary */
    String pollStatus() = 4;

    /** Cek apakah binary sedang berjalan */
    boolean isRunning() = 5;

    /** Destroy service */
    void destroy() = 6;
}
