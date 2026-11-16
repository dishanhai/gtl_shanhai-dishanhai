package com.dishanhai.gt_shanhai.common.patternmover;
/** Server-side session identity; never substitutes an equal-looking tool. */
final class HostPatternMoverBinding {
 private final Object player, world, hand, stack;
 private final int slot;
 private boolean invalid;
 HostPatternMoverBinding(Object player, Object world, Object hand, int slot, Object stack) {
  this.player=player; this.world=world; this.hand=hand; this.slot=slot; this.stack=stack;
 }
 boolean matches(Object player, Object world, Object hand, int slot, Object stack) {
  invalid |= this.player!=player || this.world!=world || this.hand!=hand || this.slot!=slot || this.stack!=stack;
  return !invalid;
 }
}
