///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS io.quarkus:quarkus-resteasy:3.17.5
//DEPS io.quarkus:quarkus-picocli:3.17.5
//DEPS io.quarkus:quarkus-arc:3.17.5
//DEPS io.quarkus:quarkus-resteasy-qute:3.17.5
//DEPS io.quarkus:quarkus-resteasy-multipart:3.17.5
//DEPS org.seleniumhq.selenium:selenium-chrome-driver:4.13.0
//DEPS io.github.bonigarcia:webdrivermanager:5.5.3
//DEPS org.fusesource.jansi:jansi:2.4.0

//FILES templates/edit.html=firestarter.html
//Q:CONFIG quarkus.log.console.level=WARN
//Q:CONFIG quarkus.banner.enabled=false

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.fusesource.jansi.Ansi;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import io.github.bonigarcia.wdm.WebDriverManager;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.runtime.Quarkus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import picocli.CommandLine;

@CommandLine.Command
public class firestarter implements Runnable {

    @CommandLine.Option(names = {"--setup-webdriver"}, description = "Download and setup ChromeWebdriver", defaultValue = "false")
    Boolean downloadWebdriver;

    @CommandLine.Parameters(arity = "0")
    String initialUrl;

    @Inject
    BrowserManager browserManager;

    @Inject
    VideoStorage video;

    @Inject
    @ConfigProperty(name = "quarkus.http.port")
    String port;

    @Override
    public void run() {
        if (downloadWebdriver && isRasPi()) {
            System.err.println("Cannot download the ChromeDriver on RaspberryPi.");
            System.err.println("Install with: 'sudo apt-get install chromium-chromedriver'");
            System.exit(0);
        }

        // Init ChromeWebdriver & Selenium
        try {
            browserManager.init(downloadWebdriver);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        browserManager.openUrl(youtubeFullscreenUrl(video.getVideoWithInitial(initialUrl).url));

        System.err.println(Ansi.ansi().render("@|green " + "Open http://localhost:" + port + "/firestarter to change the URL/video to open." + "|@\n"));

        Quarkus.waitForExit();

        browserManager.shutdown();
    }

    static boolean isRasPi() {
        return System.getProperty("os.name").equals("Linux")
                && System.getProperty("os.arch").equals("arm");
    }

    public static String youtubeFullscreenUrl(String url) {
        if (url.toLowerCase().contains("youtube")) {
            try {
                // Convert URL to embed-url that allows opening in fullscreen, with autoplay and without controls.
                final String videoId = Arrays.stream(new URL(url).getQuery().split("&"))
                        .map(parm -> parm.split("="))
                        .filter(kv -> kv[0].equals("v"))
                        .map(kv -> kv[1])
                        .findFirst()
                        .get();

                String fsUrl = "https://www.youtube.com/embed/" + videoId + "?controls=0&autoplay=1";
                System.out.println(">>>"  + fsUrl);
                return fsUrl;
            } catch (Exception e) {
                return url;
            }
        }
        return url;
    }

    @ApplicationScoped
    public static class VideoStorage {
        @Inject
        BrowserManager browserManager;

        public Video getVideo() {
            return getVideoWithInitial(null);
        }

        public Video getVideoWithInitial(String initialVideo) {
            Video video = new Video();

            var configFile = new File(System.getProperty("user.dir") + "/.firestarter");
            if (configFile.exists()) {
                var properties = new Properties();
                try {
                    properties.load(new FileReader(configFile));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                video.title = "YouTube Fire";
                video.url = properties.getProperty("url");
            } else if (initialVideo != null) {
                video.title = "YouTube Fire";
                video.url = initialVideo;
            }
            else {
                video.title = "YouTube Fire";
                video.url = "https://www.youtube.com/watch?v=L_LUpnjgPso";
            }

            return video;
        }

        public void setVideo(Video video) {
            var configFile = new File(System.getProperty("user.dir") + "/.firestarter");
            var properties = new Properties();
            try {
                properties.put("url", video.url);
                properties.store(new FileWriter(configFile), "comment");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            browserManager.openUrl(firestarter.youtubeFullscreenUrl(video.url));
        }
    }

    @Path("firestarter")
    public static class AdminResource {

        @Inject
        Template edit;

        @Inject
        VideoStorage videoStorage;

        public AdminResource() {}

        @GET
        @Consumes(MediaType.TEXT_HTML)
        @Produces(MediaType.TEXT_HTML)
        public TemplateInstance view() {
            return edit.data("video", videoStorage.getVideo());
        }

        @POST
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        public Object update(
                @MultipartForm Video video) {
            System.out.println("SET");
            videoStorage.setVideo(video);
            return Response.status(301)
                    .location(URI.create("/firestarter"))
                    .build();
        }
    }

    public static class Video {
        public @FormParam("title") String title;
        public @FormParam("url") String url;
    }

    @ApplicationScoped
    public static class BrowserManager {

        private Video video;
        private WebDriver driver;

        public static WebDriver setupDriver(boolean downloadWebdriver) {
            if (downloadWebdriver) {
                WebDriverManager.chromedriver().setup();
            }
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--kiosk", "--autoplay-policy=no-user-gesture-required");
            options.setExperimentalOption("excludeSwitches", Collections.singletonList("enable-automation"));
            options.setExperimentalOption("useAutomationExtension", false);

            options.setPageLoadStrategy(PageLoadStrategy.NONE);
            WebDriver driver = null;
            try {
                driver = new ChromeDriver(options);
            }
            catch (IllegalStateException e) {
                if (!downloadWebdriver && e.getMessage().contains("The path to the driver executable must be set by the webdriver.chrome.driver system property")) {
                    System.err.println("ChromeWebdriver is not installed.");
                    if (firestarter.isRasPi()) {
                        System.err.println("Install with: 'sudo apt-get install chromium-chromedriver'");
                    }
                    else {
                        System.err.println("Run with '--setup-webdriver'");
                    }
                    System.exit(0);
                }
                else {
                    throw e;
                }
            }
            driver.manage().timeouts().implicitlyWait(1250, TimeUnit.MILLISECONDS);
            return driver;
        }

        public void init(boolean downloadWebdriver) throws Exception {
            driver = setupDriver(downloadWebdriver);
        }

        public void openUrl(String url) {
            driver.get(url);
        }

        public void shutdown() {
            driver.quit();
        }
    }
}
