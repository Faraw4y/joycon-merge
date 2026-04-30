/*
 * uinput_setup — binary standalone yang di-bundle dalam APK
 *
 * Dijalankan oleh root shell dengan args:
 *   uinput_setup <left_path> <right_path> <target_pid> <left_wfd> <right_wfd> <uinput_rfd>
 *
 * Yang dilakukan:
 *   1. Buka /dev/uinput (root bisa)
 *   2. Setup semua ioctl (UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_ABSBIT)
 *   3. Write struct uinput_user_dev
 *   4. UI_DEV_CREATE
 *   5. Print "UINPUT_READY\n" ke stdout  ← sinyal ke Java
 *   6. Fork dua proses: cat eventL → pipe, cat eventR → pipe
 *   7. Loop baca dari uinput_rfd pipe (event dari JNI), tulis ke uinput_fd
 *      Ini adalah forward loop yang berjalan sampai pipe ditutup.
 *   8. UI_DEV_DESTROY dan exit
 *
 * Tidak ada dependensi eksternal — hanya libc yang selalu ada di Android.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define VIRT_NAME  "Nintendo Switch Combined Joy-Con"
#define VENDOR_ID  0x057e
#define PRODUCT_ID 0x2009

/* ABS indices yang kita gunakan */
#define MY_ABS_X     0
#define MY_ABS_Y     1
#define MY_ABS_RX    3
#define MY_ABS_RY    4
#define MY_ABS_HAT0X 16
#define MY_ABS_HAT0Y 17

static void die(const char *msg) {
    fprintf(stdout, "ERROR: %s: %s\n", msg, strerror(errno));
    fflush(stdout);
    exit(1);
}

int main(int argc, char *argv[]) {
    if (argc < 7) {
        fprintf(stdout, "ERROR: usage: uinput_setup left right pid lwfd rwfd urfd\n");
        fflush(stdout);
        return 1;
    }

    const char *left_path  = argv[1];
    const char *right_path = argv[2];
    const char *target_pid = argv[3];
    const char *left_wfd   = argv[4];
    const char *right_wfd  = argv[5];
    const char *uinput_rfd = argv[6];

    /* ---------- 1. Buka /dev/uinput ---------- */
    int ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ufd < 0) die("open /dev/uinput");

    /* ---------- 2. Set event/key/abs bits ---------- */
    if (ioctl(ufd, UI_SET_EVBIT, EV_KEY) < 0) die("UI_SET_EVBIT EV_KEY");
    if (ioctl(ufd, UI_SET_EVBIT, EV_ABS) < 0) die("UI_SET_EVBIT EV_ABS");
    if (ioctl(ufd, UI_SET_EVBIT, EV_SYN) < 0) die("UI_SET_EVBIT EV_SYN");

    /* Gamepad buttons standar Linux */
    int keys[] = {0x130,0x131,0x132,0x133,0x134,0x135,0x136,
                  0x137,0x138,0x139,0x13a,0x13b,0x13c,0x13d,0x13e};
    for (int i = 0; i < 15; i++)
        if (ioctl(ufd, UI_SET_KEYBIT, keys[i]) < 0) die("UI_SET_KEYBIT");

    int abits[] = {MY_ABS_X, MY_ABS_Y, MY_ABS_RX, MY_ABS_RY,
                   MY_ABS_HAT0X, MY_ABS_HAT0Y};
    for (int i = 0; i < 6; i++)
        if (ioctl(ufd, UI_SET_ABSBIT, abits[i]) < 0) die("UI_SET_ABSBIT");

    /* ---------- 3. Write struct uinput_user_dev ---------- */
    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, VIRT_NAME);
    uidev.id.bustype = BUS_VIRTUAL;
    uidev.id.vendor  = VENDOR_ID;
    uidev.id.product = PRODUCT_ID;
    uidev.id.version = 1;

    /* Stick axes */
    int stick_axes[] = {MY_ABS_X, MY_ABS_Y, MY_ABS_RX, MY_ABS_RY};
    for (int i = 0; i < 4; i++) {
        int a = stick_axes[i];
        uidev.absmax[a]  =  32767;
        uidev.absmin[a]  = -32768;
        uidev.absfuzz[a] = 256;
        uidev.absflat[a] = 4096;
    }
    /* D-pad axes */
    uidev.absmax[MY_ABS_HAT0X] =  1; uidev.absmin[MY_ABS_HAT0X] = -1;
    uidev.absmax[MY_ABS_HAT0Y] =  1; uidev.absmin[MY_ABS_HAT0Y] = -1;

    /* Switch ke blocking mode sebelum write */
    int flags = fcntl(ufd, F_GETFL);
    fcntl(ufd, F_SETFL, flags & ~O_NONBLOCK);

    if (write(ufd, &uidev, sizeof(uidev)) < 0) die("write uinput_user_dev");

    /* ---------- 4. UI_DEV_CREATE ---------- */
    if (ioctl(ufd, UI_DEV_CREATE) < 0) die("UI_DEV_CREATE");

    /* ---------- 5. Sinyal ke Java: setup OK ---------- */
    fprintf(stdout, "UINPUT_READY\n");
    fflush(stdout);

    /* ---------- 6. Fork: cat left/right event ke pipe app ---------- */
    /*
     * Format path fd milik proses target: /proc/<pid>/fd/<fd>
     * Root bisa buka fd milik proses lain via /proc.
     */
    char left_dst[64], right_dst[64], uinput_src[64];
    snprintf(left_dst,   sizeof(left_dst),   "/proc/%s/fd/%s", target_pid, left_wfd);
    snprintf(right_dst,  sizeof(right_dst),  "/proc/%s/fd/%s", target_pid, right_wfd);
    snprintf(uinput_src, sizeof(uinput_src), "/proc/%s/fd/%s", target_pid, uinput_rfd);

    /* Fork child untuk left Joy-Con */
    pid_t pid_l = fork();
    if (pid_l == 0) {
        /* Child: buka left event device, copy ke pipe */
        int src = open(left_path, O_RDONLY);
        if (src < 0) { perror("open left"); exit(1); }
        int dst = open(left_dst, O_WRONLY);
        if (dst < 0) { perror("open left dst"); exit(1); }
        char buf[4096];
        ssize_t n;
        while ((n = read(src, buf, sizeof(buf))) > 0)
            write(dst, buf, n);
        exit(0);
    }

    /* Fork child untuk right Joy-Con */
    pid_t pid_r = fork();
    if (pid_r == 0) {
        /* Child: buka right event device, copy ke pipe */
        int src = open(right_path, O_RDONLY);
        if (src < 0) { perror("open right"); exit(1); }
        int dst = open(right_dst, O_WRONLY);
        if (dst < 0) { perror("open right dst"); exit(1); }
        char buf[4096];
        ssize_t n;
        while ((n = read(src, buf, sizeof(buf))) > 0)
            write(dst, buf, n);
        exit(0);
    }

    /* ---------- 7. Loop forward: pipe (JNI) → uinput_fd ---------- */
    int src_fd = open(uinput_src, O_RDONLY);
    if (src_fd < 0) die("open uinput pipe src");

    char buf[4096];
    ssize_t n;
    while ((n = read(src_fd, buf, sizeof(buf))) > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(ufd, buf + written, n - written);
            if (w < 0) goto cleanup;
            written += w;
        }
    }

cleanup:
    /* ---------- 8. Cleanup ---------- */
    kill(pid_l, SIGTERM);
    kill(pid_r, SIGTERM);
    waitpid(pid_l, NULL, 0);
    waitpid(pid_r, NULL, 0);
    ioctl(ufd, UI_DEV_DESTROY);
    close(ufd);
    close(src_fd);
    return 0;
}
