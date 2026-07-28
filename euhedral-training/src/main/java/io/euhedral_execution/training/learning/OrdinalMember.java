package io.euhedral_execution.training.learning;
interface OrdinalMember extends AutoCloseable {
    int featureWidth();
    void predictLogits(float[] features, int rows, float[] destination);
    @Override void close();
}
