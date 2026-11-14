S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
curl -sL -o vkh.tar.gz https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/refs/tags/v1.4.309 && mkdir -p vkh && tar -xzf vkh.tar.gz -C vkh --strip-components=1
curl -sL -o clh.tar.gz https://codeload.github.com/KhronosGroup/OpenCL-Headers/tar.gz/refs/tags/v2024.10.24 && mkdir -p clh && tar -xzf clh.tar.gz -C clh --strip-components=1
ls vkh/include/vulkan | head -3; ls clh/CL | head -3
mkdir -p libs
for s in 192.168.1.15:5555 192.168.1.7:5555 192.168.1.11:5555; do
  d=$(echo $s | cut -d: -f1); mkdir -p libs/$d
  adb -s $s pull /vendor/lib64/libOpenCL.so libs/$d/ 2>&1 | tail -1
  adb -s $s shell ls /vendor/lib64 | grep -iE "libvulkan|libOpenCL|vulkan.*so|libGLES_mali|libEGL_adreno|vulkan.adreno"
  adb -s $s shell ls /vendor/lib64/hw | grep -iE "vulkan"
done
