///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.jboss.resteasy:resteasy-client:4.5.6.Final
//DEPS org.jboss.resteasy:resteasy-json-p-provider:4.5.6.Final
//DEPS net.lingala.zip4j:zip4j:2.6.4
//DEPS de.codeshelf.consoleui:consoleui:0.0.13

import de.codeshelf.consoleui.prompt.CheckboxPrompt;
import de.codeshelf.consoleui.prompt.CheckboxResult;
import de.codeshelf.consoleui.prompt.ConsolePrompt;
import de.codeshelf.consoleui.prompt.PromtResultItemIF;
import de.codeshelf.consoleui.prompt.builder.CheckboxPromptBuilder;
import de.codeshelf.consoleui.prompt.builder.PromptBuilder;
import net.lingala.zip4j.ZipFile;
import org.apache.commons.codec.binary.Hex;
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
        description = "QuarkusStarter made with jbang",
        subcommands = {
                QuarkusStarter.CreateCommand.class,
                QuarkusStarter.ListCommand.class
        })
class QuarkusStarter {

    public static void main(String... args) {
        int exitCode = new CommandLine(new QuarkusStarter()).execute(args);
        System.exit(exitCode);
    }

    @Command(name = "list", aliases = {"l"}, description = "List Quarkus extensions.")
    static class ListCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "Filter for listed extensions")
        private String filter;

        @Override
        public Integer call() throws Exception {
            AnsiConsole.systemInstall();

            List<Extension> allExtensions = fetchAllExtensions();

            allExtensions.stream()
                    .filter(ext -> {
                        if (filter == null) return true;
                        filter = filter.toLowerCase();

                        if (ext.getName().toLowerCase().contains(filter)) return true;
                        if (ext.getId().toLowerCase().contains(filter)) return true;

                        return false;
                    })
                    .forEach(ext -> System.out.println(ext.formatted()));

            return 0;
        }
    }

    @Command(name = "create", aliases = {"c"}, description = "Create/scaffold a new Quarkus project")
    static class CreateCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "name of the project / artifact-id / folder to create. If contains ':', parts are interpreted as G:A:V.")
        private String projectName;

        enum BuildTool {
            maven,
            gradle
        }

        @CommandLine.Option(names = {"--build", "-b"}, description = "Valid values: ${COMPLETION-CANDIDATES}")
        private BuildTool buildTool = BuildTool.maven;

        @CommandLine.Option(names = {"--extensions", "-e"}, defaultValue = "resteasy-jackson")
        private List<String> extensions;

        @CommandLine.Option(names = {"--interactive", "-i"})
        private boolean interactive;

        @Override
        public Integer call() throws Exception {
            AnsiConsole.systemInstall();

            Gav gav = Gav.from(projectName, "org.acme", "1.0.0-SNAPSHOT");

            Path targetDir = Paths.get(System.getProperty("user.dir"));
            if (targetDir.resolve(gav.artifactId).toFile().exists()) {
                System.err.println(ansi().render("@|red " + "Folder '" + targetDir.resolve(gav.artifactId) + "' already exists." + "|@\n"));
                System.exit(1);
            }

            List<Extension> allExtensions = fetchAllExtensions();

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
                                    .text(" " + ext.formatted())
                                    .checked(finalSelectedExtensions.contains(ext))
                                    .add();
                        });
                HashMap<String, ? extends PromtResultItemIF> result = prompt.prompt(checkboxPrompt.pageSize(100).addPrompt().build());

                CheckboxResult checkboxResult = (CheckboxResult) result.get("extensions");
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

            new ZipFile(tmpFile).extractAll(targetDir.toString());

            System.err.println("------------------------------------------------------------------------");
            System.err.println(ansi().render("@|green " + "Succesfully created a project with artifact-id '" + gav.artifactId + "' at '" + targetDir.resolve(gav.artifactId) + "'." + "|@"));
            System.err.println(ansi().render("Build & run with '" + buildCommand() + "'"));
            System.err.println("------------------------------------------------------------------------");

            return 0;
        }

        String buildCommand() {
            if (buildTool.equals(BuildTool.maven)) {
                if (isUnix()) {
                    return "./mvnw quarkus:dev";
                } else {
                    return "mvnw.cmd quarkus:dev";
                }
            } else {
                if (isUnix()) {
                    return "./gradlew quarkusDev";
                } else {
                    return "gradlew.bat quarkusDev";
                }
            }
        }

        static boolean isUnix() {
            return !System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows");
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

        static Optional<Extension> findExtensionByName(List<Extension> extensions, String name) {
            return extensions.stream()
                    .filter(ext -> ext.name.equals(name) || ext.id.equals(name) || ext.id.equals("io.quarkus:" + name) || ext.id.equals("io.quarkus:quarkus-" + name))
                    .findFirst();
        }

        static Optional<Extension> findExtensionByShortId(List<Extension> extensions, String shortId) {
            return extensions.stream()
                    .filter(ext -> ext.getShortId().equals(shortId))
                    .findFirst();
        }
    }

    static List<Extension> fetchAllExtensions() {
        JsonArray quarkusExtensions = ClientBuilder.newClient()
                .target("https://code.quarkus.io/api/extensions")
                .request()
                .get(JsonArray.class);

        return quarkusExtensions.stream()
                .map(JsonValue::asJsonObject)
                .map(Extension::new)
                .sorted(Comparator.comparing(Extension::getName))
                .distinct()
                .collect(Collectors.toList());
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

        public String getId() {
            return id;
        }

        public String toString() {
            return name;
        }

        public String formatted() {
            return ansi()
                    .a(this.getName())
                    .a(" (")
                    .fg(Ansi.Color.CYAN)
                    .a(this.id)
                    .reset()
                    .a(")")
                    .a(Ansi.Attribute.INTENSITY_FAINT)
                    .a(" - " + this.getDescription())
                    .reset().toString();
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
