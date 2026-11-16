// SwiftPM arch selection for the vendored ARM repack kernels.
//
// Upstream ggml-cpu's CMake compiles arch/arm/repack.cpp only in ARM builds;
// everywhere else the generic repack.cpp plus arch-fallback.h's per-arch
// #elif blocks select the kernels. SwiftPM manifests have no arch conditions,
// so this shim — ours, not vendored — includes the vendored file only under
// the condition upstream's own arch selection uses (the same macro set as
// arch-fallback.h's ARM #elif). Compiled unconditionally, the file's scalar
// #else branches defined all 27 repack kernels a second time on x86_64, on
// top of arch-fallback.h's x86 renames inside repack.cpp, and the linux-core
// link failed with "multiple definition" for each (run 43797191556; gold:
// arch/arm/repack.cpp.o first, repack.cpp.o second). The vendored file is
// untouched — see VENDORED.md.
#if defined(__aarch64__) || defined(__arm__) || defined(_M_ARM) || defined(_M_ARM64) || defined(_M_ARM64EC)
#include "arch/arm/repack.cpp"
#endif
