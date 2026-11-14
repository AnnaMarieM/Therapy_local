S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
bash build.sh cl -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENCL=ON -DOpenCL_INCLUDE_DIR=$S/clh -DOpenCL_LIBRARY=$S/libs/192.168.1.15/libOpenCL.so > build-cl.log 2>&1
bash build.sh cl2 -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENCL=ON -DGGML_OPENCL_TARGET_VERSION=200 -DOpenCL_INCLUDE_DIR=$S/clh -DOpenCL_LIBRARY=$S/libs/192.168.1.7/libOpenCL.so > build-cl2.log 2>&1
echo CL3DONE > cl3.done
