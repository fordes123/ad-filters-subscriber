package org.fordes.adfs.engine;

/** 接收构建阶段的实时进度；实现必须支持由多个工作线程并发调用。 */
public interface BuildProgressListener {

    void stageStarted(Stage stage, long total);

    void stageAdvanced(
            Stage stage,
            String item,
            long completed,
            long total,
            long processed
    );

    void stageCompleted(Stage stage, long completed, long total, long processed);

    enum Stage {
        SOURCES("读取并解析规则源"),
        DNS_VALIDATION("验证域名有效性"),
        OUTPUTS("转换并生成产物");

        private final String description;

        Stage(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
