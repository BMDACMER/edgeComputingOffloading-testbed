package cn.edu.scut.util;

import ai.djl.translate.TranslateException;
import cn.edu.scut.runner.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import cn.edu.scut.runner.QwenRunner;

@Component
public class RunningPoint implements IRunner {

    @Lazy
    @Autowired
    private OnlineMARLTrainingRunner onlineMARLTrainingRunner;

    @Lazy
    @Autowired
    private OfflineMARLTrainingRunner offlineMARLTrainingRunner;

    @Lazy
    @Autowired
    private OnlineMARLTestingRunner onlineMARLTestingRunner;

    @Lazy
    @Autowired
    private OnlineHeuristicTestRunner onlineHeuristicTestRunner;

    @Lazy
    @Autowired
    private OnlineHeuristicDataRunner onlineHeuristicDataRunner;

    @Lazy
    @Autowired
    private QwenRunner qwenRunner;

    @Value("${env.runner}")
    private String runningType;

    @Override
    public void run() throws TranslateException {
        switch (runningType) {
            case "rl-online" -> onlineMARLTrainingRunner.run();
            case "rl-offline" -> offlineMARLTrainingRunner.run();
            case "rl-test" -> onlineMARLTestingRunner.run();
            case "heuristic" -> onlineHeuristicTestRunner.run();
            case "heuristic-data" -> onlineHeuristicDataRunner.run();
            case "qwen" -> qwenRunner.run();
            default -> throw new RuntimeException("error in runner type.");
        }
    }
}
