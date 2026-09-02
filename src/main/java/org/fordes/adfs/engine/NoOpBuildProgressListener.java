package org.fordes.adfs.engine;

final class NoOpBuildProgressListener implements BuildProgressListener {

    static final NoOpBuildProgressListener INSTANCE = new NoOpBuildProgressListener();

    private NoOpBuildProgressListener() {
    }

    @Override
    public void stageStarted(Stage stage, long total) {
    }

    @Override
    public void stageAdvanced(
            Stage stage,
            String item,
            long completed,
            long total,
            long processed
    ) {
    }

    @Override
    public void stageCompleted(Stage stage, long completed, long total, long processed) {
    }
}
