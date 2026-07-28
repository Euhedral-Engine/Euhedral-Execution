package io.euhedral_execution.training.learning;

import io.euhedral_execution.training.data.*;
import java.util.*;

public final class ScenarioModelEvaluator {
    private ScenarioModelEvaluator() {}
    public static EvaluationSummary evaluate(String kind, ScenarioFeatureSet set,
            List<ScenarioLearningRow> rows, List<PolicyPredictionCurve> curves) {
        return evaluate(kind, "all", set, rows, curves, false);
    }

    static EvaluationSummary evaluate(String kind, String foldId, ScenarioFeatureSet set,
            List<ScenarioLearningRow> rows, List<PolicyPredictionCurve> curves,
            boolean insufficientContextVariation) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(foldId);
        Objects.requireNonNull(set);
        Objects.requireNonNull(rows);
        Objects.requireNonNull(curves);
        if (rows.isEmpty()) throw new IllegalArgumentException("Evaluation rows are empty");
        record Key(PolicyId policy, SourceScenario scenario) {}
        TreeMap<Key, ScenarioPrediction> predictions = new TreeMap<>(Comparator
                .comparing((Key k)->k.policy).thenComparing(k->k.scenario));
        for(var curve:curves) for(var prediction:curve.scenarios())
            if(predictions.put(new Key(curve.policy().id(),prediction.scenario()),prediction)!=null)
                throw new IllegalArgumentException("Duplicate prediction");
        TreeMap<SourceScenario,List<ScenarioLearningRow>> groups=new TreeMap<>();
        for(var row:rows) groups.computeIfAbsent(row.scenario(),x->new ArrayList<>()).add(row);
        ArrayList<ScenarioEvaluationMetrics> metrics=new ArrayList<>();
        CompensatedSum microError = new CompensatedSum();
        int total=0;
        for(var entry:groups.entrySet()) {
            List<ScenarioLearningRow> group=entry.getValue().stream().sorted().toList();
            ArrayList<ScenarioPrediction> ps=new ArrayList<>();
            for(var row:group) { var p=predictions.remove(new Key(row.policy().id(),row.scenario()));
                if(p==null) throw new IllegalArgumentException("Missing prediction"); ps.add(p); }
            int n=group.size(), top=0;
            CompensatedSum ae = new CompensatedSum(), se = new CompensatedSum();
            CompensatedSum bias = new CompensatedSum(), width = new CompensatedSum();
            CompensatedSum epi = new CompensatedSum(), range = new CompensatedSum();
            int coverage=0;
            double[] actual=new double[n], predicted=new double[n];
            for(int i=0;i<n;i++){double a=group.get(i).quality(),p=ps.get(i).predictedQuality(),e=p-a;
                actual[i]=a;predicted[i]=p;ae.add(StrictMath.abs(e));se.add(e*e);bias.add(e);
                width.add(ps.get(i).qualityIntervalHigh()-ps.get(i).qualityIntervalLow());
                if(ps.get(i).qualityIntervalLow()<=a&&a<=ps.get(i).qualityIntervalHigh())coverage++;
                epi.add(ps.get(i).epistemicStdDev());
                range.add(ps.get(i).disagreementRange());
                if(a>=.9)top++;}
            int k=StrictMath.max(1,(int)StrictMath.ceil(.1*n));
            ArrayList<Integer> order=new ArrayList<>();for(int i=0;i<n;i++)order.add(i);
            order.sort(Comparator.<Integer>comparingDouble(i->-predicted[i])
                    .thenComparingDouble(i->ps.get(i).epistemicStdDev())
                    .thenComparing(i->group.get(i).policy().id()));
            int hits=0;for(int i=0;i<k;i++)if(actual[order.get(i)]>=.9)hits++;
            OptionalDouble rho=spearman(actual,predicted);
            EvaluationStatus status=n<2?EvaluationStatus.INSUFFICIENT_ROWS:
                    insufficientContextVariation?EvaluationStatus.INSUFFICIENT_CONTEXT_VARIATION:
                    top==0?EvaluationStatus.NO_TOP_DECILE_TARGET:
                    rho.isEmpty()?EvaluationStatus.CONSTANT_RANK:EvaluationStatus.OK;
            metrics.add(new ScenarioEvaluationMetrics(kind,foldId,set,entry.getKey(),n,
                    (int)group.stream().map(r->r.policy().id()).distinct().count(),ae.value()/n,
                    StrictMath.sqrt(se.value()/n),bias.value()/n,rho,top,k,
                    OptionalDouble.of(hits/(double)k),
                    top == 0 ? OptionalDouble.empty() : OptionalDouble.of(hits/(double)top),
                    width.value()/n,coverage/(double)n,epi.value()/n,range.value()/n,status));
            microError.add(ae.value());total+=n;
        }
        if(!predictions.isEmpty())throw new IllegalArgumentException("Extra predictions");
        List<ScenarioEvaluationMetrics> ok=metrics.stream().filter(m->m.status()==EvaluationStatus.OK).toList();
        return new EvaluationSummary(kind,set,metrics,average(ok,ScenarioEvaluationMetrics::mae),
                average(ok,ScenarioEvaluationMetrics::rmse),averageOptional(ok,ScenarioEvaluationMetrics::spearman),
                averageOptional(ok,ScenarioEvaluationMetrics::precisionAtTen),
                averageOptional(ok,ScenarioEvaluationMetrics::recallAtTen),
                ok.isEmpty()?OptionalDouble.empty():OptionalDouble.of(ok.stream().mapToDouble(ScenarioEvaluationMetrics::mae).max().orElseThrow()),
                total==0?OptionalDouble.empty():OptionalDouble.of(microError.value()/total));
    }

    static EvaluationSummary evaluateMatrix(String kind, String foldId, ScenarioFeatureSet set,
            ScenarioLearningMatrix matrix, float[] logits) {
        if (logits.length != matrix.rows() * ScenarioOrdinalTargets.OUTPUT_WIDTH) {
            throw new IllegalArgumentException("Evaluation logits have the wrong shape");
        }
        double[] qualities = matrix.qualities();
        PolicyId[] policies = matrix.policyIds();
        SourceScenario[] scenarios = matrix.scenarios();
        TreeMap<SourceScenario, ArrayList<Integer>> groups = new TreeMap<>();
        for (int row = 0; row < matrix.rows(); row++) {
            groups.computeIfAbsent(scenarios[row], ignored -> new ArrayList<>()).add(row);
        }
        ArrayList<ScenarioEvaluationMetrics> metrics = new ArrayList<>();
        CompensatedSum micro = new CompensatedSum();
        for (Map.Entry<SourceScenario, ArrayList<Integer>> entry : groups.entrySet()) {
            List<Integer> indices = entry.getValue();
            int count = indices.size();
            double[] actual = new double[count];
            double[] predicted = new double[count];
            double[] lows = new double[count];
            double[] highs = new double[count];
            double[] epistemic = new double[count];
            double[] ranges = new double[count];
            PolicyId[] ids = new PolicyId[count];
            for (int index = 0; index < count; index++) {
                int row = indices.get(index);
                double[] rowLogits = new double[9];
                for (int output = 0; output < 9; output++) {
                    rowLogits[output] = logits[row * 9 + output];
                }
                EnsembleOrdinalDistribution distribution = ScenarioOrdinalTargets.combine(
                        List.of(ScenarioOrdinalTargets.decode(rowLogits)));
                actual[index] = qualities[row];
                predicted[index] = distribution.predictedQuality();
                lows[index] = distribution.qualityIntervalLow();
                highs[index] = distribution.qualityIntervalHigh();
                epistemic[index] = distribution.epistemicStdDev();
                ranges[index] = distribution.disagreementRange();
                ids[index] = policies[row];
            }
            ScenarioEvaluationMetrics scenarioMetrics = metrics(kind, foldId, set,
                    entry.getKey(), ids, actual, predicted, lows, highs, epistemic, ranges, false);
            metrics.add(scenarioMetrics);
            micro.add(scenarioMetrics.mae() * scenarioMetrics.rowCount());
        }
        int total = metrics.stream().mapToInt(ScenarioEvaluationMetrics::rowCount).sum();
        return new EvaluationSummary(kind, set, metrics,
                average(metrics, ScenarioEvaluationMetrics::mae),
                average(metrics, ScenarioEvaluationMetrics::rmse),
                averageOptional(metrics, ScenarioEvaluationMetrics::spearman),
                averageOptional(metrics, ScenarioEvaluationMetrics::precisionAtTen),
                averageOptional(metrics, ScenarioEvaluationMetrics::recallAtTen),
                metrics.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(metrics.stream()
                        .mapToDouble(ScenarioEvaluationMetrics::mae).max().orElseThrow()),
                OptionalDouble.of(micro.value() / total));
    }

    static EvaluationSummary summarize(String kind, ScenarioFeatureSet set,
            List<ScenarioEvaluationMetrics> metrics) {
        List<ScenarioEvaluationMetrics> ordered = metrics.stream()
                .sorted(Comparator.comparing(ScenarioEvaluationMetrics::scenario)).toList();
        List<ScenarioEvaluationMetrics> ok = ordered.stream()
                .filter(metric -> metric.status() == EvaluationStatus.OK).toList();
        CompensatedSum micro = new CompensatedSum();
        int rows = 0;
        for (ScenarioEvaluationMetrics metric : ordered) {
            micro.add(metric.mae() * metric.rowCount());
            rows += metric.rowCount();
        }
        return new EvaluationSummary(kind, set, ordered,
                average(ok, ScenarioEvaluationMetrics::mae),
                average(ok, ScenarioEvaluationMetrics::rmse),
                averageOptional(ok, ScenarioEvaluationMetrics::spearman),
                averageOptional(ok, ScenarioEvaluationMetrics::precisionAtTen),
                averageOptional(ok, ScenarioEvaluationMetrics::recallAtTen),
                ok.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(ok.stream()
                        .mapToDouble(ScenarioEvaluationMetrics::mae).max().orElseThrow()),
                rows == 0 ? OptionalDouble.empty() : OptionalDouble.of(micro.value() / rows));
    }

    private static ScenarioEvaluationMetrics metrics(String kind, String foldId,
            ScenarioFeatureSet set, SourceScenario scenario, PolicyId[] ids, double[] actual,
            double[] predicted, double[] lows, double[] highs, double[] epistemic,
            double[] ranges, boolean insufficientContext) {
        int count = actual.length;
        CompensatedSum ae = new CompensatedSum();
        CompensatedSum se = new CompensatedSum();
        CompensatedSum bias = new CompensatedSum();
        CompensatedSum width = new CompensatedSum();
        CompensatedSum epistemicSum = new CompensatedSum();
        CompensatedSum rangeSum = new CompensatedSum();
        int coverage = 0;
        int top = 0;
        for (int index = 0; index < count; index++) {
            double error = predicted[index] - actual[index];
            ae.add(StrictMath.abs(error));
            se.add(error * error);
            bias.add(error);
            width.add(highs[index] - lows[index]);
            if (lows[index] <= actual[index] && actual[index] <= highs[index]) coverage++;
            epistemicSum.add(epistemic[index]);
            rangeSum.add(ranges[index]);
            if (actual[index] >= 0.9) top++;
        }
        int selected = StrictMath.max(1, (int) StrictMath.ceil(0.10 * count));
        ArrayList<Integer> order = new ArrayList<>();
        for (int index = 0; index < count; index++) order.add(index);
        order.sort(Comparator.<Integer>comparingDouble(index -> -predicted[index])
                .thenComparingDouble(index -> epistemic[index])
                .thenComparing(index -> ids[index]));
        int hits = 0;
        for (int index = 0; index < selected; index++) {
            if (actual[order.get(index)] >= 0.9) hits++;
        }
        OptionalDouble correlation = spearman(actual, predicted);
        EvaluationStatus status = count < 2 ? EvaluationStatus.INSUFFICIENT_ROWS
                : insufficientContext ? EvaluationStatus.INSUFFICIENT_CONTEXT_VARIATION
                : top == 0 ? EvaluationStatus.NO_TOP_DECILE_TARGET
                : correlation.isEmpty() ? EvaluationStatus.CONSTANT_RANK : EvaluationStatus.OK;
        return new ScenarioEvaluationMetrics(kind, foldId, set, scenario, count,
                (int) Arrays.stream(ids).distinct().count(), ae.value() / count,
                StrictMath.sqrt(se.value() / count), bias.value() / count, correlation, top,
                selected, OptionalDouble.of(hits / (double) selected),
                top == 0 ? OptionalDouble.empty() : OptionalDouble.of(hits / (double) top),
                width.value() / count, coverage / (double) count,
                epistemicSum.value() / count, rangeSum.value() / count, status);
    }
    private static OptionalDouble spearman(double[] a,double[] b){
        double[] ra=ranks(a),rb=ranks(b);
        CompensatedSum sumA = new CompensatedSum(), sumB = new CompensatedSum();
        for (int i = 0; i < ra.length; i++) { sumA.add(ra[i]); sumB.add(rb[i]); }
        double ma=sumA.value()/ra.length, mb=sumB.value()/rb.length;
        CompensatedSum cov=new CompensatedSum(),va=new CompensatedSum(),vb=new CompensatedSum();
        for(int i=0;i<a.length;i++){double x=ra[i]-ma,y=rb[i]-mb;
            cov.add(x*y);va.add(x*x);vb.add(y*y);}
        return va.value()==0||vb.value()==0?OptionalDouble.empty():
                OptionalDouble.of(cov.value()/StrictMath.sqrt(va.value()*vb.value()));
    }
    private static double[] ranks(double[] values){Integer[] order=new Integer[values.length];
        for(int i=0;i<order.length;i++)order[i]=i;Arrays.sort(order,Comparator.comparingDouble(i->values[i]));
        double[] ranks=new double[values.length];for(int i=0;i<order.length;){int j=i+1;
            while(j<order.length&&Double.compare(values[order[i]],values[order[j]])==0)j++;
            double rank=(i+j-1)/2.0+1;for(int k=i;k<j;k++)ranks[order[k]]=rank;i=j;}return ranks;}
    private static OptionalDouble average(List<ScenarioEvaluationMetrics> rows,
            java.util.function.ToDoubleFunction<ScenarioEvaluationMetrics> fn){
        if(rows.isEmpty())return OptionalDouble.empty();CompensatedSum sum=new CompensatedSum();
        rows.forEach(row->sum.add(fn.applyAsDouble(row)));return OptionalDouble.of(sum.value()/rows.size());}
    private static OptionalDouble averageOptional(List<ScenarioEvaluationMetrics> rows,
            java.util.function.Function<ScenarioEvaluationMetrics,OptionalDouble> fn){
        CompensatedSum sum=new CompensatedSum();int n=0;for(var r:rows){var x=fn.apply(r);
            if(x.isPresent()){sum.add(x.getAsDouble());n++;}}
        return n==0?OptionalDouble.empty():OptionalDouble.of(sum.value()/n);}

    private static final class CompensatedSum {
        private double sum;
        private double correction;
        void add(double value) {
            double next=sum+value;
            correction+=StrictMath.abs(sum)>=StrictMath.abs(value)
                    ?(sum-next)+value:(value-next)+sum;
            sum=next;
        }
        double value(){return sum+correction;}
    }
}
