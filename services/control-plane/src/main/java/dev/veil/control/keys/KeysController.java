package dev.veil.control.keys;

import dev.veil.control.config.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/keys")
public class KeysController {

    private final KeyService keys;

    public KeysController(KeyService keys) {
        this.keys = keys;
    }

    @GetMapping
    public List<KeyService.KeyView> list(HttpServletRequest req) {
        return keys.list(Caller.from(req).orgId());
    }

    /** The response is the only time the key is ever shown. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KeyService.Created create(@Valid @RequestBody CreateKey body, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        return keys.create(c, body.name().trim(), body.teamId(), body.kind() == null ? "API" : body.kind(),
                body.rpmLimit() == null ? 0 : body.rpmLimit(), body.dailyBudgetMicros() == null ? 0 : body.dailyBudgetMicros());
    }

    @PostMapping("/{id}/revoke")
    public KeyService.KeyView revoke(@PathVariable String id, HttpServletRequest req) {
        return keys.revoke(Caller.from(req).requireAdmin(), id);
    }

    @PutMapping("/{id}/limits")
    public KeyService.KeyView limits(@PathVariable String id, @Valid @RequestBody Limits body, HttpServletRequest req) {
        return keys.updateLimits(Caller.from(req).requireAdmin(), id, body.rpmLimit(), body.dailyBudgetMicros());
    }

    public record CreateKey(@NotBlank @Size(max = 80) String name, @NotBlank String teamId, String kind,
                            @Min(0) Integer rpmLimit, @Min(0) Long dailyBudgetMicros) {
    }

    public record Limits(@Min(0) int rpmLimit, @Min(0) long dailyBudgetMicros) {
    }
}
