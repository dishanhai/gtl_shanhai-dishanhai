# Rust white-hole rendering backend (experimental)

This opt-in backend runs on Minecraft's existing OpenGL context and render thread. The Rust module owns white-hole direct-layer resolution and cache policy, the particle tick and three-pass compute submission, and triangle submission for the world-primary trace, white optical layer, light cache and bloom source. The game still owns shader reloads, geometry buffers, framebuffer lifetime, uniforms and scene compositing. Existing GLSL continues to calculate rays and lighting on the GPU; there is no CPU framebuffer copy. Neither black-hole mode selects the Rust path.

## Build and run

On Windows with JDK 21, Gradle 8.8, and a Rust/MSVC toolchain:

```powershell
cargo test --manifest-path native/white-hole/Cargo.toml
cargo build --release --manifest-path native/white-hole/Cargo.toml
$dll = (Resolve-Path native/white-hole/target/release/gtsh_white_hole.dll).Path
gradle whiteHoleParticleGpuProbe
gradle whiteHoleParticleGpuProbe "-PrustWhiteHoleLibrary=$dll"
```

Use `whiteHoleGpuProbe -PwhiteHoleWidth=2560 -PwhiteHoleHeight=1600 --args="--host-views --host-particles"` with and without the same Gradle property for the production shader scene comparison.

For a normal game launch, set the JVM argument `-Dgtsh.whitehole.rustLibrary=<absolute path to your locally built library>`. Leave it unset for the original Java/OpenGL path. The matching Rust library is not shipped in the normal build; a missing library or unsupported ABI leaves the Java path active. Rust and JVM architectures must match.

The offscreen probe compares particle atlas checksums, verifies the native fullscreen triangle against Java on the same OpenGL context, and exercises GL state restoration. For a complete client comparison, switch between developer test runs with the JVM switch on and off, try white-hole preview and world-host modes, orbit at close range, resize from 1080p to 1600p, and verify the two existing black-hole modes remain visually unchanged. Compare frame time by GPU stage with SHDT before claiming whole-renderer speedups.
