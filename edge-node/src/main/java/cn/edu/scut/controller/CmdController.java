package cn.edu.scut.controller;

import cn.edu.scut.service.EdgeNodeSystemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;


@RestController
@RequestMapping(value = "/cmd", method = {RequestMethod.GET, RequestMethod.POST})
@Slf4j
public class CmdController {

    @Resource
    EdgeNodeSystemService edgeNodeSystemService;

    @GetMapping("/init")
    public String init(){
        log.info("edge node system begin to init");
        edgeNodeSystemService.init();
        return "success";
    }
}
