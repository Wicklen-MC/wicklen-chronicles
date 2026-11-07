package dev.wicklen.chronicles.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/** Opt-in recording of real native framebuffer frames. No desktop/window capture or production hook. */
final class NativeGameVideo {
    private record Frame(int index, byte[] rgba) { }
    private static final int FPS = 20;

    static void record(ClientGameTestContext context, int seconds, DoubleConsumer stage) {
        var directory = Path.of("videos", "spirit-wanderer");
        try { Files.createDirectories(directory); }
        catch (java.io.IOException error) { throw new RuntimeException(error); }
        int width = context.computeOnClient(client -> client.gameRenderer.mainRenderTarget().width);
        int height = context.computeOnClient(client -> client.gameRenderer.mainRenderTarget().height);
        Process encoder;
        try {
            encoder = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", width + "x" + height,
                "-framerate", String.valueOf(FPS), "-i", "pipe:0", "-an", "-c:v", "libx264",
                "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-vf",
                "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-movflags", "+faststart",
                directory.resolve("spirit-wanderer.mp4").toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(directory.resolve("ffmpeg.log").toFile()).start();
        } catch (java.io.IOException error) { throw new RuntimeException("Native video requires ffmpeg on PATH", error); }
        var queue = new ArrayBlockingQueue<Frame>(4);
        var recording = new AtomicBoolean(true);
        var pending = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var captured = new AtomicInteger();
        var lastRequested = new AtomicInteger(-1);
        long start = System.nanoTime();
        var writer = new Thread(() -> {
            try (var output = encoder.getOutputStream()) {
                int next = 0;
                byte[] last = null;
                while (recording.get() || !queue.isEmpty()) {
                    Frame frame = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    if (frame.index() >= seconds * FPS) continue;
                    if (last == null) last = frame.rgba();
                    while (next < frame.index()) { output.write(last); next++; }
                    if (next == frame.index()) { output.write(frame.rgba()); next++; last = frame.rgba(); }
                }
                if (last == null) throw new IllegalStateException("No native video frames received");
                while (next < seconds * FPS) { output.write(last); next++; }
            } catch (Throwable error) { failure.set(error); }
        }, "wicklen-native-video");
        writer.start();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!recording.get() || failure.get() != null) return;
            int frame = (int) ((System.nanoTime() - start) / 50_000_000L);
            if (frame >= seconds * FPS || frame <= lastRequested.get() || !pending.compareAndSet(false, true)) return;
            lastRequested.set(frame);
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                try (image) {
                    if (!recording.get()) return;
                    if (image.getWidth() != width || image.getHeight() != height)
                        throw new IllegalStateException("Native video framebuffer resized during capture");
                    byte[] pixels = new byte[width * height * 4];
                    image.getPixelBytes().get(pixels);
                    if (queue.offer(new Frame(frame, pixels))) captured.incrementAndGet();
                } catch (Throwable error) { failure.set(error); }
                finally { pending.set(false); }
            });
        });
        try {
            while ((System.nanoTime() - start) < seconds * 1_000_000_000L && failure.get() == null) {
                stage.accept((System.nanoTime() - start) / 1_000_000_000.0);
                context.waitTicks(1);
            }
        } finally {
            recording.set(false);
            try {
                writer.join(10_000);
                if (writer.isAlive() || !encoder.waitFor(10, TimeUnit.SECONDS) || encoder.exitValue() != 0)
                    throw new IllegalStateException("Native video encoder did not finish cleanly");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            } finally { if (encoder.isAlive()) encoder.destroyForcibly(); }
        }
        if (failure.get() != null) throw new RuntimeException("Native video capture failed", failure.get());
        try {
            Files.writeString(directory.resolve("capture.json"), "{\"kind\":\"native Minecraft framebuffer recording\",\"fps\":" + FPS
                + ",\"seconds\":" + seconds + ",\"width\":" + width + ",\"height\":" + height
                + ",\"captured_frames\":" + captured.get() + ",\"output_frames\":" + seconds * FPS + "}\n");
        } catch (java.io.IOException error) { throw new RuntimeException(error); }
        System.out.println("PASS: native spirit video, " + captured.get() + " real framebuffer samples, " + seconds + " seconds");
    }
}
