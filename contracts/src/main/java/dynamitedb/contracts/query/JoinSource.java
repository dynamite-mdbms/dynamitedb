package dynamitedb.contracts.query;

import java.util.Optional;

public record JoinSource(
    FromSource left, FromSource right, JoinKind kind, Optional<Expression> condition)
    implements FromSource {}
