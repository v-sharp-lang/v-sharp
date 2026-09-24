package vsharp.vscode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import vsharp.compiler.syntax.SyntaxFacts;
import vsharp.compiler.syntax.SyntaxKind;

/// Generates the TextMate grammar for `.vs` from the compiler's own token tables.
///
/// The alternative - a hand-written keyword list in the grammar file - rots the first time
/// the language gains a word, and rots silently: the editor simply stops colouring it. Here
/// the words come from [SyntaxKind] and [SyntaxFacts], the same tables the lexer consults,
/// so a new keyword is highlighted by construction.
///
/// TextMate is a regular-expression colouriser, not a parser. It is deliberately the
/// *shallow* half of the editor story: structural knowledge - what a name binds to, whether
/// it exists - comes from the language server. This file only has to be right about
/// lexical shapes, which is exactly what it can be right about.
public final class GrammarGenerator {

    private GrammarGenerator() {
        throw new AssertionError("No instances");
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: GrammarGenerator <output.tmLanguage.json>");
        }
        Path out = Path.of(args[0]);
        String grammar = grammar();
        validate(grammar);
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, grammar, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write grammar to " + out, e);
        }
        System.out.println("Wrote " + out);
    }

    /// Fails the build on a grammar the editor would silently reject.
    ///
    /// A malformed `.tmLanguage.json` does not crash an editor: it loads, colours nothing,
    /// and reports the reason only in a developer console the user never opens. So the two
    /// failure modes are checked here, where they stop a build instead. Every `match`,
    /// `begin` and `end` is compiled as a regular expression - VS Code runs Oniguruma and
    /// this is `java.util.regex`, so the check is a conservative approximation: it catches
    /// unbalanced groups and bad escapes, which is what a generator actually gets wrong.
    private static void validate(String grammar) {
        // A validator that silently matched nothing would pass every grammar, so the count
        // it actually checked is printed and a zero is treated as a defect in the extractor.
        int patternCount = patternsOf(grammar).size();
        if (patternCount == 0) {
            throw new IllegalStateException(
                    "No patterns were found in the generated grammar: the extractor is broken");
        }
        System.out.println("Validated " + patternCount + " grammar patterns");
        int braces = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < grammar.length(); i++) {
            char c = grammar.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                braces++;
            } else if (!inString && c == '}') {
                braces--;
            }
        }
        if (braces != 0 || inString) {
            throw new IllegalStateException(
                    "The generated grammar is not well-formed JSON: brace balance " + braces);
        }
        for (String pattern : patternsOf(grammar)) {
            try {
                java.util.regex.Pattern.compile(pattern);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalStateException(
                        "The generated grammar contains an invalid pattern: " + pattern, e);
            }
        }
    }

    /// The regular expressions of every `match`, `begin` and `end` member, unescaped from
    /// their JSON string form.
    private static List<String> patternsOf(String grammar) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"(?:match|begin|end)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(grammar);
        List<String> patterns = new java.util.ArrayList<>();
        while (matcher.find()) {
            patterns.add(unescapeJson(matcher.group(1)));
        }
        return patterns;
    }

    /// Reverses JSON string escaping in one left-to-right pass.
    ///
    /// Chained `replace` calls cannot do this correctly: rewriting the escaped backslash
    /// first destroys the distinction between it and the backslash that opens the next
    /// escape, and rewriting it last lets an earlier substitution be re-read as an escape.
    private static String unescapeJson(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != '\\' || i + 1 >= literal.length()) {
                out.append(c);
                continue;
            }
            char next = literal.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '/' -> out.append('/');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case 'u' -> {
                    out.append((char) Integer.parseInt(literal.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                // Not a JSON escape, so the backslash belongs to the regex itself.
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }

    /// Reserved words that introduce or continue control flow. Editors theme these
    /// differently from declaration keywords, and users notice when they do not.
    private static final List<String> CONTROL = List.of(
            "break", "case", "catch", "continue", "default", "do", "else", "finally",
            "for", "foreach", "goto", "if", "in", "return", "switch", "throw", "try",
            "while", "yield", "when", "lock", "checked", "unchecked");

    /// Words naming a type directly. These are keywords to the lexer but read as types.
    private static final List<String> BUILTIN_TYPES = List.of(
            "bool", "byte", "char", "decimal", "double", "float", "int", "long", "nint",
            "nuint", "object", "sbyte", "short", "string", "uint", "ulong", "ushort",
            "void", "var", "dynamic");

    /// C# preprocessor directives. V# evaluates these before parsing, so they are lexical.
    private static final List<String> DIRECTIVES = List.of(
            "if", "elif", "else", "endif", "define", "undef", "warning", "error",
            "line", "region", "endregion", "nullable", "pragma");

    static String grammar() {
        List<String> reserved = Stream.of(SyntaxKind.values())
                .filter(SyntaxKind::isKeyword)
                .map(kind -> kind.text().orElse(""))
                .filter(text -> !text.isEmpty())
                .sorted()
                .toList();
        List<String> contextual = SyntaxFacts.contextualKeywords();

        // Literal keywords are values, not operations: `true`, `false` and `null` are
        // themed as constants in every C# grammar and users expect the same here.
        List<String> literals = List.of("true", "false", "null");

        List<String> controlWords = retain(reserved, CONTROL);
        List<String> reservedTypeWords = retain(reserved, BUILTIN_TYPES);
        List<String> contextualTypeWords = retain(contextual, BUILTIN_TYPES);
        List<String> literalWords = retain(reserved, literals);
        List<String> otherWords = remove(reserved,
                union(union(CONTROL, BUILTIN_TYPES), literals));
        List<String> contextualWords = remove(contextual, BUILTIN_TYPES);

        // Every word the lexer knows must be coloured by exactly one rule. Without this
        // check a word added to the language is silently uncoloured, and a word listed in
        // two categories is coloured by whichever rule the grammar happens to try first -
        // both of which look like theme bugs and get reported as such. The generator fails
        // the build instead.
        requirePartition(reserved,
                List.of(controlWords, literalWords, reservedTypeWords, otherWords), "reserved");
        requirePartition(contextual,
                List.of(contextualTypeWords, contextualWords), "contextual");

        return TEMPLATE
                .replace("${CONTROL}", alternation(controlWords))
                .replace("${TYPES}", alternation(union(reservedTypeWords, contextualTypeWords)))
                .replace("${LITERALS}", alternation(literalWords))
                .replace("${KEYWORDS}", alternation(otherWords))
                .replace("${CONTEXTUAL}", alternation(contextualWords))
                .replace("${DIRECTIVES}", alternation(DIRECTIVES.stream().sorted().toList()));
    }

    /// Fails the build unless `categories` is a partition of `words`: every word placed
    /// exactly once, and no category inventing a word the lexer does not produce.
    private static void requirePartition(List<String> words, List<List<String>> categories,
            String label) {
        List<String> placed = categories.stream().flatMap(List::stream).toList();
        List<String> missing = words.stream().filter(word -> !placed.contains(word)).toList();
        List<String> duplicated = words.stream()
                .filter(word -> placed.stream().filter(word::equals).count() > 1)
                .toList();
        List<String> unknown = placed.stream().filter(word -> !words.contains(word)).toList();
        if (!missing.isEmpty() || !duplicated.isEmpty() || !unknown.isEmpty()) {
            throw new IllegalStateException("The " + label + " keyword categories are not a "
                    + "partition of the lexer's words: uncoloured=" + missing
                    + " coloured twice=" + duplicated + " not a keyword=" + unknown);
        }
    }

    /// The words of `source` that appear in `wanted`, in source order.
    private static List<String> retain(List<String> source, List<String> wanted) {
        return source.stream().filter(wanted::contains).toList();
    }

    /// The words of `source` that do not appear in `unwanted`, in source order.
    private static List<String> remove(List<String> source, List<String> unwanted) {
        return source.stream().filter(word -> !unwanted.contains(word)).toList();
    }

    private static List<String> union(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).distinct().sorted().toList();
    }

    /// Renders words as a regex alternation.
    ///
    /// Longest-first ordering matters: TextMate's regex engine is leftmost-first, not
    /// leftmost-longest, so `in` placed before `int` would colour the `in` of `int` and
    /// leave a stray `t`. Every keyword here is alphanumeric, so no escaping is needed;
    /// the assertion documents that rather than trusting it.
    private static String alternation(List<String> words) {
        for (String word : words) {
            if (!word.chars().allMatch(c -> c == '_' || Character.isLetterOrDigit(c))) {
                throw new IllegalStateException("Keyword needs regex escaping: " + word);
            }
        }
        if (words.isEmpty()) {
            // An empty alternation would match everywhere; a never-matching pattern is the
            // honest rendering of "this category is currently empty".
            return "(?!)";
        }
        return words.stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed()
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .collect(Collectors.joining("|"));
    }

    /// The grammar skeleton. Only the keyword alternations are substituted.
    ///
    /// Scope names follow the TextMate conventions that themes actually key on; inventing
    /// names here would produce an uncoloured file in every theme that ships with an editor.
    private static final String TEMPLATE = """
            {
              "$schema": "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
              "name": "V#",
              "scopeName": "source.vsharp",
              "fileTypes": ["vs"],
              "patterns": [
                { "include": "#comment" },
                { "include": "#directive" },
                { "include": "#string" },
                { "include": "#character" },
                { "include": "#number" },
                { "include": "#keyword" },
                { "include": "#declaration" },
                { "include": "#invocation" },
                { "include": "#operator" },
                { "include": "#punctuation" },
                { "include": "#identifier" }
              ],
              "repository": {
                "comment": {
                  "patterns": [
                    {
                      "name": "comment.block.documentation.vsharp",
                      "begin": "///",
                      "end": "$",
                      "patterns": [
                        {
                          "name": "entity.name.tag.vsharp",
                          "match": "</?[A-Za-z][-A-Za-z0-9]*"
                        }
                      ]
                    },
                    { "name": "comment.line.double-slash.vsharp", "match": "//.*$" },
                    {
                      "name": "comment.block.vsharp",
                      "begin": "/\\\\*",
                      "end": "\\\\*/"
                    }
                  ]
                },
                "directive": {
                  "name": "meta.preprocessor.vsharp",
                  "begin": "^\\\\s*(#)\\\\s*(${DIRECTIVES})\\\\b",
                  "beginCaptures": {
                    "1": { "name": "punctuation.definition.directive.vsharp" },
                    "2": { "name": "keyword.control.directive.vsharp" }
                  },
                  "end": "$",
                  "patterns": [
                    { "name": "keyword.operator.preprocessor.vsharp", "match": "\\\\|\\\\||&&|!|==|!=" },
                    { "name": "entity.name.function.preprocessor.vsharp", "match": "[A-Za-z_][A-Za-z0-9_]*" }
                  ]
                },
                "string": {
                  "patterns": [
                    {
                      "name": "string.quoted.double.raw.vsharp",
                      "begin": "(\\\\$?)(\\"\\"\\")",
                      "beginCaptures": {
                        "1": { "name": "keyword.operator.interpolation.vsharp" },
                        "2": { "name": "punctuation.definition.string.begin.vsharp" }
                      },
                      "end": "\\"\\"\\"",
                      "endCaptures": {
                        "0": { "name": "punctuation.definition.string.end.vsharp" }
                      },
                      "patterns": [ { "include": "#interpolation" } ]
                    },
                    {
                      "name": "string.quoted.double.verbatim.vsharp",
                      "begin": "(?:(\\\\$)(@)|(@)(\\\\$)?)(\\")",
                      "beginCaptures": {
                        "1": { "name": "keyword.operator.interpolation.vsharp" },
                        "2": { "name": "keyword.operator.verbatim.vsharp" },
                        "3": { "name": "keyword.operator.verbatim.vsharp" },
                        "4": { "name": "keyword.operator.interpolation.vsharp" },
                        "5": { "name": "punctuation.definition.string.begin.vsharp" }
                      },
                      "end": "\\"(?!\\")",
                      "endCaptures": {
                        "0": { "name": "punctuation.definition.string.end.vsharp" }
                      },
                      "patterns": [
                        { "name": "constant.character.escape.vsharp", "match": "\\"\\"" },
                        { "include": "#interpolation" }
                      ]
                    },
                    {
                      "name": "string.quoted.double.vsharp",
                      "begin": "(\\\\$?)(\\")",
                      "beginCaptures": {
                        "1": { "name": "keyword.operator.interpolation.vsharp" },
                        "2": { "name": "punctuation.definition.string.begin.vsharp" }
                      },
                      "end": "(\\")|(?<!\\\\\\\\)(\\\\n)",
                      "endCaptures": {
                        "1": { "name": "punctuation.definition.string.end.vsharp" },
                        "2": { "name": "invalid.illegal.newline.vsharp" }
                      },
                      "patterns": [
                        { "include": "#escape" },
                        { "include": "#interpolation" }
                      ]
                    }
                  ]
                },
                "interpolation": {
                  "name": "meta.interpolation.vsharp",
                  "begin": "(?<!\\\\{)\\\\{(?!\\\\{)",
                  "beginCaptures": {
                    "0": { "name": "punctuation.section.interpolation.begin.vsharp" }
                  },
                  "end": "\\\\}",
                  "endCaptures": {
                    "0": { "name": "punctuation.section.interpolation.end.vsharp" }
                  },
                  "patterns": [
                    { "include": "#string" },
                    { "include": "#number" },
                    { "include": "#keyword" },
                    { "include": "#invocation" },
                    { "include": "#operator" },
                    { "include": "#identifier" }
                  ]
                },
                "escape": {
                  "name": "constant.character.escape.vsharp",
                  "match": "\\\\\\\\(u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8}|x[0-9A-Fa-f]{1,4}|[0-7]{1,3}|[abfnrtv0'\\"\\\\\\\\])"
                },
                "character": {
                  "name": "string.quoted.single.vsharp",
                  "begin": "'",
                  "beginCaptures": {
                    "0": { "name": "punctuation.definition.string.begin.vsharp" }
                  },
                  "end": "'",
                  "endCaptures": {
                    "0": { "name": "punctuation.definition.string.end.vsharp" }
                  },
                  "patterns": [ { "include": "#escape" } ]
                },
                "number": {
                  "patterns": [
                    {
                      "name": "constant.numeric.hex.vsharp",
                      "match": "\\\\b0[xX][0-9a-fA-F_]+(?:[uUlL]|[uU][lL]|[lL][uU])?\\\\b"
                    },
                    {
                      "name": "constant.numeric.binary.vsharp",
                      "match": "\\\\b0[bB][01_]+(?:[uUlL]|[uU][lL]|[lL][uU])?\\\\b"
                    },
                    {
                      "name": "constant.numeric.decimal.vsharp",
                      "match": "\\\\b[0-9][0-9_]*(?:\\\\.[0-9][0-9_]*)?(?:[eE][-+]?[0-9]+)?(?:[fFdDmMuUlL]|[uU][lL]|[lL][uU])?\\\\b"
                    },
                    {
                      "name": "constant.numeric.decimal.vsharp",
                      "match": "(?<![A-Za-z0-9_.])\\\\.[0-9][0-9_]*(?:[eE][-+]?[0-9]+)?[fFdDmM]?\\\\b"
                    }
                  ]
                },
                "keyword": {
                  "patterns": [
                    { "name": "keyword.control.vsharp", "match": "\\\\b(${CONTROL})\\\\b" },
                    { "name": "constant.language.vsharp", "match": "\\\\b(${LITERALS})\\\\b" },
                    { "name": "storage.type.builtin.vsharp", "match": "\\\\b(${TYPES})\\\\b" },
                    { "name": "keyword.other.vsharp", "match": "\\\\b(${KEYWORDS})\\\\b" },
                    {
                      "name": "keyword.other.contextual.vsharp",
                      "match": "\\\\b(${CONTEXTUAL})\\\\b"
                    }
                  ]
                },
                "declaration": {
                  "patterns": [
                    {
                      "name": "meta.declaration.type.vsharp",
                      "match": "\\\\b(enum|struct|record|class|interface|delegate|namespace)\\\\s+([A-Za-z_][A-Za-z0-9_]*)",
                      "captures": {
                        "1": { "name": "keyword.other.vsharp" },
                        "2": { "name": "entity.name.type.vsharp" }
                      }
                    },
                    {
                      "name": "meta.declaration.attribute.vsharp",
                      "comment": "Anchored to the line start rather than a lookbehind: an unanchored '[' cannot be told from an indexer, and TextMate's engine does not accept a variable-width lookbehind reliably across editors.",
                      "begin": "^\\\\s*(\\\\[)(?=[A-Za-z_])",
                      "beginCaptures": {
                        "1": { "name": "punctuation.definition.attribute.begin.vsharp" }
                      },
                      "end": "\\\\]",
                      "endCaptures": {
                        "0": { "name": "punctuation.definition.attribute.end.vsharp" }
                      },
                      "patterns": [
                        { "include": "#string" },
                        { "include": "#number" },
                        { "name": "entity.name.type.attribute.vsharp", "match": "[A-Za-z_][A-Za-z0-9_]*" }
                      ]
                    }
                  ]
                },
                "invocation": {
                  "patterns": [
                    {
                      "name": "meta.generic.invocation.vsharp",
                      "match": "([A-Za-z_][A-Za-z0-9_]*)\\\\s*(?=<[A-Za-z_?\\\\[\\\\],\\\\s]+>\\\\s*\\\\()",
                      "captures": { "1": { "name": "entity.name.function.vsharp" } }
                    },
                    {
                      "name": "meta.invocation.vsharp",
                      "match": "([A-Za-z_][A-Za-z0-9_]*)\\\\s*(?=\\\\()",
                      "captures": { "1": { "name": "entity.name.function.vsharp" } }
                    },
                    {
                      "name": "meta.qualifier.vsharp",
                      "match": "\\\\b([A-Z][A-Za-z0-9_]*)\\\\s*(?=\\\\.)",
                      "captures": { "1": { "name": "entity.name.type.vsharp" } }
                    }
                  ]
                },
                "operator": {
                  "patterns": [
                    { "name": "keyword.operator.range.vsharp", "match": "\\\\.\\\\." },
                    {
                      "name": "keyword.operator.assignment.vsharp",
                      "match": "(<<|>>>|>>|[-+*/%&|^])?=(?![=>])|\\\\?\\\\?="
                    },
                    { "name": "keyword.operator.lambda.vsharp", "match": "=>" },
                    {
                      "name": "keyword.operator.comparison.vsharp",
                      "match": "==|!=|<=|>=|<|>"
                    },
                    { "name": "keyword.operator.logical.vsharp", "match": "&&|\\\\|\\\\||!" },
                    { "name": "keyword.operator.null.vsharp", "match": "\\\\?\\\\?|\\\\?\\\\.|\\\\?\\\\[" },
                    {
                      "name": "keyword.operator.arithmetic.vsharp",
                      "match": "\\\\+\\\\+|--|[-+*/%~]|<<|>>>|>>|[&|^]"
                    },
                    { "name": "keyword.operator.ternary.vsharp", "match": "[?:]" }
                  ]
                },
                "punctuation": {
                  "patterns": [
                    { "name": "punctuation.terminator.statement.vsharp", "match": ";" },
                    { "name": "punctuation.separator.comma.vsharp", "match": "," },
                    { "name": "punctuation.accessor.vsharp", "match": "\\\\." },
                    { "name": "punctuation.section.block.vsharp", "match": "[{}]" },
                    { "name": "punctuation.section.parens.vsharp", "match": "[()]" },
                    { "name": "punctuation.section.brackets.vsharp", "match": "[\\\\[\\\\]]" }
                  ]
                },
                "identifier": {
                  "patterns": [
                    {
                      "name": "entity.name.type.vsharp",
                      "match": "\\\\b[A-Z][A-Za-z0-9_]*\\\\b"
                    },
                    {
                      "name": "variable.other.vsharp",
                      "match": "\\\\b[A-Za-z_][A-Za-z0-9_]*\\\\b"
                    }
                  ]
                }
              }
            }
            """;
}
