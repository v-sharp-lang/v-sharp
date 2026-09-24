package vsharp.lsp.server;

import vsharp.compiler.source.LineMap;
import vsharp.compiler.source.LinePosition;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.lsp.json.Json;

/// Translation between compiler source positions and LSP positions.
///
/// Two conventions differ and both differences are silent if you get them wrong: the
/// compiler numbers lines and columns from one, LSP from zero. The unit agrees - the
/// compiler measures offsets in UTF-16 code units, which is exactly the default
/// `PositionEncodingKind` of LSP and exactly what a Java `String` index already is - so no
/// re-encoding is ever required, only the one-off bias.
final class Positions {

    private Positions() {
        throw new AssertionError("No instances");
    }

    /// Renders a compiler span as an LSP `Range`.
    static Json.Obj range(SourceFile file, SourceSpan span) {
        return Json.object()
                .put("start", position(file, span.start()))
                .put("end", position(file, span.end()))
                .build();
    }

    /// Renders one offset as an LSP `Position`.
    static Json.Obj position(SourceFile file, int offset) {
        int clamped = Math.max(0, Math.min(offset, file.length()));
        LinePosition position = file.positionOf(clamped);
        return Json.object()
                .put("line", position.line() - 1)
                .put("character", position.column() - 1)
                .build();
    }

    /// An empty range at one offset, used where a feature has a point rather than an extent.
    static Json.Obj pointRange(SourceFile file, int offset) {
        Json.Obj point = position(file, offset);
        return Json.object().put("start", point).put("end", point).build();
    }

    /// The offset addressed by an LSP `Position` object.
    ///
    /// Out-of-range lines and characters are clamped rather than rejected: an editor can
    /// legitimately race a keystroke against an in-flight request, and answering against
    /// the nearest valid offset is far better for the user than failing the request.
    static int offsetOf(SourceFile file, Json.Obj position) {
        int line = position.integer("line").orElse(0) + 1;
        int character = position.integer("character").orElse(0);
        LineMap map = file.lineMap();
        int clampedLine = Math.max(1, Math.min(line, map.lineCount()));
        int start = map.lineStart(clampedLine);
        int end = map.lineEnd(clampedLine, file.text());
        return Math.min(start + Math.max(0, character), end);
    }
}
