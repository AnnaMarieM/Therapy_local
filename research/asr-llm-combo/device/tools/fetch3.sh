S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
cd $S
curl -sL -o llama.tar.gz https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/b11869 && mkdir -p llama && tar -xzf llama.tar.gz -C llama --strip-components=1
ls llama/ggml/src | head -40
grep -n "GGML_VERSION\|GGML_BACKEND_API_VERSION" llama/ggml/CMakeLists.txt llama/ggml/include/ggml-backend.h | head
grep -n "GGML_VERSION\|GGML_BACKEND_API_VERSION" src/ggml/CMakeLists.txt src/ggml/include/ggml-backend.h | head
ls llama/ggml/src/ggml-hexagon
