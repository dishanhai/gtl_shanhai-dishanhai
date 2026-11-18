# Shop crafting: stock changes and confirmation

## Contract

The shop preview is a calculation, **not a physical stock lock**. Confirming a
shop plan requests AE crafting; it does not buy goods or debit the shop wallet.
Each plan ID is consumed before validation/submission. A stale plan or a failed
submission never automatically starts a new calculation or resubmits a task.

On confirmation, only the targets actually eligible for submission must still
have the approved missing amount. A changed target amount stops the request and
asks the player to calculate again. This prevents silent order enlargement,
shrinking, or duplicate crafting when another order has already covered a gap.
Unrelated unavailable targets do not invalidate an otherwise valid subset.

Completed CPU output can move from `pending` to `stock` while leaving the missing
amount unchanged. This no longer invalidates the plan merely because `retained`
changed. Preflight uses **current retained shop stock plus the sum of all planned
consumable inputs**, not stale preview reservations. Shared material and very
large quantities are aggregated as BigInteger. Only the needed bounded quantity
is simulated during this preflight. Native AE submission remains authoritative;
a failed or partially successful multi-job batch is not an atomic transaction.
Successful tasks are reported and never replayed automatically.

## Planning reads and performance

The server thread captures extractable stock once per shop calculation session,
querying each positive cached key once with its cached count as the upper bound.
All sequential target calculations share this frozen snapshot, each with its own
retained/prior-plan reservation subtraction. The shop mixin intercepts exact and
fuzzy parent reads at HEAD, including GTLCore's lazy overrides; those shop reads
no longer query live ME storage on AE's worker thread. Non-shop calculations are
unchanged. GTLCore diagnostic fingerprint handling is not changed.

The snapshot is dropped from the session when calculation finishes/cancels. Each
finished plan caches its consumable-input map so redisplaying a preview does not
walk the pattern tree again. Membership discount is read once per snapshot.

This trades a bounded upfront whole-network scan for removing repeated live
lookups and inconsistent reads during search. It is not a claim of universal TPS
improvement: a very large network and a tiny order can pay more startup work than
GTLCore's lazy path. Runtime latency and hardware performance require a pack test.
The counting regression exercises 256,000 repeated reads over 1,000 keys and
asserts exactly 1,000 backing queries; it is an algorithmic test, not a benchmark.

## Upstream reference

AE2 Forge 15.4.10 (`b4b08d9941e3faecb520d76be617629bb56661e1`):
- [NetworkCraftingSimulationState](https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/b4b08d9941e3faecb520d76be617629bb56661e1/src/main/java/appeng/crafting/inv/NetworkCraftingSimulationState.java): copies inventory before async calculation; explicitly warns against worker-thread world access.
- [CraftingCpuHelper](https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/b4b08d9941e3faecb520d76be617629bb56661e1/src/main/java/appeng/crafting/execution/CraftingCpuHelper.java): actual initial-input extraction occurs during submission; missing material fails and triggers a return attempt, not an unconditional lossless transaction guarantee.
- [CraftConfirmMenu](https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/b4b08d9941e3faecb520d76be617629bb56661e1/src/main/java/appeng/menu/me/crafting/CraftConfirmMenu.java): submission clears automatic-start state and exposes errors.

The bundled GTLCore fix4 is not unmodified AE2. When
`enableAe2ManualCraftingInventoryLock` is enabled, its `CraftConfirmMenuMixin`
acquires `ManualCraftingInventoryLock` reservations for the preview's inputs.
These are global logical counters, not physically extracted items. Its submission
scope bypasses its own reservation, and the menu releases it on close/success/
recalculation. Its graph-mode refresh is bounded. The shop calls `submitJob`
directly and never acquires that menu-owned reservation. This change deliberately
does not transplant a lock without its lifecycle/multi-job cleanup; simulated
reads and native submission continue to respect other reservations. Do not infer
that upstream AE2 locks inventory simply because this modded menu does.

## Runtime acceptance still required

With the actual GTLCore fix4 and AE2 15.4.10 pack:
1. Preview a missing quantity while a standalone CPU is delivering the same item;
   delivered stock changes but total outstanding gap stays fixed. Confirm once.
2. Consume/add stock so the actual target gap changes. Confirm: one clear failure,
   no new dialog or automatic calculation. Repeated old confirmation packets do nothing.
3. Use a target as an ingredient of another target. Current retained stock must
   remain available; competing material demand must fail before any submission.
4. Force no CPU / unsuitable CPU / material loss at native submission; check
   accurate successful-task count and no automatic replay of accepted jobs.
5. Exercise fuzzy/NBT ingredients, fluids, virtual presence-input patterns, cancel,
   logout, disabled shop, timeout, and sequential multi-target plans.
6. Inspect the transformed exact/fuzzy methods with the actual mod list; a pure
   helper/source-contract test is not proof of live Mixin compatibility.
