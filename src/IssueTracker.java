///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Single-file projects + issues tracker with voice comments.
 *
 * - Two-level hierarchy: Projects → Issues → Comments
 * - Voice input via the local `whisper` CLI (openai-whisper, German)
 * - State persisted as a single JSON file (no database)
 * - Notifications to Telegram on every Daniel-side change
 * - Tailnet-only: no auth, no HTTPS — front it with `tailscale serve`
 *
 * Run:
 *   jbang src/IssueTracker.java
 *
 * Expose over Tailscale on https:8443 → http://localhost:7777:
 *   tailscale serve --bg https:8443 http://localhost:7777
 *
 * Environment:
 *   TELEGRAPH_BOT_TOKEN  Telegram bot token (sender of pings).
 *                        Also accepts TELEGRAM_BOT_TOKEN as fallback.
 *   TELEGRAM_CHAT_ID     Chat ID to ping. Defaults to Daniel's chat.
 *
 * System properties:
 *   -Dtracker.port=7777                       HTTP listen port (default 7777)
 *   -Dtracker.state=<path>                    state.json path
 *                                             (default ~/.local/share/issue-tracker/state.json)
 *   -Dtracker.whisper=whisper                 path to the whisper CLI
 *   -Dtracker.lang=de                         whisper --language
 */
public class IssueTracker {

    static final int    PORT        = Integer.getInteger("tracker.port", 7777);
    static final String STATE_FILE  = System.getProperty("tracker.state",
            System.getProperty("user.home") + "/.local/share/issue-tracker/state.json");
    static final String WHISPER_BIN = System.getProperty("tracker.whisper", "whisper");
    static final String WHISPER_LANG = System.getProperty("tracker.lang", "de");

    static final String BOT_TOKEN;
    static {
        String t = System.getenv("TELEGRAPH_BOT_TOKEN");
        if (t == null || t.isBlank()) t = System.getenv("TELEGRAM_BOT_TOKEN");
        BOT_TOKEN = t;
    }
    static final String CHAT_ID = firstNonBlank(System.getenv("TELEGRAM_CHAT_ID"), "7989140452");

    static final ObjectMapper JSON = new ObjectMapper();
    static final Object STATE_LOCK = new Object();
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        ensureStateFile();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/state.json", IssueTracker::handleState);
        server.createContext("/notify",     IssueTracker::handleNotify);
        server.createContext("/transcribe", IssueTracker::handleTranscribe);
        server.createContext("/",           IssueTracker::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Tracker listening on http://127.0.0.1:" + PORT);
        System.out.println("State file:   " + STATE_FILE);
        System.out.println("Whisper CLI:  " + WHISPER_BIN + " (lang=" + WHISPER_LANG + ")");
        if (BOT_TOKEN == null || BOT_TOKEN.isBlank()) {
            System.out.println("WARN: no bot token in env — Telegram pings disabled.");
        } else {
            System.out.println("Telegram:     chat_id=" + CHAT_ID);
        }
    }

    // ---------- handlers ----------

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", INDEX_HTML);
    }

    static void handleState(HttpExchange ex) throws IOException {
        switch (ex.getRequestMethod()) {
            case "GET" -> {
                byte[] body;
                synchronized (STATE_LOCK) {
                    body = Files.readAllBytes(Path.of(STATE_FILE));
                }
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                send(ex, 200, "application/json; charset=utf-8", body);
            }
            case "PUT" -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                try { JSON.readTree(body); }
                catch (Exception e) {
                    send(ex, 400, "text/plain", "Invalid JSON: " + e.getMessage());
                    return;
                }
                synchronized (STATE_LOCK) {
                    Files.write(Path.of(STATE_FILE), body,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                send(ex, 200, "application/json", "{\"ok\":true}");
            }
            default -> send(ex, 405, "text/plain", "Method not allowed");
        }
    }

    static void handleNotify(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        try {
            JsonNode node = JSON.readTree(ex.getRequestBody().readAllBytes());
            String event   = node.path("event").asText("");
            String title   = node.path("title").asText("");
            String preview = node.path("preview").asText("");
            String msg = switch (event) {
                case "project" -> "[tracker] daniel angelegt: Projekt \"" + title + "\"";
                case "issue"   -> "[tracker] daniel angelegt: Issue \""   + title + "\"";
                case "comment" -> "[tracker] daniel kommentiert: " + preview;
                case "status"  -> "[tracker] daniel hat Status geändert: " + title;
                default        -> "[tracker] daniel: " + event;
            };
            sendTelegram(msg);
            send(ex, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            send(ex, 400, "text/plain", "Bad request: " + e.getMessage());
        }
    }

    static void handleTranscribe(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        String ext = pickAudioExtension(ct);
        Path tmp = Files.createTempFile("tracker-audio-", ext);
        try {
            Files.write(tmp, body);
            var pb = new ProcessBuilder(
                    WHISPER_BIN, "--language", WHISPER_LANG,
                    tmp.toString(), "--outfile", "/dev/stdout")
                .redirectErrorStream(false);
            Process proc = pb.start();
            String out;
            try (var is = proc.getInputStream()) {
                out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            int rc = proc.waitFor();
            if (rc != 0) {
                String err;
                try (var es = proc.getErrorStream()) {
                    err = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                send(ex, 500, "text/plain; charset=utf-8",
                        "whisper failed (rc=" + rc + "): " + err);
                return;
            }
            send(ex, 200, "text/plain; charset=utf-8", out.trim());
        } catch (Exception e) {
            send(ex, 500, "text/plain", "Server error: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    // ---------- helpers ----------

    static void ensureStateFile() throws IOException {
        Path p = Path.of(STATE_FILE);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            Files.writeString(p, "{\"projects\":[]}\n");
        }
    }

    static String pickAudioExtension(String contentType) {
        if (contentType == null) return ".webm";
        String ct = contentType.toLowerCase();
        if (ct.contains("ogg") || ct.contains("oga")) return ".ogg";
        if (ct.contains("wav"))                       return ".wav";
        if (ct.contains("mp3") || ct.contains("mpeg")) return ".mp3";
        if (ct.contains("mp4") || ct.contains("m4a")) return ".m4a";
        return ".webm";
    }

    static void sendTelegram(String text) {
        if (BOT_TOKEN == null || BOT_TOKEN.isBlank()) return;
        try {
            String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            String body = JSON.writeValueAsString(Map.of(
                    "chat_id", CHAT_ID,
                    "text",    text));
            HTTP.sendAsync(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[tracker] Telegram send failed: " + e.getMessage());
        }
    }

    static String firstNonBlank(String... s) {
        for (String x : s) if (x != null && !x.isBlank()) return x;
        return "";
    }

    static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        send(ex, code, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // ---------- frontend ----------

    static final String INDEX_HTML = """
        <!doctype html>
        <html lang="de">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Tracker</title>
        <style>
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; height: 100%; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background: #f5f5f7;
            color: #1c1c1e;
            font-size: 14px;
            line-height: 1.5;
            -webkit-font-smoothing: antialiased;
        }
        .app {
            display: grid;
            grid-template-columns: 280px 360px 1fr;
            grid-template-rows: 52px 1fr;
            grid-template-areas:
                "header   header   header"
                "projects issues   thread";
            height: 100vh;
        }
        header {
            grid-area: header;
            background: white;
            border-bottom: 1px solid #e5e5e7;
            display: flex;
            align-items: center;
            padding: 0 20px;
            gap: 12px;
        }
        .title { font-weight: 600; font-size: 15px; letter-spacing: 0.01em; }
        .subtitle { color: #999; font-size: 12px; margin-left: 4px; }
        .refresh-btn {
            margin-left: auto;
            background: transparent;
            border: 1px solid #d1d1d3;
            border-radius: 6px;
            padding: 5px 12px;
            cursor: pointer;
            font-size: 13px;
            color: #1c1c1e;
            transition: background 120ms;
        }
        .refresh-btn:hover { background: #f0f0f2; }
        .col {
            overflow-y: auto;
            background: white;
            border-right: 1px solid #e5e5e7;
        }
        .projects { grid-area: projects; }
        .issues   { grid-area: issues; }
        .thread   {
            grid-area: thread; border-right: none;
            display: flex; flex-direction: column;
            background: #fafafa;
        }
        .col-header {
            position: sticky;
            top: 0;
            background: white;
            padding: 14px 16px 10px;
            border-bottom: 1px solid #f0f0f2;
            display: flex;
            align-items: center;
            justify-content: space-between;
            font-weight: 600;
            font-size: 11px;
            color: #8e8e93;
            text-transform: uppercase;
            letter-spacing: 0.06em;
        }
        .add-btn {
            background: transparent;
            border: none;
            cursor: pointer;
            font-size: 20px;
            line-height: 1;
            padding: 0 4px;
            color: #007aff;
            font-weight: 300;
        }
        .add-btn:hover { color: #0051d5; }
        .add-btn[disabled] { color: #c7c7cc; cursor: not-allowed; }
        .list-item {
            padding: 12px 16px;
            cursor: pointer;
            border-bottom: 1px solid #f5f5f7;
            display: flex;
            gap: 10px;
            align-items: flex-start;
            transition: background 80ms;
        }
        .list-item:hover { background: #fafafa; }
        .list-item.selected { background: #e8f1ff; }
        .list-item .item-title { flex: 1; font-weight: 500; color: #1c1c1e; }
        .list-item.selected .item-title { color: #0051d5; }
        .item-meta { font-size: 12px; color: #8e8e93; margin-top: 2px; }
        .badge {
            background: #ff3b30;
            color: white;
            border-radius: 10px;
            padding: 2px 8px;
            font-size: 11px;
            font-weight: 600;
            min-width: 20px;
            text-align: center;
            line-height: 1.4;
        }
        .status-dot {
            width: 8px; height: 8px;
            border-radius: 50%;
            margin-top: 7px;
            flex-shrink: 0;
        }
        .status-dot.open, .status-dot.active, .status-dot.idea { background: #34c759; }
        .status-dot.closed, .status-dot.done { background: #c7c7cc; }
        .empty {
            color: #8e8e93;
            padding: 60px 40px;
            text-align: center;
            font-size: 14px;
        }
        .thread-header {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 20px 28px 16px;
            border-bottom: 1px solid #e5e5e7;
            background: white;
        }
        .thread-title { font-size: 18px; font-weight: 600; flex: 1; }
        .thread-body {
            flex: 1;
            overflow-y: auto;
            padding: 20px 28px;
        }
        .comment {
            padding: 12px 14px;
            margin-bottom: 12px;
            border-radius: 12px;
            max-width: 78%;
            word-wrap: break-word;
        }
        .comment.daniel  { background: #007aff; color: white; margin-left: auto; }
        .comment.claude  { background: white; border: 1px solid #e5e5e7; }
        .comment.unread  { box-shadow: 0 0 0 2px #ff3b30; }
        .comment-meta {
            font-size: 11px;
            opacity: 0.7;
            margin-bottom: 4px;
            text-transform: lowercase;
        }
        .comment-text { white-space: pre-wrap; }
        .composer {
            background: white;
            border-top: 1px solid #e5e5e7;
            padding: 14px 28px 20px;
        }
        .composer textarea {
            width: 100%;
            min-height: 64px;
            border: 1px solid #d1d1d3;
            border-radius: 8px;
            padding: 10px 12px;
            font-family: inherit;
            font-size: 14px;
            line-height: 1.5;
            resize: vertical;
            color: #1c1c1e;
        }
        .composer textarea:focus { outline: 2px solid #007aff; border-color: transparent; }
        .composer-actions {
            display: flex;
            gap: 8px;
            margin-top: 10px;
            align-items: center;
        }
        .composer-actions .spacer { flex: 1; }
        .btn {
            background: #007aff;
            color: white;
            border: none;
            border-radius: 6px;
            padding: 7px 16px;
            cursor: pointer;
            font-size: 13px;
            font-weight: 500;
            transition: opacity 80ms;
        }
        .btn:hover { opacity: 0.9; }
        .btn.secondary {
            background: white;
            color: #007aff;
            border: 1px solid #007aff;
        }
        .btn.secondary:hover { background: #e8f1ff; }
        .btn.recording {
            background: #ff3b30;
            color: white;
            border-color: transparent;
            animation: pulse 1s ease-in-out infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50%      { opacity: 0.6; }
        }
        .dialog-backdrop {
            position: fixed;
            inset: 0;
            background: rgba(0, 0, 0, 0.35);
            display: none;
            align-items: center;
            justify-content: center;
            z-index: 100;
        }
        .dialog-backdrop.open { display: flex; }
        .dialog {
            background: white;
            border-radius: 14px;
            padding: 22px 26px;
            width: 460px;
            max-width: 90vw;
            box-shadow: 0 20px 60px rgba(0,0,0,0.2);
        }
        .dialog h3 { margin: 0 0 14px; font-size: 16px; }
        .dialog input, .dialog textarea {
            width: 100%;
            border: 1px solid #d1d1d3;
            border-radius: 6px;
            padding: 9px 11px;
            font-size: 14px;
            margin-bottom: 10px;
            font-family: inherit;
        }
        .dialog input:focus, .dialog textarea:focus {
            outline: 2px solid #007aff; border-color: transparent;
        }
        .dialog-actions {
            display: flex;
            justify-content: flex-end;
            gap: 8px;
            margin-top: 6px;
        }
        .dialog-actions .spacer { flex: 1; }
        .toast {
            position: fixed;
            bottom: 24px;
            left: 50%;
            transform: translateX(-50%);
            background: #1c1c1e;
            color: white;
            padding: 10px 18px;
            border-radius: 8px;
            font-size: 13px;
            opacity: 0;
            transition: opacity 200ms;
            pointer-events: none;
            z-index: 200;
        }
        .toast.show { opacity: 0.95; }
        </style>
        </head>
        <body>
        <div class="app">
            <header>
                <span class="title">Tracker</span>
                <span class="subtitle" id="status">lädt…</span>
                <button class="refresh-btn" onclick="loadState()">↻ Refresh</button>
            </header>
            <div class="col projects">
                <div class="col-header">
                    Projects
                    <button class="add-btn" title="Neues Projekt" onclick="openNewProjectDialog()">+</button>
                </div>
                <div id="project-list"></div>
            </div>
            <div class="col issues">
                <div class="col-header">
                    Issues
                    <button class="add-btn" id="add-issue-btn" title="Neues Issue"
                            onclick="openNewIssueDialog()" disabled>+</button>
                </div>
                <div id="issue-list"></div>
            </div>
            <div class="thread" id="thread">
                <div class="empty">Wähle ein Issue aus, oder lege ein neues an.</div>
            </div>
        </div>

        <div class="dialog-backdrop" id="new-project-dialog">
            <div class="dialog">
                <h3>Neues Projekt</h3>
                <input id="np-title" placeholder="Titel" />
                <textarea id="np-desc" placeholder="Beschreibung (optional)" rows="4"></textarea>
                <div class="dialog-actions">
                    <button class="btn secondary" id="np-voice">🎤 Voice</button>
                    <span class="spacer"></span>
                    <button class="btn secondary" onclick="closeDialog('new-project-dialog')">Abbrechen</button>
                    <button class="btn" onclick="createProject()">Anlegen</button>
                </div>
            </div>
        </div>

        <div class="dialog-backdrop" id="new-issue-dialog">
            <div class="dialog">
                <h3>Neues Issue</h3>
                <input id="ni-title" placeholder="Titel" />
                <div class="dialog-actions">
                    <button class="btn secondary" id="ni-voice">🎤 Voice</button>
                    <span class="spacer"></span>
                    <button class="btn secondary" onclick="closeDialog('new-issue-dialog')">Abbrechen</button>
                    <button class="btn" onclick="createIssue()">Anlegen</button>
                </div>
            </div>
        </div>

        <div class="toast" id="toast"></div>

        <script>
        let state = { projects: [] };
        let selectedProjectId = null;
        let selectedIssueId = null;

        function $(id) { return document.getElementById(id); }
        function escapeHtml(s) {
            return (s || '').replace(/[&<>"']/g, c =>
                ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
        }
        function formatTs(ts) {
            if (!ts) return '';
            const d = new Date(ts);
            return d.toLocaleString('de-DE',
                { hour:'2-digit', minute:'2-digit', day:'2-digit', month:'short' });
        }
        function newId(prefix) {
            const now = new Date().toISOString().slice(0,10);
            const rand = Math.random().toString(36).slice(2,7);
            return prefix + '-' + now + '-' + rand;
        }
        function toast(msg) {
            const t = $('toast');
            t.textContent = msg;
            t.classList.add('show');
            clearTimeout(t._h);
            t._h = setTimeout(() => t.classList.remove('show'), 2500);
        }

        async function loadState() {
            $('status').textContent = 'lädt…';
            try {
                const res = await fetch('/state.json', { cache: 'no-store' });
                state = await res.json();
                if (!state.projects) state.projects = [];
                $('status').textContent = '';
                render();
            } catch (e) {
                $('status').textContent = 'Fehler beim Laden: ' + e.message;
            }
        }

        async function saveState() {
            try {
                const res = await fetch('/state.json', {
                    method: 'PUT',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(state, null, 2)
                });
                if (!res.ok) throw new Error('HTTP ' + res.status);
            } catch (e) {
                toast('Speichern fehlgeschlagen: ' + e.message);
                throw e;
            }
        }

        function notify(event, title, preview) {
            fetch('/notify', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ event, title: title || '', preview: preview || '' })
            }).catch(() => {});
        }

        function render() {
            renderProjects();
            renderIssues();
            renderThread();
        }

        function unreadCountForIssue(issue) {
            return (issue.comments || [])
                .filter(c => c.author !== 'daniel' && !c.read_by_daniel).length;
        }
        function unreadCountForProject(p) {
            return (p.issues || []).reduce((n, i) => n + unreadCountForIssue(i), 0);
        }

        function renderProjects() {
            const list = $('project-list');
            list.innerHTML = '';
            for (const p of state.projects) {
                const unread = unreadCountForProject(p);
                const div = document.createElement('div');
                div.className = 'list-item' + (p.id === selectedProjectId ? ' selected' : '');
                div.innerHTML = `
                    <div class="status-dot ${p.status || 'idea'}"></div>
                    <div style="flex:1">
                        <div class="item-title">${escapeHtml(p.title)}</div>
                        <div class="item-meta">${(p.issues || []).length} issues · ${p.status || 'idea'}</div>
                    </div>
                    ${unread > 0 ? `<div class="badge">${unread}</div>` : ''}
                `;
                div.onclick = () => {
                    selectedProjectId = p.id;
                    selectedIssueId = null;
                    render();
                };
                list.appendChild(div);
            }
        }

        function renderIssues() {
            const list = $('issue-list');
            list.innerHTML = '';
            $('add-issue-btn').disabled = !selectedProjectId;
            if (!selectedProjectId) return;
            const project = state.projects.find(p => p.id === selectedProjectId);
            if (!project) return;
            for (const issue of (project.issues || [])) {
                const unread = unreadCountForIssue(issue);
                const div = document.createElement('div');
                div.className = 'list-item' + (issue.id === selectedIssueId ? ' selected' : '');
                div.innerHTML = `
                    <div class="status-dot ${issue.status || 'open'}"></div>
                    <div style="flex:1">
                        <div class="item-title">${escapeHtml(issue.title)}</div>
                        <div class="item-meta">${(issue.comments || []).length} comments · ${issue.status || 'open'}</div>
                    </div>
                    ${unread > 0 ? `<div class="badge">${unread}</div>` : ''}
                `;
                div.onclick = async () => {
                    selectedIssueId = issue.id;
                    if (await markIssueRead(issue)) {
                        await saveState();
                    }
                    render();
                };
                list.appendChild(div);
            }
        }

        function renderThread() {
            const thread = $('thread');
            if (!selectedIssueId) {
                thread.innerHTML = '<div class="empty">Wähle ein Issue aus, oder lege ein neues an.</div>';
                return;
            }
            const project = state.projects.find(p => p.id === selectedProjectId);
            const issue = project && project.issues && project.issues.find(i => i.id === selectedIssueId);
            if (!issue) {
                thread.innerHTML = '<div class="empty">Issue nicht gefunden.</div>';
                return;
            }
            const desc = project.description ? `<div class="item-meta" style="margin-top:2px">in ${escapeHtml(project.title)}</div>` : '';
            thread.innerHTML = `
                <div class="thread-header">
                    <div style="flex:1">
                        <div class="thread-title">${escapeHtml(issue.title)}</div>
                        ${desc}
                    </div>
                    <button class="btn secondary" onclick="toggleIssueStatus()">
                        ${issue.status === 'open' ? 'Schließen' : 'Wieder öffnen'}
                    </button>
                </div>
                <div class="thread-body" id="comments"></div>
                <div class="composer">
                    <textarea id="composer-text" placeholder="Kommentar schreiben…"></textarea>
                    <div class="composer-actions">
                        <button class="btn secondary" id="composer-voice">🎤 Voice</button>
                        <span class="spacer"></span>
                        <button class="btn" onclick="addComment()">Senden</button>
                    </div>
                </div>
            `;
            const comments = $('comments');
            for (const c of (issue.comments || [])) {
                const div = document.createElement('div');
                const isUnread = c.author !== 'daniel' && !c.read_by_daniel;
                div.className = 'comment ' + (c.author === 'daniel' ? 'daniel' : 'claude')
                    + (isUnread ? ' unread' : '');
                div.innerHTML = `
                    <div class="comment-meta">${escapeHtml(c.author)} · ${formatTs(c.ts)}</div>
                    <div class="comment-text">${escapeHtml(c.text)}</div>
                `;
                comments.appendChild(div);
            }
            // Auto-scroll to bottom of thread
            comments.scrollTop = comments.scrollHeight;
            setupVoiceButton($('composer-voice'), txt => {
                const ta = $('composer-text');
                ta.value = (ta.value ? ta.value + ' ' : '') + txt;
                ta.focus();
            });
        }

        async function markIssueRead(issue) {
            let changed = false;
            for (const c of (issue.comments || [])) {
                if (c.author !== 'daniel' && !c.read_by_daniel) {
                    c.read_by_daniel = true;
                    changed = true;
                }
            }
            return changed;
        }

        function openNewProjectDialog() {
            $('np-title').value = '';
            $('np-desc').value = '';
            $('new-project-dialog').classList.add('open');
            $('np-title').focus();
            setupVoiceButton($('np-voice'), txt => {
                const t = $('np-title');
                const d = $('np-desc');
                if (!t.value) {
                    const lines = txt.split(/\\n+/);
                    t.value = lines[0];
                    if (lines.length > 1) d.value = lines.slice(1).join('\\n').trim();
                } else {
                    d.value = (d.value ? d.value + ' ' : '') + txt;
                }
            });
        }

        function openNewIssueDialog() {
            if (!selectedProjectId) return;
            $('ni-title').value = '';
            $('new-issue-dialog').classList.add('open');
            $('ni-title').focus();
            setupVoiceButton($('ni-voice'), txt => {
                const t = $('ni-title');
                t.value = (t.value ? t.value + ' ' : '') + txt.split(/\\n+/)[0];
            });
        }

        function closeDialog(id) {
            $(id).classList.remove('open');
        }

        async function createProject() {
            const title = $('np-title').value.trim();
            if (!title) return;
            const desc = $('np-desc').value.trim();
            const now = new Date().toISOString();
            const id = newId('p');
            state.projects.push({
                id, title, description: desc,
                status: 'idea', tags: [],
                created: now, updated: now,
                issues: []
            });
            selectedProjectId = id;
            selectedIssueId = null;
            closeDialog('new-project-dialog');
            render();
            await saveState();
            notify('project', title, title);
        }

        async function createIssue() {
            const title = $('ni-title').value.trim();
            if (!title) return;
            const project = state.projects.find(p => p.id === selectedProjectId);
            if (!project) return;
            const now = new Date().toISOString();
            const id = newId('i');
            project.issues = project.issues || [];
            project.issues.push({
                id, title, status: 'open', tags: [],
                created: now, updated: now,
                comments: []
            });
            project.updated = now;
            selectedIssueId = id;
            closeDialog('new-issue-dialog');
            render();
            await saveState();
            notify('issue', title, title);
        }

        async function addComment() {
            const text = $('composer-text').value.trim();
            if (!text) return;
            const project = state.projects.find(p => p.id === selectedProjectId);
            const issue = project.issues.find(i => i.id === selectedIssueId);
            const now = new Date().toISOString();
            issue.comments = issue.comments || [];
            issue.comments.push({
                author: 'daniel', ts: now, text,
                read_by_daniel: true
            });
            issue.updated = now;
            project.updated = now;
            $('composer-text').value = '';
            render();
            await saveState();
            notify('comment', issue.title, text.slice(0, 140));
        }

        async function toggleIssueStatus() {
            const project = state.projects.find(p => p.id === selectedProjectId);
            const issue = project.issues.find(i => i.id === selectedIssueId);
            issue.status = issue.status === 'open' ? 'closed' : 'open';
            issue.updated = new Date().toISOString();
            project.updated = issue.updated;
            render();
            await saveState();
            notify('status', issue.title + ' → ' + issue.status, '');
        }

        // ----- Voice recording (MediaRecorder → /transcribe) -----

        function setupVoiceButton(btn, onTranscript) {
            if (!btn) return;
            btn.onclick = async () => {
                if (btn.dataset.recording === '1') {
                    btn._recorder && btn._recorder.stop();
                    return;
                }
                try {
                    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
                    const mime = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm'
                        : (MediaRecorder.isTypeSupported('audio/ogg') ? 'audio/ogg' : '');
                    const recorder = mime ? new MediaRecorder(stream, { mimeType: mime })
                                          : new MediaRecorder(stream);
                    const chunks = [];
                    recorder.ondataavailable = e => { if (e.data && e.data.size) chunks.push(e.data); };
                    recorder.onstop = async () => {
                        stream.getTracks().forEach(t => t.stop());
                        btn.classList.remove('recording');
                        btn.dataset.recording = '';
                        const prevText = btn.textContent;
                        btn.textContent = '⏳';
                        btn.disabled = true;
                        const blob = new Blob(chunks, { type: recorder.mimeType || 'audio/webm' });
                        try {
                            const res = await fetch('/transcribe', {
                                method: 'POST',
                                headers: { 'Content-Type': blob.type || 'audio/webm' },
                                body: blob
                            });
                            if (!res.ok) {
                                const err = await res.text();
                                toast('Transkription fehlgeschlagen: ' + err.slice(0, 200));
                            } else {
                                const text = (await res.text()).trim();
                                if (text) onTranscript(text);
                            }
                        } catch (e) {
                            toast('Transkription fehlgeschlagen: ' + e.message);
                        } finally {
                            btn.textContent = prevText;
                            btn.disabled = false;
                        }
                    };
                    recorder.start();
                    btn._recorder = recorder;
                    btn.classList.add('recording');
                    btn.dataset.recording = '1';
                    btn.textContent = '⏹ Stop';
                } catch (e) {
                    toast('Mikrofon nicht verfügbar: ' + e.message);
                }
            };
        }

        loadState();
        </script>
        </body>
        </html>
        """;
}
