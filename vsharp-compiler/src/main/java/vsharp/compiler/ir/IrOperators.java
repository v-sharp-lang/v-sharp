package vsharp.compiler.ir;

import java.util.Objects;
import vsharp.compiler.syntax.SyntaxKind;

/// Maps the source operator vocabulary onto the closed IR vocabulary.
final class IrOperators {

    private IrOperators() {}

    static IrUnaryOperator unary(SyntaxKind operator) {
        Objects.requireNonNull(operator, "operator");
        return switch (operator) {
            case PLUS -> IrUnaryOperator.IDENTITY;
            case MINUS -> IrUnaryOperator.NEGATE;
            case EXCLAMATION -> IrUnaryOperator.LOGICAL_NOT;
            case TILDE -> IrUnaryOperator.BITWISE_NOT;
            case CARET -> IrUnaryOperator.INDEX_FROM_END;
            default -> throw unsupported(operator);
        };
    }

    static IrBinaryOperator binary(SyntaxKind operator) {
        Objects.requireNonNull(operator, "operator");
        return switch (operator) {
            case PLUS -> IrBinaryOperator.ADD;
            case MINUS -> IrBinaryOperator.SUBTRACT;
            case ASTERISK -> IrBinaryOperator.MULTIPLY;
            case SLASH -> IrBinaryOperator.DIVIDE;
            case PERCENT -> IrBinaryOperator.REMAINDER;
            case AMPERSAND -> IrBinaryOperator.BITWISE_AND;
            case BAR -> IrBinaryOperator.BITWISE_OR;
            case CARET -> IrBinaryOperator.EXCLUSIVE_OR;
            case LESS_THAN_LESS_THAN -> IrBinaryOperator.SHIFT_LEFT;
            case GREATER_THAN_GREATER_THAN -> IrBinaryOperator.SHIFT_RIGHT;
            case GREATER_THAN_GREATER_THAN_GREATER_THAN -> IrBinaryOperator.UNSIGNED_SHIFT_RIGHT;
            case AMPERSAND_AMPERSAND -> IrBinaryOperator.LOGICAL_AND;
            case BAR_BAR -> IrBinaryOperator.LOGICAL_OR;
            case EQUALS_EQUALS -> IrBinaryOperator.EQUAL;
            case EXCLAMATION_EQUALS -> IrBinaryOperator.NOT_EQUAL;
            case LESS_THAN -> IrBinaryOperator.LESS_THAN;
            case LESS_THAN_EQUALS -> IrBinaryOperator.LESS_THAN_OR_EQUAL;
            case GREATER_THAN -> IrBinaryOperator.GREATER_THAN;
            case GREATER_THAN_EQUALS -> IrBinaryOperator.GREATER_THAN_OR_EQUAL;
            case QUESTION_QUESTION -> IrBinaryOperator.COALESCE;
            default -> throw unsupported(operator);
        };
    }

    static IrBinaryOperator compoundAssignment(SyntaxKind operator) {
        Objects.requireNonNull(operator, "operator");
        return switch (operator) {
            case PLUS_EQUALS -> IrBinaryOperator.ADD;
            case MINUS_EQUALS -> IrBinaryOperator.SUBTRACT;
            case ASTERISK_EQUALS -> IrBinaryOperator.MULTIPLY;
            case SLASH_EQUALS -> IrBinaryOperator.DIVIDE;
            case PERCENT_EQUALS -> IrBinaryOperator.REMAINDER;
            case AMPERSAND_EQUALS -> IrBinaryOperator.BITWISE_AND;
            case BAR_EQUALS -> IrBinaryOperator.BITWISE_OR;
            case CARET_EQUALS -> IrBinaryOperator.EXCLUSIVE_OR;
            case LESS_THAN_LESS_THAN_EQUALS -> IrBinaryOperator.SHIFT_LEFT;
            case GREATER_THAN_GREATER_THAN_EQUALS -> IrBinaryOperator.SHIFT_RIGHT;
            case GREATER_THAN_GREATER_THAN_GREATER_THAN_EQUALS ->
                    IrBinaryOperator.UNSIGNED_SHIFT_RIGHT;
            case QUESTION_QUESTION_EQUALS -> IrBinaryOperator.COALESCE;
            default -> throw unsupported(operator);
        };
    }

    private static IllegalArgumentException unsupported(SyntaxKind operator) {
        return new IllegalArgumentException("operator has no IR mapping: " + operator.display());
    }
}
