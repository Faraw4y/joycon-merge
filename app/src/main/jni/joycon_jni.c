#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>

#define TAG  "JoyConMerge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * ARCHITECTURE v3 — "root owns uinput setup, JNI just maps events"
 *
 * Root shell:
 *   1. cat /dev/input/eventL > pipe_L_write  (kita baca pipe_L_read)
 *   2. cat /dev/input/eventR > pipe_R_write  (kita baca pipe_R_read)
 *   3. Buka /dev/uinput, jalankan SEMUA ioctl + UI_DEV_CREATE
 *   4. Setelah UINPUT_READY dicetak, masuk mode:
 *         cat pipe_U_read >> /dev/uinput_fd   (kita tulis ke pipe_U_write)
 *
 * JNI tidak pernah open() /dev/* — tidak perlu akses SELinux apapun.
 */

#define VIRT_NAME  "Nintendo Switch Combined Joy-Con"
#define VENDOR_ID  0x057e
#define PRODUCT_ID 0x2009

typedef struct {
    int stick_fuzz, stick_flat;
    int inv_lx, inv_ly, inv_rx, inv_ry;
    int left_axis_x, left_axis_y;
    int right_axis_x, right_axis_y;
    int code_a, map_a, code_b, map_b;
    int code_x, map_x, code_y, map_y;
    int code_r,  map_r,  code_zr, map_zr;
    int code_plus, map_plus, code_r3, map_r3;
    int code_l,  map_l,  code_zl, map_zl;
    int code_minus, map_minus, code_l3, map_l3;
    int dpad_up, dpad_down, dpad_left, dpad_right;
} Config;

static Config cfg = {
    .stick_fuzz=256, .stick_flat=4096,
    .left_axis_x=0,  .left_axis_y=1,
    .right_axis_x=0, .right_axis_y=1,   /* Joy-Con R sends stick as ABS_X/ABS_Y */
    .code_a=304, .map_a=0x130, .code_b=305, .map_b=0x131,
    .code_x=307, .map_x=0x133, .code_y=308, .map_y=0x134,
    .code_r=0x136, .map_r=0x136, .code_zr=0x137, .map_zr=0x137,
    .code_plus=0x13b, .map_plus=0x13b, .code_r3=0x13d, .map_r3=0x13d,
    .code_l=0x135, .map_l=0x135, .code_zl=0x139, .map_zl=0x139,
    .code_minus=0x13a, .map_minus=0x13a, .code_l3=0x13c, .map_l3=0x13c,
    .dpad_up=544, .dpad_down=545, .dpad_left=546, .dpad_right=547
};

static int uinput_pipe_fd = -1;
static int left_fd  = -1;
static int right_fd = -1;
static volatile int running = 0;
static pthread_t thread_left, thread_right;

static int dp_up=0, dp_down=0, dp_left=0, dp_right=0;
static pthread_mutex_t dpad_mutex = PTHREAD_MUTEX_INITIALIZER;

static JavaVM    *jvm        = NULL;
static jobject    g_callback  = NULL;
static jmethodID  g_onStatus  = NULL;
static jmethodID  g_onEvent   = NULL;

static void notify_status(const char *msg) {
    LOGI("%s", msg);
    if (!jvm || !g_callback) return;
    JNIEnv *env; int attached = 0;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*jvm)->AttachCurrentThread(jvm, &env, NULL);
        attached = 1;
    }
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    (*env)->CallVoidMethod(env, g_callback, g_onStatus, jmsg);
    (*env)->DeleteLocalRef(env, jmsg);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

static void notify_event(const char *msg) {
    if (!jvm || !g_callback || !g_onEvent) return;
    JNIEnv *env; int attached = 0;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*jvm)->AttachCurrentThread(jvm, &env, NULL);
        attached = 1;
    }
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    (*env)->CallVoidMethod(env, g_callback, g_onEvent, jmsg);
    (*env)->DeleteLocalRef(env, jmsg);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

static void notify_errno(const char *prefix) {
    char buf[256];
    snprintf(buf, sizeof(buf), "ERROR: %s: %s", prefix, strerror(errno));
    notify_status(buf);
}

/* Kirim satu input_event ke pipe → root forward ke /dev/uinput */
static void emit(int type, int code, int value) {
    if (uinput_pipe_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type  = type;
    ev.code  = code;
    ev.value = value;
    ssize_t n = write(uinput_pipe_fd, &ev, sizeof(ev));
    (void)n;
}

static int clamp(int v, int mn, int mx) { return v<mn?mn:v>mx?mx:v; }

/* Software deadzone: if |v| <= flat, output 0. Also apply fuzz rounding. */
static int apply_deadzone(int v) {
    if (v > -cfg.stick_flat && v < cfg.stick_flat) return 0;
    /* Round to fuzz bucket */
    if (cfg.stick_fuzz > 1) v = (v / cfg.stick_fuzz) * cfg.stick_fuzz;
    return clamp(v, -32768, 32767);
}

static void handle_dpad(int code, int value) {
    pthread_mutex_lock(&dpad_mutex);
    if (code == cfg.dpad_up)    dp_up    = value;
    if (code == cfg.dpad_down)  dp_down  = value;
    if (code == cfg.dpad_left)  dp_left  = value;
    if (code == cfg.dpad_right) dp_right = value;
    int hx = dp_right - dp_left;
    int hy = dp_down  - dp_up;
    pthread_mutex_unlock(&dpad_mutex);
    emit(EV_ABS, ABS_HAT0X, hx);
    emit(EV_ABS, ABS_HAT0Y, hy);
}

static void handle_left(struct input_event *ev) {
    if (ev->type == EV_KEY) {
        int c = ev->code, v = ev->value;
        char ebuf[64];
        if      (c == cfg.code_l)     { emit(EV_KEY, cfg.map_l,    v); if(v) { snprintf(ebuf,sizeof(ebuf),"[L] L pressed");    notify_event(ebuf); } }
        else if (c == cfg.code_zl)    { emit(EV_KEY, cfg.map_zl,   v); if(v) { snprintf(ebuf,sizeof(ebuf),"[L] ZL pressed");   notify_event(ebuf); } }
        else if (c == cfg.code_minus) { emit(EV_KEY, cfg.map_minus, v); if(v) { snprintf(ebuf,sizeof(ebuf),"[L] MINUS pressed");notify_event(ebuf); } }
        else if (c == cfg.code_l3)    { emit(EV_KEY, cfg.map_l3,   v); if(v) { snprintf(ebuf,sizeof(ebuf),"[L] L3 pressed");   notify_event(ebuf); } }
        else if (c==cfg.dpad_up||c==cfg.dpad_down||
                 c==cfg.dpad_left||c==cfg.dpad_right) {
            handle_dpad(c, v);
            if(v) {
                const char *dir = c==cfg.dpad_up?"UP":c==cfg.dpad_down?"DOWN":c==cfg.dpad_left?"LEFT":"RIGHT";
                snprintf(ebuf,sizeof(ebuf),"[L] DPAD_%s",dir);
                notify_event(ebuf);
            }
        }
    } else if (ev->type == EV_ABS) {
        int v = ev->value;
        if      (ev->code == cfg.left_axis_x) {
            if (cfg.inv_lx) v = -v;
            emit(EV_ABS, ABS_X, apply_deadzone(v));
        } else if (ev->code == cfg.left_axis_y) {
            if (cfg.inv_ly) v = -v;
            emit(EV_ABS, ABS_Y, apply_deadzone(v));
        } else if (ev->code == ABS_HAT0X) {
            emit(EV_ABS, ABS_HAT0X, v);
        } else if (ev->code == ABS_HAT0Y) {
            emit(EV_ABS, ABS_HAT0Y, v);
        }
    } else if (ev->type == EV_SYN) {
        emit(EV_SYN, SYN_REPORT, 0);
    }
}

static void handle_right(struct input_event *ev) {
    if (ev->type == EV_KEY) {
        int c = ev->code, v = ev->value;
        char ebuf[64];
        if      (c == cfg.code_a)    { emit(EV_KEY, cfg.map_a,    v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] A pressed");    notify_event(ebuf); } }
        else if (c == cfg.code_b)    { emit(EV_KEY, cfg.map_b,    v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] B pressed");    notify_event(ebuf); } }
        else if (c == cfg.code_x)    { emit(EV_KEY, cfg.map_x,    v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] X pressed");    notify_event(ebuf); } }
        else if (c == cfg.code_y)    { emit(EV_KEY, cfg.map_y,    v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] Y pressed");    notify_event(ebuf); } }
        else if (c == cfg.code_r)    { emit(EV_KEY, cfg.map_r,    v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] R pressed");    notify_event(ebuf); } }
        else if (c == cfg.code_zr)   { emit(EV_KEY, cfg.map_zr,   v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] ZR pressed");   notify_event(ebuf); } }
        else if (c == cfg.code_plus) { emit(EV_KEY, cfg.map_plus,  v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] PLUS pressed"); notify_event(ebuf); } }
        else if (c == cfg.code_r3)   { emit(EV_KEY, cfg.map_r3,   v); if(v) { snprintf(ebuf,sizeof(ebuf),"[R] R3 pressed");   notify_event(ebuf); } }
    } else if (ev->type == EV_ABS) {
        int v = ev->value;
        /* Joy-Con R sends its stick as ABS_X(0)/ABS_Y(1), same as Left — handled here because
         * this is the right-thread, so we remap to ABS_RX/ABS_RY on the virtual device. */
        if      (ev->code == cfg.right_axis_x) {
            if (cfg.inv_rx) v = -v;
            emit(EV_ABS, ABS_RX, apply_deadzone(v));
        } else if (ev->code == cfg.right_axis_y) {
            if (cfg.inv_ry) v = -v;
            emit(EV_ABS, ABS_RY, apply_deadzone(v));
        }
    } else if (ev->type == EV_SYN) {
        emit(EV_SYN, SYN_REPORT, 0);
    }
}

static void *thread_read_left(void *arg) {
    struct input_event ev;
    notify_status("Left Joy-Con reader started");
    while (running && read(left_fd, &ev, sizeof(ev)) == sizeof(ev))
        handle_left(&ev);
    notify_status("Left Joy-Con reader stopped");
    return NULL;
}

static void *thread_read_right(void *arg) {
    struct input_event ev;
    notify_status("Right Joy-Con reader started");
    while (running && read(right_fd, &ev, sizeof(ev)) == sizeof(ev))
        handle_right(&ev);
    notify_status("Right Joy-Con reader stopped");
    return NULL;
}

/* ================================================================ */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_joyconmerge_MergeService_setCallback(JNIEnv *env, jobject thiz, jobject cb) {
    if (g_callback) (*env)->DeleteGlobalRef(env, g_callback);
    g_callback = (*env)->NewGlobalRef(env, cb);
    jclass cls = (*env)->GetObjectClass(env, cb);
    g_onStatus = (*env)->GetMethodID(env, cls, "onStatus", "(Ljava/lang/String;)V");
    g_onEvent  = (*env)->GetMethodID(env, cls, "onEvent",  "(Ljava/lang/String;)V");
}

JNIEXPORT void JNICALL
Java_com_joyconmerge_MergeService_setConfig(JNIEnv *env, jobject thiz,
    jint fuzz, jint flat, jint invLX, jint invLY, jint invRX, jint invRY,
    jint laxX, jint laxY, jint raxX, jint raxY,
    jint cA, jint mA, jint cB, jint mB,
    jint cX, jint mX, jint cY, jint mY,
    jint cR, jint mR, jint cZR, jint mZR,
    jint cPlus, jint mPlus, jint cR3, jint mR3,
    jint cL, jint mL, jint cZL, jint mZL,
    jint cMinus, jint mMinus, jint cL3, jint mL3,
    jint dpUp, jint dpDown, jint dpLeft, jint dpRight)
{
    cfg.stick_fuzz=fuzz; cfg.stick_flat=flat;
    cfg.inv_lx=invLX; cfg.inv_ly=invLY; cfg.inv_rx=invRX; cfg.inv_ry=invRY;
    cfg.left_axis_x=laxX; cfg.left_axis_y=laxY;
    cfg.right_axis_x=raxX; cfg.right_axis_y=raxY;
    cfg.code_a=cA; cfg.map_a=mA; cfg.code_b=cB; cfg.map_b=mB;
    cfg.code_x=cX; cfg.map_x=mX; cfg.code_y=cY; cfg.map_y=mY;
    cfg.code_r=cR; cfg.map_r=mR; cfg.code_zr=cZR; cfg.map_zr=mZR;
    cfg.code_plus=cPlus; cfg.map_plus=mPlus; cfg.code_r3=cR3; cfg.map_r3=mR3;
    cfg.code_l=cL; cfg.map_l=mL; cfg.code_zl=cZL; cfg.map_zl=mZL;
    cfg.code_minus=cMinus; cfg.map_minus=mMinus; cfg.code_l3=cL3; cfg.map_l3=mL3;
    cfg.dpad_up=dpUp; cfg.dpad_down=dpDown;
    cfg.dpad_left=dpLeft; cfg.dpad_right=dpRight;
}

/*
 * Dipanggil setelah root selesai setup uinput (sudah UI_DEV_CREATE)
 * dan sudah mulai forward dari pipe ke /dev/uinput.
 *
 * leftFd   = read-end pipe dari root cat /dev/input/eventL
 * rightFd  = read-end pipe dari root cat /dev/input/eventR
 * uinputFd = write-end pipe; root membaca dan menulis ke /dev/uinput
 */
JNIEXPORT jint JNICALL
Java_com_joyconmerge_MergeService_startMergeWithFds(JNIEnv *env, jobject thiz,
    jint jLeftFd, jint jRightFd, jint jUinputFd)
{
    if (running) return 0;

    left_fd        = dup(jLeftFd);
    right_fd       = dup(jRightFd);
    uinput_pipe_fd = dup(jUinputFd);

    if (left_fd < 0)        { notify_errno("dup left fd");   return -1; }
    if (right_fd < 0)       { notify_errno("dup right fd");  return -1; }
    if (uinput_pipe_fd < 0) { notify_errno("dup uinput fd"); return -1; }

    running = 1;
    pthread_create(&thread_left,  NULL, thread_read_left,  NULL);
    pthread_create(&thread_right, NULL, thread_read_right, NULL);
    notify_status("RUNNING");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_joyconmerge_MergeService_stopMerge(JNIEnv *env, jobject thiz) {
    running = 0;
    if (left_fd >= 0)        { close(left_fd);        left_fd        = -1; }
    if (right_fd >= 0)       { close(right_fd);       right_fd       = -1; }
    if (uinput_pipe_fd >= 0) { close(uinput_pipe_fd); uinput_pipe_fd = -1; }
    notify_status("STOPPED");
}

JNIEXPORT jstring JNICALL
Java_com_joyconmerge_MergeService_getFoundDevices(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "");
}
