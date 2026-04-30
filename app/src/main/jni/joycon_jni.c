#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <dirent.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>

#define TAG "JoyConMerge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define UINPUT_PATH  "/dev/uinput"
#define INPUT_DIR    "/dev/input"
#define VIRT_NAME    "Nintendo Switch Combined Joy-Con"
#define VENDOR_ID    0x057e
#define PRODUCT_ID   0x2009

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
    .left_axis_x=0,.left_axis_y=1,
    .right_axis_x=3,.right_axis_y=4,
    .code_a=304,.map_a=0x130, .code_b=305,.map_b=0x131,
    .code_x=307,.map_x=0x133, .code_y=308,.map_y=0x134,
    .code_r=0x136,.map_r=0x136, .code_zr=0x137,.map_zr=0x137,
    .code_plus=0x13b,.map_plus=0x13b, .code_r3=0x13d,.map_r3=0x13d,
    .code_l=0x135,.map_l=0x135, .code_zl=0x139,.map_zl=0x139,
    .code_minus=0x13a,.map_minus=0x13a, .code_l3=0x13c,.map_l3=0x13c,
    .dpad_up=544,.dpad_down=545,.dpad_left=546,.dpad_right=547
};

static int uinput_fd=-1, left_fd=-1, right_fd=-1;
static volatile int running=0;
static pthread_t thread_left, thread_right;
static char left_path[64]={0}, right_path[64]={0};
static int dp_up=0,dp_down=0,dp_left=0,dp_right=0;
static pthread_mutex_t dpad_mutex = PTHREAD_MUTEX_INITIALIZER;

static JavaVM *jvm=NULL;
static jobject g_callback=NULL;
static jmethodID g_onStatus=NULL;

static void notify_status(const char *msg) {
    LOGI("%s", msg);
    if (!jvm || !g_callback) return;
    JNIEnv *env;
    int attached=0;
    if ((*jvm)->GetEnv(jvm,(void**)&env,JNI_VERSION_1_6)!=JNI_OK) {
        (*jvm)->AttachCurrentThread(jvm,&env,NULL);
        attached=1;
    }
    jstring jmsg=(*env)->NewStringUTF(env,msg);
    (*env)->CallVoidMethod(env,g_callback,g_onStatus,jmsg);
    (*env)->DeleteLocalRef(env,jmsg);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}

/* Notify with errno detail */
static void notify_errno(const char *prefix) {
    char buf[256];
    snprintf(buf,sizeof(buf),"ERROR: %s: %s", prefix, strerror(errno));
    notify_status(buf);
}

static int find_joycons(void) {
    memset(left_path,0,sizeof(left_path));
    memset(right_path,0,sizeof(right_path));
    DIR *dir=opendir(INPUT_DIR);
    if (!dir) { notify_errno("opendir /dev/input"); return -1; }
    struct dirent *entry;
    while ((entry=readdir(dir))!=NULL) {
        if (strncmp(entry->d_name,"event",5)!=0) continue;
        char path[64];
        snprintf(path,sizeof(path),"%s/%s",INPUT_DIR,entry->d_name);
        int fd=open(path,O_RDONLY|O_NONBLOCK);
        if (fd<0) continue;
        char name[256]={0};
        ioctl(fd,EVIOCGNAME(sizeof(name)),name);
        close(fd);
        if (strcasestr(name,"Left Joy-Con") && !left_path[0])
            snprintf(left_path,sizeof(left_path),"%s",path);
        else if (strcasestr(name,"Right Joy-Con") && !right_path[0])
            snprintf(right_path,sizeof(right_path),"%s",path);
        if (left_path[0] && right_path[0]) break;
    }
    closedir(dir);
    if (!left_path[0])  { notify_status("ERROR: Left Joy-Con not found in /dev/input — is it paired via Bluetooth?");  return -1; }
    if (!right_path[0]) { notify_status("ERROR: Right Joy-Con not found in /dev/input — is it paired via Bluetooth?"); return -1; }
    char msg[128];
    snprintf(msg,sizeof(msg),"Found L:%s  R:%s",left_path,right_path);
    notify_status(msg);
    return 0;
}

static void emit(int type,int code,int value) {
    struct input_event ev;
    memset(&ev,0,sizeof(ev));
    ev.type=type; ev.code=code; ev.value=value;
    write(uinput_fd,&ev,sizeof(ev));
}

static int clamp(int v,int mn,int mx){ return v<mn?mn:v>mx?mx:v; }

static void handle_dpad(int code,int value) {
    pthread_mutex_lock(&dpad_mutex);
    if (code==cfg.dpad_up)    dp_up=value;
    if (code==cfg.dpad_down)  dp_down=value;
    if (code==cfg.dpad_left)  dp_left=value;
    if (code==cfg.dpad_right) dp_right=value;
    int hx=dp_right-dp_left, hy=dp_down-dp_up;
    pthread_mutex_unlock(&dpad_mutex);
    emit(EV_ABS,ABS_HAT0X,hx);
    emit(EV_ABS,ABS_HAT0Y,hy);
}

static void handle_left(struct input_event *ev) {
    if (ev->type==EV_KEY) {
        int c=ev->code,v=ev->value;
        if      (c==cfg.code_l)     emit(EV_KEY,cfg.map_l,v);
        else if (c==cfg.code_zl)    emit(EV_KEY,cfg.map_zl,v);
        else if (c==cfg.code_minus) emit(EV_KEY,cfg.map_minus,v);
        else if (c==cfg.code_l3)    emit(EV_KEY,cfg.map_l3,v);
        else if (c==cfg.dpad_up||c==cfg.dpad_down||
                 c==cfg.dpad_left||c==cfg.dpad_right)
            handle_dpad(c,v);
    } else if (ev->type==EV_ABS) {
        int v=ev->value;
        if      (ev->code==cfg.left_axis_x) { if(cfg.inv_lx)v=-v; emit(EV_ABS,ABS_X,clamp(v,-32768,32767)); }
        else if (ev->code==cfg.left_axis_y) { if(cfg.inv_ly)v=-v; emit(EV_ABS,ABS_Y,clamp(v,-32768,32767)); }
        else if (ev->code==ABS_HAT0X)       emit(EV_ABS,ABS_HAT0X,v);
        else if (ev->code==ABS_HAT0Y)       emit(EV_ABS,ABS_HAT0Y,v);
    } else if (ev->type==EV_SYN) emit(EV_SYN,SYN_REPORT,0);
}

static void handle_right(struct input_event *ev) {
    if (ev->type==EV_KEY) {
        int c=ev->code,v=ev->value;
        if      (c==cfg.code_a)    emit(EV_KEY,cfg.map_a,v);
        else if (c==cfg.code_b)    emit(EV_KEY,cfg.map_b,v);
        else if (c==cfg.code_x)    emit(EV_KEY,cfg.map_x,v);
        else if (c==cfg.code_y)    emit(EV_KEY,cfg.map_y,v);
        else if (c==cfg.code_r)    emit(EV_KEY,cfg.map_r,v);
        else if (c==cfg.code_zr)   emit(EV_KEY,cfg.map_zr,v);
        else if (c==cfg.code_plus) emit(EV_KEY,cfg.map_plus,v);
        else if (c==cfg.code_r3)   emit(EV_KEY,cfg.map_r3,v);
    } else if (ev->type==EV_ABS) {
        int v=ev->value;
        if      (ev->code==cfg.right_axis_x) { if(cfg.inv_rx)v=-v; emit(EV_ABS,ABS_RX,clamp(v,-32768,32767)); }
        else if (ev->code==cfg.right_axis_y) { if(cfg.inv_ry)v=-v; emit(EV_ABS,ABS_RY,clamp(v,-32768,32767)); }
    } else if (ev->type==EV_SYN) emit(EV_SYN,SYN_REPORT,0);
}

static void *thread_read_left(void *arg) {
    struct input_event ev;
    notify_status("Left Joy-Con reader started");
    while (running && read(left_fd,&ev,sizeof(ev))==sizeof(ev)) handle_left(&ev);
    notify_status("Left Joy-Con reader stopped");
    return NULL;
}
static void *thread_read_right(void *arg) {
    struct input_event ev;
    notify_status("Right Joy-Con reader started");
    while (running && read(right_fd,&ev,sizeof(ev))==sizeof(ev)) handle_right(&ev);
    notify_status("Right Joy-Con reader stopped");
    return NULL;
}

static int setup_uinput(void) {
    uinput_fd=open(UINPUT_PATH,O_WRONLY|O_NONBLOCK);
    if (uinput_fd<0) { notify_errno("open /dev/uinput (needs root or uinput group)"); return -1; }

    ioctl(uinput_fd,UI_SET_EVBIT,EV_KEY);
    ioctl(uinput_fd,UI_SET_EVBIT,EV_ABS);
    ioctl(uinput_fd,UI_SET_EVBIT,EV_SYN);
    int keys[]={0x130,0x131,0x132,0x133,0x134,0x135,0x136,
                0x137,0x138,0x139,0x13a,0x13b,0x13c,0x13d,0x13e};
    for (int i=0;i<15;i++) ioctl(uinput_fd,UI_SET_KEYBIT,keys[i]);
    int abits[]={ABS_X,ABS_Y,ABS_RX,ABS_RY,ABS_HAT0X,ABS_HAT0Y};
    for (int i=0;i<6;i++) ioctl(uinput_fd,UI_SET_ABSBIT,abits[i]);

    struct uinput_user_dev uidev;
    memset(&uidev,0,sizeof(uidev));
    snprintf(uidev.name,UINPUT_MAX_NAME_SIZE,VIRT_NAME);
    uidev.id.bustype=BUS_VIRTUAL;
    uidev.id.vendor=VENDOR_ID;
    uidev.id.product=PRODUCT_ID;
    uidev.id.version=1;
    int axes[]={ABS_X,ABS_Y,ABS_RX,ABS_RY};
    for (int i=0;i<4;i++) {
        uidev.absmin[axes[i]]=-32768; uidev.absmax[axes[i]]=32767;
        uidev.absfuzz[axes[i]]=cfg.stick_fuzz; uidev.absflat[axes[i]]=cfg.stick_flat;
    }
    uidev.absmin[ABS_HAT0X]=-1; uidev.absmax[ABS_HAT0X]=1;
    uidev.absmin[ABS_HAT0Y]=-1; uidev.absmax[ABS_HAT0Y]=1;

    if (write(uinput_fd,&uidev,sizeof(uidev))<0) { notify_errno("write uinput_user_dev"); return -1; }
    if (ioctl(uinput_fd,UI_DEV_CREATE)<0)         { notify_errno("UI_DEV_CREATE"); return -1; }

    int ca[]={ABS_X,ABS_Y,ABS_RX,ABS_RY};
    for (int i=0;i<4;i++) emit(EV_ABS,ca[i],0);
    emit(EV_SYN,SYN_REPORT,0);
    return 0;
}

/* ---- JNI exports ---- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm=vm; return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_joyconmerge_MergeService_setCallback(JNIEnv *env,jobject thiz,jobject cb) {
    if (g_callback) (*env)->DeleteGlobalRef(env,g_callback);
    g_callback=(*env)->NewGlobalRef(env,cb);
    jclass cls=(*env)->GetObjectClass(env,cb);
    g_onStatus=(*env)->GetMethodID(env,cls,"onStatus","(Ljava/lang/String;)V");
}

JNIEXPORT void JNICALL
Java_com_joyconmerge_MergeService_setConfig(JNIEnv *env,jobject thiz,
    jint fuzz,jint flat,jint invLX,jint invLY,jint invRX,jint invRY,
    jint laxX,jint laxY,jint raxX,jint raxY,
    jint cA,jint mA,jint cB,jint mB,jint cX,jint mX,jint cY,jint mY,
    jint cR,jint mR,jint cZR,jint mZR,jint cPlus,jint mPlus,jint cR3,jint mR3,
    jint cL,jint mL,jint cZL,jint mZL,jint cMinus,jint mMinus,jint cL3,jint mL3,
    jint dpUp,jint dpDown,jint dpLeft,jint dpRight)
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
    cfg.dpad_up=dpUp; cfg.dpad_down=dpDown; cfg.dpad_left=dpLeft; cfg.dpad_right=dpRight;
}

JNIEXPORT jint JNICALL
Java_com_joyconmerge_MergeService_startMerge(JNIEnv *env,jobject thiz) {
    if (running) return 0;
    if (find_joycons()<0) return -1;

    left_fd=open(left_path,O_RDONLY);
    if (left_fd<0) { notify_errno("open Left Joy-Con (needs root)"); return -1; }
    ioctl(left_fd,EVIOCGRAB,1);

    right_fd=open(right_path,O_RDONLY);
    if (right_fd<0) { notify_errno("open Right Joy-Con (needs root)"); return -1; }
    ioctl(right_fd,EVIOCGRAB,1);

    if (setup_uinput()<0) return -1;

    running=1;
    pthread_create(&thread_left,  NULL,thread_read_left,  NULL);
    pthread_create(&thread_right, NULL,thread_read_right, NULL);
    notify_status("RUNNING");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_joyconmerge_MergeService_stopMerge(JNIEnv *env,jobject thiz) {
    running=0;
    if (left_fd>=0)   { ioctl(left_fd,EVIOCGRAB,0);  close(left_fd);   left_fd=-1; }
    if (right_fd>=0)  { ioctl(right_fd,EVIOCGRAB,0); close(right_fd);  right_fd=-1; }
    if (uinput_fd>=0) { ioctl(uinput_fd,UI_DEV_DESTROY); close(uinput_fd); uinput_fd=-1; }
    notify_status("STOPPED");
}

JNIEXPORT jstring JNICALL
Java_com_joyconmerge_MergeService_getFoundDevices(JNIEnv *env,jobject thiz) {
    char buf[128];
    snprintf(buf,sizeof(buf),"%s|%s",left_path,right_path);
    return (*env)->NewStringUTF(env,buf);
}
