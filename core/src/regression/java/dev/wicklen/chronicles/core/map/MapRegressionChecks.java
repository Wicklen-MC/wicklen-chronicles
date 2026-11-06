package dev.wicklen.chronicles.core.map;

import java.util.*;

public final class MapRegressionChecks {
    public static void main(String[] args) {
        var world = UUID.randomUUID(); var owner = UUID.randomUUID(); var cell = new MapCell("minecraft:overworld", -1, -1);
        require(cell.regionX() == -1 && cell.regionZ() == -1 && cell.index() == 255, "negative grid coordinates use floor division");
        var edge = new MapCell("minecraft:overworld", -MapCell.LIMIT, -MapCell.LIMIT);
        require(MapRegion.empty(world, owner, edge).regionX() == edge.regionX(), "partial regions at negative world bounds remain valid");
        byte[] palette = colors(44); var sample = new SurveyChunk(cell, 100, palette); palette[0] = 60;
        require(sample.color(0, 0) == 44, "input array is copied");
        var exposed = sample.colors(); exposed[0] = 60; require(sample.color(0, 0) == 44, "projection array is copied");
        var first = MapTransitions.record(MapRegion.empty(world, owner, cell), owner, sample);
        require(first.firstVisit() && first.changed() && first.region().revision() == 1, "first survey produces one personal record");
        var again = MapTransitions.record(first.region(), owner, new SurveyChunk(cell, 900, colors(44)));
        require(!again.changed() && !again.firstVisit(), "revisits do not repeat discovery or first-visit timestamp");
        var updated = MapTransitions.record(first.region(), owner, new SurveyChunk(cell, 900, colors(48)));
        require(updated.changed() && !updated.firstVisit() && updated.region().chunks().get(255).firstVisitedAt() == 100,
                "changed terrain can update its snapshot without inventing a new visit");
        rejects(() -> MapTransitions.record(first.region(), UUID.randomUUID(), sample), "another owner cannot mutate the map");
        rejects(() -> MapTransitions.record(first.region(), owner, new SurveyChunk(new MapCell("minecraft:the_nether", -1, -1), 100, colors(44))), "dimensions remain separate");
        rejects(() -> new MapViewport(0, 0, 2048), "unbounded zoom is rejected");
        var start = new MapCell("minecraft:overworld", 0, 0); var far = new MapCell("minecraft:overworld", 4, 0);
        var known = Map.of(start, new SurveyChunk(start, 1, colors(44)), far, new SurveyChunk(far, 2, colors(48)));
        byte[] image = MapProjection.pixels("minecraft:overworld", new MapViewport(0, 0, 256), known::get);
        require(count(image) == 128, "only two actually surveyed chunks contribute their 8x8 pixels");
        require(image[64 * 128 + 64] == 44 && image[64 * 128 + 96] == 48 && image[64 * 128 + 80] == 0,
                "a teleport does not interpolate a visited line through the unknown gap");
        require(count(MapProjection.pixels("minecraft:overworld", new MapViewport(0, 0, 1024), known::get)) == 8,
                "zoom aggregates only known cells and preserves fog");
        var view = new MapViewport(-3, -7, 512);
        require(view.minX() % view.step() == 0 && view.minZ() % view.step() == 0, "negative viewport origins align to pixel samples");
        require(view.contains(view.minX(), view.minZ()) && !view.contains(view.minX() + view.span(), view.minZ()), "projection has consistent exclusive upper edges");
        System.out.println("PASS: terrain personal immutable snapshots, negative coordinates, strict fog, zoom and teleport-without-line");
    }
    private static byte[] colors(int color) { var result = new byte[64]; Arrays.fill(result, (byte) color); return result; }
    private static long count(byte[] pixels) { int count = 0; for (byte color : pixels) if (color != 0) count++; return count; }
    private static void rejects(Runnable action, String message) { try { action.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) {} }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
