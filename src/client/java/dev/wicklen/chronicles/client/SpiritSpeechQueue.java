package dev.wicklen.chronicles.client;

import java.util.ArrayDeque;
import java.util.List;

/** UI-only timing: new speech never interrupts the card currently being read. */
final class SpiritSpeechQueue {
    static final int MAX_MESSAGES=3;
    private static final long MAX_PENDING_AGE_MS=30_000;
    record Speech(String title,int color,String text,int ttlMs,long receivedAt) {}
    record Visible(Speech speech,long shownAt) {
        float opacity(long now) {
            long elapsed=Math.max(0,now-shownAt);
            return (float)Math.clamp(Math.min(elapsed/180.0,(speech.ttlMs()-elapsed)/420.0),0,1);
        }
    }
    private final ArrayDeque<Speech> pending=new ArrayDeque<>();
    private Visible current;

    void offer(String title,int color,String text,int ttlMs,long now) {
        if(text==null || text.isBlank())return;
        while(size()>=MAX_MESSAGES && !pending.isEmpty())pending.removeFirst();
        pending.addLast(new Speech(title==null?"":title,color,text,Math.clamp(ttlMs,2500,12000),now));
    }
    void advance(long now,boolean displayAllowed) {
        pending.removeIf(speech->now-speech.receivedAt()>MAX_PENDING_AGE_MS);
        if(current!=null && now-current.shownAt()>=current.speech().ttlMs())current=null;
        if(current==null && displayAllowed && !pending.isEmpty())current=new Visible(pending.removeFirst(),now);
    }
    Visible current() { return current; }
    int size() { return pending.size()+(current==null?0:1); }
    List<String> texts() {
        var texts=new java.util.ArrayList<String>();
        if(current!=null)texts.add(current.speech().text());
        pending.forEach(speech->texts.add(speech.text()));
        return List.copyOf(texts);
    }
    void clear() {current=null;pending.clear();}
}
