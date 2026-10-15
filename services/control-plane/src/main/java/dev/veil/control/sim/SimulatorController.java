package dev.veil.control.sim;

import dev.veil.control.config.Caller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final TrafficSimulator simulator;

    public SimulatorController(TrafficSimulator simulator) {
        this.simulator = simulator;
    }

    @GetMapping
    public TrafficSimulator.Status status(HttpServletRequest req) {
        Caller.from(req);
        return simulator.status();
    }

    @PostMapping("/start")
    public TrafficSimulator.Status start(@RequestParam(defaultValue = "60") int perMinute, HttpServletRequest req) {
        Caller.from(req).requireAdmin();
        return simulator.start(perMinute);
    }

    @PostMapping("/stop")
    public TrafficSimulator.Status stop(HttpServletRequest req) {
        Caller.from(req).requireAdmin();
        return simulator.stop();
    }
}
