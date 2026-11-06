package dev.wicklen.chronicles.client;

import dev.wicklen.chronicles.core.map.MapViewport;

/** Pure Java17 geometry checks; no client, network, texture or Minecraft world required. */
public final class TerrainMapCameraChecks {
    public static void main(String[] args) {
        verify();
    }

    public static void verify() {
        var camera = new TerrainMapCamera();
        camera.focus(-33, -79, 512);
        for (int i = 0; i < 100; i++) camera.pan(0.01, -0.01);
        near(camera.view().centerX(), -32, "fractional horizontal deltas accumulate");
        near(camera.view().centerZ(), -80, "negative fractional deltas accumulate");
        double u = 0.23;
        double v = 0.72;
        double x = camera.view().minX() + u * camera.view().span();
        double z = camera.view().minZ() + v * camera.view().span();
        require(camera.zoom(true, u, v), "wheel zoom changes one supported step");
        near(camera.view().minX() + u * camera.view().span(), x, "cursor anchor X survives zoom in");
        near(camera.view().minZ() + v * camera.view().span(), z, "cursor anchor Z survives zoom in");
        camera.zoom(false, u, v);
        near(camera.view().minX() + u * camera.view().span(), x, "cursor anchor survives reverse zoom");
        require(camera.wire().span() == 512, "wire remains at a supported scale");
        camera.focus(MapViewport.BLOCK_LIMIT, -MapViewport.BLOCK_LIMIT, 128);
        camera.pan(1e12, -1e12);
        require(camera.wire().centerX() == MapViewport.BLOCK_LIMIT
                && camera.wire().centerZ() == -MapViewport.BLOCK_LIMIT, "world edge coordinates clamp without overflow");
        for (int i = 0; i < 20; i++) camera.zoom(false, 0, 1);
        require(camera.wire().span() == 1024, "zoom out is bounded");
        for (int i = 0; i < 20; i++) camera.zoom(true, 1, 0);
        require(camera.wire().span() == 128, "zoom in is bounded");
        var before = camera.view();
        camera.moveTo(Double.NaN, Double.POSITIVE_INFINITY);
        require(camera.view().equals(before), "nonfinite pointer math cannot poison camera");
        require(!camera.zoom(true, Double.NaN, 0), "nonfinite wheel anchor is rejected");
        System.out.println("PASS: terrain map continuous camera, negative coordinates, anchored zoom, finite/bounded wire");
    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-8, message + ": " + actual + " != " + expected);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
