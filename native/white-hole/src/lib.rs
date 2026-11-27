//! White-hole render scheduling and OpenGL compute submission on the game render thread.
use std::ffi::c_void;
use std::mem::transmute;

const STORAGE_BUFFER: u32 = 0x90D2;
const READ_WRITE: u32 = 0x88BA;
const WRITE_ONLY: u32 = 0x88B9;
const R32UI: u32 = 0x8236;
const R16F: u32 = 0x822D;
const IMAGE: u32 = 0x0020;
const STORAGE: u32 = 0x2000;
const TEXTURE_FETCH: u32 = 0x0008;
const ATLAS_GROUPS: u32 = 512 / 16;

fn direct_divisor(adaptive: bool, area: f64, previous: i32) -> i32 {
    let budget = if adaptive { 320_000.0 } else { 2_100_000.0 };
    let native_limit = if adaptive { 480_000.0 } else { budget };
    let requested = if area < native_limit {
        1
    } else {
        ((area / budget).sqrt().ceil() as i32).max(1)
    };
    let boundary = if requested == 1 {
        native_limit
    } else {
        budget * f64::from(requested).powi(2)
    };
    if requested == previous - 1 && area > 0.85 * boundary {
        previous
    } else {
        requested
    }
}

fn cache_direct(reduced: bool, distance: f64, width: i32, height: i32) -> bool {
    (reduced || distance <= 70.0) && i64::from(width) * i64::from(height) <= 4_200_000
}

fn reuse(age_nanos: i64, frames: i32) -> bool {
    (0..=56_000_000).contains(&age_nanos) && frames < 3
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_planCache(
    _env: *mut c_void,
    _class: *mut c_void,
    reduced: u8,
    distance: f64,
    width: i32,
    height: i32,
) -> u8 {
    u8::from(cache_direct(reduced != 0, distance, width, height))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_planReuse(
    _env: *mut c_void,
    _class: *mut c_void,
    age_nanos: i64,
    frames: i32,
) -> u8 {
    u8::from(reuse(age_nanos, frames))
}

fn particle_schedule(seconds: f64, budget: i32) -> (i64, u32, f32) {
    let tick = ((seconds * 30.0).floor() as i64).max(0);
    let count = budget.clamp(1, 32_768) as u32;
    (tick, count, (8192.0 / count as f32).clamp(0.25, 8.0))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_abi(
    _env: *mut c_void,
    _class: *mut c_void,
) -> i32 {
    2
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_planDirect(
    _env: *mut c_void,
    _class: *mut c_void,
    adaptive: u8,
    area: f64,
    previous: i32,
) -> i32 {
    direct_divisor(adaptive != 0, area, previous)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_particleTick(
    _env: *mut c_void,
    _class: *mut c_void,
    seconds: f64,
) -> i64 {
    particle_schedule(seconds, 1).0
}

/// # Safety
/// Call only with the current render thread's GL 4.3 function table and valid
/// programs, binding names and locations owned by the caller's current context.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_dispatch(
    _env: *mut c_void,
    _class: *mut c_void,
    bind_image: i64,
    use_program: i64,
    uniform_i: i64,
    dispatch: i64,
    barrier: i64,
    bind_buffer: i64,
    uniform_2ui: i64,
    uniform_ui: i64,
    uniform_4f: i64,
    atlas: i32,
    particle: i32,
    accumulator: i32,
    density: i32,
    buffer: i32,
    normalize_loc: i32,
    seed_loc: i32,
    tick_loc: i32,
    count_loc: i32,
    flow_loc: i32,
    seed: i64,
    tick: i64,
    budget: i32,
    beta: f32,
    jets: f32,
    horizon: f32,
) {
    let (_, count, normalization) = particle_schedule(0.0, budget);
    // LWJGL provides addresses from the very same current context. GL APIENTRY
    // uses the system ABI and GLboolean is an unsigned byte, not a Rust bool.
    unsafe {
        let image: unsafe extern "system" fn(u32, u32, i32, u8, i32, u32, u32) =
            transmute(bind_image as usize);
        let program: unsafe extern "system" fn(u32) = transmute(use_program as usize);
        let int: unsafe extern "system" fn(i32, i32) = transmute(uniform_i as usize);
        let compute: unsafe extern "system" fn(u32, u32, u32) = transmute(dispatch as usize);
        let sync: unsafe extern "system" fn(u32) = transmute(barrier as usize);
        let storage: unsafe extern "system" fn(u32, u32, u32) = transmute(bind_buffer as usize);
        let pair: unsafe extern "system" fn(i32, u32, u32) = transmute(uniform_2ui as usize);
        let uint: unsafe extern "system" fn(i32, u32) = transmute(uniform_ui as usize);
        let flow: unsafe extern "system" fn(i32, f32, f32, f32, f32) =
            transmute(uniform_4f as usize);

        image(0, accumulator as u32, 0, 0, 0, READ_WRITE, R32UI);
        image(1, density as u32, 0, 0, 0, WRITE_ONLY, R16F);
        program(atlas as u32);
        int(normalize_loc, 0);
        compute(ATLAS_GROUPS, ATLAS_GROUPS, 1);
        sync(IMAGE);

        program(particle as u32);
        storage(STORAGE_BUFFER, 0, buffer as u32);
        pair(seed_loc, seed as u32, ((seed as u64) >> 32) as u32);
        uint(tick_loc, tick as u32);
        uint(count_loc, count);
        flow(flow_loc, beta, jets, horizon, normalization);
        compute(count.div_ceil(128), 1, 1);
        sync(IMAGE | STORAGE);

        program(atlas as u32);
        int(normalize_loc, 1);
        compute(ATLAS_GROUPS, ATLAS_GROUPS, 1);
        sync(IMAGE | TEXTURE_FETCH);
    }
}

/// # Safety
/// Called on the render thread with a valid OpenGL glDrawArrays address and
/// the bound white-hole fullscreen position buffer and shader.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_draw(
    _env: *mut c_void,
    _class: *mut c_void,
    draw_arrays: i64,
) {
    unsafe {
        let draw: unsafe extern "system" fn(u32, i32, i32) = transmute(draw_arrays as usize);
        draw(0x0004, 0, 3); // GL_TRIANGLES
    }
}

// Each pixel gets a float index (four bytes). Queue+result reserve only
// one slot per sixteen pixels; overflow pixels stay on the exact inline path.
fn sparse_capacity(width: i32, height: i32) -> i32 {
    let area = i64::from(width) * i64::from(height);
    if width <= 0 || height <= 0 || !(480_000..=4_200_000).contains(&area) {
        return 0;
    }
    ((area + 16_383) / 16_384 * 1024) as i32
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_planSparse(
    _env: *mut c_void,
    _class: *mut c_void,
    width: i32,
    height: i32,
) -> i32 {
    sparse_capacity(width, height)
}

/// # Safety
/// Addresses come from the current OpenGL 4.3 context. The caller owns the
/// linked programs, already reset/bound job buffer, images, and GL-state restore.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_dishanhai_gt_1shanhai_client_renderer_blackhole_RustWhiteHoleRenderer_dispatchSparse(
    _env: *mut c_void,
    _class: *mut c_void,
    use_program: i64,
    bind_image: i64,
    dispatch: i64,
    indirect: i64,
    barrier: i64,
    classify: i32,
    repair: i32,
    map: i32,
    light: i32,
    width: i32,
    height: i32,
) {
    const COMMAND: u32 = 0x0040;
    const BUFFER_UPDATE: u32 = 0x0200;
    const R32F: u32 = 0x822e;
    const RGBA32F: u32 = 0x8814;
    unsafe {
        let program: unsafe extern "system" fn(u32) = transmute(use_program as usize);
        let image: unsafe extern "system" fn(u32, u32, i32, u8, i32, u32, u32) =
            transmute(bind_image as usize);
        let groups: unsafe extern "system" fn(u32, u32, u32) = transmute(dispatch as usize);
        let queued: unsafe extern "system" fn(isize) = transmute(indirect as usize);
        let sync: unsafe extern "system" fn(u32) = transmute(barrier as usize);
        program(classify as u32);
        image(0, map as u32, 0, 0, 0, WRITE_ONLY, R32F);
        groups((width as u32).div_ceil(8), (height as u32).div_ceil(8), 1);
        sync(COMMAND | STORAGE);
        program(repair as u32);
        image(0, light as u32, 0, 0, 0, WRITE_ONLY, RGBA32F);
        queued(4); // The indirect command begins after the leading job counter.
        sync(IMAGE | TEXTURE_FETCH | STORAGE | BUFFER_UPDATE);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sparse_queue_budget_is_bounded_and_row_aligned() {
        assert_eq!(sparse_capacity(2560, 1600), 256_000);
        assert_eq!(sparse_capacity(3840, 2160), 0);
        assert_eq!(sparse_capacity(800, 560), 0);
        assert_eq!(sparse_capacity(-800, -800), 0);
        assert_eq!(sparse_capacity(1001, 540) % 1024, 0);
    }

    #[test]
    fn direct_resolution_grows_immediately_and_releases_with_hysteresis() {
        assert_eq!(direct_divisor(true, 479_999.0, 1), 1);
        assert_eq!(direct_divisor(true, 480_000.0, 1), 2);
        assert_eq!(direct_divisor(true, 450_000.0, 2), 2);
        assert_eq!(direct_divisor(true, 400_000.0, 2), 1);
        assert_eq!(direct_divisor(true, 400_000.0, 4), 1);
        assert_eq!(direct_divisor(false, 2_100_000.0, 1), 1);
        assert_eq!(direct_divisor(false, 2_100_001.0, 1), 2);
    }

    #[test]
    fn bounds_cache_footprint_and_history_age() {
        assert!(cache_direct(false, 70.0, 2100, 2000));
        assert!(!cache_direct(false, 70.1, 2100, 2000));
        assert!(cache_direct(true, 100.0, 2100, 2000));
        assert!(!cache_direct(true, 100.0, 2101, 2000));
        assert!(reuse(56_000_000, 2));
        assert!(!reuse(56_000_001, 2));
        assert!(!reuse(0, 3));
        assert!(!reuse(-1, 0));
    }

    #[test]
    fn particles_are_deterministic_and_clamp_at_limits() {
        assert_eq!(particle_schedule(-1.0, 1), (0, 1, 8.0));
        assert_eq!(particle_schedule(4.25, 8192), (127, 8192, 1.0));
        assert_eq!(particle_schedule(4.259, 8192), (127, 8192, 1.0));
        assert_eq!(particle_schedule(4.27, 65_536), (128, 32_768, 0.25));
    }
}
