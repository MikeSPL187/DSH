#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define REQUIRE(expr) do { if (!(expr)) { perror(#expr); return 1; } } while (0)

int main(void)
{
    char directory[] = "/tmp/dsh-creat-XXXXXX";
    REQUIRE(mkdtemp(directory) != NULL);
    REQUIRE(chdir(directory) == 0);
    umask(0027);
#ifdef SYS_creat
    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_creat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog filter = { .len = sizeof(instructions) / sizeof(instructions[0]), .filter = instructions };
    REQUIRE(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
    REQUIRE(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter) == 0);
#endif
    int fd = creat("ordinary", 0666);
    REQUIRE(fd >= 0);
    REQUIRE(write(fd, "old", 3) == 3);
    REQUIRE(close(fd) == 0);
    struct stat info;
    REQUIRE(stat("ordinary", &info) == 0 && (info.st_mode & 0777) == 0640);
    fd = creat64("ordinary", 0600);
    REQUIRE(fd >= 0);
    REQUIRE(fstat(fd, &info) == 0 && info.st_size == 0 && (info.st_mode & 0777) == 0640);
    REQUIRE(write(fd, "new", 3) == 3);
    REQUIRE(close(fd) == 0);
    fd = creat64("large-file", 0666);
    REQUIRE(fd >= 0);
    REQUIRE(close(fd) == 0);
    errno = 0;
    REQUIRE(creat("missing/child", 0600) == -1 && errno == ENOENT);
    REQUIRE(unlink("ordinary") == 0 && unlink("large-file") == 0);
    REQUIRE(chdir("/") == 0 && rmdir(directory) == 0);
    puts("creat compatibility: seccomp, umask, truncation, creat64 and errno PASS");
    return 0;
}
