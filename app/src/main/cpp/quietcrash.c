// AD-04: a crash of an engine host process ends it quietly. Fatal signals call _exit(base + signo), so the system sees a plain exit
// (EXIT_SELF, status base + signo) instead of a native crash: no tombstone, no DropBox entry, no "keeps stopping" dialog for the app.
//
// Only async-signal-safe calls run in the handler (_exit). The handler is installed with sigaction(); in an app process that call
// resolves to libsigchain (app_process lists it in DT_NEEDED so every library finds it before libc). libsigchain keeps ART's own handler
// first (implicit null checks, stack overflow) and only passes on a signal ART declines to the handler registered here. install() checks
// that sigaction really comes from libsigchain and installs nothing if it does not, because a plain libc sigaction would replace ART's
// handler and break every managed null check.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <jni.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>

static int g_base = 0;

static void on_fatal(int signo, siginfo_t* info, void* ctx) {
  (void)info;
  (void)ctx;
  _exit(g_base + signo);
}

static const int kSignals[] = {SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL, SIGSYS, SIGTRAP};

// The library that provides sigaction as this library sees it.
static const char* sigaction_owner(void) {
  Dl_info di;
  if (dladdr((void*)&sigaction, &di) && di.dli_fname) return di.dli_fname;
  return "";
}

JNIEXPORT jint JNICALL Java_com_therapylocal_app_engine_NativeQuietCrash_install(JNIEnv* env, jclass cls, jint base) {
  (void)env;
  (void)cls;
  if (strstr(sigaction_owner(), "sigchain") == NULL) return 1;  // NOT_CHAINED
  g_base = base;
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_fatal;
  sigemptyset(&sa.sa_mask);
  // SA_ONSTACK: run on the thread's alternate stack when it has one (ART sets one on every thread it creates, so a stack overflow still
  // reaches the handler). SA_NODEFER is not set: a fault inside the handler cannot happen, it only calls _exit.
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  for (size_t i = 0; i < sizeof kSignals / sizeof kSignals[0]; i++) {
    if (sigaction(kSignals[i], &sa, NULL) != 0) return 2;
  }
  return 0;
}

JNIEXPORT jstring JNICALL Java_com_therapylocal_app_engine_NativeQuietCrash_sigactionOwner(JNIEnv* env, jclass cls) {
  (void)cls;
  return (*env)->NewStringUTF(env, sigaction_owner());
}
