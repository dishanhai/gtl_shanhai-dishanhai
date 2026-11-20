#!/usr/bin/env bash
set -euo pipefail

project="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
shared="$(dirname -- "$project")"
ui="$shared/SH-Better-UI"
common="$shared/SH-Common-Lib/forge-1.20.1"

# Use the same pinned local libraries as GTSH. SHBUI treats these as build
# dependencies, not files to bundle into its output.
mkdir -p "$ui/libs"
cp "$project/libs/kotlinforforge-4.12.0-all.jar" \
   "$project/libs/ldlib-forge-1.20.1-1.0.33.b.jar" "$ui/libs/"

gradle --no-daemon --console=plain -p "$ui" devJar
gradle --no-daemon --console=plain -p "$common" build
gradle --no-daemon --console=plain -p "$project" assemble releaseJar

# Select the reobfuscated release, never the raw, dev or sources JAR.
version="$(sed -n 's/^mod_version=//p' "$project/gradle.properties" | tr -d '\r')"
archive="$project/build/libs/gt_shanhai-${version}.jar"
gradle --no-daemon --console=plain -p "$project" verifyModJar "-PshanhaiVerifyJar=$archive"

mkdir -p "$project/build/ci-artifacts"
cp "$archive" "$project/build/ci-artifacts/"
