# Shared by the Pixel 8 probe scripts. Only the QA app (com.therapylocal.app.qa) is touched.
S=44030DLJH000DD
OUT=/home/martin/projects/therapylocal_workspaces/.bigcache/pixel8
PKG=com.therapylocal.app.qa
D=/sdcard/Android/data/$PKG/files
adbq() { flock /tmp/tl-device-$S.lock adb -s $S "$@"; }
snap() { adbq shell "echo avail_kb=\$(grep MemAvailable /proc/meminfo | tr -s ' ' | cut -d' ' -f2) temp_dC=\$(dumpsys battery | grep temperature | tr -dc 0-9) cached_procs=\$(dumpsys activity lru | grep -v com.therapylocal.app.qa | grep -c ' cch ')"; }
