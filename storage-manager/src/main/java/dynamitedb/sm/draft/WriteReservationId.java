package dynamitedb.sm.draft;

import java.util.UUID;

/** Temporary contract: Opaque identity of one unapplied storage reservation. */
public record WriteReservationId(UUID value) {}
