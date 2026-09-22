package dev.relism.flash.ext.vite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A real child process: a package.json whose dev script stands in for Vite. Needs npm on the PATH. */
class DevServerTest {

    @TempDir
    Path project;

    @Test
    void startsTheDevScriptWaitsForItAndTakesItDownOnClose() throws Exception {
        assumeTrue(onPath("npm"), "npm is not on the PATH");
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        Files.writeString(project.resolve("package.json"), """
                {"name":"t","private":true,"scripts":{"dev":"node -e \\"require('http').createServer((q,s)=>s.end('ok')).listen(+process.argv[process.argv.indexOf('--port')+1],'127.0.0.1')\\" --"}}
                """);
        DevServer server = new DevServer(project, port);
        assertTrue(answers(port));
        assertTrue(Files.isRegularFile(project.resolve("node_modules/.flash-vite")));
        server.close();
        assertFalse(answers(port));
    }

    private static boolean answers(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean onPath(String command) {
        try {
            return new ProcessBuilder(command, "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
