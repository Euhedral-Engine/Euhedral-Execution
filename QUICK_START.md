# Quick Start for Using Euhedral

Euhedral is a low-latency execution system designed for parallel execution with minimal coordination
overhead.

- Work is represented as frames and routed through worker pipelines based on their hashes.
- Euhedral is asynchronous and non-blocking

---

## VM Flags

These are required to run your program with Euhedral.

```
-XX:+UseThreadPriorities
--add-opens java.base/java.util=ALL-UNNAMED
```

## Level 0 (Make the ControlPlaneLattice)

The ControlPlaneLattice manages multiple workers; frames are distributed based on their routingHash
and are processed independently by workers. Currently, only one ControlPlaneLattice instance may be
active per JVM process.

Creating the ControlPlaneLattice

```java
ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate();
controlPlane.start();
```

If you want to collect the per-CPU metrics

```java
String name = "ThingDoer9000";
String shardName = "ShardOfThingDoer";
String metricPrefix = "euhedral.metrics";
MeterRegistry registry; // Any implementation of io.micrometer.core.instrument.MeterRegistry

ControlPlaneConfig config = ControlPlaneConfig.ofDefaults(name, shardName, metricPrefix, registry);
ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);
controlPlane.start();
```

metricPrefix defaults to "euhedral" if you give it a registry but pass a blank or null prefix.

---

## Level 1 (Executing work)

The most straight-forward way to use Euhedral is with one of the default sinks.

The following sinks will run your functions asynchronously when you give them data. You can give
them data before or after you give the sink to the ControlPlaneLattice.

Default Sinks:

- FunctionIngestSink
- ConsumerIngestSink

_Assumes a running ControlPlaneLattice_

```java
Function<Integer, Integer> square = x -> x * x;
Consumer<Integer> print = x -> System.out.println(x);

// `false` = execute in order
// `true` = execute in parallel
FunctionIngestSink<Integer, Integer> sink = new FunctionIngestSink<>(square, print, false);

controlPlane.addUpstream(sink);

int x = 2;
List<Integer> nums = List.of(4, 8, 16);

sink.push(x);
sink.push(nums);

--- Output ---
4
16
64
256
```

Remember to complete the sink when you're done and it will disconnect from the ControlPlaneLattice.

```java
sink.completeGracefully();
```

## Level 2 (Manually make frames and send them in)

The routingHash defaults to the idHash provided at construction. Frames sharing a routingHash are
routed to the same execution lane in order. Ordering is only guaranteed per input stream (sink), not
across sinks.

Default frames:

- ArrayFrame
- CollectionFrame
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

Give it to the ControlPlaneLattice. Frames will be executed asynchronously.

```java
controlPlane.addUpstream(sink);

--- Output ---
4
16
--------------
```

Close the sink when you're done with it. This notifies the ControlPlaneLattice that no more frames
will come through it and disconnect it.

```java
sink.completeGracefully();
```

---

## Level 3 (Make frames run in parallel)

**Remember: The routingHash defaults to the idHash provided at construction.**

Using the same setup as Level 2, only a slight change is needed to make frames execute in
parallel. Modify the hash they use for routing.

Every frame with the same idHash coming from the same ingest source will execute in the order they
were received. Calling `randomizeHash(seed)` on the frame mixes its idHash with the seed to generate
a new routingHash. This changes where each frame will be executed. It can only be safely done before
ingestion.

The seed only needs to be changed slightly for each frame.

```java
long idHash = HasherApi.mix(12345);
long seed = HasherApi.mix(54321);

FunctionFrame<Integer, Integer> thing1 = new FunctionFrame<>(idHash, square, print, 2);
FunctionFrame<Integer, Integer> thing2 = new FunctionFrame<>(idHash, square, print, 4);

thing1.randomizeHash(seed++);
thing2.randomizeHash(seed++);
```

**Performance Note:**

Euhedral performs parallel execution best when hashes are well mixed and evenly distributed. It
relies on hash distribution to fan out work across the system. randomizeHash() uses HasherApi
internally to mix what you pass it. Using HasherApi.mix() on a random number is recommended for
creating ids and seeds because it uses a fast, high-quality hash function (xxHash64), but any
equivalent hash function will work.

---

## Level 4 (Creating your own frames)

Make a class that extends AbstractFrame.

| Function                        | Description                                                                                                                                                                                                                                               |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| execute()                       | What it does.                                                                                                                                                                                                                                             |
| isAlive()                       | Checked by Euhedral. It will not execute it if it's false.                                                                                                                                                                                                |
| kill()                          | Marks the frame as inactive and prevents the frame from being executed if it has not started yet.                                                                                                                                                         |
| doFinally()                     | What Euhedral does with your frame after executing. Defaults to sending it to the recycler if you've set one.                                                                                                                                             |
| doFinallyWithError(Throwable t) | If execute() throws an unexpected error, Euhedral will call this instead of doFinally()                                                                                                                                                                   |
| throwCancelSignal()             | If you want to cancel a frame after it starts executing, call this and Euhedral will stop it. This will throw a specific RuntimeException (AbstractFrame.CancelSignal) that should be filtered out and thrown again if caught in your execute() function. |

```java
public class MyCustomFrame extends AbstractFrame {

    private final AtomicBoolean killSwitch;
    private MyDataType payload;
    
    public MyFrame(long idHash, MyDataType payload, AtomicBoolean killSwitch, FrameManager<Void, MyFrame> manager) {
        super(idHash, manager);
        this.killSwitch = killSwitch;
        this.payload = payload;
    }
    
    @Override
    public void execute() {
        System.out.println(payload);
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
    public void doFinallyWithError(Throwable t) {
        System.err.println(t);
        super.recycle();    
    }
    
    @Override
    public void doFinally() {
        super.doFinally();
    }
    
    public void replace(MyDataType payload) {
        this.payload = payload;
    }
}
```

---

## Level 5 (Using the frame recycler)

Recycling frames reduces allocations and GC events. They are most useful for high-volume or
long-running workloads.

_Assumes an active ControlPlaneLattice_

```java
long password = 1234;

FrameManager<MyDataType, MyCustomFrame> manager = new FrameManager<>(password);

final long[] seed = {1234};
AtomicBoolean killSwitch = new AtomicBoolean(false);

// Randomized hash for parallel execution
FrameCreate<MyDataType, MyCustomFrame> generate = (idHash, data) -> {
    MyCustomFrame frame = new MyCustomFrame(idHash, data, killSwitch, manager);
    frame.randomizeHash(seed[0]++);
    return frame;
};
FrameReplace<MyDataType, MyCustomFrame> replace = (data, oldFrame) -> {
    oldFrame.replace(data);
    oldFrame.randomizeHash(seed[0]++);
};

manager.setFactory(new FrameFactory<>(generate, replace));

QueueIngestSink sink = new QueueIngestSink();
controlPlane.addUpstream(sink);

for(int i = 0; i < 1_000_000; i++) {
    sink.offer(manager.getOrCreate(i, password));
}

sink.completeGracefully();
```

**IMPORTANT NOTE: Frames are reset after execution whether if you use the FrameManager. This sets
their routingHash back to their idHash. If you want them to continue executing in parallel,
randomize the routing hash again in replace().**
