package vsharp.tests.source;

import vsharp.compiler.source.LineMap;
import vsharp.compiler.source.LinePosition;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Spans, line maps and the source file model.
///
/// Line terminators are written as explicit code points rather than escapes so the test
/// text cannot be silently altered by an editor normalising line endings.
public final class SourceModelTests implements TestSuite {

    private static final String NEL = String.valueOf((char) 0x0085);
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);
    private static final String PARAGRAPH_SEPARATOR = String.valueOf((char) 0x2029);
    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

    @Override
    public String suiteName() {
        return "source.model";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("span rejects negative start or length", () -> {
            Assert.throwsException(IllegalArgumentException.class,
                    () -> new SourceSpan(-1, 0), "negative start");
            Assert.throwsException(IllegalArgumentException.class,
                    () -> new SourceSpan(0, -1), "negative length");
        });

        registry.test("span arithmetic", () -> {
            SourceSpan span = SourceSpan.between(3, 7);
            Assert.equal(3L, span.start(), "start");
            Assert.equal(4L, span.length(), "length");
            Assert.equal(7L, span.end(), "end is exclusive");
            Assert.isTrue(span.contains(3), "start is contained");
            Assert.isFalse(span.contains(7), "end is not contained");
            Assert.equal("[3..7)", span.toString(), "rendering");
            Assert.equal(SourceSpan.between(1, 9),
                    SourceSpan.between(1, 4).union(SourceSpan.between(6, 9)), "union");
            Assert.isTrue(SourceSpan.at(4).isEmpty(), "point span is empty");
        });

        registry.test("line map handles every C# line terminator", () -> {
            // a LF b CR c CRLF d NEL e LS f PS g
            String text = "a\nb\rc\r\nd" + NEL + "e" + LINE_SEPARATOR + "f"
                    + PARAGRAPH_SEPARATOR + "g";
            LineMap map = LineMap.of(text);
            Assert.equal(7L, map.lineCount(), "six terminators produce seven lines");
            Assert.equal(new LinePosition(1, 1), map.positionOf(0), "first character");
            Assert.equal(new LinePosition(2, 1), map.positionOf(2), "after LF");
            Assert.equal(new LinePosition(3, 1), map.positionOf(4), "after CR");
            Assert.equal(new LinePosition(4, 1), map.positionOf(7), "CR LF counts once");
            Assert.equal(new LinePosition(5, 1), map.positionOf(9), "after NEL");
            Assert.equal(new LinePosition(6, 1), map.positionOf(11), "after LINE SEPARATOR");
            Assert.equal(new LinePosition(7, 1), map.positionOf(13), "after PARAGRAPH SEPARATOR");
        });

        registry.test("line map reports the end of the text", () -> {
            LineMap map = LineMap.of("ab\ncd");
            Assert.equal(new LinePosition(2, 3), map.positionOf(5), "offset at end of text");
            Assert.throwsException(IndexOutOfBoundsException.class,
                    () -> map.positionOf(6), "offset past end of text");
        });

        registry.test("empty text still has one line", () -> {
            LineMap map = LineMap.of("");
            Assert.equal(1L, map.lineCount(), "line count");
            Assert.equal(new LinePosition(1, 1), map.positionOf(0), "position of the end");
        });

        registry.test("line bounds exclude the terminator", () -> {
            SourceFile file = SourceFile.of("t.vs", "one\r\ntwo\n");
            Assert.equal("one", file.lineText(1), "first line");
            Assert.equal("two", file.lineText(2), "second line");
            Assert.equal("", file.lineText(3), "trailing empty line");
        });

        registry.test("source file strips a leading byte-order mark", () -> {
            SourceFile file = SourceFile.of("t.vs", BYTE_ORDER_MARK + "int x;");
            Assert.equal("int x;", file.text(), "BOM removed so offset 0 is real text");
            Assert.equal(new LinePosition(1, 1), file.positionOf(0), "first column");
        });

        registry.test("source file exposes span text", () -> {
            SourceFile file = SourceFile.of("t.vs", "let value = 1;");
            Assert.equal("value", file.textOf(SourceSpan.between(4, 9)), "span text");
        });

        registry.test("line positions are validated and ordered", () -> {
            Assert.throwsException(IllegalArgumentException.class,
                    () -> new LinePosition(0, 1), "line is one-based");
            Assert.isTrue(new LinePosition(1, 2).compareTo(new LinePosition(2, 1)) < 0,
                    "line dominates column");
            Assert.equal("1,5", new LinePosition(1, 5).toString(), "rendering");
        });
    }
}
