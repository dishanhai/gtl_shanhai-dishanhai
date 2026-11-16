package com.dishanhai.gt_shanhai.common.patternmover.compat;
/** Only the plain-label sanitizer needed by the mover. */
public final class ShanhaiTextParser {
 private ShanhaiTextParser() {}
 public static String stripStyleCode(String raw) {
  if(raw == null) return "";
  if(!raw.startsWith("&$")) return raw;
  int dash=raw.indexOf('-');
  if(dash<0 || dash+1>=raw.length()) return raw;
  return raw.substring(dash+1).replace("&r", "");
 }
}
