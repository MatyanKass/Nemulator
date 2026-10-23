// Прослойка от фильтра seccomp Android. Подгружается в QEMU и qemu-img
// через загрузчик musl (--preload).
//
// Android запрещает приложениям часть системных вызовов (faccessat2,
// io_uring_setup, mbind, set_mempolicy…) и на такой вызов шлёт SIGSYS,
// который по умолчанию молча убивает процесс. musl, glib и QEMU не ждут
// этого: на обычном Linux такие вызовы просто отвечают ENOSYS, и программа
// берёт запасной путь. Здесь мы делаем так же: ловим SIGSYS и подставляем
// результат -ENOSYS. (В Termux это делает proot, поэтому там всё работало.)
//
// Тонкость: если SIGSYS в потоке заблокирован, ядро не вызывает
// обработчик, а убивает процесс. QEMU блокирует все сигналы в своих
// потоках, поэтому sigprocmask/pthread_sigmask подменены: SIGSYS из маски
// всегда выбрасывается.

#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

static volatile unsigned char reported[512];

static void report(int nr) {
    if (nr < 0 || nr >= (int)sizeof reported || reported[nr]) return;
    reported[nr] = 1;
    char msg[64] = "nemu: Android запретил syscall ";
    size_t len = strlen(msg);
    char digits[8];
    int n = 0;
    do { digits[n++] = '0' + nr % 10; nr /= 10; } while (nr && n < 8);
    while (n) msg[len++] = digits[--n];
    msg[len++] = '\n';
    (void)!write(2, msg, len);
}

static void on_sigsys(int sig, siginfo_t *si, void *ctx) {
    (void)sig;
    ucontext_t *uc = ctx;
    report(si->si_syscall);
    // После SECCOMP_RET_TRAP pc уже стоит за svc — достаточно задать результат.
    uc->uc_mcontext.regs[0] = (unsigned long)-ENOSYS;
}

static int mask(int how, const sigset_t *set, sigset_t *old) {
    sigset_t copy;
    if (set && how != SIG_UNBLOCK) {
        copy = *set;
        sigdelset(&copy, SIGSYS);
        set = &copy;
    }
    return syscall(SYS_rt_sigprocmask, how, set, old, _NSIG / 8);
}

int sigprocmask(int how, const sigset_t *set, sigset_t *old) {
    return mask(how, set, old);
}

int pthread_sigmask(int how, const sigset_t *set, sigset_t *old) {
    return mask(how, set, old) < 0 ? errno : 0;
}

__attribute__((constructor)) static void init(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = on_sigsys;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigaction(SIGSYS, &sa, NULL);
    sigset_t s;
    sigemptyset(&s);
    sigaddset(&s, SIGSYS);
    syscall(SYS_rt_sigprocmask, SIG_UNBLOCK, &s, NULL, _NSIG / 8);
}
