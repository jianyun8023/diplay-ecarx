fn main() {
    let source = std::path::Path::new("../../jni");
    println!("cargo:rerun-if-changed=../../jni/speex_echo.c");
    println!("cargo:rerun-if-changed=../../jni/speex_echo.h");
    println!("cargo:rerun-if-changed=../../jni/speexdsp");
    let mut build = cc::Build::new();
    build.include(source.join("speexdsp")).include(source.join("speexdsp/include"))
        .define("FLOATING_POINT", None).define("USE_KISS_FFT", None).define("EXPORT", Some(""))
        .opt_level(2).warnings(false).file(source.join("speex_echo.c"));
    for name in ["mdf", "preprocess", "fftwrap", "kiss_fft", "kiss_fftr", "filterbank"] {
        build.file(source.join(format!("speexdsp/{name}.c")));
    }
    build.compile("diplay_speex");
}
