// uimouse.c —— 虚拟鼠标（标准 libc 版）
// 从 FIFO /data/local/tmp/sk_mouse 读命令：
//   M:dx,dy   相对移动
//   C         左键单击
//   R         右键单击
//   W:d       滚轮（正=向上）
//   H:a       横向滚轮（正=向右）
//   B         中键单击
//   D/U       左键按住/松开（拖动）
//
// 交叉编译：
//   aarch64-linux-gnu-gcc -static -O2 -o uimouse uimouse.c

#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

static int ufd = -1;

static void emit(int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short)type;
    ev.code = (unsigned short)code;
    ev.value = val;
    if (write(ufd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) {
        fprintf(stderr, "write ev failed errno=%d\n", errno);
    }
}

static void syn(void) { emit(EV_SYN, SYN_REPORT, 0); }

static int setup_mouse(void) {
    ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (ufd < 0) { perror("open /dev/uinput"); return -1; }

    int r = 0;
    r |= ioctl(ufd, UI_SET_EVBIT, EV_KEY);
    r |= ioctl(ufd, UI_SET_EVBIT, EV_REL);
    r |= ioctl(ufd, UI_SET_EVBIT, EV_SYN);
    r |= ioctl(ufd, UI_SET_KEYBIT, BTN_LEFT);
    r |= ioctl(ufd, UI_SET_KEYBIT, BTN_RIGHT);
    r |= ioctl(ufd, UI_SET_KEYBIT, BTN_MIDDLE);
    r |= ioctl(ufd, UI_SET_RELBIT, REL_X);
    r |= ioctl(ufd, UI_SET_RELBIT, REL_Y);
    r |= ioctl(ufd, UI_SET_RELBIT, REL_WHEEL);
    r |= ioctl(ufd, UI_SET_RELBIT, REL_HWHEEL);
    if (r < 0) perror("uinput setup ioctl");

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor = 0x1234;
    usetup.id.product = 0x5678;
    strcpy(usetup.name, "SplitKey Virtual Mouse");
    if (ioctl(ufd, UI_DEV_SETUP, &usetup) < 0) perror("UI_DEV_SETUP");
    if (ioctl(ufd, UI_DEV_CREATE) < 0) { perror("UI_DEV_CREATE"); return -1; }
    return 0;
}

static void handle(char *line) {
    long dx = 0, dy = 0, d = 0, a = 0;
    char *end;
    switch (line[0]) {
    case 'M':
        // 用 strtol 而不是 sscanf：静态链接下 sscanf 对 "%ld,%ld" 解析失败
        dx = strtol(line + 2, &end, 10);
        if (*end == ',') {
            dy = strtol(end + 1, NULL, 10);
            if (dx) emit(EV_REL, REL_X, (int)dx);
            if (dy) emit(EV_REL, REL_Y, (int)dy);
            if (dx || dy) syn();
        }
        break;
    case 'C':
        emit(EV_KEY, BTN_LEFT, 1); syn();
        usleep(40000);
        emit(EV_KEY, BTN_LEFT, 0); syn();
        break;
    case 'D':                    // 按住左键（拖动开始）
        emit(EV_KEY, BTN_LEFT, 1); syn();
        break;
    case 'U':                    // 松开左键（拖动结束）
        emit(EV_KEY, BTN_LEFT, 0); syn();
        break;
    case 'R':
        emit(EV_KEY, BTN_RIGHT, 1); syn();
        usleep(40000);
        emit(EV_KEY, BTN_RIGHT, 0); syn();
        break;
    case 'B':                    // 中键（三指点按）
        emit(EV_KEY, BTN_MIDDLE, 1); syn();
        usleep(40000);
        emit(EV_KEY, BTN_MIDDLE, 0); syn();
        break;
    case 'W':
        d = strtol(line + 2, NULL, 10);
        if (d) {
            emit(EV_REL, REL_WHEEL, (int)d);
            syn();
        }
        break;
    case 'H':
        a = strtol(line + 2, NULL, 10);
        if (a) {
            emit(EV_REL, REL_HWHEEL, (int)a);
            syn();
        }
        break;
    }
}

int main(void) {
    if (setup_mouse() < 0) return 1;
    fprintf(stderr, "uimouse ready\n");

    char buf[256];
    int len = 0;
    for (;;) {
        int fd = open("/data/local/tmp/sk_mouse", O_RDONLY);
        if (fd < 0) { usleep(500000); continue; }

        for (;;) {
            ssize_t n = read(fd, buf + len, sizeof(buf) - 1 - len);
            if (n <= 0) break;
            len += (int)n;
            buf[len] = 0;

            int start = 0;
            for (int i = 0; i < len; i++) {
                if (buf[i] == '\n') {
                    buf[i] = 0;
                    handle(buf + start);
                    start = i + 1;
                }
            }
            if (start > 0) {
                int rest = len - start;
                memmove(buf, buf + start, rest);
                len = rest;
            }
            if (len > 240) len = 0;
        }
        close(fd);
    }
}
