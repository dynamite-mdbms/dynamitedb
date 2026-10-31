package dynamitedb.qo.parser;

public record Token(TokenKind kind, String lexeme, int offset) {}
