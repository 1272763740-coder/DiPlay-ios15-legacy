/*
 * diplay_gadget — privileged USB gadget helper for the pre-iOS-16 wired CarPlay path.
 *
 * The app has already sent Apple's 0x51 vendor request and waited until the iPhone left the USB
 * host bus. This helper then:
 *   1. switches the target port's USB role to device,
 *   2. builds a composite configfs gadget (FunctionFS iAP2 first, CDC-NCM second),
 *   3. publishes the iAP2 descriptors and binds the UDC,
 *   4. addresses the NCM netdev with the verified link-local pair,
 *   5. relays the two iAP2 bulk endpoints to the app over an abstract Unix socket.
 *
 * Protocol: magic "DPG1", u8 type, u8 flags, u16 BE length, payload (max 16 KiB).
 * It never listens on TCP and never interpolates user input into a shell command.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usb/functionfs.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PROTOCOL_MAGIC 0x44504731u /* "DPG1" */
#define TYPE_HELLO 1
#define TYPE_READY 2
#define TYPE_TO_PHONE 3
#define TYPE_FROM_PHONE 4
#define TYPE_STATUS 5
#define TYPE_STOP 6
#define TYPE_ERROR 7
#define HEADER_BYTES 8
#define MAX_PAYLOAD 16384
#define FRAME_BUF (HEADER_BYTES + MAX_PAYLOAD)

#define GADGET_NAME "dplay_legacy"
#define FFS_MNT "/dev/ffs-dplay-iap2"
#define FFS_INSTANCE "iap2"
#define NETDEV_MAX 15

static volatile sig_atomic_t g_stop = 0;
static int g_sock = -1;
static int g_ep0 = -1;
static char g_gadget[256];
static char g_ffs_ep0[280];
static char g_ffs_ep1[280];
static char g_ffs_ep2[280];
static char g_previous_role[32];
static char g_previous_gadget[256];
static char g_udc[64];
static bool g_role_changed = false;
static bool g_gadget_created = false;
static bool g_ffs_mounted = false;

static void on_signal(int signo) {
    (void)signo;
    g_stop = 1;
}

static void log_line(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vfprintf(stdout, fmt, args);
    fputc('\n', stdout);
    fflush(stdout);
    va_end(args);
}

static int64_t now_millis(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void sleep_millis(int millis) {
    struct timespec ts = { millis / 1000, (long)(millis % 1000) * 1000000L };
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR && !g_stop) {
    }
}

static int write_text(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -errno;
    size_t length = strlen(value);
    ssize_t written = write(fd, value, length);
    int saved = errno;
    close(fd);
    if (written != (ssize_t)length) return written < 0 ? -saved : -EIO;
    return 0;
}

static int read_text(const char *path, char *out, size_t capacity) {
    if (capacity == 0) return -EINVAL;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -errno;
    ssize_t n = read(fd, out, capacity - 1);
    int saved = errno;
    close(fd);
    if (n < 0) return -saved;
    out[n] = '\0';
    while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r' || out[n - 1] == ' ')) out[--n] = '\0';
    return 0;
}

static int mkdir_one(const char *path) {
    if (mkdir(path, 0755) == 0) return 0;
    return errno == EEXIST ? 0 : -errno;
}

static void remove_path(const char *path) {
    if (path && path[0]) unlink(path);
}

static void remove_tree(const char *path) {
    if (!path || !path[0]) return;
    rmdir(path);
}

static const char *gadget_root(void) {
    static const char *candidates[] = {
        "/sys/kernel/config/usb_gadget",
        "/config/usb_gadget",
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        if (access(candidates[i], F_OK) == 0) return candidates[i];
    }
    return NULL;
}

static void send_frame(uint8_t type, const void *payload, uint16_t length);
static void send_status(const char *fmt, ...);
static void send_error(const char *fmt, ...);

static int send_all(int fd, const void *data, size_t length, int timeout_millis) {
    const uint8_t *cursor = data;
    size_t remaining = length;
    int64_t deadline = now_millis() + timeout_millis;
    while (remaining > 0) {
        ssize_t n = write(fd, cursor, remaining);
        if (n > 0) {
            cursor += n;
            remaining -= (size_t)n;
            continue;
        }
        if (n < 0 && (errno == EINTR)) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (now_millis() >= deadline) return -ETIMEDOUT;
            struct pollfd pfd = { .fd = fd, .events = POLLOUT };
            poll(&pfd, 1, 100);
            continue;
        }
        return n < 0 ? -errno : -EIO;
    }
    return 0;
}

static void send_frame(uint8_t type, const void *payload, uint16_t length) {
    if (g_sock < 0) return;
    uint8_t header[HEADER_BYTES];
    uint32_t magic = htonl(PROTOCOL_MAGIC);
    memcpy(header, &magic, 4);
    header[4] = type;
    header[5] = 0;
    uint16_t be_length = htons(length);
    memcpy(header + 6, &be_length, 2);
    if (send_all(g_sock, header, sizeof(header), 5000) != 0) return;
    if (length > 0) send_all(g_sock, payload, length, 5000);
}

static void send_status(const char *fmt, ...) {
    char text[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(text, sizeof(text), fmt, args);
    va_end(args);
    log_line("%s", text);
    send_frame(TYPE_STATUS, text, (uint16_t)strlen(text));
}

static void send_error(const char *fmt, ...) {
    char text[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(text, sizeof(text), fmt, args);
    va_end(args);
    log_line("ERROR %s", text);
    send_frame(TYPE_ERROR, text, (uint16_t)strlen(text));
}

/* Returns the exact request length when a full frame arrived, 0 when more bytes are needed,
 * -1 on a protocol violation. */
static int parse_frame(const uint8_t *data, size_t available, uint8_t *type, uint16_t *length) {
    if (available < HEADER_BYTES) return 0;
    uint32_t magic;
    memcpy(&magic, data, 4);
    if (ntohl(magic) != PROTOCOL_MAGIC) return -1;
    uint8_t frame_type = data[4];
    uint16_t be_length;
    memcpy(&be_length, data + 6, 2);
    uint16_t payload_length = ntohs(be_length);
    if (frame_type < TYPE_HELLO || frame_type > TYPE_ERROR || payload_length > MAX_PAYLOAD) return -1;
    size_t total = HEADER_BYTES + payload_length;
    if (available < total) return 0;
    *type = frame_type;
    *length = payload_length;
    return (int)total;
}

static void append_frame(uint8_t *buffer, size_t *used, uint8_t type, const void *payload, uint16_t length) {
    uint32_t magic = htonl(PROTOCOL_MAGIC);
    memcpy(buffer + *used, &magic, 4);
    buffer[*used + 4] = type;
    buffer[*used + 5] = 0;
    uint16_t be_length = htons(length);
    memcpy(buffer + *used + 6, &be_length, 2);
    if (length > 0 && payload) memcpy(buffer + *used + HEADER_BYTES, payload, length);
    *used += HEADER_BYTES + length;
}

static int connect_socket(const char *token) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    char name[128];
    snprintf(name, sizeof(name), "diplay-legacy-%s", token);
    size_t name_length = strlen(name);
    if (name_length + 1 > sizeof(addr.sun_path)) {
        close(fd);
        return -1;
    }
    memcpy(addr.sun_path + 1, name, name_length);
    socklen_t addr_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_length);
    if (bind(fd, (struct sockaddr *)&addr, addr_length) != 0 ||
        listen(fd, 1) != 0) {
        log_line("cannot bind abstract socket %s: %s", name, strerror(errno));
        close(fd);
        return -1;
    }
    log_line("waiting for the app on %s", name);
    int64_t deadline = now_millis() + 30000;
    while (!g_stop && now_millis() < deadline) {
        struct pollfd pfd = { .fd = fd, .events = POLLIN };
        int ready = poll(&pfd, 1, 500);
        if (ready > 0 && (pfd.revents & POLLIN)) {
            int client = accept4(fd, NULL, NULL, SOCK_CLOEXEC);
            close(fd);
            if (client < 0) return -1;
            struct timeval timeout = { .tv_sec = 10, .tv_usec = 0 };
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
            return client;
        }
    }
    close(fd);
    return -1;
}

static int read_frame(int fd, uint8_t *type, uint8_t *payload, uint16_t *length) {
    uint8_t header[HEADER_BYTES];
    size_t received = 0;
    while (received < sizeof(header)) {
        ssize_t n = read(fd, header + received, sizeof(header) - received);
        if (n == 0) return -1;
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) return -2;
            return -1;
        }
        received += (size_t)n;
    }
    uint32_t magic;
    memcpy(&magic, header, 4);
    if (ntohl(magic) != PROTOCOL_MAGIC) return -1;
    uint8_t frame_type = header[4];
    uint16_t be_length;
    memcpy(&be_length, header + 6, 2);
    uint16_t payload_length = ntohs(be_length);
    if (frame_type < TYPE_HELLO || frame_type > TYPE_ERROR || payload_length > MAX_PAYLOAD) return -1;
    size_t got = 0;
    while (got < payload_length) {
        ssize_t n = read(fd, payload + got, payload_length - got);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        got += (size_t)n;
    }
    *type = frame_type;
    *length = payload_length;
    return 0;
}

static int run_ip(char *const argv[]) {
    static const char *candidates[] = {
        "/system/bin/ip",
        "/vendor/bin/ip",
        "/sbin/ip",
        "/system/xbin/ip",
    };
    const char *program = NULL;
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        if (access(candidates[i], X_OK) == 0) {
            program = candidates[i];
            break;
        }
    }
    if (!program) program = "ip";
    pid_t pid = fork();
    if (pid < 0) return -errno;
    if (pid == 0) {
        int devnull = open("/dev/null", O_WRONLY | O_CLOEXEC);
        if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        execvp(program, argv);
        _exit(127);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -errno;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -EIO;
}

static int load_gadget_module(const char *name) {
    static const char *candidates[] = {
        "/system/bin/modprobe",
        "/vendor/bin/modprobe",
        "/sbin/modprobe",
        "/system/xbin/modprobe",
    };
    const char *program = NULL;
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        if (access(candidates[i], X_OK) == 0) {
            program = candidates[i];
            break;
        }
    }
    if (!program) program = "modprobe";
    pid_t pid = fork();
    if (pid < 0) return -errno;
    if (pid == 0) {
        int devnull = open("/dev/null", O_WRONLY | O_CLOEXEC);
        if (devnull >= 0) {
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        char *argv[] = { (char *)program, (char *)name, NULL };
        execvp(program, argv);
        _exit(127);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -errno;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -EIO;
}

/* Modules are usually built in on head units, but a modular kernel needs an explicit load before
 * configfs can create the function directories. Failures are harmless for built-ins. */
static void load_gadget_modules(void) {
    static const char *modules[] = { "libcomposite", "usb_f_fs", "usb_f_ncm" };
    for (size_t i = 0; i < sizeof(modules) / sizeof(modules[0]); i++) {
        int rc = load_gadget_module(modules[i]);
        log_line("modprobe %s rc=%d", modules[i], rc);
    }
}

static int snapshot_net(char names[64][NETDEV_MAX + 1], int capacity) {
    DIR *dir = opendir("/sys/class/net");
    if (!dir) return -errno;
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL && count < capacity) {
        if (entry->d_name[0] == '.') continue;
        if (strlen(entry->d_name) > NETDEV_MAX) continue;
        strncpy(names[count], entry->d_name, NETDEV_MAX);
        names[count][NETDEV_MAX] = '\0';
        count++;
    }
    closedir(dir);
    return count;
}

static bool netdev_known(char names[64][NETDEV_MAX + 1], int count, const char *candidate) {
    for (int i = 0; i < count; i++) {
        if (strcmp(names[i], candidate) == 0) return true;
    }
    return false;
}

/* The gadget's NCM function creates exactly one new netdev. Prefer an entry whose uevent names
 * ncm; fall back to the only new non-loopback interface. */
static int find_new_netdev(char names[64][NETDEV_MAX + 1], int count, char *out) {
    DIR *dir = opendir("/sys/class/net");
    if (!dir) return -errno;
    char fallback[NETDEV_MAX + 1] = { 0 };
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        if (strcmp(entry->d_name, "lo") == 0) continue;
        if (netdev_known(names, count, entry->d_name)) continue;
        if (strlen(entry->d_name) > NETDEV_MAX) continue;
        char uevent[320];
        snprintf(uevent, sizeof(uevent), "/sys/class/net/%s/device/uevent", entry->d_name);
        char text[512] = { 0 };
        read_text(uevent, text, sizeof(text));
        if (strstr(text, "ncm") || strstr(text, "cdc_ncm") || strstr(text, "cdc_ether")) {
            strncpy(out, entry->d_name, NETDEV_MAX);
            out[NETDEV_MAX] = '\0';
            closedir(dir);
            return 0;
        }
        if (!fallback[0]) {
            strncpy(fallback, entry->d_name, NETDEV_MAX);
            fallback[NETDEV_MAX] = '\0';
        }
    }
    closedir(dir);
    if (fallback[0]) {
        strncpy(out, fallback, NETDEV_MAX);
        out[NETDEV_MAX] = '\0';
        return 0;
    }
    return -ENODEV;
}

static void configure_netdev(const char *name) {
    char path[256];
    snprintf(path, sizeof(path), "/proc/sys/net/ipv6/conf/%s/disable_ipv6", name);
    if (write_text(path, "0\n") != 0) log_line("warning: could not enable IPv6 on %s", name);
    snprintf(path, sizeof(path), "/proc/sys/net/ipv6/conf/%s/accept_ra", name);
    write_text(path, "0\n");

    char *up[] = { "ip", "link", "set", "dev", (char *)name, "up", NULL };
    char *v6[] = { "ip", "-6", "addr", "replace", "fe80::1/64", "dev", (char *)name, NULL };
    char *v6_add[] = { "ip", "-6", "addr", "add", "fe80::1/64", "dev", (char *)name, NULL };
    char *v4[] = { "ip", "addr", "replace", "169.254.1.1/16", "dev", (char *)name, NULL };
    char *v4_add[] = { "ip", "addr", "add", "169.254.1.1/16", "dev", (char *)name, NULL };
    char *multi[] = { "ip", "link", "set", "dev", (char *)name, "multicast", "on", NULL };
    int rc_up = run_ip(up);
    int rc_v6 = run_ip(v6);
    if (rc_v6 != 0) rc_v6 = run_ip(v6_add);
    int rc_v4 = run_ip(v4);
    if (rc_v4 != 0) rc_v4 = run_ip(v4_add);
    run_ip(multi);
    log_line(
        "netdev %s configured: link=%d ipv6=%d ipv4=%d",
        name,
        rc_up,
        rc_v6,
        rc_v4
    );
}

static int switch_role(const char *role_path) {
    if (read_text(role_path, g_previous_role, sizeof(g_previous_role)) != 0) {
        g_previous_role[0] = '\0';
    }
    if (strcmp(g_previous_role, "device") == 0) {
        log_line("USB role already device");
        return 0;
    }
    int rc = write_text(role_path, "device");
    log_line("wrote device to %s rc=%d previous=%s", role_path, rc, g_previous_role);
    if (rc != 0) return rc;
    g_role_changed = true;
    int64_t deadline = now_millis() + 5000;
    while (!g_stop && now_millis() < deadline) {
        char current[32] = { 0 };
        if (read_text(role_path, current, sizeof(current)) == 0 && strcmp(current, "device") == 0) return 0;
        sleep_millis(100);
    }
    return -ETIMEDOUT;
}

static void restore_role(const char *role_path) {
    if (!g_role_changed) return;
    const char *target = g_previous_role[0] ? g_previous_role : "host";
    int rc = write_text(role_path, target);
    log_line("restored USB role to %s rc=%d", target, rc);
    g_role_changed = false;
}

/* configfs refuses to bind a UDC that already has a gadget. Remember the current owner so the
 * teardown can hand the controller back, which is what keeps ADB recoverable. */
static void release_udc_owner(const char *gadget_base, const char *udc) {
    g_previous_gadget[0] = '\0';
    DIR *dir = opendir(gadget_base);
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        if (strcmp(entry->d_name, GADGET_NAME) == 0) continue;
        char path[320];
        snprintf(path, sizeof(path), "%s/%s/UDC", gadget_base, entry->d_name);
        char owner[128] = { 0 };
        if (read_text(path, owner, sizeof(owner)) == 0 && strcmp(owner, udc) == 0) {
            snprintf(g_previous_gadget, sizeof(g_previous_gadget), "%s/%s", gadget_base, entry->d_name);
            int rc = write_text(path, "\n");
            log_line("released UDC %s from %s rc=%d", udc, g_previous_gadget, rc);
            closedir(dir);
            return;
        }
    }
    closedir(dir);
}

static void restore_previous_gadget(const char *gadget_base, const char *udc) {
    if (!g_previous_gadget[0]) return;
    char path[320];
    snprintf(path, sizeof(path), "%s/UDC", g_previous_gadget);
    int rc = write_text(path, udc);
    log_line("rebound previous gadget %s to %s rc=%d", g_previous_gadget, udc, rc);
    g_previous_gadget[0] = '\0';
}

/* Exact descriptors verified by the reference device: vendor ff/f0/00, bulk ep1 OUT/ep2 IN. */
static const struct {
    struct usb_functionfs_descs_head_v2 header;
    uint32_t fs_count;
    uint32_t hs_count;
    struct {
        struct usb_interface_descriptor intf;
        struct usb_endpoint_descriptor_no_audio out;
        struct usb_endpoint_descriptor_no_audio in;
    } __attribute__((packed)) fs;
    struct {
        struct usb_interface_descriptor intf;
        struct usb_endpoint_descriptor_no_audio out;
        struct usb_endpoint_descriptor_no_audio in;
    } __attribute__((packed)) hs;
} __attribute__((packed)) g_descriptors = {
    .header = {
        .magic = FUNCTIONFS_DESCRIPTORS_MAGIC_V2,
        .length = sizeof(g_descriptors),
        .flags = FUNCTIONFS_HAS_FS_DESC | FUNCTIONFS_HAS_HS_DESC,
    },
    .fs_count = 3,
    .hs_count = 3,
    .fs = {
        .intf = {
            .bLength = USB_DT_INTERFACE_SIZE,
            .bDescriptorType = USB_DT_INTERFACE,
            .bNumEndpoints = 2,
            .bInterfaceClass = 0xff,
            .bInterfaceSubClass = 0xf0,
            .bInterfaceProtocol = 0x00,
            .iInterface = 1,
        },
        .out = {
            .bLength = USB_DT_ENDPOINT_SIZE,
            .bDescriptorType = USB_DT_ENDPOINT,
            .bEndpointAddress = 0x01,
            .bmAttributes = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize = 64,
        },
        .in = {
            .bLength = USB_DT_ENDPOINT_SIZE,
            .bDescriptorType = USB_DT_ENDPOINT,
            .bEndpointAddress = 0x82,
            .bmAttributes = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize = 64,
        },
    },
    .hs = {
        .intf = {
            .bLength = USB_DT_INTERFACE_SIZE,
            .bDescriptorType = USB_DT_INTERFACE,
            .bNumEndpoints = 2,
            .bInterfaceClass = 0xff,
            .bInterfaceSubClass = 0xf0,
            .bInterfaceProtocol = 0x00,
            .iInterface = 1,
        },
        .out = {
            .bLength = USB_DT_ENDPOINT_SIZE,
            .bDescriptorType = USB_DT_ENDPOINT,
            .bEndpointAddress = 0x01,
            .bmAttributes = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize = 512,
        },
        .in = {
            .bLength = USB_DT_ENDPOINT_SIZE,
            .bDescriptorType = USB_DT_ENDPOINT,
            .bEndpointAddress = 0x82,
            .bmAttributes = USB_ENDPOINT_XFER_BULK,
            .wMaxPacketSize = 512,
        },
    },
};

static const uint16_t g_interface_string[] = {
    'i', 'A', 'P', '2', ' ', 'I', 'n', 't', 'e', 'r', 'f', 'a', 'c', 'e', 0,
};

static int publish_descriptors(int ep0) {
    ssize_t n = write(ep0, &g_descriptors, sizeof(g_descriptors));
    if (n != (ssize_t)sizeof(g_descriptors)) return n < 0 ? -errno : -EIO;
    uint8_t strings[sizeof(struct usb_functionfs_strings_head) + 2 + sizeof(g_interface_string)];
    struct usb_functionfs_strings_head header = {
        .magic = FUNCTIONFS_STRINGS_MAGIC,
        .length = (uint32_t)(sizeof(struct usb_functionfs_strings_head) + 2 + sizeof(g_interface_string)),
        .str_count = 1,
        .lang_count = 1,
    };
    memcpy(strings, &header, sizeof(header));
    uint16_t lang = 0x0409;
    memcpy(strings + sizeof(header), &lang, 2);
    memcpy(strings + sizeof(header) + 2, g_interface_string, sizeof(g_interface_string));
    n = write(ep0, strings, header.length);
    if (n != (ssize_t)header.length) return n < 0 ? -errno : -EIO;
    return 0;
}

static int setup_gadget(const char *gadget_base, const char *udc, const char *serial) {
    load_gadget_modules();
    snprintf(g_udc, sizeof(g_udc), "%s", udc);
    snprintf(g_gadget, sizeof(g_gadget), "%s/%s", gadget_base, GADGET_NAME);
    mkdir_one("/sys/kernel/config");
    mkdir_one(gadget_base);
    /* Reclaim a tree left behind by a killed helper. */
    char path[320];
    snprintf(path, sizeof(path), "%s/UDC", g_gadget);
    write_text(path, "\n");
    if (access(g_gadget, F_OK) == 0) {
        snprintf(path, sizeof(path), "%s/configs/c.1/ffs.iap2", g_gadget);
        remove_path(path);
        snprintf(path, sizeof(path), "%s/configs/c.1/ncm.0", g_gadget);
        remove_path(path);
    }
    if (mkdir_one(g_gadget) != 0) return -EIO;
    g_gadget_created = true;

    struct { const char *name; const char *value; } attributes[] = {
        { "idVendor", "0x08e4" },
        { "idProduct", "0x01c0" },
        { "bDeviceClass", "0xef" },
        { "bDeviceSubClass", "0x02" },
        { "bDeviceProtocol", "0x01" },
        { "bcdUSB", "0x0200" },
    };
    for (size_t i = 0; i < sizeof(attributes) / sizeof(attributes[0]); i++) {
        snprintf(path, sizeof(path), "%s/%s", g_gadget, attributes[i].name);
        int rc = write_text(path, attributes[i].value);
        if (rc != 0) {
            log_line("gadget attribute %s failed rc=%d", attributes[i].name, rc);
            return rc;
        }
    }

    snprintf(path, sizeof(path), "%s/strings/0x409", g_gadget);
    if (mkdir_one(path) != 0) return -EIO;
    snprintf(path, sizeof(path), "%s/strings/0x409/serialnumber", g_gadget);
    if (write_text(path, serial) != 0) return -EIO;
    snprintf(path, sizeof(path), "%s/strings/0x409/manufacturer", g_gadget);
    if (write_text(path, "Magic Communication Tec.") != 0) return -EIO;
    snprintf(path, sizeof(path), "%s/strings/0x409/product", g_gadget);
    if (write_text(path, "Auto Box") != 0) return -EIO;

    snprintf(path, sizeof(path), "%s/configs/c.1", g_gadget);
    if (mkdir_one(path) != 0) return -EIO;
    snprintf(path, sizeof(path), "%s/configs/c.1/strings/0x409", g_gadget);
    if (mkdir_one(path) != 0) return -EIO;
    snprintf(path, sizeof(path), "%s/configs/c.1/strings/0x409/configuration", g_gadget);
    if (write_text(path, "CarPlay") != 0) return -EIO;
    snprintf(path, sizeof(path), "%s/configs/c.1/MaxPower", g_gadget);
    if (write_text(path, "500") != 0) return -EIO;

    snprintf(path, sizeof(path), "%s/functions/ffs." FFS_INSTANCE, g_gadget);
    if (mkdir_one(path) != 0) return -EIO;
    snprintf(path, sizeof(path), "%s/functions/ncm.0", g_gadget);
    if (mkdir_one(path) != 0) return -EIO;

    char target[320];
    char link[320];
    snprintf(target, sizeof(target), "%s/functions/ffs." FFS_INSTANCE, g_gadget);
    snprintf(link, sizeof(link), "%s/configs/c.1/ffs." FFS_INSTANCE, g_gadget);
    remove_path(link);
    if (symlink(target, link) != 0) return -errno;
    snprintf(target, sizeof(target), "%s/functions/ncm.0", g_gadget);
    snprintf(link, sizeof(link), "%s/configs/c.1/ncm.0", g_gadget);
    remove_path(link);
    if (symlink(target, link) != 0) return -errno;

    mkdir_one(FFS_MNT);
    /* A killed helper can leave functionfs mounted on this mountpoint. */
    umount2(FFS_MNT, MNT_DETACH);
    int rc = -EIO;
    for (int attempt = 0; attempt < 20 && !g_stop; attempt++) {
        rc = mount(FFS_INSTANCE, FFS_MNT, "functionfs", 0, NULL);
        if (rc == 0) break;
        if (errno != EBUSY) break;
        sleep_millis(300);
    }
    if (rc != 0) {
        log_line("mount functionfs failed: %s", strerror(errno));
        return -errno;
    }
    g_ffs_mounted = true;

    snprintf(g_ffs_ep0, sizeof(g_ffs_ep0), "%s/ep0", FFS_MNT);
    snprintf(g_ffs_ep1, sizeof(g_ffs_ep1), "%s/ep1", FFS_MNT);
    snprintf(g_ffs_ep2, sizeof(g_ffs_ep2), "%s/ep2", FFS_MNT);
    g_ep0 = open(g_ffs_ep0, O_RDWR | O_CLOEXEC);
    if (g_ep0 < 0) return -errno;
    rc = publish_descriptors(g_ep0);
    if (rc != 0) {
        log_line("publish descriptors failed rc=%d", rc);
        return rc;
    }

    release_udc_owner(gadget_base, udc);
    snprintf(path, sizeof(path), "%s/UDC", g_gadget);
    rc = write_text(path, udc);
    if (rc != 0) {
        log_line("bind UDC %s failed rc=%d", udc, rc);
        return rc;
    }
    send_status("gadget bound udc=%s", udc);
    return 0;
}

static int open_endpoints(int *out_ep1, int *out_ep2) {
    int64_t deadline = now_millis() + 25000;
    while (!g_stop && now_millis() < deadline) {
        int ep1 = open(g_ffs_ep1, O_RDWR | O_NONBLOCK | O_CLOEXEC);
        int ep2 = open(g_ffs_ep2, O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (ep1 >= 0 && ep2 >= 0) {
            *out_ep1 = ep1;
            *out_ep2 = ep2;
            return 0;
        }
        if (ep1 >= 0) close(ep1);
        if (ep2 >= 0) close(ep2);
        sleep_millis(200);
    }
    return -1;
}

static void teardown(const char *role_path) {
    if (g_ep0 >= 0) {
        close(g_ep0);
        g_ep0 = -1;
    }
    if (g_gadget_created) {
        char path[320];
        snprintf(path, sizeof(path), "%s/UDC", g_gadget);
        write_text(path, "\n");
        if (g_ffs_mounted) {
            umount2(FFS_MNT, MNT_DETACH);
            g_ffs_mounted = false;
            rmdir(FFS_MNT);
        }
        snprintf(path, sizeof(path), "%s/configs/c.1/ffs." FFS_INSTANCE, g_gadget);
        remove_path(path);
        snprintf(path, sizeof(path), "%s/configs/c.1/ncm.0", g_gadget);
        remove_path(path);
        snprintf(path, sizeof(path), "%s/functions/ncm.0", g_gadget);
        remove_tree(path);
        snprintf(path, sizeof(path), "%s/functions/ffs." FFS_INSTANCE, g_gadget);
        remove_tree(path);
        snprintf(path, sizeof(path), "%s/configs/c.1/strings/0x409", g_gadget);
        remove_tree(path);
        snprintf(path, sizeof(path), "%s/configs/c.1", g_gadget);
        remove_tree(path);
        snprintf(path, sizeof(path), "%s/strings/0x409", g_gadget);
        remove_tree(path);
        snprintf(path, sizeof(path), "%s/strings", g_gadget);
        remove_tree(path);
        snprintf(path, sizeof(path), "%s/configs", g_gadget);
        remove_tree(path);
        snprintf(path, sizeof(path), "%s/functions", g_gadget);
        remove_tree(path);
        remove_tree(g_gadget);
        g_gadget_created = false;
        const char *base = gadget_root();
        if (base) restore_previous_gadget(base, g_udc);
    }
    if (role_path) restore_role(role_path);
}

static int relay(int ep1, int ep2) {
    uint8_t *incoming = malloc(FRAME_BUF);
    uint8_t *rx = malloc(MAX_PAYLOAD);
    if (!incoming || !rx) {
        free(incoming);
        free(rx);
        return -ENOMEM;
    }
    size_t used = 0;
    int rc = 0;
    while (!g_stop) {
        struct pollfd fds[3] = {
            { .fd = g_sock, .events = POLLIN },
            { .fd = ep1, .events = POLLIN },
            { .fd = g_ep0, .events = POLLIN },
        };
        int ready = poll(fds, 3, 500);
        if (ready < 0) {
            if (errno == EINTR) continue;
            rc = -errno;
            break;
        }
        if (fds[2].revents & POLLIN) {
            uint8_t events[64];
            while (read(g_ep0, events, sizeof(events)) > 0) {
            }
        }
        if (fds[1].revents & (POLLIN | POLLERR | POLLHUP)) {
            ssize_t n = read(ep1, rx, MAX_PAYLOAD);
            if (n > 0) {
                send_frame(TYPE_FROM_PHONE, rx, (uint16_t)n);
            } else if (n == 0) {
                send_status("iAP2 OUT endpoint closed");
                rc = -ENODEV;
                break;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                send_status("iAP2 OUT read failed: %s", strerror(errno));
                rc = -errno;
                break;
            }
        }
        if (fds[0].revents & (POLLIN | POLLERR | POLLHUP)) {
            ssize_t n = read(g_sock, incoming + used, FRAME_BUF - used);
            if (n == 0) break;
            if (n < 0) {
                if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
                rc = -errno;
                break;
            }
            used += (size_t)n;
            size_t offset = 0;
            for (;;) {
                uint8_t type = 0;
                uint16_t length = 0;
                int parsed = parse_frame(incoming + offset, used - offset, &type, &length);
                if (parsed < 0) {
                    send_error("invalid control frame from the app");
                    rc = -EPROTO;
                    goto done;
                }
                if (parsed == 0) break;
                const uint8_t *payload = incoming + offset + HEADER_BYTES;
                if (type == TYPE_TO_PHONE && length > 0) {
                    int write_rc = send_all(ep2, payload, length, 10000);
                    if (write_rc != 0) {
                        send_status("iAP2 IN write failed: %d", write_rc);
                        rc = write_rc;
                        goto done;
                    }
                } else if (type == TYPE_STOP) {
                    send_status("stop requested by the app");
                    goto done;
                }
                offset += (size_t)parsed;
            }
            if (offset > 0) {
                memmove(incoming, incoming + offset, used - offset);
                used -= offset;
            }
        }
    }
done:
    free(incoming);
    free(rx);
    return rc;
}

static void usage(void) {
    fprintf(stderr, "usage: diplay_gadget --token=HEX --udc=NAME --role=/sys/class/usb_role/X/role --serial=TEXT\n");
}

int main(int argc, char **argv) {
    const char *token = NULL;
    const char *udc = NULL;
    const char *role_path = NULL;
    const char *serial = NULL;
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "--token=", 8) == 0) token = argv[i] + 8;
        else if (strncmp(argv[i], "--udc=", 6) == 0) udc = argv[i] + 6;
        else if (strncmp(argv[i], "--role=", 7) == 0) role_path = argv[i] + 7;
        else if (strncmp(argv[i], "--serial=", 9) == 0) serial = argv[i] + 9;
    }
    if (!token || strlen(token) != 16 || !udc || !role_path || !serial) {
        usage();
        return 2;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal;
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    signal(SIGPIPE, SIG_IGN);

    const char *gadget_base = gadget_root();
    if (!gadget_base) {
        log_line("configfs usb_gadget is not available");
        return 3;
    }

    g_sock = connect_socket(token);
    if (g_sock < 0) {
        log_line("the app did not connect");
        return 4;
    }
    uint8_t type = 0;
    uint16_t length = 0;
    uint8_t payload[256] = { 0 };
    int hello = read_frame(g_sock, &type, payload, &length);
    if (hello != 0 || type != TYPE_HELLO || length != strlen(token) ||
        memcmp(payload, token, length) != 0) {
        log_line("HELLO validation failed");
        close(g_sock);
        return 5;
    }
    log_line("HELLO accepted; starting the legacy USB gadget");

    int rc = switch_role(role_path);
    if (rc != 0) {
        send_error("USB role switch failed: %d (%s)", rc, strerror(-rc));
        close(g_sock);
        return 6;
    }
    send_status("USB role switched to device");

    char netdev_before[64][NETDEV_MAX + 1];
    int before_count = snapshot_net(netdev_before, 64);
    if (before_count < 0) before_count = 0;

    rc = setup_gadget(gadget_base, udc, serial);
    if (rc != 0) {
        send_error("USB gadget setup failed: %d (%s)", rc, strerror(-rc));
        teardown(role_path);
        close(g_sock);
        return 7;
    }

    int ep1 = -1;
    int ep2 = -1;
    if (open_endpoints(&ep1, &ep2) != 0) {
        send_error("the iPhone did not open the iAP2 endpoints");
        teardown(role_path);
        close(g_sock);
        return 8;
    }
    send_status("iAP2 endpoints open");

    char netdev[NETDEV_MAX + 1] = { 0 };
    int64_t deadline = now_millis() + 15000;
    int net_rc = -ENODEV;
    while (!g_stop && now_millis() < deadline) {
        net_rc = find_new_netdev(netdev_before, before_count, netdev);
        if (net_rc == 0) break;
        sleep_millis(200);
    }
    if (net_rc != 0) {
        send_error("the NCM netdev did not appear");
        close(ep1);
        close(ep2);
        teardown(role_path);
        close(g_sock);
        return 9;
    }
    configure_netdev(netdev);
    send_frame(TYPE_READY, netdev, (uint16_t)strlen(netdev));

    rc = relay(ep1, ep2);
    close(ep1);
    close(ep2);
    teardown(role_path);
    if (rc != 0) log_line("relay ended rc=%d", rc);
    close(g_sock);
    return rc == 0 ? 0 : 10;
}
