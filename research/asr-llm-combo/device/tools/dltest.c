#include <dlfcn.h>
#include <stdio.h>
int main(int c, char**v){ void*h=dlopen(v[1],RTLD_NOW|RTLD_LOCAL); printf("h=%p err=%s\n",h,dlerror()); if(!h) return 1; void*s=dlsym(h,"ggml_backend_init"); printf("sym=%p err=%s\n",s,dlerror()); void*(*f)(void)=s; if(s){void*r=f(); printf("reg=%p\n",r);} return 0;}
