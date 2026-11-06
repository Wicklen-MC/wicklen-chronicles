package dev.wicklen.chronicles.station;

public enum StationVariant {
    OVERGROWN_RESEARCH("Заросшая исследовательская станция", "Уцелевший архив, заросший двор и следы полевой работы."),
    RANSACKED_SUPPLIES("Разграбленный путевой склад", "Разобранные запасы и два пути к уцелевшей кладовой."),
    UNDEAD_COURTYARD("Заброшенная станция", "Следы нежити и просторный двор. Испытание запускается только добровольно.");

    private final String title;
    private final String summary;
    StationVariant(String title, String summary) { this.title = title; this.summary = summary; }
    public String title() { return title; }
    public String summary() { return summary; }
}
