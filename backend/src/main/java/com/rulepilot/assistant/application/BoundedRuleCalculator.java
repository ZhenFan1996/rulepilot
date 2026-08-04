package com.rulepilot.assistant.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Evaluates a deliberately small arithmetic language without code execution or ambient state. */
final class BoundedRuleCalculator {

    private static final int MAX_EXPRESSION_LENGTH = 160;
    private static final int MAX_TOKENS = 64;
    private static final int MAX_DEPTH = 8;
    private static final BigDecimal MAX_ABSOLUTE_VALUE = new BigDecimal("1000000000000");
    private static final MathContext MATH = MathContext.DECIMAL64;

    Evaluation evaluate(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > MAX_EXPRESSION_LENGTH) {
            throw invalid();
        }
        Parser parser = new Parser(expression.strip());
        BigDecimal value = parser.parse();
        bounded(value);
        return new Evaluation(
                expression.strip(), printable(value), List.copyOf(parser.literals));
    }

    private static String printable(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }

    private static BigDecimal bounded(BigDecimal value) {
        if (value == null || value.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("rule calculation expression is invalid");
    }

    record Evaluation(String expression, String result, List<BigDecimal> literals) {}

    private static final class Parser {
        private final String source;
        private final List<BigDecimal> literals = new ArrayList<>();
        private int offset;
        private int tokens;

        private Parser(String source) {
            this.source = source;
        }

        private BigDecimal parse() {
            BigDecimal value = expression(0);
            whitespace();
            if (offset != source.length()) throw invalid();
            return value;
        }

        private BigDecimal expression(int depth) {
            checkDepth(depth);
            BigDecimal value = term(depth + 1);
            while (true) {
                if (take('+')) value = bounded(value.add(term(depth + 1), MATH));
                else if (take('-')) value = bounded(value.subtract(term(depth + 1), MATH));
                else return value;
            }
        }

        private BigDecimal term(int depth) {
            checkDepth(depth);
            BigDecimal value = unary(depth + 1);
            while (true) {
                if (take('*')) value = bounded(value.multiply(unary(depth + 1), MATH));
                else if (take('/')) {
                    BigDecimal divisor = unary(depth + 1);
                    if (divisor.compareTo(BigDecimal.ZERO) == 0) throw invalid();
                    value = bounded(value.divide(divisor, MATH));
                } else return value;
            }
        }

        private BigDecimal unary(int depth) {
            checkDepth(depth);
            if (take('+')) return unary(depth + 1);
            if (take('-')) return bounded(unary(depth + 1).negate(MATH));
            return primary(depth + 1);
        }

        private BigDecimal primary(int depth) {
            checkDepth(depth);
            whitespace();
            if (take('(')) {
                BigDecimal value = expression(depth + 1);
                if (!take(')')) throw invalid();
                return value;
            }
            if (offset < source.length() && Character.isLetter(source.charAt(offset))) {
                return function(depth + 1);
            }
            return number();
        }

        private BigDecimal function(int depth) {
            int start = offset;
            while (offset < source.length() && Character.isLetter(source.charAt(offset))) offset++;
            token();
            String name = source.substring(start, offset).toLowerCase(Locale.ROOT);
            if (!take('(')) throw invalid();
            BigDecimal first = expression(depth + 1);
            BigDecimal second = null;
            if (take(',')) second = expression(depth + 1);
            if (!take(')')) throw invalid();
            return switch (name) {
                case "floor" -> requireUnary(second, first.setScale(0, RoundingMode.FLOOR));
                case "ceil" -> requireUnary(second, first.setScale(0, RoundingMode.CEILING));
                case "min" -> binary(first, second, true);
                case "max" -> binary(first, second, false);
                default -> throw invalid();
            };
        }

        private BigDecimal requireUnary(BigDecimal second, BigDecimal result) {
            if (second != null) throw invalid();
            return bounded(result);
        }

        private BigDecimal binary(BigDecimal first, BigDecimal second, boolean minimum) {
            if (second == null) throw invalid();
            return bounded(minimum ? first.min(second) : first.max(second));
        }

        private BigDecimal number() {
            whitespace();
            int start = offset;
            boolean dot = false;
            while (offset < source.length()) {
                char character = source.charAt(offset);
                if (Character.isDigit(character)) {
                    offset++;
                } else if (character == '.' && !dot) {
                    dot = true;
                    offset++;
                } else break;
            }
            if (start == offset || source.substring(start, offset).equals(".")) throw invalid();
            token();
            try {
                BigDecimal value = bounded(new BigDecimal(source.substring(start, offset), MATH));
                literals.add(value.stripTrailingZeros());
                return value;
            } catch (NumberFormatException failure) {
                throw invalid();
            }
        }

        private boolean take(char expected) {
            whitespace();
            if (offset >= source.length() || source.charAt(offset) != expected) return false;
            offset++;
            token();
            return true;
        }

        private void whitespace() {
            while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) offset++;
        }

        private void token() {
            if (++tokens > MAX_TOKENS) throw invalid();
        }

        private void checkDepth(int depth) {
            if (depth > MAX_DEPTH) throw invalid();
        }
    }
}
