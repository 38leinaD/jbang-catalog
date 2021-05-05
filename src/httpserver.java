///usr/bin/env jbang "$0" "$@" ; exit $?
// //DEPS <dependency1> <dependency2>

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;

import static java.lang.System.*;

public class httpserver {

    public static void main(String[] args) throws Exception {
        int port = 80;
        Path serveLocation = Paths.get(System.getProperty("user.dir"));
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            serveLocation = Paths.get(args[1]);
        }

        out.printf("Server started at port %s. Serving %s.\n", port, serveLocation);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new RequestHandler(serveLocation));
        server.setExecutor(null); // creates a default executor
        server.start();
    }

    static class RequestHandler implements HttpHandler {
        private Path root;
        public RequestHandler(Path root) {
            this.root = root;
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            String uri = t.getRequestURI().toString();

            if (uri.equals("") || uri.equals("/")) {
                uri = "/index.html";
            }

            Path filePath = this.root.resolve(Paths.get(uri.substring(1)));

            // TODO query string

            if (!Files.isRegularFile(filePath)) {
                t.sendResponseHeaders(404, 0);
                OutputStream os = t.getResponseBody();
                os.close();
                return;
            }
            out.println("* " + t.getRequestMethod() + ":" + filePath);

            if (filePath.toString().endsWith(".js")) {
                t.getResponseHeaders().add("Content-Type", "text/javascript");
            }
            else if (filePath.toString().endsWith(".css")) {
                t.getResponseHeaders().add("Content-Type", "text/css");
            }

            byte[] responseBytes = Files.readAllBytes(filePath);

            t.sendResponseHeaders(200, responseBytes.length);
            OutputStream os = t.getResponseBody();
            os.write(responseBytes);
            os.close();
        }
    }

}
