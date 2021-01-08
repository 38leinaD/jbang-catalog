///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.glassfish.jersey.core:jersey-client:2.26
//DEPS org.glassfish.jersey.inject:jersey-hk2:2.26
//DEPS org.glassfish.jersey.media:jersey-media-json-processing:2.26
//DEPS de.codeshelf.consoleui:consoleui:0.0.13

import de.codeshelf.consoleui.prompt.CheckboxPrompt;
import de.codeshelf.consoleui.prompt.CheckboxResult;
import de.codeshelf.consoleui.prompt.ConsolePrompt;
import de.codeshelf.consoleui.prompt.PromtResultItemIF;
import de.codeshelf.consoleui.prompt.builder.CheckboxPromptBuilder;
import de.codeshelf.consoleui.prompt.builder.PromptBuilder;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.Extension;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.fusesource.jansi.Ansi.ansi;

@Command(name = "QuarkusStarter", mixinStandardHelpOptions = true, version = "QuarkusStarter 0.1",
        description = "QuarkusStarter made with jbang")
class QuarkusStarter implements Callable<Integer> {

    @Parameters(index = "0", description = "name of the project / artifact-id / folder to create. If contains ':', parts are interpreted as G:A:V.")
    private String projectName;

    enum BuildTool {
        maven,
        gradle
    }

    @CommandLine.Option(names = {"--build", "-b"}, defaultValue = "maven")
    private BuildTool buildTool;

    @CommandLine.Option(names = {"--extensions", "-e"}, defaultValue = "resteasy-jackson")
    private List<String> extensions;

    @CommandLine.Option(names = {"--interactive", "-i"})
    private boolean interactive;

    public static void main(String... args) {
        int exitCode = new CommandLine(new QuarkusStarter()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        AnsiConsole.systemInstall();

        Gav gav = Gav.from(projectName, "org.acme", "1.0.0-SNAPSHOT");

        Path targetDir = Paths.get(System.getProperty("user.dir"));
        if (targetDir.resolve(gav.artifactId).toFile().exists()) {
            System.err.println(ansi().render("@|red " + "Folder '" + targetDir + "' already exists." + "|@\n"));
            System.exit(1);
        }

        JsonArray quarkusExtensions = ClientBuilder.newClient()
                .target("https://code.quarkus.io/api/extensions")
                .request()
                .get(JsonArray.class);

        List<Extension> allExtensions = quarkusExtensions.stream()
                .map(JsonValue::asJsonObject)
                .map(Extension::new)
                .sorted(Comparator.comparing(Extension::getName))
                .distinct()
                .collect(Collectors.toList());

        List<Extension> selectedExtensions = extensions.stream()
                .map(extName -> findExtensionByName(allExtensions, extName).orElseThrow(() -> new RuntimeException("Unknown extension: " + extName)))
                .collect(Collectors.toList());

        if (interactive) {
            ConsolePrompt prompt = new ConsolePrompt();

            PromptBuilder promptBuilder = prompt.getPromptBuilder();
            CheckboxPromptBuilder checkboxPrompt = promptBuilder.createCheckboxPrompt()
                    .name("extensions")
                    .message("Pick your extensions");


            List<Extension> finalSelectedExtensions = selectedExtensions;
            allExtensions.stream()
                    .forEach(ext -> {

                        checkboxPrompt
                                .newItem(ext.getShortId())
                                .text(ansi().a(ext.getName()).a(Ansi.Attribute.INTENSITY_FAINT).a(" - " + ext.getDescription()).reset().toString())
                                .checked(finalSelectedExtensions.contains(ext))
                                .add();
                    });
            HashMap<String, ? extends PromtResultItemIF> result = prompt.prompt(checkboxPrompt.pageSize(100).addPrompt().build());

            CheckboxResult checkboxResult = (CheckboxResult)result.get("extensions");
            selectedExtensions = checkboxResult.getSelectedIds().stream()
                    .map(shortId -> findExtensionByShortId(allExtensions, shortId).orElseThrow(() -> new RuntimeException("Unknown extension: " + shortId)))
                    .collect(Collectors.toList());
        }

        String urlEncodedExtensions = selectedExtensions.stream()
                .map(Extension::getShortId)
                .collect(Collectors.joining("."));

        String projectUrl = String.format("https://code.quarkus.io/d?g=%s&a=%s&v=%s&b=%s&s=%s&cn=%s",
                gav.groupId,
                gav.artifactId,
                gav.version,
                buildTool.name().toUpperCase(),
                urlEncodedExtensions,
                "code.quarkus.io");

        File tmpFile = Files.createTempFile("", ".zip").toFile();
        download(new URL(projectUrl), tmpFile);
        unzip(tmpFile, targetDir.toFile());

        System.err.println(ansi().render("@|green " + "Project created in folder '" + targetDir.resolve(gav.artifactId) + "'." + "|@\n"));

        return 0;
    }

    static void download(URL url, File projectZipFile) {
        URLConnection conn = null;
        try {
            conn = url.openConnection();

            BufferedInputStream bis = new BufferedInputStream(conn.getInputStream());

            FileOutputStream fis = new FileOutputStream(projectZipFile);

            byte[] buffer = new byte[1024];
            int count = 0;
            while ((count = bis.read(buffer, 0, 1024)) != -1) {
                fis.write(buffer, 0, count);
            }
            fis.close();
            bis.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn instanceof HttpURLConnection) {
                ((HttpURLConnection) conn).disconnect();
            }
        }
    }

    static void unzip(File zipFile, File targetDir) {
        byte[] buffer = new byte[1024];
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(targetDir, zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    // fix for Windows-created archives
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    // write file content
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    static Optional<Extension> findExtensionByName(List<Extension> extensions, String name) {
        return extensions.stream()
                .filter(ext -> ext.name.equals(name) || ext.id.equals(name) || ext.id.equals("io.quarkus:quarkus-" + name))
                .findFirst();
    }

    static Optional<Extension> findExtensionByShortId(List<Extension> extensions, String shortId) {
        return extensions.stream()
                .filter(ext -> ext.getShortId().equals(shortId))
                .findFirst();
    }

    static class Gav {
        private final String groupId;
        private final String artifactId;
        private final String version;

        public static Gav from(String gav, String defaultGroupId, String defaultVersion) {
            String[] gavParts = gav.split(":");
            if (gavParts.length == 1) {
                return new Gav(defaultGroupId, gavParts[0], defaultVersion);
            } else if (gavParts.length == 2) {
                return new Gav(gavParts[0], gavParts[1], defaultVersion);
            } else if (gavParts.length == 3) {
                return new Gav(gavParts[0], gavParts[1], gavParts[2]);
            } else {
                throw new RuntimeException("Invalid GAV format: " + gav);
            }
        }

        public Gav(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }
    }

    static class Extension {

        private final String id;
        private final String shortId;
        private final String name;
        private final String description;

        public Extension(JsonObject json) {
            this.id = json.getString("id");
            this.shortId = json.getString("shortId");
            this.name = json.getString("name");
            this.description = json.getString("description");
        }

        public String getShortId() {
            return shortId;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String toString() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Extension extension = (Extension) o;
            return Objects.equals(id, extension.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}
