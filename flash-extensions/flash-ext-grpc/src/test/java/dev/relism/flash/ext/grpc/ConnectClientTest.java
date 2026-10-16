package dev.relism.flash.ext.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.WrappersProto;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.protobuf.ProtobufModule;
import dev.relism.flash.http.HttpMethod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The Connect gRPC-Web transport for Node, a real client, over HTTP/1.1 and HTTP/2. Needs node and npm. */
@Tag("interop")
class ConnectClientTest {

    private static final Path DIR = Path.of("target", "connect-client");
    private static final String SCRIPT = """
            import { createClient, ConnectError } from "@connectrpc/connect";
            import { createGrpcWebTransport } from "@connectrpc/connect-node";
            import { createFileRegistry, fromBinary } from "@bufbuild/protobuf";
            import { FileDescriptorSetSchema } from "@bufbuild/protobuf/wkt";

            const [port, httpVersion, set] = process.argv.slice(2);
            const registry = createFileRegistry(fromBinary(FileDescriptorSetSchema, Buffer.from(set, "base64")));
            const echo = createClient(registry.getService("flash.test.Echo"),
                createGrpcWebTransport({ baseUrl: `http://127.0.0.1:${port}`, httpVersion }));
            const lines = [];
            lines.push((await echo.unary({ value: "node" })).value);
            for await (const message of echo.watch({ value: "w" })) lines.push(message.value);
            try {
                await echo.unary({ value: "missing" });
            } catch (e) {
                const error = ConnectError.from(e);
                lines.push(error.code + " " + error.rawMessage);
            }
            console.log(lines.join("\\n"));
            """;

    private static Server server;

    @BeforeAll
    static void install() throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve("package.json"), """
                {"type": "module", "private": true, "dependencies": {
                  "@connectrpc/connect": "2.2.0", "@connectrpc/connect-node": "2.2.0", "@bufbuild/protobuf": "2.15.0"}}
                """);
        Files.writeString(DIR.resolve("client.mjs"), SCRIPT);
        if (!Files.exists(DIR.resolve("node_modules"))) run(List.of("npm", "install", "--no-audit", "--no-fund"));
        server = Server.start(App.create().install(new ProtobufModule(), new GrpcModule(GrpcTest.ECHO))
                        .route(HttpMethod.POST, "/flash.test.Echo/Unary", GrpcTest.Unary.class)
                        .route(HttpMethod.POST, "/flash.test.Echo/Watch", GrpcTest.Watch.class),
                ServerConfig.builder().host("127.0.0.1").port(0).http2CleartextEnabled(true).build());
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop().join();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1", "2"})
    void unaryServerStreamingAndAnErrorStatus(String httpVersion) throws Exception {
        String set = Base64.getEncoder().encodeToString(FileDescriptorSet.newBuilder()
                .addFile(WrappersProto.getDescriptor().toProto()).addFile(GrpcTest.ECHO.toProto()).build().toByteArray());
        assertEquals(List.of("hello node", "w:0", "w:1", "w:2", Status.NOT_FOUND.ordinal() + " no order é"),
                run(List.of("node", "client.mjs", String.valueOf(server.port()), httpVersion, set)));
    }

    private static List<String> run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(DIR.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new AssertionError(String.join(" ", command) + " failed:\n" + output);
        return output.lines().toList();
    }
}
