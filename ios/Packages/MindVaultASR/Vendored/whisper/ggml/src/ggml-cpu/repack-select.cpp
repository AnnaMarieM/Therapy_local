// SwiftPM arch selection for the vendored repack kernels.
//
// Upstream ggml-cpu's CMake picks the repack sources per arch: repack.cpp is
// compiled on every arch, and the arch/ file is compiled only for that arch
// (ARM: arch/arm/repack.cpp; x86: arch/x86/repack.cpp). This vendoring ships
// only the ARM arch file, so the x86 pairing is impossible; SwiftPM manifests
// also have no arch conditions. This shim — ours, not vendored — reproduces
// the CMake branch structure instead:
//
//   ARM        repack.cpp as upstream ships it (arch-fallback.h's ARM #elif
//              renames what arch/arm/repack.cpp does not provide) plus the ARM
//              arch file itself;
//   elsewhere  repack.cpp with GGML_CPU_GENERIC, upstream's own fallback for
//              archs it has no arch/ files for (CMakeLists.txt: the
//              unknown-architecture branch appends -DGGML_CPU_GENERIC), so
//              every repack kernel is the generic implementation.
//
// The two failure modes this replaces, both real CI runs:
// - arch/arm/repack.cpp compiled unconditionally redefined all 27 repack
//   kernels on x86_64 — the file's scalar #else branches on top of
//   arch-fallback.h's x86 renames — and the link failed (run 43797191556,
//   gold: arch/arm/repack.cpp.o first, repack.cpp.o second).
// - with the ARM file selected away, the 4x8/8x8 kernels (ARM i8mm variants
//   with no generic provider under the x86 #elif) were undefined (run
//   43826845274). GGML_CPU_GENERIC selects their _generic providers.
//
// Generic x86 is correct everywhere and only slower; x86 SIMD could be
// restored by shipping arch/x86/{quants.c,repack.cpp} from the pin. The
// vendored files are untouched — see VENDORED.md.
#if defined(__aarch64__) || defined(__arm__) || defined(_M_ARM) || defined(_M_ARM64) || defined(_M_ARM64EC)
#include "repack.cpp"
#include "arch/arm/repack.cpp"
#else
#define GGML_CPU_GENERIC
#include "repack.cpp"
#endif
