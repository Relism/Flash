package dev.relism;

import dev.relism.http.ContentType;
import dev.relism.models.*;
import dev.relism.routing.Route;
import dev.relism.routing.routers.fastpathrouter.FastPathRouterImpl;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class Main {

    private static final int PORT = 8080;

    /*
    @Route(method = "GET", path = "/profile")
    public static class ProfileHandler extends RequestHandler {
        @Override
        public Object handle(Request request, Response response) {
            return "Class based handler: User profile info under /api/profile";
        }
    }
     */

    public static void main(String[] args) throws IOException {
        HttpServerConfiguration config = HttpServerConfiguration.builder()
                .port(PORT)
                .host("localhost")
                .build();

        HttpServer server = new HttpServer(config);

        FastPathRouterImpl apiRouter = new FastPathRouterImpl();
        server.mount("/api", apiRouter);

        // apiRouter.register(new ProfileHandler());

        server.get("/headers", (req, res) -> {
            throw new RuntimeException(req.getQueryParam("test"));
        });



        server.start().thenRun(() -> log.info("Server started on port: " + PORT));
    }
}
