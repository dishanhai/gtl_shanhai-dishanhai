//! Repeatable 2560x1600 A/B of baseline and candidate white-hole shaders.
//! Compile with: rustc --edition=2024 -O src/test/rust/whitehole_gpu_ab.rs
//! The variants directory must contain baseline/ and candidate/ shader trees
//! (including core/ and include/). Use fresh precompiled test classes; the
//! classpath file contains sourceSets.test.runtimeClasspath.asPath, one line.
//! The runner alternates variants, preserves probe logs and frames, and
//! reports medians only if all built-in visual assertions pass.
use std::{env, fs, path::Path, process::Command};
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = env::args_os().skip(1).collect();
    let [root, repo, java, classpath]: [_; 4] = args.try_into()
        .map_err(|_| "usage: whitehole-ab <variants-dir> <repo> <java> <classpath-file>")?;
    let root = Path::new(&root);
    let cp = fs::read_to_string(classpath)?;
    let mut samples = [[Vec::<f64>::new(), Vec::<f64>::new()], [Vec::new(), Vec::new()]];
    for (index, variant) in ["baseline", "candidate", "candidate", "baseline", "baseline", "candidate"].into_iter().enumerate() {
        let dest = root.join(format!("{index}-{variant}"));
        let output = Command::new(&java).current_dir(&repo)
            .args(["--enable-native-access=ALL-UNNAMED", "-Dkerr.width=2560", "-Dkerr.height=1600"])
            .arg(format!("-Dkerr.shaderRoot={}", root.join(variant).join("core").display()))
            .arg(format!("-Dwhitehole.reportDir={}", dest.display()))
            .args(["-cp", cp.trim(), "com.dishanhai.gt_shanhai.client.renderer.blackhole.GuixuWhiteHoleGpuProbe", "--host-views", "--host-particles"]).output()?;
        fs::write(root.join(format!("{index}-{variant}.log")), [&output.stdout[..], &output.stderr[..]].concat())?;
        let log = String::from_utf8_lossy(&output.stdout);
        let line = log.lines().find(|l| l.starts_with("Host moving GPU with stars, particles and sloped terrain "))
            .ok_or_else(|| format!("{variant}: failed to produce timings ({}). Log: {}", output.status, dest.display()))?;
        if !output.status.success() { return Err(format!("{variant} failed visual assertions: {}", output.status).into()); }
        let numbers: Vec<f64> = line.split_whitespace().filter_map(|s| s.parse().ok()).collect();
        let [full, reduced] = numbers[..] else { return Err(format!("Unexpected timing line: {line}").into()); };
        println!("{index} {variant}: {full:.2} ms full, {reduced:.2} ms layered");
        let v = usize::from(variant == "candidate");
        samples[v][0].push(full);
        samples[v][1].push(reduced);
    }
    for (i, label) in ["full", "layered"].into_iter().enumerate() {
        let mut median = |variant: usize| {
            let v = &mut samples[variant][i];
            v.sort_by(f64::total_cmp);
            v[v.len() / 2]
        };
        let (a, b) = (median(0), median(1));
        println!("{label} median: {a:.2} -> {b:.2} ms ({:+.1}%)", 100.0 * (b / a - 1.0));
    }
    Ok(())
}
