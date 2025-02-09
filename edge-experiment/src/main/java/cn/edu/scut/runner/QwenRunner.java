package cn.edu.scut.runner;

import cn.edu.scut.agent.MultiAgentBuffer;
import cn.edu.scut.agent.qwen.QwenAgent;
import cn.edu.scut.service.*;
import cn.edu.scut.util.DateTimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@Lazy
public class QwenRunner implements IRunner {

    @Autowired
    private TaskService taskService;

    @Autowired
    private LinkService linkService;

    @Autowired
    private EdgeNodeService edgeNodeService;

    @Autowired
    private PlotService plotService;

    @Autowired
    private RunnerService runnerService;

    @Autowired
    private QwenAgent qwenAgent;

    @Value("${env.episode-number}")
    private int episodeNumber;

    @Value("${qwen.name}")
    private String name;

    @Value("${env.flag}")
    private String flag;

    @Autowired
    private MultiAgentBuffer buffer;

    public void run() {
        log.info("=============================");
        log.info("run Qwen runner.");
        log.info("=============================");

        linkService.remove(null);
        edgeNodeService.remove(null);
        taskService.remove(null);
        runnerService.init();

        var dateFlag = DateTimeUtils.getFlag();
        var fullFlag = dateFlag + "@" + name + "@" + this.flag;

        var episodes = new ArrayList<Double>();
        var successRates = new ArrayList<Double>();
        
        for (int currentEpisode = 1; currentEpisode <= episodeNumber; currentEpisode++) {
            runnerService.run();
            
            double successRate = taskService.getSuccessRate();
            log.info("current episode: {}, success rate: {}", currentEpisode, successRate);
            
            episodes.add((double) currentEpisode);
            successRates.add(successRate);

            taskService.remove(null);
        }
        
        buffer.save(fullFlag);
        plotService.plot(episodes, successRates, fullFlag);
        var averageSuccessRate = successRates.stream()
                .collect(Collectors.averagingDouble(x -> x));
        log.info("average success rate: {}", averageSuccessRate);
    }

} 