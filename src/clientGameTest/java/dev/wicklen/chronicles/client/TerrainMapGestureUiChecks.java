package dev.wicklen.chronicles.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.wicklen.chronicles.core.map.MapViewport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Real screen mouse dispatch over the existing terrain fixture, including inverse UI scaling. */
public final class TerrainMapGestureUiChecks {
    public static void verify(ClientGameTestContext context) {
        TerrainMapCameraChecks.verify();
        var restore = new AtomicReference<TerrainMapCamera.View>();
        var dragged = new AtomicReference<TerrainMapCamera.View>();
        context.runOnClient(client -> {
            var screen = (SpiritDialogScreen) client.gui.screen();
            var pages = screen.pagesForInputChecks();
            var bounds = pages.mapBounds();
            var initial = TerrainMapClientState.displayViewport();
            restore.set(initial);
            var place = ResearchClientState.data().places().stream()
                    .filter(entry -> entry.key().id().endsWith("terrain_camp")).findFirst().orElseThrow();
            double x = bounds.x() + initial.u(place.position().x()) * bounds.size();
            double y = bounds.y() + initial.v(place.position().z()) * bounds.size();
            String selected = pages.selectedMapPlace();
            byte[] pixels = TerrainMapClientState.page().pixels().clone();
            var right = new MouseButtonEvent(x * screen.journalScale(), y * screen.journalScale(),
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0));
            screen.mouseClicked(right, false);
            require(!pages.mouseDragged(x + 20, y, InputConstants.MOUSE_BUTTON_RIGHT), "right click does not capture map panning");
            near(TerrainMapClientState.displayViewport().centerX(), initial.centerX(), "right click does not move camera");
            screen.mouseReleased(right);
            require(screen.mouseClicked(mouse(screen, x, y), false), "map captures real marker press before widgets");
            require(pages.selectedMapPlace().equals(selected), "press does not prematurely open a marker");
            screen.mouseDragged(mouse(screen, x + 2, y + 1), 2 * screen.journalScale(), screen.journalScale());
            near(TerrainMapClientState.displayViewport().centerX(), initial.centerX(), "subthreshold move is a click");
            screen.mouseDragged(mouse(screen, x + 20, y + 12), 18 * screen.journalScale(), 11 * screen.journalScale());
            var moved = TerrainMapClientState.displayViewport();
            near(moved.centerX(), initial.centerX() - 20.0 * initial.span() / bounds.size(), "terrain follows horizontal pointer");
            near(moved.centerZ(), initial.centerZ() - 12.0 * initial.span() / bounds.size(), "terrain follows vertical pointer");
            require(Arrays.equals(pixels, TerrainMapClientState.page().pixels()), "optimistic drag never edits authorized terrain/fog pixels");
            screen.mouseReleased(mouse(screen, x + 20, y + 12));
            require(pages.selectedMapPlace().equals(selected), "drag starting on marker does not select it");
            dragged.set(moved);
        });
        // Let the actual RPC complete: accepting an older response must not rewind the camera.
        context.waitTicks(14);
        context.runOnClient(client -> {
            var screen = (SpiritDialogScreen) client.gui.screen();
            var pages = screen.pagesForInputChecks();
            var bounds = pages.mapBounds();
            var current = TerrainMapClientState.displayViewport();
            near(current.centerX(), dragged.get().centerX(), "RPC completion preserves local pointer intent");
            double x = bounds.x() + bounds.size() * 0.27;
            double y = bounds.y() + bounds.size() * 0.69;
            double anchorX = current.minX() + 0.27 * current.span();
            double anchorZ = current.minZ() + 0.69 * current.span();
            require(screen.mouseScrolled(x * screen.journalScale(), y * screen.journalScale(), 0, -1), "wheel over map is consumed");
            var zoomed = TerrainMapClientState.displayViewport();
            require(zoomed.span() == 256, "native wheel zooms out one supported scale");
            near(zoomed.minX() + 0.27 * zoomed.span(), anchorX, "native wheel keeps world X under cursor");
            near(zoomed.minZ() + 0.69 * zoomed.span(), anchorZ, "native wheel keeps world Z under cursor");
            screen.mouseScrolled(x * screen.journalScale(), y * screen.journalScale(), 0, 1);
            require(TerrainMapClientState.span() == 128, "reverse wheel returns to near scale");
            screen.mouseScrolled((bounds.x() - 10) * screen.journalScale(), y * screen.journalScale(), 0, -1);
            require(TerrainMapClientState.span() == 128, "wheel outside map does not zoom terrain");
            screen.mouseClicked(mouse(screen, x, y), false);
            double outsideX = bounds.x() + bounds.size() + 70;
            require(screen.mouseDragged(mouse(screen, outsideX, y), 70 * screen.journalScale(), 0), "captured drag continues outside map");
            require(screen.mouseReleased(mouse(screen, outsideX, y)), "release outside map clears capture");
            require(!pages.mouseDragged(x, y, InputConstants.MOUSE_BUTTON_LEFT), "released pointer no longer pans");
            screen.mouseClicked(mouse(screen, x, y), false);
            screen.open(SpiritDialogScreen.Tab.ATLAS);
            screen.open(SpiritDialogScreen.Tab.MAP);
            require(!pages.mouseDragged(x, y, InputConstants.MOUSE_BUTTON_LEFT), "changing tabs cancels drag capture");
            screen.mouseClicked(mouse(screen, x, y), false);
            screen.keyPressed(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            require(client.gui.screen() != screen, "Escape closes the journal during a captured drag");
            require(!pages.mouseDragged(x, y, InputConstants.MOUSE_BUTTON_LEFT), "Escape cancels drag capture");
            client.setScreenAndShow(screen);
            screen.open(SpiritDialogScreen.Tab.MAP);
            TerrainMapClientState.focus(MapViewport.BLOCK_LIMIT, -MapViewport.BLOCK_LIMIT, 128);
            screen.mouseClicked(mouse(screen, x, y), false);
            screen.mouseDragged(mouse(screen, x - 1000, y + 1000), -1000, 1000);
            require(TerrainMapClientState.viewport().centerX() == MapViewport.BLOCK_LIMIT
                    && TerrainMapClientState.viewport().centerZ() == -MapViewport.BLOCK_LIMIT, "actual drag clamps both world edges");
            screen.mouseReleased(mouse(screen, x - 1000, y + 1000));
            var original = restore.get();
            TerrainMapClientState.focus((int) Math.round(original.centerX()), (int) Math.round(original.centerZ()), original.span());
            screen.changed();
        });
        context.waitFor(client -> !TerrainMapClientState.busy() && TerrainMapClientState.page() != null
                && TerrainMapClientState.page().viewport().equals(TerrainMapClientState.viewport()));
        System.out.println("PASS: native terrain map drag threshold/marker safety, scaled mouse routing, outside release/tab cancel, anchored wheel and clamp");
    }

    private static MouseButtonEvent mouse(SpiritDialogScreen screen, double x, double y) {
        return new MouseButtonEvent(x * screen.journalScale(), y * screen.journalScale(), new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-6, message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
