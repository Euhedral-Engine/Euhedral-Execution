package io.euhedral_execution.core.frames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.core.ingest.QueueIngestSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import test_utils.TestReceiver;

class PipelineFrameTest {

    @Test
    void composesOrdinaryLambdasAcrossTypes() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<String> results = new ArrayList<>();
        PipelineFrame<Integer> root = PipelineFrame.<Integer>builder(sink)
                .mapParallel(value -> value * 2)
                .mapParallel(Object::toString)
                .mapParallel(value -> "result=" + value)
                .buildParallel(21, results::add);

        List<AbstractFrame> stages = executePipeline(root, sink);

        assertThat(results).containsExactly("result=42");
        assertThat(stages).hasSize(4);
        assertThat(stages).allMatch(stage -> stage.getIdHash() == root.getIdHash());
        assertThat(stages).allMatch(stage -> !stage.isOrdered());
        assertThat(stages).extracting(AbstractFrame::getRoutingHash).doesNotHaveDuplicates();
    }

    @Test
    void selectsOrderedOrParallelRoutingForEveryStage() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<String> results = new ArrayList<>();
        PipelineFrame<Integer> root = PipelineFrame.<Integer>builder(sink)
                .mapOrdered(value -> value * 2)
                .mapParallel(Object::toString)
                .mapOrdered(value -> "result=" + value)
                .buildParallel(21, results::add);

        List<AbstractFrame> stages = executePipeline(root, sink);

        assertThat(results).containsExactly("result=42");
        assertThat(stages).extracting(AbstractFrame::isOrdered).containsExactly(true, false, true, false);
        assertThat(stages).allMatch(stage -> stage.getIdHash() == root.getIdHash());
    }

    @Test
    void allowsAnOrderedTerminalStage() {
        QueueIngestSink sink = new QueueIngestSink();
        List<Integer> results = new ArrayList<>();
        PipelineFrame<Integer> terminal = PipelineFrame.<Integer>builder(sink).buildOrdered(7, results::add);

        execute(terminal);

        assertThat(results).containsExactly(7);
        assertThat(terminal.isOrdered()).isTrue();
    }

    @Test
    void acceptsAConsumerOnlyPipelineAndRecyclesItsRoot() {
        QueueIngestSink sink = new QueueIngestSink();
        FrameManager<String, PipelineFrame<String>> recycler = new FrameManager<>(8, 17L);
        List<String> results = new ArrayList<>();
        PipelineFrame<String> root =
                PipelineFrame.<String>builder(sink).buildParallel("value", results::add, recycler, 17L);

        execute(root);

        assertThat(results).containsExactly("value");
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
        assertThat(sink.size()).isZero();
    }

    @Test
    void frameManagerReusesTheRootAndReplacesItsInput() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        FrameManager<Integer, PipelineFrame<Integer>> recycler = new FrameManager<>(8, 17L);
        List<Integer> results = new ArrayList<>();
        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder(sink).mapOrdered(value -> value * 2);

        PipelineFrame<Integer> first = pipeline.buildOrdered(3, results::add, recycler, 17L);
        executePipeline(first, sink);
        PipelineFrame<Integer> reused = recycler.getOrCreate(5, 17L);
        executePipeline(reused, sink);

        assertThat(reused).isSameAs(first);
        assertThat(results).containsExactly(6, 10);
        assertThat(reused.isOrdered()).isTrue();
    }

    @Test
    void sameBuilderCreatesIndependentManagedPipelines() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder(sink).mapOrdered(value -> value * 2);
        FrameManager<Integer, PipelineFrame<Integer>> firstManager = new FrameManager<>(8, 17L);
        FrameManager<Integer, PipelineFrame<Integer>> secondManager = new FrameManager<>(8, 29L);
        AtomicBoolean firstKillSwitch = new AtomicBoolean();
        AtomicBoolean secondKillSwitch = new AtomicBoolean();
        List<Integer> firstResults = new ArrayList<>();
        List<Integer> secondResults = new ArrayList<>();

        PipelineFrame<Integer> first = pipeline.buildParallel(3, firstResults::add, firstKillSwitch, firstManager, 17L);
        PipelineFrame<Integer> second =
                pipeline.buildParallel(5, secondResults::add, secondKillSwitch, secondManager, 29L);

        assertThat(first).isNotSameAs(second);
        assertThat(first.recycler).isSameAs(firstManager);
        assertThat(second.recycler).isSameAs(secondManager);
        assertThat(first.killSwitch).isSameAs(firstKillSwitch);
        assertThat(second.killSwitch).isSameAs(secondKillSwitch);

        first.kill();
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isTrue();
        assertThat(secondKillSwitch).isFalse();

        first.doFinally();
        assertThat(firstManager.getRecycleQueue().sizeLong()).isOne();
        assertThat(secondManager.getRecycleQueue().sizeLong()).isZero();

        executePipeline(second, sink);
        PipelineFrame<Integer> reusedSecond = secondManager.getOrCreate(7, 29L);
        executePipeline(reusedSecond, sink);
        assertThat(secondResults).containsExactly(10, 14);
        assertThat(firstResults).isEmpty();
    }

    @Test
    void sharedPrefixBuildsKeepTheirManagerFactoriesIndependent() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        PipelineFrame.Builder<Integer, Integer> prefix =
                PipelineFrame.<Integer>builder(sink).mapOrdered(value -> value + 1);
        PipelineFrame.Builder<Integer, Integer> doubled = prefix.mapParallel(value -> value * 2);
        PipelineFrame.Builder<Integer, String> labeled = prefix.mapOrdered(value -> "value=" + value);
        FrameManager<Integer, PipelineFrame<Integer>> doubledManager = new FrameManager<>(8, 17L);
        FrameManager<Integer, PipelineFrame<Integer>> labeledManager = new FrameManager<>(8, 29L);
        AtomicBoolean doubledKillSwitch = new AtomicBoolean();
        AtomicBoolean labeledKillSwitch = new AtomicBoolean();
        List<Integer> doubledResults = new ArrayList<>();
        List<String> labeledResults = new ArrayList<>();

        PipelineFrame<Integer> doubledRoot =
                doubled.buildParallel(2, doubledResults::add, doubledKillSwitch, doubledManager, 17L);
        PipelineFrame<Integer> labeledRoot =
                labeled.buildOrdered(4, labeledResults::add, labeledKillSwitch, labeledManager, 29L);

        executePipeline(doubledRoot, sink);
        executePipeline(labeledRoot, sink);

        PipelineFrame<Integer> recycledDoubled = doubledManager.getOrCreate(5, 17L);
        executePipeline(recycledDoubled, sink);

        assertThat(recycledDoubled).isSameAs(doubledRoot);
        assertThat(doubledResults).containsExactly(6, 12);
        assertThat(labeledResults).containsExactly("value=5");
        assertThat(doubledKillSwitch).isFalse();
        assertThat(labeledKillSwitch).isFalse();
        assertThat(doubledRoot.recycler).isSameAs(doubledManager);
        assertThat(labeledRoot.recycler).isSameAs(labeledManager);
    }

    @Test
    void branchesWithoutMutatingTheSharedBuilderPrefix() {
        QueueIngestSink firstSink = new QueueIngestSink();
        firstSink.getDelegate().addDownstream(new TestReceiver());
        PipelineFrame.Builder<Integer, Integer> prefix =
                PipelineFrame.<Integer>builder(firstSink).mapParallel(value -> value + 1);
        List<Integer> firstResult = new ArrayList<>();
        List<String> secondResult = new ArrayList<>();

        PipelineFrame<Integer> first = prefix.mapParallel(value -> value * 2).buildParallel(4, firstResult::add);
        PipelineFrame<Integer> second = prefix.mapParallel(Object::toString).buildParallel(7, secondResult::add);

        executePipeline(first, firstSink);
        executePipeline(second, firstSink);

        assertThat(firstResult).containsExactly(10);
        assertThat(secondResult).containsExactly("8");
    }

    @Test
    void cancellationDoesNotAdvanceThePipelineAndRecyclesTheRoot() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        AtomicBoolean killSwitch = new AtomicBoolean();
        FrameManager<Integer, PipelineFrame<Integer>> recycler = new FrameManager<>(8, 17L);
        PipelineFrame<Integer> root = PipelineFrame.<Integer>builder(sink)
                .mapParallel(value -> value + 1)
                .buildParallel(1, ignored -> {}, killSwitch, recycler, 17L);

        execute(root);
        AbstractFrame intermediate = poll(sink);

        assertThat(root.recycler).isSameAs(recycler);
        assertThat(root.killSwitch).isSameAs(killSwitch);
        assertThat(intermediate.recycler).isNull();
        assertThat(intermediate.killSwitch).isNull();

        intermediate.kill();
        intermediate.doFinally();

        assertThat(killSwitch).isTrue();
        assertThat(intermediate.isAlive()).isFalse();
        assertThat(sink.size()).isZero();
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
    }

    @Test
    void downstreamErrorRecyclesTheRoot() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        FrameManager<Integer, PipelineFrame<Integer>> recycler = new FrameManager<>(8, 17L);
        PipelineFrame<Integer> root = PipelineFrame.<Integer>builder(sink)
                .mapParallel(value -> value + 1)
                .mapParallel(value -> {
                    throw new IllegalStateException("failed stage");
                })
                .buildParallel(1, ignored -> {}, recycler, 17L);

        execute(root);
        AbstractFrame failingStage = poll(sink);

        assertThatThrownBy(failingStage::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed stage");
        failingStage.doFinallyWithError(new IllegalStateException("failed stage"));
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
        assertThat(sink.size()).isZero();
    }

    @Test
    void rejectsNullArgumentsAtTheBuilderBoundary() {
        QueueIngestSink sink = new QueueIngestSink();

        assertThatNullPointerException().isThrownBy(() -> PipelineFrame.builder(null));
        assertThatNullPointerException()
                .isThrownBy(() -> PipelineFrame.<String>builder(sink).mapParallel(null));
        assertThatNullPointerException()
                .isThrownBy(() -> PipelineFrame.<String>builder(sink).buildParallel(null, ignored -> {}));
        assertThatNullPointerException()
                .isThrownBy(() -> PipelineFrame.<String>builder(sink).buildParallel("value", null));
    }

    private static List<AbstractFrame> executePipeline(AbstractFrame root, QueueIngestSink sink) {
        List<AbstractFrame> stages = new ArrayList<>();
        AbstractFrame current = root;
        while (current != null) {
            stages.add(current);
            execute(current);
            current = sink.size() == 0 ? null : poll(sink);
        }
        return stages;
    }

    private static void execute(AbstractFrame frame) {
        frame.execute();
        frame.doFinally();
    }

    private static AbstractFrame poll(QueueIngestSink sink) {
        AbstractFrame[] result = new AbstractFrame[1];
        long pulled = sink.getDelegate().pull(frame -> result[0] = frame, ignored -> false, 1);
        assertThat(pulled).isOne();
        return result[0];
    }
}
