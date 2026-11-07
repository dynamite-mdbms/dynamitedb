# Initial shared-contract ownership

Each file under `contracts/src/main/java/dynamitedb/contracts` has one initial role
owner below. The owner settles the type's behavior and keeps its contract tests
current. Other roles import the same canonical type; they do not create a second
placeholder type. A provider that is still unfinished can be replaced by a test
fake implementing the canonical interface. Changes affecting another component
still need that component owner's agreement before merging.

`CCM1` through `CCM5` correspond to `CC1` through `CC5` in the component role
mapping; `FRM1` through `FRM5` correspond to `FR1` through `FR5`. The assignment
is an initial coordination decision, not a transfer of component requirements.

| Package | Initial role | Shared types (Java filenames without `.java`) |
| --- | --- | --- |
| `api` | QP1 | `QueryProcessor` |
| `api` | QO1 | `QueryOptimizer` |
| `api` | SM1 | `HistoricalRecoveryStorage`, `RecoveryPageStore`, `RowCursor`, `StorageGenerationAdmin`, `TupleReader`, `WriteStore` |
| `api` | SM4 | `CatalogAccess` |
| `api` | SM5 | `IndexAdmin` |
| `api` | CCM1 | `ProtocolAdmin`, `TransactionManager` |
| `api` | CCM2 | `AccessController` |
| `api` | CCM3 | `ChangeLogger`, `MaintenanceGuard`, `RecoveryCoordinator` |
| `api` | FRM2 | `LogInspection`, `TransactionWal`, `WalDurability` |
| `api` | FRM3 | `RollbackService` |
| `api` | FRM4 | `CheckpointService`, `GenerationManifestStore`, `HistoricalRecoveryService` |
| `api` | FRM5 | `CrashRecovery` |
| `error` | QP1 | `DbmsException`, `ErrorCode` |
| `query` | QP1 | `QueryResult`, `SessionId` |
| `query` | QP2 | `OperatorRow` |
| `query` | QP3 | `DeletePlan` |
| `query` | QP4 | `SortDirection`, `SortKey`, `SortPlan` |
| `query` | QP5 | `CreateIndexPlan`, `InsertPlan`, `TransactionCommand`, `TransactionCommandPlan`, `UpdatePlan` |
| `query` | QO1 | `PlanExplanation`, `PlanNode`, `PreparedQuery`, `TableScanPlan` |
| `query` | QO2 | `BinaryExpression`, `BinaryOp`, `ColumnExpression`, `ColumnRef`, `CreateIndexStatement`, `DeleteStatement`, `Expression`, `FromSource`, `InsertStatement`, `JoinKind`, `JoinSource`, `LiteralExpression`, `SelectStatement`, `Statement`, `TableSource`, `TransactionStatement`, `UpdateStatement` |
| `query` | QO4 | `FilterPlan`, `ProjectPlan` |
| `query` | QO5 | `IndexScanPlan`, `JoinPlan` |
| `recovery` | FRM2 | `ChangeRecord`, `LogFilter`, `LogSequenceNumber`, `LogView` |
| `recovery` | FRM4 | `CheckpointResult`, `GenerationManifest`, `RecoveryBoundary`, `RecoveryStage`, `RecoveryTarget`, `SealedGeneration`, `SnapshotMetadata` |
| `recovery` | FRM5 | `RecoveryHighWater`, `RecoveryMutation`, `RecoveryResult`, `RecoveryStatus` |
| `storage` | QP2 | `BooleanValue` |
| `storage` | SM1 | `PreparedWrite`, `RecordId`, `RecordImage`, `StoredRow`, `WriteReservationId` |
| `storage` | SM3 | `DataType`, `FloatValue`, `ImmutableBytes`, `IntValue`, `NullValue`, `Row`, `TextValue`, `Value` |
| `storage` | SM4 | `Column`, `Schema`, `TableStatistics` |
| `storage` | SM5 | `ComparisonOp`, `IndexKind`, `IndexMetadata`, `IndexPredicate` |
| `testing` | QP1 | `TestReport`, `TestResult` |
| `transaction` | CCM1 | `ProtocolType`, `TransactionId`, `TransactionStatus` |
| `transaction` | CCM2 | `AccessDecision`, `AccessMode`, `AccessRequest`, `AccessState`, `ResourceId`, `ResourceKind`, `WaitToken` |

The main handoffs are query plan types between QO and QP, SM4's catalog types to QO3,
SM1's row and write interfaces to QP3/QP5, CCM2's access types to QP5, and
FRM2's WAL types to CCM3 and SM2. The owner and consumers should agree on
semantics before changing a type used across those handoffs.
