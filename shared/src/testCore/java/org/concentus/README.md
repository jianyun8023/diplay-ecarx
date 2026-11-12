# Concentus (vendored)

The Java port of the Opus reference library from
[lostromb/concentus](https://github.com/lostromb/concentus) at commit
`3885c4e46513ef0fc81fca100189e54f1714c6ca`: every file of `Java/Concentus/src/main/java/org/concentus`,
unmodified. Upstream publishes no release artifact of this port.

DiPlay retains this port only as an independent test oracle for its Rust Opus codec.
The microphone's production software encoder uses Rust; Android MediaCodec capability
selection remains in the platform adapter. The Java sources are shared only by unit and device tests.

Licensed under the BSD-style licence in `LICENSE`, which retains the Opus copyright notices.
