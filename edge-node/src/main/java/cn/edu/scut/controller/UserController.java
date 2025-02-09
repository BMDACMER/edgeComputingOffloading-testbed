package cn.edu.scut.controller;

import cn.edu.scut.bean.Task;
import cn.edu.scut.service.EdgeNodeSystemService;
import cn.edu.scut.thread.ExecutionRunnable;
import lombok.extern.apachecommons.CommonsLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping(value = "/user", method = {RequestMethod.GET, RequestMethod.POST})
@CommonsLog
public class UserController {
    @Autowired
    EdgeNodeSystemService edgeNodeSystemService;

    List<Integer> waitingTime = new ArrayList<>();

    @Autowired
    RestTemplate restTemplate; 

//    @Value("${env.use-redundancy}")
//    private boolean useRedundancy;

    @PostMapping("/task")
    public String receiveUserTask(@RequestBody Task task) {
        log.info("receive task "+task.getJobId()+" from user");
        task.setArrivalTime(LocalDateTime.now());
        edgeNodeSystemService.processTaskFromUser(task);
//        if (useRedundancy) {
//            edgeNodeSystemService.processRedundantTaskFromUser(task);
//        } else {
//            edgeNodeSystemService.processTaskFromUser(task);
//        }
        return "success";
    }

    @PostMapping("/time")
    public String updateTimeSlot(@RequestBody Integer timeSlot) {
        log.info("The time slot is updated to "+timeSlot);
        if(timeSlot == 1){
            waitingTime.clear();
        }
        waitingTime.add( edgeNodeSystemService.getEdgeNodeSystem().getExecutionQueue().getExecutor().getQueue().size() );


        // edgeNodeSystemService.setTimeSlot(timeSlot);
        return "success";
    }

    @GetMapping("/result")
    public List<Integer> getResult() {
        return waitingTime;
    }
}