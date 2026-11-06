/* Android's app seccomp profile rejects the legacy x86_64 creat syscall.
 * glibc still uses it for creat()/creat64(), including dpkg-deb's ar output.
 * openat with these flags has the same POSIX semantics and is permitted.
 * Keep this shim narrow: errors, mode/umask and truncation remain kernel-owned. */
#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#include <fcntl.h>
#include <sys/types.h>

int creat(const char *path, mode_t mode)
{
    return openat(AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC, mode);
}

int creat64(const char *path, mode_t mode)
{
    return openat64(AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC, mode);
}
