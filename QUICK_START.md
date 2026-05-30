# Quick Start for Using Euhedral

Euhedral is a low-latency execution system designed for parallel execution with minimal coordination
overhead.

- Work is represented as frames and routed through worker pipelines based on their hashes.
- Euhedral is asynchronous and non-blocking

---

## Level 0 (Make the ControlPlane)

The ControlPlane manages multiple workers; frames are distributed based on their routingHash and
are processed independently by workers. Currently, only one ControlPlane instance may be active per
JVM process.

Creating the ControlPlane

```java
ControlPlane controlPlane = ControlPlane.getOrCreate("Lots of Things Doer 9000");
controlPlane.start();
```

If you want to collect the per-CPU metrics

```java
String metricPrefix = "euhedral.metrics";
MeterRegistry registry; // Any implementation of io.micrometer.core.instrument.MeterRegistry

ControlPlaneConfig config = ControlPlaneConfig.defaultConfig("Lots of Things Doer 9000", metricPrefix, registry);
ControlPlane controlPlane = ControlPlane.getOrCreate(config);
controlPlane.start();
```

metricPrefix defaults to "euhedral" if you give it a registry but pass a blank or null prefix.

---

## Level 1 (Make frames and send them in)

The routingHash defaults to the idHash provided at construction. Frames sharing a routingHash are
routed to the same execution lane in order. Ordering is only guaranteed per input stream (sink), not
across sinks.

Default frames:

- FunctionFrame
- ConsumerFrame
- RunnableFrame

```java
long idHash = ThreadLocalRandom.current().nextLong();

Function<Integer, Integer> square = x -> x * x;
Consumer<Integer> print = x -> { System.out.println(x); };

FunctionFrame<Integer, Integer> thing1 = new FunctionFrame<>(idHash, square, print, 2);
FunctionFrame<Integer, Integer> thing2 = new FunctionFrame<>(idHash, square, print, 4);
```

Create a QueueIngestSink or an ArrayIngestSink. For a QueueIngestSink, you can preload it or feed it
while it is connected.

```java
QueueIngestSink sink = new QueueIngestSink();
sink.offer(thing1);
sink.offer(thing2);
```

Give it to the ControlPlane. Euhedral is asynchronous and non-blocking. Frames will be executed in
the background.

```java
controlPlane.ingest(sink);

--- Output ---
4
16
--------------
```

Close the sink when you're done with it. This notifies the ControlPlane that no more frames will
come through it. It will then be disconnected from the ControlPlane.

```java
sink.complete();
```

---

## Level 2 (Make frames run in parallel)

**Remember: The routingHash defaults to the idHash provided at construction.**

Using the same constructs in Level 1, only a slight modification is needed to make frames execute in
parallel. You change the hash they use for routing.

`randomizeHash(seed)` mixes your idHash with the seed to generate the routingHash. This changes
where
each frame will be executed. It can only be safely done before ingestion.

The seed only needs to be changed slightly for each frame.

```java
long idHash = HasherApi.mix(12345);
long seed = HasherApi.mix(54321);

FunctionFrame<Integer, Integer> thing1 = new FunctionFrame<>(idHash, square, print, 2);
FunctionFrame<Integer, Integer> thing2 = new FunctionFrame<>(idHash, square, print, 4);

thing1.randomizeHash(seed++);
thing2.randomizeHash(seed++);
```

You can also use the seed as the idHash directly. But if you want some frames to run in order, and
some to run in parallel, randomize the hash after making them.

**Performance Note:**

Euhedral performs parallel execution best when hashes are well mixed and evenly distributed. It
relies on hash distribution to fan out work across workers. randomizeHash() uses HasherApi
internally to mix what you pass it. Using HasherApi.mix() on a random number is recommended for
creating ids and seeds because it uses a fast, high-quality hash function (xxHash64), but any
equivalent hash function will work.

---

## Level 3 (Using the frame recycler)

Recycling frames reduces allocations and GC events. They are most useful for high-frequency or
long-running workloads.

_Assumes an active ControlPlane_

```java
long password = 1234;

FrameManager<Integer, FunctionFrame<Integer, Integer>> manager = new FrameManager<>(password);

final long[] seed = {1234};
AtomicBoolean killSwitch = new AtomicBoolean(false);
Function<Integer, Integer> square = x -> x * x;
Consumer<Integer> print = x -> { System.out.println(x); };

// Randomized hash for parallel execution
FrameCreate<Integer, FunctionFrame<Integer, Integer>> generate = (idHash, data) -> {
    FunctionFrame<Integer, Integer> frame = new FunctionFrame<>(idHash, square, print, data, killSwitch, manager);
    frame.randomizeHash(seed[0]++);
    return frame;
};
FrameReplace<Integer, FunctionFrame<Integer, Integer>> replace = (data, oldFrame) -> {
    oldFrame.replace(data);
    oldFrame.randomizeHash(seed[0]++);
};

manager.setFactory(new FrameFactory<>(generate, replace));

QueueIngestSink sink = new QueueIngestSink();
controlPlane.ingest(sink);

for(int i = 0; i < 1_000_000; i++) {
    sink.offer(manager.getOrCreate(i, password));
}

sink.complete();
```

**IMPORTANT NOTE: Frames are reset after execution whether you use the recycler or not. This sets
their routingHash back to their idHash. If you
want them to keep executing in parallel, randomize the routing hash again in replace().**

---

## Level 4 (Creating your own frames)

Make a class that extends AbstractFrame.

| Function         | Description                                                                                                                 |
|------------------|-----------------------------------------------------------------------------------------------------------------------------|
| getSizeBytes()   | Used by Euhedral to estimate the impact of executing your frame and control memory usage. Doesn't need to be 100% accurate. |
| execute()        | What it does.                                                                                                               |
| isAlive()        | Checked by Euhedral. It will not execute it if it's false.                                                                  |
| kill()           | Marks the frame as inactive and prevents the frame from being executed if it has not started yet.                           |
| doFinally()      | What Euhedral does with your frame after executing. Defaults to sending it to the recycler if you set one.                  |
| throwMeAsError() | If you want to cancel a frame after it starts executing, call this and Euhedral will stop it.                               |

```java
public class MyFrame extends AbstractFrame {

    private AtomicBoolean killSwitch = new AtomicBoolean(false);
    
    public MyFrame(long idHash, FrameManager<Void, MyFrame> manager) {
        super(idHash, manager);
    }
    
    @Override
    public void execute() {
        System.out.println("Hello, world!");
    }
    
    @Override
    public long getSizeBytes() {
        return 64;    
    }
    
    @Override
    public boolean isAlive() {
        return !this.killSwitch.getOpaque();
    }
    
    @Override
    public void kill() {
        this.killSwitch.setRelease(true);
    }
    
    @Override
    public void doFinally() {
        super.doFinally();
    }
}
```
