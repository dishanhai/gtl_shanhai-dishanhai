// Analytic display of the exterior. All emission is evaluated at the present
// fragment. The narrow cores integrate pixel coverage, independently of frame history.
float exteriorBand(float offset, float halfWidth, float footprint) {
    return clamp(.5 + (offset + halfWidth) / footprint, 0.0, 1.0)
         - clamp(.5 + (offset - halfWidth) / footprint, 0.0, 1.0);
}

vec4 exteriorGlint(vec2 px) {
    float d = length(px - uExterior.yz);
    float pixel = max(fwidth(d), 1.0);
    float shadow = clamp(.5 + (.83 - d) / pixel, 0.0, 1.0);
    float ring = exteriorBand(d - 1.24, .34, pixel);
    float power = clamp(uDiskGain * 2.0 * uDiskPhysics.x * uDiskPhysics.w, 0.0, 1.0);
    return vec4(vec3(1.0, .65, .31) * ring * .85 * power, clamp(shadow + .65 * ring * power, 0.0, 1.0));
}

vec4 drawKerrExterior(vec2 px) {
    if (uShadowPixels <= 1.1) return exteriorGlint(px);
    mat3 basis;
    vec3 observer, direction;
    cameraRay(px, basis, observer, direction);
    float distance = length(observer);
    vec3 sight = observer / distance;
    float forward = -dot(direction, sight);
    if (forward <= .25) return vec4(0.0);
    // Intersect the viewing ray with the plane through the hole perpendicular
    // to the sight line. The cross products avoid cancellation for distant views.
    vec3 impact = cross(sight, cross(direction, sight)) * (distance / (5.1961524 * forward));
    if (dot(impact, impact) > 16.0) return vec4(0.0);
    float inclination = length(sight.xy);
    vec3 major = inclination > 1e-5 ? vec3(-sight.y, sight.x, 0.0) / inclination : vec3(1.0, 0.0, 0.0);
    vec3 minor = cross(sight, major);
    vec2 p = vec2(dot(impact, major), dot(impact, minor));
    float b = length(p);

    float margin = shadowMargin(observer, direction, vec3(0.0));
    vec2 slope = vec2(dFdx(margin), dFdy(margin));
    float grad = length(slope);
    float rimPx = margin / max(grad, 1e-8);
    float visible = shadowVisibility(rimPx, slope / max(grad, 1e-8));

    // The primary image is a projected annulus. The compressed side remains a
    // resolved luminous ribbon as the observer approaches the disk plane.
    float thickness = uDiskPhysics.y;
    float flatten = max(length(vec2(sight.z, thickness * 1.55)), .012);
    vec2 q = vec2(p.x, p.y / flatten);
    float r = length(q);
    float radialPixel = clamp(fwidth(r), .0001, .85);
    float drift = .0040267 * uTime.x + .25770877 * mod(uTime.y, 1024.0);
    vec2 diskPosition = q.x * major.xy + q.y * clamp(sight.z / max(thickness, .012), -1.0, 1.0)
                      * vec2(-major.y, major.x);
    float angle = dot(diskPosition, diskPosition) > 1e-5 ? atan(diskPosition.y, diskPosition.x) : 0.0;
    float turn = angle - ROTATION * drift;
    float warp = sin(2.0 * turn + 2.5 * r) * .40
               + sin(3.0 * turn - 1.8 * r) * .19;
    float radialPhase = 32.0 * log(max(r, .7)) + 2.0 * warp;
    float phase = radialPhase + 3.0 * turn;
    vec2 turnStep = vec2(dFdx(angle), dFdy(angle));
    turnStep = mod(turnStep + 3.14159265, 6.28318530) - 3.14159265;
    vec2 phaseStep = vec2(dFdx(radialPhase), dFdy(radialPhase)) + 3.0 * turnStep;
    float phasePixel = dot(abs(phaseStep), vec2(1.0));
    float detail = 1.0 - smoothstep(.75, 3.14, phasePixel);
    float narrowDetail = 1.0 - smoothstep(.35, 1.22, phasePixel);
    float filament = .54 + .22 * detail * cos(phase)
                   + .25 * mix(.28, smoothstep(.40, .84, cos(1.0 + 2.0 * phase)), narrowDetail);
    float inner = clamp((ISCO + 1.35) / 5.1961524, .90, 1.37);
    float diskMask = clamp(.5 + (r - inner) / radialPixel, 0.0, 1.0)
                   * (1.0 - smoothstep(2.45, 3.43, r));
    float nearSide = 1.0 - smoothstep(-.20, .20, q.y * sight.z / (abs(sight.z) + thickness));
    float beam = clamp(1.0 + .48 * ROTATION * inclination * q.x / max(r, .01), .50, 1.5);
    float temperature = clamp(log2(uDiskPhysics.z / 5800.0) * .37 + .40 + .17 * uDiskHeat, 0.0, 1.0);
    vec3 amber = vec3(1.0, .12, .025);
    vec3 ivory = vec3(1.0, .72, .35);
    vec3 diskTint = mix(amber, ivory, clamp(temperature + .32 * (beam - 1.0) - .24 * (r - 1.0), 0.0, 1.0));
    float disk = diskMask * (.29 + filament) * beam * mix(.37, 1.0, nearSide) / max(r * r, 1.0);

    // Use nested conics for the broad lensed disk. Spin translates and
    // compresses the conics; the exact critical curve clips their inner edge.
    // The radial potential itself is only a local distance near that curve.
    float radialScale = max(uShadowPixels, 1.0);
    float spinProjection = SPIN * inclination;
    float apparentScale = (1.0 + 2.15 / distance) * (1.0 - .045 * SPIN * SPIN);
    vec2 lensed = (p - vec2(.40 * spinProjection * (1.0 + 2.3 / distance), 0.0))
                / (apparentScale * vec2(1.0 - .10 * spinProjection * spinProjection, 1.0));
    float lensedRadius = length(lensed);
    float arcDistance = lensedRadius - .985;
    float latitude = lensed.y / max(lensedRadius, .001);
    float upper = clamp(.5 + .68 * latitude * sight.z / (abs(sight.z) + .055), 0.0, 1.0);
    float opening = inclination * abs(latitude);
    // Widen the lensed disk where it meets the equatorial image while
    // keeping its inner edge anchored near the silhouette.
    float hemisphere = lensed.y * sight.z / (abs(sight.z) + .045);
    float fold = smoothstep(-.11, .18, hemisphere) * (1.0 - smoothstep(.20, 1.26, abs(lensed.y)));
    float width = mix(.20, mix(.47, 1.24, upper), smoothstep(.04, .98, opening))
                + 1.02 * fold * smoothstep(.65, .99, inclination);
    float arcCoordinate = arcDistance / width;
    float flow = sin(5.5 * lensed.x + 1.7 * latitude - ROTATION * drift)
               * sin(3.2 * lensed.x - 4.0 * latitude + ROTATION * drift);
    float arcEnvelope = smoothstep(.025, .15, arcCoordinate) * (1.0 - smoothstep(.49, .69, arcCoordinate));
    float arcLane = arcCoordinate + flow * .019;
    float arcFootprint = max(fwidth(arcLane), .0001);
    float arc = (.32 + .12 * flow) * arcEnvelope
              + .66 * exteriorBand(arcLane - .155, .052, arcFootprint)
              + .52 * exteriorBand(arcLane - .155, .023, arcFootprint)
              + .56 * exteriorBand(arcLane - .250, .031, arcFootprint)
              + .47 * exteriorBand(arcLane - .343, .022, arcFootprint)
              + .38 * exteriorBand(arcLane - .427, .018, arcFootprint)
              + .21 * exteriorBand(arcLane - .504, .013, arcFootprint);
    float ring = exteriorBand(rimPx - max(.78, .016 * radialScale),
                max(.37, .008 * radialScale), max(1.0, (abs(slope.x) + abs(slope.y)) / max(grad, 1e-8)));
    float orbitalSide = clamp(1.0 + .55 * ROTATION * inclination * p.x / max(b, .01), .45, 1.55);
    vec3 arcTint = mix(amber, ivory, clamp(temperature - .26 * arcCoordinate + .27 * (orbitalSide - 1.0), 0.0, 1.0));
    float power = min(9.0, 6.0 * uDiskGain * uDiskPhysics.w * sqrt(uDiskPhysics.x) * (1.0 + .27 * uDiskHeat));
    // Broad light belongs to a separate, faint outer corona. It never
    // passes through the capture mask or softens the luminous cores.
    float corona = smoothstep(0.0, .11, arcDistance) * (1.0 - smoothstep(.2, .87, arcDistance));
    float frontDisk = disk * nearSide;
    float backDisk = disk - frontDisk;
    float frontTransmission = 1.0 - clamp(power * frontDisk * .88, 0.0, .88);
    float arcLight = orbitalSide * (.43 * arc + .62 * ring + .035 * min(uDisplay.y, 1.25) * corona) * frontTransmission;
    vec3 emission = power * (diskTint * (frontDisk + backDisk * visible) + arcTint * arcLight * visible);
    float opacity = clamp(power * (.79 * (frontDisk + backDisk * visible) + 1.19 * arcLight * visible), 0.0, .98);
    float through = visible * (1.0 - opacity);

    // Keep donor coordinates at native resolution. The finite-distance depth
    // test and the existing foreground composition still apply.
    float warpAngle = .18 * (1.0 - smoothstep(1.0, 3.3, b)) / max(b, 1.0);
    vec3 outgoing = basis * normalize(direction - impact * warpAngle / max(b, .01));
    vec3 viewing = basis * direction;
    float skyCoverage;
    float edge = traceCoverage(px);
    vec3 incoming = background(px, viewing, vec4(outgoing, 1.0), edge, edge, true, false, 1.0, skyCoverage);
    vec3 source = toneMap(uDisplay.x * emission);
    vec3 scene = displayEncode(clamp(incoming * through + source, 0.0, 1.0));
    float field = 1.0 - smoothstep(3.5, 4.0, b);
    vec4 exterior = vec4(mix(displayEncode(source), scene, skyCoverage),
                         1.0 - through * (1.0 - skyCoverage)) * field;
    if (uShadowPixels >= 2.75) return exterior;
    return mix(exteriorGlint(px), exterior, smoothstep(1.1, 2.75, uShadowPixels));
}
