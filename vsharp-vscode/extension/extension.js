"use strict";

// A complete LSP client for V#, written against nothing but the VS Code API and Node's
// standard library.
//
// The usual choice here is `vscode-languageclient`. This project's dependency rule exists
// so that the whole toolchain can be built and audited from its own sources, and pulling an
// npm tree into the one component a user actually installs would be the loudest possible
// place to break it. The protocol surface we need - framing, request/response correlation,
// three sync notifications and four requests - is small and fully specified, so it is
// written out here instead. Everything it does is visible in this file.

const vscode = require("vscode");
const child_process = require("child_process");
const fs = require("fs");
const path = require("path");

const LANGUAGE_ID = "vsharp";

/** Milliseconds a request waits before the client gives up on the server. */
const REQUEST_TIMEOUT_MS = 20000;

/**
 * A live connection to one server process.
 *
 * The class owns the process, the read buffer and the pending-request table. Restarting is
 * modelled as disposing one instance and constructing another, so no state can survive a
 * restart and go stale - which is the failure mode that makes editors report diagnostics
 * for files the server has never seen.
 */
class Client {
    constructor(output, diagnostics, trace) {
        this.output = output;
        this.diagnostics = diagnostics;
        this.trace = trace;
        this.process = null;
        this.buffer = Buffer.alloc(0);
        this.pending = new Map();
        this.nextId = 1;
        this.initialized = false;
        this.disposed = false;
        this.versions = new Map();
    }

    /** Starts the server and completes the LSP handshake. */
    async start(command, args) {
        this.output.appendLine("Starting: " + command + " " + args.join(" "));
        this.process = child_process.spawn(command, args, {
            stdio: ["pipe", "pipe", "pipe"]
        });
        this.process.on("error", (error) => {
            this.output.appendLine("Server process failed: " + error.message);
            vscode.window.showErrorMessage("V#: could not start the language server: "
                + error.message);
            this.failPending("server process failed to start");
        });
        this.process.stderr.on("data", (chunk) => {
            this.output.append(chunk.toString("utf8"));
        });
        this.process.stdout.on("data", (chunk) => this.receive(chunk));
        this.process.on("exit", (code, signal) => {
            this.output.appendLine("Server exited (code " + code + ", signal " + signal + ")");
            this.failPending("server exited");
            setStatus("failed", "server exited (code " + code + ")");
            if (!this.disposed) {
                vscode.window.showWarningMessage(
                    "V#: the language server stopped. Run 'V#: Restart Language Server' to try again.");
            }
        });

        const result = await this.request("initialize", {
            processId: process.pid,
            clientInfo: { name: "vsharp-vscode" },
            rootUri: null,
            // Mirrors what the build passes the compiler. Without it the editor would report
            // VS20018 as an error on a project whose build accepts those joins, which is the
            // editor disagreeing with a green build.
            initializationOptions: {
                allowUnboundedJoins: vscode.workspace
                    .getConfiguration("vsharp").get("allowUnboundedJoins", false)
            },
            capabilities: {
                textDocument: {
                    synchronization: { dynamicRegistration: false },
                    completion: { completionItem: { snippetSupport: false } },
                    hover: { contentFormat: ["markdown", "plaintext"] },
                    signatureHelp: {
                        signatureInformation: { parameterInformation: { labelOffsetSupport: true } }
                    },
                    definition: { linkSupport: false },
                    documentSymbol: { hierarchicalDocumentSymbolSupport: true },
                    publishDiagnostics: { relatedInformation: false }
                }
            }
        });
        this.output.appendLine("Server capabilities: " + JSON.stringify(result.capabilities));
        this.notify("initialized", {});
        this.initialized = true;
        return result;
    }

    /** Sends a request and resolves with its result. */
    request(method, params, token) {
        if (!this.process || this.process.exitCode !== null) {
            return Promise.reject(new Error("The V# language server is not running"));
        }
        const id = this.nextId++;
        const message = { jsonrpc: "2.0", id: id, method: method, params: params };
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                if (this.pending.delete(id)) {
                    reject(new Error(method + " timed out after " + REQUEST_TIMEOUT_MS + "ms"));
                }
            }, REQUEST_TIMEOUT_MS);
            // The editor cancels aggressively - every keystroke abandons the completion
            // request before it - and a client that ignores that accumulates promises which
            // never settle and answers with results for a cursor that has already moved.
            if (token && typeof token.onCancellationRequested === "function") {
                token.onCancellationRequested(() => {
                    if (this.pending.delete(id)) {
                        clearTimeout(timer);
                        this.notify("$/cancelRequest", { id: id });
                        resolve(null);
                    }
                });
            }
            const started = Date.now();
            const entry = {
                resolve: (value) => {
                    // One line per request, always. Without this the channel showed the
                    // handshake and then nothing at all, so a session where the editor never
                    // asked anything was indistinguishable from one where every answer was
                    // empty - which is exactly the report that led here.
                    const size = Array.isArray(value) ? value.length
                        : (value === null || value === undefined) ? "null" : "ok";
                    this.output.appendLine("  " + method + " -> " + size
                        + " (" + (Date.now() - started) + "ms)");
                    resolve(value);
                },
                reject, timer
            };
            this.pending.set(id, entry);
            this.send(message);
        });
    }

    notify(method, params) {
        if (!this.process || this.process.exitCode !== null) {
            return;
        }
        this.send({ jsonrpc: "2.0", method: method, params: params });
    }

    send(message) {
        const body = Buffer.from(JSON.stringify(message), "utf8");
        if (this.trace()) {
            this.output.appendLine("-> " + body.toString("utf8"));
        }
        // Content-Length counts bytes, not characters: the header must be written from the
        // encoded buffer's length or any non-ASCII source text desynchronises the stream.
        this.process.stdin.write("Content-Length: " + body.length + "\r\n\r\n");
        this.process.stdin.write(body);
    }

    /**
     * Accumulates bytes and dispatches every complete message in them.
     *
     * A chunk boundary can fall anywhere, including inside a header or a multi-byte
     * character, so decoding happens only once a whole payload has arrived.
     */
    receive(chunk) {
        this.buffer = Buffer.concat([this.buffer, chunk]);
        for (;;) {
            const separator = this.buffer.indexOf("\r\n\r\n");
            if (separator < 0) {
                return;
            }
            const header = this.buffer.subarray(0, separator).toString("ascii");
            const match = /content-length:\s*(\d+)/i.exec(header);
            if (!match) {
                this.output.appendLine("Dropping frame with no Content-Length: " + header);
                this.buffer = this.buffer.subarray(separator + 4);
                continue;
            }
            const length = Number(match[1]);
            const start = separator + 4;
            if (this.buffer.length < start + length) {
                return;
            }
            const body = this.buffer.subarray(start, start + length).toString("utf8");
            this.buffer = this.buffer.subarray(start + length);
            if (this.trace()) {
                this.output.appendLine("<- " + body);
            }
            let message;
            try {
                message = JSON.parse(body);
            } catch (error) {
                this.output.appendLine("Malformed JSON from server: " + error.message);
                continue;
            }
            this.dispatch(message);
        }
    }

    dispatch(message) {
        if (message.id !== undefined && message.id !== null
            && (message.result !== undefined || message.error !== undefined)) {
            const entry = this.pending.get(message.id);
            if (!entry) {
                return;
            }
            this.pending.delete(message.id);
            clearTimeout(entry.timer);
            if (message.error) {
                entry.reject(new Error(message.error.message || "server error"));
            } else {
                entry.resolve(message.result);
            }
            return;
        }
        if (message.method === "textDocument/publishDiagnostics") {
            this.publish(message.params);
            return;
        }
        if (message.id !== undefined && message.id !== null) {
            // A server-to-client request. This server issues none, but answering with
            // MethodNotFound is required: leaving it unanswered would hang the server.
            this.send({
                jsonrpc: "2.0",
                id: message.id,
                error: { code: -32601, message: "Unsupported request: " + message.method }
            });
        }
    }

    publish(params) {
        const uri = vscode.Uri.parse(params.uri);
        const entries = (params.diagnostics || []).map((diagnostic) => {
            const range = toRange(diagnostic.range);
            const item = new vscode.Diagnostic(range, diagnostic.message,
                toSeverity(diagnostic.severity));
            item.code = diagnostic.code;
            item.source = diagnostic.source || "vsharp";
            return item;
        });
        this.diagnostics.set(uri, entries);
        this.output.appendLine("diagnostics " + uri.fsPath + ": " + entries.length);
    }

    failPending(reason) {
        for (const entry of this.pending.values()) {
            clearTimeout(entry.timer);
            entry.reject(new Error(reason));
        }
        this.pending.clear();
    }

    // -- document synchronisation -------------------------------------------------------

    didOpen(document) {
        this.output.appendLine("didOpen " + document.uri.fsPath
            + " (v" + document.version + ")");
        this.versions.set(document.uri.toString(), document.version);
        this.notify("textDocument/didOpen", {
            textDocument: {
                uri: document.uri.toString(),
                languageId: LANGUAGE_ID,
                version: document.version,
                text: document.getText()
            }
        });
    }

    didChange(document) {
        // Full sync only: the server advertises TextDocumentSyncKind.Full, so sending the
        // whole buffer is not a shortcut, it is the contract.
        this.versions.set(document.uri.toString(), document.version);
        this.notify("textDocument/didChange", {
            textDocument: {
                uri: document.uri.toString(),
                version: document.version
            },
            contentChanges: [{ text: document.getText() }]
        });
    }

    didClose(document) {
        this.versions.delete(document.uri.toString());
        this.notify("textDocument/didClose", {
            textDocument: { uri: document.uri.toString() }
        });
        this.diagnostics.delete(document.uri);
    }

    async dispose() {
        this.disposed = true;
        if (!this.process || this.process.exitCode !== null) {
            return;
        }
        try {
            if (this.initialized) {
                await this.request("shutdown", null);
                this.notify("exit", null);
            }
        } catch (error) {
            this.output.appendLine("Shutdown failed, killing: " + error.message);
        }
        // The server exits on `exit`; the kill is the backstop for one that does not, so a
        // reload never leaves an orphaned JVM holding the workspace.
        const victim = this.process;
        setTimeout(() => {
            if (victim.exitCode === null) {
                victim.kill();
            }
        }, 2000);
    }
}

function toRange(range) {
    return new vscode.Range(
        new vscode.Position(range.start.line, range.start.character),
        new vscode.Position(range.end.line, range.end.character));
}

function toSeverity(severity) {
    switch (severity) {
        case 1: return vscode.DiagnosticSeverity.Error;
        case 2: return vscode.DiagnosticSeverity.Warning;
        case 3: return vscode.DiagnosticSeverity.Information;
        default: return vscode.DiagnosticSeverity.Hint;
    }
}

/**
 * Works out how to launch the server.
 *
 * The bundled jars are run through `java` directly rather than through the generated start
 * script: a .vsix is a zip, zip entries carry no executable bit on the platforms that need
 * one, and a launcher that is not executable fails with a message that tells the user
 * nothing useful.
 */
function resolveLaunch(context, config) {
    const explicit = (config.get("server.path") || "").trim();
    if (explicit) {
        if (!fs.existsSync(explicit)) {
            throw new Error("vsharp.server.path does not exist: " + explicit);
        }
        return { command: explicit, args: [] };
    }

    const lib = path.join(context.extensionPath, "server", "lib");
    if (fs.existsSync(lib)) {
        return {
            command: javaExecutable(config),
            args: ["--module-path", lib, "--add-modules", "ALL-DEFAULT",
                "--module", "vsharp.lsp/vsharp.lsp.Main"]
        };
    }
    // The installed start script is `vsharp-lsp` everywhere but Windows, where the same
    // distribution installs `vsharp-lsp.bat`. Node's `spawn` does not resolve a `.bat` from
    // PATH by name without a shell, so the extension names it: without this the fallback
    // fails with ENOENT on the one platform that needs the other spelling.
    const server = process.platform === "win32" ? "vsharp-lsp.bat" : "vsharp-lsp";
    return { command: server, args: [] };
}

function javaExecutable(config) {
    const explicit = (config.get("server.java") || "").trim();
    if (explicit) {
        return explicit;
    }
    const home = process.env.JAVA_HOME;
    if (home) {
        const candidate = path.join(home, "bin",
            process.platform === "win32" ? "java.exe" : "java");
        if (fs.existsSync(candidate)) {
            return candidate;
        }
    }
    return "java";
}

let client = null;
let output = null;
let diagnostics = null;
let status = null;

/// Shows the server's state in the status bar.
///
/// This exists because of a real report: the extension looked completely inert, and there
/// was no way to tell a server that had failed to start from one that was merely thinking,
/// or from an editor that had never activated the extension at all. Three different causes,
/// one indistinguishable symptom. The status bar is the cheapest possible answer, and
/// clicking it opens the log.
function setStatus(state, detail) {
    if (!status) {
        return;
    }
    const icons = { starting: "$(sync~spin)", ready: "$(check)", failed: "$(error)" };
    status.text = (icons[state] || "$(question)") + " V#";
    status.tooltip = "V# language server: " + (detail || state)
        + "\nClick to show the log.";
    status.show();
}

async function startClient(context) {
    const config = vscode.workspace.getConfiguration("vsharp");
    const launch = resolveLaunch(context, config);
    const next = new Client(output, diagnostics,
        () => vscode.workspace.getConfiguration("vsharp").get("trace.server") === true);
    setStatus("starting", "starting " + launch.command);
    const capabilities = await next.start(launch.command, launch.args);
    client = next;
    setStatus("ready", "ready (" + ((capabilities && capabilities.serverInfo
        && capabilities.serverInfo.version) || "dev") + ")");
    const known = vscode.workspace.textDocuments
        .filter((document) => document.languageId === LANGUAGE_ID);
    output.appendLine("Open V# documents at startup: " + known.length
        + " (of " + vscode.workspace.textDocuments.length + " open documents)");
    if (known.length === 0) {
        output.appendLine("No document has languageId 'vsharp'. If a .vs file is open, its "
            + "language was not associated - check the language indicator in the status bar.");
    }
    for (const document of known) {
        client.didOpen(document);
    }
}

async function restart(context) {
    if (client) {
        const previous = client;
        client = null;
        await previous.dispose();
    }
    diagnostics.clear();
    await startClient(context);
}

function activate(context) {
    output = vscode.window.createOutputChannel("V# Language Server");
    diagnostics = vscode.languages.createDiagnosticCollection("vsharp");
    status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    status.command = "vsharp.showOutput";
    context.subscriptions.push(output, diagnostics, status);
    setStatus("starting", "starting");

    const selector = { language: LANGUAGE_ID, scheme: "file" };

    // Go to definition on a jar or JDK type opens a generated signature view. It exists
    // nowhere on disk, so the editor is given a provider for its scheme rather than a path;
    // the document is read-only because nothing could be saved back into a class file.
    context.subscriptions.push(vscode.workspace.registerTextDocumentContentProvider(
        "vsharp-jar", {
            async provideTextDocumentContent(uri, token) {
                if (!client) {
                    return "// The V# language server is not running.";
                }
                try {
                    const result = await client.request("vsharp/javaSource",
                        { uri: uri.toString() }, token);
                    return (result && result.text)
                        || "// No signature view is available for " + uri.path;
                } catch (error) {
                    return "// Could not load the signature view: " + error.message;
                }
            }
        }));

    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument((document) => {
            if (client && document.languageId === LANGUAGE_ID) {
                client.didOpen(document);
            }
        }),
        vscode.workspace.onDidChangeTextDocument((event) => {
            if (client && event.document.languageId === LANGUAGE_ID) {
                client.didChange(event.document);
            }
        }),
        vscode.workspace.onDidCloseTextDocument((document) => {
            if (client && document.languageId === LANGUAGE_ID) {
                client.didClose(document);
            }
        }));

    context.subscriptions.push(vscode.languages.registerCompletionItemProvider(selector, {
        async provideCompletionItems(document, position, token) {
            if (!client) {
                return [];
            }
            const items = await client.request("textDocument/completion", {
                textDocument: { uri: document.uri.toString() },
                position: { line: position.line, character: position.character }
            }, token);
            const list = Array.isArray(items) ? items : (items && items.items) || [];
            return list.map((item) => {
                const completion = new vscode.CompletionItem(item.label,
                    (item.kind || 1) - 1);
                completion.detail = item.detail;
                completion.sortText = item.sortText;
                return completion;
            });
        }
    }, ".", " ", "(", "[", ",", ":", "<"));

    context.subscriptions.push(vscode.languages.registerSignatureHelpProvider(selector, {
        async provideSignatureHelp(document, position, token) {
            if (!client) {
                return null;
            }
            const result = await client.request("textDocument/signatureHelp", {
                textDocument: { uri: document.uri.toString() },
                position: { line: position.line, character: position.character }
            }, token);
            if (!result || !result.signatures || result.signatures.length === 0) {
                return null;
            }
            const help = new vscode.SignatureHelp();
            help.signatures = result.signatures.map((signature) => {
                const item = new vscode.SignatureInformation(signature.label);
                item.parameters = (signature.parameters || []).map(
                    (parameter) => new vscode.ParameterInformation(parameter.label));
                return item;
            });
            help.activeSignature = result.activeSignature || 0;
            help.activeParameter = result.activeParameter || 0;
            return help;
        }
    }, "(", ","));

    context.subscriptions.push(vscode.languages.registerHoverProvider(selector, {
        async provideHover(document, position, token) {
            if (!client) {
                return null;
            }
            const result = await client.request("textDocument/hover", {
                textDocument: { uri: document.uri.toString() },
                position: { line: position.line, character: position.character }
            }, token);
            if (!result || !result.contents) {
                return null;
            }
            const value = typeof result.contents === "string"
                ? result.contents
                : result.contents.value;
            if (!value) {
                return null;
            }
            const markdown = new vscode.MarkdownString(value);
            return new vscode.Hover(markdown,
                result.range ? toRange(result.range) : undefined);
        }
    }));

    context.subscriptions.push(vscode.languages.registerDefinitionProvider(selector, {
        async provideDefinition(document, position, token) {
            if (!client) {
                return null;
            }
            const result = await client.request("textDocument/definition", {
                textDocument: { uri: document.uri.toString() },
                position: { line: position.line, character: position.character }
            }, token);
            if (!result) {
                return null;
            }
            const locations = Array.isArray(result) ? result : [result];
            return locations
                .filter((location) => location && location.uri)
                .map((location) => new vscode.Location(
                    vscode.Uri.parse(location.uri), toRange(location.range)));
        }
    }));

    context.subscriptions.push(vscode.languages.registerReferenceProvider(selector, {
        async provideReferences(document, position, context_, token) {
            if (!client) {
                return [];
            }
            const result = await client.request("textDocument/references", {
                textDocument: { uri: document.uri.toString() },
                position: { line: position.line, character: position.character },
                context: { includeDeclaration: context_ ? context_.includeDeclaration : true }
            }, token);
            return (result || [])
                .filter((location) => location && location.uri)
                .map((location) => new vscode.Location(
                    vscode.Uri.parse(location.uri), toRange(location.range)));
        }
    }));

    context.subscriptions.push(vscode.languages.registerDocumentSymbolProvider(selector, {
        async provideDocumentSymbols(document, token) {
            if (!client) {
                return [];
            }
            const result = await client.request("textDocument/documentSymbol", {
                textDocument: { uri: document.uri.toString() }
            }, token);
            return (result || []).map(toDocumentSymbol).filter((symbol) => symbol !== null);
        }
    }));

    context.subscriptions.push(
        vscode.commands.registerCommand("vsharp.restartServer", async () => {
            try {
                await restart(context);
                vscode.window.showInformationMessage("V#: language server restarted.");
            } catch (error) {
                vscode.window.showErrorMessage("V#: restart failed: " + error.message);
            }
        }),
        vscode.commands.registerCommand("vsharp.showOutput", () => output.show(true)),
        vscode.commands.registerCommand("vsharp.reportStatus", () => report(context)));

    startClient(context).catch((error) => {
        output.appendLine("Startup failed: " + (error.stack || error.message));
        setStatus("failed", error.message);
        vscode.window.showErrorMessage("V#: language server failed to start: "
            + error.message + " (see the V# Language Server output channel)");
    });
}

/// When the bundled server was built, and how far behind the workspace it is.
///
/// A server older than the compiler a project builds with reports syntax errors on source the
/// build accepts, because it is a different parser. That happened with `async`/`await`: an
/// extension four days old flagged 13 phantom errors in a file the CLI compiled cleanly, and
/// nothing on screen said the two were different programs. The age is printed here so one
/// status report answers it.
function bundledServerAge(context) {
    try {
        const lib = path.join(context.extensionPath, "server", "lib");
        const jars = fs.readdirSync(lib).filter((n) => n.endsWith(".jar"));
        if (jars.length === 0) {
            return "no bundled server (using an external launcher)";
        }
        const newest = jars
            .map((n) => fs.statSync(path.join(lib, n)).mtime)
            .reduce((a, b) => (a > b ? a : b));
        const days = Math.floor((Date.now() - newest.getTime()) / 86400000);
        return newest.toISOString() + "  (" + days + " day(s) old)"
            + (days >= 1 ? "   <-- reinstall the extension if the compiler is newer" : "");
    } catch (error) {
        return "unknown (" + error.message + ")";
    }
}

/// Writes everything needed to diagnose "nothing happens" into the output channel.
///
/// Every failure so far has been one of a small set - the extension never activated, the file
/// was not recognised as V#, the document never reached the server, the server answered with
/// nothing - and from the user's seat they are indistinguishable. This asks each question in
/// turn and prints the answer, including a live round trip, so one command produces a report
/// that identifies which of them it is.
async function report(context) {
    output.show(true);
    output.appendLine("");
    output.appendLine("===== V# status report =====");
    try {
        const config = vscode.workspace.getConfiguration("vsharp");
        output.appendLine("extension path : " + context.extensionPath);
        const launch = resolveLaunch(context, config);
        output.appendLine("launch command : " + launch.command + " " + launch.args.join(" "));
        output.appendLine("server built   : " + bundledServerAge(context));
        output.appendLine("client         : " + (client ? "connected" : "NOT CONNECTED"));

        const editor = vscode.window.activeTextEditor;
        if (!editor) {
            output.appendLine("active editor  : none - open a .vs file and run this again");
        } else {
            const document = editor.document;
            output.appendLine("active file    : " + document.uri.fsPath);
            output.appendLine("languageId     : " + document.languageId
                + (document.languageId === LANGUAGE_ID ? "" : "   <-- NOT 'vsharp'"));
            output.appendLine("uri scheme     : " + document.uri.scheme);
        }

        const vs = vscode.workspace.textDocuments
            .filter((d) => d.languageId === LANGUAGE_ID);
        output.appendLine("V# documents   : " + vs.length + " of "
            + vscode.workspace.textDocuments.length + " open");

        if (client && editor && editor.document.languageId === LANGUAGE_ID) {
            const uri = editor.document.uri.toString();
            const started = Date.now();
            const symbols = await client.request("textDocument/documentSymbol",
                { textDocument: { uri: uri } });
            output.appendLine("round trip     : documentSymbol -> "
                + (Array.isArray(symbols) ? symbols.length + " symbols" : String(symbols))
                + " in " + (Date.now() - started) + "ms");
            const position = editor.selection.active;
            const items = await client.request("textDocument/completion", {
                textDocument: { uri: uri },
                position: { line: position.line, character: position.character }
            });
            const list = Array.isArray(items) ? items : (items && items.items) || [];
            output.appendLine("round trip     : completion at " + position.line + ":"
                + position.character + " -> " + list.length + " items"
                + (list.length ? " e.g. " + list.slice(0, 5).map((i) => i.label).join(", ") : ""));
        }
    } catch (error) {
        output.appendLine("report failed  : " + (error.stack || error.message));
    }
    output.appendLine("===== end of report =====");
}

function toDocumentSymbol(node) {
    if (!node || !node.range || !node.selectionRange) {
        return null;
    }
    const symbol = new vscode.DocumentSymbol(
        node.name,
        node.detail || "",
        (node.kind || 1) - 1,
        toRange(node.range),
        toRange(node.selectionRange));
    symbol.children = (node.children || [])
        .map(toDocumentSymbol)
        .filter((child) => child !== null);
    return symbol;
}

async function deactivate() {
    if (client) {
        const previous = client;
        client = null;
        await previous.dispose();
    }
}

module.exports = { activate, deactivate };
