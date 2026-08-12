package io.euhedral_execution.core.frames;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.core.impl.FrameFactory;
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
        FrameManager<Integer, PipelineFrame<Integer>> manager = PipelineFrame.<Integer>builder()
                .fanOut(value -> value * 2)
                .fanOut(Object::toString)
                .fanOut(value -> "result=" + value)
                .composeFannedOut(sink, results::add, 0L, new AtomicBoolean());
        PipelineFrame<Integer> root = manager.getOrCreate(21, 0L);

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
        FrameManager<Integer, PipelineFrame<Integer>> manager = PipelineFrame.<Integer>builder()
                .fanIn(value -> value * 2)
                .fanOut(Object::toString)
                .fanIn(value -> "result=" + value)
                .composeFannedOut(sink, results::add, 0L, new AtomicBoolean());
        PipelineFrame<Integer> root = manager.getOrCreate(21, 0L);

        List<AbstractFrame> stages = executePipeline(root, sink);

        assertThat(results).containsExactly("result=42");
        assertThat(stages).extracting(AbstractFrame::isOrdered).containsExactly(true, false, true, false);
        assertThat(stages).allMatch(stage -> stage.getIdHash() == root.getIdHash());
    }

    @Test
    void allowsAnOrderedTerminalStage() {
        QueueIngestSink sink = new QueueIngestSink();
        List<Integer> results = new ArrayList<>();
        FrameManager<Integer, PipelineFrame<Integer>> manager =
                PipelineFrame.<Integer>builder().composeFannedIn(sink, results::add, 0L, new AtomicBoolean());
        PipelineFrame<Integer> terminal = manager.getOrCreate(7, 0L);

        execute(terminal);

        assertThat(results).containsExactly(7);
        assertThat(terminal.isOrdered()).isTrue();
    }

    @Test
    void acceptsAConsumerOnlyPipelineAndRecyclesItsRoot() {
        QueueIngestSink sink = new QueueIngestSink();
        List<String> results = new ArrayList<>();
        FrameManager<String, PipelineFrame<String>> recycler =
                PipelineFrame.<String>builder().composeFannedOut(sink, results::add, 17L, new AtomicBoolean());
        PipelineFrame<String> root = recycler.getOrCreate("value", 17L);

        execute(root);

        assertThat(results).containsExactly("value");
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();
        assertThat(sink.size()).isZero();
    }

    @Test
    void frameManagerReusesTheRootAndReplacesItsInput() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();
        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder().fanIn(value -> value * 2);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                pipeline.composeFannedIn(sink, results::add, 17L, new AtomicBoolean());
        PipelineFrame<Integer> first = recycler.getOrCreate(3, 17L);
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
                PipelineFrame.<Integer>builder().fanIn(value -> value * 2);
        AtomicBoolean firstKillSwitch = new AtomicBoolean();
        AtomicBoolean secondKillSwitch = new AtomicBoolean();
        List<Integer> firstResults = new ArrayList<>();
        List<Integer> secondResults = new ArrayList<>();

        FrameManager<Integer, PipelineFrame<Integer>> firstManager =
                pipeline.composeFannedOut(sink, firstResults::add, 17L, firstKillSwitch);
        FrameManager<Integer, PipelineFrame<Integer>> secondManager =
                pipeline.composeFannedOut(sink, secondResults::add, 29L, secondKillSwitch);

        PipelineFrame<Integer> first = firstManager.getOrCreate(3, 17L);
        PipelineFrame<Integer> second = secondManager.getOrCreate(5, 29L);

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
                PipelineFrame.<Integer>builder().fanIn(value -> value + 1);
        PipelineFrame.Builder<Integer, Integer> doubled = prefix.fanOut(value -> value * 2);
        PipelineFrame.Builder<Integer, String> labeled = prefix.fanIn(value -> "value=" + value);
        AtomicBoolean doubledKillSwitch = new AtomicBoolean();
        AtomicBoolean labeledKillSwitch = new AtomicBoolean();
        List<Integer> doubledResults = new ArrayList<>();
        List<String> labeledResults = new ArrayList<>();

        FrameManager<Integer, PipelineFrame<Integer>> doubledManager =
                doubled.composeFannedOut(sink, doubledResults::add, 17L, doubledKillSwitch);
        FrameManager<Integer, PipelineFrame<Integer>> labeledManager =
                labeled.composeFannedIn(sink, labeledResults::add, 29L, labeledKillSwitch);

        PipelineFrame<Integer> doubledRoot = doubledManager.getOrCreate(2, 17L);
        PipelineFrame<Integer> labeledRoot = labeledManager.getOrCreate(4, 29L);

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
                PipelineFrame.<Integer>builder().fanOut(value -> value + 1);
        List<Integer> firstResult = new ArrayList<>();
        List<String> secondResult = new ArrayList<>();

        FrameManager<Integer, PipelineFrame<Integer>> firstManager = prefix.fanOut(value -> value * 2)
                .composeFannedOut(firstSink, firstResult::add, 0L, new AtomicBoolean());
        PipelineFrame<Integer> first = firstManager.getOrCreate(4, 0L);

        FrameManager<Integer, PipelineFrame<Integer>> secondManager =
                prefix.fanOut(Object::toString).composeFannedOut(firstSink, secondResult::add, 0L, new AtomicBoolean());
        PipelineFrame<Integer> second = secondManager.getOrCreate(7, 0L);

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
        FrameManager<Integer, PipelineFrame<Integer>> recycler = PipelineFrame.<Integer>builder()
                .fanOut(value -> value + 1)
                .composeFannedOut(sink, ignored -> {}, 17L, killSwitch);
        PipelineFrame<Integer> root = recycler.getOrCreate(1, 17L);

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
        FrameManager<Integer, PipelineFrame<Integer>> recycler = PipelineFrame.<Integer>builder()
                .fanOut(value -> value + 1)
                .fanOut(value -> {
                    throw new IllegalStateException("failed stage");
                })
                .composeFannedOut(sink, ignored -> {}, 17L, new AtomicBoolean());
        PipelineFrame<Integer> root = recycler.getOrCreate(1, 17L);

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
    void pooledReuseReusesEntireStageChainWithoutReconstruction() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();
        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder().fanOut(v -> v + 1).fanIn(v -> v * 2);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                pipeline.composeFannedOut(sink, results::add, 17L, new AtomicBoolean());
        PipelineFrame<Integer> firstRoot = recycler.getOrCreate(10, 17L);
        PipelineFrame<?> firstStage1 = firstRoot.getNextFrame();
        assertThat(firstStage1).isNotNull();
        PipelineFrame<?> firstTerminal = firstStage1.getNextFrame();
        assertThat(firstTerminal).isNotNull();

        executePipeline(firstRoot, sink);
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();

        PipelineFrame<Integer> reusedRoot = recycler.getOrCreate(20, 17L);
        assertThat(reusedRoot).isSameAs(firstRoot);
        assertThat(reusedRoot.getNextFrame()).isSameAs(firstStage1);
        assertThat(reusedRoot.getNextFrame().getNextFrame()).isSameAs(firstTerminal);

        executePipeline(reusedRoot, sink);
        assertThat(results).containsExactly(22, 42);
    }

    @Test
    void frameManagerPreventsFactoryOverwrite() {
        FrameManager<String, AbstractFrame> manager = new FrameManager<>(17L);
        FrameFactory<String, AbstractFrame> factory1 = new FrameFactory<>((id, d) -> null, (d, f) -> {});
        FrameFactory<String, AbstractFrame> factory2 = new FrameFactory<>((id, d) -> null, (d, f) -> {});

        manager.setFactory(factory1);
        manager.setFactory(factory1); // Re-setting the exact same factory instance succeeds

        assertThatThrownBy(() -> manager.setFactory(factory2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already set");
    }

    @Test
    void childFrameCancellationDelegatesToKillSwitchAfterReuse() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();
        AtomicBoolean killSwitch = new AtomicBoolean();
        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder().fanOut(v -> v * 2);

        FrameManager<Integer, PipelineFrame<Integer>> manager =
                pipeline.composeFannedOut(sink, results::add, 17L, killSwitch);
        PipelineFrame<Integer> first = manager.getOrCreate(10, 17L);
        executePipeline(first, sink);

        PipelineFrame<Integer> second = manager.getOrCreate(20, 17L);
        PipelineFrame<?> child = second.getNextFrame();
        assertThat(child).isNotNull();

        child.kill();
        assertThat(killSwitch.get()).isTrue();
        assertThat(child.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
    }

    @Test
    void orderedRootRoutingStateOnCreationAndRecycling() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();

        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder().fanIn(v -> v + 1).fanOut(v -> v * 2);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                pipeline.composeFannedOut(sink, results::add, 17L, new AtomicBoolean());
        PipelineFrame<Integer> firstRoot = recycler.getOrCreate(10, 17L);
        assertThat(firstRoot.isOrdered()).isTrue();
        assertThat(firstRoot.getRoutingHash()).isEqualTo(firstRoot.getIdHash());

        executePipeline(firstRoot, sink);
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();

        PipelineFrame<Integer> secondRoot = recycler.getOrCreate(20, 17L);
        assertThat(secondRoot).isSameAs(firstRoot);
        assertThat(secondRoot.isOrdered()).isTrue();
        assertThat(secondRoot.getRoutingHash()).isEqualTo(secondRoot.getIdHash());
    }

    @Test
    void parallelRootRoutingStateOnCreationAndRecycling() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();

        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder().fanOut(v -> v + 1).fanIn(v -> v * 2);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                pipeline.composeFannedOut(sink, results::add, 17L, new AtomicBoolean());
        PipelineFrame<Integer> firstRoot = recycler.getOrCreate(10, 17L);
        assertThat(firstRoot.isOrdered()).isFalse();
        assertThat(firstRoot.getRoutingHash()).isNotEqualTo(firstRoot.getIdHash());

        executePipeline(firstRoot, sink);
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();

        PipelineFrame<Integer> secondRoot = recycler.getOrCreate(20, 17L);
        assertThat(secondRoot).isSameAs(firstRoot);
        assertThat(secondRoot.isOrdered()).isFalse();
        assertThat(secondRoot.getRoutingHash()).isNotEqualTo(secondRoot.getIdHash());
    }

    @Test
    void mixedOrderedAndParallelDownstreamStagesRoutingStateOnCreationAndRecycling() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();

        PipelineFrame.Builder<Integer, Integer> pipeline = PipelineFrame.<Integer>builder()
                .fanOut(v -> v + 1)
                .fanIn(v -> v * 2)
                .fanOut(v -> v - 3);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                pipeline.composeFannedIn(sink, results::add, 17L, new AtomicBoolean());
        PipelineFrame<Integer> firstRoot = recycler.getOrCreate(10, 17L);
        PipelineFrame<?> stage1 = firstRoot.getNextFrame();
        PipelineFrame<?> stage2 = stage1.getNextFrame();
        PipelineFrame<?> terminal = stage2.getNextFrame();

        assertThat(firstRoot.isOrdered()).isFalse();
        assertThat(stage1.isOrdered()).isTrue();
        assertThat(stage2.isOrdered()).isFalse();
        assertThat(terminal.isOrdered()).isTrue();

        executePipeline(firstRoot, sink);
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();

        PipelineFrame<Integer> secondRoot = recycler.getOrCreate(20, 17L);
        assertThat(secondRoot).isSameAs(firstRoot);
        PipelineFrame<?> secondStage1 = secondRoot.getNextFrame();
        PipelineFrame<?> secondStage2 = secondStage1.getNextFrame();
        PipelineFrame<?> secondTerminal = secondStage2.getNextFrame();

        assertThat(secondRoot.isOrdered()).isFalse();
        assertThat(secondStage1.isOrdered()).isTrue();
        assertThat(secondStage2.isOrdered()).isFalse();
        assertThat(secondTerminal.isOrdered()).isTrue();
    }

    @Test
    void passwordValidationOnInitialCreation() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());

        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder().fanIn(v -> v + 1);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                pipeline.composeFannedIn(sink, v -> {}, 17L, new AtomicBoolean());

        assertThatThrownBy(() -> recycler.getOrCreate(10, 99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Incorrect password");

        PipelineFrame<Integer> frame = recycler.getOrCreate(10, 17L);
        assertThat(frame).isNotNull();
    }

    @Test
    void passwordValidationOnRecycledReuse() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());

        PipelineFrame.Builder<Integer, Integer> pipeline =
                PipelineFrame.<Integer>builder().fanIn(v -> v + 1);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                pipeline.composeFannedIn(sink, v -> {}, 17L, new AtomicBoolean());

        PipelineFrame<Integer> first = recycler.getOrCreate(10, 17L);
        executePipeline(first, sink);
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();

        assertThatThrownBy(() -> recycler.getOrCreate(20, 99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Incorrect password");

        PipelineFrame<Integer> second = recycler.getOrCreate(20, 17L);
        assertThat(second).isSameAs(first);
    }

    @Test
    void filtersElementsAtIntermediateStagePassingAndFailing() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();
        PipelineFrame.Builder<Integer, Integer> builder =
                PipelineFrame.<Integer>builder().fanOut(v -> v * 2).filterOutput(v -> v > 10);
        FrameManager<Integer, PipelineFrame<Integer>> manager =
                builder.composeFannedOut(sink, results::add, 0L, new AtomicBoolean());

        PipelineFrame<Integer> failingRoot = manager.getOrCreate(3, 0L);
        executePipeline(failingRoot, sink);
        assertThat(results).isEmpty();
        assertThat(sink.size()).isZero();

        PipelineFrame<Integer> passingRoot = manager.getOrCreate(7, 0L);
        executePipeline(passingRoot, sink);
        assertThat(results).containsExactly(14);
    }

    @Test
    void filtersElementsAtTerminalStage() {
        QueueIngestSink sink = new QueueIngestSink();
        List<Integer> results = new ArrayList<>();
        PipelineFrame.Builder<Integer, Integer> builder =
                PipelineFrame.<Integer>builder().filterOutput(v -> v % 2 == 0);
        FrameManager<Integer, PipelineFrame<Integer>> manager =
                builder.composeFannedOut(sink, results::add, 0L, new AtomicBoolean());

        PipelineFrame<Integer> passingFrame = manager.getOrCreate(4, 0L);
        execute(passingFrame);
        assertThat(results).containsExactly(4);

        results.clear();
        PipelineFrame<Integer> failingFrame = manager.getOrCreate(5, 0L);
        execute(failingFrame);
        assertThat(results).isEmpty();
    }

    @Test
    void filtersMultipleStagesSequentially() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();
        PipelineFrame.Builder<Integer, Integer> builder = PipelineFrame.<Integer>builder()
                .fanIn(v -> v + 1)
                .filterOutput(v -> v > 5)
                .fanOut(v -> v * 2)
                .filterOutput(v -> v < 20);
        FrameManager<Integer, PipelineFrame<Integer>> manager =
                builder.composeFannedOut(sink, results::add, 0L, new AtomicBoolean());

        PipelineFrame<Integer> stage1Failing = manager.getOrCreate(3, 0L);
        executePipeline(stage1Failing, sink);
        assertThat(results).isEmpty();

        PipelineFrame<Integer> stage2Failing = manager.getOrCreate(15, 0L);
        executePipeline(stage2Failing, sink);
        assertThat(results).isEmpty();

        PipelineFrame<Integer> passing = manager.getOrCreate(6, 0L);
        executePipeline(passing, sink);
        assertThat(results).containsExactly(14);
    }

    @Test
    void filterRecyclesRootFrameAndClearsFilteredFlagOnReuse() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Integer> results = new ArrayList<>();
        PipelineFrame.Builder<Integer, Integer> builder =
                PipelineFrame.<Integer>builder().fanIn(v -> v * 2).filterOutput(v -> v > 10);

        FrameManager<Integer, PipelineFrame<Integer>> recycler =
                builder.composeFannedIn(sink, results::add, 17L, new AtomicBoolean());

        PipelineFrame<Integer> first = recycler.getOrCreate(3, 17L);
        executePipeline(first, sink);

        assertThat(results).isEmpty();
        assertThat(recycler.getRecycleQueue().sizeLong()).isOne();

        PipelineFrame<Integer> reused = recycler.getOrCreate(8, 17L);
        assertThat(reused).isSameAs(first);

        executePipeline(reused, sink);
        assertThat(results).containsExactly(16);
    }

    @Test
    void filterRejectsNullPredicate() {
        QueueIngestSink sink = new QueueIngestSink();

        assertThatNullPointerException()
                .isThrownBy(() -> PipelineFrame.<Integer>builder().filterOutput(null));
    }

    @Test
    void filterInBranchedBuilderPreservesIndependence() {
        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        PipelineFrame.Builder<Integer, Integer> base =
                PipelineFrame.<Integer>builder().fanOut(v -> v + 1);

        PipelineFrame.Builder<Integer, Integer> filteredPath = base.filterOutput(v -> v > 5);
        PipelineFrame.Builder<Integer, Integer> unfilteredPath = base.fanOut(v -> v * 2);

        List<Integer> filteredResults = new ArrayList<>();
        List<Integer> unfilteredResults = new ArrayList<>();

        FrameManager<Integer, PipelineFrame<Integer>> filteredManager =
                filteredPath.composeFannedOut(sink, filteredResults::add, 0L, new AtomicBoolean());
        FrameManager<Integer, PipelineFrame<Integer>> unfilteredManager =
                unfilteredPath.composeFannedOut(sink, unfilteredResults::add, 0L, new AtomicBoolean());

        PipelineFrame<Integer> filteredFrame = filteredManager.getOrCreate(2, 0L);
        PipelineFrame<Integer> unfilteredFrame = unfilteredManager.getOrCreate(2, 0L);

        executePipeline(filteredFrame, sink);
        executePipeline(unfilteredFrame, sink);

        assertThat(filteredResults).isEmpty();
        assertThat(unfilteredResults).containsExactly(6);
    }

    @Test
    void filtersMultipleStagesAcrossDifferentObjectTypes() {
        record UserPayload(String value, int length) {}

        QueueIngestSink sink = new QueueIngestSink();
        sink.getDelegate().addDownstream(new TestReceiver());
        List<Boolean> results = new ArrayList<>();

        PipelineFrame.Builder<Integer, Boolean> builder = PipelineFrame.<Integer>builder()
                .fanOut(Object::toString)
                .filterOutput(s -> s.length() >= 2)
                .fanIn(s -> new UserPayload(s, s.length()))
                .filterOutput(payload -> payload.length() % 2 == 0)
                .fanOut(payload -> payload.value().startsWith("1"))
                .filterOutput(b -> b);
        FrameManager<Integer, PipelineFrame<Integer>> manager =
                builder.composeFannedOut(sink, results::add, 0L, new AtomicBoolean());

        // Filtered at stage 1 (single-digit string length < 2)
        PipelineFrame<Integer> failingStage1 = manager.getOrCreate(5, 0L);
        executePipeline(failingStage1, sink);
        assertThat(results).isEmpty();

        // Filtered at stage 3 (does not start with "1")
        PipelineFrame<Integer> failingStage3 = manager.getOrCreate(35, 0L);
        executePipeline(failingStage3, sink);
        assertThat(results).isEmpty();

        // Passes all stages (length >= 2, even length 4, starts with "1")
        PipelineFrame<Integer> passing = manager.getOrCreate(1234, 0L);
        executePipeline(passing, sink);
        assertThat(results).containsExactly(true);
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
