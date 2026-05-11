package dev.relism.flash.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackageScannerTest {

    @Test
    void scan_separatesHttpHandlersAndWsEndpoints() {
        PackageScanner.ScanResult result = PackageScanner.scan("dev.relism.flash.websocket.onlyws");

        assertTrue(result.wsEndpoints().stream().anyMatch(c -> c.getSimpleName().equals("OnlyWsEndpoint")));
        assertFalse(result.httpHandlers().stream().anyMatch(c -> c.getSimpleName().equals("OnlyWsEndpoint")));
    }

    @Test
    void scan_includesHttpHandlerAndWsEndpointFromTestPackage() {
        PackageScanner.ScanResult result = PackageScanner.scan("dev.relism.flash.websocket.scantest");

        assertTrue(result.httpHandlers().stream().anyMatch(c -> c.getSimpleName().equals("ScanHttpHandler")));
        assertTrue(result.wsEndpoints().stream().anyMatch(c -> c.getSimpleName().equals("ScanWsEndpoint")));
    }
}
