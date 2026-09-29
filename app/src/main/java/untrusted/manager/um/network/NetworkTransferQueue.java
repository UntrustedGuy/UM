package untrusted.manager.um.network;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Process-wide transfer queue shared by network-storage activities. */
public final class NetworkTransferQueue {
    public enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
    public record Job(String id, String label, State state, String detail) {}
    private static final NetworkTransferQueue INSTANCE = new NetworkTransferQueue();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<MutableJob> jobs = Collections.synchronizedList(new ArrayList<>());
    private NetworkTransferQueue() {}
    public static NetworkTransferQueue get() { return INSTANCE; }
    public String submit(String label, Runnable work) {
        if (work == null) throw new IllegalArgumentException("work");
        MutableJob j = new MutableJob(UUID.randomUUID().toString(), label == null ? "Transfer" : label);
        jobs.add(j);
        j.future = executor.submit(() -> {
            if (j.cancelled || Thread.currentThread().isInterrupted()) { j.state=State.CANCELLED; j.detail="Cancelled"; notifyChanged(); return; }
            j.state=State.RUNNING; j.detail="Running"; notifyChanged();
            try {
                work.run();
                if (j.cancelled || Thread.currentThread().isInterrupted()) { j.state=State.CANCELLED; j.detail="Cancelled"; }
                else { j.state=State.COMPLETED; j.detail="Completed"; }
            } catch (Throwable t) {
                if (j.cancelled || Thread.currentThread().isInterrupted()) { j.state=State.CANCELLED; j.detail="Cancelled"; }
                else { j.state=State.FAILED; j.detail=t.getMessage()==null?t.getClass().getSimpleName():t.getMessage(); }
            }
            notifyChanged();
        });
        notifyChanged(); return j.id;
    }
    public void cancel(String id) { synchronized(jobs){ for(MutableJob j:jobs) if(j.id.equals(id)){ if(j.state==State.COMPLETED||j.state==State.FAILED||j.state==State.CANCELLED) return; j.cancelled=true; j.state=State.CANCELLED; j.detail="Cancelled"; if(j.future!=null)j.future.cancel(true); } } notifyChanged(); }
    public List<Job> snapshot() { List<Job> out=new ArrayList<>(); synchronized(jobs){ for(MutableJob j:jobs)out.add(new Job(j.id,j.label,j.state,j.detail)); } return out; }
    public void clearFinished() { synchronized(jobs){ jobs.removeIf(j -> j.state==State.COMPLETED || j.state==State.FAILED || j.state==State.CANCELLED); } notifyChanged(); }
    public interface Listener { void onQueueChanged(List<Job> jobs); }
    private final List<Listener> listeners=new ArrayList<>();
    public void addListener(Listener l){ synchronized(listeners){listeners.add(l);} l.onQueueChanged(snapshot()); }
    public void removeListener(Listener l){ synchronized(listeners){listeners.remove(l);} }
    private void notifyChanged(){ List<Job>s=snapshot(); synchronized(listeners){ for(Listener l:new ArrayList<>(listeners)) l.onQueueChanged(s); } }
    private static final class MutableJob { final String id,label; volatile State state=State.QUEUED; volatile String detail="Queued"; volatile Future<?> future; volatile boolean cancelled; MutableJob(String i,String l){id=i;label=l;} }
}
