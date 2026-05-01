/*
 * joycon_merge — standalone root binary, bundled in APK assets.
 *
 * Arsitektur v6: binary ini handle SEMUA logika merge langsung,
 * persis seperti versi Termux yang sudah terbukti bekerja.
 * Tidak ada JNI, tidak ada event pipe, tidak ada race condition.
 *
 * Java:
 *   1. Jalankan binary via su: uinput_setup <left> <right> [args...]
 *   2. Baca stdout: "UINPUT_READY\n" = sukses, "ERROR:...\n" = gagal
 *   3. Kirim "STOP\n" ke stdin untuk menghentikan
 *   4. Baca "STATUS:STOPPED\n" sebagai konfirmasi berhenti
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define VIRT_NAME   "Nintendo Switch Combined Joy-Con"
#define VENDOR_ID   0x057e
#define PRODUCT_ID  0x2009

static int stick_fuzz = 256;
static int stick_flat = 4096;
static int inv_lx=0, inv_ly=0, inv_rx=0, inv_ry=0;
static int map_a=0x130, map_b=0x131, map_x=0x133, map_y=0x134;
static int map_r=0x137, map_zr=0x139, map_plus=0x13b, map_r3=0x13e;
static int map_l=0x136, map_zl=0x138, map_minus=0x13a, map_l3=0x13d;
static int map_home=0x13c;
static int map_capture=0xa7;  /* KEY_RECORD */

static int uinput_fd = -1;
static int left_fd   = -1;
static int right_fd  = -1;
static volatile int running = 0;

/* Mutex: prevents two threads writing simultaneously to uinput_fd */
static pthread_mutex_t emit_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t dpad_mutex = PTHREAD_MUTEX_INITIALIZER;

static int dp_up=0, dp_down=0, dp_left=0, dp_right=0;

static void emit_event_to_java(int type, int code, int value)
{
    if (type == EV_KEY)
        fprintf(stdout, "EVENT:KEY %d %d\n", code, value);
    else if (type == EV_ABS && code <= 4)
        fprintf(stdout, "EVENT:ABS %d %d\n", code, value);
    fflush(stdout);
}

static void emit(int type, int code, int value)
{
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type; ev.code = code; ev.value = value;
    pthread_mutex_lock(&emit_mutex);
    write(uinput_fd, &ev, sizeof(ev));
    pthread_mutex_unlock(&emit_mutex);
}

static int clamp(int v, int mn, int mx) { return v<mn?mn:v>mx?mx:v; }

static int apply_deadzone(int v) {
    if (v > -stick_flat && v < stick_flat) return 0;
    if (stick_fuzz > 1) v = (v / stick_fuzz) * stick_fuzz;
    return clamp(v, -32768, 32767);
}

static void handle_dpad(int code, int value)
{
    pthread_mutex_lock(&dpad_mutex);
    if (code==544) dp_up    = value;
    if (code==545) dp_down  = value;
    if (code==546) dp_left  = value;
    if (code==547) dp_right = value;
    int hx = dp_right - dp_left;
    int hy = dp_down  - dp_up;
    pthread_mutex_unlock(&dpad_mutex);
    emit(EV_ABS, ABS_HAT0X, hx);
    emit(EV_ABS, ABS_HAT0Y, hy);
    emit(EV_SYN, SYN_REPORT, 0);
    /* Forward dpad state to Java UI */
    fprintf(stdout,"EVENT:DPAD up %d\n",    dp_up);
    fprintf(stdout,"EVENT:DPAD down %d\n",  dp_down);
    fprintf(stdout,"EVENT:DPAD left %d\n",  dp_left);
    fprintf(stdout,"EVENT:DPAD right %d\n", dp_right);
    fflush(stdout);
}

static void handle_left(struct input_event *ev)
{
    if (ev->type == EV_KEY) {
        int c=ev->code, v=ev->value;
        if      (c==0x136) { emit(EV_KEY, map_l,       v); emit_event_to_java(EV_KEY, map_l,       v); }
        else if (c==0x138) { emit(EV_KEY, map_zl,      v); emit_event_to_java(EV_KEY, map_zl,      v); }
        else if (c==0x13a) { emit(EV_KEY, map_minus,   v); emit_event_to_java(EV_KEY, map_minus,   v); }
        else if (c==0x13d) { emit(EV_KEY, map_l3,      v); emit_event_to_java(EV_KEY, map_l3,      v); }
        else if (c==0x135) { emit(EV_KEY, map_capture, v); emit_event_to_java(EV_KEY, map_capture, v); }
        else if (c==544||c==545||c==546||c==547) handle_dpad(c,v);
    } else if (ev->type == EV_ABS) {
        if (ev->code == 0) {
            int v = ev->value; if (inv_lx) v=-v;
            int dv = apply_deadzone(v);
            emit(EV_ABS, ABS_X, dv);
            emit_event_to_java(EV_ABS, 0, dv);
        } else if (ev->code == 1) {
            int v = ev->value; if (inv_ly) v=-v;
            int dv = apply_deadzone(v);
            emit(EV_ABS, ABS_Y, dv);
            emit_event_to_java(EV_ABS, 1, dv);
        }
    } else if (ev->type == EV_SYN) {
        emit(EV_SYN, SYN_REPORT, 0);
    }
}

static void handle_right(struct input_event *ev)
{
    if (ev->type == EV_KEY) {
        int c=ev->code, v=ev->value;
        if      (c==304)   { emit(EV_KEY, map_a,    v); emit_event_to_java(EV_KEY, map_a,    v); }
        else if (c==305)   { emit(EV_KEY, map_b,    v); emit_event_to_java(EV_KEY, map_b,    v); }
        else if (c==307)   { emit(EV_KEY, map_x,    v); emit_event_to_java(EV_KEY, map_x,    v); }
        else if (c==308)   { emit(EV_KEY, map_y,    v); emit_event_to_java(EV_KEY, map_y,    v); }
        else if (c==0x137) { emit(EV_KEY, map_r,    v); emit_event_to_java(EV_KEY, map_r,    v); }
        else if (c==0x139) { emit(EV_KEY, map_zr,   v); emit_event_to_java(EV_KEY, map_zr,   v); }
        else if (c==0x13b) { emit(EV_KEY, map_plus, v); emit_event_to_java(EV_KEY, map_plus, v); }
        else if (c==0x13e) { emit(EV_KEY, map_r3,   v); emit_event_to_java(EV_KEY, map_r3,   v); }
        else if (c==0x13c) { emit(EV_KEY, map_home, v); emit_event_to_java(EV_KEY, map_home, v); }
    } else if (ev->type == EV_ABS) {
        if (ev->code == 3) {
            int v = ev->value; if (inv_rx) v=-v;
            int dv = apply_deadzone(v);
            emit(EV_ABS, ABS_RX, dv);
            emit_event_to_java(EV_ABS, 3, dv);
        } else if (ev->code == 4) {
            int v = ev->value; if (inv_ry) v=-v;
            int dv = apply_deadzone(v);
            emit(EV_ABS, ABS_RY, dv);
            emit_event_to_java(EV_ABS, 4, dv);
        }
    } else if (ev->type == EV_SYN) {
        emit(EV_SYN, SYN_REPORT, 0);
    }
}

static void *thread_left(void *arg)
{
    struct input_event ev;
    fprintf(stdout,"STATUS:Left reader started\n"); fflush(stdout);
    while (running && read(left_fd, &ev, sizeof(ev))==sizeof(ev))
        handle_left(&ev);
    fprintf(stdout,"STATUS:Left reader stopped\n"); fflush(stdout);
    return NULL;
}

static void *thread_right(void *arg)
{
    struct input_event ev;
    fprintf(stdout,"STATUS:Right reader started\n"); fflush(stdout);
    while (running && read(right_fd, &ev, sizeof(ev))==sizeof(ev))
        handle_right(&ev);
    fprintf(stdout,"STATUS:Right reader stopped\n"); fflush(stdout);
    return NULL;
}

static void sig_handler(int s) { running = 0; }

int main(int argc, char *argv[])
{
    if (argc < 3) {
        fprintf(stdout,"ERROR:usage: uinput_setup <left> <right> [fuzz flat invLX invLY invRX invRY mapA mapB mapX mapY mapR mapZR mapPlus mapR3 mapL mapZL mapMinus mapL3 mapHome mapCapture]\n");
        fflush(stdout); return 1;
    }

    const char *left_path  = argv[1];
    const char *right_path = argv[2];
    if (argc>3)  stick_fuzz = atoi(argv[3]);
    if (argc>4)  stick_flat = atoi(argv[4]);
    if (argc>5)  inv_lx     = atoi(argv[5]);
    if (argc>6)  inv_ly     = atoi(argv[6]);
    if (argc>7)  inv_rx     = atoi(argv[7]);
    if (argc>8)  inv_ry     = atoi(argv[8]);
    if (argc>9)  map_a      = atoi(argv[9]);
    if (argc>10) map_b      = atoi(argv[10]);
    if (argc>11) map_x      = atoi(argv[11]);
    if (argc>12) map_y      = atoi(argv[12]);
    if (argc>13) map_r      = atoi(argv[13]);
    if (argc>14) map_zr     = atoi(argv[14]);
    if (argc>15) map_plus   = atoi(argv[15]);
    if (argc>16) map_r3     = atoi(argv[16]);
    if (argc>17) map_l      = atoi(argv[17]);
    if (argc>18) map_zl     = atoi(argv[18]);
    if (argc>19) map_minus  = atoi(argv[19]);
    if (argc>20) map_l3     = atoi(argv[20]);
    if (argc>21) map_home    = atoi(argv[21]);
    if (argc>22) map_capture = atoi(argv[22]);

    signal(SIGTERM, sig_handler);
    signal(SIGINT,  sig_handler);

    /* Setup uinput */
    uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) {
        fprintf(stdout,"ERROR:open /dev/uinput: %s\n",strerror(errno));
        fflush(stdout); return 1;
    }

    ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN);

    int keys[]={0x130,0x131,0x132,0x133,0x134,0x135,0x136,
                0x137,0x138,0x139,0x13a,0x13b,0x13c,0x13d,0x13e,
                0xa7};
    for (int i=0;i<16;i++) ioctl(uinput_fd, UI_SET_KEYBIT, keys[i]);
    int abits[]={ABS_X,ABS_Y,ABS_RX,ABS_RY,ABS_HAT0X,ABS_HAT0Y};
    for (int i=0;i<6;i++) ioctl(uinput_fd, UI_SET_ABSBIT, abits[i]);

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, VIRT_NAME);
    uidev.id.bustype=BUS_VIRTUAL; uidev.id.vendor=VENDOR_ID;
    uidev.id.product=PRODUCT_ID;  uidev.id.version=1;

    int sax[]={ABS_X,ABS_Y,ABS_RX,ABS_RY};
    for (int i=0;i<4;i++) {
        uidev.absmax[sax[i]]= 32767; uidev.absmin[sax[i]]=-32768;
        uidev.absfuzz[sax[i]]=stick_fuzz; uidev.absflat[sax[i]]=stick_flat;
    }
    uidev.absmax[ABS_HAT0X]=1; uidev.absmin[ABS_HAT0X]=-1;
    uidev.absmax[ABS_HAT0Y]=1; uidev.absmin[ABS_HAT0Y]=-1;

    int fl = fcntl(uinput_fd, F_GETFL);
    fcntl(uinput_fd, F_SETFL, fl & ~O_NONBLOCK);

    if (write(uinput_fd, &uidev, sizeof(uidev))<0) {
        fprintf(stdout,"ERROR:write uidev: %s\n",strerror(errno));
        fflush(stdout); return 1;
    }
    if (ioctl(uinput_fd, UI_DEV_CREATE)<0) {
        fprintf(stdout,"ERROR:UI_DEV_CREATE: %s\n",strerror(errno));
        fflush(stdout); return 1;
    }

    /* Center all axes */
    emit(EV_ABS,ABS_X,0); emit(EV_ABS,ABS_Y,0);
    emit(EV_ABS,ABS_RX,0); emit(EV_ABS,ABS_RY,0);
    emit(EV_ABS,ABS_HAT0X,0); emit(EV_ABS,ABS_HAT0Y,0);
    emit(EV_SYN,SYN_REPORT,0);

    /* Open Joy-Con devices */
    left_fd = open(left_path, O_RDONLY);
    if (left_fd < 0) {
        fprintf(stdout,"ERROR:open left %s: %s\n",left_path,strerror(errno));
        fflush(stdout); ioctl(uinput_fd,UI_DEV_DESTROY); return 1;
    }
    right_fd = open(right_path, O_RDONLY);
    if (right_fd < 0) {
        fprintf(stdout,"ERROR:open right %s: %s\n",right_path,strerror(errno));
        fflush(stdout); close(left_fd); ioctl(uinput_fd,UI_DEV_DESTROY); return 1;
    }

    /* GRAB exclusive — stops Android from also forwarding raw events */
    ioctl(left_fd,  EVIOCGRAB, 1);
    ioctl(right_fd, EVIOCGRAB, 1);

    running = 1;
    fprintf(stdout,"UINPUT_READY\n"); fflush(stdout);

    pthread_t tl, tr;
    pthread_create(&tl, NULL, thread_left,  NULL);
    pthread_create(&tr, NULL, thread_right, NULL);

    /* Block reading stdin — Java sends "STOP\n" to stop */
    char line[64];
    while (running && fgets(line, sizeof(line), stdin)) {
        if (strncmp(line,"STOP",4)==0) break;
    }
    running = 0;

    /* Close fds to unblock blocked read() in threads */
    close(left_fd);  left_fd  = -1;
    close(right_fd); right_fd = -1;

    pthread_join(tl, NULL);
    pthread_join(tr, NULL);

    ioctl(uinput_fd, UI_DEV_DESTROY);
    close(uinput_fd);
    fprintf(stdout,"STATUS:STOPPED\n"); fflush(stdout);
    return 0;
}
