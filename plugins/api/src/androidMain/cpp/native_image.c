/* Generic ARM64 static ELF bootstrap. No application or guest-execution policy. */
#include <elf.h>
#include <stdint.h>
#include <stddef.h>

static long call(long n, long a, long b, long c, long d, long e, long f) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    register long x8 __asm__("x8") = n;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
    return x0;
}
static void fail_at(unsigned line) {
    static const char message[] = "native image bootstrap failed\n";
    call(64, 2, (long)message, sizeof(message)-1, 0, 0, 0);
    char number[16];
    size_t n = 0;
    do { number[n++] = '0' + line % 10; line /= 10; } while (line);
    for (size_t i = 0; i < n/2; ++i) { char c=number[i]; number[i]=number[n-i-1]; number[n-i-1]=c; }
    number[n++]='\n';
    call(64, 2, (long)number, n, 0, 0, 0);
    call(94, 126, 0, 0, 0, 0, 0);
    __builtin_unreachable();
}
#define fail() fail_at(__LINE__)
static void read_exact(long fd, void *buffer, size_t size) {
    size_t done = 0;
    while (done < size) {
        long n = call(63, fd, (long)((char *)buffer + done), size-done, 0, 0, 0);
        if (n == -4) continue;
        if (n <= 0) fail();
        done += n;
    }
}
uintptr_t map_image(void) {
    Elf64_auxv_t aux[64];
    long fd = call(56, -100, (long)"/proc/self/auxv", 0, 0, 0, 0);
    if (fd < 0) fail();
    long count = call(63, fd, (long)aux, sizeof(aux), 0, 0, 0);
    call(57, fd, 0, 0, 0, 0, 0);
    if (count <= 0 || count % sizeof(aux[0])) fail();
    size_t page = 0;
    for (long i = 0; i < count/(long)sizeof(aux[0]); ++i) {
        if (aux[i].a_type == AT_PAGESZ) page = aux[i].a_un.a_val;
    }
    if (page < 4096 || (page & (page-1))) fail();
    // The caller exposes its image through its own filesystem namespace.
    fd = call(56, -100, (long)"/.__kcode_native_image/image.elf", 0, 0, 0, 0);
    if (fd < 0) fail();
    Elf64_Ehdr header;
    read_exact(fd, &header, sizeof(header));
    if (header.e_ident[0] != 127 || header.e_ident[1] != 'E' || header.e_ident[2] != 'L' ||
        header.e_ident[3] != 'F' || header.e_ident[EI_CLASS] != ELFCLASS64 ||
        header.e_ident[EI_DATA] != ELFDATA2LSB || header.e_ident[EI_VERSION] != EV_CURRENT ||
        header.e_machine != EM_AARCH64 || header.e_type != ET_EXEC ||
        header.e_phentsize != sizeof(Elf64_Phdr) || header.e_phnum > 32) fail();
    long file_size = call(62, fd, 0, 2, 0, 0, 0);
    if (file_size < (long)sizeof(header) || file_size > 64*1024*1024 ||
        header.e_phoff > (uint64_t)file_size ||
        header.e_phnum*sizeof(Elf64_Phdr) > (uint64_t)file_size-header.e_phoff) fail();
    Elf64_Phdr segments[32];
    if (call(62, fd, header.e_phoff, 0, 0, 0, 0) < 0) fail();
    read_exact(fd, segments, header.e_phnum*sizeof(segments[0]));
    int entry_found = 0;
    for (unsigned i = 0; i < header.e_phnum; ++i) {
        Elf64_Phdr *p = &segments[i];
        if (p->p_type == PT_INTERP) fail();
        if (p->p_type != PT_LOAD) continue;
        if (p->p_offset > (uint64_t)file_size || p->p_filesz > (uint64_t)file_size-p->p_offset ||
            p->p_memsz > 64*1024*1024) fail();
        if (p->p_memsz < p->p_filesz || (p->p_vaddr & (page-1)) != (p->p_offset & (page-1))) fail();
        uintptr_t begin = p->p_vaddr & ~(page-1);
        uintptr_t end = (p->p_vaddr+p->p_memsz+page-1) & ~(page-1);
        if (end <= begin) fail();
        // Keep bootstrap addresses separate from private image addresses.
        if (begin < 0x1000000000ULL || end > 0x2800000000ULL) fail();
        int prot = (p->p_flags & PF_R ? 1 : 0) | (p->p_flags & PF_W ? 2 : 0) | (p->p_flags & PF_X ? 4 : 0);
        if ((prot & 6) == 6) fail();
        if (p->p_filesz != p->p_memsz) fail();
        long mapped = call(222, begin, end-begin, prot, 2|16, fd, p->p_offset & ~(page-1));
        if (mapped != (long)begin) fail();
        if ((prot & 4) && header.e_entry >= p->p_vaddr && header.e_entry < p->p_vaddr+p->p_memsz) entry_found = 1;
    }
    call(57, fd, 0, 0, 0, 0, 0);
    if (!entry_found) fail();
    return header.e_entry;
}
__asm__(".global _start\n"
        "_start:\n"
        "mov x19, x0\n"
        "mov x20, sp\n"
        "bl map_image\n"
        "mov x21, x0\n"
        "mov x0, x19\n"
        "mov sp, x20\n"
        "br x21\n");
