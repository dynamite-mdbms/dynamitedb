package dynamitedb.contracts.query;

import java.util.Optional;

/**
 * {@code operand [NOT] IN (SELECT kolom ...)} untuk subquery tak berkorelasi di WHERE.
 *
 * <p>Parser dan binder cuma mengisi {@code query}; {@code plan} diisi PlanBuilder supaya QP bisa
 * menjalankan subquery sekali lalu mengecek keanggotaan per baris.
 */
public record InSubqueryExpression(
    Expression operand, SelectStatement query, Optional<PlanNode> plan, boolean negated)
    implements Expression {}
