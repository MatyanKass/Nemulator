// ALSA-плагин «nemu»: звук QEMU идёт не в звуковую карту (на Android её
// для нас нет), а в unix-сокет, к которому подключается приложение.
//
//   воспроизведение: QEMU → плагин → сокет out → AudioTrack
//   запись:          AudioRecord → сокет in → плагин → QEMU (микрофон в Windows)
//
// Плагин сам служит сервером: слушает сокет и принимает одного клиента в
// фоновом потоке, чтобы QEMU никогда не ждал приложение. Нет клиента —
// вывод выбрасывается, на вход идёт тишина.
//
// Часы — CLOCK_MONOTONIC: позиция «железа» считается от времени старта,
// поэтому QEMU получает и отдаёт звук ровно в реальном темпе.

#define _GNU_SOURCE
#include <alsa/asoundlib.h>
#include <alsa/pcm_external.h>
#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <sys/timerfd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define RATE 44100
#define CHANNELS 2
#define FRAME_BYTES (CHANNELS * 2)
// Полсекунды входного звука про запас; больше не копим, чтобы не росла задержка.
#define RING_FRAMES (RATE / 2)

typedef struct {
    snd_pcm_ioplug_t io;
    int listen_fd;
    int client_fd;
    int timer_fd;  // тикает каждые 5 мс — для программ, которые ждут через poll
    pthread_t thread;
    pthread_mutex_t lock;
    int stop;

    struct timespec start;
    snd_pcm_uframes_t done;  // кадров с prepare: записано QEMU или отдано ему
    int running;

    unsigned char ring[RING_FRAMES * FRAME_BYTES];
    size_t ring_head, ring_len;  // в байтах
} nemu_t;

static snd_pcm_uframes_t elapsed_frames(nemu_t *n) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    double sec = (now.tv_sec - n->start.tv_sec) + (now.tv_nsec - n->start.tv_nsec) / 1e9;
    return (snd_pcm_uframes_t)(sec * RATE);
}

// Сдвигает старт так, чтобы «прошло» ровно frames кадров. Нужен после
// недогруза или перегруза: иначе QEMU попытается догнать упущенное разом.
static void rebase(nemu_t *n, snd_pcm_uframes_t frames) {
    clock_gettime(CLOCK_MONOTONIC, &n->start);
    long long ns = (long long)n->start.tv_nsec - (long long)frames * 1000000000LL / RATE;
    n->start.tv_sec += ns / 1000000000LL;
    ns %= 1000000000LL;
    if (ns < 0) { ns += 1000000000LL; n->start.tv_sec--; }
    n->start.tv_nsec = ns;
}

static void drop_client(nemu_t *n) {
    if (n->client_fd >= 0) {
        close(n->client_fd);
        n->client_fd = -1;
    }
}

static void *serve(void *arg) {
    nemu_t *n = arg;
    unsigned char buf[4096];
    while (!n->stop) {
        // accept4, а не accept: голый accept фильтр seccomp Android запрещает.
        int fd = accept4(n->listen_fd, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) {
            if (n->stop) break;
            usleep(100000);
            continue;
        }
        pthread_mutex_lock(&n->lock);
        drop_client(n);
        n->client_fd = fd;
        n->ring_len = 0;
        pthread_mutex_unlock(&n->lock);

        if (n->io.stream == SND_PCM_STREAM_PLAYBACK) continue;

        // Запись: читаем микрофон приложения в кольцевой буфер.
        for (;;) {
            ssize_t got = read(fd, buf, sizeof buf);
            if (got <= 0) break;
            pthread_mutex_lock(&n->lock);
            for (ssize_t i = 0; i < got; i++) {
                if (n->ring_len == sizeof n->ring) {  // переполнен — старое долой
                    n->ring_head = (n->ring_head + 1) % sizeof n->ring;
                    n->ring_len--;
                }
                n->ring[(n->ring_head + n->ring_len) % sizeof n->ring] = buf[i];
                n->ring_len++;
            }
            pthread_mutex_unlock(&n->lock);
        }
        pthread_mutex_lock(&n->lock);
        if (n->client_fd == fd) drop_client(n);
        pthread_mutex_unlock(&n->lock);
    }
    return NULL;
}

// Счёт кадров сбрасывается здесь, а не в start: до старта QEMU уже
// заполняет буфер воспроизведения, и эти кадры тоже надо учесть.
static int nemu_prepare(snd_pcm_ioplug_t *io) {
    nemu_t *n = io->private_data;
    n->done = 0;
    n->running = 0;
    return 0;
}

static int nemu_start(snd_pcm_ioplug_t *io) {
    nemu_t *n = io->private_data;
    n->running = 1;
    clock_gettime(CLOCK_MONOTONIC, &n->start);
    return 0;
}

static int nemu_stop(snd_pcm_ioplug_t *io) {
    nemu_t *n = io->private_data;
    n->running = 0;
    return 0;
}

static snd_pcm_sframes_t nemu_pointer(snd_pcm_ioplug_t *io) {
    nemu_t *n = io->private_data;
    if (!n->running) return 0;
    snd_pcm_uframes_t pos = elapsed_frames(n);
    if (io->stream == SND_PCM_STREAM_PLAYBACK) {
        // Нельзя «проиграть» больше, чем QEMU успел записать.
        if (pos > n->done) {
            pos = n->done;
            rebase(n, pos);
        }
    } else if (pos > n->done + io->buffer_size) {
        // QEMU давно не забирал запись — забываем пропущенное.
        pos = n->done + io->buffer_size;
        rebase(n, pos);
    }
    return pos % io->buffer_size;
}

static snd_pcm_sframes_t nemu_transfer(snd_pcm_ioplug_t *io,
                                       const snd_pcm_channel_area_t *areas,
                                       snd_pcm_uframes_t offset,
                                       snd_pcm_uframes_t size) {
    nemu_t *n = io->private_data;
    unsigned char *data = (unsigned char *)areas->addr + (areas->first + areas->step * offset) / 8;
    size_t bytes = size * FRAME_BYTES;

    pthread_mutex_lock(&n->lock);
    if (io->stream == SND_PCM_STREAM_PLAYBACK) {
        // Приложение не успевает — лучше потерять кусок, чем остановить ПК.
        if (n->client_fd >= 0 && send(n->client_fd, data, bytes, MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
            errno != EAGAIN && errno != EWOULDBLOCK) {
            drop_client(n);
        }
    } else {
        size_t take = bytes < n->ring_len ? bytes : n->ring_len;
        take -= take % FRAME_BYTES;
        for (size_t i = 0; i < take; i++) {
            data[i] = n->ring[n->ring_head];
            n->ring_head = (n->ring_head + 1) % sizeof n->ring;
        }
        n->ring_len -= take;
        memset(data + take, 0, bytes - take);
    }
    pthread_mutex_unlock(&n->lock);

    n->done += size;
    return size;
}

static int nemu_poll_revents(snd_pcm_ioplug_t *io, struct pollfd *pfd, unsigned int nfds,
                             unsigned short *revents) {
    nemu_t *n = io->private_data;
    (void)pfd;
    (void)nfds;
    uint64_t ticks;
    while (read(n->timer_fd, &ticks, sizeof ticks) > 0) {
    }
    snd_pcm_sframes_t avail = snd_pcm_avail_update(io->pcm);
    *revents = avail >= (snd_pcm_sframes_t)io->period_size ? io->poll_events : 0;
    return 0;
}

static int nemu_close(snd_pcm_ioplug_t *io) {
    nemu_t *n = io->private_data;
    n->stop = 1;
    shutdown(n->listen_fd, SHUT_RDWR);
    close(n->listen_fd);
    pthread_mutex_lock(&n->lock);
    if (n->client_fd >= 0) shutdown(n->client_fd, SHUT_RDWR);
    pthread_mutex_unlock(&n->lock);
    pthread_join(n->thread, NULL);
    drop_client(n);
    close(n->timer_fd);
    pthread_mutex_destroy(&n->lock);
    free(n);
    return 0;
}

static const snd_pcm_ioplug_callback_t callbacks = {
    .prepare = nemu_prepare,
    .start = nemu_start,
    .stop = nemu_stop,
    .pointer = nemu_pointer,
    .transfer = nemu_transfer,
    .poll_revents = nemu_poll_revents,
    .close = nemu_close,
};

SND_PCM_PLUGIN_DEFINE_FUNC(nemu) {
    const char *out_path = NULL, *in_path = NULL;
    snd_config_iterator_t i, next;
    snd_config_for_each(i, next, conf) {
        snd_config_t *c = snd_config_iterator_entry(i);
        const char *id;
        if (snd_config_get_id(c, &id) < 0) continue;
        if (!strcmp(id, "comment") || !strcmp(id, "type") || !strcmp(id, "hint")) continue;
        if (!strcmp(id, "out")) { snd_config_get_string(c, &out_path); continue; }
        if (!strcmp(id, "in")) { snd_config_get_string(c, &in_path); continue; }
        SNDERR("nemu: неизвестный параметр %s", id);
        return -EINVAL;
    }
    const char *path = stream == SND_PCM_STREAM_PLAYBACK ? out_path : in_path;
    if (!path || strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        SNDERR("nemu: не задан путь сокета (out/in)");
        return -EINVAL;
    }

    nemu_t *n = calloc(1, sizeof *n);
    if (!n) return -ENOMEM;
    n->client_fd = -1;
    pthread_mutex_init(&n->lock, NULL);

    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    strcpy(addr.sun_path, path);
    unlink(path);
    n->listen_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (n->listen_fd < 0 || bind(n->listen_fd, (struct sockaddr *)&addr, sizeof addr) < 0 ||
        listen(n->listen_fd, 1) < 0) {
        int err = -errno;
        SNDERR("nemu: не открыть сокет %s: %s", path, strerror(errno));
        if (n->listen_fd >= 0) close(n->listen_fd);
        free(n);
        return err;
    }
    n->timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK);
    struct itimerspec tick = {{0, 5000000}, {0, 5000000}};
    timerfd_settime(n->timer_fd, 0, &tick, NULL);

    n->io.version = SND_PCM_IOPLUG_VERSION;
    n->io.name = "Nemulator";
    n->io.callback = &callbacks;
    n->io.private_data = n;
    n->io.poll_fd = n->timer_fd;
    n->io.poll_events = stream == SND_PCM_STREAM_PLAYBACK ? POLLOUT : POLLIN;

    int err = snd_pcm_ioplug_create(&n->io, name, stream, mode);
    if (err < 0) {
        close(n->listen_fd);
        close(n->timer_fd);
        free(n);
        return err;
    }

    static const unsigned int access[] = {SND_PCM_ACCESS_RW_INTERLEAVED};
    static const unsigned int formats[] = {SND_PCM_FORMAT_S16_LE};
    snd_pcm_ioplug_set_param_list(&n->io, SND_PCM_IOPLUG_HW_ACCESS, 1, access);
    snd_pcm_ioplug_set_param_list(&n->io, SND_PCM_IOPLUG_HW_FORMAT, 1, formats);
    snd_pcm_ioplug_set_param_minmax(&n->io, SND_PCM_IOPLUG_HW_CHANNELS, CHANNELS, CHANNELS);
    snd_pcm_ioplug_set_param_minmax(&n->io, SND_PCM_IOPLUG_HW_RATE, RATE, RATE);
    snd_pcm_ioplug_set_param_minmax(&n->io, SND_PCM_IOPLUG_HW_PERIOD_BYTES, 256, 64 * 1024);
    snd_pcm_ioplug_set_param_minmax(&n->io, SND_PCM_IOPLUG_HW_PERIODS, 2, 64);

    pthread_create(&n->thread, NULL, serve, n);
    *pcmp = n->io.pcm;
    return 0;
}

SND_PCM_PLUGIN_SYMBOL(nemu);
