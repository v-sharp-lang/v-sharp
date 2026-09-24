/// Build-time generator for the V# editor extension.
///
/// Nothing here ships inside the extension: it produces the TextMate grammar from the
/// compiler's token tables and then exits. The extension itself is JavaScript plus the
/// language server, so this module has no runtime role.
module vsharp.vscode {
    requires vsharp.compiler;
}
