///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.regex.*;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.Timer;

/**
 * Voice-to-text transcription using whisper.cpp.
 * Inspired by the Windows 11 Voice Typing floating widget.
 *
 * Hold the mic button (or hold the shortcut key) to record, release to transcribe.
 * Result is copied to clipboard and pasted via ydotool (Ctrl+Shift+V).
 *
 * Non-focusable: never steals keyboard focus from the target app.
 *
 * Dependencies:
 *   - whisper.cpp   Build from https://github.com/ggerganov/whisper.cpp
 *                   Expects ~/whisper.cpp/build/bin/whisper-server and a model in
 *                   ~/whisper.cpp/models/ (override paths via system properties below).
 *                   If whisper-server is already running on whisper.port, it is used as-is.
 *   - wl-copy       Wayland clipboard tool (package: wl-clipboard).
 *   - ydotool       Kernel-level input simulation for auto-paste on Wayland.
 *                   Requires ydotoold running with a user-accessible socket.
 *                   See ydotoold setup notes below.
 *   - /dev/input    Global shortcut reads evdev events. Requires 'input' group:
 *                     sudo usermod -aG input $USER   (then log out/in)
 *
 * ydotoold setup (systemd override for user-accessible socket):
 *   sudo systemctl edit ydotoold
 *   ────────────────────────────────
 *   [Service]
 *   RuntimeDirectory=ydotoold
 *   ExecStart=
 *   ExecStart=/usr/local/bin/ydotoold --socket-path=/run/ydotoold/socket --socket-own=1000:1000
 *   ────────────────────────────────
 *   sudo systemctl restart ydotoold
 *
 * System properties:
 *   -Dwhisper.shortcut=F8   Global shortcut key (default: F8). Requires /dev/input access.
 *   -Dwhisper.server=...    Path to whisper-server binary.
 *   -Dwhisper.model=...     Path to whisper model file.
 *   -Dwhisper.lang=en       Language code (default: auto-detect).
 *   -Dwhisper.port=8080     whisper-server port (default: 8080).
 *   -Dwhisper.keywords=...  Path to a text file with one keyword per line; biases
 *                           whisper toward those terms.
 *                           Default: ~/.config/whisper-transcribe/keywords.txt
 *                           Lines starting with '#' are treated as comments.
 *
 * Example:
 *   jbang -Dwhisper.lang=de -Dwhisper.model=/home/daniel/whisper.cpp/models/ggml-large-v3.bin src/WhisperTranscribe.java
 */
public class WhisperTranscribe {

    static final String WHISPER_SERVER_BIN = System.getProperty("whisper.server",
            System.getProperty("user.home") + "/whisper.cpp/build/bin/whisper-server");
    static final String WHISPER_MODEL = System.getProperty("whisper.model",
            System.getProperty("user.home") + "/whisper.cpp/models/ggml-base.en.bin");
    static final String WHISPER_LANG = System.getProperty("whisper.lang", "auto");
    static final int    WHISPER_PORT = Integer.getInteger("whisper.port", 9898);
    static final String SHORTCUT_KEY = System.getProperty("whisper.shortcut", "F8");
    static final String KEYWORDS_FILE = System.getProperty("whisper.keywords",
            System.getProperty("user.home") + "/.config/whisper-transcribe/keywords.txt");
    static String keywordsPrompt; // loaded at startup from KEYWORDS_FILE

    // Colors — Fluent-inspired dark theme
    static final Color BG = new Color(44, 44, 44);
    static final Color BG_HOVER = new Color(55, 55, 55);
    static final Color ACCENT = new Color(0, 120, 212);
    static final Color ACCENT_RECORDING = new Color(232, 17, 35);
    static final Color TEXT_PRIMARY = new Color(255, 255, 255);
    static final Color TEXT_SECONDARY = new Color(160, 160, 160);
    static final Color SUCCESS = new Color(16, 185, 129);
    static final Color SURFACE = new Color(56, 56, 56);

    private volatile boolean recording = false;
    private boolean hasYdotool = false;
    private boolean hasWlCopy = false;
    private TargetDataLine microphone;
    private ByteArrayOutputStream audioBuffer;
    private Thread recordThread;
    private MicWidget micWidget;
    private Timer pulseTimer;
    private float pulsePhase = 0f;
    private JWindow frame;

    private Process serverProcess;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    enum State { IDLE, RECORDING, TRANSCRIBING, DONE, ERROR }
    private State state = State.IDLE;

    public static void main(String[] args) {
        WhisperTranscribe app = new WhisperTranscribe();
        if (!app.checkDependencies()) System.exit(1);
        loadKeywords();
        if (!app.startWhisperServer()) System.exit(1);
        SwingUtilities.invokeLater(app::createUI);
    }

    private static void loadKeywords() {
        Path p = Path.of(KEYWORDS_FILE);
        if (!Files.isReadable(p)) {
            System.out.println("  keywords:         (no file at " + KEYWORDS_FILE + ")");
            return;
        }
        try {
            var lines = Files.readAllLines(p).stream()
                    .map(String::strip)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .toList();
            if (lines.isEmpty()) return;
            keywordsPrompt = String.join(", ", lines);
            System.out.println("  keywords:         " + lines.size() + " loaded from " + KEYWORDS_FILE);
        } catch (IOException e) {
            System.err.println("Could not read keywords file: " + e.getMessage());
        }
    }

    private boolean checkDependencies() {
        // Mandatory: wl-copy (Wayland clipboard)
        hasWlCopy = isCommandAvailable("wl-copy");
        if (!hasWlCopy) {
            System.err.println("ERROR: wl-copy not found. Install wl-clipboard package.");
            return false;
        }

        // Optional: ydotool (auto-paste)
        hasYdotool = isCommandAvailable("ydotool");
        if (!hasYdotool) {
            System.err.println("WARNING: ydotool not found. Auto-paste disabled; text will be copied to clipboard only.");
        }

        return true;
    }

    private boolean startWhisperServer() {
        if (isServerReady()) {
            System.out.println("  using existing whisper-server on port " + WHISPER_PORT);
            return true;
        }

        if (!Files.isExecutable(Path.of(WHISPER_SERVER_BIN))) {
            System.err.println("ERROR: whisper-server not found: " + WHISPER_SERVER_BIN);
            System.err.println("       Build from https://github.com/ggerganov/whisper.cpp");
            System.err.println("       Or set -Dwhisper.server=/path/to/whisper-server");
            return false;
        }
        if (!Files.isReadable(Path.of(WHISPER_MODEL))) {
            System.err.println("ERROR: whisper model not found: " + WHISPER_MODEL);
            System.err.println("       Download a model into ~/whisper.cpp/models/");
            System.err.println("       Or set -Dwhisper.model=/path/to/model.bin");
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    WHISPER_SERVER_BIN,
                    "--model", WHISPER_MODEL,
                    "--port", String.valueOf(WHISPER_PORT),
                    "--host", "127.0.0.1"
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            serverProcess = pb.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (serverProcess != null && serverProcess.isAlive()) serverProcess.destroy();
            }));

            System.out.print("  starting whisper-server (loading model)...");
            System.out.flush();
            for (int i = 0; i < 60; i++) {
                Thread.sleep(500);
                if (!serverProcess.isAlive()) {
                    System.out.println(" FAILED (process exited with " + serverProcess.exitValue() + ")");
                    return false;
                }
                if (isServerReady()) {
                    System.out.println(" ready");
                    return true;
                }
                if (i % 4 == 3) { System.out.print("."); System.out.flush(); }
            }
            System.out.println(" TIMEOUT");
            return false;

        } catch (Exception e) {
            System.err.println("ERROR starting whisper-server: " + e.getMessage());
            return false;
        }
    }

    private boolean isServerReady() {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + WHISPER_PORT + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    private static boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private void createUI() {
        frame = new JWindow();
        frame.setAlwaysOnTop(true);
        frame.setFocusableWindowState(false);
        frame.setType(Window.Type.POPUP);

        // Single widget that paints pill background + mic button in one pass
        micWidget = new MicWidget();
        micWidget.setToolTipText("Hold to record, release to transcribe (" + SHORTCUT_KEY + ")");

        // Hold-to-record on mouse
        micWidget.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (state == State.IDLE || state == State.DONE || state == State.ERROR)
                    startRecording();
            }
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (state == State.RECORDING)
                    stopRecordingAndTranscribe();
            }
        });

        // Dragging
        final Point[] dragOffset = {null};
        micWidget.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragOffset[0] = e.getPoint(); }
        });
        micWidget.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (dragOffset[0] != null) {
                    Point loc = frame.getLocation();
                    frame.setLocation(loc.x + e.getX() - dragOffset[0].x,
                                      loc.y + e.getY() - dragOffset[0].y);
                }
            }
        });

        // Pulse animation timer
        pulseTimer = new Timer(40, e -> {
            pulsePhase += 0.12f;
            if (pulsePhase > (float)(2 * Math.PI)) pulsePhase -= (float)(2 * Math.PI);
            micWidget.repaint();
        });

        frame.setContentPane(micWidget);
        frame.pack();

        // Clip window to rounded shape (no alpha transparency needed — avoids flicker)
        int w = frame.getWidth(), h = frame.getHeight();
        frame.setShape(new RoundRectangle2D.Float(0, 0, w, h, 26, 26));

        // Position bottom-right
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration());
        frame.setLocation(screen.width - frame.getWidth() - insets.right - 40,
                          screen.height - frame.getHeight() - insets.bottom - 40);
        frame.setVisible(true);

        System.out.println("Whisper Transcribe ready.");
        System.out.println("  whisper.server:   " + WHISPER_SERVER_BIN);
        System.out.println("  whisper.model:    " + WHISPER_MODEL);
        System.out.println("  whisper.lang:     " + WHISPER_LANG);
        System.out.println("  whisper.port:     " + WHISPER_PORT);
        System.out.println("  whisper.shortcut: " + SHORTCUT_KEY);

        startGlobalShortcutListener();
    }

    // ── Single widget: pill background + mic button in one paint pass ──
    class MicWidget extends JPanel {
        private static final int BTN = 34;
        private static final int PAD = 12;
        private static final int SIZE = BTN + PAD * 2;
        private boolean hovered = false;

        MicWidget() {
            setOpaque(true);
            setBackground(BG);
            setPreferredSize(new Dimension(SIZE, SIZE));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setDoubleBuffered(true);
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth() / 2, cy = getHeight() / 2;

            // Pulse ring when recording
            if (state == State.RECORDING) {
                float scale = 1.0f + 0.3f * (float) Math.sin(pulsePhase);
                int pr = (int)(BTN + PAD * scale);
                g2.setColor(new Color(232, 17, 35, 50));
                g2.fillOval(cx - pr / 2, cy - pr / 2, pr, pr);
            }

            // Circle background
            Color bg = switch (state) {
                case RECORDING -> ACCENT_RECORDING;
                case TRANSCRIBING -> ACCENT;
                default -> hovered ? BG_HOVER : SURFACE;
            };
            g2.setColor(bg);
            g2.fillOval(cx - BTN / 2, cy - BTN / 2, BTN, BTN);

            // Mic icon or spinner
            g2.setColor(TEXT_PRIMARY);
            if (state == State.TRANSCRIBING) {
                drawSpinner(g2, cx, cy);
            } else {
                drawMicIcon(g2, cx, cy);
            }
            g2.dispose();
        }

        private void drawMicIcon(Graphics2D g2, int cx, int cy) {
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.fillRoundRect(cx - 3, cy - 8, 6, 12, 4, 4);
            g2.drawArc(cx - 7, cy - 5, 14, 14, 0, -180);
            g2.drawLine(cx, cy + 9, cx, cy + 12);
        }

        private void drawSpinner(Graphics2D g2, int cx, int cy) {
            for (int i = 0; i < 3; i++) {
                float alpha = 0.3f + 0.7f * (float)(0.5 + 0.5 * Math.sin(pulsePhase + i * 0.8));
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.fillOval(cx - 10 + i * 8, cy - 2, 4, 4);
            }
            g2.setComposite(AlphaComposite.SrcOver);
        }
    }

    // ── Recording logic ────────────────────────────────────────────

    private void startRecording() {
        if (recording) return;
        recording = true;
        state = State.RECORDING;
        setStatus("Listening...", TEXT_PRIMARY);
        pulseTimer.start();
        micWidget.repaint();

        try {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                setStatus("Mic not supported", ACCENT_RECORDING);
                state = State.ERROR;
                recording = false;
                pulseTimer.stop();
                return;
            }
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();
            audioBuffer = new ByteArrayOutputStream();
            recordThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (recording) {
                    int read = microphone.read(buf, 0, buf.length);
                    if (read > 0) audioBuffer.write(buf, 0, read);
                }
            }, "audio-recorder");
            recordThread.start();
        } catch (LineUnavailableException ex) {
            setStatus("Mic error", ACCENT_RECORDING);
            state = State.ERROR;
            recording = false;
            pulseTimer.stop();
        }
    }

    private void stopRecordingAndTranscribe() {
        if (!recording) return;
        recording = false;
        pulseTimer.stop();

        if (microphone != null) { microphone.stop(); microphone.close(); }
        if (recordThread != null) {
            try { recordThread.join(1000); } catch (InterruptedException ignored) {}
        }

        byte[] audioData = audioBuffer.toByteArray();
        if (audioData.length < 3200) {
            state = State.IDLE;
            setStatus("Too short — try again", TEXT_SECONDARY);
            micWidget.repaint();
            return;
        }

        state = State.TRANSCRIBING;
        setStatus("Transcribing...", ACCENT);
        pulseTimer.start();
        micWidget.repaint();

        new Thread(() -> {
            try {
                Path wavFile = Files.createTempFile("whisper_", ".wav");
                writeWav(wavFile, audioData, new AudioFormat(16000, 16, 1, true, false));
                String raw = runWhisper(wavFile);
                String text = postProcess(raw);
                System.out.println("Raw whisper response: " + raw.replace("\n", "\\n"));
                Files.deleteIfExists(wavFile);

                if (text.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        state = State.IDLE;
                        setStatus("No speech detected", TEXT_SECONDARY);
                        pulseTimer.stop();
                        micWidget.repaint();
                    });
                    return;
                }

                copyToClipboard(text);
                pasteViaYdotool();

                final String result = text;
                SwingUtilities.invokeLater(() -> {
                    state = State.DONE;
                    setStatus("✓ " + truncate(result, 35), SUCCESS);
                    pulseTimer.stop();
                    micWidget.repaint();
                });
                System.out.println("Transcribed: " + result);

            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    state = State.ERROR;
                    setStatus("Error: " + ex.getMessage(), ACCENT_RECORDING);
                    pulseTimer.stop();
                    micWidget.repaint();
                });
            }
        }, "transcriber").start();
    }

    // ── Post-processing ────────────────────────────────────────────

    // Collapses whisper's per-segment newlines into spaces, then turns spoken
    // commands ("new line", "neue Zeile", ...) into actual line breaks.
    private static String postProcess(String raw) {
        // Whisper occasionally splits a word across two segments with no leading space
        // on the second segment ("F\nische" → "Fische"). Drop those newlines without
        // inserting a space; only "\n" followed by whitespace is a real segment break.
        String t = raw.replaceAll("\\n(?!\\s)", "");
        t = t.replaceAll("\\s+", " ").strip();
        t = t.replaceAll("(?i)\\s*[,.!?]?\\s*\\b(new\\s*paragraph|neuer\\s+absatz)\\b[,.!?]?\\s*", "\n\n");
        t = t.replaceAll("(?i)\\s*[,.!?]?\\s*\\b(new\\s*line|line\\s*break|newline|neue\\s+zeile|zeilenumbruch)\\b[,.!?]?\\s*", "\n");
        return t.strip();
    }

    // ── Whisper integration (HTTP) ─────────────────────────────────

    private String runWhisper(Path wavFile) throws Exception {
        String boundary = "Boundary" + System.currentTimeMillis();
        byte[] body = buildMultipart(boundary, wavFile);

        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + WHISPER_PORT + "/inference"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new RuntimeException("whisper-server " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    private byte[] buildMultipart(String boundary, Path wavFile) throws IOException {
        byte[] fileBytes = Files.readAllBytes(wavFile);
        var out = new ByteArrayOutputStream();

        // audio file part
        appendStr(out, "--" + boundary + "\r\n");
        appendStr(out, "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n");
        appendStr(out, "Content-Type: audio/wav\r\n\r\n");
        out.write(fileBytes);
        appendStr(out, "\r\n");

        // response format: plain text (no timestamps, no JSON overhead)
        appendStr(out, "--" + boundary + "\r\n");
        appendStr(out, "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n");
        appendStr(out, "text\r\n");

        // language: "auto" tells whisper-server to detect; omitting would default to "en"
        appendStr(out, "--" + boundary + "\r\n");
        appendStr(out, "Content-Disposition: form-data; name=\"language\"\r\n\r\n");
        appendStr(out, WHISPER_LANG + "\r\n");

        // initial prompt biases the decoder toward user-supplied keywords
        if (keywordsPrompt != null && !keywordsPrompt.isEmpty()) {
            appendStr(out, "--" + boundary + "\r\n");
            appendStr(out, "Content-Disposition: form-data; name=\"prompt\"\r\n\r\n");
            appendStr(out, keywordsPrompt + "\r\n");
        }

        appendStr(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void appendStr(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private void writeWav(Path path, byte[] pcmData, AudioFormat format) throws IOException {
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcmData), format, pcmData.length / format.getFrameSize())) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    // ── Clipboard & paste ──────────────────────────────────────────

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        if (hasWlCopy) {
            try {
                Process p = new ProcessBuilder("wl-copy", "--", text).start();
                p.waitFor();
            } catch (Exception e) {
                System.err.println("wl-copy failed: " + e.getMessage());
            }
        }
    }

    private void pasteViaYdotool() {
        if (!hasYdotool) return;
        try {
            Thread.sleep(500);
            // Ctrl+Shift+V for terminal paste (gnome-terminal)
            ProcessBuilder pb = new ProcessBuilder("ydotool", "key",
                    "29:1", "42:1", "47:1", "47:0", "42:0", "29:0");
            pb.environment().put("YDOTOOL_SOCKET", "/run/ydotoold/socket");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                output = r.lines().reduce("", (a, b) -> a + b);
            }
            int rc = p.waitFor();
            if (rc != 0) System.err.println("ydotool failed (rc=" + rc + "): " + output);
        } catch (Exception e) {
            System.err.println("Could not paste via ydotool: " + e.getMessage());
        }
    }

    // ── Global shortcut via evdev ─────────────────────────────────

    private void startGlobalShortcutListener() {
        int keyCode = resolveEvdevKeyCode(SHORTCUT_KEY);
        if (keyCode < 0) {
            System.err.println("Unknown shortcut key: " + SHORTCUT_KEY);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                java.util.List<Path> keyboards = findKeyboardDevices();
                if (keyboards.isEmpty()) {
                    System.err.println("No keyboard devices found for global shortcut.");
                    return;
                }
                System.out.println("  listening on " + keyboards.size() + " input device(s)");
                for (Path dev : keyboards) {
                    Thread dt = new Thread(() -> listenEvdev(dev, keyCode), "evdev-" + dev.getFileName());
                    dt.setDaemon(true);
                    dt.start();
                }
            } catch (Exception e) {
                System.err.println("Global shortcut not available: " + e.getMessage());
            }
        }, "shortcut-init");
        t.setDaemon(true);
        t.start();
    }

    private java.util.List<Path> findKeyboardDevices() throws IOException {
        java.util.List<Path> result = new ArrayList<>();
        String content = Files.readString(Path.of("/proc/bus/input/devices"));
        for (String block : content.split("\n\n")) {
            boolean hasKey = false;
            String eventId = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("B: EV=")) {
                    long ev = Long.parseLong(line.substring(6).trim(), 16);
                    hasKey = (ev & 2) != 0;
                }
                if (line.startsWith("H: Handlers=")) {
                    Matcher m = Pattern.compile("event(\\d+)").matcher(line);
                    if (m.find()) eventId = m.group(1);
                }
            }
            if (hasKey && eventId != null) {
                Path dev = Path.of("/dev/input/event" + eventId);
                if (Files.exists(dev) && Files.isReadable(dev)) result.add(dev);
            }
        }
        return result;
    }

    private void listenEvdev(Path device, int targetKeyCode) {
        try (FileInputStream fis = new FileInputStream(device.toFile())) {
            byte[] buf = new byte[24]; // struct input_event on 64-bit
            while (true) {
                if (fis.read(buf) != 24) continue;
                // bytes 16-17: type (LE u16), 18-19: code (LE u16), 20-23: value (LE s32)
                int type  = (buf[16] & 0xFF) | ((buf[17] & 0xFF) << 8);
                int code  = (buf[18] & 0xFF) | ((buf[19] & 0xFF) << 8);
                int value = (buf[20] & 0xFF) | ((buf[21] & 0xFF) << 8)
                          | ((buf[22] & 0xFF) << 16) | ((buf[23] & 0xFF) << 24);
                if (type == 1 && code == targetKeyCode) { // EV_KEY
                    if (value == 1) { // press
                        SwingUtilities.invokeLater(() -> {
                            if (state == State.IDLE || state == State.DONE || state == State.ERROR)
                                startRecording();
                        });
                    } else if (value == 0) { // release
                        SwingUtilities.invokeLater(() -> {
                            if (state == State.RECORDING) stopRecordingAndTranscribe();
                        });
                    }
                }
            }
        } catch (Exception e) {
            // Permission denied or device removed — silently skip
        }
    }

    private static int resolveEvdevKeyCode(String name) {
        return switch (name.toUpperCase()) {
            case "F1" -> 59;  case "F2" -> 60;  case "F3" -> 61;  case "F4" -> 62;
            case "F5" -> 63;  case "F6" -> 64;  case "F7" -> 65;  case "F8" -> 66;
            case "F9" -> 67;  case "F10" -> 68; case "F11" -> 87; case "F12" -> 88;
            case "PAUSE", "BREAK"   -> 119;
            case "SCROLLLOCK"        -> 70;
            case "INSERT"            -> 110;
            case "HOME"              -> 102;
            case "END"               -> 107;
            case "PAGEUP"            -> 104;
            case "PAGEDOWN"          -> 109;
            case "PRINTSCREEN", "SYSRQ" -> 99;
            default -> -1;
        };
    }

    // ── Helpers ────────────────────────────────────────────────────

    private void setStatus(String text, Color color) {
        System.out.println("[whisper] " + text);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
