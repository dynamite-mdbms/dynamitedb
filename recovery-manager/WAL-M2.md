# FRM WAL Milestone 2 (Bryan)

`LogManager` is the single writer and LSN allocator for transaction events, changes, compensation records, and checkpoint markers. `WalService` exposes the existing `TransactionWal`, `WalDurability`, and `LogInspection` contracts. The Milestone 1 mock constructors remain available for isolated collaborators' tests.

## Open and close

For a new database, create a WAL explicitly with `LogManager.createNew(root, lineage, highWater)`. For an existing database, read its persisted active lineage and baseline high-water from the generation manifest, then call `LogManager.openExisting(root, lineage, highWater)`. A missing or corrupt existing WAL fails; it is never replaced with an empty file. Own the returned manager with try-with-resources and inject it into `new WalService(new CommitClock(), log)`.

The `root` directory holds `wal-<lineage UUID>.bin`. The caller must persist and recover the active lineage and baseline high-water outside the WAL file. `openExisting` validates and replays complete frames, rebuilds the transaction index, and truncates only an incomplete final frame. A complete bad frame fails with `CORRUPT_LOG` without repair. One writer holds an OS file lock. Retained lineages are opened read-only by `scanHistory` after the catalog calls `registerHistory(id, floor)`.

## Write contract

1. `begin(tx, timestamp)` appends BEGIN. Transaction IDs and timestamps must exceed the manifest baseline; each ID may begin once in the active WAL.
2. After SM reserves a stable RID and supplies before/after images, `appendChange(change)` appends one full change and returns its assigned LSN. The caller applies the mutation only after this returns. SM calls `ensureDurable(pageLsn)` before flushing a page to disk.
3. `commitDurably(tx)` appends COMMIT and forces the entire WAL prefix before reporting success. Repeating it for an already committed transaction only checks durability. A write or force error propagates as `WAL_FAILURE`; the caller must not report commit success.
4. The undo owner uses `reverseByTx(tx)`, `appendCompensation(tx, originalLsn, undoNext, mutation)`, and `finishAbort(tx)`. ABORT_COMPLETE is forced and is rejected while any logged change lacks a CLR. The checkpoint owner uses `appendCheckpoint(id, BEGIN/END, throughLsn)` and `flush(lsn)`; both share the same LSN allocator.
5. `readLog(filter)` reads the in-memory index rebuilt from the WAL. `fromLsn` is inclusive and a transaction filter omits checkpoint markers. Inspection does not change the WAL.

The binary format has a versioned file header containing the lineage and baseline LSN. Each record is framed with magic, payload length, header checksum, payload, and payload CRC32. Record types cover changes, BEGIN/COMMIT/ABORT_COMPLETE, CLRs, and checkpoint BEGIN/END. Images preserve the `exists` flag and byte content; an absent record is distinct from a present record whose encoded data represents SQL NULL.

## Lineage handoff

`prepareLineage` creates a new WAL with the supplied high-water floor while leaving the current WAL active. After Azam's manifest publication and transaction maintenance coordination, `activate(manifest)` switches the handle; the old lineage remains available for registered historical reads. On process restart, open the lineage named by the persisted manifest directly. The catalog must register retained history and supply the high-water covering it; `LogManager` does not publish a generation manifest.

The facade/runtime wiring, SM page flush gate, CCM change bridge, checkpoint scheduler, rollback algorithm, and crash/PITR algorithms belong to their respective owners. Their M1 mock paths do not become persistent merely by injecting this WAL. In particular, the runtime must replace the mock `RecoveryTestDriver` before claiming end-to-end persistence.

## Verification

Run `./gradlew --offline --no-daemon spotlessCheck build` from the repository root. `DurableWalTest` covers reopen, abrupt child-process exit, all three change kinds, filtering, concurrent writers, CLR/checkpoint encoding, lineage switch, torn tails, corruption, and writer exclusion.
