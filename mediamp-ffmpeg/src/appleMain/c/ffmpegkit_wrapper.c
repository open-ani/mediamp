#ifdef __cplusplus
extern "C" {
#endif

int main(int argc, char **argv);

__attribute__((visibility("default")))
int ffmpegkit_execute(int argc, char **argv) {
    return main(argc, argv);
}

#ifdef __cplusplus
}
#endif
